// Upstream SMS provider pool — port of `sms core/providers.go`.
// Sole upstream contact (VoltX/MNIT/Zenex). Plain fetch, no TCP, no local
// state except the round-robin cursor: safe on Workers. The pool itself is
// rebuilt from env per call (cheap); callers keep one instance per isolate.

import { Classify, CleanSid, toInt64 } from "./smsText";
import type { EdgeEnv } from "./edge";
import { edgeStr } from "./edge";

export interface Provider {
  ID: string; Name: string; Kind: "volt" | "zenex";
  Base: string; WebBase: string; AuthHeader: string; Key: string;
}
export interface FeedHit {
  Message: string; Range: string; Sid: string; Time: number;
  App: string; Method: string; Provider: string;
}
export interface OtpHit { Number: string; Message: string; Time: number; Provider: string }
export interface NumberData { NoPlus: string; Full: string; Country: string }

async function postJSON(url: string, header: string, key: string, body: unknown): Promise<Record<string, unknown>> {
  try {
    const res = await fetch(url, {
      method: "POST",
      headers: { "Content-Type": "application/json", [header]: key },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(15000),
    });
    const out = await res.json().catch(() => null);
    return out && typeof out === "object" ? (out as Record<string, unknown>) : {};
  } catch {
    return {};
  }
}

async function getJSON(url: string, header: string, key: string): Promise<Record<string, unknown>> {
  try {
    const headers: Record<string, string> = { "Content-Type": "application/json" };
    if (header !== "") headers[header] = key;
    const res = await fetch(url, { headers, signal: AbortSignal.timeout(15000) });
    const out = await res.json().catch(() => null);
    return out && typeof out === "object" ? (out as Record<string, unknown>) : {};
  } catch {
    return {};
  }
}

const str = (m: unknown, k: string): string => {
  const v = (m as Record<string, unknown> | null)?.[k];
  return typeof v === "string" ? v : "";
};
const metaCode = (r: Record<string, unknown>): number => {
  const m = r["meta"];
  if (m && typeof m === "object" && typeof (m as Record<string, unknown>)["code"] === "number") {
    return (m as Record<string, unknown>)["code"] as number;
  }
  return 0;
};

const csv = (v: string): string[] => String(v ?? "").split(",").map((s) => s.trim()).filter(Boolean);

/** Build the pool from env CSVs (mirrors InitPool). One slot per key. */
export function buildPool(env: EdgeEnv): { pool: Provider[]; enabled: Record<string, boolean> } {
  const pool: Provider[] = [];
  const countByName = (name: string) => pool.filter((p) => p.Name === name).length;
  for (const k of csv(edgeStr(env, "VOLTX_API_KEY"))) {
    pool.push({ ID: `voltx:${countByName("voltx")}`, Name: "voltx", Kind: "volt", Base: "https://api.2oo9.cloud/MXS47FLFX0U/tnevs/@public/api", WebBase: "", AuthHeader: "mauthapi", Key: k });
  }
  const mnitBase = edgeStr(env, "MNIT_API_BASE") || "https://api.2oo9.cloud/MXS47FLFX0U/tnemn/@public/api";
  for (const k of csv(edgeStr(env, "MNIT_API_KEY"))) {
    pool.push({ ID: `mnit:${countByName("mnit")}`, Name: "mnit", Kind: "volt", Base: mnitBase, WebBase: "", AuthHeader: "mauthapi", Key: k });
  }
  const zenBase = edgeStr(env, "ZENEX_API_BASE") || "https://api.zenexnetwork.com";
  const zenWeb = edgeStr(env, "ZENEX_WEB_BASE") || "https://www.zenexnetwork.com";
  for (const k of csv(edgeStr(env, "ZENEX_API_KEY"))) {
    pool.push({ ID: `zenex:${countByName("zenex")}`, Name: "zenex", Kind: "zenex", Base: zenBase, WebBase: zenWeb, AuthHeader: "mapikey", Key: k });
  }
  const enabled: Record<string, boolean> = {};
  for (const p of pool) enabled[p.ID] = true;
  return { pool, enabled };
}

export const poolStatus = (pool: Provider[], enabled: Record<string, boolean>) =>
  pool.map((p) => {
    const key6 = p.Key.length > 6 ? p.Key.slice(0, 6) + "..." : p.Key;
    return { id: p.ID, name: p.Name, kind: p.Kind, enabled: enabled[p.ID], key6 };
  });

/** Replace the enabled set; at least one known id must remain. */
export function setEnabled(pool: Provider[], enabled: Record<string, boolean>, ids: string[]): string | null {
  const known = new Set(pool.map((p) => p.ID));
  const next: Record<string, boolean> = {};
  for (const id of ids) if (known.has(id)) next[id] = true;
  if (Object.keys(next).length === 0) return "at least one pool entry must stay enabled";
  for (const k of Object.keys(enabled)) delete enabled[k];
  Object.assign(enabled, next);
  return null;
}

const enabledList = (pool: Provider[], enabled: Record<string, boolean>): Provider[] => {
  const list = pool.filter((p) => enabled[p.ID]);
  if (list.length === 0 && pool.length > 0) return pool.slice(0, 1);
  return list;
};

/** Round-robin over enabled slots. Single attempt, no failover (mirrors Go). */
export function pickProvider(pool: Provider[], enabled: Record<string, boolean>, rr: { n: number }): Provider | null {
  const list = enabledList(pool, enabled);
  if (!list.length) return null;
  if (list.length === 1) return list[0];
  rr.n = (rr.n + 1) % list.length;
  return list[rr.n];
}

export async function provisionNumber(prov: Provider | null, rng: string): Promise<NumberData | null> {
  if (!prov) return null;
  if (prov.Kind === "zenex") {
    const r = await postJSON(`${prov.Base}/v1/getnum`, prov.AuthHeader, prov.Key, { range: rng, is_national: false, remove_plus: false });
    const d = r["data"];
    if (!d || typeof d !== "object") return null;
    const meta = r["meta"];
    if (meta && typeof meta === "object") {
      const c = (meta as Record<string, unknown>)["code"];
      if (typeof c === "number" && c !== 200 && str(meta, "status") !== "success") return null;
    }
    let full = str(d, "full_number");
    if (!full) full = str(d, "number");
    return { NoPlus: full.replace(/^\+/, ""), Full: full, Country: str(d, "country") };
  }
  const rid = rng.replace(/[xX]/g, "");
  const r = await postJSON(`${prov.Base}/getnum`, prov.AuthHeader, prov.Key, { rid });
  if (metaCode(r) !== 200) return null;
  const d = r["data"];
  if (!d || typeof d !== "object") return null;
  return { NoPlus: str(d, "no_plus_number"), Full: str(d, "full_number"), Country: str(d, "country") };
}

export async function fetchFeed(prov: Provider): Promise<FeedHit[]> {
  try {
    if (prov.Kind === "zenex") {
      const d = await getJSON(`${prov.WebBase}/api/v1/global-broadcast`, prov.AuthHeader, prov.Key);
      const data = d["data"];
      const arr = data && typeof data === "object" ? ((data as Record<string, unknown>)["data"] as unknown[]) : null;
      const out: FeedHit[] = [];
      for (const it of Array.isArray(arr) ? arr : []) {
        if (!it || typeof it !== "object") continue;
        const m = it as Record<string, unknown>;
        const otp = str(m, "otp");
        const { app, method } = Classify(otp);
        let svc = str(m, "service").toUpperCase().trim();
        if (!svc) svc = "SMS";
        let tm = toInt64(m["time"]);
        if (!tm) tm = Date.now();
        out.push({ Message: otp, Range: str(m, "number"), Sid: CleanSid(svc, otp), Time: tm, App: app, Method: method, Provider: prov.ID });
      }
      return out;
    }
    const con = await getJSON(`${prov.Base}/console`, prov.AuthHeader, prov.Key);
    const data = con["data"];
    const arr = data && typeof data === "object" ? ((data as Record<string, unknown>)["hits"] as unknown[]) : null;
    const out: FeedHit[] = [];
    for (const it of Array.isArray(arr) ? arr : []) {
      if (!it || typeof it !== "object") continue;
      const m = it as Record<string, unknown>;
      const { app, method } = Classify(str(m, "message"));
      out.push({ Message: str(m, "message"), Range: str(m, "range"), Sid: CleanSid(str(m, "sid"), str(m, "message")), Time: toInt64(m["time"]), App: app, Method: method, Provider: prov.ID });
    }
    return out;
  } catch {
    return [];
  }
}

export async function fetchOtps(prov: Provider): Promise<OtpHit[]> {
  try {
    if (prov.Kind === "zenex") {
      const d = await getJSON(`${prov.Base}/v1/numsuccess/info`, prov.AuthHeader, prov.Key);
      const data = d["data"];
      const arr = data && typeof data === "object" ? ((data as Record<string, unknown>)["otps"] as unknown[]) : null;
      const out: OtpHit[] = [];
      for (const it of Array.isArray(arr) ? arr : []) {
        if (!it || typeof it !== "object") continue;
        const m = it as Record<string, unknown>;
        out.push({ Number: str(m, "number").replace(/^\+/, ""), Message: str(m, "otp"), Time: toInt64(m["created_at"]), Provider: prov.ID });
      }
      return out;
    }
    const d = await getJSON(`${prov.Base}/success-otp`, prov.AuthHeader, prov.Key);
    const data = d["data"];
    const arr = data && typeof data === "object" ? ((data as Record<string, unknown>)["otps"] as unknown[]) : null;
    const out: OtpHit[] = [];
    for (const it of Array.isArray(arr) ? arr : []) {
      if (!it || typeof it !== "object") continue;
      const m = it as Record<string, unknown>;
      out.push({ Number: str(m, "number"), Message: str(m, "message"), Time: toInt64(m["time"]), Provider: prov.ID });
    }
    return out;
  } catch {
    return [];
  }
}
