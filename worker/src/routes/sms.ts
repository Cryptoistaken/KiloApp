// SMS gateway routes — TypeScript port of `sms core/gateway.go` (+ pool logic
// from `providers.go`). Same paths, same JSON shapes, same status codes:
//   GET  /v1/health   (no auth)    feed/otp cache sizes + refresh timestamps
//   GET  /v1/feed     (app key)    live feed hits, ?since=&limit= (1..50)
//   POST /v1/numbers  (app key)    provision one number for a range pattern
//   GET  /v1/otp      (app key)    delivered OTPs for ?number=&since=
//   GET  /v1/stream   (app key)    SSE: replay + live OTPs + 25s :ping
//   GET  /v1/meta     (app key)    country/service aggregates over the feed
//   GET  /v1/admin/pool            provider slots (key truncated)
//   PUT  /v1/admin/pool            replace enabled set (≥1 known id stays)
//   GET  /v1/admin/stats           counters + caches + pool
//   POST /v1/admin/inject          test-only fake SMS delivery
//
// Edge notes (Cloudflare Workers, no sticky process):
// - Caches are per-isolate memory. Handlers lazily refresh when stale
//   (feed >60s, otps >5s) so ANY isolate serves fresh data; the 1-min cron
//   (entry.ts scheduled) only warms. Upstream polling fans out per isolate
//   under load — acceptable at this scale, same shape as the Go ticker.
// - SSE live pushes reach only same-isolate subscribers; clients resync via
//   replay + ?since= polling on reconnect (the app already polls otp).
// - Admin pool toggles mutate the isolate's pool copy (see smsProviders).

import { Hono } from "hono";
import type { EdgeEnv } from "../lib/edge";
import { edgeStr } from "../lib/edge";
import {
  Classify, CleanSid, AppLabel, MethodLabel, MaskMiddle, IsoFromPrefix, digitsOnly,
} from "../lib/smsText";
import {
  buildPool, poolStatus, setEnabled, pickProvider, provisionNumber,
  fetchFeed, fetchOtps,
  type FeedHit, type OtpHit, type Provider,
} from "../lib/smsProviders";

export const sms = new Hono<{ Bindings: EdgeEnv }>();

// ── isolate-local state (mirrors gateway.go globals) ──
let feedCache: FeedHit[] = [];
let otpCache: OtpHit[] = [];
let injectCache: OtpHit[] = [];
let lastFeedAt = 0;
let lastOtpAt = 0;
let reqTotal = 0;
const reqByRoute = new Map<string, number>();
let pool: Provider[] = [];
let poolSig = "";
let enabled: Record<string, boolean> = {};
const rr = { n: -1 };
const subs = new Set<{ numbers: Set<string>; write: (s: string) => void }>();
let lastPush = 0;
let feedRefreshing: Promise<void> | null = null;
let otpRefreshing: Promise<void> | null = null;

const poolFor = (env: EdgeEnv): void => {
  const sig = ["VOLTX_API_KEY", "MNIT_API_KEY", "MNIT_API_BASE", "ZENEX_API_KEY", "ZENEX_API_BASE", "ZENEX_WEB_BASE"]
    .map((k) => edgeStr(env, k)).join("\n");
  if (sig !== poolSig) {
    const b = buildPool(env);
    pool = b.pool;
    enabled = b.enabled;
    poolSig = sig;
  }
};

// ── refresh (mirrors refreshFeed/refreshOtps; sequential, fail-open) ──
export async function refreshSmsFeed(env: EdgeEnv): Promise<void> {
  if (feedRefreshing) { await feedRefreshing; return; }
  feedRefreshing = (async () => {
    try {
      poolFor(env);
      const now = Date.now();
      const seen = new Map<string, FeedHit>();
      for (const p of pool) {
        for (const h of await fetchFeed(p)) {
          if (h.Time < now - 5 * 60_000) continue;
          const k = `${h.Range}|${h.Message}`;
          const prev = seen.get(k);
          if (!prev || h.Time < prev.Time) seen.set(k, h);
        }
      }
      feedCache = [...seen.values()].sort((a, b) => b.Time - a.Time).slice(0, 200);
      lastFeedAt = now;
    } catch { /* fail-open: keep serving the old cache */ }
  })();
  await feedRefreshing;
  feedRefreshing = null;
}

const otpFrame = (o: OtpHit): string => {
  const { app, code } = Classify(o.Message);
  return `event: otp\ndata: ${JSON.stringify({ number: digitsOnly(o.Number), code, text: o.Message, at: o.Time, app, appLabel: AppLabel(app) })}\n\n`;
};

function pushFreshOtps(hits: OtpHit[]): void {
  const fresh = hits.filter((h) => h.Time > lastPush);
  if (!fresh.length) return;
  lastPush = fresh.reduce((m, h) => Math.max(m, h.Time), lastPush);
  for (const h of fresh) {
    const frame = otpFrame(h);
    const num = digitsOnly(h.Number);
    for (const sub of subs) if (sub.numbers.has(num)) sub.write(frame);
  }
}

export async function refreshSmsOtps(env: EdgeEnv): Promise<void> {
  if (otpRefreshing) { await otpRefreshing; return; }
  otpRefreshing = (async () => {
    try {
      poolFor(env);
      const now = Date.now();
      let kept: OtpHit[] = [];
      for (const p of pool) {
        for (const o of await fetchOtps(p)) {
          if (o.Time >= now - 10 * 60_000) kept.push(o);
        }
      }
      const freshInjects = injectCache.filter((o) => now - o.Time < 10 * 60_000);
      injectCache = freshInjects;
      kept = kept.concat(freshInjects).sort((a, b) => b.Time - a.Time).slice(0, 500);
      otpCache = kept;
      lastOtpAt = now;
      pushFreshOtps(kept);
    } catch { /* fail-open */ }
  })();
  await otpRefreshing;
  otpRefreshing = null;
}

const freshFeed = async (env: EdgeEnv): Promise<void> => {
  if (Date.now() - lastFeedAt > 60_000) await refreshSmsFeed(env);
};
const freshOtps = async (env: EdgeEnv): Promise<void> => {
  if (Date.now() - lastOtpAt > 5_000) await refreshSmsOtps(env);
};

// ── auth (mirrors bearer/needApp/needAdmin; counted before auth like wrap) ──
sms.use("/*", async (c, next) => {
  reqTotal++;
  reqByRoute.set(c.req.path, (reqByRoute.get(c.req.path) ?? 0) + 1);
  await next();
});

const bearerOf = (c: { req: { header: (n: string) => string | undefined } }): string => {
  const h = c.req.header("Authorization") || "";
  const m = /^bearer\s+(.*)$/i.exec(h);
  return (m ? m[1] : "").trim();
};
const needApp = (c: { req: { header: (n: string) => string | undefined }; env: EdgeEnv; json: (o: unknown, s: number) => Response }): Response | null => {
  const key = edgeStr(c.env, "KILO_API_KEY");
  if (key !== "" && bearerOf(c) !== key) return c.json({ ok: false, error: "bad api key" }, 401);
  return null;
};
const needAdmin = (c: { req: { header: (n: string) => string | undefined }; env: EdgeEnv; json: (o: unknown, s: number) => Response }): Response | null => {
  const key = edgeStr(c.env, "ADMIN_KEY");
  if (key !== "" && bearerOf(c) !== key) return c.json({ ok: false, error: "bad admin key" }, 401);
  return null;
};

// ── routes ──
sms.get("/health", (c) => c.json({ ok: true, feed: feedCache.length, otps: otpCache.length, lastFeedAt, lastOtpAt }));

sms.get("/feed", async (c) => {
  const deny = needApp(c);
  if (deny) return deny;
  await freshFeed(c.env);
  const qSince = parseInt(c.req.query("since") ?? "", 10);
  const since = Number.isFinite(qSince) ? qSince : 0;
  let limit = parseInt(c.req.query("limit") ?? "", 10);
  if (!Number.isFinite(limit)) limit = 0;
  if (limit < 1 || limit > 50) limit = 20;
  const items: Record<string, unknown>[] = [];
  for (const h of feedCache) {
    if (h.Time <= since) continue;
    let app = h.App, method = h.Method, code = "";
    const cls = Classify(h.Message);
    if (cls.code !== "") {
      code = cls.code;
      if (h.Method === "") method = cls.method;
    }
    if (h.App === "") app = Classify(h.Message).app;
    let svc = CleanSid(h.Sid, h.Message);
    if (!svc) svc = "SMS";
    items.push({
      masked: MaskMiddle(h.Range), svc, method, app,
      appLabel: AppLabel(app), methodLabel: MethodLabel(method),
      iso: IsoFromPrefix(digitsOnly(h.Range)),
      code, msg: h.Message, range: h.Range, at: h.Time,
    });
    if (items.length >= limit) break;
  }
  return c.json({ ok: true, items, now: Date.now() });
});

sms.post("/numbers", async (c) => {
  const deny = needApp(c);
  if (deny) return deny;
  let body: { range?: unknown } = {};
  try { body = await c.req.json(); } catch { body = {}; }
  let rng = String(body.range ?? "").replace(/[^0-9X]/g, "").toUpperCase();
  if (rng.length > 15) rng = rng.slice(0, 15);
  if (rng.length < 3) return c.json({ ok: false, error: "range like 229016XXX required" }, 400);
  poolFor(c.env);
  const prov = pickProvider(pool, enabled, rr);
  const data = await provisionNumber(prov, rng);
  if (!data || !data.NoPlus) return c.json({ ok: false, error: "provider returned nothing, retry" }, 502);
  const full = data.NoPlus.replace(/^\+/, "");
  let disp = "";
  for (let i = 0; i < full.length; i++) {
    if (i > 0 && i % 3 === 0) disp += " ";
    disp += full[i];
  }
  return c.json({
    ok: true,
    number: {
      full, display: "+" + disp, country: data.Country || "Unknown",
      range: rng, expires_in: 420, provider: prov ? prov.ID : "",
    },
    now: Date.now(),
  });
});

sms.get("/otp", async (c) => {
  const deny = needApp(c);
  if (deny) return deny;
  const number = digitsOnly((c.req.query("number") || "").replace(/^\+/, ""));
  if (!number) return c.json({ ok: false, error: "number required" }, 400);
  const qSince = parseInt(c.req.query("since") ?? "", 10);
  const since = Number.isFinite(qSince) ? qSince : 0;
  await freshOtps(c.env);
  const msgs: Record<string, unknown>[] = [];
  for (const o of otpCache) {
    if (digitsOnly(o.Number) !== number || o.Time <= since) continue;
    const { app, code } = Classify(o.Message);
    msgs.push({ code, text: o.Message, at: o.Time, app, appLabel: AppLabel(app) });
  }
  msgs.sort((a, b) => Number(a["at"]) - Number(b["at"]));
  return c.json({ ok: true, code: msgs.length ? msgs[msgs.length - 1]["code"] : null, msgs, now: Date.now() });
});

sms.get("/stream", async (c) => {
  const deny = needApp(c);
  if (deny) return deny;
  await freshOtps(c.env);
  const numbers = (c.req.query("numbers") || "").split(",");
  const replay: OtpHit[] = [];
  for (const o of otpCache) {
    for (const n of numbers) {
      if (n !== "" && digitsOnly(o.Number) === digitsOnly(n)) replay.push(o);
    }
  }
  replay.sort((a, b) => a.Time - b.Time);
  let timer: ReturnType<typeof setInterval> | undefined;
  let sub: { numbers: Set<string>; write: (s: string) => void } | undefined;
  const stream = new ReadableStream({
    start(controller) {
      const enc = new TextEncoder();
      const send = (s: string) => {
        try {
          if ((controller.desiredSize ?? 1) <= 0) return; // drop on slow reader (chan cap 8)
          controller.enqueue(enc.encode(s));
        } catch { /* closed */ }
      };
      for (const o of replay) send(otpFrame(o));
      sub = { numbers: new Set(numbers.map((n) => digitsOnly(n)).filter((n) => n !== "")), write: send };
      subs.add(sub);
      timer = setInterval(() => send(":ping\n\n"), 25000);
      const done = () => {
        clearInterval(timer);
        if (sub) subs.delete(sub);
        try { controller.close(); } catch { /* already closed */ }
      };
      const signal = c.req.raw.signal;
      if (signal.aborted) done();
      else signal.addEventListener("abort", done, { once: true });
    },
    cancel() {
      clearInterval(timer);
      if (sub) subs.delete(sub);
    },
  });
  return new Response(stream, { headers: { "Content-Type": "text/event-stream", "Cache-Control": "no-cache", "X-Accel-Buffering": "no" } });
});

sms.get("/meta", async (c) => {
  const deny = needApp(c);
  if (deny) return deny;
  await freshFeed(c.env);
  const cm = new Map<string, { prefix: string; count: number; services: Set<string>; ranges: Map<string, number> }>();
  const svcCount = new Map<string, number>();
  for (const h of feedCache) {
    if (!h.Range) continue;
    const p3 = h.Range.length > 3 ? h.Range.slice(0, 3) : h.Range;
    let a = cm.get(p3);
    if (!a) { a = { prefix: p3, count: 0, services: new Set(), ranges: new Map() }; cm.set(p3, a); }
    a.count++;
    a.ranges.set(h.Range, (a.ranges.get(h.Range) ?? 0) + 1);
    if (h.Sid !== "") {
      const svc = h.Sid.toUpperCase().trim();
      a.services.add(svc);
      svcCount.set(svc, (svcCount.get(svc) ?? 0) + 1);
    }
  }
  const countries = [...cm.values()].map((a) => {
    let topRange = "", topN = 0;
    for (const [r, n] of a.ranges) if (n > topN) { topRange = r; topN = n; }
    return { prefix: a.prefix, count: a.count, services: [...a.services].sort(), range: topRange };
  }).sort((x, y) => y.count - x.count);
  const services = [...svcCount.entries()].map(([name, count]) => ({ name, count }))
    .sort((x, y) => y.count - x.count).slice(0, 20);
  return c.json({ ok: true, countries, services, now: Date.now() });
});

sms.get("/admin/pool", (c) => {
  const deny = needAdmin(c);
  if (deny) return deny;
  poolFor(c.env);
  return c.json({ ok: true, pool: poolStatus(pool, enabled) });
});

sms.put("/admin/pool", async (c) => {
  const deny = needAdmin(c);
  if (deny) return deny;
  let body: { enabled?: unknown } = {};
  try { body = await c.req.json(); } catch { body = {}; }
  poolFor(c.env);
  const err = setEnabled(pool, enabled, Array.isArray(body.enabled) ? (body.enabled as unknown[]).map(String) : []);
  if (err) return c.json({ ok: false, error: err }, 400);
  return c.json({ ok: true, pool: poolStatus(pool, enabled) });
});

sms.get("/admin/stats", (c) => {
  const deny = needAdmin(c);
  if (deny) return deny;
  poolFor(c.env);
  return c.json({
    ok: true, reqTotal, reqByRoute: Object.fromEntries(reqByRoute),
    feed: feedCache.length, otps: otpCache.length, lastFeedAt, lastOtpAt,
    pool: poolStatus(pool, enabled),
  });
});

sms.post("/admin/inject", async (c) => {
  const deny = needAdmin(c);
  if (deny) return deny;
  let body: { number?: unknown; code?: unknown; text?: unknown };
  try {
    body = await c.req.json();
  } catch {
    return c.json({ ok: false, error: "bad json" }, 400);
  }
  const number = digitsOnly(String(body?.number ?? "").trim().replace(/^\+/, ""));
  if (!number) return c.json({ ok: false, error: "number required" }, 400);
  let text = String(body?.text ?? "").trim();
  let code = String(body?.code ?? "").trim();
  if (!text && code) text = `${code} is your Facebook confirmation code`;
  if (!code) code = Classify(text).code;
  const hit: OtpHit = { Number: number, Message: text, Time: Date.now(), Provider: "inject" };
  injectCache.push(hit);
  otpCache.unshift(hit);
  if (otpCache.length > 500) otpCache = otpCache.slice(0, 500);
  pushFreshOtps([hit]);
  return c.json({ ok: true, number, code });
});
