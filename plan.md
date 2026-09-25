# KiloApp Local Performance Improvement Plan

> For agentic workers: execute one task at a time. Keep the diff focused, review it, and do not claim Android verification without the required CI build.

**Status:** Active plan. This plan improves the features that already exist in KiloApp. It does not add online sync, accounts, a new backend, or a new product architecture.

**Goal:** Make the existing VPN, SMS, Sheet, wallet, floating bubble, and Compose UI smoother, faster, safer under lifecycle/concurrency pressure, and easier to maintain without changing user-visible product behavior unnecessarily.

**Architecture:** Keep the current local-first modules. `SheetDb.kt` and `SheetStore.kt` remain the Sheet source of truth. `SmsWatcher` remains the SMS state coordinator and keeps using the existing Go/Railway SMS gateway. `SmsGateway` remains the small HTTP adapter. The VPN service, profile manager, and split-tunnel engine remain outside this plan.

**Tech Stack:** Kotlin 2.2, Jetpack Compose Material 3, `StateFlow`, coroutines, Android `SQLiteOpenHelper`, `HttpURLConnection`, `org.json`, min SDK 21, compile/target SDK 36.

## Current Decision and Scope

KiloApp is local-only for this phase:

- No Telegram login.
- No online Sheet sync.
- No Neon database.
- No Cloudflare Worker migration.
- No Cloudflare Pages admin panel.
- No server wallet or server-side pool.
- No new repository layer, database library, WorkManager dependency, or state owner.
- The existing Go/Railway SMS gateway remains unchanged except for compatible bug fixes needed by the Android client. This is the one temporary server-backed exception.
- Existing local Sheet, VPN, bubble, and SMS UI behavior stays in place unless a performance or safety fix requires a small interface correction. The Wallet tab is hidden from the UI; its current local implementation is retained temporarily as deferred code, not an active feature.

The previous cloud migration design is retained in the **Future Plan - Deferred** section at the end of this document. It is not pending work and must not be started from this plan.

## Global Constraints

- Work on `master`; never build Android locally. Use the existing fast CI lane and `go run ./monitor-build.go <run-id>` after pushes.
- Do not modify `SocksVpnService.kt`, `IVpnService.aidl`, `Utility.kt`, or `ProfileManager.kt` for this performance work.
- Keep user-visible text plain ASCII.
- Use `rememberPref` for preference-backed Compose state and write through `prefs.edit()`.
- Do not add dependencies. Prefer Kotlin, Android platform, and standard-library solutions.
- Preserve the existing public navigation and service interfaces unless a caller search proves a change is safe.
- Use stable lazy-list keys, lifecycle-aware Flow collection, remembered derived values, and immutable state at module seams.
- Verify zero callers before deleting files, imports, wrappers, or state.
- One file, one job. Split a file only when the split improves locality and does not create another state owner.
- Keep the existing gateway route and JSON contract stable. Do not make Android performance work depend on a Worker migration.
- Never expose secrets in logs, source, screenshots, or build output.
- No local Gradle claims. The Android module has no configured test/lint/codegen suite; do not invent one.

## Current Performance Findings

These are the concrete areas to re-check before changing code. Confirm each finding against the current branch rather than assuming the old checklist is still exact.

### Sheet and SQLite

- `SheetStore.open()` can publish an older request after a newer open.
- `runCheck()` can capture one file and later persist through the current global open file.
- Bubble writes can refresh editor rows without refreshing undo/redo memory.
- Every edit can serialize and rewrite padded rows, including trailing empty editor padding.
- Existing `journal` and `outbox` tables and `recordOp` calls are not an online sync implementation. Audit their callers and either remove dead writes or make the local state ownership explicit. Do not create a new sync system in this plan.
- Compose screens use plain `collectAsState`, recalculate hot derived lists, and can rebuild large custom grids and popups too often.
- Undo/redo history, snapshots, styles, hidden columns, and check requests can be loaded or written more often than necessary.

### SMS runtime

- The current gateway returns OTP `at` timestamps, but Android can discard them and use number birth time.
- Polling can start overlapping sweeps while a previous network request is still running.
- Expired numbers can be mutated by a late response.
- Regeneration can remove a number only in the UI and allow it to return after process restart.
- Stream and polling paths can duplicate OTP application, logging, persistence, and notification work.
- `SmsLog` performs read-modify-write file I/O on caller threads.
- Foreground-service startup can acquire duplicate wake locks or leave heartbeat work running.
- Feed and OTP polling can continue when the SMS surface is not being used.

### SMS UI

- SMS Compose caches can be keyed by list size even when message fields change.
- The popup can rebuild every row on each clock tick.
- Preference migration can leave duplicate SMS range keys.
- Custom controls need stable semantics, keyboard behavior, and accessible labels.
- Country flags or other non-ASCII display characters must not be introduced in user-visible text.

## Performance Rules

1. Keep expensive parsing, sorting, filtering, and serialization out of composition functions.
2. Keep network and file I/O off the main thread.
3. Make every async result re-check ownership, file generation, session state, and expiry before mutating state.
4. Use one owner for each state transition. UI, bubble, stream, and polling adapters must call that owner rather than duplicating logic.
5. Persist only meaningful data. Do not serialize editor-only padding, transient UI state, or redundant derived values.
6. Prefer a stable state revision or content key over list size or object identity for Compose memoization.
7. Reuse expensive immutable results when their real inputs have not changed.
8. Never trade correctness for speed. Validation, error handling, accessibility, and data-loss protection remain mandatory.

## Task 0: Baseline and Caller Audit

**Files:**
- Read: `app/src/main/java/net/typeblog/socks/util/sheet/SheetDb.kt`
- Read: `app/src/main/java/net/typeblog/socks/util/sheet/SheetStore.kt`
- Read: `app/src/main/java/net/typeblog/socks/util/SmsWatcher.kt`
- Read: `app/src/main/java/net/typeblog/socks/util/SmsGateway.kt`
- Read: `app/src/main/java/net/typeblog/socks/ui/screens/sheet/*.kt`
- Read: `app/src/main/java/net/typeblog/socks/ui/screens/SmsScreen.kt`
- Read: `sms core/gateway.go`
- Modify: `plan.md` only if the audit changes a task boundary.

**Steps:**
- [ ] Record the current callers of every Sheet mutation, open, close, check, undo/redo, and persistence method.
- [ ] Record the current callers of `recordOp`, `journal`, and `outbox`. Do not delete them based only on grep results.
- [ ] Record every SMS network entry point, stream parser, poll owner, alarm receiver, service start/stop path, and notification entry point.
- [ ] Capture the current gateway JSON fixtures without changing the Go gateway.
- [ ] Identify the largest current Compose recomposition and list-rebuild surfaces from the code before editing.
- [ ] Separate confirmed findings from assumptions in the task notes.

**Validation:**
- No production code changes in this task.
- Every later task has a known caller list and a measurable performance target.

## Task 1: Harden and Speed Up Local Sheet State

**Files:**
- Modify: `app/src/main/java/net/typeblog/socks/util/sheet/SheetDb.kt`
- Modify: `app/src/main/java/net/typeblog/socks/util/sheet/SheetStore.kt`
- Modify: `app/src/main/java/net/typeblog/socks/util/sheet/SheetRowsJson.kt`
- Modify: `app/src/main/java/net/typeblog/socks/util/sheet/SheetBubbleCoordinator.kt`
- Modify: the existing Sheet mutation callers only where a file-scoped command is required.
- Do not add a repository layer or online sync module.

**Interfaces:**
- `SheetStore.open(id)` keeps its screen-facing contract while an internal generation prevents stale publication.
- `SheetStore.reloadOpenFileIfMatches(fileId)` reloads rows, styles, hidden columns, checks, requests, duplicates, undo, and redo for the matching file.
- `SheetStore.runCheck(...)` captures file ID, sequence, and row snapshot and finalizes only if that generation is still current.
- Sheet mutations remain serialized by the existing mutation owner.
- Wallet mutations remain local and must never become a hidden server operation.

**Steps:**
- [ ] Enable and verify SQLite WAL before the database is opened.
- [ ] Persist only rows through the last meaningful row; keep the 500-row editor padding in memory.
- [ ] Trim trailing empty rows from stored JSON, history, and snapshots.
- [ ] Reduce unnecessary history loading and cap history at the existing intended depth.
- [ ] Add an open generation and make open, close, row mutation, undo/redo, refresh publication, and check finalization file-scoped.
- [ ] Make check finalization verify the captured sequence and use `try/finally` for busy state cleanup.
- [ ] Route direct merge, replace, and import writes through small `SheetStore` commands that enforce row limits and clear stale check data.
- [ ] Make bubble writes refresh the same editor memory as main-screen writes, including undo/redo state.
- [ ] Audit `recordOp`, `journal`, and `outbox`. Remove only dead calls and imports after caller verification; do not create a future-sync abstraction.
- [ ] Reject non-finite local wallet amounts and keep local balance/transaction publication ordered and atomic.
- [ ] Verify that no UI composable writes directly to `SheetDb` outside the state owner.

**Validation:**
- Search for stale-size keys, direct UI database writes, unresolved `recordOp` callers, ignored `checking` exceptions, and untrimmed row serialization.
- Confirm opening file A then file B cannot publish A after B.
- Confirm a late check result cannot write to a newly opened file.
- Confirm bubble and main-screen edits produce identical history behavior.
- Confirm local wallet input cannot become `NaN`, infinite, or negative through the withdrawal path.

## Task 2: Reduce Compose Recomposition and Improve Sheet UI Feel

**Files:**
- Modify: current files under `app/src/main/java/net/typeblog/socks/ui/screens/sheet/` that collect state or render the grid/popups.
- Create only if needed: `app/src/main/java/net/typeblog/socks/ui/screens/sheet/SheetCheckUi.kt` for genuinely shared check rendering.
- Modify: `SheetDotPopup.kt`, `SheetFilePopup.kt`, `SheetGrid.kt`, and `SheetDetailScreen.kt` as needed.

**Interfaces:**
- Keep existing `SheetGrid`, `DotPopup`, and `DotPopupCard` callers stable.
- `SheetCheckUi.kt`, if created, is a shared renderer and not a new state owner.
- Store collections use `collectAsStateWithLifecycle` where lifecycle is available.
- Popup-only state is read below the popup call site when practical.

**Steps:**
- [ ] Replace plain `collectAsState()` with lifecycle-aware collection.
- [ ] Remember sorting, filtering, counts, and selection sets by meaningful inputs rather than list size.
- [ ] Cache parsed style colors outside the per-cell body.
- [ ] Use stable row and log keys based on IDs or content revision.
- [ ] Keep the one-second clock and battery checks at leaf UI rather than recomposing whole screens.
- [ ] Add semantics names, roles, selected/checked states, and real click actions to custom controls.
- [ ] Make create menus and dialogs respond correctly to system back and keyboard/switch actions.
- [ ] Extract shared check rendering only when it removes duplicate code without introducing a second state owner.
- [ ] Keep the editor responsive during large saves by showing existing local progress state instead of blocking the main thread.

**Validation:**
- Search for `collectAsState()` in Sheet UI, index-only keys, duplicate check renderers, and unlabeled custom controls.
- Inspect the largest changed files for accidental broad recomposition or state writes during composition.
- Verify all user-visible strings remain ASCII.

## Task 3: Make SMS Polling and Background Work Cheaper and Safer

**Files:**
- Modify: `app/src/main/java/net/typeblog/socks/util/SmsGateway.kt`
- Modify: `app/src/main/java/net/typeblog/socks/util/SmsWatcher.kt`
- Modify: `app/src/main/java/net/typeblog/socks/util/SmsLog.kt`
- Modify: `app/src/main/java/net/typeblog/socks/util/SmsOtpService.kt`
- Modify: `app/src/main/java/net/typeblog/socks/util/SmsNotify.kt`
- Modify: `app/src/main/java/net/typeblog/socks/util/SmsCopyReceiver.kt`
- Modify: `SmsAlarmReceiver.kt` only if the heartbeat handoff requires it.
- Keep the existing `sms core` HTTP contract and deployment unchanged.

**Interfaces:**
- `SmsGateway.OtpState.msgs` carries code, text, gateway `at`, and app metadata.
- `SmsWatcher.provision(pat, onDone)` keeps its caller contract.
- Regeneration is a watcher-owned replacement operation, not a UI-only list mutation.
- One OTP application implementation is shared by polling and SSE.
- `SmsNotify.showCode(...)` opens the existing SMS navigation route.

**Steps:**
- [ ] Parse gateway `at` values and use them for message timestamps, with receipt time only as fallback.
- [ ] Add one in-flight poll owner so a new sweep cannot overlap a slow previous sweep.
- [ ] Recheck waiting membership, code state, and expiry after every network result.
- [ ] Centralize OTP mutation, persistence, logging, and notification dispatch in one watcher implementation.
- [ ] Make regeneration remove the old number through the watcher and persist the list atomically from the caller's perspective.
- [ ] Publish waiting-number snapshots through a thread-safe state holder; never read Compose snapshot lists from an IO thread.
- [ ] Move `SmsLog` read-modify-write work to one serialized background executor while preserving ordered read and clear behavior.
- [ ] Make foreground-service wake-lock acquisition idempotent, cancel heartbeat work on explicit stop, and release exactly the owned lock.
- [ ] Stop feed/meta polling when the SMS surface is not active or the process is backgrounded without a waiting number.
- [ ] Preserve the current 5-second upstream gateway behavior; do not move it to a new backend in this plan.
- [ ] Keep stream and polling fallback behavior compatible with the existing Android read timeout and gateway heartbeat.

**Validation:**
- Search for overlapping poll entry points, `n.born` used as the message time, raw `mine` reads from IO, duplicate wake locks, and late writes to expired numbers.
- Run `go vet ./... && go build ./...` from `sms core/` only if gateway code changes.
- Verify no local Android build is claimed.

## Task 4: Reduce SMS UI Work

**Files:**
- Modify: `app/src/main/java/net/typeblog/socks/ui/screens/SmsScreen.kt`
- Create only if needed: `SmsComponents.kt` for genuinely shared presentation-only composables.
- Modify: `SmsMenuOverlay.kt` and `CircleBubbleMenu.kt` only for SMS action/accessibility or clock isolation.
- Modify: `PrefsState.kt` only if a small reusable preference helper is required.

**Interfaces:**
- `SmsScreen` remains the navigation-level composable.
- The SMS range preference has one canonical key, `PREF_SMS_LAST_RANGE`, and uses `rememberPref`.
- Transient page, search, and tab state uses `rememberSaveable`.
- Popup rows are keyed by number ID and update changed fields without inflating every row.

**Steps:**
- [ ] Migrate old SMS range keys to the canonical key and remove duplicate reads after caller verification.
- [ ] Use `rememberSaveable` for transient navigation state and `rememberPref` for the persisted range.
- [ ] Key derived SMS lists by a watcher revision or meaningful content, not list size.
- [ ] Memoize filtering, sorting, and statistics.
- [ ] Keep the one-second clock at the smallest leaf component that needs it.
- [ ] Avoid repeated battery and exact-alarm permission checks in every row.
- [ ] Reuse popup row views by number ID and preserve scroll position.
- [ ] Add accessible click/long-click actions and content descriptions to the SMS bubble, generation control, rows, and swipe actions.
- [ ] Replace any Unicode flag literals with plain ASCII country codes.
- [ ] Remove verified dead helpers and imports only after repository-wide caller searches.

**Validation:**
- Search for duplicate range keys, `remember(feed.size)`, `remember(mine.size)`, Unicode user literals, and full popup re-inflation.
- Confirm a message update does not rebuild unrelated rows.
- Confirm all SMS controls remain usable with keyboard and accessibility services.

## Task 5: Hide the Wallet Tab and Keep Its Code Deferred

**Files:**
- Modify: `app/src/main/java/net/typeblog/socks/ui/screens/SheetScreen.kt` to remove the Wallet destination from the visible tab row and content switch.
- Keep temporarily: `app/src/main/java/net/typeblog/socks/ui/screens/sheet/SheetWalletTab.kt`.
- Keep temporarily: local wallet methods in `SheetStore.kt` and `SheetDb.kt`.
- Do not delete wallet tables or local data in this task.

**Steps:**
- [ ] Remove `WALLET` from the `SheetTab` enum, tab buttons, and `when` rendering in `SheetScreen.kt`.
- [ ] Keep the wallet composable and local database code untouched so the feature can be reconsidered without restoring data.
- [ ] Do not add a credit path, server endpoint, fake balance, or hidden network call.
- [ ] Do not claim that the current local withdrawal moves real money.
- [ ] Keep the dormant implementation clearly identified as deferred rather than an active user feature.
- [ ] Verify no visible navigation, notification, deep link, or accessibility action can open the hidden Wallet tab.

**Validation:**
- Confirm the Sheet screen shows only My Files and Archive.
- Confirm the hidden tab cannot be reached through saved state or an old intent.
- Confirm no wallet data is deleted and no new wallet code is added.
- Treat the retained implementation as an intentional temporary exception to the normal dead-code rule; remove it when the wallet decision is finalized.

## Task 6: Final Performance Review and CI

**Files:**
- Review all files changed by Tasks 1-5.
- Do not add generated build artifacts or temporary files to the repository.

**Steps:**
- [ ] Search for duplicate state owners, direct UI database writes, overlapping SMS polls, stale file generations, unresolved `recordOp` calls, and dead imports.
- [ ] Review the complete diff against this plan and `AGENTS.md`.
- [ ] Confirm no online sync, login, Neon, Worker, Pages, admin, server wallet, or server pool code was added.
- [ ] Confirm the existing Go/Railway SMS gateway contract was not changed accidentally.
- [ ] Run repository checks that already exist: Android fast CI after push and focused Go checks only if Go code changed.
- [ ] Keep changes on `master`, commit one concern at a time, push, and wait for `go run ./monitor-build.go <run-id>`.
- [ ] Report any device-only validation that was not run instead of implying it passed.

**Validation:**
- CI is green for every pushed concern.
- The final plan and repository rules match the shipped local-only behavior.

## Future Plan - Deferred, Not Pending

The following design was discussed earlier but is intentionally not part of the current work. Do not create implementation tasks, files, migrations, or tickets from this section unless the user explicitly reactivates it.

### Deferred Cloud Platform

- Convert the Go `sms core` gateway to a TypeScript Cloudflare Worker.
- Use a dedicated Neon PostgreSQL database.
- Add official Telegram login and revocable sessions.
- Add all-sheet online sync with a single Android writer and recovery copies for stale mutations.
- Move wallet and pool state to server-authoritative records.
- Build a Cloudflare Pages admin panel for users/devices, wallet, SMS provider/quota operations, data pools, read-only sheet inspection, and audit logs.
- Use free-tier request budgets and consider Convex or another provider only after measured usage proves it is necessary.

### Deferred Conflict Rules

If cloud sync is activated later:

- Android remains the only writer of sheet cell content.
- Admin can inspect and copy/download but cannot mutate user file data.
- One active Android session and one separate admin-web session are allowed per account.
- A stale sync mutation creates a recovery copy; it is never silently merged.
- `available` pool entries follow source edits; held/claimed/approved/sold snapshots are frozen.
- Releasing an entry to `available` refreshes it from the current source row.
- Device changes require pending edits to sync or be exported.

The future plan requires a new reviewed plan, updated `AGENTS.md`, a snapshot tag, and a separate implementation decision. It is not hidden pending work in this document.

## Closure Checklist

- [ ] Existing Sheet behavior is faster without changing its local source-of-truth model.
- [ ] Existing SMS behavior is smoother without changing the gateway contract.
- [ ] No overlapping SMS poll owners, stale file generations, or late expired-number writes remain.
- [ ] Compose recomposition and popup work are reduced at verified leaf surfaces.
- [ ] The Wallet tab is hidden from the Sheet UI, and the retained wallet code is clearly marked as deferred rather than presented as a working payment feature.
- [ ] No sync, login, Neon, Worker, Pages, admin, server wallet, or server pool code was added.
- [ ] The Go/Railway SMS gateway remains the only temporary server-backed exception.
- [ ] CI is green and the final diff contains only intentional local-performance changes.
- [ ] The future cloud plan remains visibly deferred rather than silently treated as pending work.
