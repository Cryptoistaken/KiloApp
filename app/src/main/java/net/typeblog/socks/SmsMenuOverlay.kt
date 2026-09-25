package net.typeblog.socks

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import net.typeblog.socks.util.SmsNum
import net.typeblog.socks.util.SmsWatcher
import net.typeblog.socks.util.smsIsRangePat
import net.typeblog.socks.util.ThemeMode

/**
 * Long-press popup for the circle-menu SMS bubble: a fixed 230x280dp panel on
 * the proxy popup's shell (same background, same 4-side bubble-edge
 * positioning and grow-in, same scrim-tap-to-close). Content ports the
 * approved HTML mockup: a range bar on top (empty = X closes, valid range =
 * X swaps to the Gen pill, Enter submits and dismisses the keyboard, Gen
 * locks with a spinner ~1.2s and repeat taps are ignored), then appending
 * number rows — flag + number, live mm:ss + spinner while waiting, green code
 * once arrived, tap copies the number. Empty shows the speech-bubble doodle.
 */
class SmsMenuOverlay(
    private val context: Context,
    private val onNumberCopy: (String) -> Unit,
    private val onGenerate: (String) -> Unit,
    private val onDismissed: () -> Unit = {}
) {
    private var windowManager: WindowManager = createWindowManager()

    private fun createWindowManager(): WindowManager {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
        val display = dm.getDisplay(android.view.Display.DEFAULT_DISPLAY)
            ?: return context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val displayCtx = context.createDisplayContext(display)
        return displayCtx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    fun onConfigurationChanged() {
        windowManager = createWindowManager()
        if (isShowing()) hide()
    }

    private var activeInflateContext: Context = context
    private val handler = Handler(Looper.getMainLooper())
    private val tickHandler = Handler(Looper.getMainLooper())
    private var rootView: FrameLayout? = null
    private var panelView: LinearLayout? = null
    private var scrollView: ScrollView? = null
    private var listView: LinearLayout? = null
    private var emptyView: ImageView? = null
    private var searchInput: EditText? = null
    private var genWrap: FrameLayout? = null
    private var genLabel: TextView? = null
    private var genSpin: ProgressBar? = null
    private var closeBtn: ImageButton? = null
    private var generating = false
    private val rowViews = mutableMapOf<Long, RowViews>()

    private data class RowViews(
        val root: View,
        val flag: TextView,
        val name: TextView,
        val time: TextView,
        val spin: ProgressBar,
        val code: TextView
    )

    fun isShowing(): Boolean = rootView?.isAttachedToWindow == true

    // While the range field is focused the IME covers the lower part of the
    // overlay — push the panel up so its bottom stays above the keyboard.
    private val globalLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        val root = rootView ?: return@OnGlobalLayoutListener
        if (!root.isAttachedToWindow) return@OnGlobalLayoutListener
        val search = searchInput ?: return@OnGlobalLayoutListener
        if (!search.hasFocus()) return@OnGlobalLayoutListener
        repositionPanelAboveIme(root)
    }

    fun show(bubbleCenterX: Int, bubbleCenterY: Int, bubbleSizePx: Int) {
        if (isShowing()) return
        generating = false
        activeInflateContext = ThemeMode.themedContext(context)
        val root = try {
            rootView ?: LayoutInflater.from(activeInflateContext)
                .inflate(R.layout.bubble_sms_menu, null) as FrameLayout
        } catch (_: Exception) {
            rootView = null
            return
        }
        rootView = root

        val panel = root.findViewById<LinearLayout>(R.id.sms_panel)
        val scroll = root.findViewById<ScrollView>(R.id.sms_scroll)
        val list = root.findViewById<LinearLayout>(R.id.sms_list)
        val empty = root.findViewById<ImageView>(R.id.sms_empty)
        val input = root.findViewById<EditText>(R.id.sms_range)
        val genW = root.findViewById<FrameLayout>(R.id.sms_gen_wrap)
        val genT = root.findViewById<TextView>(R.id.sms_gen)
        val genS = root.findViewById<ProgressBar>(R.id.sms_gen_spin)
        val close = root.findViewById<ImageButton>(R.id.sms_close)
        if (panel == null || list == null || scroll == null || empty == null ||
            input == null || genW == null || genT == null || genS == null || close == null
        ) {
            rootView = null
            return
        }
        listView = list
        scrollView = scroll
        emptyView = empty
        panelView = panel
        searchInput = input
        genWrap = genW
        genLabel = genT
        genSpin = genS
        closeBtn = close

        input.setText("")
        syncGenButton("")
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                syncGenButton(s?.toString() ?: "")
            }
        })
        input.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                submitRange()
                true
            } else {
                false
            }
        }
        // Tapping the field focuses it and raises the keyboard; it stays
        // focused until Enter submits, a tap elsewhere in the panel unfocuses
        // it, or the popup closes.
        input.setOnClickListener {
            input.requestFocus()
            showKeyboard(input)
        }
        input.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) showKeyboard(input)
        }
        genT.contentDescription = "Generate number"
        genT.setOnClickListener { submitRange() }
        close.contentDescription = "Close"
        close.setOnClickListener {
            input.setText("")
            hide()
        }
        // Taps on panel background (not the field, Gen, X, or a row) drop
        // field focus and hide the keyboard; the popup stays open.
        panel.setOnClickListener { clearFieldFocus() }

        val bounds = contentBounds()
        val panelWidth = minOf(
            dp(230f),
            (bounds.width() - bubbleSizePx - dp(16f)).coerceAtLeast(1)
        )
        val panelHeight = dp(280f)

        val panelLp = panel.layoutParams as? FrameLayout.LayoutParams
            ?: FrameLayout.LayoutParams(panelWidth, panelHeight)
        panelLp.width = panelWidth
        panelLp.height = panelHeight
        panel.layoutParams = panelLp

        val margin8 = dp(8f)
        val placement = BubblePopupPlacer.place(
            bounds = bounds,
            bubbleCenterX = bubbleCenterX,
            bubbleCenterY = bubbleCenterY,
            bubbleSizePx = bubbleSizePx,
            panelWidth = panelWidth,
            panelHeight = panelHeight,
            marginPx = margin8
        )
        val side = placement.side

        panelLp.leftMargin = placement.x
        panelLp.topMargin = placement.y

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            0,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        // Shrink the window above the keyboard so the panel can be pushed
        // clear of the IME while the range field is focused (same as the
        // proxy popup — a FLAG_NOT_FOCUSABLE window can never take input).
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE

        root.alpha = 0f
        try {
            windowManager.addView(root, params)
        } catch (_: Exception) {
            rootView = null
            listView = null
            return
        }

        root.viewTreeObserver.addOnGlobalLayoutListener(globalLayoutListener)

        root.setOnClickListener { hide() }
        panel.isClickable = true
        list.isClickable = true
        root.animate().alpha(1f).setDuration(100).start()

        panel.scaleX = 0.55f
        panel.scaleY = 0.55f
        panel.alpha = 0f
        panel.pivotX = when (side) {
            "left" -> panelWidth.toFloat()
            "top", "bottom" -> panelWidth / 2f
            else -> 0f
        }
        panel.pivotY = when (side) {
            "top" -> panelHeight.toFloat()
            "bottom" -> 0f
            else -> panelHeight / 2f
        }
        panel.animate()
            .scaleX(1f).scaleY(1f).alpha(1f)
            .setDuration(120)
            .setInterpolator(FastOutSlowInInterpolator())
            .start()

        renderLive()
        startTick()
        // Bubble-tap generations open the popup BEFORE the async provision
        // lands: watch for it like submitRange does so the row appears.
        watchForFreshNumber(java.lang.System.currentTimeMillis())
    }

    fun hide() {
        handler.removeCallbacksAndMessages(null)
        tickHandler.removeCallbacksAndMessages(null)
        generating = false
        hideKeyboard()
        val root = rootView ?: return
        if (!root.isAttachedToWindow) {
            cleanup()
            return
        }
        root.alpha = 0f
        cleanup()
    }

    /** X swaps to the Gen pill as soon as the range holds any digit. */
    private fun syncGenButton(text: String) {
        val valid = smsIsRangePat(text)
        genWrap?.visibility = if (valid) View.VISIBLE else View.GONE
        closeBtn?.visibility = if (valid) View.GONE else View.VISIBLE
    }

    /** Enter / Gen: blur + close the keyboard, lock ~1.2s, generate once. */
    private fun submitRange() {
        if (generating || !isShowing()) return
        val raw = searchInput?.text?.toString() ?: ""
        if (!smsIsRangePat(raw)) return
        // Full typed/pasted text (e.g. 23762XXX) stays visible in the field
        // like the HTML mockup — only digits feed the provision prefix.
        val digits = raw.filter { it.isDigit() }
        if (digits.isEmpty()) return
        generating = true
        hideKeyboard()
        searchInput?.clearFocus()
        genLabel?.alpha = 0f
        genSpin?.visibility = View.VISIBLE
        // Provisioning is async (network): the number lands in SmsWatcher.mine
        // AFTER onGenerate returns, so renderLive() here would paint too
        // early. Watch for the fresh entry instead (bounded, self-stopping).
        val t0 = java.lang.System.currentTimeMillis()
        handler.postDelayed({
            try {
                onGenerate(digits)
            } catch (_: Exception) {
            }
            generating = false
            genLabel?.alpha = 1f
            genSpin?.visibility = View.GONE
            if (isShowing()) {
                renderLive()
                watchForFreshNumber(t0)
            }
        }, 1200)
    }

    private fun hideKeyboard() {
        try {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            val token = searchInput?.windowToken ?: rootView?.windowToken
            if (token != null) imm.hideSoftInputFromWindow(token, 0)
        } catch (_: Exception) {
        }
    }

    private fun showKeyboard(input: EditText) {
        try {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        } catch (_: Exception) {
        }
    }

    /** Unfocus the range field + hide the keyboard; popup stays open. */
    private fun clearFieldFocus() {
        try {
            searchInput?.clearFocus()
        } catch (_: Exception) {
        }
        hideKeyboard()
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!isShowing()) return
            renderLive()
            if (SmsWatcher.hasWaiting()) {
                tickHandler.postDelayed(this, 1000)
            }
        }
    }

    private fun startTick() {
        tickHandler.removeCallbacks(tickRunnable)
        renderLive()
        if (SmsWatcher.hasWaiting()) {
            tickHandler.postDelayed(tickRunnable, 1000)
        }
    }

    // Re-render until the just-provisioned number lands in mine (or 30s
    // pass): the gateway call resolves after onGenerate returns, so a single
    // render would miss it. Once it lands, startTick() takes over the live
    // mm:ss + code updates.
    private val watchRunnable = object : Runnable {
        var t0 = 0L
        var left = 0
        override fun run() {
            if (!isShowing() || left <= 0) return
            left--
            renderLive()
            if (SmsWatcher.mine.any { it.born >= t0 }) {
                startTick()
            } else {
                tickHandler.postDelayed(this, 1000)
            }
        }
    }

    private fun watchForFreshNumber(t0: Long) {
        tickHandler.removeCallbacks(watchRunnable)
        watchRunnable.t0 = t0
        watchRunnable.left = 30
        tickHandler.postDelayed(watchRunnable, 1000)
    }

    private fun renderLive() {
        val list = listView ?: return
        // Pending on top, newest first — mirrors the HTML mockup.
        render(
            list,
            SmsWatcher.mine.sortedWith(
                compareBy({ it.code != null }, { -it.born })
            )
        )
    }

    private fun render(list: LinearLayout, numbers: List<SmsNum>) {
        val activeIds = numbers.mapTo(HashSet()) { it.id }
        rowViews.keys.filter { it !in activeIds }.forEach { id ->
            rowViews.remove(id)?.root?.let(list::removeView)
        }
        numbers.forEachIndexed { index, n ->
            val views = rowViews.getOrPut(n.id) { makeRow(n) }
            if (list.getChildAt(index) !== views.root) {
                list.removeView(views.root)
                list.addView(views.root, index)
            }
            updateRow(views, n)
        }
        while (list.childCount > numbers.size) {
            list.removeViewAt(list.childCount - 1)
        }
        val empty = numbers.isEmpty()
        emptyView?.visibility = if (empty) View.VISIBLE else View.GONE
        scrollView?.visibility = if (empty) View.GONE else View.VISIBLE
    }

    private fun makeRow(n: SmsNum): RowViews {
        val row = LayoutInflater.from(activeInflateContext).inflate(R.layout.bubble_sms_row, listView, false)
        val views = RowViews(
            root = row,
            flag = row.findViewById(R.id.sms_row_flag),
            name = row.findViewById(R.id.sms_row_name),
            time = row.findViewById(R.id.sms_row_time),
            spin = row.findViewById(R.id.sms_row_spin),
            code = row.findViewById(R.id.sms_row_code),
        )
        try {
            views.spin.indeterminateTintList = ColorStateList.valueOf(Color.parseColor("#0C0C14"))
        } catch (_: Exception) {
        }
        return views
    }

    private fun updateRow(views: RowViews, n: SmsNum) {
        val code = n.code
        views.flag.text = n.flag
        views.name.text = n.display
        if (code != null) {
            views.time.visibility = View.GONE
            views.spin.visibility = View.GONE
            views.code.visibility = View.VISIBLE
            views.code.text = code
        } else {
            views.time.visibility = View.VISIBLE
            views.time.text = elapsed(n.born)
            views.spin.visibility = View.VISIBLE
            views.code.visibility = View.GONE
        }
        views.root.contentDescription = if (code != null) {
            "Copy code $code"
        } else {
            "Copy number ${n.display}"
        }
        views.root.isClickable = true
        views.root.isFocusable = true
        views.root.setOnClickListener { onNumberCopy(code ?: n.display) }
        views.code.isClickable = code != null
        views.code.isFocusable = code != null
        views.code.contentDescription = if (code != null) "Copy code $code" else null
        views.code.setOnClickListener { code?.let(onNumberCopy) }
    }

    private fun elapsed(born: Long): String {
        val sec = ((java.lang.System.currentTimeMillis() - born) / 1000).coerceAtLeast(0)
        return String.format("%02d:%02d", sec / 60, sec % 60)
    }

    private fun cleanup() {
        handler.removeCallbacksAndMessages(null)
        tickHandler.removeCallbacksAndMessages(null)
        generating = false
        val root = rootView
        if (root != null) {
            try {
                root.viewTreeObserver.removeOnGlobalLayoutListener(globalLayoutListener)
            } catch (_: Exception) {
            }
            if (root.isAttachedToWindow) {
                try {
                    windowManager.removeView(root)
                } catch (_: Exception) {
                }
            }
        }
        rootView = null
        panelView = null
        scrollView = null
        listView = null
        rowViews.clear()
        emptyView = null
        searchInput = null
        genWrap = null
        genLabel = null
        genSpin = null
        closeBtn = null
        try {
            onDismissed()
        } catch (_: Exception) {
        }
    }

    private fun displayBounds(): Rect {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val b = windowManager.currentWindowMetrics.bounds
                return Rect(0, 0, b.width(), b.height())
            } catch (_: Exception) {
            }
        }
        val dm = context.resources.displayMetrics
        return Rect(0, 0, dm.widthPixels, dm.heightPixels)
    }

    private fun systemBarInsets(): Rect {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val insets = windowManager.currentWindowMetrics.windowInsets
                    .getInsets(WindowInsets.Type.systemBars())
                return Rect(insets.left, insets.top, insets.right, insets.bottom)
            } catch (_: Exception) {
            }
        }
        val statusBar = try {
            context.resources.getDimensionPixelSize(
                context.resources.getIdentifier("status_bar_height", "dimen", "android")
            )
        } catch (_: Exception) {
            0
        }
        val navBar = try {
            context.resources.getDimensionPixelSize(
                context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
            )
        } catch (_: Exception) {
            0
        }
        return Rect(0, statusBar, 0, navBar)
    }

    /** Display bounds minus ALL four system-bar/cutout insets. */
    private fun contentBounds(): Rect {
        val b = displayBounds()
        val i = systemBarInsets()
        return Rect(i.left, i.top, b.width() - i.right, b.height() - i.bottom)
    }

    /**
     * When the range field is focused the IME covers the lower part of the
     * overlay (SOFT_INPUT_ADJUST_RESIZE shrinks the window), so push the panel
     * up so its bottom stays above the top of the keyboard — same as the
     * proxy popup.
     */
    private fun repositionPanelAboveIme(root: View) {
        val panel = panelView ?: return
        val displayH = displayBounds().height()
        val imeHeight = (displayH - root.height).coerceAtLeast(0)
        if (imeHeight <= 0) return
        val bounds = contentBounds()
        val panelBottom = panel.top + panel.height
        val limitBottom = displayH - imeHeight
        if (panelBottom > limitBottom) {
            val delta = panelBottom - limitBottom
            val lp = panel.layoutParams as? FrameLayout.LayoutParams ?: return
            lp.topMargin = (lp.topMargin - delta).coerceAtLeast(bounds.top)
            panel.requestLayout()
        }
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun dp(value: Float): Int = (value * context.resources.displayMetrics.density).toInt()
}
