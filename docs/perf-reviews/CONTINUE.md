# CONTINUE.md

Handoff for a fresh session. Read this first, then `/app/KiloApp/AGENTS.md` (repo rules —
executable config wins over prose, and several rules here contradict the usual defaults).

**Working dir:** `/app/KiloApp` (Android/Kotlin, package `net.typeblog.socks`, appId `com.kiloapp.app`)

---

## TL;DR

Three read-only performance reviews of the floating bubble were produced. The first was
implemented and **shipped as v345**. The safe half of the other two is **implemented and
waiting in PR #3 (CI green)**. The single biggest remaining win — converting a 243-row popup
to a `RecyclerView` — is **deliberately not done**, and the reason is written up below so it
is not re-attempted blind.

**The sheet-bubble silent-rejection bug is FIXED** — PR #4, commit `ddf7ffd`, CI green, not yet
merged. Seven silent paths now report a 2-4 word message. See the section below.

| PR | Branch | State |
|---|---|---|
| #1 | `perf/bubble-hotpath` | **merged** as `c96550d`, released v345 |
| #2 | `docs/bubble-perf-reviews` | open, mergeable, docs only |
| #3 | `perf/sheet-popup-scaling` | open, mergeable, **CI green** |
| #4 | `fix/sheet-bubble-silent-bubbles` | open, **CI green**, fixes the silent-paste bug |
| #5 | `fix/hide-page-checks-non-page` | open, **CI green**, hides Page-only check switches |

---

## Done and shipped (PR #1, `c96550d`, v345)

Bubble + foreground-service hot paths. All in `FloatingControlService.kt` and
`CircleBubbleMenu.kt`.

- Notification was built **before** the dedupe compare, so `pollRunnable` decoded the launcher
  bitmap and made 3 `PendingIntent` calls every second (5x/second while connecting) and threw
  the result away. Now compares first; icon decodes once via a `lazy` field;
  `startForeground` seeds the dedupe.
- Drag fired up to 4 `updateViewLayout` binder round-trips per touch frame at 90-120Hz. Now
  recorded per `MotionEvent` and applied once per frame from a `Choreographer.FrameCallback`,
  flushed on `ACTION_UP`/`ACTION_CANCEL`.
- `menuClearancePx()` read SharedPreferences every drag frame; `currentDragBounds` /
  `currentSystemBarInsets` allocated `Rect`s per call and called `getIdentifier` per frame.
  All cached; refreshed from the existing pref listener + `onConfigurationChanged`.
- **Real bug fixed:** `if (flagPillView?.height ?: 0 == 0)` parses as
  `height ?: (0 == 0)`, not `(height ?: 0) == 0`. Combined with re-posting unconditionally, a
  `GONE` pill (normal in circle style) spun `updateViewLayout` calls forever.
- `ExpiryRingView.onDraw` was doing 3 `Color.parseColor` + `"%02d:%02d".format` + 2 Paint
  mutations **per drawn frame**. Now constants, reused `StringBuilder`, `onSizeChanged`.
- Dropped the 1px `RenderEffect` blur on the close spin; `withLayer()` instead.
- `applyLayout` forced 3 traversals per button; now one `box.requestLayout()`.

## Done, not merged (PR #3, `0dcd41c`)

- `BubblePopupPlacer` returns a `BubblePopupSide` enum instead of `String`, and picks the side
  with plain comparisons. **This was a robustness win as much as a perf one:** the 3 overlays
  branch on `side` in 7 `when` blocks to pick the grow-in pivot, and with String keys a typo
  silently fell to `else`. The enum makes them exhaustive-checked.
- Remaining `Color.parseColor` in the overlays hoisted to `@ColorInt` constants.
- `normalizedRows` no longer reallocates a copy of every row.
- `openChecks`/`openCheckReqs` use `minus`, read `.value` once.

## FIXED — Page-only check switches were shown on every file type (PR #5)

**Reported by the user:** the check dropdown offered "Simple check" and "Advanced check" on
2fa and cookie files, where they do nothing.

Those are **Page-file checks**: `checkFile()` gates both page sweeps on `preset == PAGE`
(`SheetBubbleCoordinator.kt:165`). On a Cookie or 2fa file the two switches were dead controls
— turn them on, press Check, get a result identical to having them off, with no indication
which. Same class of problem as the silent-paste bug: a control that lies.

**Both menus had the identical bug**, so both were fixed:

- `SheetMenuOverlay.refreshMenuRows()` (the floating bubble menu) now takes a
  `presetProvider: () -> SheetPreset?` and hides the two rows unless the file is a Page file.
  The service supplies it from `lastSheetSnapshot?.file?.preset`, so no database read was
  added to the menu path. `null` means "not loaded yet" and shows everything, because
  `refreshMenuRows()` also runs from `renderToolbar()` immediately after the file loads and
  before the Check button is reachable.
- `SheetDetailContent` (the in-app Compose `DropdownMenu`) gates on `file?.preset`, which that
  composable already receives, so no plumbing was needed.

Left alone deliberately: the `ss_pageSimple` / `ss_pageAdvanced` prefs are not cleared, so
switching a file between presets restores the user's previous choice. The bubble's Check
*button* enabled-state already derived from `snap.file.preset.columns` and needed no change.

## The reviews

Pushed to `docs/bubble-perf-reviews` (PR #2), also at `/app/reports/`:

- `docs/perf-reviews/01-circle-bubble.md` — 25 findings; High items already shipped
- `docs/perf-reviews/02-sheet-rendering.md` — N+1 in `listFiles`, 500-row rebuilds, 13 flows
- `docs/perf-reviews/03-popup-overlays.md` — the 243-row problem

**Correction to 02:** it ranked "no index on `files(archived, updatedAt)`" as High. That is
overstated. `files` holds one row per sheet file (tens of rows), so a full scan + sort is
sub-millisecond. The N+1 is the real cost because *it* queries `rows` (500+ per file). Don't
spend a migration on that index.

---

## NOT done — the RecyclerView conversion

**The single biggest win in either review, deliberately skipped.** `BubbleMenuOverlay` inflates
up to **243** country rows (`Countries.ALL` has exactly 243 entries) into a `LinearLayout`
inside a `ScrollView`: ~243 `inflate` calls, ~1200 View objects, ~1000 `findViewById` (5/row),
and `LinearLayout` re-measures all children every layout pass. `SmsMenuOverlay` already pools
rows correctly — copy that pattern.

Four concrete blockers, found by reading the code:

1. **`androidx.recyclerview` is not a dependency anywhere in the project.** This is a
   Compose-only module (`LazyColumn` everywhere, zero RecyclerView). Needs a new dep.
2. **The 200dp panel cap would break.** It is applied in a post-layout pass deriving height from
   `list.measuredHeight` (`BubbleMenuOverlay.kt:327-341`). A `wrap_content` RecyclerView
   auto-measures *all* children when unbounded, so a naive swap preserves the exact cost being
   removed. Rows are a fixed 32dp (`bubble_country_row.xml`), so height is computable up front.
3. **View recycling breaks the connected-dot animator.** `makeRow` starts an `INFINITE`
   `ObjectAnimator` on the dot (`:415-427`). A holder re-bound to a different country is a
   classic leak/crash vector; needs explicit `onViewRecycled` handling.
4. XML change (ScrollView+LinearLayout -> RecyclerView) plus rewriting **both** list-build
   paths — the sectioned initial build *and* the search filter — to produce item lists.

**Only attempt this with a device attached.** No test suite exists in this repo.

### Also outstanding

- `listFiles` N+1 (`SheetDb.kt:106-125`): 5 extra queries per file inside the cursor loop
  (1 + 5N), one a correlated `EXISTS`. Worth doing, but it rewrites count semantics with
  nothing to prove the numbers still match.
- `topUp` rebuilds a 500-element list (`MAX_GRID_ROWS`) on every `openRows` emission, so a bulk
  check run rebuilds it once per row checked.
- 13 independent `MutableStateFlow`s in `SheetStore`; related pairs assigned adjacently, so one
  refresh is several emissions. Group into a single state object.
- `bringBubbleToFront()` does `removeView`+`addView` — full window destroy/create to change
  z-order, on every menu open and every size-slider step.
- Menu view tree rebuilt from scratch on every open (~20 Views, 8 `SpringAnimation`s).
- **`SocksVpnService.kt:869` and `:932`** have the same per-notification
  `BitmapFactory.decodeResource` that was fixed in `FloatingControlService`. Not touched:
  `AGENTS.md` says never modify that file for UI-only work, and this isn't verified.
- Never reviewed at all: `SheetChecker.kt`, `SheetBackup.kt` (804 lines, over the repo's own
  ~800 split threshold), `SheetBackupXlsx.kt`, `SheetXlsx.kt`. The XLSX paths are the most
  likely place for parsing to land on the wrong thread.

---

## FIXED — the sheet bubble was silent whenever it rejected your input

**Reported by the user:** with a duplicate cookie in the clipboard, tapping the sheet bubble
does nothing at all. No toast, no shake, no visible change. The user cannot tell whether the
tap registered, whether the app is broken, or whether they should try again.

**Status: FIXED in PR #4, branch `fix/sheet-bubble-silent-bubbles`, commit `ddf7ffd`, CI green.**
Not merged to master. Messages are plain string changes on existing branches, but there has been
no device run, so the happy path (a valid cookie still saving) wants one manual pass.

Keep this section as the reference for *why* the code is shaped the way it is — the
exhaustive `when`s look redundant otherwise, and someone will "simplify" them back into the
silent form.

### Root cause (the code as it was **before** `ddf7ffd`)

`util/sheet/SheetBubbleCoordinator.kt:324-330` picked the user-facing message from what
changed:

```kotlin
val resultMessage = if (changed) message else if (changedRows.isNotEmpty()) {
    "Couldn't save. Please try again."
} else if (noFreeRow) {
    "This Sheet file has no free row. Open the Sheet tab to add one."
} else {
    ""            // <-- silent
}
```

and the caller only toasts when the message is non-empty
(`FloatingControlService.kt:2106`):

```kotlin
if (result.message.isNotEmpty()) toast(result.message)
```

So **every rejection path that sets neither `changed` nor `noFreeRow` nor `changedRows`
produces an empty string and is swallowed.** The duplicate-cookie case is one of them: the
`if` at `:287` is guarded by `!cookieDuplicate(...)`, so on a duplicate nothing is written,
`changedRows` stays empty, `noFreeRow` is false, and the message collapses to `""`.

### Every silent path through capture()

`capture()` is `SheetBubbleCoordinator.kt:251-331`. These all reach the `else ""` branch:

| # | Condition | User pastes | What happens |
|---|---|---|---|
| 1 | `cookieDuplicate(rows, active, parsed.value)` is true (`:287`) | a cookie already in the file | **silent — the reported bug** |
| 2 | `keyDuplicate(rows, active, parsed.value)` is true (`:301`) | a 2FA key already in the file | silent |
| 3 | `parsed.type == EMPTY` (`:281`, from `readSheetClipboard()` returning null) | nothing / non-text clipboard | silent |
| 4 | `parsed.type == INVALID` (`:284`, `:298` both fail) | unparseable text | silent |
| 5 | `TWO_FA` but `!usesBubbleTwoFa(file.preset)` (`:298`) | a 2FA key into a cookie-preset file | silent |
| 6 | `rows[active].cookies.isNotBlank()` (`:287`) | a cookie, but the active row already holds one | silent |

Case 6 is easy to hit and worth calling out: for a 2FA-preset file, `isBubbleRowComplete`
(`SheetBubbleRules.kt:37-40`) treats a row with a cookie but missing/invalid 2FA as
*incomplete*, so it becomes the active row — and then the cookie branch bails on
`cookies.isBlank()`. Pasting a replacement cookie there does nothing, silently.

Case 3 has a second layer: `readSheetClipboard()` (`FloatingControlService.kt:2237-2248`)
returns null on no clipboard, on a non-`text/plain` MIME type, on empty text, **and on any
exception** — all indistinguishable to `capture()`, which sees only `EMPTY`.

### Fix shape (APPLIED — see `ddf7ffd`)

**Requirement from the user: notify, never go silent — but the new messages must be very
short, matching the existing ones.** Do not write sentences.

What was actually done, beyond swapping in the strings:

- The cookie and 2FA branches each used **two independent `if`s**, so both could fire for a
  single paste. They are now exhaustive `when`s that record exactly one reason, which is what
  makes "no silent path" actually provable by reading the block.
- A `rejected` reason is threaded into the `resultMessage` selection ahead of the final
  `else ""`.
- The auto-check pass also had a guard that silenced it whenever `check.checked` was false
  (a **seventh** silent spot). `checkCounts` already degrades to `"No UID to check."` when both
  counts are zero, so the guard was redundant as well as silencing. Removed.

Calibrate against what is already there (all hardcoded, sentence case, trailing period):

```
"Cookie saved."          "2FA key saved."       "No_2Fa saved."
"Nothing to undo."      "Nothing to redo."     "Check done."
"No UID to check."       "Nothing to check."    "Alive 2, Dead 1."
"Select a Sheet file first."              (5 words, the only string resource)
"Turn on a check in the menu."            (7 words - the outlier, do not copy it)
```

Target **2-5 words**. Final wording, set by the user - use exactly these, do not reword:

| Path | Message | Mirrors |
|---|---|---|
| duplicate cookie | `Duplicate cookie.` | `Cookie saved.` |
| duplicate 2FA key | `Duplicate 2FA key.` | `2FA key saved.` |
| unparseable clipboard | `Invalid content.` | - |
| empty clipboard | `Copy a cookie first.` | `Select a Sheet file first.` |
| 2FA into cookie-preset file | `File takes no 2FA.` | - |
| active row already occupied | `Row not empty.` | `Nothing to check.` |

Note: the user asked for **"duplicate"**, not "already" - earlier drafts saying
`Cookie already saved.` / `Row already filled.` were rejected. Keep the two duplicate
messages worded as nouns, not sentences.

The **empty-clipboard** case is the one to get right: it is the most common real-world
trigger (user taps the bubble having forgotten to copy anything) and it used to be completely
silent. `Copy a cookie first.` is short but actionable, which is the point.

Hardcoded is the locally consistent choice - every message inside `capture()` is hardcoded,
and `R.string` is used only in `openSheetPopup`. Either is defensible; match the surrounding
lines and be consistent within the change.

Prefer `SheetBubbleRules.kt` for any new pure predicate (that file is explicitly "pure rules
shared by the native floating Sheet bubble" and is the right home per the repo's
"shared logic has one home in `util/`" rule). Keep user-visible strings plain ASCII - no
emoji, no unicode symbols.

### While in there, check for other silent spots — audit results

The user asked whether the app stays silent elsewhere. **Confirmed fine** (these do toast):
`openSheetPopup` no-file (`:2054`), file-vanished (`:2088`), undo/redo with no history
(`"Nothing to undo."`), `runSheetBubbleCheck` with no checks enabled and with nothing checked.

**Found and fixed:** the auto-check pass (`FloatingControlService.kt:2120`) — see above.

**Known remaining, deliberately not changed:** `toast()` itself (`:1946-1952`) is wrapped in a
`try { } catch { Log.w(...) }`, so if it ever throws the user sees nothing and only a log line
records it. Very unlikely — `Toast.makeText` with a `Service` context is legal — and there is no
good fallback without an activity, so flagging rather than pretending it is covered.

Still unexamined: `SheetBubbleCoordinator.checkFile` internal phase failures, and
`runSheetHistory`'s non-snapshot error branches.

---

## Hard-won lessons — please don't rediscover these

**1. `git fetch origin master` does NOT update local `master`.** This caused a real near-miss:
a branch was created off stale local `master` (`54e3514`) instead of `origin/master`
(`c96550d`), so it was missing all of PR #1's work — merging it would have silently reverted
everything in v345. Always branch off `origin/master`:
```
git fetch origin master && git checkout -b <name> origin/master
```

**2. `kotlinc` without the Android SDK only proves code *parses*.** Every Android/AndroidX
symbol is unresolved, so it produces hundreds of cascading type errors. The only useful signal
is: compile, then diff the error set against the same compile of the *unmodified* files. Even
then it missed a genuine error (`Unresolved reference 'text'` — a local in another function) that
CI caught immediately. **CI is the real compiler.** Don't trust a green local parse.

**3. AGENTS.md says never build Android locally** and there is no Android SDK on this VM
anyway. CI is the verification lane. Don't claim local Gradle verification.

**4. Watch for operator precedence in Kotlin elvis expressions.** `a?.height ?: 0 == 0` is
`a?.height ?: (0 == 0)`. This was a live bug in the shipped code, not a hypothetical.

**5. Check every call site before "optimizing" a comparison.** I rewrote `staleChecks` to
compare lists positionally instead of by `rowIdx`; then found 7 of its `persistRowsLocked`
callers pass rows that never went through `topUp`/`normalizedRows`, so the lists are not
positionally indexed and the rewrite was **not** equivalent. Reverted. Positional identity is
only safe where a normalizer guarantees it.

**6. Repo conventions that differ from the defaults:** commit identity is
`Cryptoistaken` / `traderspopy@gmail.com`; push with
`git -c credential.helper='!gh auth git-credential' push`; snapshot tags before risky changes;
one concern per commit; no dead code or unused imports; user-visible text plain ASCII (country
flag emoji excepted, and only in rows/sheets, never buttons/labels/toasts).

---

## Environment

- **The VM is an unclaimed Railway trial, past its build window, with the LLM budget
  exhausted.** It may refuse work or be deleted. Nothing important is only on the VM: this
  file is mirrored at `docs/perf-reviews/CONTINUE.md` on the `docs/bubble-perf-reviews` branch,
  and all code is on GitHub. What *would* be lost is uncommitted local work.
- Claim link (fetch fresh — these expire in ~30 min):
  `curl -fsS -H "Authorization: Bearer $AI_AGENT_KEY" "$AI_GATEWAY_URL/status" | jq -r .claim_url`
- GitHub auth: `gh` is authenticated as `Cryptoistaken` (repo, workflow, gist, read:org).
- Toolchain I installed: `openjdk-21-jdk-headless`, kotlinc 2.0.21 at `/tmp/kotlinc/bin/kotlinc`
  (in `/tmp`, so likely gone after a VM restart — reinstall from
  `https://github.com/JetBrains/kotlin/releases/download/v2.0.21/kotlin-compiler-2.0.21.zip`;
  note `unzip` is not installed, use `python3 -c "import zipfile;..."` or `jar xf`).

## Resume commands

```bash
cd /app/KiloApp
gh auth setup-git
git fetch origin

# PR #4 - the user-facing silent-paste fix, highest priority to land
git checkout fix/sheet-bubble-silent-bubbles && git pull

# PR #5 - hide Page-only check switches on Cookie/2fa files
git checkout fix/hide-page-checks-non-page && git pull

# PR #3 - the safe perf batch
git checkout perf/sheet-popup-scaling && git pull

# PR #2 - the three reviews + this file
git checkout docs/bubble-perf-reviews && git pull

# start fresh work -- ALWAYS off origin/master, see lesson 1
git checkout -b <branch> origin/master
```

Local `master` is stale (`54e3514`); the real head is `origin/master` (`c96550d`). Do not
commit to it directly.

## Open questions for the user

- **Merge PR #4 and PR #5?** Both CI green, both user-facing, both low risk, neither device-run.
- Merge PR #3? (CI green, semantics-preserving, but still no device run.)
- Merge PR #2 (docs only, zero risk)?
- Attach a device (`adb -s localhost:5557`) to do the RecyclerView conversion and the N+1
  properly, or accept the popup as-is?
