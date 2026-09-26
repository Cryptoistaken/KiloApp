# Performance / Resource-Efficiency Review — Floating Circle Bubble Menu

Scope (read-only review, no files modified):

- `app/src/main/java/net/typeblog/socks/CircleBubbleMenu.kt` (847 lines)
- `app/src/main/java/net/typeblog/socks/BubblePopupPlacer.kt` (58 lines)
- `app/src/main/java/net/typeblog/socks/FloatingControlService.kt` (2738 lines) — bubble/overlay parts

Findings are ranked High → Low. Effort: S (< 1h), M (a few hours), L (structural).

---

## Summary table

| # | Finding | File:line | Impact | Effort |
|---|---|---|---|---|
| 1 | Drag `ACTION_MOVE` does 3 `updateViewLayout` IPCs + full clearance recompute + text measure per touch frame | `FloatingControlService.kt:1240-1267` | **High** | M |
| 2 | 1 Hz `pollRunnable` runs forever (200 ms while connecting): binder IPC + notification rebuild + `BitmapFactory.decodeResource` per tick | `FloatingControlService.kt:211-221`, `1361-1371`, `1434` | **High** | M |
| 3 | `menuClearancePx()` reads SharedPreferences (2 gets) on every clamp — i.e. every drag frame | `FloatingControlService.kt:992-1014`, called `1028-1029` | **High** | S |
| 4 | Whole circle menu view tree is torn down and rebuilt (4 FrameLayouts + 4 ImageViews + GradientDrawables + listeners) on every open | `CircleBubbleMenu.kt:127`, `196-385`, `740-767` | **High** | M |
| 5 | `Color.parseColor(...)` string parsing in view construction and inside `onDraw` | `CircleBubbleMenu.kt:158-164`, `216`, `804-808`; `FloatingControlService.kt:175-177` | Med-High | S |
| 6 | `bringBubbleToFront()` = `removeView` + `addView` (full window destroy/create) on every menu open and every size-slider tick | `FloatingControlService.kt:1568-1579`, `388`, `1559` | Med-High | M |
| 7 | `smsTick` 1 Hz `Handler.postDelayed` polling drives `ExpiryRingView.invalidate()` even when nothing changed / ring is GONE | `CircleBubbleMenu.kt:100-106`, `469-471`, `820-838` | Med | S |
| 8 | `ExpiryRingView.onDraw` allocates a `String` via `"%02d:%02d".format(...)` + 1 boxed color per frame; no dirty-rect | `CircleBubbleMenu.kt:793-815`, `831` | Med | S |
| 9 | 8 `SpringAnimation` + 8 `SpringForce` objects allocated per open and per close, plus 8 `Handler.postDelayed` start posts | `CircleBubbleMenu.kt:451-467`, `643-664`, `705-728` | Med | M |
| 10 | Size-slider pref change rebuilds the whole bubble window per slider step | `FloatingControlService.kt:363-391`, `479-524` | Med | M |
| 11 | `applyLayout()` sets `layoutParams` 3× per button → up to ~9 forced measure/layout passes of the overlay | `CircleBubbleMenu.kt:541-585` | Med | S |
| 12 | `setRenderEffect` blur on the whole trigger / items layer — an offscreen render pass on an overlay window | `FloatingControlService.kt:1631-1652`; `CircleBubbleMenu.kt:676-690` | Med | S |
| 13 | `playMenuClosePulse()` chained `postDelayed` + `ObjectAnimator` + closure-per-step instead of one AnimatorSet | `FloatingControlService.kt:1661-1706` | Low-Med | S |
| 14 | Drag/tap uses raw coords + `ViewConfiguration` slop but no `VelocityTracker` / no fling settle; `onTouchEvent` returns `true` unconditionally | `FloatingControlService.kt:1224-1298` | Low-Med | M |
| 15 | `updateFlagPillPosition()` / `updateStatusLabelPosition()` re-post themselves via `View.post` while height == 0 | `FloatingControlService.kt:886-888`, `1124` | Low-Med | S |
| 16 | No hardware-layer hint on the animated items layer; overdraw from nested transparent FrameLayouts | `CircleBubbleMenu.kt:196-206`, `421-426` | Low-Med | S |
| 17 | `sheetBubbleScope` is a manual `CoroutineScope` (leak-shaped) + an IO DB read on the menu-open critical path | `FloatingControlService.kt:193`, `1537-1551` | Low-Med | S |
| 18 | `BitmapFactory.decodeResource` on each notification rebuild; no cached large icon | `FloatingControlService.kt:1434` | Low-Med | S |
| 19 | `applyGradientColors` allocates a new `GradientDrawable` per animation frame (~16 per 260 ms crossfade) | `FloatingControlService.kt:2473-2479`, `2448-2455` | Low | S |
| 20 | `ArgbEvaluator.evaluate` boxes two `Integer`s per frame; `ValueAnimator.ofFloat` + manual scale instead of `ViewPropertyAnimator` / `ofArgb` | `FloatingControlService.kt:2417-2463` | Low | S |
| 21 | `currentDragBounds()` / `currentSystemBarInsets()` allocate `Rect`s on every call (drag-frame hot path) | `FloatingControlService.kt:1174-1222` | Low | S |
| 22 | `show()` allocates ~12 short-lived Lists/Pairs/Triples per open | `CircleBubbleMenu.kt:147-177`, `207-211` | Low | S |
| 23 | `BubblePopupPlacer.place` allocates 3 lists of Pairs + lambdas per call | `BubblePopupPlacer.kt:28-37` | Low | S |
| 24 | Per-item `Handler.postDelayed` tap/double-tap/long-press state machine duplicates `GestureDetector` | `CircleBubbleMenu.kt:254-321` | Low | M |
| 25 | `Typeface.create(...)` called repeatedly in text update paths | `FloatingControlService.kt:2521`, `2553`, `2575` | Low | S |

---

## Detailed findings

### 1. Drag does 3 WindowManager IPCs + a full clearance recompute per touch frame — **High / M**

`FloatingControlService.kt:1240-1267`

```kotlin
MotionEvent.ACTION_MOVE -> {
    ...
    val (nx, ny) = clampBubbleToBounds(...)      // :1253
    lp.x = nx; lp.y = ny
    windowManager?.updateViewLayout(v, lp)        // :1260  IPC #1
    updateFlagPillPosition()                      // :1261  IPC #2 (+ measure)
    updateStatusLabelPosition()                   // :1262  IPC #3 (+ paint.measureText, + possibly a 4th IPC at :1113)
}
```

**Cost.** Touch events arrive at display rate (90–120 Hz on modern devices). Each `updateViewLayout` is a synchronous binder round-trip to `WindowManagerService` that triggers a full relayout of the window (`relayoutWindow` → surface resize/reposition + a new buffer). Three of these per frame is ~3× the per-frame system cost; on top of that each move frame calls `clampBubbleToBounds` → `currentDragBounds()` (which on API 30+ calls `currentWindowMetrics.windowInsets`, allocating `WindowMetrics`/`WindowInsets`/`Rect`) plus `menuClearancePx()` (2 SharedPreferences reads, finding #3), plus `updateStatusLabelPosition` does `tv.paint.measureText(tv.text.toString())` (`:1092`) — a `toString()` allocation and a text measure — whenever `tv.width == 0`. Worst case `updateStatusLabelPosition` performs a *fourth* `updateViewLayout` on the bubble itself (`:1113`) inside the same frame. Result: visible drag jank and elevated CPU/battery while the finger is down.

**Recommended fix.**
- Keep a single window and move it, but coalesce: compute the new position on every move, then apply it **once per frame** from a `Choreographer.FrameCallback` (`android.view.Choreographer.getInstance().postFrameCallback`) instead of once per `MotionEvent`. This is the documented way to align work with vsync.
- Better still, do not move three windows: put the bubble, flag pill and status label in **one** overlay window whose `LayoutParams` covers the region and position the children with `View.setX/setY` / `ViewPropertyAnimator` (no `updateViewLayout`, no binder IPC, no relayout — just a RenderNode property update, which is what `android.view.RenderNode`/`ViewPropertyAnimator` are designed for).
- Cache the drag bounds and clearance in fields recomputed only on `ACTION_DOWN`, `onConfigurationChanged`, and pref change.

---

### 2. Forever-running 1 Hz poll that rebuilds a notification (with a bitmap decode) every tick — **High / M**

`FloatingControlService.kt:211-221`, `1361-1371`, `1373-1442`

```kotlin
private val pollRunnable = object : Runnable {
    override fun run() {
        pollState()                                       // :213 binder IPC to :vpn process
        updateFlagPill()                                  // :214 2 more binder IPCs (countryCode, currentIp)
        updateForegroundNotification()                    // :215
        pollHandler.postDelayed(this,
            if (state == CONNECTING) 200L else 1000L)     // :216-219
    }
}
```

`updateForegroundNotification()` **always builds the full notification first** and only then compares text to decide whether to `notify()`:

```kotlin
val notification = buildForegroundNotification()   // :1364 — builds even when nothing changed
...
if (latestText == lastNotificationText && latestState == lastNotificationState) return  // :1367
```

and `buildForegroundNotification()` does, every single second:

```kotlin
.setLargeIcon(BitmapFactory.decodeResource(resources, R.drawable.app_icon))  // :1434
```

plus two `PendingIntent.getBroadcast(... FLAG_UPDATE_CURRENT)` (`:1375`, `:1380`) and one `getActivity` (`:1420`).

**Cost.** One full-resolution bitmap decode + allocation **per second, forever, while the bubble exists** — that is the single largest GC pressure source in this file (an `app_icon` PNG can easily be hundreds of KB of pixel data each tick), plus 3 `PendingIntent` system calls and 3+ cross-process binder calls per second. `CONNECTING_POLL_INTERVAL = 200L` (`:2712`) multiplies all of it by 5 during connect. This runs with the screen off too (a foreground service is not doze-exempt for handlers, but the loop still resumes and batches). Battery + jank.

**Recommended fix.**
- Compute the *state* first; return early before building anything. Build the `Notification` only when title/text/action actually changed.
- Decode the large icon **once** into a field (or use `IconCompat.createWithResource` / `NotificationCompat.Builder.setLargeIcon(Icon)`, which lets the system load it lazily and cache it), never per-notify.
- Hoist the three `PendingIntent`s into `onCreate` fields (`FLAG_IMMUTABLE or FLAG_UPDATE_CURRENT` already makes them reusable).
- Replace polling with push: the service already registers `ACTION_VPN_STATE_CHANGED` (`:262-266`) — the broadcast path already re-polls, so the timer is redundant except as a watchdog. Drop the 1 Hz loop to a low-frequency watchdog (e.g. 15 s) and keep the 200 ms cadence only while `state == CONNECTING` and bounded by `CONNECT_TIMEOUT_MS`.
- For repeating timers that must follow the frame clock, use `Choreographer`; for wall-clock ticks that may be deferred, `Handler.postDelayed` is fine but should be stopped when the bubble is invisible/collapsed.

---

### 3. SharedPreferences read on the drag hot path — **High / S**

`FloatingControlService.kt:992-1014`

```kotlin
private fun menuClearancePx(): IntArray {
    if (!isCircleStyle()) return intArrayOf(0, 0, 0, 0)
    val prefs = PreferenceManager.getDefaultSharedPreferences(this)   // :994
    val align = prefs.getString(PREF_CIRCLE_ALIGN, CIRCLE_SMALL) ...  // :995
    val sizeDp = prefs.getInt(PREF_CIRCLE_SIZE, ...)                  // :996
```

Called from `clampBubbleToBounds` (`:1029`), which is called from `ACTION_MOVE` (`:1253`), `reClampBubblePosition`, `nudgeBubbleIntoClearance`, `restoreBubblePosition`, `recreateBubbleForStyleChange`.

**Cost.** `getDefaultSharedPreferences` is a `HashMap` lookup plus, on first use, a disk load; `getString`/`getInt` take the `SharedPreferencesImpl` monitor and can **block on the load latch**. Doing that inside a per-touch-frame clamp risks a lock-contention stall on the UI thread → dropped frames. Also allocates a fresh `IntArray(4)` per call.

**Recommended fix.** Cache `align`/`sizeDp` in fields, refreshed from the existing `OnSharedPreferenceChangeListener` (`:356-416`) which already watches `PREF_CIRCLE_ALIGN` and `PREF_CIRCLE_SIZE`. Cache the resulting clearance `IntArray` too and invalidate it on pref change / `onConfigurationChanged`. Official guidance: keep `SharedPreferences` off frame-critical paths (StrictMode's `detectDiskReads` flags exactly this).

---

### 4. Full view-tree rebuild on every menu open — **High / M**

`CircleBubbleMenu.kt:127` (`show()` calls `hideNow()` first), `:196-385`, `:740-767`

Every open constructs from scratch: 1 root `FrameLayout` + 1 box `FrameLayout` (`:196-205`), then per item a `FrameLayout` + a `GradientDrawable` (`:214-217`) + an `ImageView` with `setImageResource` (`:218-224`) + `FrameLayout.LayoutParams` (`:226`, `:374`), plus for SMS an `ExpiryRingView` (`:232`), plus up to 5 listener lambdas per item (`:249`, `:269`, `:276`, `:323`, `:334`, `:343`, `:349`, `:360`), plus an optional `TextView` with a `Typeface.create` (`:393-403`). `finishRemove()` (`:740`) drops all of it (`btnViews = emptyList()`, `container = null`) and removes the window.

**Cost.** ~20 View/Drawable objects, 4 drawable inflations (`setImageResource` decodes/inflates a VectorDrawable unless the resource cache holds it), 8+ lambda allocations, one `WindowManager.addView` (window create = surface allocation) — all synchronously on the UI thread at the exact moment the user expects the animation to start. That is a classic first-frame jank source, and with `setImageResource` inside `show()` it also does resource I/O on the main thread on a cold cache. `hideNow()` at `:127` also means a re-show always destroys and re-creates the overlay **window**, not just the views.

**Recommended fix.** Build the view tree once (lazily on first open) and **recycle** it: keep `rootView`/`btnViews` alive, and on hide either (a) keep the window attached with `root.visibility = GONE`, or (b) `windowManager.removeView` but keep the view objects for re-`addView`. Only mutate what changes (icon resource, tint, translations, `LayoutParams` margins). Cache the `Drawable`s (`AppCompatResources.getDrawable` + `Drawable.mutate()` per instance, or `ImageView.setImageDrawable` with a shared `ConstantState`). This is the standard "view recycling" pattern; the whole open path then becomes property writes only.

---

### 5. `Color.parseColor()` string parsing in construction and in `onDraw` — Med-High / S

`CircleBubbleMenu.kt:158-164`, `:216`, `:231`, `:678`; `:804-808` (inside `onDraw`); `FloatingControlService.kt:175-177`, `:681`

The worst instance is in the draw path:

```kotlin
override fun onDraw(c: Canvas) {
    ...
    val col = when {
        leftSec < 60  -> Color.parseColor("#CC2D4F")   // :805
        leftSec < 180 -> Color.parseColor("#F59E0B")   // :806
        else          -> Color.parseColor("#16A34A")   // :807
    }
```

**Cost.** `Color.parseColor` does string validation + `Long.parseLong(String, 16)` on a substring — string allocation and parsing **inside `onDraw`**, i.e. per drawn frame. `lockGreen()`/`lockErr()`/`lockSpin()` (`FloatingControlService.kt:175-177`) are functions that re-parse on every call, and they are called from `updateBubbleUi`, `updateStatusLabel`, `startLockSequence`, `doLockFlash`.

**Recommended fix.** Declare the colors as `@ColorInt` constants (`private const val COLOR_DANGER = 0xFFCC2D4F.toInt()`) or as `<color>` resources read once via `ContextCompat.getColor`. Never call `Color.parseColor` inside `onDraw`, per the "Avoid allocations / expensive work in onDraw" guidance in the Android performance docs.

---

### 6. `removeView` + `addView` to re-order the overlay z-order — Med-High / M

`FloatingControlService.kt:1568-1579`, called at `:1559` (every menu open) and `:388` (every size-slider change while open)

```kotlin
private fun bringBubbleToFront() {
    ...
    wm.removeView(view)
    wm.addView(view, lp)
}
```

**Cost.** Destroying and re-creating a system overlay window: surface destroy, `ViewRootImpl` teardown, then a fresh `ViewRootImpl` + surface + **full measure/layout/draw** of the bubble view hierarchy, plus two binder transactions. It also cancels in-flight `ViewPropertyAnimator`s implicitly (detach) and is the reason the code carries defensive `circleMenuOpen` / `circleGlyphGen` bookkeeping (`:1606-1655`). On the size slider (`:388`) this fires per slider tick.

**Recommended fix.** Do not re-order windows; order them once by choosing the window position deliberately. Options, in order of preference:
1. Put the trigger and the menu items in the **same** overlay window (`FrameLayout` z-order is free — `View.setZ` / `bringChildToFront` / `ViewGroup.setChildrenDrawingOrderEnabled`), which removes this method entirely.
2. Add the menu window *below* the trigger by giving it a lower window layer — e.g. `TYPE_APPLICATION_OVERLAY` windows are ordered by insertion, but `WindowManager.LayoutParams` also honors `flags`/`z` grouping; adding the menu first and never re-adding the trigger works when the trigger window is created last and never removed.
3. If a re-add is truly needed, at minimum debounce it (once per open, not per slider tick).

---

### 7. 1 Hz `Handler` polling tick for the SMS expiry ring — Med / S

`CircleBubbleMenu.kt:100-106`, started at `:469-471`, also re-invoked from `applyLayout` (`:607`)

```kotlin
private val smsTick = object : Runnable {
    override fun run() {
        if (!isShowing()) return
        refreshSmsIcon()
        handler.postDelayed(this, 1000)
    }
}
```

`refreshSmsIcon()` (`:820-838`) each tick: reads `System.currentTimeMillis()`, iterates `SmsWatcher.mine` with `maxOfOrNull { it.born }` (a lambda + iterator per tick over a Compose `SnapshotStateList` — snapshot reads are not free), does 4 visibility comparisons, and calls `ring.invalidate()` (`:831`).

**Cost.** Wakes the UI thread once a second for the whole time the menu is open even when **no number is alive** (`alive == false` still costs the snapshot-list scan and the tick reschedule). Each `invalidate()` on a visible ring schedules a traversal + RenderNode re-record for the whole view.

**Recommended fix.**
- Bail out of the reschedule entirely when nothing is alive; restart the tick from the SMS provisioning callback instead (`FloatingControlService.kt:1800-1807`).
- Only `invalidate()` when the *displayed second* actually changed (compare the previous `leftSec`); today `refreshSmsIcon` re-invalidates unconditionally in the alive branch.
- For an animated countdown, prefer a `ValueAnimator` over `SMS_EXPIRE_SEC` with `addUpdateListener` (it is driven by `Choreographer`, auto-pauses with the window, and is cancelled on detach) rather than a hand-rolled `postDelayed` chain.
- Cache the `maxOfOrNull { it.born }` value: it only changes when a number is provisioned.

---

### 8. `ExpiryRingView.onDraw` allocates a String per frame; full-view invalidate — Med / S

`CircleBubbleMenu.kt:793-815`

```kotlin
override fun onDraw(c: Canvas) {
    ...
    oval.set(pad, pad, w - pad, w - pad)                       // :801 (good — reused RectF)
    ...
    val col = when { ... Color.parseColor(...) }               // :804-808 (see #5)
    val label = "%02d:%02d".format(leftSec / 60, leftSec % 60) // :813
    c.drawText(label, ...)                                     // :814
}
```

**Cost.** `String.format` per draw allocates a `Formatter`, a `StringBuilder`, boxed `Long`s for the varargs array, and the result `String` — the canonical "string formatting in a hot loop" anti-pattern. Also `textPaint.descent()`/`ascent()` are recomputed each draw, and `textPaint.textSize = w * 0.22f` (`:811`) invalidates the Paint's internal text-measurement cache on every draw.

The `Paint`/`RectF` reuse here (`:776-791`) is **correct and worth keeping** — the problem is only the per-frame String + color parse + Paint mutation.

**Recommended fix.**
- Keep a reusable `StringBuilder` and append two-digit values manually, or precompute the 421 possible `"mm:ss"` strings lazily in a small cache; at minimum use `java.util.Formatter` bound to a reused `StringBuilder`.
- Set `textSize` and stroke widths in `onSizeChanged(w, h, oldw, oldh)` instead of `onDraw`; cache the baseline offset `-(descent + ascent)/2` there too.
- `invalidate()` at `:831` repaints the whole ring. Since only the arc sweep and the label change, use the dirty-rect overload `invalidate(l, t, r, b)` or, better, split the static background arc into a separate cached layer. On API 29+ the arc+text can be recorded into a `android.graphics.RenderNode` and re-issued, so only the changed portion is re-recorded.

---

### 9. 8 spring animators + 8 delayed Handler posts allocated per open *and* per close — Med / M

`CircleBubbleMenu.kt:451-467` (open), `:643-664` (close), `:705-728` (`springTo`/`startSpring`)

```kotlin
private fun springTo(...): SpringAnimation {
    return SpringAnimation(view, property, target).apply {
        spring = SpringForce(target).apply { ... }       // :706-711  2 objects per axis per item
    }
}
private fun startSpring(spring, delayMs, gen, requireOpen) {
    handler.postDelayed({ ... spring.start() }, delayMs) // :719-727  1 Runnable+closure per axis per item
}
```

Per open: 8 `SpringAnimation` + 8 `SpringForce` + 8 `Runnable` closures + 4 `ViewPropertyAnimator` alpha animations (`:459`). Per close: another 8 + 8 + 8 + 4 shrink animators (`:661-663`) + 1–2 `postDelayed` failsafes (`:672`, `:692`). Nothing is pooled.

**Cost.** Moderate GC churn exactly at the frame where the animation must start smoothly. Also: each `SpringAnimation` registers its own `AnimationHandler` frame callback, so 8 independent `Choreographer` callbacks run per frame during the open/close.

**Recommended fix.** Create the 8 `SpringAnimation`s **once** alongside the recycled views (#4) and just `animateToFinalPosition(target)` on re-use — that is the documented reuse API for `SpringAnimation` (`DynamicAnimation.animateToFinalPosition`) and it avoids re-allocating `SpringForce`. For the stagger, `SpringAnimation` genuinely has no start delay, but a single `Choreographer.FrameCallback` (or one `ValueAnimator` driving all four via `animateToFinalPosition`) replaces 8 `postDelayed`s with 1 callback. Also note `SpringAnimation` has no `setStartDelay`, so the failsafe `postDelayed` at `:672`/`:692` is reasonable — but the *duplicate* failsafe at `:692` fires even after the `withEndAction` already ran; guard it with the existing `animGen`.

---

### 10. Size-slider pref change rebuilds the entire bubble window — Med / M

`FloatingControlService.kt:363-391` → `recreateBubbleForStyleChange(...)` at `:479-524`

`OnSharedPreferenceChangeListener` fires **per slider step**. Each fire runs `recreateBubbleForStyleChange`, which: hides 3 overlays, stops 3 timers/animators, removes 3 windows, **constructs 3 new view trees** (`createBubbleView` at `:496` itself re-reads SharedPreferences at `:576` and `:644-650`), adds 3 windows back, repositions them (3 more `updateViewLayout`), then calls `updateBubbleUi` and `updateForegroundNotification` (which rebuilds the notification + decodes the bitmap, see #2), then `circleMenu?.updateSize()` (`:387`) and `bringBubbleToFront()` (`:388`, see #6 — another remove+add).

**Cost.** For a slider dragged across 40 values that is ~40 × (6 window destroy/creates + 3 view-tree constructions + 40 bitmap decodes). Guaranteed jank and a burst of GC.

**Recommended fix.** Debounce the pref callback (e.g. 100–150 ms via `Handler.removeCallbacks`/`postDelayed`, or collect the pref as a `Flow` with `debounce`). Then resize in place: the bubble window only needs `params.width/height` updated + one `updateViewLayout`, and the child `LayoutParams` updated — no teardown. `CircleBubbleMenu.updateSize` already demonstrates the in-place path (`:481-484`).

---

### 11. `applyLayout` assigns `layoutParams` three times per button → repeated measure/layout — Med / S

`CircleBubbleMenu.kt:541-585`

```kotlin
btn.getChildAt(0)?.layoutParams = glp     // :550   requestLayout #1
ring.layoutParams = rlp                   // :558   requestLayout #2 (SMS item)
btn.layoutParams = blp                    // :573   requestLayout #3
```

Each `setLayoutParams` calls `requestLayout()`, which walks up to the `ViewRootImpl` and schedules a traversal. Doing it 9+ times in one pass is harmless *only* because traversals coalesce — but each call still walks the parent chain and sets `PFLAG_FORCE_LAYOUT` on every ancestor, and `applyLayout` finishes with a `windowManager.updateViewLayout` (`:601`) that forces a **full** measure/layout/draw of the overlay anyway.

Additionally `sub.post { sub.translationX = -sub.width / 2f }` (`:593`, also `:415`) defers to another frame, guaranteeing a second layout+draw pass for the sub-label.

**Cost.** Extra measure/layout passes per slider tick, compounding #10.

**Recommended fix.** Mutate the `LayoutParams` objects in place without reassigning (the child already holds the same instance — reassigning the *same* object is what triggers the redundant `requestLayout`), then call `box.requestLayout()` **once** at the end. For the label centering, set `Gravity.CENTER_HORIZONTAL` on the label's `FrameLayout.LayoutParams` relative to a zero-width anchor, or use `View.addOnLayoutChangeListener` once, instead of a per-relayout `post`.

---

### 12. `RenderEffect` blur on a whole overlay window — Med / S

`FloatingControlService.kt:1631-1652` (trigger blur on every glyph swap, i.e. every open *and* close tap) and `CircleBubbleMenu.kt:676-690` (blur of the entire items layer for the whole close animation)

```kotlin
trigger?.setRenderEffect(RenderEffect.createBlurEffect(10f, 10f, Shader.TileMode.CLAMP))  // :1634
...
pollHandler.postDelayed({ ... trigger?.setRenderEffect(null) }, 220)                      // :1646-1652
```

```kotlin
box.setRenderEffect(RenderEffect.createBlurEffect(1f, 1f, Shader.TileMode.CLAMP))  // :678
box.animate().rotation(-360f).setDuration(totalMs)...                              // :686-690
```

**Cost.** `setRenderEffect` forces the view into an **offscreen render target** and runs a blur shader every frame the view is dirty. In `CircleBubbleMenu.hide()` the blurred layer is *also* being rotated for `closeStaggerMs * (n + 2)` = 420 ms, so the blur is re-evaluated ~25–50 times on a layer containing 4 bubbles. A 1 px blur radius (`:679`) is visually near-invisible but costs a full offscreen pass — poor cost/benefit. On mid-range GPUs this is a measurable per-frame GPU cost on an always-on-top overlay.

**Recommended fix.** Drop the 1 px blur at `CircleBubbleMenu.kt:678` entirely (it is below perceptual threshold). For the 200 ms glyph swap, a plain crossfade of two `ImageView`s via `ViewPropertyAnimator.alpha` achieves the effect with no offscreen pass; if the blur is kept, apply it to the small `iconView` only, not to the whole `trigger` container, and clear it in the animator's `withEndAction` **and** the timer. Also note that the clear at `:1646` is posted on `pollHandler`, mixing the glyph lifecycle into the polling handler's queue.

---

### 13. Chained `postDelayed` + per-step closures for the close pulse — Low-Med / S

`FloatingControlService.kt:1661-1706`

A local recursive `fun step(i: Int)` re-enters through `v.animate()...withEndAction { v.postDelayed({ step(i + 1) }, 70) }` (`:1696-1698`) for 3 steps, alongside an `ObjectAnimator` shake with `repeatCount = 5` (`:1666-1671`). Each step allocates a `ViewPropertyAnimator` configuration, a `withEndAction` closure, a `Runnable`, and posts to the view's handler. `v.postDelayed` also survives detach in ways the animator does not, so a removed view can still be scheduled.

**Recommended fix.** Express the whole sequence as one `AnimatorSet` with `playSequentially`/`setStartDelay` on `ObjectAnimator`s (`android.animation.AnimatorSet`), which the platform drives from a single `AnimationHandler`/`Choreographer` callback and which can be cancelled as a unit on detach. Keep a field reference so `onDestroy` / `recreateBubbleForStyleChange` can `cancel()` it (currently these pulses are not cancelled anywhere).

---

### 14. Touch handling: no `VelocityTracker`, unconditional event consumption — Low-Med / M

`FloatingControlService.kt:1224-1298`

Good: `touchSlop` comes from `ViewConfiguration.get(this).scaledTouchSlop` (`:290`) — that is the correct API. Missing:
- No `VelocityTracker` (`android.view.VelocityTracker.obtain()` / `computeCurrentVelocity` / `recycle`), so there is no fling-to-edge settle; the bubble stops dead wherever the finger lifts. Users then drag more, which costs more frames.
- The listener returns `true` for **every** event (`:1296`) including `ACTION_DOWN` on a non-draggable tap, so the window always consumes the gesture stream.
- The long-press timeout is a hard-coded `480` (`:1235`) instead of `ViewConfiguration.getLongPressTimeout()`; the double-tap window in `CircleBubbleMenu.kt:297` is a hard-coded `300` instead of `ViewConfiguration.getDoubleTapTimeout()`, and the 550 ms long-press at `CircleBubbleMenu.kt:281` is a third, different value. Inconsistent timings mean the user retries gestures — more frames, more work.
- `abs(event.rawX - initialRawX) > touchSlop` (`:1245`) compares each axis independently rather than the euclidean distance, so the effective slop is anisotropic.

**Recommended fix.** Use `VelocityTracker` for a settle animation driven by `SpringAnimation`/`FlingAnimation` (androidx.dynamicanimation is already a dependency — see `CircleBubbleMenu.kt:20-22`), and pull all gesture timings from `ViewConfiguration` (`getLongPressTimeout`, `getDoubleTapTimeout`, `getScaledTouchSlop`). Return `false` from `ACTION_DOWN` when neither drag nor long-press is plausible so events are not needlessly retained.

---

### 15. Self-reposting layout callbacks — Low-Med / S

`FloatingControlService.kt:886-888` and `:1124`

```kotlin
if (flagPillView?.height ?: 0 == 0) { flagPillView?.post { updateFlagPillPosition() } }   // :886-888
if (statusLabelView?.height ?: 0 == 0) statusLabelView?.post { updateStatusLabelPosition() } // :1124
```

If the view legitimately has height 0 (e.g. `visibility == GONE`, which is the normal state for the flag pill in circle style — see `:801-804`), this **re-posts itself every frame**: each iteration performs an `updateViewLayout` IPC (`:879`), a `currentDragBounds()` + `currentSystemBarInsets()` pair of `Rect` allocations, and then posts again. A GONE view never gains height, so this is an unbounded self-feeding loop of window IPCs.

Note the operator-precedence bug that makes it worse: `flagPillView?.height ?: 0 == 0` parses as `height ?: (0 == 0)` — a type error that only compiles because of `Any` inference, and evaluates to a non-null `Int` (truthy path), i.e. the condition behaves unexpectedly. Worth verifying against the intended `(flagPillView?.height ?: 0) == 0`.

**Recommended fix.** Bail out early when the view is not `VISIBLE`, and use a one-shot `View.doOnPreDraw` / `addOnLayoutChangeListener` that removes itself, or `ViewTreeObserver.OnGlobalLayoutListener` with explicit removal, instead of an unconditional `post` recursion.

---

### 16. No hardware-layer hint on the animated layer; avoidable overdraw — Low-Med / S

`CircleBubbleMenu.kt:196-206` (root + box `FrameLayout`s), `:421-426` (box `MATCH_PARENT` over root), `:686-690` (box rotated)

The items layer (`box`) is rotated a full 360° over ~420 ms while 4 children spring and scale. Nothing calls `setLayerType(View.LAYER_TYPE_HARDWARE, null)` for the duration, and nothing calls `ViewPropertyAnimator.withLayer()`. Meanwhile the hierarchy is root `FrameLayout` (`MATCH_PARENT`) → box `FrameLayout` (`MATCH_PARENT`) → 4 item `FrameLayout`s each with a `GradientDrawable` background → `ImageView` (+ ring). The two full-window transparent `FrameLayout`s add a layout/draw level for no visual content.

**Cost.** Without a hardware layer, the rotated subtree is re-recorded/re-rasterized every frame; with overlapping transparent containers the GPU also pays overdraw on an always-on-top window.

**Recommended fix.** Use `box.animate().rotation(-360f).withLayer()` — the documented one-liner that promotes the view to `LAYER_TYPE_HARDWARE` for the animation and restores `LAYER_TYPE_NONE` at the end. Collapse `root`+`box` into a single `FrameLayout` (the root adds nothing: `box` is `MATCH_PARENT` inside it and the pivot could live on the single view). Verify with **Developer options → Debug GPU overdraw** and **Profile HWUI rendering**.

---

### 17. Manually-managed `CoroutineScope` + IO DB read on the menu-open path — Low-Med / S

`FloatingControlService.kt:193`, `:1537-1551`

```kotlin
private val sheetBubbleScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)  // :193
...
sheetBubbleScope.launch {
    val sheetIcon = withContext(Dispatchers.IO) { sheetBubbleCoordinator.bubbleIconRes() }   // :1538-1539
    ...
    circleMenu?.show(...)                                                                     // :1542
}
```

**Cost / risk.**
- The scope is cancelled in `onDestroy` (`:551`) — good — but it is **not** tied to a lifecycle owner, so any path that leaks the service reference (e.g. a long-running `withContext(Dispatchers.IO)` in `sheetBubbleCoordinator.checkFile`, `:1975-1977`) keeps the `Service` and its view hierarchy alive past `onDestroy` until the IO block returns. `runSheetBubbleCheck` (`:1973-1999`) launches on the same scope and can run for a long time.
- Functionally more important for perceived performance: the **menu cannot open until a database round-trip completes**. Every open is gated on `Dispatchers.IO` latency, so a cold DB/page-cache miss shows up as "the bubble doesn't respond to my tap".

**Recommended fix.** Make the service a `LifecycleService` (`androidx.lifecycle:lifecycle-service`) and use `lifecycleScope`, which cancels automatically and is the officially recommended scope. Separately, show the menu **immediately** with the cached/last-known sheet icon and update the single `ImageView` when the IO result arrives (`ImageView.setImageResource` on an already-visible view is cheap). Cache `bubbleIconRes()` in a field invalidated by the sheet-file pref.

---

### 18. Bitmap decoded per notification build — Low-Med / S

`FloatingControlService.kt:1434` — covered in #2, called out separately because the one-line fix (decode once into a field, or use `IconCompat`) removes a per-second full-image decode + allocation. `BitmapFactory.decodeResource` with no `BitmapFactory.Options` also decodes at full resource resolution regardless of the notification icon size the system will use.

---

### 19. New `GradientDrawable` per animation frame — Low / S

`FloatingControlService.kt:2473-2479`, driven from `:2448-2455`

```kotlin
addUpdateListener { anim -> ... applyGradientColors(intArrayOf(start, end)) }  // :2450-2455
...
private fun applyGradientColors(colors: IntArray) {
    val drawable = GradientDrawable(Orientation.TL_BR, colors)  // :2476
    drawable.shape = GradientDrawable.OVAL                      // :2477
    view.background = drawable                                  // :2478
}
```

Per frame: 1 `IntArray`, 1 `GradientDrawable` (+ its `GradientState`), and `setBackground` → `requestLayout` if padding differs + `invalidate`. ~16 drawables per 260 ms crossfade.

The comment at `:2469-2471` justifies this with minSdk 21, but `GradientDrawable.setColors(int[])` is **API 24**, and the module's `minSdk` should be re-checked (`app/build.gradle`); if it is ≥ 24 the fix is a one-liner. For API 21–23 the same thing can be achieved by mutating a single retained `GradientDrawable` via `setColor(int)` (API 1) when start == end — and per `stateGradient` (`:2688-2707`) **start always equals end**, so the gradient is flat and `setColor` alone suffices today.

**Recommended fix.** Retain one `GradientDrawable` and call `setColor(color)` (or `setColors` on API 24+) in the update listener. Better: use `ValueAnimator.ofArgb(oldColor, newColor)` (API 21+) which avoids `ArgbEvaluator` boxing, and call `setColor` directly.

---

### 20. `ArgbEvaluator` boxing + `ofFloat`-with-manual-scale instead of `ViewPropertyAnimator` — Low / S

`FloatingControlService.kt:2417-2430` (breathing) and `:2447-2455` (color)

```kotlin
val animator = ValueAnimator.ofFloat(1f, 1.07f).apply {
    addUpdateListener { anim -> val scale = anim.animatedValue as Float; view.scaleX = scale; view.scaleY = scale }  // :2422-2426
}
```
`anim.animatedValue` returns `Object` → an autoboxed `Float` per frame per animator; `ArgbEvaluator.evaluate` (`:2452-2453`) returns boxed `Integer`s, two per frame. At 90 Hz that is ~270 short-lived boxes/second while connecting.

**Recommended fix.** Use `anim.animatedFraction` (primitive `float`, no boxing) and interpolate manually, or drive the scale with `ObjectAnimator.ofFloat(view, View.SCALE_X, ...)` (`View.SCALE_X`/`SCALE_Y` are `FloatProperty`s — no boxing) with a second animator, or `ViewPropertyAnimator` for the non-repeating case. For colors use `ValueAnimator.ofArgb` (primitive int path).

Also: `startBreathing` (`:2417`) sets `view.scaleX/scaleY` directly, which is fine (RenderNode property), but the animator is `INFINITE`/`REVERSE` and only stopped via `stopBreathing()` (`:2432`) from `updateBubbleUi` and `onDestroy` — it keeps running with the screen off during a long `CONNECTING` state, burning a `Choreographer` callback per frame for an invisible animation. Guard it on window visibility (`View.onVisibilityAggregated` / `Application.ActivityLifecycleCallbacks`, or simply pause when the display is off via `DisplayManager.DisplayListener`).

---

### 21. `Rect` allocations on the drag hot path — Low / S

`FloatingControlService.kt:1174-1200` (`currentSystemBarInsets` returns a new `Rect` on every call, `:1180`, `:1199`) and `:1207-1222` (`currentDragBounds` returns a new `Rect`, `:1214`, `:1221`). Both are called from `updateFlagPillPosition` (`:849-850`), `updateStatusLabelPosition` (`:1083-1084`) and `clampBubbleToBounds` (`:1023`) — i.e. up to **6 `Rect` allocations per drag frame**, plus the `WindowMetrics`/`WindowInsets` objects the platform allocates inside `currentWindowMetrics`.

The non-API-30 fallback additionally calls `resources.getIdentifier("status_bar_height", "dimen", "android")` (`:1187`, `:1194`) — a **string-keyed resource table lookup**, one of the slowest resource APIs — on every call.

**Recommended fix.** Keep two reusable `Rect` fields and fill them with `Rect.set(...)`, recomputed only on `ACTION_DOWN` / `onConfigurationChanged` / display change. Cache the two `getIdentifier` results in `onCreate`.

---

### 22. Short-lived collection/tuple allocations in `show()` — Low / S

`CircleBubbleMenu.kt:147-153` (`List(4) { Pair }` + a `listOf`), `:156-165` (a `listOf` of 4 `Triple`s, each holding a boxed `Int`/`Float`), `:166` (`listOf` of 4 lambdas), `:173-177` (`pts.map` → new list of Pairs), `:207-208` (2 `mutableListOf`), `:380` (`Pair` per item), `:451` (`mutableListOf`). Roughly 12 containers + ~20 `Pair`/`Triple` objects per open.

Individually trivial; collectively they land in the same frame as the window creation (#4) and the animator allocation (#9), so they contribute to a GC pause exactly at animation start. The same pattern is duplicated verbatim in `applyLayout` (`:509-523`, `:537-538`).

**Recommended fix.** Once views are recycled (#4), hoist the icon/tint/fraction tables to `private val` arrays of primitives (`IntArray`/`FloatArray`) at class scope, and write slot positions into a preallocated `IntArray(8)` instead of a `List<Pair<Int,Int>>`.

---

### 23. `BubblePopupPlacer.place` allocates lists of Pairs per call — Low / S

`BubblePopupPlacer.kt:28-37`

```kotlin
fun fitsH(value: Int) = value >= panelWidth                                        // :28  local fun object
val horizontal = listOf("right" to right, "left" to left).filter { fitsH(...) }    // :30  2 lists + 2 Pairs + lambda
val vertical   = listOf("bottom" to bottom, "top" to top).filter { fitsV(...) }    // :31  2 lists + 2 Pairs + lambda
val side = when { ... maxByOrNull { it.second }!! ... else -> listOf(4 pairs)... } // :32-37  up to 1 more list + 4 Pairs
```

Plus `String` side identifiers compared by value (`:39`, `:44`) rather than an enum.

Not a hot path today (called once per popup open), but it is pure integer math wrapped in ~6 allocations. If it is ever called during a drag (it is the shared placer for the popup shells), it becomes one.

**Recommended fix.** Replace the `String` side with an `enum class Side` and compute the max with plain comparisons — the whole function is branch-only, zero-allocation except the returned data class.

---

### 24. Hand-rolled tap/double-tap/long-press state machine per item — Low / M

`CircleBubbleMenu.kt:254-321`

Per SMS item: `lastTap`/`singlePending`/`lpFired` captured vars, a `lpRunnable`, a fresh `Runnable { onSmsTap() }` per tap (`:305`), a `postDelayed` per down (`:281`) and per up (`:306`), plus 3 `ViewPropertyAnimator` calls (`:280`, `:287`, `:314`). Every single tap therefore allocates a `Runnable` + a `Message` and holds a 300 ms deferred callback — which also means a plain single tap has a **300 ms mandatory latency** (`:306`) before anything happens.

**Recommended fix.** Use `android.view.GestureDetector` with `SimpleOnGestureListener` (`onSingleTapConfirmed` / `onDoubleTap` / `onLongPress`) — one shared detector, platform-tuned timings from `ViewConfiguration`, no per-tap allocation. The perceived-latency win (correct `onSingleTapConfirmed` semantics + no 300 ms artificial hold on the non-double-tap-capable items) is bigger than the allocation win.

---

### 25. `Typeface.create` in text-update paths — Low / S

`FloatingControlService.kt:2521` (inside `updateTimerText`, which runs **once per second** while connected in lock style), `:2553`, `:2575`; `CircleBubbleMenu.kt:397-400`, `:786-789`.

`Typeface.create(Typeface.DEFAULT, Typeface.BOLD)` hits a static cache, so it is cheap-ish, but assigning `view.typeface = ...` unconditionally at `:2521` invalidates the `TextView`'s layout and forces a re-measure **every second** even though the typeface never changes. Same for `view.textSize = 11f` (`:2520`) and `letterSpacing` (`:2522`) — each setter calls `requestLayout()` + `invalidate()`.

**Recommended fix.** Set typeface/size/letterSpacing once at view creation (`createBubbleView`, `:617-633`) and only assign `view.text` in `updateTimerText`. Hoist the `Typeface` to a `companion object val`.

---

## Things the code already does right (keep)

- `ExpiryRingView` reuses `Paint` and `RectF` fields instead of allocating in `onDraw` — `CircleBubbleMenu.kt:776-791`. Only the `String`/`Color.parseColor` remain (#5, #8).
- `touchSlop` is sourced from `ViewConfiguration.get(this).scaledTouchSlop` — `FloatingControlService.kt:290`.
- `FLAG_NOT_FOCUSABLE` is set on all three overlay windows (`:1138`, `:768`, `:914`) and `FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCH_MODAL` on the menu (`CircleBubbleMenu.kt:433-434`), so the overlay never steals IME focus or blocks the window behind it.
- The menu window is sized to the menu's **bounding box** rather than full-screen (`CircleBubbleMenu.kt:182-189`, `:429-439`) — this is a real win: a full-screen translucent overlay would cost full-screen composition + overdraw on every frame the app below redraws.
- `updateForegroundNotification` at least *tries* to dedupe `notify()` calls (`:1367`) — the fix in #2 is to move the dedupe before the build.
- `SmsOtpService` holds its `PARTIAL_WAKE_LOCK` with a timeout and only while numbers wait (`util/SmsOtpService.kt:88-99`) — correctly scoped, and `FloatingControlService` itself holds **no** wakelock. No wakelock waste found in the bubble code.
- `hideNow()` calls `handler.removeCallbacksAndMessages(null)` (`CircleBubbleMenu.kt:699`), so the delayed-callback set is cleaned on teardown; the `animGen` generation guard (`:714-728`) correctly prevents stale spring starts.

---

## Suggested order of work

1. **#2 + #18** — delete the per-second bitmap decode and build-before-compare. One afternoon, largest battery win.
2. **#3 + #21** — cache prefs/bounds off the drag path. Trivial, removes the disk-read-on-frame risk.
3. **#1** — coalesce drag updates onto `Choreographer`, ideally collapse the three windows into one. Largest jank win.
4. **#4 + #9** — recycle the menu view tree and its `SpringAnimation`s. Largest open/close-smoothness win.
5. **#10 + #6** — debounce the size slider, drop the `removeView`/`addView` z-order hack.
6. **#5, #7, #8, #12, #16** — draw-path cleanups (colors, tick, dirty rect, blur, `withLayer()`).
7. Remaining Low items as opportunistic cleanup.

## How to verify

- **Perfetto / Android Studio Profiler** system trace while dragging the bubble: count `relayoutWindow` slices per frame (target: 1, currently up to 4).
- **Memory Profiler → Record Java/Kotlin allocations** for 10 s idle with the bubble up: the `Bitmap`/`byte[]` from #2 should disappear entirely.
- **Developer options → Profile HWUI rendering** + **Debug GPU overdraw** during menu open/close for #12/#16.
- **StrictMode** `detectDiskReads().penaltyLog()` in the service process will flag #3 immediately.
