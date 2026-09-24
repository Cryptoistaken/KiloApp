// Safe read paths for the edge entry — single-statement SELECTs only,
// served over the Neon HTTP driver (lib/db-http). No db.begin anywhere:
// every op here is one round trip, so nothing needs a transaction.
// Writes (persist/append/pool/wallet) stay on the Railway/Bun backend.
import { Hono } from "hono";
import type { EdgeEnv } from "../lib/edge";
import { edgeStr } from "../lib/edge";
import { verifySession } from "../lib/sessionCrypto";
import { getSql, json } from "../lib/db-http";
import { API_VERSION } from "../lib/version";

export const reads = new Hono<{ Bindings: EdgeEnv; Variables: { uid: string } }>();

type Row = Record<string, string | null | undefined>;
const poolId = (r: Row): string =>
  String(r.uid || (String(r.cookies || "").match(/c_user=(\d+)/)?.[1] || ""));

// ponytail: mirrors requireAuth's DB checks (session row + user row + ban)
// without rpc/pg. 30s per-token memory cache like AUTH_CACHE in session.ts.
const authCache = new Map<string, { uid: string; exp: number }>();
async function edgeAuth(c: {
  req: { header: (n: string) => string | undefined };
  env: EdgeEnv; json: (o: unknown, s: number) => Response;
  set: (k: string, v: string) => void;
}): Promise<string | Response> {
  const secret = edgeStr(c.env, "SESSION_SECRET");
  if (!secret) return c.json({ error: "Server configuration error" }, 500);
  const token = c.req.header("Cookie")?.match(/(?:^|;\s*)ss_session=([^;]+)/)?.[1];
  if (!token) return c.json({ error: "Not authenticated" }, 401);
  let session: { uid: string } | null = null;
  try {
    session = await verifySession(token, secret);
  } catch {
    return c.json({ error: "Not authenticated" }, 401);
  }
  if (!session) return c.json({ error: "Not authenticated" }, 401);
  const hit = authCache.get(token);
  if (hit && hit.exp > Date.now()) {
    c.set("uid", hit.uid);
    return hit.uid;
  }
  const databaseUrl = edgeStr(c.env, "DATABASE_URL");
  if (!databaseUrl) return c.json({ error: "Server configuration error" }, 500);
  const sql = getSql(databaseUrl);
  let dbSession: unknown;
  let user: unknown;
  try {
    dbSession = (await sql`SELECT * FROM sessions WHERE token=${token} AND exp>${Date.now()}`)[0] ?? null;
    if (!dbSession) return c.json({ error: "Not authenticated" }, 401);
    user = (await sql`SELECT * FROM users WHERE user_id=${session.uid}`)[0] ?? null;
    if (!user) return c.json({ error: "Not authenticated" }, 401);
    if ((user as Record<string, unknown>)["banned"]) return c.json({ error: "account banned" }, 403);
  } catch {
    return c.json({ error: "Not authenticated" }, 401);
  }
  authCache.set(token, { uid: session.uid, exp: Date.now() + 30_000 });
  if (authCache.size > 1000) {
    for (const [k, v] of authCache) if (v.exp <= Date.now()) authCache.delete(k);
  }
  c.set("uid", session.uid);
  return session.uid;
}

reads.use("/api/*", async (c, next) => {
  const uid = await edgeAuth(c);
  if (typeof uid !== "string") return uid;
  await next();
});

reads.get("/api/health", (c) => c.json({ ok: true, ts: Date.now(), version: API_VERSION }));

reads.get("/api/wallet/balance", async (c) => {
  const uid = c.get("uid");
  const sql = getSql(edgeStr(c.env, "DATABASE_URL"));
  const r = (await sql`SELECT balance FROM wallets WHERE user_id=${uid}`)[0] as { balance?: unknown } | undefined;
  return c.json({ uid, balance: r ? Number(r.balance) : 0 });
});

reads.get("/api/files", async (c) => {
  const uid = c.get("uid");
  const sql = getSql(edgeStr(c.env, "DATABASE_URL"));
  const rows = (await sql`SELECT data FROM file_index WHERE owner_id=${uid} AND archived=false`) as { data: unknown }[];
  return c.json(rows.map((r) => json(r.data)));
});

const applyHoldFlags = (row: Row, s: { hold?: boolean; approved?: boolean; dead?: boolean } | undefined): void => {
  if (!s) return;
  if (s.dead) (row as Record<string, unknown>)["_dead"] = true;
  if (s.hold) (row as Record<string, unknown>)["_hold"] = true;
  else if (s.approved) (row as Record<string, unknown>)["_approved"] = true;
};

reads.get("/api/files/:id/full", async (c) => {
  const uid = c.get("uid");
  const id = c.req.param("id");
  const sql = getSql(edgeStr(c.env, "DATABASE_URL"));
  const found = (await sql`SELECT data,owner_id,archived FROM file_index WHERE file_id=${id}`)[0] as
    | { data: unknown; owner_id: string; archived: boolean } | undefined;
  if (!found || found.owner_id !== uid || found.archived) return c.json({ error: "file not found" }, 404);
  const file = json(found.data) as Record<string, unknown>;
  const rows = ((await sql`SELECT data FROM file_rows WHERE file_id=${id} ORDER BY idx`) as { data: unknown }[])
    .map((r) => json(r.data) as Row);
  const meta = (await sql`SELECT seq FROM file_meta WHERE file_id=${id}`)[0] as { seq?: unknown } | undefined;
  const password = typeof file["password"] === "string" ? (file["password"] as string) : undefined;
  if (password && rows.length) {
    const keys = [...new Set(rows.map(poolId).filter(Boolean))];
    if (keys.length) {
      const states = (await sql`SELECT row_key,bool_or(state='held') hold,bool_or(state='claimed' AND hold_id IS NOT NULL) approved,bool_or(state='dead') dead FROM pool_rows WHERE password=${password} AND row_key = ANY(${keys}) GROUP BY row_key`) as {
        row_key: string; hold: boolean; approved: boolean; dead: boolean;
      }[];
      const map = new Map(states.map((s) => [s.row_key, s]));
      for (const row of rows) {
        const s = map.get(poolId(row));
        if (s) applyHoldFlags(row, { hold: !!s.hold, approved: !!s.approved, dead: !!s.dead });
      }
    }
  }
  return c.json({ file, rows, seq: Number(meta?.seq || 0) });
});
