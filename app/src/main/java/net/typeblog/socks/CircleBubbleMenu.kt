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
import android.view.View
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
 * 70ms forward for circle, reverse cascade for lines; items-layer spins a full
 * turn ONLY for circle (lines return straight, like the HTML); buttons grow
 * 1.1x on press (touch equivalent of the mockup's hover grow).
 *
 * Full-screen scrim: tap outside dismisses. Proxy supports long-press
 * (opens the country menu); the rest are taps.
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

        val root = FrameLayout(context).apply {
            setOnClickListener { hide() }
        }
        // Pivot at the anchor so the layer orbits it on close.
        val box = FrameLayout(context).apply {
            pivotX = bx.toFloat()
            pivotY = by.toFloat()
        }

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
        val labels = listOf("Proxy", "SMS", "Sheet", "Name")
        val metrics = context.resources.displayMetrics
        // HTML items are (size - 2); trigger is full size.
        val itemSize = (size - 2 * density).toInt().coerceAtLeast(1)
        val margin = itemSize / 2 + (8 * density).toInt()

        // Touch equivalent of the mockup's hover label (cm-item-label):
        // one reusable tag shown under the pressed bubble, hidden on release.
        val pressLabel = android.widget.TextView(context).apply {
            textSize = 12f
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setShadowLayer(4f, 0f, 2f, Color.argb(160, 0, 0, 0))
            visibility = View.GONE
        }
        fun showPressLabel(text: String, cx: Int, top: Int) {
            try {
                pressLabel.text = text
                (pressLabel.layoutParams as? FrameLayout.LayoutParams)?.let {
                    it.leftMargin = cx
                    it.topMargin = top
                }
                if (pressLabel.parent == null) {
                    box.addView(
                        pressLabel,
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            Gravity.TOP or Gravity.START
                        ).apply {
                            leftMargin = cx
                            topMargin = top
                        }
                    )
                }
                pressLabel.visibility = View.VISIBLE
                pressLabel.post { pressLabel.translationX = -pressLabel.width / 2f }
            } catch (_: Exception) {
            }
        }
        fun hidePressLabel() {
            try {
                pressLabel.visibility = View.GONE
            } catch (_: Exception) {
            }
        }

        val built = mutableListOf<FrameLayout>()
        val deltas = mutableListOf<Pair<Float, Float>>()
        var proxyCx = bx
        var proxyCy = by
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
                // duration 0.1s, delay 0, plus the hover label tag.
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
                        hidePressLabel()
                        onSmsLongPress()
                    }
                    setOnTouchListener { v, ev ->
                        when (ev.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                lpFired = false
                                v.animate().scaleX(1.1f).scaleY(1.1f).setDuration(100).start()
                                try {
                                    val loc = IntArray(2)
                                    v.getLocationOnScreen(loc)
                                    showPressLabel(
                                        labels[1],
                                        loc[0] + v.width / 2,
                                        loc[1] + v.height + (4 * density).toInt()
                                    )
                                } catch (_: Exception) {
                                }
                                handler.postDelayed(lpRunnable, 550)
                                false
                            }
                            MotionEvent.ACTION_UP -> {
                                handler.removeCallbacks(lpRunnable)
                                v.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                                hidePressLabel()
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
                                hidePressLabel()
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
                                try {
                                    val loc = IntArray(2)
                                    v.getLocationOnScreen(loc)
                                    val cx = loc[0] + v.width / 2
                                    // Proxy already carries its status line
                                    // below it — float its tag above instead.
                                    val top = if (i == 0) {
                                        loc[1] - (20 * density).toInt()
                                    } else {
                                        loc[1] + v.height + (4 * density).toInt()
                                    }
                                    showPressLabel(labels[i], cx, top)
                                } catch (_: Exception) {
                                }
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                                v.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                        }
                        if (ev.actionMasked == MotionEvent.ACTION_UP ||
                            ev.actionMasked == MotionEvent.ACTION_CANCEL
                        ) {
                            hidePressLabel()
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
            box.addView(
                btn,
                FrameLayout.LayoutParams(itemSize, itemSize, Gravity.TOP or Gravity.START).apply {
                    leftMargin = cx - itemSize / 2
                    topMargin = cy - itemSize / 2
                }
            )
            built.add(btn)
            // Spring path: anchor relative to the slot (bx - cx), so the
            // button starts on the anchor and springs out to its slot at 0.
            deltas.add(Pair((bx - cx).toFloat(), (by - cy).toFloat()))
            if (i == 0) {
                proxyCx = cx
                proxyCy = cy
            }
        }
        btnViews = built
        slots = deltas

        // HTML lock-line: status text pinned under the Proxy bubble
        // (top 100% + 2px, 11sp bold), e.g. Connecting red / Protected green.
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
                        leftMargin = proxyCx
                        topMargin = proxyCy + itemSize / 2 + (2 * density).toInt()
                    }
                )
                sub.post { sub.translationX = -sub.width / 2f }
            } catch (_: Exception) {
            }
        }

        root.addView(box, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        container = box

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
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
        // inside, exactly like the mockup where they slide under the trigger.
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
            // Failsafe: never trap the scrim if an animator is cancelled.
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
        // Generation-guarded so a stale open start can never fire mid-close
        // (the old `!hiding` gate blocked EVERY close spring — that was why
        // bubbles faded in place instead of flying inside). hideNow() bumps
        // the generation and clears these alongside everything else.
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
