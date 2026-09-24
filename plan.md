# Sheet DB hardening + smart data plan

Status: proposed (1 helper `isEmptyRow()` landed in `SheetModels.kt`, rest pending; edge stage 1 landed in `worker/`: sms `/v1/*` + `/v1/check` + safe reads on Workers via Neon HTTP, writes stay on Railway — `wrangler.toml` + `src/entry.ts`).
Skills read: `durable-objects`, `workers-best-practices`, `tdd`, `github-actions-hardening`.

## A. SheetModels.kt — single homes
- `isEmptyRow()` (done): raw emptiness for trimming, wider than `isData()`.
- `effectiveUid(row) = uid.ifEmpty { extractCUser(cookies) }`; replace 4 inline
  `Regex("c_user=(\\d+)")` copies in `SheetStore.runCheck` (x2), paste/uid paths.

## B. SheetDb.kt (no version bump, no schema change)
1. `onConfigure` → `enableWriteAheadLogging()` (readers never block writer).
2. `recordOp` → prune `outbox` to newest 500 (`journal` already 200/file).
3. `saveAllRows` → store prefix `0..lastData` only; empty file stores 0 rows.
4. `insertUndo/insertRedo` cap 50 → 20.
5. `loadUndoStack/loadRedoStack` → `ORDER BY id DESC LIMIT n` + reverse to
   chronological (fixes oversize tables returning oldest); default limit 20.

## C. SheetStore.kt
1. `UNDO_DEPTH = 20`; `rowsToJson` trims trailing empties (old 500-entry JSON
   still parses; `topUp()` re-pads on open).
2. `pushUndo(cell?)` coalescing: same cell within 3s keeps top entry (typing
   burst = 1 undo); paste/clear/structural/check ops force-push. Trackers reset
   on `open`/`close`.
3. Identity remap instead of wipe: `deleteDeadRows`/`compactRows`/
   `restoreSnapshot` re-key `openChecks`/`openCheckReqs` by `cookies + uid`
   onto new `rowIdx`; unmatched dropped. No UUID migration.
4. `persistRows` stays the single-tx batch (rows + snapshot + file + op +
   stale-check delete); remapped checks saved in trailing tx.
5. Empty `catch {}` → `Log.w(TAG, …)` with fileId/op (no silent swallowing).

## D. SheetXlsx.kt (export only)
- Skip empty cells (sparse, `r`-addressed); numeric `uid` (`^\d+$`) as `<v>`;
  cookies/2fa/headers stay `inlineStr`.

## Risks / notes
- Counts (`countDataRows`, `countDups`) use `WHERE`, unaffected by fewer stored rows.
- Remap relies on in-file uniqueness (already enforced at entry via `rejectReason`).
- Legacy undo tables (>20 rows) prune lazily on next insert; loads take newest.
- No `AGENTS.md` map update (no structural change). Build via CI only:
  commit + push `master`, follow with `go run ./monitor-build.go`.

## E. New backend: Cloudflare Worker + Neon Postgres (free)

One API serves both KiloApp sync and the admin console. Old Railway
`file_rows`-per-row format is NOT reused — new tables, new endpoints.
Verified: `@neondatabase/serverless` HTTP driver runs in Workers (no TCP,
no Hyperdrive); Workers free = 100k req/day, 10ms CPU; Neon free = 0.5GB,
~100 CU-hrs/mo. Our load (10 files/week, 5–50KB snapshots, hundreds of
req/day) fits with ~100x headroom.

1. Stack: Worker + `@neondatabase/serverless`; secrets (`DATABASE_URL`,
   `SESSION_SECRET`, `TG_BOT_TOKEN`, `ADMIN_IDS`) as Worker secrets, never in
   APK/Pages bundle. Hono routes (portable: Railway today, Worker tomorrow).
   Neon IS Postgres (same wire protocol): the existing Railway backend connects
   unchanged via its Neon pooled URL + `sslmode=require` (`postgres.js`); the
   Worker uses the HTTP driver only because Workers lack raw TCP — same DB,
   driver per runtime.
2. Schema (new, old tables untouched):
   `users(telegram_id PK, name, username, banned, created_at)`,
   `sessions(token PK, user_id, exp)`,
   `sync_files(user_id, file_id, rev, seq, hash, meta JSONB, data TEXT,
   updated_at, deleted)` + per-user rev counter. Tombstones purged after 30d.
3. Auth (Telegram, both clients): bot claim flow ported from
   `admin/backend/src/index.ts:184` (removed with `admin/`; see tag `pre-admin-removal`) (`device/claim`) — client gets
   claim token, user opens bot `?start=claim_<tok>`, webhook binds telegram
   id, client polls claim → Bearer session (30d). App stores it in
   `EncryptedSharedPreferences`. Admin = id in `ADMIN_IDS`.
4. Sync endpoints:
   `POST /v1/sync/changes {sinceRev}` → `{rev, changed:[{fileId,seq,hash,
   deleted,meta}]}` (1-request discovery);
   `POST /v1/sync/push {fileId,baseSeq,hash,meta,data}` → `{seq,rev}` or 409
   with server copy; `POST /v1/sync/pull {fileIds[]}` batch fetch.
   Guards: ownership (`file_id` scoped to `user_id`, admin bypass with user
   scope), ciphertext size cap ~512KB, 4MB body cap, gzip, no body logging.
5. Encryption split (pool business needs plaintext, backups must not):
   private file blobs are E2EE (AES-GCM, key from user backup password;
   login proves who, password protects what; `meta` plaintext for listing).
   Rows the user explicitly pools/sells go plaintext to pool endpoints
   (explicit action = consent). Server never sees private cookies.
6. Conflicts: per-file `seq`, LWW; 409 → server wins + local conflict
   duplicate, never silent loss. Single-user files → conflicts rare.
7. App client: `SyncEngine` (Bearer store, rev cursor, dirty tracker,
   debounced push 5–10s with burst coalescing, boot pull, tombstone on
   `deleteForever`); crypto helper (PBKDF2 → AES-GCM; SHA-256 hash for
   skip/409); sync only meta + trimmed data rows (no checks/undo/wallet).
8. Rollout: phase 1 = Worker + Neon + auth + sync (admin console untouched,
   pools/wallet/checker stay on Railway); phase 2 = admin file reads via
   Worker (F below).

## F. Admin console → Worker API + user-surface cut

1. Website becomes admin console only: cut My Files / Archive / Wallet /
   personal `/file/:id` + `/archive/:id` editor routes (`App.tsx`, `Sidebar`,
   `HomePage` panes, delete `ArchiveView`/`FileGrid`/`Fab`; keep `WalletView`
   module for `WithdrawalsView` shared exports, keep `FileCard`/`EmptyState`
   for `AdminView`). `SheetPage` stays for `/admin/user/:userId/file/:fileId`
   inspection. Backend unchanged (already `admin_only`).
2. Point admin file access at Worker: user list/files/detail, meta +
   counts, purge/delete — contents stay E2EE-blind (admin acts on meta +
   pools, never private cookies). Two base URLs during transition
   (Worker for files, Railway for pools/wallet until phase 2).
3. Fallout: e2e user-flow specs (`page-entry`, `chaos`) rework/drop; backend
   vitest unaffected; update both `AGENTS.md` maps.
