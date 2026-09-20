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
    private var listView: LinearLayout? = null
    private var emptyView: ImageView? = null
    private var searchInput: EditText? = null
    private var genWrap: FrameLayout? = null
    private var genLabel: TextView? = null
    private var genSpin: ProgressBar? = null
    private var closeBtn: ImageButton? = null
    private var generating = false

    fun isShowing(): Boolean = rootView?.isAttachedToWindow == true

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
        emptyView = empty
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
        genT.setOnClickListener { submitRange() }
        close.setOnClickListener {
            input.setText("")
            hide()
        }

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

        // Same smart 4-side positioning as the proxy popup.
        val margin8 = dp(8f)
        val bx = bubbleCenterX - bubbleSizePx / 2
        val by = bubbleCenterY - bubbleSizePx / 2
        val right = bounds.right - (bx + bubbleSizePx) - margin8
        val left = bx - bounds.left - margin8
        val bottom = bounds.bottom - (by + bubbleSizePx) - margin8
        val top = by - bounds.top - margin8

        fun fitsH(s: Int) = s >= panelWidth
        fun fitsV(s: Int) = s >= panelHeight

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
            "top" -> by - panelHeight - margin8
            else -> by + bubbleSizePx / 2 - panelHeight / 2
        }
        panelX = panelX.coerceIn(bounds.left + margin8, bounds.right - panelWidth - margin8)
        panelY = panelY.coerceIn(bounds.top + margin8, bounds.bottom - panelHeight - margin8)

        panelLp.leftMargin = panelX
        panelLp.topMargin = panelY

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START

        root.alpha = 0f
        try {
            windowManager.addView(root, params)
        } catch (_: Exception) {
            rootView = null
            listView = null
            return
        }

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
        val valid = text.filter { it.isDigit() }.isNotEmpty()
        genWrap?.visibility = if (valid) View.VISIBLE else View.GONE
        closeBtn?.visibility = if (valid) View.GONE else View.VISIBLE
    }

    /** Enter / Gen: blur + close the keyboard, lock ~1.2s, generate once. */
    private fun submitRange() {
        if (generating || !isShowing()) return
        val digits = searchInput?.text?.toString()?.filter { it.isDigit() } ?: ""
        if (digits.isEmpty()) return
        generating = true
        hideKeyboard()
        searchInput?.clearFocus()
        genLabel?.alpha = 0f
        genSpin?.visibility = View.VISIBLE
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
                startTick()
            }
        }, 1200)
    }

    private fun hideKeyboard() {
        try {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            rootView?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
        } catch (_: Exception) {
        }
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

    private fun renderLive() {
        val list = listView ?: return
        render(list, SmsWatcher.mine.sortedBy { it.born })
    }

    private fun render(list: LinearLayout, numbers: List<SmsNum>) {
        list.removeAllViews()
        emptyView?.visibility = if (numbers.isEmpty()) View.VISIBLE else View.GONE
        numbers.forEach { n -> list.addView(makeRow(n)) }
    }

    private fun makeRow(n: SmsNum): View {
        val row = LayoutInflater.from(activeInflateContext).inflate(R.layout.bubble_sms_row, listView, false)
        row.findViewById<TextView>(R.id.sms_row_flag).text = n.flag
        row.findViewById<TextView>(R.id.sms_row_name).text = n.display
        val timeView = row.findViewById<TextView>(R.id.sms_row_time)
        val spin = row.findViewById<ProgressBar>(R.id.sms_row_spin)
        val codeView = row.findViewById<TextView>(R.id.sms_row_code)
        try {
            spin.indeterminateTintList = ColorStateList.valueOf(Color.parseColor("#0C0C14"))
        } catch (_: Exception) {
        }
        if (n.code != null) {
            timeView.visibility = View.GONE
            spin.visibility = View.GONE
            codeView.visibility = View.VISIBLE
            codeView.text = n.code
        } else {
            timeView.visibility = View.VISIBLE
            timeView.text = elapsed(n.born)
            spin.visibility = View.VISIBLE
            codeView.visibility = View.GONE
        }
        row.setOnClickListener {
            hide()
            onNumberCopy(n.display)
        }
        return row
    }

    private fun elapsed(born: Long): String {
        val sec = ((System.currentTimeMillis() - born) / 1000).coerceAtLeast(0)
        return String.format("%02d:%02d", sec / 60, sec % 60)
    }

    private fun cleanup() {
        handler.removeCallbacksAndMessages(null)
        tickHandler.removeCallbacksAndMessages(null)
        generating = false
        val root = rootView
        if (root != null && root.isAttachedToWindow) {
            try {
                windowManager.removeView(root)
            } catch (_: Exception) {
            }
        }
        rootView = null
        listView = null
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

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun dp(value: Float): Int = (value * context.resources.displayMetrics.density).toInt()
}
