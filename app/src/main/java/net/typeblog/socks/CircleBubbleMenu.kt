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
 * around the floating bubble's anchor point. Layout math mirrors the
 * settings live preview ([ui.screens.CircleAlignPreview]): same order,
 * same one-sided line offsets, same tight small-circle radius.
 *
 * Motion is an exact port of the HTML mockup (ObsidianUI CircleMenu):
 * items ride springs (stiffness 300, damping ratio 0.866) between the
 * anchor and their slot, staggered 20ms opening / 70ms closing; the items
 * layer spins a full turn on close; buttons squeeze on press (touch
 * equivalent of the mockup's hover grow).
 *
 * Full-screen scrim: tap outside dismisses. Proxy supports long-press
 * (opens the country menu); the rest are taps.
 */
class CircleBubbleMenu(
    private val context: Context,
    private val onProxyTap: () -> Unit,
    private val onProxyLongPress: () -> Unit,
    private val onSmsTap: () -> Unit,
    private val onSheetTap: () -> Unit,
    private val onNameTap: () -> Unit,
    private val onDismissed: () -> Unit = {},
    private val onCloseAnim: () -> Unit = {}
) {
    // HTML mockup motion constants.
    private val openStaggerMs = 20L
    private val closeStaggerMs = 70L
    private val springStiffness = 300f
    // framer damping 30 at stiffness 300 -> ratio 30 / (2 * sqrt(300)).
    private val springDamping = 0.866f

    private var windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var rootView: FrameLayout? = null
    private var container: FrameLayout? = null
    private var slots: List<Pair<Float, Float>> = emptyList()
    private var btnViews: List<FrameLayout> = emptyList()
    private var springs: List<SpringAnimation> = emptyList()
    private var hiding = false
    private val handler = Handler(Looper.getMainLooper())

    fun isShowing(): Boolean = rootView?.isAttachedToWindow == true

    fun show(bx: Int, by: Int, align: String, sizeDp: Int, proxyConnected: Boolean) {
        hideNow()
        hiding = false
        val density = context.resources.displayMetrics.density
        val size = (sizeDp * density).toInt().coerceAtLeast(1)
        val gap = size + (12 * density).toInt()
        val off = size + (14 * density).toInt()
        val r = size + (28 * density).toInt()
        val pts: List<Pair<Int, Int>> = when (align) {
            CIRCLE_UP -> List(4) { 0 to -(off + it * gap) }
            CIRCLE_DOWN -> List(4) { 0 to (off + it * gap) }
            CIRCLE_RIGHT -> List(4) { (off + it * gap) to 0 }
            CIRCLE_LEFT -> List(4) { (-(off + it * gap)) to 0 }
            else -> listOf(0 to -r, r to 0, 0 to r, -r to 0)
        }

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
        val metrics = context.resources.displayMetrics
        val margin = size / 2 + (8 * density).toInt()

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
                val glyph = (size * frac).toInt().coerceAtLeast(1)
                addView(iv, FrameLayout.LayoutParams(glyph, glyph, Gravity.CENTER))
                isClickable = true
                isFocusable = true
                // Touch equivalent of the mockup's hover grow: press squeeze.
                setOnTouchListener { v, ev ->
                    when (ev.actionMasked) {
                        MotionEvent.ACTION_DOWN ->
                            v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(100).start()
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                            v.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                    }
                    false
                }
                setOnClickListener { taps[i]() }
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
                FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.START).apply {
                    leftMargin = cx - size / 2
                    topMargin = cy - size / 2
                }
            )
            built.add(btn)
            // Spring path: slot position relative to the anchor.
            deltas.add(Pair((cx - bx).toFloat(), (cy - by).toFloat()))
        }
        btnViews = built
        slots = deltas

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

        // Entry: each item springs anchor -> slot, staggered.
        val newSprings = mutableListOf<SpringAnimation>()
        built.forEachIndexed { i, btn ->
            val (dx, dy) = deltas[i]
            btn.translationX = dx
            btn.translationY = dy
            btn.alpha = 0f
            btn.animate().alpha(1f).setStartDelay(i * openStaggerMs).setDuration(150).start()
            newSprings.add(springTo(btn, DynamicAnimation.TRANSLATION_X, 0f, i * openStaggerMs))
            newSprings.add(springTo(btn, DynamicAnimation.TRANSLATION_Y, 0f, i * openStaggerMs))
        }
        springs = newSprings
        newSprings.forEach { it.start() }
    }

    /**
     * Animated close: items spring slot -> anchor staggered 70ms while the
     * layer spins a full turn and fades — the mockup's close, plus the
     * trigger shake/pulse via [onCloseAnim].
     */
    fun hide() {
        val root = rootView ?: return
        if (hiding) return
        hiding = true
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
        val newSprings = mutableListOf<SpringAnimation>()
        btnViews.forEachIndexed { i, btn ->
            val (dx, dy) = slots.getOrElse(i) { Pair(0f, 0f) }
            newSprings.add(springTo(btn, DynamicAnimation.TRANSLATION_X, dx, i * closeStaggerMs))
            newSprings.add(springTo(btn, DynamicAnimation.TRANSLATION_Y, dy, i * closeStaggerMs))
            btn.animate().alpha(0f).setStartDelay(i * closeStaggerMs).setDuration(180).start()
        }
        springs = newSprings
        newSprings.forEach { it.start() }
        box.animate()
            .rotation(-360f).alpha(0f)
            .setDuration(closeStaggerMs * (n + 2))
            .withEndAction { finishRemove() }
            .start()
        // Failsafe: never trap the scrim if an animator is cancelled.
        handler.postDelayed({ finishRemove() }, closeStaggerMs * (n + 2) + 400L)
    }

    /** Immediate removal for destroy / config change / re-show. */
    fun hideNow() {
        handler.removeCallbacksAndMessages(null)
        hiding = false
        cancelSprings()
        finishRemove()
    }

    private fun springTo(view: FrameLayout, property: DynamicAnimation.ViewProperty, target: Float, delayMs: Long): SpringAnimation {
        return SpringAnimation(view, property, target).apply {
            spring = SpringForce(target).apply {
                stiffness = springStiffness
                dampingRatio = springDamping
            }
            setStartDelay(delayMs)
        }
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
