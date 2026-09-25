# KiloApp — Agent Rules

Executable config wins over prose. If this file conflicts with CI/Gradle/manifest, trust the executable file.

## Repo units (three independent units)
- `app/` is the only Android module (`:app`, package `net.typeblog.socks`, appId `com.kiloapp.app`).
- `sms core/` is a stdlib-only Go 1.22 gateway. Only `providers.go` contacts upstreams; `cli/` calls the gateway, not providers directly.
- `.railway/` deploys only `kilosms-gateway` from `sms core`, never Android.
- Railway changes use `.railway/railway.ts` (`railway config plan` then `apply`); do not add deprecated `railway.json/toml`.
- Gateway secrets come from environment variables/Railway `preserve()`; never commit them. If `KILO_API_KEY` or `ADMIN_KEY` is unset locally, gateway auth is bypassed; never expose that run.
- `plan.md` and `spec/` describe intent; CI/workflow files define what actually runs.

## Build (never local)
- Never build Android locally. Work on `master`.
- Push to `master` runs `.github/workflows/build-fast.yml` (arm64 debug, release-signed, publishes `v<code>` release for the in-app updater).
- `.github/workflows/build.yml` is PR/manual full release only; not the push lane.
- After every push: `go run ./monitor-build.go [run-id]` (repo-root stdlib waiter: polls every 5s; exits 0 success, 1 failure, 2 infrastructure/timeout).
- With no arg it follows the newest run repo-wide, not necessarily the current branch; pass the intended run id explicitly.
- No fixed sleeps, no manual polling. Requires `gh` auth.
- On CI failure: read the failing step, fix, commit, push again.

## Commit and push
- Identity: `Cryptoistaken` / `traderspopy@gmail.com`.
- Push: `git -c credential.helper='!gh auth git-credential' push origin master`.
- Stage only files intentionally changed. Never leave fixes uncommitted/unpushed.

## Toolchain (compact)
- compile/target 36, min 21, Java/Kotlin 17, NDK 27.0.12077973, AGP 9.2.1.
- Shared CI version code: `max(GITHUB_RUN_NUMBER + 100, latest numeric release tag + 1)`; per-ABI codes still exist.
- The in-app updater reads the latest `v<number>` GitHub release and prefers the arm64 asset.
- Release signing uses CI env `KILO_KEYSTORE_*`, else debug.
- `SMS_GATEWAY_URL` and `SMS_API_KEY` are baked into `BuildConfig`; rotating them requires a CI rebuild/release.
- Do not claim local Gradle verification.

## Checks
- Go focused check from `sms core/` (the repo root has no Go module): `go vet ./... && go build ./...`.
- Android has no test/lint/codegen suite configured; do not invent one.

## Architecture boundaries
- `MainActivity` hosts Compose `ui/navigation/AppNavigation.kt`.
- `SocksVpnService` runs in `:vpn` and owns the tun2socks/pdnsd engine; `IVpnService.aidl` is the UI/service state interface.
- Never modify `SocksVpnService.kt`, `IVpnService.aidl`, `Utility.kt`, or `ProfileManager.kt` for UI-only work.
- `FloatingControlService` + overlay classes own the floating bubble.
- `app/src/main/java/net/typeblog/socks/util/sheet/SheetDb.kt` and `SheetStore.kt` are the local-first SQLite source of truth. No online sync is implemented. App is user-only (no admin UI).

## Hard constraints
- Split tunneling is Include-only; the UI refuses to start with no effective apps, while the engine defensively falls back to full tunnel. No bypass mode or IP split (the legacy bypass branch is forced off in `Utility.kt`).
- The one-time single-mode migration clears split config but keeps proxy profiles and starts split off.
- Prefs-backed Compose state must use `rememberPref` (`ui/components/PrefsState.kt`); writes go through `prefs.edit()` directly.
- Shared logic has one home in `util/` (split parse/guard, country derivation, usage keys, rebind backoff). No duplicated blocks.
- No dead code: delete unused files/imports (verify zero callers with `git grep` first).
- One file, one job: screens stay list/navigation-level; move sheets/dialogs/forms to their own files. Split files passing ~800 lines.
- Keep user messages short and direct: state what happened, nothing else.
- Log routing decision inputs (mode + count), not just the outcome.
- Refactors: snapshot tag first, one concern per commit, CI green before the next batch.
- All user-visible text is plain ASCII (no emoji, no unicode symbols).
- App-list visibility uses manifest `<queries>`, never `QUERY_ALL_PACKAGES`.
- 16 KB ELF linker flags live in `app/src/main/jni/Android.mk`.
- New single icons: keylineicons (fill) first, then allsvgicons; convert to 24dp black `ic_ss_*`, tint at use site. No icon library deps.

## Install and release
- Never download/deliver an APK unless explicitly asked. When asked, use the `app-release` universal artifact only (the push lane instead publishes `app-fast-debug` arm64), fresh-downloaded to a clean dir.
- ADB target is `localhost:5557`. Preserve data: `adb -s localhost:5557 install -r <apk>`.
- Never start an emulator. If device is not alive, stop and wait for the user.
- If the app is not installed, install directly (no uninstall needed).
- Uninstall only on explicit signature (`INSTALL_FAILED_UPDATE_INCOMPATIBLE` / `SIGNATURE`) or version-downgrade errors.
- `INSTALL_FAILED_VERSION_DOWNGRADE` means the incoming build has a lower version code (often a per-ABI fast APK versus a universal release); install the higher-code build and never uninstall just to force it.
- Verify: `adb -s localhost:5557 shell dumpsys package com.kiloapp.app`.

## Snapshots
- Before major changes: `git tag -a pre-<feature> -m "..."` and `git push origin pre-<feature>`.
- Restore via a new branch from the tag: `git checkout -b restore-<feature> pre-<feature>`. No hard-reset by default.
- Always push tags to remote so they survive local disasters.
