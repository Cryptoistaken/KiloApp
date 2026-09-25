# SMS and Sheet Performance Cleanup Implementation Plan

Status: completed. Core implementation, extraction, review, fast CI, and full CI are complete.

> For agentic workers: execute one task at a time. Review the diff, commit only that task, push `master`, and wait for the required CI build before starting the next task.

**Goal:** Make the SMS and Sheet features correct under lifecycle and concurrency pressure, while reducing main-thread work, repeated database writes, and Compose recomposition.

**Architecture:** Keep `SmsWatcher` and `SheetStore` as the two deep modules. Their interfaces own state transitions; Compose screens, overlays, and the floating bubble remain adapters. Do not add a repository layer, database library, WorkManager dependency, or another state owner. SQLite remains the Sheet source of truth, and the existing gateway remains the SMS source of truth.

**Tech Stack:** Kotlin 2.2, Jetpack Compose Material 3, `StateFlow`, coroutines, Android `SQLiteOpenHelper`, min SDK 21, compile/target SDK 36.

## Global Constraints

- Work on `master`; never build Android locally. Push each concern and run `go run ./monitor-build.go <run-id>`.
- Preserve `SheetDb.kt` and `SheetStore.kt` as local-first SQLite state. Do not add online sync.
- Do not modify `SocksVpnService.kt`, `IVpnService.aidl`, `Utility.kt`, or `ProfileManager.kt`.
- Keep user-visible text plain ASCII.
- Use `rememberPref` for preference-backed Compose state; write through `prefs.edit()`.
- No new dependencies. Prefer platform and Kotlin standard-library solutions.
- Use stable lazy-list keys, lifecycle-aware Flow collection, remembered derived values, and immutable state at module seams.
- Delete unused files/imports only after a repository-wide caller search.
- One file, one job. Keep the external screen interfaces stable; extract internal implementation only when it improves locality.

## Audit Findings Driving the Plan

- `SheetStore.open()` can publish an older request after a newer open; `runCheck()` captures one file but later persists through the current global open file.
- Bubble writes refresh editor rows but not editor undo/redo memory.
- Every sheet edit serializes and rewrites padded rows and writes an unused online-sync outbox.
- Sheet screens use plain `collectAsState`, recalculate hot derived lists, and expose unlabeled custom controls.
- SMS regeneration removes a number only in the UI, so it returns after process restart.
- SMS polling has a 15 second network timeout but can start another sweep every 5 seconds and can mutate expired numbers.
- The gateway sends OTP `at` timestamps, but the Android client discards them and uses number birth time.
- `SmsLog` performs read-modify-write file I/O on callers' threads; foreground service starts can acquire duplicate wake locks.
- SMS Compose caches are keyed by list size, while `SmsNum` message fields can change without a size change; the popup rebuilds every row each second.

Official guidance reviewed before implementation:

- Compose architecture and performance: https://developer.android.com/develop/ui/compose/architecture and https://developer.android.com/develop/ui/compose/performance/bestpractices (updated 2026-09-22).
- Compose lifecycle and side effects: https://developer.android.com/develop/ui/compose/lifecycle and https://developer.android.com/develop/ui/compose/side-effects (updated 2026-09-22).
- SQLite threading and WAL: https://developer.android.com/training/data-storage/sqlite and https://developer.android.com/reference/android/database/sqlite/SQLiteOpenHelper (updated 2026-08-03).
- Foreground services: https://developer.android.com/develop/background-work/services/foreground-services (updated 2026-09-16).

## Task 1: Harden Sheet State and SQLite Writes

**Files:**
- Modify: `app/src/main/java/net/typeblog/socks/util/sheet/SheetDb.kt`
- Modify: `app/src/main/java/net/typeblog/socks/util/sheet/SheetStore.kt`
- Modify: `app/src/main/java/net/typeblog/socks/util/sheet/SheetRowsJson.kt`
- Modify: `app/src/main/java/net/typeblog/socks/util/sheet/SheetBubbleCoordinator.kt`
- Modify: `app/src/main/java/net/typeblog/socks/ui/screens/sheet/SheetDetailScreen.kt`
- Modify: `app/src/main/java/net/typeblog/socks/ui/screens/sheet/SheetCreateDialogs.kt`
- Modify: `app/src/main/java/net/typeblog/socks/FloatingControlService.kt` only for the existing Sheet bubble busy flag cleanup.

**Interfaces:**
- `SheetStore.open(id: String): Boolean` remains the screen-facing interface, but an internal open generation prevents stale publication.
- `SheetStore.reloadOpenFileIfMatches(fileId: String): Boolean` reloads rows, styles, hidden columns, checks, requests, duplicates, undo, and redo for the open file.
- `SheetStore.runCheck(...)` captures a file ID, sequence, and row snapshot; it publishes/persists only if that file generation is still current.
- `SheetStore.requestWithdraw(amount: Double, method: String, account: String): Boolean` rejects non-finite amounts and performs balance read plus transaction write under one lock.

**Steps:**
- [x] Enable SQLite WAL before the database is opened, using the current `SQLiteOpenHelper` API.
- [x] Persist only rows through the last meaningful row; keep the in-memory 500-row editor padding. Trim trailing empty rows from JSON history and snapshots.
- [x] Reduce history depth to 20, load newest entries in chronological order, and reload history after bubble writes.
- [x] Remove the unused `recordOp` outbox/journal writes and their call sites; do not drop existing tables in this change.
- [x] Add an internal open generation and mutation lock. Make open, close, row mutations, undo/redo, refresh publication, and check finalization file-scoped and ordered.
- [x] Make check finalization verify the captured file sequence and use `try/finally` for `checking` and bubble busy state.
- [x] Move direct merge, replace, and import row writes behind small `SheetStore` commands that enforce the 500-row limit, clear stale check data where reindexed, and return the actual saved count.
- [x] Reject `NaN` and infinity withdrawals and keep balance/transaction publication ordered.
- [x] Verify caller searches for every removed method and inspect the diff for direct `SheetDb` writes left in UI code.

**Validation:**
- Run editor diagnostics for all changed Kotlin files.
- Search for remaining `recordOp`, direct `SheetDb.saveAllRows` in UI code, stale-size keys, and ignored `checking` exceptions.
- Commit: `Harden sheet state and SQLite writes`
- Push and wait for the fast CI run before Task 2.

## Task 2: Reduce Sheet Recomposition and Improve Accessibility

**Files:**
- Modify: all current `app/src/main/java/net/typeblog/socks/ui/screens/sheet/*.kt` files that collect store state or render the grid/popups.
- Create: `app/src/main/java/net/typeblog/socks/ui/screens/sheet/SheetCheckUi.kt` for shared check strip, tabs, details, log, and duplicate rendering extracted from `SheetDotPopup.kt`.
- Modify: `SheetDotPopup.kt` and `SheetFilePopup.kt` to consume the shared check UI without changing the public popup interface.
- Modify: `SheetGrid.kt` for stable semantics and cached cell color parsing.
- Modify: `SheetDetailScreen.kt` to move internal overlay/dialog implementation out of the composition root if needed to keep the file below the project size threshold.

**Interfaces:**
- `SheetGrid` and `DotPopup`/`DotPopupCard` signatures remain unchanged for their existing callers.
- `SheetCheckUi.kt` contains one implementation shared by row and file popups; it is not a new state owner.
- Store collections use `collectAsStateWithLifecycle`; popup-only state is read below the popup call site when practical.

**Steps:**
- [x] Replace Sheet `collectAsState` calls with lifecycle-aware collection.
- [x] Remember sorting/filtering/stat derivations by meaningful inputs, not list size; compute selection-wide cell sets only in selection mode.
- [x] Cache parsed style colors outside the per-cell body and provide stable keys for log and duplicate rows.
- [x] Add Compose semantics names, roles, selected/checked states, and a real semantics click action to custom grid/dot and slide-to-confirm controls.
- [x] Extract shared check rendering from `SheetDotPopup.kt` into `SheetCheckUi.kt`; remove duplicate implementations and unused imports.
- [x] Make the create menu dismiss on system back and expose the same action through keyboard/switch access.
- [x] Remove verified dead wrappers and keep all user-visible labels ASCII.
- [x] Run diagnostics and inspect the largest changed files for accidental broad recomposition or new state writes during composition.

**Validation:**
- Search for `collectAsState()` in the Sheet UI, duplicate check UI definitions, index-only log keys, and unlabeled custom click targets.
- Commit: `Reduce sheet recomposition and modal work`
- Push and wait for the fast CI run before Task 3.

## Task 3: Fix SMS Pickup State, Polling, and Background I/O

**Files:**
- Modify: `app/src/main/java/net/typeblog/socks/util/SmsGateway.kt`
- Modify: `app/src/main/java/net/typeblog/socks/util/SmsWatcher.kt`
- Modify: `app/src/main/java/net/typeblog/socks/util/SmsLog.kt`
- Modify: `app/src/main/java/net/typeblog/socks/util/SmsOtpService.kt`
- Modify: `app/src/main/java/net/typeblog/socks/util/SmsNotify.kt`
- Modify: `app/src/main/java/net/typeblog/socks/util/SmsCopyReceiver.kt`
- Modify: `app/src/main/java/net/typeblog/socks/util/SmsAlarmReceiver.kt` only if the heartbeat handoff requires it.
- Modify: `app/src/main/java/net/typeblog/socks/MainActivity.kt` or navigation only if the existing `EXTRA_OPEN_SMS` path is incomplete.

**Interfaces:**
- `SmsGateway.OtpState.msgs` carries code, text, gateway `at`, and app metadata.
- `SmsWatcher.provision(pat, onDone)` keeps its caller contract; regeneration is a watcher-owned replacement operation so persistence and state cannot diverge.
- `SmsWatcher` has one OTP application implementation shared by polling and SSE.
- `SmsNotify.showCode(context, display, code)` sets `EXTRA_OPEN_SMS` in the content intent.

**Steps:**
- [x] Parse gateway `at` values and use them for `SmsMsg`; use receipt time only when the gateway omits a valid timestamp.
- [x] Add one in-flight poll owner and recheck active membership, code state, and expiry after each network result.
- [x] Centralize OTP mutation, persistence, logging, and notification dispatch in one watcher implementation.
- [x] Make regeneration remove the old number through the watcher and persist the resulting list atomically from the UI caller's perspective.
- [x] Publish waiting-number snapshots through a thread-safe state holder; do not read Compose snapshot lists from the IO stream thread.
- [x] Move `SmsLog` read-modify-write work to one serialized background executor and preserve ordered `read`/`clear` behavior.
- [x] Make foreground service wake-lock acquisition idempotent, cancel the heartbeat on explicit stop, and release exactly the owned lock.
- [x] Route both SMS notification taps to the existing SMS navigation extra; cancel a copy notification only after clipboard success.
- [x] Remove unused SMS helpers only after caller search and keep the existing gateway path/fallback behavior.
- [x] Run diagnostics and inspect all coroutine entry points for unstructured or overlapping work.

**Validation:**
- Search for `n.born` used as message time, duplicate polling entry points, raw `mine` reads from IO, wake-lock recreation, and notification intents without `EXTRA_OPEN_SMS`.
- Run `go vet ./... && go build ./...` from `sms core/` only if gateway code changes; do not run local Gradle.
- Commit: `Fix SMS pickup state and polling`
- Push and wait for the fast CI run before Task 4.

## Task 4: Tighten SMS UI and Popup Rendering

**Files:**
- Modify: `app/src/main/java/net/typeblog/socks/ui/screens/SmsScreen.kt`
- Create: `app/src/main/java/net/typeblog/socks/ui/screens/SmsComponents.kt` for extracted page rows, sheets, and shared presentation helpers.
- Modify: `app/src/main/java/net/typeblog/socks/SmsMenuOverlay.kt`
- Modify: `app/src/main/java/net/typeblog/socks/CircleBubbleMenu.kt` only for SMS action accessibility and clock isolation.
- Modify: `app/src/main/java/net/typeblog/socks/ui/components/PrefsState.kt` only if a small reusable lifecycle/preference helper is required.
- Modify: `app/src/main/java/net/typeblog/socks/util/SmsWatcher.kt` only for the revision/state seam required by the UI.

**Interfaces:**
- `SmsScreen` remains the navigation-level composable; extracted components receive immutable data and callbacks.
- SMS range preference has one key (`PREF_SMS_LAST_RANGE`) and uses `rememberPref`; transient page/search/tab state uses `rememberSaveable`.
- The SMS popup reuses row views by number ID and updates text/status fields without inflating every row on each clock tick.

**Steps:**
- [x] Replace the split SMS range preference keys with the canonical preference while reading the old key once for migration.
- [x] Use `rememberSaveable` for transient navigation state and `rememberPref` for the range value.
- [x] Key SMS derived lists by a watcher revision or meaningful content, not list size; memoize filtering, sorting, and statistics.
- [x] Isolate the one-second clock read to leaf UI where practical and avoid repeated battery/exact-alarm checks.
- [x] Add stable keys to SMS lazy lists and replace country flag characters with ASCII country codes.
- [x] Reuse popup row views by number ID, update changed text/status only, and preserve scroll position.
- [x] Add accessible click/long-click actions and content descriptions to the SMS bubble, generation control, rows, and swipe-only regenerate action.
- [x] Extract presentation-only composables from `SmsScreen.kt` and delete verified dead helpers/imports.
- [x] Run diagnostics, ASCII literal search, and a final caller/import search.

**Validation:**
- Search for duplicate SMS range keys, `remember(feed.size)`, `remember(mine.size`, Unicode user literals, and popup `removeAllViews`/full row re-inflation.
- Commit: `Tighten SMS UI and popup rendering`
- Push and wait for the fast CI run.

## Final Review and Closure

- [x] Review the complete diff against this plan and the project rules.
- [x] Confirm no direct UI database writes, new dependencies, online sync, forbidden file changes, or untracked build artifacts.
- [x] Confirm the worktree is clean after the final CI run and report any device-only checks that were not run.
- [x] Keep the remote snapshot tag `pre-sms-sheet-performance` as the restore point.
