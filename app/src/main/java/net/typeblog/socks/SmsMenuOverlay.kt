package net.typeblog.socks

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import net.typeblog.socks.util.SmsNum
import net.typeblog.socks.util.ThemeMode
import java.util.Locale

/**
 * Long-press popup for the circle-menu SMS bubble, built on the exact same
 * panel as the proxy popup ([BubbleMenuOverlay]): the same
 * `R.layout.bubble_menu` shell (search bar + scrollable rows), the same
 * `R.layout.bubble_country_row` rows, the same 4-side bubble-edge positioning
 * and grow-in, the same scrim-tap-to-close. Only the content differs: the
 * search filters numbers, rows are numbers (tap copies the arrived code, else
 * the number), then New number / Open SMS tab actions.
 */
class SmsMenuOverlay(
    private val context: Context,
    private val onNumberCopy: (String) -> Unit,
    private val onNewNumber: () -> Unit,
    private val onOpenSmsTab: () -> Unit,
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

    private fun isLightMode(): Boolean = !ThemeMode.isDarkTheme(context)
    private var activeInflateContext: Context = context

    private val handler = Handler(Looper.getMainLooper())
    private var rootView: FrameLayout? = null
    private var menuList: LinearLayout? = null
    private var numbers: List<SmsNum> = emptyList()

    private val globalLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        val root = rootView ?: return@OnGlobalLayoutListener
        if (!root.isAttachedToWindow) return@OnGlobalLayoutListener
        val search = root.findViewById<EditText>(R.id.menu_search) ?: return@OnGlobalLayoutListener
        if (!search.hasFocus()) return@OnGlobalLayoutListener
        repositionPanelAboveIme(root)
    }

    fun isShowing(): Boolean = rootView?.isAttachedToWindow == true

    fun show(bubbleCenterX: Int, bubbleCenterY: Int, bubbleSizePx: Int, nums: List<SmsNum>) {
        if (isShowing()) return
        numbers = nums.sortedByDescending { it.born }
        activeInflateContext = ThemeMode.themedContext(context)
        val root = try {
            rootView ?: LayoutInflater.from(activeInflateContext)
                .inflate(R.layout.bubble_menu, null) as FrameLayout
        } catch (_: Exception) {
            rootView = null
            return
        }
        rootView = root

        val panel = root.findViewById<LinearLayout>(R.id.menu_panel)
        val scroll = root.findViewById<ScrollView>(R.id.menu_scroll)
        val list = root.findViewById<LinearLayout>(R.id.menu_list)
        val searchInput = root.findViewById<EditText>(R.id.menu_search)
        if (panel == null || list == null || scroll == null || searchInput == null) {
            rootView = null
            return
        }
        // The shell's X just closes this popup (it is the bubble exit in the
        // proxy popup — not here).
        root.findViewById<ImageButton>(R.id.menu_dismiss)?.setOnClickListener { hide() }
        searchInput.hint = "Search numbers"
        menuList = list

        val bounds = contentBounds()
        val panelWidth = minOf(
            dp(230f),
            (bounds.width() - bubbleSizePx - dp(16f)).coerceAtLeast(1)
        )
        val maxHeightPx = minOf(dp(260f), bounds.height() - dp(32f)).coerceAtLeast(1)

        val panelLp = panel.layoutParams as? FrameLayout.LayoutParams
            ?: FrameLayout.LayoutParams(panelWidth, FrameLayout.LayoutParams.WRAP_CONTENT)
        panelLp.width = panelWidth
        panelLp.height = FrameLayout.LayoutParams.WRAP_CONTENT
        panel.layoutParams = panelLp

        renderList(list, "")

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                renderList(list, s?.toString()?.trim()?.lowercase(Locale.ROOT) ?: "")
                scroll.post { scroll.fullScroll(View.FOCUS_UP) }
            }
        })
        searchInput.setOnClickListener {
            searchInput.requestFocus()
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(searchInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
        searchInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.showSoftInput(searchInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
        }

        // Same smart 4-side positioning as the proxy popup.
        val margin8 = dp(8f)
        val bx = bubbleCenterX - bubbleSizePx / 2
        val by = bubbleCenterY - bubbleSizePx / 2
        val right = bounds.right - (bx + bubbleSizePx) - margin8
        val left = bx - bounds.left - margin8
        val bottom = bounds.bottom - (by + bubbleSizePx) - margin8
        val top = by - bounds.top - margin8

        fun fitsH(s: Int) = s >= panelWidth
        fun fitsV(s: Int) = s >= maxHeightPx

        val hOptions = listOf("right" to right, "left" to left).filter { fitsH(it.second) }
        val vOptions = listOf("bottom" to bottom, "top" to top).filter { fitsV(it.second) }

        val side = when {
            hOptions.isNotEmpty() -> hOptions.maxByOrNull { it.second }!!.first
            vOptions.isNotEmpty() -> vOptions.maxByOrNull { it.second }!!.first
            else -> listOf("right" to right, "left" to left, "bottom" to bottom, "top" to top)
                .maxByOrNull { it.second }!!.first
        }

        var panelX = when (side) {
            "right" -> bx + bubbleSizePx + margin8
            "left" -> bx - panelWidth - margin8
            else -> bx + bubbleSizePx / 2 - panelWidth / 2
        }
        var panelY = when (side) {
            "bottom" -> by + bubbleSizePx + margin8
            "top" -> by - maxHeightPx - margin8
            else -> by + bubbleSizePx / 2 - maxHeightPx / 2
        }
        panelX = panelX.coerceIn(bounds.left + margin8, bounds.right - panelWidth - margin8)
        panelY = panelY.coerceIn(bounds.top + margin8, bounds.bottom - maxHeightPx - margin8)

        panelLp.leftMargin = panelX
        panelLp.topMargin = panelY

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            0,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE

        root.alpha = 0f
        try {
            windowManager.addView(root, params)
        } catch (_: Exception) {
            rootView = null
            menuList = null
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
        panel.post {
            val listHeight = list.measuredHeight
            val targetHeight = if (listHeight > 0) listHeight.coerceAtMost(maxHeightPx) else maxHeightPx
            val scrollLp = scroll.layoutParams as? LinearLayout.LayoutParams
                ?: LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    targetHeight
                )
            if (scrollLp.height != targetHeight) {
                scrollLp.height = targetHeight
                scroll.layoutParams = scrollLp
            }
            val headerHeight = (panel.height - scroll.height).coerceAtLeast(0)
            val trueHeight = headerHeight + targetHeight
            val refinedY = when (side) {
                "top" -> (by - trueHeight - margin8).coerceIn(bounds.top + margin8, (bounds.bottom - trueHeight - margin8).coerceAtLeast(bounds.top + margin8))
                "bottom" -> (by + bubbleSizePx + margin8).coerceIn(bounds.top + margin8, (bounds.bottom - trueHeight - margin8).coerceAtLeast(bounds.top + margin8))
                else -> (bubbleCenterY - trueHeight / 2).coerceIn(bounds.top + margin8, (bounds.bottom - trueHeight - margin8).coerceAtLeast(bounds.top + margin8))
            }
            if (refinedY != panelLp.topMargin) {
                panelLp.topMargin = refinedY
                panel.requestLayout()
            }
            panel.pivotX = when (side) {
                "left" -> panelWidth.toFloat()
                "top", "bottom" -> panelWidth / 2f
                else -> 0f
            }
            panel.pivotY = when (side) {
                "top" -> trueHeight.toFloat()
                "bottom" -> 0f
                else -> trueHeight / 2f
            }
            panel.animate()
                .scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(120)
                .setInterpolator(FastOutSlowInInterpolator())
                .start()
        }
    }

    fun hide() {
        handler.removeCallbacksAndMessages(null)
        try {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            rootView?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
        } catch (_: Exception) {
        }
        val root = rootView ?: return
        if (!root.isAttachedToWindow) {
            cleanup()
            return
        }
        root.alpha = 0f
        cleanup()
    }

    private fun renderList(list: LinearLayout, query: String) {
        list.removeAllViews()
        val digits = query.filter { it.isDigit() }
        val matched = numbers.filter { n ->
            if (query.isEmpty()) {
                true
            } else {
                n.display.lowercase(Locale.ROOT).contains(query) ||
                    n.full.lowercase(Locale.ROOT).contains(query) ||
                    (n.code != null && n.code!!.lowercase(Locale.ROOT).contains(query)) ||
                    (!arrived(n) && "waiting".contains(query)) ||
                    (digits.isNotEmpty() && (n.display.filter { it.isDigit() }.contains(digits) || n.full.filter { it.isDigit() }.contains(digits)))
            }
        }
        if (matched.isEmpty() && numbers.isNotEmpty()) {
            list.addView(sectionLabel("No matches"))
        } else {
            if (matched.isNotEmpty()) {
                list.addView(sectionLabel(if (query.isEmpty()) "Numbers" else "Matches"))
                matched.forEach { n -> list.addView(makeNumberRow(n)) }
            } else {
                list.addView(sectionLabel("No number yet"))
            }
            list.addView(separatorView())
            list.addView(
                makeActionRow(flag = "+", name = "New number", hint = "get", onTap = {
                    hide()
                    onNewNumber()
                })
            )
            list.addView(
                makeActionRow(flag = "≡", name = "Open SMS tab", hint = "", onTap = {
                    hide()
                    onOpenSmsTab()
                })
            )
        }
    }

    private fun arrived(n: SmsNum): Boolean = n.code != null

    private fun makeNumberRow(n: SmsNum): View {
        val row = LayoutInflater.from(activeInflateContext).inflate(R.layout.bubble_country_row, menuList, false)
        row.findViewById<TextView>(R.id.row_flag).text = n.flag
        row.findViewById<TextView>(R.id.row_name).text = n.display
        row.findViewById<TextView>(R.id.row_code).text = n.country
        val dialView = row.findViewById<TextView>(R.id.row_dial)
        val dot = row.findViewById<View>(R.id.row_dot)
        if (arrived(n)) {
            dialView.text = n.code
            dialView.visibility = View.VISIBLE
            dialView.setTextColor(Color.parseColor("#1C9C7C"))
            dot.visibility = View.VISIBLE
        } else {
            dialView.text = "waiting"
            dialView.visibility = View.VISIBLE
            dot.visibility = View.GONE
        }
        row.setOnClickListener {
            hide()
            onNumberCopy(n.display)
        }
        return row
    }

    private fun makeActionRow(flag: String, name: String, hint: String, onTap: () -> Unit): View {
        val row = LayoutInflater.from(activeInflateContext).inflate(R.layout.bubble_country_row, menuList, false)
        row.findViewById<TextView>(R.id.row_flag).text = flag
        row.findViewById<TextView>(R.id.row_name).text = name
        row.findViewById<TextView>(R.id.row_code).text = ""
        val dialView = row.findViewById<TextView>(R.id.row_dial)
        if (hint.isEmpty()) {
            dialView.visibility = View.GONE
        } else {
            dialView.text = hint
            dialView.visibility = View.VISIBLE
        }
        row.findViewById<View>(R.id.row_dot).visibility = View.GONE
        row.setOnClickListener { onTap() }
        return row
    }

    private fun sectionLabel(text: String): TextView = TextView(context).apply {
        this.text = text.uppercase(Locale.ROOT)
        textSize = 9f
        setTypeface(typeface, Typeface.BOLD)
        letterSpacing = 0.08f
        setTextColor(if (isLightMode()) Color.BLACK else Color.WHITE)
        setPadding(dp(5f), dp(1f), 0, dp(2f))
    }

    private fun separatorView(): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(1f)
        ).apply {
            setMargins(dp(6f), 0, dp(6f), 0)
        }
        setBackgroundColor(Color.parseColor(if (isLightMode()) "#E4E4E7" else "#3F3F46"))
    }

    private fun cleanup() {
        handler.removeCallbacksAndMessages(null)
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
        menuList = null
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
     * When the search field is focused the IME covers the lower part of the
     * overlay (SOFT_INPUT_ADJUST_RESIZE shrinks the window), so push the panel up
     * so its bottom stays above the top of the keyboard.
     */
    private fun repositionPanelAboveIme(root: View) {
        val panel = root.findViewById<LinearLayout>(R.id.menu_panel) ?: return
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
