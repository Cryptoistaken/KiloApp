# Performance / Resource-Efficiency Review — Bubble Popup Overlays

Read-only review. No files modified. Performance only, no security findings.

Scope:

- `BubbleMenuOverlay.kt` (633 lines) — the country/menu popup shell
- `SmsMenuOverlay.kt` (582 lines) — the SMS numbers popup shell
- `BubblePopupPlacer.kt` (58 lines) — shared placement math
- `res/layout/bubble_menu.xml`, `bubble_country_row.xml`, `bubble_sms_menu.xml`, `bubble_sms_row.xml`

This is the third of three bubble passes. The circle menu and the service hot paths were
covered separately; the sheet rendering path is `sheet_rendering.md`.

---

## Summary

| # | Finding | File:line | Impact | Effort |
|---|---|---|---|---|
| 1 | Up to 243 country rows inflated into a `LinearLayout` — no view recycling | `BubbleMenuOverlay.kt:163-241`, `:402-404` | **High** | M |
| 2 | ~1000 `findViewById` calls per popup open (4-5 per row) | `BubbleMenuOverlay.kt:405-409` | **High** | S (with #1) |
| 3 | `LinearLayout` measures all children on every layout pass — O(243) per pass, nested in a `ScrollView` | `BubbleMenuOverlay.kt:123-125` | **High** | M |
| 4 | Whole popup tree re-inflated per `show()`; nothing is cached across opens | `BubbleMenuOverlay.kt:110-125` | Med | M |
| 5 | `ObjectAnimator` re-created per connected row per open, `INFINITE` repeat | `BubbleMenuOverlay.kt:415-427` | Med | S |
| 6 | `Color.parseColor` in `separatorView()` / `showMessage()` | `BubbleMenuOverlay.kt:459`, `:485` | Low | S |
| 7 | `BubblePopupPlacer.place` allocates lists, Pairs and lambdas per call; `String` sides compared by value | `BubblePopupPlacer.kt:28-44` | Low | S |
| 8 | Popup window re-added/removed to change z-order rather than ordered once | `FloatingControlService.kt:1568-1579` | Med | M |

---

## 1. Up to 243 inflated rows, no recycling — **High / M**

`BubbleMenuOverlay.kt:163-241`

The country list is a plain `LinearLayout` (`R.id.menu_list`) inside a `ScrollView`
(`R.id.menu_scroll`). Rows are built by looping and calling `addView`:

```kotlin
allRows.forEach { list.addView(makeRow(it, isConnected = false)) }   // :185
```

and `makeRow` inflates a fresh layout per row:

```kotlin
private fun makeRow(country: Countries.Country, isConnected: Boolean): View {
    val row = LayoutInflater.from(activeInflateContext)
        .inflate(R.layout.bubble_country_row, menuList, false)        // :404
```

`Countries.ALL` has **243 entries** (`util/Countries.kt:15`). The "all countries" section
therefore inflates up to 243 row layouts on a single `show()`, each containing a flag, name,
code, dial-code and status-dot view — roughly **1200 View objects** built synchronously on
the main thread at the moment the user expects the popup to appear.

**Cost.** ~243 `LayoutInflater.inflate` calls, ~1200 View allocations, and the whole set
retained for as long as the popup is open. This is the dominant first-open cost in the popup
path and a direct source of jank on low-end devices, where 1200 views also pressures GC.

**Recommended fix.** Use a `RecyclerView` with a `ListAdapter` + `DiffUtil` and a
`country_code` item key. The list is a slow-changing static set, so a plain
`ListAdapter` with a stable `DiffUtil` (or even `setHasStableIds(true)` keyed on
`Country.code`) collapses the open cost to roughly a dozen recycled holders regardless of
whether the section shows 5 or 243 rows. Rows are uniform in shape, so a single
`ViewHolder` type is enough.

## 2. ~1000 `findViewById` per open — **High / S**

`BubbleMenuOverlay.kt:405-409`

```kotlin
row.findViewById<TextView>(R.id.row_flag).text = country.flag
row.findViewById<TextView>(R.id.row_name).text = country.name
row.findViewById<TextView>(R.id.row_code).text = country.code
val dialView = row.findViewById<TextView>(R.id.row_dial)
val dot = row.findViewById<View>(R.id.row_dot)
```

Five `findViewById` walks per row, each traversing the inflated row hierarchy. At 243 rows
that is ~1200 tree walks per popup open, on top of the inflations.

**Cost.** Each `findViewById` walks the subtree comparing against the `R.id` cache. It is
much cheaper than inflation but it is pure repeated work that a `ViewHolder` eliminates
outright, and it is entirely redundant with the inflate that just happened.

**Recommended fix.** Resolve the ids once in the `ViewHolder` constructor. This finding is
not worth fixing on its own — it disappears as part of #1, since a `RecyclerView.ViewHolder`
holds the references directly.

## 3. `LinearLayout` measures every child on every pass — **High / M**

`BubbleMenuOverlay.kt:123-125`

`LinearLayout` is not a recycling container. `onMeasure` iterates **all** children and calls
`measureChildWithMargins` for each, so a 243-child `LinearLayout` performs 243 child
measures on every measure pass, and the whole chain re-runs whenever an ancestor requests
layout — including on scroll-driven re-layout of the enclosing `ScrollView`.

**Cost.** The 243 visible-or-not rows are all measured even though the `ScrollView` shows
roughly a dozen. This compounds #1: the rows are not merely allocated, they are re-measured
on any layout invalidation for as long as the popup is open.

**Recommended fix.** Same as #1 — `RecyclerView` measures only the visible children plus a
small prefetch buffer. If a `RecyclerView` is too large a change for this popup, at minimum
split the 243 rows into a paged or sectioned container so no single layout pass sees all of
them.

## 4. Popup tree re-inflated per `show()` — **Med / M**

`BubbleMenuOverlay.kt:110-125`

Each `show()` calls `LayoutInflater.inflate(R.layout.bubble_menu)` and re-resolves the panel,
scroll container and list via `findViewById`. Nothing survives the close: `rootView` and
`menuList` are dropped, so the shell is rebuilt and re-inflated every time the popup opens.
The class does correctly track the inflation `Context` (`:66-71`) so a theme change can
re-inflate in place, which is the right instinct — it is just not carried through to reuse
across opens.

**Recommended fix.** Build the shell once and keep it, re-attaching on re-open, re-binding
only the row data. Combined with #1 this turns open into a data bind rather than a
construction.

## 5. `ObjectAnimator` re-created per open, `INFINITE` — **Med / S**

`BubbleMenuOverlay.kt:415-427`

Each `makeRow` for the connected country builds a fresh `ObjectAnimator` with three
`PropertyValuesHolder`s and `repeatCount = INFINITE`, starting immediately:

```kotlin
connectedDotAnimator?.cancel()
connectedDotAnimator = ObjectAnimator.ofPropertyValuesHolder(
    dot,
    PropertyValuesHolder.ofFloat("alpha", 0.6f, 1f),
    PropertyValuesHolder.ofFloat("scaleX", 1f, 1.08f),
    PropertyValuesHolder.ofFloat("scaleY", 1f, 1.08f)
).apply { duration = 900; repeatCount = ValueAnimator.INFINITE; ...; start() }
```

The `cancel()` before it correctly prevents stacking, and only one row is connected at a
time, so this is one animator rather than 243. It is still rebuilt on every open, and
because the previous dot view is discarded with the old tree, the cancel is what keeps the
old animation from leaking a `Choreographer` callback against a detached view.

**Recommended fix.** Move the pulse to a single retained animator targeting the connected
row's dot, and drive it with `ViewPropertyAnimator` (`alpha`/`scaleX`/`scaleY` are all
`FloatProperty`s, so no `PropertyValuesHolder` boxing). Cancel in the same place the view is
detached.

## 6. `Color.parseColor` in view construction — **Low / S**

`BubbleMenuOverlay.kt:459` (separator) and `:485` (toast background)

```kotlin
setBackgroundColor(Color.parseColor(if (isLightMode()) "#E4E4E7" else "#3F3F46"))   // :459
setColor(Color.parseColor("#CC111111"))                                              // :485
```

These run per separator and per toast, not per frame, so the impact is minor — but
separators are created inside the same loop as the rows, so a multi-section list parses the
same two colors repeatedly. Same fix as the circle menu: `@ColorInt` constants.

## 7. `BubblePopupPlacer` allocates per call — **Low / S**

`BubblePopupPlacer.kt:28-44`

```kotlin
fun fitsH(value: Int) = value >= panelWidth                                     // local fun object
val horizontal = listOf("right" to right, "left" to left).filter { fitsH(...) }   // list + 2 Pairs + lambda
val vertical   = listOf("bottom" to bottom, "top" to top).filter { fitsV(...) }
```

Roughly six allocations and two local function objects for what is branch-only integer
math, with the chosen side carried around as a `String` and compared by value at `:39` and
`:44`.

It is called once per popup open, so it is not currently hot — but it is the shared placer
for the popup shells, so it is exactly the code that becomes hot if placement ever moves
into a drag path.

**Recommended fix.** Replace the `String` side with an `enum class Side` and pick the maximum
with plain comparisons. Zero allocations apart from the returned value.

## 8. Window re-added to change z-order — **Med / M**

`FloatingControlService.kt:1568-1579`

`bringBubbleToFront()` does `wm.removeView(view)` followed by `wm.addView(view, lp)`. That
destroys and recreates the overlay window — surface teardown, a fresh `ViewRootImpl`, and a
full measure/layout/draw of the bubble hierarchy — purely to raise it above the popup. It
runs on every menu open and on every size-slider step.

**Recommended fix.** Order the windows once at creation time rather than re-adding. Either
give the trigger a higher window layer than the popups so it is naturally on top, or host
the trigger and the popup in a single overlay window and use `View.setZ` /
`bringChildToFront` for ordering, which is a RenderNode property with no IPC at all.

---

## Already correct — keep

- **`SmsMenuOverlay` pools its rows.** `rowViews` is a retained map, rows are reused via
  `list.addView(views.root, index)`, and views whose ids left the active set are recycled at
  `:399`. This is the pattern `BubbleMenuOverlay` should adopt.
- Both overlays correctly track a themed inflation `Context` and can re-inflate in place when
  the effective theme changes (`BubbleMenuOverlay.kt:66-78`).
- `setBackgroundColor`/alpha are applied before `addView` in `BubbleMenuOverlay.kt:298`, with
  an explicit comment about avoiding a one-frame scrim flash. Correct instinct.
- `FLAG_NOT_FOCUSABLE` and `FLAG_NOT_TOUCH_MODAL` on the popup windows, so the overlay never
  steals IME focus or blocks the app behind it.
- The menu popup window is sized to its content rather than full-screen.

## Suggested order

1. **#1 + #2 + #3 together** — move the country list to `RecyclerView` + `ListAdapter`. This is
   one change that removes the dominant cost in the whole popup path; the other two fall out
   of it.
2. **#4** — retain the inflated shell across opens.
3. **#8** — drop the remove/add z-order hack.
4. **#5, #6, #7** — opportunistic cleanup.

## How to verify

- Memory Profiler, record allocations, open and close the country popup 10 times: ~1200
  retained View objects per open should collapse to a stable small number.
- Perfetto with `Choreographer`/`measure` slices while scrolling the country list: measure
  passes should track visible children, not 243.
- CPU Profiler on open: `LayoutInflater.inflate` should stop dominating the frame.
