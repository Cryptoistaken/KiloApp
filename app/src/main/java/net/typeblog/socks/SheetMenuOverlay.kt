package net.typeblog.socks

import android.content.Context
import android.graphics.Color
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
import android.widget.TextView
import net.typeblog.socks.util.ThemeMode
import net.typeblog.socks.util.sheet.NO_2FA
import net.typeblog.socks.util.sheet.SheetBubbleSnapshot
import net.typeblog.socks.util.sheet.SheetPreset
import net.typeblog.socks.util.sheet.SheetRow
import net.typeblog.socks.util.sheet.isNo2Fa

/**
 * Read-only 230dp x 280dp Sheet file window used by the floating Circle menu.
 * It mirrors the existing country/SMS popup shell: full-screen outside-tap
 * catcher, smart four-side placement, and bubble-edge grow-in. No cell, row,
 * toolbar, or menu control receives a click listener.
 */
class SheetMenuOverlay(
    private val context: Context,
    private val onOpened: () -> Unit,
    private val onDismissed: () -> Unit = {}
) {
    private var windowManager: WindowManager = createWindowManager()
    private val handler = Handler(Looper.getMainLooper())
    private var rootView: FrameLayout? = null
    private var rowsView: LinearLayout? = null
    private var emptyView: TextView? = null
    private var iconView: ImageView? = null
    private var nameView: TextView? = null
    private var descriptionView: TextView? = null
    private var lastSnapshot: SheetBubbleSnapshot? = null

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
        val rows = root.findViewById<LinearLayout>(R.id.bubble_sheet_rows) ?: return
        val empty = root.findViewById<TextView>(R.id.bubble_sheet_empty) ?: return
        val icon = root.findViewById<ImageView>(R.id.bubble_sheet_icon)
        val name = root.findViewById<TextView>(R.id.bubble_sheet_name)
        val description = root.findViewById<TextView>(R.id.bubble_sheet_description)
        rootView = root
        rowsView = rows
        emptyView = empty
        iconView = icon
        nameView = name
        descriptionView = description
        render(lastSnapshot)

        val bounds = contentBounds()
        val margin = dp(8f)
        val panelWidth = minOf(dp(230f), (bounds.width() - bubbleSizePx - dp(16f)).coerceAtLeast(1))
        val panelHeight = minOf(dp(280f), (bounds.height() - dp(16f)).coerceAtLeast(1))
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
        panel.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(120).start()
        root.post { if (isShowing()) onOpened() }
    }

    fun render(snapshot: SheetBubbleSnapshot?) {
        lastSnapshot = snapshot
        val rows = rowsView ?: return
        val empty = emptyView ?: return
        rows.removeAllViews()
        if (snapshot == null) {
            iconView?.setImageResource(R.drawable.ic_tab_sheet)
            nameView?.text = "Sheet"
            descriptionView?.text = "Select a file"
            empty.text = "Select a Sheet file first"
            empty.visibility = View.VISIBLE
            return
        }
        val file = snapshot.file
        iconView?.setImageResource(iconFor(file.preset))
        nameView?.text = file.name
        descriptionView?.text = file.preset.desc
        empty.visibility = View.GONE
        rows.addView(headerRow(file))
        snapshot.rows.take(40).forEachIndexed { index, row ->
            rows.addView(dataRow(file, row, index == snapshot.activeRow))
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
        lastSnapshot = null
        rowsView = null
        emptyView = null
        iconView = null
        nameView = null
        descriptionView = null
    }

    private fun headerRow(file: net.typeblog.socks.util.sheet.SheetFile): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(28f))
            background = colorDrawable(headerColor(), gridLineColor())
        }
        row.addView(cellView("", 26f, 0f, true))
        file.preset.columns.forEach { column ->
            row.addView(cellView(column.label, 0f, 1f, true))
        }
        row.addView(cellView("", 24f, 0f, true))
        return row
    }

    private fun dataRow(file: net.typeblog.socks.util.sheet.SheetFile, row: SheetRow, active: Boolean): LinearLayout {
        val line = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(30f))
            background = colorDrawable(if (active) activeRowColor() else surfaceColor(), gridLineColor())
        }
        line.addView(cellView((row.rowIdx + 1).toString(), 26f, 0f, false, rowNumber = true))
        file.preset.columns.forEach { column ->
            val value = if (column.key == "twofakey" && isNo2Fa(row.twofakey)) NO_2FA else row.cell(column.key)
            line.addView(cellView(value, 0f, 1f, false))
        }
        val dotCell = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(24f), dp(30f))
            setBackgroundColor(surfaceColor())
            addView(View(context).apply {
                layoutParams = FrameLayout.LayoutParams(dp(8f), dp(8f), Gravity.CENTER)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(statusColor(row))
                }
            })
        }
        line.addView(dotCell)
        return line
    }

    private fun cellView(
        value: String,
        width: Float,
        weight: Float,
        header: Boolean,
        rowNumber: Boolean = false
    ): TextView = TextView(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            if (width > 0f) dp(width) else 0,
            LinearLayout.LayoutParams.MATCH_PARENT,
            weight
        )
        text = value
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
        gravity = if (rowNumber) Gravity.CENTER else Gravity.CENTER_VERTICAL
        setPadding(dp(4f), 0, dp(4f), 0)
        textSize = if (header) 8f else 9f
        setTextColor(if (header) headerTextColor() else if (rowNumber) mutedTextColor() else textColor())
        typeface = if (header) Typeface.DEFAULT_BOLD else Typeface.MONOSPACE
    }

    private fun iconFor(preset: SheetPreset): Int = when (preset) {
        SheetPreset.COOKIE -> R.drawable.ic_ss_cookie
        SheetPreset.COMBO -> R.drawable.ic_ss_twofa
        SheetPreset.PAGE -> R.drawable.ic_ss_page
    }

    private fun statusColor(row: SheetRow): Int = when {
        row.dead || row.status == "bad" -> Color.rgb(0xE3, 0x3B, 0x2E)
        row.status == "eligible" -> Color.rgb(0x25, 0x63, 0xEB)
        row.status == "good" || row.status == "done" -> Color.rgb(0x22, 0x93, 0x42)
        row.status == "pending" -> Color.rgb(0xF5, 0xA6, 0x23)
        else -> gridLineColor()
    }

    private fun isDark(): Boolean = ThemeMode.isDarkTheme(context)
    private fun surfaceColor(): Int = if (isDark()) Color.rgb(0x27, 0x27, 0x2A) else Color.WHITE
    private fun headerColor(): Int = if (isDark()) Color.rgb(0x3F, 0x3F, 0x46) else Color.rgb(0xF4, 0xF4, 0xF5)
    private fun activeRowColor(): Int = if (isDark()) Color.rgb(0x14, 0x33, 0x2A) else Color.rgb(0xEC, 0xFD, 0xF5)
    private fun gridLineColor(): Int = if (isDark()) Color.rgb(0x3F, 0x3F, 0x46) else Color.rgb(0xE4, 0xE4, 0xE7)
    private fun textColor(): Int = if (isDark()) Color.rgb(0xED, 0xED, 0xED) else Color.rgb(0x18, 0x18, 0x1B)
    private fun headerTextColor(): Int = if (isDark()) Color.rgb(0xD4, 0xD4, 0xD8) else Color.rgb(0x52, 0x52, 0x5B)
    private fun mutedTextColor(): Int = if (isDark()) Color.rgb(0xA1, 0xA1, 0xAA) else Color.rgb(0x71, 0x71, 0x7A)

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
}
