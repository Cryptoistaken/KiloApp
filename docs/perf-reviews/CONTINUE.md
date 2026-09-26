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

| PR | Branch | State |
|---|---|---|
| #1 | `perf/bubble-hotpath` | **merged** as `c96550d`, released v345 |
| #2 | `docs/bubble-perf-reviews` | open, mergeable, docs only |
| #3 | `perf/sheet-popup-scaling` | open, mergeable, **CI green** |

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
  exhausted.** It may refuse work or be deleted. Everything important is on GitHub; this file
  is the only thing that would be lost.
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
git fetch origin master
git checkout perf/sheet-popup-scaling && git pull    # PR #3, green, ready to merge
# or start fresh work:
git checkout -b <branch> origin/master
```

## Open questions for the user

- Merge PR #3? (CI green, semantics-preserving, but still no device run.)
- Merge PR #2 (docs only, zero risk)?
- Attach a device (`adb -s localhost:5557`) to do the RecyclerView conversion and the N+1
  properly, or accept the popup as-is?
