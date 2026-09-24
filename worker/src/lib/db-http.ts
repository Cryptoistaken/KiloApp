// Neon HTTP database handle for the edge entry — reads only in stage 1.
// postgres.js (TCP) cannot run on Workers; @neondatabase/serverless speaks
// HTTPS, so a lazy per-isolate singleton is enough (no connection to hold).
// Writes stay on the Railway/Bun backend until the transactional ops are
// rewritten (db.begin has no HTTP equivalent) — see plan.md section E.

import { neon, type NeonQueryFunction } from "@neondatabase/serverless";

let client: NeonQueryFunction<false, false> | null = null;
let clientUrl = "";

/** Tagged-template SQL client for a DATABASE_URL (cached per isolate). */
export function getSql(databaseUrl: string): NeonQueryFunction<false, false> {
  if (!client || clientUrl !== databaseUrl) {
    client = neon(databaseUrl);
    clientUrl = databaseUrl;
  }
  return client;
}

/** Defensive JSON decode: neon returns jsonb parsed, TEXT stays string. */
export const json = (v: unknown): unknown =>
  v == null ? null : typeof v === "string" ? JSON.parse(v as string) : v;
