package net.typeblog.socks

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import net.typeblog.socks.util.ThemeMode
import net.typeblog.socks.util.sheet.NO_2FA
import net.typeblog.socks.util.sheet.SheetBubbleSnapshot
import net.typeblog.socks.util.sheet.SheetColumn
import net.typeblog.socks.util.sheet.SheetPreset
import net.typeblog.socks.util.sheet.SheetRow
import net.typeblog.socks.util.sheet.isNo2Fa

/**
 * Sheet file window for the floating Circle menu. Same shell as the
 * proxy/SMS popups: fixed 230x280dp panel on menu_panel_bg, the same 44dp
 * top bar (file identity only — no close or "..." buttons, a tap outside
 * dismisses), the same smart four-side placement and grow-in.
 *
 * The body is the in-app file grid at small size: fixed header row plus a
 * scrollable window of up to [WINDOW_ROWS] data rows ending at the active
 * row, 36dp rows, 36dp row-number/dot rails, 13sp centered monospace
 * cells, site status colors, approved/hold fills, dup marks, per-cell
 * styles, and hidden columns — all from the same snapshot rules as
 * SheetDetailScreen. After every render the list scrolls to the active
 * row, so newly captured clipboard data is always on screen.
 */
class SheetMenuOverlay(
    private val context: Context,
    private val onOpened: () -> Unit,
    private val onDismissed: () -> Unit = {}
) {
    private var windowManager: WindowManager = createWindowManager()
    private val handler = Handler(Looper.getMainLooper())
    private var rootView: FrameLayout? = null
    private var headerView: LinearLayout? = null
    private var rowsView: LinearLayout? = null
    private var scrollView: ScrollView? = null
    private var emptyView: TextView? = null
    private var iconView: ImageView? = null
    private var nameView: TextView? = null
    private var descriptionView: TextView? = null
    private var scrolledOnce = false

    fun isShowing(): Boolean = rootView?.isAttachedToWindow == true

    fun onConfigurationChanged() {
        windowManager = createWindowManager()
        if (isShowing()) hide()
    }

    fun show(bubbleCenterX: Int, bubbleCenterY: Int, bubbleSizePx: Int) {
        if (isShowing()) return
        val themed = ThemeMode.themedContext(context)
        val root = try {
            LayoutInflater.from(themed).inflate(R.layout.bubble_sheet_menu, null) as FrameLayout
        } catch (_: Exception) {
            return
        }
        val panel = root.findViewById<LinearLayout>(R.id.bubble_sheet_panel) ?: return
        val header = root.findViewById<LinearLayout>(R.id.bubble_sheet_header_row) ?: return
        val rows = root.findViewById<LinearLayout>(R.id.bubble_sheet_rows) ?: return
        val scroll = root.findViewById<ScrollView>(R.id.bubble_sheet_scroll)
        val empty = root.findViewById<TextView>(R.id.bubble_sheet_empty) ?: return
        val icon = root.findViewById<ImageView>(R.id.bubble_sheet_icon)
        val name = root.findViewById<TextView>(R.id.bubble_sheet_name)
        val description = root.findViewById<TextView>(R.id.bubble_sheet_description)
        rootView = root
        headerView = header
        rowsView = rows
        scrollView = scroll
        emptyView = empty
        iconView = icon
        nameView = name
        descriptionView = description
        scrolledOnce = false
        // Placeholder identity until the service renders the loaded snapshot
        // right after attach (DB load + clipboard capture).
        icon?.setImageResource(R.drawable.ic_tab_sheet)
        name?.text = "Sheet"
        description?.text = ""
        empty.visibility = View.GONE

        val bounds = contentBounds()
        val margin = dp(8f)
        val panelWidth = minOf(dp(230f), (bounds.width() - bubbleSizePx - dp(16f)).coerceAtLeast(1))
        val panelHeight = dp(280f)
        val panelLp = panel.layoutParams as? FrameLayout.LayoutParams
            ?: FrameLayout.LayoutParams(panelWidth, panelHeight)
        panelLp.width = panelWidth
        panelLp.height = panelHeight

        val placement = BubblePopupPlacer.place(
            bounds = bounds,
            bubbleCenterX = bubbleCenterX,
            bubbleCenterY = bubbleCenterY,
            bubbleSizePx = bubbleSizePx,
            panelWidth = panelWidth,
            panelHeight = panelHeight,
            marginPx = margin
        )
        val side = placement.side
        panelLp.leftMargin = placement.x
        panelLp.topMargin = placement.y
        panel.layoutParams = panelLp

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            0,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        root.alpha = 0f
        try {
            windowManager.addView(root, params)
        } catch (_: Exception) {
            clearReferences()
            return
        }
        root.setOnClickListener { hide() }
        root.requestFocus()
        // The panel consumes interior touches so a tap on a cell never reaches
        // the outside-dismiss listener. The children remain non-clickable.
        panel.isClickable = true
        panel.setOnClickListener { }
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
        root.post { if (isShowing()) onOpened() }
    }

    fun render(snapshot: SheetBubbleSnapshot?) {
        if (snapshot == null) {
            hide()
            return
        }
        val header = headerView ?: return
        val rows = rowsView ?: return
        val empty = emptyView ?: return
        val scroll = scrollView
        header.removeAllViews()
        rows.removeAllViews()
        val file = snapshot.file
        iconView?.setImageResource(iconFor(file.preset))
        nameView?.text = file.name
        descriptionView?.text = file.preset.desc
        val cols = file.preset.columns.filter { !snapshot.hidden.contains(it.key) }
        val dataIdx = snapshot.rows.mapIndexedNotNull { index, row ->
            if (row.isData(file.preset.columns)) index else null
        }
        when {
            cols.isEmpty() -> {
                empty.text = "All columns hidden. Use the menu."
                empty.visibility = View.VISIBLE
                scroll?.visibility = View.GONE
                return
            }
            dataIdx.isEmpty() -> {
                empty.text = "No rows yet"
                empty.visibility = View.VISIBLE
                scroll?.visibility = View.GONE
                return
            }
        }
        empty.visibility = View.GONE
        scroll?.visibility = View.VISIBLE
        header.addView(headerRow(cols))
        // Window of up to WINDOW_ROWS data rows ending at the active row, so
        // the row holding freshly captured data is always on screen.
        val endPos = dataIdx.indexOf(snapshot.activeRow)
            .let { if (it < 0) dataIdx.size else it + 1 }
        val startPos = (endPos - WINDOW_ROWS).coerceAtLeast(0)
        for (pos in startPos until endPos) {
            val index = dataIdx[pos]
            snapshot.rows.getOrNull(index)?.let { row ->
                rows.addView(dataRow(snapshot, cols, row, index == snapshot.activeRow))
            }
        }
        val activePos = (endPos - startPos - 1).coerceAtLeast(0)
        scroll?.post {
            if (!isShowing()) return@post
            val y = activePos * dp(36f)
            if (scrolledOnce) scroll.smoothScrollTo(0, y) else scroll.scrollTo(0, y)
            scrolledOnce = true
        }
    }

    fun hide() {
        handler.removeCallbacksAndMessages(null)
        val root = rootView
        rootView = null
        if (root != null) {
            try {
                if (root.isAttachedToWindow) windowManager.removeView(root)
            } catch (_: Exception) {
            }
        }
        clearReferences()
        if (root != null) onDismissed()
    }

    private fun clearReferences() {
        headerView = null
        rowsView = null
        scrollView = null
        emptyView = null
        iconView = null
        nameView = null
        descriptionView = null
    }

    private fun headerRow(cols: List<SheetColumn>): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36f))
            setBackgroundColor(headerColor())
        }
        row.addView(headerCell("", RAIL_DP))
        cols.forEach { column -> row.addView(headerCell(column.label, 0f, 1f)) }
        row.addView(headerCell("", RAIL_DP))
        return row
    }

    private fun headerCell(value: String, widthDp: Float, weight: Float = 0f): TextView =
        TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                if (widthDp > 0f) dp(widthDp) else 0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                weight
            )
            background = colorDrawable(headerColor(), gridLineColor())
            text = value
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            gravity = Gravity.CENTER
            setPadding(dp(8f), 0, dp(8f), 0)
            textSize = 12f
            setTextColor(headerTextColor())
            typeface = Typeface.DEFAULT_BOLD
        }

    private fun dataRow(
        snapshot: SheetBubbleSnapshot,
        cols: List<SheetColumn>,
        row: SheetRow,
        active: Boolean
    ): LinearLayout {
        val statusFill: Int? = when {
            row.dead || row.status == "bad" -> DeadRed
            row.status == "eligible" -> PageBlue
            row.status == "good" || row.status == "done" -> AliveGreen
            else -> null
        }
        val line = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36f))
        }
        // Row-number rail: surfaceVariant, status-filled when approved.
        val numBg = if (row.approved && statusFill != null) statusFill else surfaceVariantColor()
        line.addView(TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(RAIL_DP), LinearLayout.LayoutParams.MATCH_PARENT)
            background = colorDrawable(numBg, gridLineColor())
            text = (row.rowIdx + 1).toString()
            maxLines = 1
            includeFontPadding = false
            gravity = Gravity.CENTER
            textSize = 11f
            setTextColor(mutedTextColor())
            typeface = Typeface.DEFAULT
        })
        cols.forEach { column ->
            val raw = if (column.key == "twofakey" && isNo2Fa(row.twofakey)) NO_2FA else row.cell(column.key)
            val style = snapshot.styles["${row.rowIdx}:${column.key}"]
            val customBg = parseHexColor(style?.bg)
            val fg = parseHexColor(style?.color)
            val isDup = snapshot.dups.contains(Pair(row.rowIdx, column.key))
            // In-app fill order: custom style > dup tint > hold/approved
            // status > transparent.
            val fill = when {
                customBg != null -> customBg
                isDup -> dupTint()
                (row.hold || row.approved) && statusFill != null -> statusFill
                active -> activeRowColor()
                else -> surfaceColor()
            }
            val border = when {
                isDup -> DupYellow
                else -> gridLineColor()
            }
            line.addView(TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                background = colorDrawable(fill, border)
                text = raw
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                includeFontPadding = false
                gravity = Gravity.CENTER
                setPadding(dp(8f), 0, dp(8f), 0)
                textSize = 13f
                setTextColor(fg ?: textColor())
                typeface = if (style?.bold == true) Typeface.DEFAULT_BOLD else Typeface.MONOSPACE
                if (row.approved) paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            })
        }
        // Status rail: surface cell with the site status mark (rounded 8dp
        // square, 2.5dp radius — never a circle).
        line.addView(FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(RAIL_DP), dp(36f))
            background = colorDrawable(surfaceColor(), gridLineColor())
            addView(View(context).apply {
                layoutParams = FrameLayout.LayoutParams(dp(8f), dp(8f), Gravity.CENTER)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dpF(2.5f)
                    setColor(dotColor(row))
                }
            })
        })
        return line
    }

    private fun iconFor(preset: SheetPreset): Int = when (preset) {
        SheetPreset.COOKIE -> R.drawable.ic_ss_cookie
        SheetPreset.COMBO -> R.drawable.ic_ss_twofa
        SheetPreset.PAGE -> R.drawable.ic_ss_page
    }

    private fun dotColor(row: SheetRow): Int = when {
        row.dead || row.status == "bad" -> DeadRed
        row.status == "eligible" -> PageBlue
        row.status == "good" || row.status == "done" -> AliveGreen
        row.status == "pending" -> StatusYellow
        else -> gridLineColor()
    }

    private fun parseHexColor(hex: String?): Int? {
        if (hex == null) return null
        var h = hex.trim().removePrefix("#")
        if (h.length == 3) h = h.map { "$it$it" }.joinToString("")
        if (h.length != 6) return null
        return try {
            Color.rgb(
                h.substring(0, 2).toInt(16),
                h.substring(2, 4).toInt(16),
                h.substring(4, 6).toInt(16)
            )
        } catch (_: Exception) {
            null
        }
    }

    // Exact app theme tokens (KiloProxyTheme monochrome); status colors are
    // the shared site tokens from SheetUi.
    private fun isDark(): Boolean = ThemeMode.isDarkTheme(context)
    private fun surfaceColor(): Int = if (isDark()) Color.rgb(0x0A, 0x0A, 0x0A) else Color.WHITE
    private fun surfaceVariantColor(): Int = if (isDark()) Color.rgb(0x1A, 0x1A, 0x1A) else Color.rgb(0xF5, 0xF5, 0xF5)
    private fun headerColor(): Int = surfaceVariantColor()
    private fun activeRowColor(): Int = if (isDark()) Color.rgb(0x14, 0x33, 0x2A) else Color.rgb(0xEC, 0xFD, 0xF5)
    private fun gridLineColor(): Int = if (isDark()) Color.rgb(0x22, 0x22, 0x22) else Color.rgb(0xEE, 0xEE, 0xEE)
    private fun textColor(): Int = if (isDark()) Color.WHITE else Color.BLACK
    private fun headerTextColor(): Int = if (isDark()) Color.rgb(0xAA, 0xAA, 0xAA) else Color.rgb(0x55, 0x55, 0x55)
    private fun mutedTextColor(): Int = if (isDark()) Color.argb(153, 0xAA, 0xAA, 0xAA) else Color.argb(153, 0x55, 0x55, 0x55)

    private companion object {
        const val RAIL_DP = 36f
        const val WINDOW_ROWS = 10
        val DeadRed = Color.rgb(0xE3, 0x3B, 0x2E)
        val PageBlue = Color.rgb(0x25, 0x63, 0xEB)
        val AliveGreen = Color.rgb(0x22, 0x93, 0x42)
        val StatusYellow = Color.rgb(0xF5, 0xA6, 0x23)
        val DupYellow = Color.rgb(0xF5, 0xA6, 0x23)
        fun dupTint(): Int = Color.argb(38, 0xF5, 0xA6, 0x23)
    }

    private fun colorDrawable(fill: Int, stroke: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        setStroke(dp(1f), stroke)
    }

    private fun createWindowManager(): WindowManager {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
        val display = dm.getDisplay(android.view.Display.DEFAULT_DISPLAY)
            ?: return context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return context.createDisplayContext(display).getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    private fun contentBounds(): android.graphics.Rect {
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try { windowManager.currentWindowMetrics.bounds } catch (_: Exception) { null }
        } else null
        val b = display ?: context.resources.displayMetrics.let {
            android.graphics.Rect(0, 0, it.widthPixels, it.heightPixels)
        }
        val insets = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try { windowManager.currentWindowMetrics.windowInsets.getInsets(WindowInsets.Type.systemBars()) } catch (_: Exception) { null }
        } else null
        return if (insets != null) {
            android.graphics.Rect(insets.left, insets.top, b.width() - insets.right, b.height() - insets.bottom)
        } else {
            b
        }
    }

    private fun overlayType(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
        @Suppress("DEPRECATION")
        WindowManager.LayoutParams.TYPE_PHONE
    }

    private fun dp(value: Float): Int = (value * context.resources.displayMetrics.density).toInt()
    private fun dpF(value: Float): Float = value * context.resources.displayMetrics.density
}
