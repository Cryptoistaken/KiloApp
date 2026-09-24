import type { Env } from "./shared";

/** Edge (Cloudflare Workers) bindings: same shape as Env plus secret/var
 * passthrough. Workers has no Bun.env — every secret arrives per-request
 * via c.env, so edge routers use this instead of Env. */
export type EdgeEnv = Env & { [key: string]: string | undefined };

export const edgeStr = (env: EdgeEnv, key: string): string =>
  typeof env[key] === "string" ? (env[key] as string) : "";
