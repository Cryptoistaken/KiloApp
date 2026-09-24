// ponytail: manual bump on any backend route change — lets health checks
// confirm a deploy landed. Lives here (not index.ts) so the edge entry
// (entry.ts, Cloudflare Workers) reports the same version as the Bun server.
export const API_VERSION = "2.0.37";
