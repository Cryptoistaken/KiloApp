# KiloProxy — Agent Rules

## Build (mandatory)
- Use ONLY the GitHub builders: pushes build fast (`.github/workflows/build-fast.yml` = arm64-debug default), full release (`.github/workflows/build.yml`) is manual-only until sheet work is done. Never build locally on this machine.
- After pushing code to `master`, follow the CI run with `go run ./monitor-build.go` (repo root; polls every 5s, exits 0 on success, 1 on failure). With no arg it follows the latest run on the current branch; pass an explicit run id to follow that one (`go run ./monitor-build.go <run-id>`). Do NOT use fixed `sleep` waits or manual re-polling.
- On failure: read the failing step, fix the code, commit, and push again.
- On success: proceed with download/install per below.

## Commit & Push
- Commit and push to `master` after fixes are done — no separate `push` command needed. Never leave completed fixes uncommitted or sitting unpushed.
- Identity: `Cryptoistaken` / `traderspopy@gmail.com`; push with `git -c credential.helper='!gh auth git-credential' push origin master`.

## Download & Install
- Do NOT download or deliver the APK by default — the user updates from inside the app. Only download the **universal release APK** (versioned by CI) from the `app-release` artifact if the user explicitly asks for it.
- If asked: fresh-download to a clean directory before installing (stale APKs caused version/signature mismatch before).
- Since the persistent release keystore (GitHub secrets `RELEASE_KEYSTORE_*`) was introduced, every build is signed with the SAME key and `versionCode` increases monotonically (CI `GITHUB_RUN_NUMBER` + 100). Updates are install-overs and PRESERVE all app data — never uninstall just to update.

### Install flow
1. Check if ADB device `localhost:5557` is alive (`adb devices` → shows `device`).
2. If alive:
   - Install over the old app WITHOUT uninstalling, so profiles/usage data are preserved:
     `adb -s localhost:5557 install -r <apk>`
   - Only uninstall first if a signature mismatch or downgrade is reported:
     - `INSTALL_FAILED_UPDATE_INCOMPATIBLE` / `INSTALL_FAILED_SIGNATURE` → signature differs (one-time migration from pre-keystore builds, or a different ABI build).
     - `INSTALL_FAILED_VERSION_DOWNGRADE` → installed versionCode is higher (e.g. a different ABI artifact); uninstall, or pull the matching ABI.
   - If not installed, install directly.
   - Verify with `adb -s localhost:5557 shell dumpsys package com.kiloproxy.app`.
3. If NOT alive: download the APK anyway, then STOP and wait for the user. Do NOT start any emulator/AVD on your own.
   - The user may skip the install, or start the emulator and tell you to install.
   - When the user later says to install after starting the device: install with `adb -s localhost:5557 install -r <apk>`; only uninstall first on signature/downgrade errors.

## Device notes
- App package: `com.kiloproxy.app`. Device ABI: supports `arm64-v8a`.
- Cross-ABI versionCode mismatch causes `INSTALL_FAILED_VERSION_DOWNGRADE` — install the ABI that matches the device; only uninstall before switching ABIs.
- Signature mismatch → the installed app was signed with an older key (pre-keystore ephemeral CI key, or a different ABI build); uninstall once, then all future updates install over cleanly.

## State Snapshot & Restore

Before making any major changes (UI redesign, architecture changes, etc.),
always snapshot the current working state so you can restore it later.

### Creating a snapshot
```bash
# Tag the current commit with a descriptive name
git tag -a pre-ui-redesign -m "Working state before UI redesign"

# Push the tag to remote
git push origin pre-ui-redesign
```

### Listing available snapshots
```bash
# List all tags
git tag -l

# List tags with their commit dates
git tag -l --sort=-creatordate
```

### Restoring a snapshot
```bash
# Option 1: Reset hard to a tagged state (DESTRUCTIVE — discards all changes)
git checkout pre-ui-redesign
git checkout -b restore-from-pre-ui-redesign
# Now you're on a new branch at the old state

# Option 2: Create a branch from a tag (SAFE — preserves current work)
git checkout -b ui-redesign-attempt-1 pre-ui-redesign
# You now have a branch with the old state

# Option 3: Cherry-pick specific commits from a snapshot
git log pre-ui-redesign..HEAD --oneline  # see what changed since snapshot
git revert <commit-hash>                  # undo a specific commit
```

### Tag naming convention
- `pre-<feature-name>` — before starting a feature (e.g. `pre-ui-redesign`)
- `stable-<date>` — known working release (e.g. `stable-2026-08-07`)
- `post-<feature-name>` — after completing a feature (e.g. `post-ui-redesign`)

### Existing snapshots
| Tag | Commit | Date | Description |
|---|---|---|---|
| `pre-ui-redesign` | `397d4b0` | 2026-08-07 | Engine intact, CI passing, floating bubble fixed. Use this to restore before any UI redesign work. |
| `pre-proton-settings` | (pre-proton-settings commit) | 2026-08-12 | Working state before ProtonVPN-style settings redesign (UI only). |
| `pre-netshield` | (pushed) | 2026-08-12 | Before NetShield Phase 1 (pdnsd exclude-list DNS blocking). |
| `pre-proton-2-settings` | (pushed) | 2026-09-08 | Before replacing Split tunneling + Theme settings with the ProtonVPN mock design. |
| `pre-notif-and-dot-fixes` | (pushed) | 2026-09-09 | Before notification large-icon fix + effective-theme wiring for bubble/popup. |
| `pre-accelerator` | `353da5e` | 2026-09-10 | Before VPN Accelerator engine work (UI toggle only, engine untouched). |
| `pre-android-parity` | `816486d` | 2026-09-13 | Before Android 15/16 parity fixes (16 KB ELF alignment, setMetered(false), always-on VPN start, stale-notification cleanup). |
| `pre-split-include-only` | `9ead889` | 2026-09-17 | Before single-mode Include-only split tunneling rework (KiloProxy only; migration wipes split config, keeps profiles). |
| `pre-home-country-recents` | `8709388` | 2026-09-19 | Before Home country selector (Proton-style location row) + Recents list at the bottom of Home. |
| `pre-admin-removal` | `695c984` | 2026-09-24 | Before removing `admin/` (ex-`sheetsubmit/`) web copy. Restore: `git checkout -b restore-admin pre-admin-removal`. |

> **One-time (do before the notification/dot pass):** done 2026-09-09 — tag `pre-notif-and-dot-fixes` created and pushed, table updated.

### Quick restore (pre-ui-redesign)
```bash
# Safe restore — creates a new branch from the snapshot
git checkout -b ui-redesign pre-ui-redesign

# If you need to go back to THIS commit directly (destructive)
git reset --hard 397d4b0
```

### Important notes
- Tags are lightweight and don't affect branch history.
- Always push tags to remote (`git push origin <tag>`) so they survive local disasters.
- The `DesignPlan.md` file in the repo root describes the UI redesign plan.
- Engine code (`SocksVpnService.kt`, `IVpnService.aidl`, `Utility.kt`, `ProfileManager.kt`) must never be modified by UI changes.

## User-Facing Messages

All user-facing text (Toast, Snackbar, notification content, status labels, error messages) must be **plain ASCII text only**. No emojis, no icons, no decorative unicode symbols.

**Allowed:** letters, digits, spaces, basic punctuation (`. , ! ? : ; - ( ) / ' "`).
**Forbidden:** `✓ ✗ ⚠ ⏳ 🔗 🌐 🇩🇪 … → — · ｢｣` and any other non-ASCII character in user-visible strings.

Bad: `"✓ Proxy works"`, `"Checking for updates…"`, `"Connected to ｢%s｣"`
Good: `"Proxy works"`, `"Checking for updates"`, `"Connected to %s"`

Keep messages short and direct. State what happened, nothing else.

## Code Cleanliness Rules

Learned from the Batch 1-3 dedup passes (Sept 2026) — follow these so the
codebase stays clean without future cleanups:

1. **One home per logic.** Never copy a block to a second caller. Shared logic
   lives in `util/` and every caller delegates: `SplitTunnel` (app-list
   parse/format + include-empty guard), `ProxyProviders.displayCountry` /
   `switchCountry` (country derivation), `Utility.usageRxKey` / `usageTxKey` /
   `readUsage` (stats keys), `ServiceRebind.backoffDelayMs` (retry ladders).
2. **Prefs-backed UI state uses `rememberPref`** (`ui/components/PrefsState.kt`).
   No hand-rolled `remember` + `OnSharedPreferenceChangeListener` blocks in
   screens or theme. Local writes still go through `prefs.edit()` directly.
3. **One file, one job.** Screens stay list/navigation-level; move sheets,
   dialogs, and form logic to their own files in the same package (e.g.
   `AddEditProxySheet.kt`). If a file passes ~800 lines, split it.
4. **No dead code.** Delete unused files/dialogs instead of leaving them
   (verify zero callers with `rg` first). Remove imports your edit orphaned.
5. **Pure string/key logic lives in `util` objects**, not in `when` blocks
   inside services or screens — so all callers agree by construction.
6. **Log the inputs of every routing-affecting decision** (mode + count, not
   just the outcome), so logcat can prove what ran.
7. **Refactors: snapshot tag first, one concern per commit**, update the
   Filesystem Map in the same commit, CI green before the next batch.

## Roadmap (planned, in progress, done)

### Sheet plan (active)
- **App (KiloApp Android) is user-features-only.** Regular users and admins alike
  get the same Sheet UI: My Files / Wallet / Archive tabs, file cards, sheet
  grid, withdraw form. No admin views (pools, approvals, settings, tools,
  analysis, user detail) exist in the app, for anyone.
- **Local-first storage (app).** `util/sheet/` (SQLite `sheet.db`) is the
  source of truth: files/rows/styles/columns/journal/snapshots/wallet/outbox.
  Every mutation writes here first; online sync (when added) only backs it up.
  SQLite survives offline use, crashes and app updates. It does NOT survive
  uninstall (Android wipes app-private data) — uninstall survival comes from
  SAF export copies in Download/Documents and/or encrypted online backup.
- **SAF export.** Xlsx export waits on a parser dep; until then Download writes
  RFC-4180 CSV through `ACTION_CREATE_DOCUMENT` (no permission needed, the
  copy survives uninstall). Copy-all uses TSV to the clipboard.
- Status: store layer committed (`2a7ee4e`); site-exact `ic_ss_*` icon set in
  progress; Compose UI port next; website copy + admin gate after.

### Later
- KiloSMS features.
- Online backup/sync for sheets (backup only, never the read/write source).
- Final goal: a new `circle-bubble.html`-style control bubble that controls everything. Tons of work required — plan placeholder only for now.

## Filesystem Map & References (KEEP UPDATED)

> **Rule:** Whenever the repo structure changes (files/dirs added, moved, renamed, or deleted), update this map in the same commit. Read this section first for fast orientation instead of re-scanning the tree.

### Root
| Path | Purpose |
|---|---|
| `AGENTS.md` | This file — agent rules, build/install flow, snapshots, filesystem map |
| `monitor-build.go` | CI waiter (stdlib only): `go run ./monitor-build.go [run-id]` polls the Actions run every 5s with a live job table + log tail, dumps failed logs at the end, exits 0 on success / 1 on failure. Always use this after pushing; never fixed sleeps. **Keep it updated:** when CI-wait requirements change, update the script AND this row in the same commit. |
| `worker/` | Backend API (Hono/Bun + Postgres, copied from standalone SheetSubmit `backend/`; Railway-deployed via dashboard, `railway.toml` deploy config) |
| `Pages/` | React SPA frontend (Vite; copied from standalone SheetSubmit `Pages/`; Cloudflare Pages deploys) |
| `checker/` | Own exit-IP checker (Cloudflare Worker source; deploys via wrangler, outside the APK build) |
| `cli/` | On-device Go test harness (stdlib only) for the portable engine half: `probe` (SocksTester parity), `check` (Utility.checkWith parity), `bench` (repeat connect-time stats + CSV), `sweep` (bulk proxy list), `speed` (throughput via proxy/direct), `dns` (IPv4-preferred resolve timing). Build: `go build -o kiloproxy .` in `cli/` (binary gitignored). Cannot drive TUN/tun2socks/pdnsd (Android-only). |
| `sms core/` | Go SMS gateway (stdlib only, Railway-deployed): `gateway.go` (public API `/v1/feed|numbers|otp|meta`, SSE push `/v1/stream`, + `/v1/admin/*`), `providers.go` (sole upstream contact: VoltX/MNIT/Zenex pool, bot-exact app/method labels), `cli/` (admin CLI), `Dockerfile` (multi-stage build). Test: `go vet ./... && go build ./...` inside. Secrets via env, never committed. |
| `.railway/` | Railway IaC (`railway.ts` + SDK `package.json`): owns the `kilosms-gateway` service (source = this repo @master, Root Directory = `sms core`). Android `/app` is NOT built by Railway. |
| `protonvpn-settings.html` | Settings mock reference (tracked; `design/` docs were deleted) |
| `build.gradle` | Root Gradle build (plugins: android.application, Kotlin compose) |
| `settings.gradle` / `gradle.properties` / `gradle/wrapper/gradle-wrapper.properties` | Gradle config (Gradle 9.4.1, AGP 9.2.1, Kotlin 2.2.10, Java 17) |
| `.github/workflows/build.yml` + `build-fast.yml` | **ONLY** build entry points (CI GitHub Actions; never build locally). `build-fast.yml` = push default (separate lane, own native cache key; release-signed + published as v<code> for the in-app updater). `build.yml` = manual-only full release until sheet work is done |
| `.keystore-backup/` | Local keystore backup — signing handled via GitHub secrets in CI |
| `.gitignore` | Ignorable paths |

### `app/build.gradle` (app module)
- compileSdk 36, minSdk 21, **targetSdk 36**
- Monotonic `versionCode`: CI `GITHUB_RUN_NUMBER + 100`, local `git commit count + 100`
- Per-ABI versionCode override: `abi_rank * 67 + base` (arm7=1, arm64=2, x86=3, x86_64=4)
- ABIs: `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64` (+ universal) via `-Pabi=` split
- Native: ndkBuild via `src/main/jni/Android.mk`, NDK 27.0.12077973, `useLegacyPackaging = true`
- Signing: persistent release key from CI env `KILO_KEYSTORE_*`, else debug
- R8 minify+shrink on release; Java 17; Compose BOM `2024.10.01`, material3, navigation-compose 2.8.3, lifecycle 2.8.6, activity 1.9.x, appcompat 1.6.1, material 1.11.0, security-crypto 1.1.0-alpha06
- `tasks.configureEach` copies pdnsd/tun2socks `.so` from `build/intermediates/cxx` → `src/main/jniLibs`

### Kotlin source — `app/src/main/java/net/typeblog/socks/`
| File | Responsibility |
|---|---|
| `MainActivity.kt` | Compose host activity, entry point, launcher |
| `SocksApplication.kt` | Application class (init, context wiring) + one-time single-mode split migration (wipes global/per-profile split config, keeps proxy profiles, split starts OFF) |
| `SocksVpnService.kt` | **Engine** — VpnService + tun2socks/pdnsd spawn, tunnelling, notifications, stats, IP check. NEVER modify for UI. Split is Include-only: `configure()` forces allow-list, skips own UID, falls back to full tunnel on empty effective list; `onStartCommand` logs `bypass` + app count. |
| `FloatingControlService.kt` | Floating bubble (60dp) + flag pill overlays, long-press popup; WindowManager, SYSTEM_ALERT_WINDOW. Circle style shares lock visuals; long-press opens CircleBubbleMenu (Proxy tap toggles, Proxy long-press opens country menu, SMS provisions+copies, Name copies, Sheet placeholder) |
| `BubbleMenuOverlay.kt` | Popup overlay shown near bubble: country list, search, positioning; window params/IME handling |
| `CircleBubbleMenu.kt` | Circle-menu overlay: Proxy/SMS/Sheet/Name bubbles around the anchor, alignment + size from prefs, scrim dismiss |
| `BootReceiver.kt` | BOOT_COMPLETED + MY_PACKAGE_REPLACED auto-start receiver (restores VPN for auto-connect profiles and the floating bubble after reboot and after in-app updates) |
| `System.kt` | JNI bridge (sendfd) |

Notes on the merged notification/dot pass:
- `SocksVpnService.kt` — reuses the shared "floating control" notification (id 2, channel `floating_control`) instead of a separate VPN notification; user sees only ONE notification. `stopMe` uses DETACH (not REMOVE) only while `FloatingControlService` is alive (shared FGS notification); with the bubble off it uses REMOVE so no stale Connected notification is left.
- `FloatingControlService.kt` — notification uses custom RemoteViews: always-visible centered pill with Connect/Disconnect; connected bubble color is now `#DC2626` (light-theme `LightError`) instead of `DarkError #EF4444`.
- `BubbleMenuOverlay.kt` / `bubble_country_row.xml` — connected-dot now positioned where the dial code was shown.

### `.../util/`
| File | Responsibility |
|---|---|
| `Constants.kt` | Intent extras, preference keys, actions |
| `Countries.kt` | Country list for bubble menu |
| `LogCollector.kt` | In-app log capture (Debug Logs screen) |
| `Profile.kt` / `ProfileFactory.kt` | Profile data class + factory (pre-defined server profiles) |
| `ProfileManager.kt` | **ENGINE** — profile CRUD, prefs. NEVER modify for UI |
| `ProxyProviders.kt` | Proxy provider catalog (Owl/Rapid/Clip/IpDeep/ProxyRise/generic presets) + country display derivation + country-switch rewrite + `nameFromHost` (hostname-derived profile names) |
| `Routes.kt` | VpnService route selection (route config) |
| `NotifText.kt` | Notification text caps (static titles, body capped at 40, no big notifications) |
| `SocksTester.kt` | SOCKS5 liveness/health probe |
| `ServiceRebind.kt` | Shared AIDL rebind backoff ladder (200/1000/3000ms by attempt) |
| `SplitTunnel.kt` | Split-tunnel list parse/format + include-empty guard (single home for UI + engine guards) |
| `sheet/SheetModels.kt` | Sheet local-first models: presets/columns, file/row/style/wallet types, auto-naming, archive days-left |
| `sheet/SheetDb.kt` | Sheet SQLite store (source of truth, app-private): files/rows/styles/hidden/journal/snapshots/wallet/outbox; uninstall wipes it, SAF export survives |
| `sheet/SheetCsv.kt` | Sheet CSV/TSV builders for SAF export and clipboard copy-all |
| `sheet/SheetStore.kt` | Sheet working state over SheetDb: flows, undo/redo, create/rename/archive/restore/purge, cell edits with dup guard, compact/delete-dead, snapshot restore, offline check, wallet withdraw |
| `SmsGateway.kt` | Go SMS gateway client (`sms core/`): feed/meta/numbers/otp over HTTPS with `BuildConfig` URL + global app key (stdlib + org.json, no new deps) |
| `SmsWatcher.kt` | App-scoped SMS state (my/expired numbers, feed, countries, 1s ticker, 7-min expiry sweep, SSE push stream with 5s-OTP-poll fallback, 60s feed refresh); fires OTP notifications, outlives the SMS tab |
| `SmsNotify.kt` | OTP arrival notifications (code in title + Copy action); plain ASCII |
| `SmsCopyReceiver.kt` | Manifest receiver for the notification Copy button (copies code, dismisses) |
| `NamesRepo.kt` | Random-name pool for the circle menu (assets/names.txt, one per line) |
| `ThemeMode.kt` | Effective theme (manual theme_mode override, else device) + themedContext for -night inflation |
| `Utility.kt` | **ENGINE** — pdnsd conf, ip lookups, usage-stats keys, misc helpers. NEVER modify for UI |

### `.../ui/`
- `components/` — Compose components: ConnectionCard, RecentsCard, ProxyCard, ProfileDetailSheet, ProtonControls (ProtonSwitch + ProtonRadio + ProtonDialogRadioRow, mock-exact mono controls), SearchInput, SettingsItem, UpdateDialog, PrefsState (`rememberPref` mirrors a prefs key as state)
  - `ConnectionCard.kt` — Hero card for the Home tab: Proton-style location row (large flag tile + bold country name + mono host:port subtitle + plain chevron, opens the Countries list) when a country parses from the profile, else a centered flag + name row; Connect/Disconnect button is text-only (icon removed) with spinner while connecting.
  - `RecentsCard.kt` — Recents list at the bottom of Home (up to 10; older ones live on the Recents screen): Proton-style rows matching CountriesScreen (flag, name, CODE, right-aligned dial slot swapping for a green dot + Connected label on the connected row, shown only while actually connected), "See all" opens the Recents screen. Tapping a recent selects that country AND connects (rewrite via `applyCountryCode`, start via shared `startVpnForSelected`); hidden when empty.
  - `ProxyCard.kt` — Minimal card: square, borderless (transparent 1dp keeps picked outline + layout), lifted `surfaceContainer` tile on `surface` page (`--tile` in mock), flag-emoji / server-glyph icon slot, name + app-green dot (hidden offline), host without port, Used total only. Whole card taps to `onSelect` (detail sheet, or pick in pickMode). No chips, no buttons. Long-press (`onLongPress`) enters multi-select and picks the card; picked cards (`checked`) use the overlay style (primary border + primaryContainer tint, no checkbox). Swipe right opens Edit (black bg + white pencil), swipe left deletes immediately with a 5s Undo snackbar (no confirm); swipe disabled in pickMode/multi-select.
  - `ProfileDetailSheet.kt` — Bottom sheet opened by tapping a card: icon + name + sub, Provider/Type + Used/Server(no port) facts, Copy (clipboard `host:port:user:pass`, flips to bold Copied with icon hidden, no Toast) / Test (SocksTester + Toast) / Edit / Duplicate (`duplicateProfile` in ProxiesScreen, `Profile.copyTo`, no engine change) / Delete rows with `ic_sheet_*` icons (`lucide_copy` for Copy). Delete reuses the existing confirm dialog. Opens fully expanded (`skipPartiallyExpanded`).
- `navigation/AppNavigation.kt` — NavHost destinations (incl. `theme` route)
- `screens/` — BubbleSettingsScreen (Lock/Classic/Circle styles; Circle gates alignment options each with a live 4-icon preview + button-size slider + full preview), CountriesScreen, RecentsScreen (full recents list from Home See all; taps select AND connect via `VpnViewModel.pickAndConnectCountry`, bottom bar hidden), SmsScreen (SMS tab: mockup port — main/numbers/live/activity pages + country/confirm/item sheets, state in `SmsWatcher`, OTP notifications with Copy action), DebugLogsScreen, ProxiesScreen (list + swipe + dialogs + FAB; form lives in AddEditProxySheet; Home pick mode keeps full functionality, only tap selects + returns), AddEditProxySheet (add/edit form + proxy-string parse), SettingsScreen, SplitTunnelingScreen, StatusScreen (Home: Profile picker field + hero ConnectionCard with country selector + Data used + Connection details + Recents at the bottom; home country picks return via `VpnViewModel.pickCountry`), ThemeScreen, AdvancedSettingsScreen
- `screens/sheet/` — Sheet tab port (user-only, same for admins in-app; no admin views): SheetScreen (My Files / Wallet / Archive tabs + open-file routing + BackHandler), SheetUi (site-exact status colors, StatusDot, PresetIcon, PasswordBadge, empty state), SheetFilesTab + SheetFileCard + SheetCreateDialogs (cards, FAB create, type/password picks, rename, SAF xlsx download, share-a-copy xlsx via system sheet, dep-free xlsx import), SheetArchiveTab (restore/delete-forever, days-left), SheetWalletTab (balance USD/BDT, withdraw form, history), SheetDetailScreen (toolbar, grid, QuickEditBar, selection bar, archived viewer)
  - `AdvancedSettingsScreen.kt` — Advanced Settings page (Accelerator master + Primary checker + Checker mode + Cache last IP + Proxy health probe + Recheck interval + Cache proxy DNS). Engine honors prefs only while master is ON.
  - `ThemeScreen.kt` — Theme picker page: Light / Dark / Device theme cards with mini phone previews; writes PREF_THEME_MODE.
  - `SplitTunnelingScreen.kt` — Include-only single mode: feature header + toggle card (enabling jumps to Included page) + Included-apps row. Three pages: main, Included (dedicated list + FAB to add, empty state, minus to remove), Add apps (searchable full list, + flips to check). Same engine prefs minus bypass (`PREF_ADV_PER_APP` / `PREF_ADV_APP_LIST`; legacy `PREF_ADV_APP_BYPASS` ignored, removed from `settings.xml`). Picker hides own package, prunes stale entries on open, auto-turns split OFF when leaving with zero effective apps. IP-address rows skipped: engine has no IP split-tunneling support. Included page opens directly via `startOnApps` arg (refuse-to-connect link).
  - `SettingsScreen.kt` — Features rows: "Split tunneling" (On/Off), "Theme" (subtitle = theme label), "Floating Bubble" (On/Off), "Advanced Settings" (On/Off, opens AdvancedSettingsScreen); no chevrons.
- `theme/` — Color, Fonts, Theme, Type (Compose theming, Geist fonts)
- `viewmodel/VpnViewModel.kt` — Vpn state, AIDL binding, split Include-empty guard, `awaitStopped` restart wait, accelerator DNS warm-up, `pickAndConnectCountry` (recent tapped: rewrite + connect)

### Drawables added for this pass
- `drawable/ic_ss_*.xml` (34 site-exact Sheet icons ported from the website SVGs: cookie/coda, twofa/authenticator, page/soundcloud-solid, facebook, myfiles/redis, wallet/invoiceplane, archive/proton-drive, doc2x FAB, pw_dgd swirl, pw_love silhouette, undo/redo, check_arrow pixel, restore, merge, compact, download/upload/copy/pencil/trash/more/check/square/plus, textcolor/fill/eraser/paste, bkash/nagad/usdt/binance)
- `drawable/lucide_minus.xml`, `ic_proton_filter.xml`, `ic_proton_apps.xml` (vector icons for the split tunneling rows)
- `drawable/ic_sheet_test.xml`, `ic_sheet_edit.xml`, `ic_sheet_duplicate.xml`, `ic_sheet_delete.xml` (filled icons for the profile detail sheet rows)
- `drawable/ic_notification_transparent.xml` (required invisible notification small icon)
- `drawable/ic_copy.xml`, `ic_paste.xml` (fill icons for Copy/Paste, tinted to text color; no green)
- `drawable/ic_ss_cursor.xml`, `ic_ss_rename.xml`, `ic_ss_download_file.xml`, `ic_ss_send.xml` (file-card menu icons: select cursor, rename pen, doc-download, share arrow)

### Icon sourcing (applies to every new icon in this project)
- Source priority: https://keylineicons.com (fill style) first, https://allsvgicons.com second. Never add an icon library/font dependency for single icons.
- Convert the 24px `currentColor` SVG to `res/drawable/ic_ss_*.xml`: 24dp viewport, `#000000` fills/strokes, tinted at the use site (same pattern as the existing set).

### Native C — `app/src/main/jni/`
| Area | Purpose |
|---|---|
| `Android.mk`, `Application.mk` | ndkBuild top-level build files. All modules link `-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384` (16 KB ELF alignment, required on Android 15/16 16 KB-page devices; NDK r27 does not align by default). |
| `badvpn/` | tun2socks engine (full badvpn fork: tun2socks/, lwip/ stack, client/, system/, etc.). `lwip/custom/lwipopts.h` tunes `TCP_WND`/`TCP_SND_BUF` to 65535 (lwIP default 4*MSS caps a single stream at ~0.5 Mbps on 100 ms proxy RTT). |
| `pdnsd/` | pdnsd DNS proxy source |
| `libancillary/` | ancillary fd passing (sendfd recvfd) |
| `system.cpp` | JNI — `sendfd()` used by VPN tunnel setup |

### AIDL
- `app/src/main/aidl/net/typeblog/socks/IVpnService.aidl` — **ENGINE** binder interface between activity/UI and SocksVpnService (DO NOT touch for UI)

### Manifest — `app/src/main/AndroidManifest.xml`
- Permissions: INTERNET, RECEIVE_BOOT_COMPLETED, FOREGROUND_SERVICE, FOREGROUND_SERVICE_SPECIAL_USE, POST_NOTIFICATIONS, SYSTEM_ALERT_WINDOW, VIBRATE, REQUEST_INSTALL_PACKAGES. App list visibility is handled by `<queries>` (MAIN action), NOT `QUERY_ALL_PACKAGES` (removed in the 2026-08-28 audit to stay off Play's restricted permission).
- `SocksVpnService`: `process=":vpn"`, `exported=true`, BIND_VPN_SERVICE, fgType specialUse + subType property. Supports system always-on VPN: on a null/extra-less start the service falls back to the saved default profile via `Utility.buildVpnIntent`.
- `FloatingControlService`: specialUse FGS
- `BootReceiver`: exported=false, BOOT_COMPLETED
- `FileProvider` authorities `${applicationId}.provider`, paths `@xml/file_paths`
- `networkSecurityConfig="@xml/network_security_config"`

### Resources — `app/src/main/res/`
- `assets/` — names.txt (circle-menu Name pool); NetShield blocklists were removed — NetShield is now cloud-only
- `layout/` — `app_item.xml`, `bubble_menu.xml` (bubble popup panel), `bubble_country_row.xml`, `notification_action.xml` (RemoteViews layout for the notification Connect/Disconnect pill)
- `drawable/` — lucide_* icons, menu_panel_bg, search_input_bg, signal_dot, logo_*, launcher, notification_pill, notification icons (pill button background)
- `font/` — Geist family TTFs (bold/medium/mono/pixel etc.)
- `mipmap-*/` — legacy + adaptive launcher icons
- `values/` — strings.xml, arrays.xml, styles.xml, pdnsd.xml, ruroute.xml, simpleroute.xml, ic_launcher_background
- `xml/` — network_security_config.xml (cleartext/trust config), file_paths.xml (FileProvider), settings.xml (preference screen XML)
