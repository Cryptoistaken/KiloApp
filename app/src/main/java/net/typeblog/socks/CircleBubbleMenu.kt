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
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
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
 * Entry/exit follow the alignment like the HTML mockup: lines cascade
 * out/in (last-in-first-out on close), the small circle pops in and
 * spins a full turn on close. Full-screen scrim: tap outside dismisses.
 * Proxy supports long-press (opens the country menu); the rest are taps.
 */
class CircleBubbleMenu(
    private val context: Context,
    private val onProxyTap: () -> Unit,
    private val onProxyLongPress: () -> Unit,
    private val onSmsTap: () -> Unit,
    private val onSheetTap: () -> Unit,
    private val onNameTap: () -> Unit,
    private val onDismissed: () -> Unit = {}
) {
    private var windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var rootView: FrameLayout? = null
    private var container: FrameLayout? = null
    private var btnViews: List<FrameLayout> = emptyList()
    private var lastAlign = ""
    private var hiding = false
    private val handler = Handler(Looper.getMainLooper())

    fun isShowing(): Boolean = rootView?.isAttachedToWindow == true

    private fun isLine(): Boolean = lastAlign == CIRCLE_UP ||
        lastAlign == CIRCLE_DOWN ||
        lastAlign == CIRCLE_RIGHT ||
        lastAlign == CIRCLE_LEFT

    fun show(bx: Int, by: Int, align: String, sizeDp: Int, proxyConnected: Boolean) {
        hideNow()
        lastAlign = align
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
        // Pivot at the anchor so the small circle orbits it on close.
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
        }
        btnViews = built

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

        // Entry: lines cascade, small circle pops with a tight stagger.
        val step = if (isLine()) 70L else 30L
        built.forEachIndexed { i, btn ->
            btn.scaleX = 0f
            btn.scaleY = 0f
            btn.alpha = 0f
            btn.animate()
                .scaleX(1f).scaleY(1f).alpha(1f)
                .setStartDelay(i * step)
                .setDuration(220)
                .setInterpolator(OvershootInterpolator(2.5f))
                .start()
        }
    }

    /** Animated close: spin for the small circle, reverse cascade for lines. */
    fun hide() {
        val root = rootView ?: return
        if (hiding) return
        hiding = true
        val box = container
        if (box == null || !root.isAttachedToWindow) {
            finishRemove()
            return
        }
        if (isLine()) {
            val n = btnViews.size
            btnViews.forEachIndexed { i, btn ->
                btn.animate()
                    .scaleX(0f).scaleY(0f).alpha(0f)
                    .setStartDelay((n - 1 - i) * 60L)
                    .setDuration(180)
                    .start()
            }
            handler.postDelayed({ finishRemove() }, (n * 60L + 200L))
        } else {
            box.animate()
                .rotation(-360f).alpha(0f)
                .setDuration(300)
                .withEndAction { finishRemove() }
                .start()
        }
    }

    /** Immediate removal for destroy / config change / re-show. */
    fun hideNow() {
        handler.removeCallbacksAndMessages(null)
        hiding = false
        finishRemove()
    }

    private fun finishRemove() {
        val root = rootView
        rootView = null
        container = null
        btnViews = emptyList()
        hiding = false
        if (root != null) {
            try {
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
