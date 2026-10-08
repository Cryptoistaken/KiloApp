# KiloApp Admin Panel - Plan (plain text, edit me)

> Status: PLAN ONLY. No code changed yet. Fill specs under Section 5, I will implement after.

## 1. Goal (1 line)
Server hosts admin-only panel. App is user-only (even admin in app = user). Admin sees users/files, downloads, archives/restores. No edit/delete.

## 2. Architecture
- Phone stays source of truth: `app/src/main/java/net/typeblog/socks/util/sheet/SheetDb.kt` + `SheetStore.kt`, backup via `SheetBackup.kt`.
- Full backup (2026-10-08, commit 2d475ba): one restore file `backup/backup.db` = WAL-checkpointed `sheet.db` + stamped `backup_meta`/`backup_prefs` (allowlisted default prefs, sms_store minus tg_session, decrypted profiles). Human copies only, never restore: `files/<Name>.xlsx`, `archive/<Name>.xlsx` (Download-identical via `SheetXlsx.build`), `profiles/<Name>.xlsx` (proxy string `server:port:user:pass`). No json/xlsx dumps. Snapshots + local rotating copies are .db.
- One-way upload: app `dump():104` -> POST /api/files/upload -> Postgres. `toJson` was deleted in 2d475ba, so upload needs a new lean serializer from `dump()` (sheets + wallet only, no prefs/sms/profiles). `backup.db` never uploads (holds credentials).
- Server has: ingest API (user) + admin API + panel (admin-only). No sync-back to phone.
- Auth user: same as `SmsAuth.kt` device flow, new `KILOADMIN_URL` like `KILOSMS_URL` in `app/build.gradle`.
- Auth admin: copy SheetSubmit `backend/src/lib/session.ts` (`ss_session` cookie) + `isAdmin = ADMIN_IDS.includes(uid)` in `backend/src/routes/admin.ts`.

## 3. Admin powers (final)
CAN:
- `GET /api/admin/stats` - totals
- `GET /api/admin/users?q=` - list/search
- `GET /api/admin/user/:id` + `/archive` - detail + files
- `GET /api/admin/file/:id` + `/rows` + `/logs` - view
- Download xlsx/json (client-side xlsx like SheetSubmit `Pages/src/lib/xlsx.ts`)
- `POST /api/admin/file/:id/archive` - soft hide (archived=true)
- `POST /api/admin/user/:id/archive/:fileId/restore` - unarchive
- `POST /api/admin/file/:id/restore-snapshot {seq}` - rollback to snapshot (auto-snapshot current first, keep-10, 409 on seq mismatch)
- `GET snapshots/list` - picker + `file_logs` audit (actor=admin_uid)

CANNOT:
- Edit cookies/twofakey/uid, `PUT persist`, `PUT file`, `DELETE file/user`, `clearAllSheets`, ban/unban, wallet credit, pool approve/price/flags.

## 4. Data flow + backup
- Upload: `SheetBackup.dump():104` (allFiles, loadRows, loadStyles, loadHidden, loadRowChecks) -> new lean JSON serializer (sheets + wallet only) -> server `file_index/file_rows/file_meta/file_logs`.
- Server `archived` flag is server-owned, phone upload never overwrites it.
- Phone keeps 4-way mirror (`SheetBackup.artifacts():217` + Drive `DriveSync.kt` `maybeAutoPush:245`). Tree: `backup/backup.db`, `files/`, `archive/`, `profiles/`. Drive pull is `pullBackupDb`. Server adds standby copy like SheetSubmit `backend/src/lib/backup.ts` (`BACKUP_DATABASE_URL`, 30min).
- Server restore = per-file snapshot only. Full `backup/backup.db` restore stays phone-only (`SheetBackup.restore():451`, `summarize():391`, UI in `BackupScreen.kt`).

## 5. SPECS - WRITE WHAT YOU WANT BELOW (your turn)
<!--
How to fill: plain lines. Example:
- Admin login: Telegram only, ids 123,456
- User list shows: name, phone, file count, last upload
- Download name: {user}_{file}_{date}.xlsx
-->

### 5.1 Admin login / access
-

### 5.2 Users list (what columns, search by what)
-

### 5.3 File view (what columns, hide secrets?)
-

### 5.4 Download (xlsx? json? filename? max rows?)
-

### 5.5 Archive / restore rules (who can, confirm dialog?, keep days?)
-

### 5.6 Server / DB (InstaCloud? Neon? region ap-southeast?)
-

### 5.7 Anything forbidden (extra no's)
-

## 6. Phases
1. DB 7 tables + upload + ADMIN_IDS gate.
2. GETs + panel list/detail/logs/dbhealth.
3. Download + paging (max 1000).
4. Archive/unarchive + snapshot restore + audit.
5. App SyncUpload hooked to `SheetBackup.schedule():131` + standby job.

## 7. Constraints (do not break)
- Never touch: `SocksVpnService.kt`, `IVpnService.aidl`, `Utility.kt`, `ProfileManager.kt`.
- Mutations via `persistRowsLocked():479`. Prefs via `rememberPref`. ASCII text only.
- Push to `master`, CI `build-fast.yml`, wait `go run ./monitor-build.go [run-id]`.
