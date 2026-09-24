import { Hono } from "hono";
import { sms, refreshSmsFeed } from "./routes/sms";
import { reads } from "./routes/reads";
import type { EdgeEnv } from "./lib/edge";

// Edge entry (Cloudflare Workers) — stage 1: stateless + safe reads.
// Mounts ONLY Workers-safe routers: nothing here may statically import
// postgres, redis, bun, or node:. Writes stay on the Railway/Bun backend
// (src/server.ts) until the transactional ops are rewritten.
const app = new Hono<{ Bindings: EdgeEnv }>();

app.use("/v1/*", async (c, next) => {
  const origin = c.req.header("Origin") || "";
  c.header("Vary", "Origin");
  const allowed = [c.env.FRONTEND_URL, "https://sheetsubmit.pages.dev", "http://localhost:5173", "http://127.0.0.1:5173"].filter(Boolean) as string[];
  if (origin && allowed.includes(origin)) {
    c.header("Access-Control-Allow-Origin", origin);
    c.header("Access-Control-Allow-Credentials", "true");
    c.header("Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,OPTIONS");
    c.header("Access-Control-Allow-Headers", "Content-Type,Authorization");
    c.header("Access-Control-Max-Age", "86400");
  }
  if (c.req.method === "OPTIONS") return new Response(null, { status: 204, headers: c.res.headers });
  return next();
});
app.use("/api/*", async (c, next) => {
  const origin = c.req.header("Origin") || "";
  c.header("Vary", "Origin");
  const allowed = [c.env.FRONTEND_URL, "https://sheetsubmit.pages.dev", "http://localhost:5173", "http://127.0.0.1:5173"].filter(Boolean) as string[];
  if (origin && allowed.includes(origin)) {
    c.header("Access-Control-Allow-Origin", origin);
    c.header("Access-Control-Allow-Credentials", "true");
  }
  return next();
});

app.route("/v1", sms);
app.route("/", reads);

app.notFound((c) => {
  if (c.req.path.startsWith("/v1/")) return c.json({ ok: false, error: "unknown route" }, 404);
  return c.json({ error: "not found" }, 404);
});

// Minimal Workers runtime shapes (avoids a @cloudflare/workers-types dep
// for two names; wrangler's esbuild strips types without checking them).
interface ScheduledEvent {
  cron: string;
  scheduledTime: number;
}
interface ExecutionContext {
  waitUntil(promise: Promise<unknown>): void;
  passThroughOnException(): void;
}

export default {
  async fetch(request: Request, env: EdgeEnv): Promise<Response> {
    return app.fetch(request, env);
  },
  // 1-min cron: warm the SMS feed cache (per isolate; handlers also refresh
  // lazily when stale, so any isolate serves fresh data).
  async scheduled(_event: ScheduledEvent, env: EdgeEnv, ctx: ExecutionContext): Promise<void> {
    ctx.waitUntil(refreshSmsFeed(env).catch(() => {}));
  },
};
