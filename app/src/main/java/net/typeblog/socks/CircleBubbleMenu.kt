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
 * 70ms forward for circle, reverse cascade for lines; buttons grow 1.1x on
 * press (touch equivalent of the mockup's hover grow). No hover labels — the
 * mockup's cm-item-label was removed.
 *
 * Touch transparency (per official WindowManager.LayoutParams docs):
 * FLAG_NOT_TOUCH_MODAL sends pointer events OUTSIDE a window to the windows
 * behind it — but our old full-screen scrim had no "outside", so it swallowed
 * every tap on the device. Each item therefore gets its own small window
 * covering only its anchor->slot travel segment (NOT_FOCUSABLE +
 * NOT_TOUCH_MODAL). Taps anywhere else fall through to the app below and
 * never collapse the menu — it only closes via the trigger bubble or one of
 * its 4 actions. The close -360 spin is reproduced per item around the same
 * anchor pivot, so the shared-layer rotation looks identical.
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
    private var itemWindows: List<FrameLayout> = emptyList()
    private var slots: List<Pair<Float, Float>> = emptyList()
    private var btnViews: List<FrameLayout> = emptyList()
    private var springs: List<SpringAnimation> = emptyList()
    private var lastAlign = ""
    private var hiding = false
    private var animGen = 0
    private val handler = Handler(Looper.getMainLooper())

    fun isShowing(): Boolean = itemWindows.any { it.isAttachedToWindow }

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
        val pad = (4 * density).toInt()

        val newWindows = mutableListOf<FrameLayout>()
        val built = mutableListOf<FrameLayout>()
        val deltas = mutableListOf<Pair<Float, Float>>()
        pts.forEachIndexed { i, (dx, dy) ->
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
            val cx = (bx + dx).coerceIn(margin, (metrics.widthPixels - margin).coerceAtLeast(margin))
            val cy = (by + dy).coerceIn(margin, (metrics.heightPixels - margin).coerceAtLeast(margin))

            // One small window per item covering its anchor->slot segment:
            // everything outside these windows falls through to the app below.
            val subExtra = if (i == 0 && proxySub.isNotEmpty()) (20 * density).toInt() else 0
            val minX = minOf(bx, cx) - itemSize / 2 - pad
            val minY = minOf(by, cy) - itemSize / 2 - pad
            val maxX = maxOf(bx, cx) + itemSize / 2 + pad
            val maxY = maxOf(by, cy) + itemSize / 2 + pad + subExtra
            val winW = (maxX - minX).coerceAtLeast(1)
            val winH = (maxY - minY).coerceAtLeast(1)
            // Item rests at its slot (local coords); it starts on the anchor
            // and springs out — the stored delta is the spring path.
            val endLX = cx - itemSize / 2 - minX
            val endLY = cy - itemSize / 2 - minY
            val container = FrameLayout(context).apply {
                isClickable = false
                isFocusable = false
            }
            container.addView(
                btn,
                FrameLayout.LayoutParams(itemSize, itemSize, Gravity.TOP or Gravity.START).apply {
                    leftMargin = endLX
                    topMargin = endLY
                }
            )
            // HTML lock-line: status text pinned under the Proxy bubble
            // (11sp bold), e.g. Connecting red / Protected green.
            if (subExtra > 0) {
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
                    container.addView(
                        sub,
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            Gravity.TOP or Gravity.START
                        ).apply {
                            leftMargin = endLX + itemSize / 2
                            topMargin = endLY + itemSize + (2 * density).toInt()
                        }
                    )
                    sub.post { sub.translationX = -sub.width / 2f }
                } catch (_: Exception) {
                }
            }
            btn.translationX = (bx - itemSize / 2 - minX - endLX).toFloat()
            btn.translationY = (by - itemSize / 2 - minY - endLY).toFloat()

            val wparams = WindowManager.LayoutParams(
                winW,
                winH,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            )
            wparams.gravity = Gravity.TOP or Gravity.START
            wparams.x = minX
            wparams.y = minY
            try {
                windowManager.addView(container, wparams)
            } catch (_: Exception) {
                return@forEachIndexed
            }
            newWindows.add(container)
            built.add(btn)
            deltas.add(Pair(btn.translationX, btn.translationY))
        }
        itemWindows = newWindows
        btnViews = built
        slots = deltas

        // Entry: each item springs anchor -> slot, staggered. Like the HTML
        // there is no fade on the motion itself; the small alpha-in only
        // avoids a one-frame pop on the overlay window.
        val newSprings = mutableListOf<SpringAnimation>()
        built.forEachIndexed { i, btn ->
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
     * Animated close, per alignment like the HTML: circle items spring home
     * forward while spinning -360 around the anchor (the shared-layer spin,
     * reproduced per item); lines cascade home in reverse (last-in-first-out)
     * with no spin. Trigger shake/pulse runs via [onCloseAnim].
     */
    fun hide() {
        if (!isShowing() || hiding) return
        hiding = true
        animGen++
        val gen = animGen
        cancelSprings()
        try {
            onCloseAnim()
        } catch (_: Exception) {
        }
        val n = btnViews.size
        if (n == 0) {
            finishRemove()
            return
        }
        val line = isLineAlign(lastAlign)
        // HTML: items spring slot -> anchor (inside the trigger), staggered;
        // the bubbles stay visible while they travel and only shrink out as
        // they land inside, exactly like the mockup where they slide under
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
        if (!line) {
            // HTML closeAnimationCallback spins the shared items-layer -360
            // while the items spring home. Separate windows can't share one
            // layer, so each item rotates around the same anchor pivot with
            // the same linear duration — the composite is that rigid spin,
            // plus the HTML's 1px blur while it turns (API 31+).
            val linear = android.view.animation.LinearInterpolator()
            btnViews.forEachIndexed { i, btn ->
                val (dx, dy) = slots.getOrElse(i) { Pair(0f, 0f) }
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        btn.setRenderEffect(
                            android.graphics.RenderEffect.createBlurEffect(
                                1f, 1f, android.graphics.Shader.TileMode.CLAMP
                            )
                        )
                    }
                } catch (_: Exception) {
                }
                try {
                    btn.pivotX = dx
                    btn.pivotY = dy
                    android.animation.ObjectAnimator.ofFloat(btn, "rotation", 0f, -360f).apply {
                        duration = totalMs
                        interpolator = linear
                        start()
                    }
                } catch (_: Exception) {
                }
            }
        }
        // Failsafe: never trap windows if an animator is cancelled.
        handler.postDelayed({ finishRemove() }, totalMs + 400L)
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
        val windows = itemWindows
        itemWindows = emptyList()
        btnViews = emptyList()
        slots = emptyList()
        hiding = false
        windows.forEach { w ->
            try {
                w.animate().cancel()
                if (w.isAttachedToWindow) windowManager.removeView(w)
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
