package net.typeblog.socks

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import net.typeblog.socks.util.Constants.CIRCLE_DOWN
import net.typeblog.socks.util.Constants.CIRCLE_LEFT
import net.typeblog.socks.util.Constants.CIRCLE_RIGHT
import net.typeblog.socks.util.Constants.CIRCLE_UP

/**
 * Floating circle menu: 4 option bubbles (Proxy/SMS/Sheet/Name) arranged
 * around the floating bubble's anchor point. Layout math mirrors
 * circle-bubble.html + ObsidianUI CircleMenu: fixed GAP 58dp / OFF 62dp for
 * lines, fixed 68.75dp radius for the small circle, button diameter from the
 * size slider (trigger = size, items = size - 2).
 *
 * Motion is an exact port of the HTML mockup (ObsidianUI CircleMenu):
 * items ride springs (stiffness 300, damping 30 -> ratio 0.866) between the
 * anchor and their slot; open stagger 20ms circle / 60ms lines; close stagger
 * 70ms forward for circle, reverse cascade for lines; the shared items-layer
 * spins a full -360 turn ONLY for circle (lines return straight, like the
 * HTML); buttons grow 1.1x on press (touch equivalent of the mockup's hover
 * grow). No hover labels — the mockup's cm-item-label was removed.
 *
 * Touch transparency (per official WindowManager.LayoutParams docs):
 * FLAG_NOT_TOUCH_MODAL sends pointer events OUTSIDE a window to the windows
 * behind it — but a full-screen scrim has no "outside", so it swallowed every
 * tap on the device. This window is therefore sized to the menu's own
 * bounding box (anchor + all slots + spin sweep + margin) instead of the full
 * screen. Taps outside it fall through to the app below and never collapse
 * the menu — it only closes via the trigger bubble or one of its 4 actions.
 * The box must fully contain the close spin sweep, or bubbles clip mid-turn.
 */
class CircleBubbleMenu(
    private val context: Context,
    private val onProxyTap: () -> Unit,
    private val onProxyLongPress: () -> Unit,
    private val onSmsTap: () -> Unit,
    private val onSmsDoubleTap: () -> Unit = {},
    private val onSmsLongPress: () -> Unit = {},
    private val onSheetTap: () -> Unit,
    private val onNameTap: () -> Unit,
    private val onDismissed: () -> Unit = {},
    private val onCloseAnim: () -> Unit = {}
) {
    // HTML mockup motion constants (ObsidianUI CircleMenu + circle-bubble.html).
    private val openStaggerCircleMs = 20L
    private val openStaggerLineMs = 60L
    private val closeStaggerMs = 70L
    private val springStiffness = 300f
    // framer damping 30 at stiffness 300 -> ratio 30 / (2 * sqrt(300)).
    private val springDamping = 0.866f

    private fun isLineAlign(align: String): Boolean =
        align == CIRCLE_UP || align == CIRCLE_DOWN ||
            align == CIRCLE_RIGHT || align == CIRCLE_LEFT

    private var windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var rootView: FrameLayout? = null
    private var container: FrameLayout? = null
    private var slots: List<Pair<Float, Float>> = emptyList()
    private var btnViews: List<FrameLayout> = emptyList()
    private var springs: List<SpringAnimation> = emptyList()
    private var lastAlign = ""
    private var lastBx = 0
    private var lastBy = 0
    private var lastSizeDp = 0
    private var lastProxyConnected = false
    private var lastProxySub = ""
    private var lastProxySubColor: Int = Color.WHITE
    private var winParams: WindowManager.LayoutParams? = null
    private var proxySubView: android.widget.TextView? = null
    // Glyph scale fractions per item (Proxy 0.58, rest 0.4) — must match show().
    private var hiding = false
    private var animGen = 0
    private val handler = Handler(Looper.getMainLooper())

    fun isShowing(): Boolean = rootView?.isAttachedToWindow == true

    fun show(
        bx: Int,
        by: Int,
        align: String,
        sizeDp: Int,
        proxyConnected: Boolean,
        proxySub: String = "",
        proxySubColor: Int = Color.WHITE
    ) {
        hideNow()
        hiding = false
        animGen++
        val gen = animGen
        lastAlign = align
        lastBx = bx
        lastBy = by
        lastSizeDp = sizeDp
        lastProxyConnected = proxyConnected
        lastProxySub = proxySub
        lastProxySubColor = proxySubColor
        proxySubView = null
        val density = context.resources.displayMetrics.density
        // Button diameter follows the slider; spread stays fixed like the HTML
        // (GAP 58dp / OFF 62dp / small-circle radius 68.75dp) so the slider
        // only grows the bubbles themselves, main + side together.
        val size = (sizeDp * density).toInt().coerceAtLeast(1)
        val gapPx = (58 * density).toInt()
        val offPx = (62 * density).toInt()
        val rPx = (68.75f * density).toInt()
        val pts: List<Pair<Int, Int>> = when (align) {
            CIRCLE_UP -> List(4) { 0 to -(offPx + it * gapPx) }
            CIRCLE_DOWN -> List(4) { 0 to (offPx + it * gapPx) }
            CIRCLE_RIGHT -> List(4) { (offPx + it * gapPx) to 0 }
            CIRCLE_LEFT -> List(4) { (-(offPx + it * gapPx)) to 0 }
            else -> listOf(0 to -rPx, rPx to 0, 0 to rPx, -rPx to 0)
        }
        val openStaggerMs = if (isLineAlign(align)) openStaggerLineMs else openStaggerCircleMs

        val icons = listOf(
            Triple(
                if (proxyConnected) R.drawable.ic_proton_lock_filled else R.drawable.ic_proton_lock_open_filled_2,
                if (proxyConnected) Color.parseColor("#1C9C7C") else Color.parseColor("#CC2D4F"),
                0.58f
            ),
            Triple(R.drawable.ic_tab_sms, Color.parseColor("#18181B"), 0.4f),
            Triple(R.drawable.ic_tab_sheet, Color.parseColor("#18181B"), 0.4f),
            Triple(R.drawable.ic_name_person, Color.parseColor("#18181B"), 0.4f)
        )
        val taps = listOf(onProxyTap, onSmsTap, onSheetTap, onNameTap)
        val metrics = context.resources.displayMetrics
        // HTML items are (size - 2); trigger is full size.
        val itemSize = (size - 2 * density).toInt().coerceAtLeast(1)
        val margin = itemSize / 2 + (8 * density).toInt()

        // Slot centers in screen coords (clamped on-screen like before).
        val centers = pts.map { (dx, dy) ->
            val cx = (bx + dx).coerceIn(margin, (metrics.widthPixels - margin).coerceAtLeast(margin))
            val cy = (by + dy).coerceIn(margin, (metrics.heightPixels - margin).coerceAtLeast(margin))
            cx to cy
        }

        // Window = menu bounding box: anchor + all slots + spin sweep + pad.
        // A rotating item corner reaches ~0.21*item beyond the slot edge, so
        // the pad must clear that or bubbles clip mid-spin.
        val pad = (itemSize / 2 + 12 * density).toInt()
        val subExtra = if (proxySub.isNotEmpty()) (20 * density).toInt() else 0
        val minX = minOf(bx, centers.minOf { it.first }) - pad
        val minY = minOf(by, centers.minOf { it.second }) - pad
        val maxX = maxOf(bx, centers.maxOf { it.first }) + pad
        val maxY = maxOf(by, centers.maxOf { it.second }) + pad + subExtra
        val winW = (maxX - minX).coerceAtLeast(1)
        val winH = (maxY - minY).coerceAtLeast(1)
        // Anchor in window-local coords: the layer orbits it on close.
        val abx = (bx - minX).toFloat()
        val aby = (by - minY).toFloat()

        // Pass-through scrim: the window itself never consumes — only the 4
        // item bubbles are clickable, everything else falls to the app below.
        val root = FrameLayout(context).apply {
            isClickable = false
            isFocusable = false
        }
        val box = FrameLayout(context).apply {
            isClickable = false
            isFocusable = false
            pivotX = abx
            pivotY = aby
        }

        val built = mutableListOf<FrameLayout>()
        val deltas = mutableListOf<Pair<Float, Float>>()
        var proxyCx = abx
        var proxyCy = aby
        centers.forEachIndexed { i, (cx, cy) ->
            val (icon, tint, frac) = icons[i]
            val btn = FrameLayout(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#F4F4F5"))
                }
                val iv = ImageView(context).apply {
                    setImageResource(icon)
                    setColorFilter(tint)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
                val glyph = (itemSize * frac).toInt().coerceAtLeast(1)
                addView(iv, FrameLayout.LayoutParams(glyph, glyph, Gravity.CENTER))
                isClickable = true
                isFocusable = true
                // Touch equivalent of the mockup's whileHover scale 1.1,
                // duration 0.1s, delay 0.
                if (i == 1) {
                    // SMS mirrors the HTML MenuItem tap contract: 550ms
                    // long-press opens the popup, 300ms double-tap window
                    // regenerates, single tap is delayed 300ms so a double
                    // never also fires a single.
                    var lastTap = 0L
                    var singlePending: Runnable? = null
                    var lpFired = false
                    val lpRunnable = Runnable {
                        if (!isShowing()) return@Runnable
                        lpFired = true
                        try {
                            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        } catch (_: Exception) {
                        }
                        onSmsLongPress()
                    }
                    setOnTouchListener { v, ev ->
                        when (ev.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                lpFired = false
                                v.animate().scaleX(1.1f).scaleY(1.1f).setDuration(100).start()
                                handler.postDelayed(lpRunnable, 550)
                                false
                            }
                            MotionEvent.ACTION_UP -> {
                                handler.removeCallbacks(lpRunnable)
                                v.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                                if (lpFired) {
                                    lpFired = false
                                    true
                                } else {
                                    val now = android.os.SystemClock.uptimeMillis()
                                    if (now - lastTap < 300) {
                                        singlePending?.let { handler.removeCallbacks(it) }
                                        singlePending = null
                                        lastTap = 0
                                        onSmsDoubleTap()
                                    } else {
                                        lastTap = now
                                        singlePending?.let { handler.removeCallbacks(it) }
                                        singlePending = Runnable { onSmsTap() }
                                        handler.postDelayed(singlePending!!, 300)
                                    }
                                    true
                                }
                            }
                            MotionEvent.ACTION_CANCEL -> {
                                handler.removeCallbacks(lpRunnable)
                                v.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                                lpFired = false
                                true
                            }
                            else -> false
                        }
                    }
                } else {
                    setOnTouchListener { v, ev ->
                        when (ev.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                v.animate().scaleX(1.1f).scaleY(1.1f).setDuration(100).start()
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                                v.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                        }
                        false
                    }
                    setOnClickListener { taps[i]() }
                }
                if (i == 0) {
                    setOnLongClickListener {
                        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        onProxyLongPress()
                        true
                    }
                }
            }
            // Local slot pos; spring path runs anchor -> slot.
            val lx = (cx - minX - itemSize / 2).toFloat()
            val ly = (cy - minY - itemSize / 2).toFloat()
            box.addView(
                btn,
                FrameLayout.LayoutParams(itemSize, itemSize, Gravity.TOP or Gravity.START).apply {
                    leftMargin = lx.toInt()
                    topMargin = ly.toInt()
                }
            )
            built.add(btn)
            deltas.add(Pair(abx - itemSize / 2 - lx, aby - itemSize / 2 - ly))
            if (i == 0) {
                proxyCx = lx + itemSize / 2
                proxyCy = ly + itemSize / 2
            }
        }
        btnViews = built
        slots = deltas

        // HTML lock-line: status text pinned under the Proxy bubble
        // (11sp bold), e.g. Connecting red / Protected green.
        if (proxySub.isNotEmpty()) {
            try {
                val sub = android.widget.TextView(context).apply {
                    text = proxySub
                    setTextColor(proxySubColor)
                    textSize = 11f
                    typeface = android.graphics.Typeface.create(
                        android.graphics.Typeface.DEFAULT,
                        android.graphics.Typeface.BOLD
                    )
                    gravity = Gravity.CENTER
                    setSingleLine(true)
                }
                box.addView(
                    sub,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP or Gravity.START
                    ).apply {
                        leftMargin = proxyCx.toInt()
                        topMargin = (proxyCy + itemSize / 2 + (2 * density).toInt()).toInt()
                    }
                )
                sub.post { sub.translationX = -sub.width / 2f }
                proxySubView = sub
            } catch (_: Exception) {
            }
        }

        root.addView(box, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        container = box

        val params = WindowManager.LayoutParams(
            winW,
            winH,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = minX
        params.y = minY
        winParams = params
        try {
            windowManager.addView(root, params)
        } catch (_: Exception) {
            return
        }
        rootView = root

        // Entry: each item springs anchor -> slot, staggered. Like the HTML
        // there is no fade on the motion itself; the small alpha-in only
        // avoids a one-frame pop on the overlay window.
        val newSprings = mutableListOf<SpringAnimation>()
        built.forEachIndexed { i, btn ->
            val (dx, dy) = deltas[i]
            btn.translationX = dx
            btn.translationY = dy
            btn.scaleX = 1f
            btn.scaleY = 1f
            btn.alpha = 0f
            btn.animate().alpha(1f).setStartDelay(i * openStaggerMs).setDuration(120).start()
            val sx = springTo(btn, DynamicAnimation.TRANSLATION_X, 0f)
            val sy = springTo(btn, DynamicAnimation.TRANSLATION_Y, 0f)
            newSprings.add(sx)
            newSprings.add(sy)
            startSpring(sx, i * openStaggerMs, gen, requireOpen = true)
            startSpring(sy, i * openStaggerMs, gen, requireOpen = true)
        }
        springs = newSprings
    }

    /**
     * Live-resize an open menu when the size slider moves: the trigger alone
     * used to grow while the 4 item bubbles stayed small until collapse +
     * re-expand. Recomputes the same layout math as [show] and applies it in
     * place — no teardown, no entry animation, translations stay at 0. Also
     * refreshes [slots] so the close fly-home targets match the new sizes.
     */
    fun updateSize(newSizeDp: Int) {
        if (newSizeDp == lastSizeDp) return
        applyLayout(newSizeDp, lastAlign)
    }

    /**
     * Live-move an open menu when the alignment setting changes: same
     * in-place relayout as [updateSize] but for the new slot arrangement
     * (circle vs up/down/left/right). No collapse + re-expand needed.
     */
    fun updateAlign(newAlign: String) {
        if (newAlign == lastAlign) return
        applyLayout(lastSizeDp, newAlign)
    }

    private fun applyLayout(newSizeDp: Int, newAlign: String) {
        val root = rootView ?: return
        val box = container ?: return
        if (!root.isAttachedToWindow) return
        if (btnViews.size != 4) return
        try {
            val density = context.resources.displayMetrics.density
            val metrics = context.resources.displayMetrics
            val size = (newSizeDp * density).toInt().coerceAtLeast(1)
            val itemSize = (size - 2 * density).toInt().coerceAtLeast(1)
            val gapPx = (58 * density).toInt()
            val offPx = (62 * density).toInt()
            val rPx = (68.75f * density).toInt()
            val pts: List<Pair<Int, Int>> = when (newAlign) {
                CIRCLE_UP -> List(4) { 0 to -(offPx + it * gapPx) }
                CIRCLE_DOWN -> List(4) { 0 to (offPx + it * gapPx) }
                CIRCLE_RIGHT -> List(4) { (offPx + it * gapPx) to 0 }
                CIRCLE_LEFT -> List(4) { (-(offPx + it * gapPx)) to 0 }
                else -> listOf(0 to -rPx, rPx to 0, 0 to rPx, -rPx to 0)
            }
            val margin = itemSize / 2 + (8 * density).toInt()
            val bx = lastBx
            val by = lastBy
            val centers = pts.map { (dx, dy) ->
                val cx = (bx + dx).coerceIn(margin, (metrics.widthPixels - margin).coerceAtLeast(margin))
                val cy = (by + dy).coerceIn(margin, (metrics.heightPixels - margin).coerceAtLeast(margin))
                cx to cy
            }
            val pad = (itemSize / 2 + 12 * density).toInt()
            val subExtra = if (lastProxySub.isNotEmpty()) (20 * density).toInt() else 0
            val minX = minOf(bx, centers.minOf { it.first }) - pad
            val minY = minOf(by, centers.minOf { it.second }) - pad
            val maxX = maxOf(bx, centers.maxOf { it.first }) + pad
            val maxY = maxOf(by, centers.maxOf { it.second }) + pad + subExtra
            val winW = (maxX - minX).coerceAtLeast(1)
            val winH = (maxY - minY).coerceAtLeast(1)
            val abx = (bx - minX).toFloat()
            val aby = (by - minY).toFloat()
            box.pivotX = abx
            box.pivotY = aby

            val fracs = listOf(0.58f, 0.4f, 0.4f, 0.4f)
            val newDeltas = mutableListOf<Pair<Float, Float>>()
            var proxyCx = abx
            var proxyCy = aby
            btnViews.forEachIndexed { i, btn ->
                try {
                    btn.animate().cancel()
                } catch (_: Exception) {
                }
                val glyph = (itemSize * fracs[i]).toInt().coerceAtLeast(1)
                (btn.getChildAt(0)?.layoutParams as? FrameLayout.LayoutParams)?.let { glp ->
                    glp.width = glyph
                    glp.height = glyph
                    btn.getChildAt(0)?.layoutParams = glp
                }
                val lx = (centers[i].first - minX - itemSize / 2).toFloat()
                val ly = (centers[i].second - minY - itemSize / 2).toFloat()
                (btn.layoutParams as? FrameLayout.LayoutParams)?.let { blp ->
                    blp.width = itemSize
                    blp.height = itemSize
                    blp.leftMargin = lx.toInt()
                    blp.topMargin = ly.toInt()
                    btn.layoutParams = blp
                }
                btn.translationX = 0f
                btn.translationY = 0f
                btn.scaleX = 1f
                btn.scaleY = 1f
                btn.alpha = 1f
                newDeltas.add(Pair(abx - itemSize / 2 - lx, aby - itemSize / 2 - ly))
                if (i == 0) {
                    proxyCx = lx + itemSize / 2
                    proxyCy = ly + itemSize / 2
                }
            }
            slots = newDeltas
            proxySubView?.let { sub ->
                (sub.layoutParams as? FrameLayout.LayoutParams)?.let { slp ->
                    slp.leftMargin = proxyCx.toInt()
                    slp.topMargin = (proxyCy + itemSize / 2 + (2 * density).toInt()).toInt()
                    sub.layoutParams = slp
                }
                sub.post { sub.translationX = -sub.width / 2f }
            }
            try {
                winParams?.let { p ->
                    p.x = minX
                    p.y = minY
                    p.width = winW
                    p.height = winH
                    windowManager.updateViewLayout(root, p)
                }
            } catch (_: Exception) {
            }
            lastSizeDp = newSizeDp
            lastAlign = newAlign
        } catch (_: Exception) {
        }
    }

    /**
     * Animated close, per alignment like the HTML:
     * circle spins the items-layer -360deg while items spring home forward;
     * lines cascade home in reverse (last-in-first-out) with no spin.
     * Trigger shake/pulse runs via [onCloseAnim].
     */
    fun hide() {
        val root = rootView ?: return
        if (hiding) return
        hiding = true
        animGen++
        val gen = animGen
        cancelSprings()
        try {
            onCloseAnim()
        } catch (_: Exception) {
        }
        val box = container
        if (box == null || !root.isAttachedToWindow) {
            finishRemove()
            return
        }
        val n = btnViews.size
        val line = isLineAlign(lastAlign)
        // HTML close: items spring slot -> 0 (inside the trigger), staggered;
        // the layer spins -360 only for circle. No early fade: the bubbles
        // stay visible while they travel and only shrink out as they land
        // inside the trigger, exactly like the mockup where they slide under
        // the trigger.
        val totalMs = closeStaggerMs * (n + 2)
        val newSprings = mutableListOf<SpringAnimation>()
        btnViews.forEachIndexed { i, btn ->
            val (dx, dy) = slots.getOrElse(i) { Pair(0f, 0f) }
            // HTML: lines close (n-1-i)*70ms, circle closes i*70ms.
            val d = if (line) (n - 1 - i) * closeStaggerMs else i * closeStaggerMs
            try {
                btn.animate().cancel()
            } catch (_: Exception) {
            }
            btn.alpha = 1f
            val sx = springTo(btn, DynamicAnimation.TRANSLATION_X, dx)
            val sy = springTo(btn, DynamicAnimation.TRANSLATION_Y, dy)
            newSprings.add(sx)
            newSprings.add(sy)
            startSpring(sx, d, gen, requireOpen = false)
            startSpring(sy, d, gen, requireOpen = false)
            // Shrink + fade only as the bubble lands inside the trigger,
            // so the eye sees travel-then-swallow instead of vanish-in-place.
            btn.animate()
                .scaleX(0.2f).scaleY(0.2f).alpha(0f)
                .setStartDelay(d + 180L).setDuration(150).start()
        }
        springs = newSprings
        try {
            box.animate().cancel()
        } catch (_: Exception) {
        }
        if (line) {
            // Lines: no spin, no layer fade — just let the items fly home.
            handler.postDelayed({ finishRemove() }, totalMs + 200L)
        } else {
            // HTML closeAnimationCallback: the layer spins -360 with a 1px
            // blur while the items spring inside (blur on API 31+).
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    box.setRenderEffect(
                        android.graphics.RenderEffect.createBlurEffect(
                            1f, 1f, android.graphics.Shader.TileMode.CLAMP
                        )
                    )
                }
            } catch (_: Exception) {
            }
            box.animate()
                .rotation(-360f)
                .setDuration(totalMs)
                .withEndAction { finishRemove() }
                .start()
            // Failsafe: never trap the window if an animator is cancelled.
            handler.postDelayed({ finishRemove() }, totalMs + 400L)
        }
    }

    /** Immediate removal for destroy / config change / re-show. */
    fun hideNow() {
        animGen++
        handler.removeCallbacksAndMessages(null)
        hiding = false
        cancelSprings()
        finishRemove()
    }

    private fun springTo(view: FrameLayout, property: DynamicAnimation.ViewProperty, target: Float): SpringAnimation {
        return SpringAnimation(view, property, target).apply {
            spring = SpringForce(target).apply {
                stiffness = springStiffness
                dampingRatio = springDamping
            }
        }
    }

    private fun startSpring(spring: SpringAnimation, delayMs: Long, gen: Int, requireOpen: Boolean) {
        // DynamicAnimation has no start delay here: post the start instead.
        // Generation-guarded so a stale open start can never fire mid-close.
        // hideNow() bumps the generation and clears these alongside
        // everything else.
        handler.postDelayed({
            if (gen != animGen) return@postDelayed
            if (requireOpen && hiding) return@postDelayed
            if (!isShowing()) return@postDelayed
            try {
                spring.start()
            } catch (_: Exception) {
            }
        }, delayMs)
    }

    private fun cancelSprings() {
        springs.forEach {
            try {
                it.cancel()
            } catch (_: Exception) {
            }
        }
        springs = emptyList()
    }

    private fun finishRemove() {
        val root = rootView
        rootView = null
        winParams = null
        proxySubView = null
        try {
            container?.setRenderEffect(null)
        } catch (_: Exception) {
        }
        container = null
        btnViews = emptyList()
        slots = emptyList()
        hiding = false
        if (root != null) {
            try {
                root.animate().cancel()
                if (root.isAttachedToWindow) windowManager.removeView(root)
            } catch (_: Exception) {
            }
        }
        try {
            onDismissed()
        } catch (_: Exception) {
        }
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
}
