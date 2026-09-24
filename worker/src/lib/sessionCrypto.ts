// Session crypto — pure WebCrypto, no DB, no node:, no Bun. Split out of
// session.ts so the edge entry (Cloudflare Workers) can verify ss_session
// cookies without dragging the postgres/redis-backed rpc() along.
// session.ts re-exports these; behavior is unchanged on both runtimes.

const enc = new TextEncoder();
const b64 = (v: ArrayBuffer | string) => btoa(typeof v === "string" ? v : String.fromCharCode(...new Uint8Array(v))).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
const unb64 = (v: string) => atob(v.replace(/-/g, "+").replace(/_/g, "/") + "===".slice((v.length + 3) % 4));
async function key(secret: string, usage: ("sign" | "verify")[]) { if (!secret) throw new Error("SESSION_SECRET missing"); return crypto.subtle.importKey("raw", enc.encode(secret), { name: "HMAC", hash: "SHA-256" }, false, usage); }
export async function signSession(uid: string, secret: string) { if (!secret) throw new Error("SESSION_SECRET missing"); const body = b64(JSON.stringify({ uid, exp: Date.now() + 30 * 86400000 })); const sig = b64(await crypto.subtle.sign("HMAC", await key(secret, ["sign"]), enc.encode(body))); return `${body}.${sig}`; }
export async function verifySession(token: string, secret: string) { if (!secret) throw new Error("SESSION_SECRET missing"); const [body, sig] = token.split("."); if (!body || !sig) return null; try { const valid = await crypto.subtle.verify("HMAC", await key(secret, ["verify"]), Uint8Array.from(unb64(sig), (c) => c.charCodeAt(0)), enc.encode(body)); if (!valid) return null; } catch { return null; } try { const data = JSON.parse(unb64(body)); return data.exp > Date.now() ? { uid: String(data.uid) } : null; } catch { return null; } }
// ponytail: direct cross-origin calls (pages.dev → edge/backend) need SameSite=None (Lax cookies never ride fetch); None requires Secure, so http local dev keeps Lax
export function cookie(token: string, maxAge = 2592000, secure = true) { return `ss_session=${token}; Path=/; HttpOnly;${secure ? " Secure; SameSite=None" : " SameSite=Lax"}; Max-Age=${maxAge}`; }
