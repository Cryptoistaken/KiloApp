package net.typeblog.socks

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeoutOrNull
import net.typeblog.socks.ui.screens.sheet.SheetGrid
import net.typeblog.socks.ui.theme.KiloProxyTheme
import net.typeblog.socks.util.ThemeMode
import net.typeblog.socks.util.sheet.BUBBLE_WINDOW_ROWS
import net.typeblog.socks.util.sheet.MAX_GRID_ROWS
import net.typeblog.socks.util.sheet.SheetBubbleSnapshot
import net.typeblog.socks.util.sheet.SheetPreset

/**
 * Sheet file window for the floating Circle menu. Same shell as the
 * proxy/SMS popups: fixed 230x280dp panel on menu_panel_bg, the same 44dp
 * top bar (file identity plus undo / redo / Check split button — no close
 * or "..." buttons, a tap outside dismisses), the same smart four-side
 * placement and grow-in.
 *
 * The grid is the shared SheetGrid component hosted in a ComposeView — the
 * exact file view from the app, read-only, at compact bubble metrics
 * (24dp rows/rails, 10sp cells). The popup renders a BUBBLE_WINDOW_ROWS
 * (20) row window at first paint and grows it as data is saved (and when
 * scrolled near its end); inside the window LazyColumn virtualizes, so
 * the tap never freezes. Scroll targets the last data row (never the
 * empty capture slot) and only moves when that row is NOT already on
 * screen: a file that fits shows row 1 with no jump, an overflowing
 * list reveals a newly-saved row. undo/redo/check re-renders stay put.
 */
class SheetMenuOverlay(
    private val context: Context,
    private val onOpened: () -> Unit,
    private val onDismissed: () -> Unit = {},
    private val onUndo: () -> Unit = {},
    private val onRedo: () -> Unit = {},
    private val onCheck: () -> Unit = {}
) {
    private var windowManager: WindowManager = createWindowManager()
    private val handler = Handler(Looper.getMainLooper())
    private var rootView: FrameLayout? = null
    private var composeView: ComposeView? = null
    private var emptyView: TextView? = null
    private var iconView: ImageView? = null
    private var nameView: TextView? = null
    private var descriptionView: TextView? = null
    private var undoView: ImageButton? = null
    private var redoView: ImageButton? = null
    private var checkPill: LinearLayout? = null
    private var checkView: TextView? = null
    private var checkSpinner: ProgressBar? = null
    private var arrowView: ImageButton? = null
    private var menuView: LinearLayout? = null
    private var lastSnapshot: SheetBubbleSnapshot? = null
    private var scrolledOnce = false
    private var lastScrolledTarget = Int.MIN_VALUE
    // Composition state: the grid content reads the snapshot; scrollGen
    // bumps only when a recenter is actually wanted (smart scroll).
    private var snapshotState = mutableStateOf<SheetBubbleSnapshot?>(null)
    private var scrollGenState = mutableIntStateOf(0)
    private var scrollTarget = 0
    // Popup-only row window: first paint holds BUBBLE_WINDOW_ROWS rows and
    // grows as data lands (and when scrolled near its end), so a small file
    // is cheap without hiding history.
    private var rowWindowState = mutableIntStateOf(BUBBLE_WINDOW_ROWS)
    private var lastDataCount = 0
    private val gridListState = androidx.compose.foundation.lazy.LazyListState()
    private var overlayScope: CoroutineScope? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null

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
        val compose = root.findViewById<ComposeView>(R.id.bubble_sheet_grid)
        val empty = root.findViewById<TextView>(R.id.bubble_sheet_empty) ?: return
        val icon = root.findViewById<ImageView>(R.id.bubble_sheet_icon)
        val name = root.findViewById<TextView>(R.id.bubble_sheet_name)
        val description = root.findViewById<TextView>(R.id.bubble_sheet_description)
        val undo = root.findViewById<ImageButton>(R.id.bubble_tool_undo)
        val redo = root.findViewById<ImageButton>(R.id.bubble_tool_redo)
        val pill = root.findViewById<LinearLayout>(R.id.bubble_tool_check_pill)
        val check = root.findViewById<TextView>(R.id.bubble_tool_check)
        val spinner = root.findViewById<ProgressBar>(R.id.bubble_tool_spinner)
        val arrow = root.findViewById<ImageButton>(R.id.bubble_tool_arrow)
        val menu = root.findViewById<LinearLayout>(R.id.bubble_check_menu)
        rootView = root
        composeView = compose
        emptyView = empty
        iconView = icon
        nameView = name
        descriptionView = description
        undoView = undo
        redoView = redo
        checkPill = pill
        checkView = check
        checkSpinner = spinner
        arrowView = arrow
        menuView = menu
        lastSnapshot = null
        scrolledOnce = false
        lastScrolledTarget = Int.MIN_VALUE
        snapshotState.value = null
        scrollGenState.intValue = 0
        rowWindowState.intValue = BUBBLE_WINDOW_ROWS
        lastDataCount = 0
        // Placeholder identity until the service renders the loaded snapshot
        // right after attach (DB load + clipboard capture + auto-check).
        icon?.setImageResource(R.drawable.ic_tab_sheet)
        name?.text = "Sheet"
        description?.text = ""
        empty.visibility = View.GONE
        undo?.setColorFilter(textColor())
        redo?.setColorFilter(textColor())
        pill?.background = pillDrawable(primaryColor())
        check?.setTextColor(onPrimaryColor())
        try {
            spinner?.indeterminateTintList =
                android.content.res.ColorStateList.valueOf(onPrimaryColor())
        } catch (_: Exception) {
        }
        undo?.setOnClickListener { onUndo() }
        redo?.setOnClickListener { onRedo() }
        check?.setOnClickListener { onCheck() }
        arrow?.setColorFilter(onPrimaryColor())
        arrow?.setOnClickListener { toggleMenu() }
        try {
            menu?.background = pillDrawable(surfaceColor())
        } catch (_: Exception) {
        }
        // Overlay windows provide no lifecycle owners on their own, and
        // ComposeView needs all three trees (rememberSaveable included).
        overlayScope?.cancel()
        overlayScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val owner = OverlayLifecycleOwner()
        lifecycleOwner = owner
        compose?.let { cv ->
            cv.visibility = View.GONE
            // Tags must sit on the window root: ComposeView resolves its
            // parent composition context (and recomposer) by walking UP
            // from its parent, so owners on the ComposeView itself are
            // never found (ViewTreeLifecycleOwner not found crash).
            attachTreeOwners(root, owner)
            attachTreeOwners(cv, owner)
            owner.create()
            cv.setContent {
                KiloProxyTheme {
                    val snap = snapshotState.value
                    if (snap != null) {
                        val cols =
                            snap.file.preset.columns.filter { !snap.hidden.contains(it.key) }
                        val dataCount =
                            snap.rows.count { it.isData(snap.file.preset.columns) }
                        val visibleRows = snap.rows.take(rowWindowState.intValue)
                        SheetGrid(
                            rows = visibleRows,
                            visibleCols = cols,
                            styles = snap.styles,
                            crossDups = snap.dups,
                            modifier = Modifier.fillMaxSize(),
                            railWidth = 24.dp,
                            rowHeight = 24.dp,
                            cellTextSize = 10.sp,
                            headerTextSize = 10.sp,
                            railTextSize = 9.sp,
                            listState = gridListState,
                            footer = {
                                Text(
                                    text = "$dataCount rows",
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                    fontSize = 11.sp,
                                    textAlign = TextAlign.Center,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        )
                    }
                    val gen = scrollGenState.intValue
                    LaunchedEffect(gen) {
                        if (gen > 0) {
                            // Wait for the list to measure so the visibility
                            // check below is accurate (empty on frame 0).
                            withTimeoutOrNull(300) {
                                snapshotFlow {
                                    gridListState.layoutInfo.visibleItemsInfo.isNotEmpty()
                                }.first { it }
                            }
                            val n = rowWindowState.intValue
                            if (n > 0) {
                                try {
                                    // Sticky header occupies Lazy index 0, so
                                    // row k lives at index k + 1.
                                    val targetLazy =
                                        (scrollTarget + 1).coerceIn(1, n)
                                    val alreadyVisible = gridListState
                                        .layoutInfo.visibleItemsInfo
                                        .any { it.index == targetLazy }
                                    // Only scroll when the newest row is NOT
                                    // already on screen: a file that fits shows
                                    // row 1 (no jump); an overflowing list
                                    // reveals the newly-saved row.
                                    if (!alreadyVisible) {
                                        gridListState.scrollToItem(targetLazy)
                                    }
                                } catch (_: Exception) {
                                }
                            }
                            scrolledOnce = true
                        }
                    }
                    // Scrolling near the end of the window reveals more rows,
                    // so saved history past the first 20 stays reachable.
                    LaunchedEffect(snap) {
                        snapshotFlow {
                            gridListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                        }.collect { last ->
                            val win = rowWindowState.intValue
                            val total = snap?.rows?.size ?: 0
                            if (last >= win - 3 && win < total) {
                                rowWindowState.intValue =
                                    (win + BUBBLE_WINDOW_ROWS).coerceAtMost(total)
                            }
                        }
                    }
                }
            }
            owner.resume()
        }
        refreshMenuRows()
        renderToolbar(canUndo = false, canRedo = false, checking = false)

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
        panel.setOnClickListener { hideMenu() }
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
        // The grid is usually composing its first rows mid-animation (local
        // DB lands fast): without a hardware layer every scale frame
        // re-composites the live grid and the open looks slow-motion.
        panel.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        panel.animate()
            .scaleX(1f).scaleY(1f).alpha(1f)
            .setDuration(120)
            .setInterpolator(FastOutSlowInInterpolator())
            .withEndAction { panel.setLayerType(View.LAYER_TYPE_NONE, null) }
            .start()
        root.post { if (isShowing()) onOpened() }
    }

    fun render(snapshot: SheetBubbleSnapshot?) {
        if (snapshot == null) {
            hide()
            return
        }
        val empty = emptyView ?: return
        lastSnapshot = snapshot
        val file = snapshot.file
        iconView?.setImageResource(iconFor(file.preset))
        nameView?.text = file.name
        descriptionView?.text = file.preset.desc
        val cols = file.preset.columns.filter { !snapshot.hidden.contains(it.key) }
        val dataCount = snapshot.rows.count { it.isData(file.preset.columns) }
        if (cols.isEmpty() || dataCount == 0) {
            empty.text = if (cols.isEmpty()) "All columns hidden. Use the menu." else "No rows yet"
            empty.visibility = View.VISIBLE
            composeView?.visibility = View.GONE
            return
        }
        empty.visibility = View.GONE
        composeView?.visibility = View.VISIBLE
        // The composition reads this state: same rows, same cells, same
        // status dots as the app — LazyColumn only composes the visible
        // ones, so any file size paints instantly.
        snapshotState.value = snapshot
        // Windowed render: 20 rows at first, +20 as new data is saved (and
        // on scroll-to-end), so opening a small file stays cheap.
        if (dataCount > lastDataCount) {
            val win = rowWindowState.intValue
            rowWindowState.intValue =
                (win + BUBBLE_WINDOW_ROWS).coerceAtMost(MAX_GRID_ROWS)
        }
        lastDataCount = dataCount
        // Scroll target is the last DATA row, never the empty capture slot:
        // opening with one row shows row 1, not row 2. Recenter only on
        // first paint and when new data actually lands; undo/redo/check
        // re-renders stay exactly where the user left them.
        val endRow = snapshot.rows.indexOfLast { it.isData(snapshot.file.preset.columns) }.coerceAtLeast(0)
        val shouldScroll = !scrolledOnce || endRow != lastScrolledTarget
        lastScrolledTarget = endRow
        if (shouldScroll && isShowing()) {
            scrollTarget = endRow
            scrollGenState.intValue++
        }
    }

    /**
     * Toolbar states. Check arms only while a checkable row exists and no
     * check is running — same rule as the in-app Check split button.
     */
    fun renderToolbar(canUndo: Boolean, canRedo: Boolean, checking: Boolean) {
        undoView?.let {
            it.isEnabled = canUndo && !checking
            it.alpha = if (canUndo && !checking) 1f else 0.38f
        }
        redoView?.let {
            it.isEnabled = canRedo && !checking
            it.alpha = if (canRedo && !checking) 1f else 0.38f
        }
        val snap = lastSnapshot
        // Toggle-honoring enable rule (same targets the check itself sweeps):
        // UID arms on UID/c_user rows while its toggle is on; Simple /
        // Advanced arm on PAGE sweep candidates while either is on.
        val prefs = try { menuPrefs() } catch (_: Exception) { null }
        val uidOn = prefs?.getBoolean("ss_autoCheck", true) ?: true
        val pageOn = (prefs?.getBoolean("ss_pageSimple", false) ?: false) ||
            (prefs?.getBoolean("ss_pageAdvanced", false) ?: false)
        val checkable = snap != null && run {
            val cols = snap.file.preset.columns
            val uidTargets = snap.rows.any { r ->
                r.isData(cols) && !r.locked &&
                    (r.uid.isNotEmpty() || "c_user=" in r.cookies)
            }
            val pageTargets = snap.file.preset == SheetPreset.PAGE && pageOn &&
                snap.rows.any { r ->
                    r.isData(cols) && !r.locked && !r.approved && !r.hold && !r.dead &&
                        "c_user=" in r.cookies
                }
            (uidOn && uidTargets) || pageTargets
        }
        // In-app split button parity: the whole joined pill dims when there
        // is nothing checkable or a check is running; the left half runs the
        // check, the arrow only opens the menu (still available while
        // disabled, hidden while checking). Checking swaps the label for a
        // 10dp spinner + "Checking", like the in-app indicator row.
        val pillEnabled = checkable && !checking
        checkPill?.background = pillDrawable(if (pillEnabled) primaryColor() else dimColor(primaryColor()))
        checkView?.let {
            it.isEnabled = pillEnabled
            it.text = if (checking) "Checking" else "Check"
            it.setPadding(if (checking) dp(4f) else dp(8f), dp(4f), dp(8f), dp(4f))
        }
        checkSpinner?.visibility = if (checking) View.VISIBLE else View.GONE
        arrowView?.visibility = if (checking) View.GONE else View.VISIBLE
        refreshMenuRows()
    }

    /** In-app check menu parity: label + MiniSwitch rows, rebuilt from the
     * same prefs the app honors. UID toggles freely, Simple/Advanced are
     * exclusive. */
    private fun toggleMenu() {
        val menu = menuView ?: return
        menu.visibility = if (menu.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    private fun hideMenu() {
        menuView?.visibility = View.GONE
    }

    private fun menuPrefs(): android.content.SharedPreferences =
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)

    private fun refreshMenuRows() {
        val menu = menuView ?: return
        val prefs = try { menuPrefs() } catch (_: Exception) { return }
        val uid = prefs.getBoolean("ss_autoCheck", true)
        val simple = prefs.getBoolean("ss_pageSimple", false)
        val adv = prefs.getBoolean("ss_pageAdvanced", false)
        menu.removeAllViews()
        menu.addView(menuSwitchRow("UID check", uid) { toggleUid() })
        menu.addView(menuSwitchRow("Simple check", simple) { togglePage(simple = true) })
        menu.addView(menuSwitchRow("Advanced check", adv) { togglePage(simple = false) })
    }

    private fun menuSwitchRow(label: String, checked: Boolean, onTap: () -> Unit): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
            isClickable = true
            setOnClickListener { onTap() }
        }
        row.addView(TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = label
            textSize = 12f
            setTextColor(headerTextColor())
            typeface = Typeface.DEFAULT
        })
        // MiniSwitch mirror: 36x20 track, 12dp thumb, checked fills with
        // on-surface, unchecked shows a 2dp outline.
        val track = LinearLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(36f), dp(20f))
            orientation = LinearLayout.HORIZONTAL
            gravity = (if (checked) Gravity.END else Gravity.START) or Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpF(10f)
                setColor(if (checked) textColor() else surfaceVariantColor())
                if (!checked) setStroke(dp(2f), gridLineColor())
            }
            setPadding(dp(2f), dp(2f), dp(2f), dp(2f))
        }
        track.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(12f), dp(12f))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(surfaceColor())
            }
        })
        row.addView(track)
        return row
    }

    private fun toggleUid() {
        try {
            val prefs = menuPrefs()
            prefs.edit().putBoolean("ss_autoCheck", !prefs.getBoolean("ss_autoCheck", true)).apply()
        } catch (_: Exception) {
        }
        refreshMenuRows()
    }

    private fun togglePage(simple: Boolean) {
        try {
            val prefs = menuPrefs()
            if (simple) {
                val on = !prefs.getBoolean("ss_pageSimple", false)
                prefs.edit().putBoolean("ss_pageSimple", on).apply()
                if (on) prefs.edit().putBoolean("ss_pageAdvanced", false).apply()
            } else {
                val on = !prefs.getBoolean("ss_pageAdvanced", false)
                prefs.edit().putBoolean("ss_pageAdvanced", on).apply()
                if (on) prefs.edit().putBoolean("ss_pageSimple", false).apply()
            }
        } catch (_: Exception) {
        }
        refreshMenuRows()
    }

    fun hide() {
        handler.removeCallbacksAndMessages(null)
        overlayScope?.cancel()
        overlayScope = null
        try {
            composeView?.disposeComposition()
        } catch (_: Exception) {
        }
        try {
            lifecycleOwner?.pause()
            lifecycleOwner?.stop()
            lifecycleOwner?.destroy()
        } catch (_: Exception) {
        }
        lifecycleOwner = null
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
        composeView = null
        emptyView = null
        iconView = null
        nameView = null
        descriptionView = null
        undoView = null
        redoView = null
        checkPill = null
        checkView = null
        checkSpinner = null
        arrowView = null
        menuView = null
        lastSnapshot = null
        snapshotState.value = null
    }

    private fun iconFor(preset: SheetPreset): Int = when (preset) {
        SheetPreset.COOKIE -> R.drawable.ic_ss_cookie
        SheetPreset.COMBO -> R.drawable.ic_ss_twofa
        SheetPreset.PAGE -> R.drawable.ic_ss_page
    }

    // Exact app theme tokens (KiloProxyTheme monochrome); the grid itself
    // is the shared SheetGrid component, so no cell colors live here.
    private fun isDark(): Boolean = ThemeMode.isDarkTheme(context)
    private fun surfaceColor(): Int = if (isDark()) Color.rgb(0x0A, 0x0A, 0x0A) else Color.WHITE
    private fun surfaceVariantColor(): Int = if (isDark()) Color.rgb(0x1A, 0x1A, 0x1A) else Color.rgb(0xF5, 0xF5, 0xF5)
    private fun gridLineColor(): Int = if (isDark()) Color.rgb(0x22, 0x22, 0x22) else Color.rgb(0xEE, 0xEE, 0xEE)
    private fun textColor(): Int = if (isDark()) Color.WHITE else Color.BLACK
    private fun headerTextColor(): Int = if (isDark()) Color.rgb(0xAA, 0xAA, 0xAA) else Color.rgb(0x55, 0x55, 0x55)
    private fun primaryColor(): Int = if (isDark()) Color.WHITE else Color.BLACK
    private fun onPrimaryColor(): Int = if (isDark()) Color.BLACK else Color.WHITE

    private fun pillDrawable(fill: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dpF(6f)
        setColor(fill)
    }

    /** 38% alpha fill — the in-app disabled-check pill strength. */
    private fun dimColor(color: Int): Int = (color and 0x00FFFFFF) or (97 shl 24)

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

/**
 * Attach the view-tree owners by reflection: the classes live in
 * lifecycle-runtime / savedstate, which the overlay compile classpath does
 * not expose, but compose-ui guarantees them at runtime (its own
 * ComposeView resolves them when creating the composition).
 */
private fun attachTreeOwners(view: View, owner: OverlayLifecycleOwner) {
    try {
        Class.forName("androidx.lifecycle.ViewTreeLifecycleOwner")
            .getMethod("set", View::class.java, LifecycleOwner::class.java)
            .invoke(null, view, owner)
        Class.forName("androidx.lifecycle.ViewTreeViewModelStoreOwner")
            .getMethod("set", View::class.java, ViewModelStoreOwner::class.java)
            .invoke(null, view, owner)
        Class.forName("androidx.savedstate.ViewTreeSavedStateRegistryOwner")
            .getMethod("set", View::class.java, SavedStateRegistryOwner::class.java)
            .invoke(null, view, owner)
    } catch (_: Exception) {
    }
}

/**
 * Manual lifecycle for the overlay window: a plain Service has no
 * LifecycleOwner, and ComposeView needs all three view-tree owners
 * (Lifecycle, ViewModelStore, SavedStateRegistry for rememberSaveable).
 * Created + resumed on show, torn down on hide.
 */private class OverlayLifecycleOwner :
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val vmStore = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = vmStore
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    fun create() {
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    fun resume() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun pause() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    }

    fun stop() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    }

    fun destroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        vmStore.clear()
    }
}
