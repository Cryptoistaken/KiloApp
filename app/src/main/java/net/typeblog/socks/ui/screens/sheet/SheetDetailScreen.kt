package net.typeblog.socks.ui.screens.sheet

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.typeblog.socks.ui.components.SsBanner
import net.typeblog.socks.ui.components.SsBannerStatus
import net.typeblog.socks.util.sheet.CopiedGrid
import net.typeblog.socks.util.sheet.MAX_GRID_ROWS
import net.typeblog.socks.util.sheet.SheetStore

// Presentation helpers live in SheetDetailOverlays.kt.
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SheetDetailScreen(
    fileId: String,
    archived: Boolean = false,
    onBack: () -> Unit = {},
    onRestoreArchived: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val appCtx = remember(context) { context.applicationContext }
    val store = remember(appCtx) { SheetStore.get(appCtx) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current

    val openFile by store.openFile.collectAsStateWithLifecycle()
    val rows by store.openRows.collectAsStateWithLifecycle()
    val styles by store.openStyles.collectAsStateWithLifecycle()
    val hidden by store.openHidden.collectAsStateWithLifecycle()
    val checking by store.checking.collectAsStateWithLifecycle()
    val canUndo by store.canUndo.collectAsStateWithLifecycle()
    val canRedo by store.canRedo.collectAsStateWithLifecycle()

    DisposableEffect(fileId) {
        val job = scope.launch(Dispatchers.IO) { store.open(fileId) }
        onDispose {
            job.cancel()
            store.closeFile(fileId)
        }
    }

    val prefs = remember(appCtx) { PreferenceManager.getDefaultSharedPreferences(appCtx) }
    var autoCheck by rememberSaveable { mutableStateOf(prefs.getBoolean("ss_autoCheck", true)) }
    var simpleCheck by rememberSaveable { mutableStateOf(prefs.getBoolean("ss_pageSimple", false)) }
    var advancedCheck by rememberSaveable { mutableStateOf(prefs.getBoolean("ss_pageAdvanced", false)) }

    fun persistCheck(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    val readOnly = archived || openFile?.archived == true
    val columns = openFile?.preset?.columns ?: emptyList()
    val visibleCols = remember(columns, hidden) { columns.filter { !hidden.contains(it.key) } }

    val crossDups by store.openCrossDups.collectAsStateWithLifecycle()
    val openChecks by store.openChecks.collectAsStateWithLifecycle()
    val openCheckReqs by store.openCheckReqs.collectAsStateWithLifecycle()
    val gridClip by store.copiedGrid.collectAsStateWithLifecycle()

    var selectedCell by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var draft by remember { mutableStateOf("") }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedItems by remember { mutableStateOf(setOf<Pair<Int, String>>()) }
    var lastTapCell by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var lastTapTime by remember { mutableStateOf(0L) }
    // Slow second tap on the same cell shows the Cut/Copy/Paste menu.
    var menuCell by remember { mutableStateOf<Pair<Int, String>?>(null) }
    // Dot popup: anchored under the dot like the mock small popup, tap
    // the open dot again to close. Expand swaps to the wide dialog.
    var dotRowIdx by remember { mutableStateOf<Int?>(null) }
    var dotWide by remember { mutableStateOf(false) }
    var dotTab by remember { mutableStateOf(0) }
    var dotDups by remember { mutableStateOf<List<net.typeblog.socks.util.sheet.DupSource>>(emptyList()) }
    LaunchedEffect(fileId, dotRowIdx) {
        dotTab = 0
        dotWide = false
        val ri = dotRowIdx
        dotDups = emptyList()
        if (ri != null) {
            val r = rows.firstOrNull { it.rowIdx == ri }
            if (r != null) {
                dotDups = withContext(Dispatchers.IO) {
                    try {
                        net.typeblog.socks.util.sheet.SheetDb(appCtx).dupSources(fileId, r)
                    } catch (e: Exception) {
                        emptyList()
                    }
                }
            }
        }
    }

    var checkMenu by remember { mutableStateOf(false) }
    var overflowMenu by remember { mutableStateOf(false) }
    var filePopupOpen by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var picker by remember { mutableStateOf<String?>(null) }
    var confirmClearSelection by remember { mutableStateOf(false) }
    var confirmDeleteDead by remember { mutableStateOf(false) }
    var confirmCompact by remember { mutableStateOf(false) }
    var downloadName by remember { mutableStateOf("") }

    fun io(block: suspend () -> Unit) {
        scope.launch { withContext(Dispatchers.IO) { block() } }
    }

    val fileReady = openFile?.id == fileId
    if (!fileReady) {
        // Loading gate: stale or empty flows must never render as a file.
        // A skeleton stands in so the open never flashes hidden columns,
        // stray text, or a bare spinner.
        SheetSkeleton()
        return
    }

    // Row count + auto-grow: when the user scrolls within 6 rows of the end,
    // append 10 more (scroll-gated so idle rest adds nothing).
    val gridState = rememberLazyListState()
    // The floating cell bar anchors to the tapped cell: dismiss it on scroll
    // so it never floats over the wrong row. Same for the anchored dot card.
    LaunchedEffect(gridState.isScrollInProgress) {
        if (gridState.isScrollInProgress && menuCell != null) menuCell = null
        if (gridState.isScrollInProgress && dotRowIdx != null && !dotWide) dotRowIdx = null
    }
    // Multi-cell mode owns the bottom: the single-cell popup never shares
    // the screen with the Copy/Paste/Clear card.
    LaunchedEffect(selectionMode) {
        if (selectionMode && menuCell != null) menuCell = null
    }
    LaunchedEffect(fileId, readOnly) {
        snapshotFlow {
            gridState.isScrollInProgress to
                    (gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1)
        }.collect { (scrolling, lastIdx) ->
            if (readOnly || !scrolling || lastIdx < 0) return@collect
            val total = store.openRows.value.size
            if (lastIdx >= total - 6) io { store.growRows(10) }
        }
    }
    val dataCount = remember(rows, columns) {
        detailDataCount(rows, columns)
    }

    fun doCheck() {
        runSheetDetailCheck(
            scope,
            appCtx,
            store,
            rows,
            columns,
            checking,
            readOnly,
            autoCheck,
            simpleCheck,
            advancedCheck,
            openFile?.preset
        )
    }

    // Website parity (sheetStore maybeAutoCheck): committing a cookie with
    // the UID toggle on runs the whole-file UID check, chaining after a
    // running check instead of overlapping it.
    var pendingAutoCheck by remember { mutableStateOf(false) }

    fun autoCheckArmed() =
        detailAutoCheckArmed(readOnly, openFile?.preset, autoCheck, simpleCheck, advancedCheck)

    fun maybeAutoCheck(colKey: String) {
        if (colKey != "cookies") return
        if (checking) {
            if (autoCheckArmed()) pendingAutoCheck = true
            return
        }
        if (!autoCheckArmed()) return
        doCheck()
    }

    fun commitDraft() {
        commitSheetDetailDraft(scope, appCtx, store, rows, selectedCell, draft) {
            maybeAutoCheck("cookies")
        }
    }

    LaunchedEffect(checking) {
        if (!checking && pendingAutoCheck) {
            pendingAutoCheck = false
            if (autoCheckArmed()) doCheck()
        }
    }

    fun copySelection() {
        copySheetDetailSelection(
            selectedItems = selectedItems,
            visibleCols = visibleCols,
            rows = rows,
            preset = openFile?.preset?.name ?: "",
            store = store,
            clipboard = clipboard,
            appCtx = appCtx,
            selectionMode = selectionMode
        ) {
            selectedItems = emptySet()
            selectionMode = false
        }
    }

    fun doPasteGrid(
        grid: CopiedGrid,
        anchor: Pair<Int, String>,
        area: Set<Pair<Int, String>>?
    ) {
        pasteSheetDetailGrid(
            scope = scope,
            appCtx = appCtx,
            store = store,
            grid = grid,
            anchor = anchor,
            area = area,
            visibleColumns = { visibleCols.map { it.key } },
            shouldClearSelection = { selectionMode },
            onSelectionCleared = {
                selectedItems = emptySet()
                selectionMode = false
            },
            onCookiesPasted = { maybeAutoCheck("cookies") },
            onAnchorPasted = { cell, value ->
                selectedCell = cell
                draft = value
            }
        )
    }

    // Single-cell Cut/Copy/Paste for the tap-again menu (Sheets-style).
    // Copy and Cut also feed the grid clipboard, so a cut/copied cell can
    // be pasted cross-file like a multi-cell copy.
    fun gridOf(ri: Int, ck: String, value: String): CopiedGrid =
        detailGrid(openFile?.preset?.name ?: "", ck, value)

    fun copyCell(ri: Int, ck: String) {
        val v = rows.getOrNull(ri)?.cell(ck) ?: ""
        if (v.isEmpty()) {
            toast(appCtx, "Cell is empty.")
            return
        }
        clipboard.setText(AnnotatedString(v))
        store.copyGrid(gridOf(ri, ck, v))
        selectedCell = Pair(ri, ck)
        draft = v
        toast(appCtx, "Copied.")
    }

    fun cutCell(ri: Int, ck: String) {
        val row = rows.getOrNull(ri) ?: return
        if (row.locked) {
            toast(appCtx, if (row.hold) "On hold. Editing is locked." else "Approved.")
            return
        }
        val v = row.cell(ck)
        if (v.isEmpty()) {
            toast(appCtx, "Cell is empty.")
            return
        }
        store.rejectReason(ri, ck, "")?.let { toast(appCtx, it); return }
        clipboard.setText(AnnotatedString(v))
        store.copyGrid(gridOf(ri, ck, v))
        scope.launch {
            val ok = withContext(Dispatchers.IO) { store.setCell(ri, ck, "") }
            if (ok) {
                selectedCell = Pair(ri, ck)
                draft = ""
                toast(appCtx, "Cut.")
            } else {
                toast(appCtx, "Couldn't save. Please try again.")
            }
        }
    }

    fun pasteInto(ri: Int, ck: String) {
        val clip = gridClip
        if (clip != null) {
            doPasteGrid(clip, Pair(ri, ck), null)
            return
        }
        val pasted = clipboard.getText()?.text ?: ""
        if (pasted.isEmpty()) {
            toast(appCtx, "Clipboard is empty.")
            return
        }
        store.rejectReason(ri, ck, pasted)?.let { toast(appCtx, it); return }
        selectedCell = Pair(ri, ck)
        draft = pasted
        scope.launch {
            val ok = withContext(Dispatchers.IO) { store.setCell(ri, ck, pasted) }
            if (!ok) {
                toast(appCtx, "Couldn't save. Please try again.")
            } else if (ck == "cookies") {
                maybeAutoCheck(ck)
            }
        }
    }

    // Website parity (SheetGrid enterSelectionMode/toggleSelection): header
    // taps select a whole row / column / everything; a long-press on a
    // header re-selects just that row / column fresh.
    fun allCells(): Set<Pair<Int, String>> = detailAllCells(rows, visibleCols)

    fun rowCells(ri: Int): Set<Pair<Int, String>> = detailRowCells(ri, visibleCols)

    fun colCells(ck: String): Set<Pair<Int, String>> = detailColumnCells(rows, ck)

    fun enterMulti(items: Set<Pair<Int, String>>) {
        selectedCell = null
        selectedItems = items
        if (items.isNotEmpty()) selectionMode = true
    }

    fun toggleMulti(items: Set<Pair<Int, String>>) {
        if (items.isEmpty()) return
        val next =
            if (items.all { selectedItems.contains(it) }) selectedItems - items
            else selectedItems + items
        selectedCell = null
        selectedItems = next
        selectionMode = next.isNotEmpty()
    }

    fun deleteDeadWithCount() {
        if (detailCanDeleteDead(appCtx, rows)) confirmDeleteDead = true
    }

    val downloadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    ) { uri ->
        val name = downloadName
        downloadName = ""
        writeSheetDetailDownload(scope, appCtx, uri, name, { openFile }, { rows })
    }

    var detailUploadMode by remember { mutableStateOf("replace") }

    // "Send a copy": same content as Download, dropped in cache and opened
    // in the system share sheet (Telegram, Drive, ...).
    fun shareOpenFile() {
        shareSheetDetailFile(scope, context, appCtx, { openFile }, { rows })
    }

    val detailUploadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            importSheetDetailFile(scope, appCtx, uri, detailUploadMode, { openFile }, store)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        SheetDetailHeader(
            readOnly = readOnly,
            file = openFile,
            rows = rows,
            visibleCols = visibleCols,
            columns = columns,
            selectionMode = selectionMode,
            selectedItems = selectedItems,
            hidden = hidden,
            canUndo = canUndo,
            canRedo = canRedo,
            checking = checking,
            autoCheck = autoCheck,
            simpleCheck = simpleCheck,
            advancedCheck = advancedCheck,
            checkMenu = checkMenu,
            overflowMenu = overflowMenu,
            onBack = onBack,
            onToggleAll = { allCellSet ->
                if (selectedItems.isNotEmpty() && allCellSet.all { selectedItems.contains(it) }) {
                    selectedItems = emptySet()
                    selectionMode = false
                } else {
                    selectedItems = allCellSet
                }
            },
            onCancelSelection = {
                selectedItems = emptySet()
                selectionMode = false
            },
            onRename = {
                renameText = openFile?.name ?: ""
                renameOpen = true
            },
            onUndo = { io { store.undo() } },
            onRedo = { io { store.redo() } },
            onCheck = { doCheck() },
            onCheckMenuChange = { checkMenu = it },
            onToggleAutoCheck = {
                autoCheck = !autoCheck
                persistCheck("ss_autoCheck", autoCheck)
            },
            onToggleSimpleCheck = {
                simpleCheck = !simpleCheck
                persistCheck("ss_pageSimple", simpleCheck)
                if (simpleCheck) {
                    advancedCheck = false
                    persistCheck("ss_pageAdvanced", false)
                }
            },
            onToggleAdvancedCheck = {
                advancedCheck = !advancedCheck
                persistCheck("ss_pageAdvanced", advancedCheck)
                if (advancedCheck) {
                    simpleCheck = false
                    persistCheck("ss_pageSimple", false)
                }
            },
            onOverflowMenuChange = { overflowMenu = it },
            onInspector = { filePopupOpen = true },
            onDownload = {
                sheetDetailDownloadName(appCtx, openFile, rows)?.let { name ->
                    downloadName = name
                    downloadLauncher.launch("$name.xlsx")
                }
            },
            onShare = { shareOpenFile() },
            onUploadReplace = {
                detailUploadMode = "replace"
                detailUploadLauncher.launch("*/*")
            },
            onUploadMerge = {
                detailUploadMode = "merge"
                detailUploadLauncher.launch("*/*")
            },
            onCompact = { confirmCompact = true },
            onDeleteDead = { deleteDeadWithCount() },
            onToggleColumn = { col, visible ->
                val next = hidden.toMutableSet()
                if (visible) next.add(col.key) else next.remove(col.key)
                io { store.setHidden(next) }
            }
        )

        if (readOnly) {
            SheetDetailArchivedBanner(
                onRestore = {
                    restoreSheetDetailFile(scope, appCtx, store, fileId, onRestoreArchived)
                }
            )
        }

        SheetDetailEditor(
            readOnly = readOnly,
            rows = rows,
            visibleCols = visibleCols,
            styles = styles,
            crossDups = crossDups,
            selectedCell = selectedCell,
            selectionMode = selectionMode,
            selectedItems = selectedItems,
            menuCell = menuCell,
            dotRowIdx = dotRowIdx,
            gridState = gridState,
            dataCount = dataCount,
            gridClip = gridClip,
            draft = draft,
            interactions = SheetGridInteractions(
                onCellClick = cellClick@{ row, col ->
                    val selKey = Pair(row.rowIdx, col.key)
                    if (readOnly) {
                        val now = System.currentTimeMillis()
                        if (lastTapCell == selKey && now - lastTapTime < 400) {
                            lastTapCell = null
                            menuCell = null
                            val v = row.cell(col.key)
                            if (v.isNotEmpty()) {
                                clipboard.setText(AnnotatedString(v))
                                toast(appCtx, "Copied.")
                            }
                            return@cellClick
                        }
                        if (lastTapCell == selKey) {
                            lastTapCell = null
                            menuCell = selKey
                            return@cellClick
                        }
                        lastTapCell = selKey
                        lastTapTime = now
                        selectedCell = selKey
                        draft = row.cell(col.key)
                        return@cellClick
                    }
                    if (selectionMode) {
                        selectedItems = if (selectedItems.contains(selKey)) {
                            val next = selectedItems - selKey
                            if (next.isEmpty()) selectionMode = false
                            next
                        } else {
                            selectedItems + selKey
                        }
                        return@cellClick
                    }
                    if (row.locked) {
                        toast(appCtx, if (row.hold) "On hold. Editing is locked." else "Approved.")
                        return@cellClick
                    }
                    val now = System.currentTimeMillis()
                    if (lastTapCell == selKey && now - lastTapTime < 400) {
                        lastTapCell = null
                        menuCell = null
                        val v = row.cell(col.key)
                        if (v.isNotEmpty()) copyCell(selKey.first, selKey.second)
                        else pasteInto(selKey.first, selKey.second)
                        return@cellClick
                    }
                    if (lastTapCell == selKey) {
                        lastTapCell = null
                        menuCell = selKey
                        return@cellClick
                    }
                    if (selectedCell != null && selectedCell != selKey) commitDraft()
                    lastTapCell = selKey
                    lastTapTime = now
                    selectedCell = selKey
                    draft = row.cell(col.key)
                },
                onCellLongClick = cellLongClick@{ row, col ->
                    val selKey = Pair(row.rowIdx, col.key)
                    if (!readOnly && row.locked) {
                        toast(appCtx, if (row.hold) "On hold. Editing is locked." else "Approved.")
                        return@cellLongClick
                    }
                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    selectionMode = true
                    selectedCell = null
                    selectedItems = selectedItems + selKey
                },
                onRowRailClick = { ri -> toggleMulti(rowCells(ri)) },
                onRowRailLongClick = { ri ->
                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    enterMulti(rowCells(ri))
                },
                onCornerClick = { enterMulti(allCells()) },
                onCornerLongClick = {
                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    enterMulti(allCells())
                },
                onHeaderClick = { ck -> toggleMulti(colCells(ck)) },
                onHeaderLongClick = { ck ->
                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    enterMulti(colCells(ck))
                },
                onDotClick = dotClick@{ row ->
                    if (dotRowIdx == row.rowIdx && !dotWide) {
                        dotRowIdx = null
                    } else {
                        val v = row.twofakey
                        if (v.isEmpty()) {
                            toast(appCtx, "No 2FA to copy.")
                        } else {
                            clipboard.setText(AnnotatedString(v))
                            store.copyGrid(gridOf(row.rowIdx, "twofakey", v))
                            toast(appCtx, "Copied.")
                        }
                    }
                },
                onDotLongClick = { row ->
                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    dotRowIdx = row.rowIdx
                }
            ),
            cellPopup = { ri, ck ->
                CellPopBar(
                    readOnly = readOnly,
                    onCut = { menuCell = null; cutCell(ri, ck) },
                    onCopy = { menuCell = null; copyCell(ri, ck) },
                    onPaste = { menuCell = null; pasteInto(ri, ck) },
                    onDismiss = { menuCell = null }
                )
            },
            dotPopup = { row ->
                if (dotRowIdx == row.rowIdx && !dotWide) {
                    SheetDotAnchor(
                        row = row,
                        check = openChecks[row.rowIdx],
                        reqs = openCheckReqs[row.rowIdx] ?: emptyList(),
                        dupSources = dotDups,
                        fileName = openFile?.name ?: "",
                        presetLabel = openFile?.preset?.name ?: "",
                        checking = checking,
                        tab = dotTab,
                        onTabChange = { dotTab = it },
                        onToggleWide = { dotWide = true },
                        onDismiss = { dotRowIdx = null }
                    )
                }
            },
            footer = {
                if (!readOnly) {
                    val atCap = rows.size >= MAX_GRID_ROWS
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .then(
                                if (atCap) Modifier
                                else Modifier.combinedClickable(
                                    onClick = { addSheetDetailRow(scope, appCtx, store) }
                                )
                            )
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (atCap) {
                                if (dataCount > 0) "$dataCount rows" else "No rows yet"
                            } else {
                                "Add row" + if (dataCount > 0) ", $dataCount rows" else ""
                            },
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            onCopySelection = { copySelection() },
            onPasteSelection = { clip -> doPasteGrid(clip, Pair(-1, ""), selectedItems) },
            onClearSelection = { confirmClearSelection = true },
            onDraftChange = { draft = it },
            onCommitDraft = { commitDraft() }
        )

    }

    if (renameOpen && !readOnly) {
        fun commitRename() {
            val name = renameText.trim()
            if (name.isEmpty()) {
                toast(appCtx, "Enter a file name.")
                return
            }
            val id = openFile?.id ?: fileId
            renameOpen = false
            renameText = ""
            renameSheetDetailFile(scope, appCtx, store, id, name)
        }
        SheetRenameDialog(
            value = renameText,
            onValueChange = { renameText = it },
            onDismiss = {
                renameOpen = false
                renameText = ""
            },
            onConfirm = { commitRename() }
        )
    }

    if (filePopupOpen) {
        val f = openFile
        if (f == null) {
            filePopupOpen = false
        } else {
            SheetFileInspector(
                appCtx = appCtx,
                file = f,
                fileId = fileId,
                filePopupOpen = filePopupOpen,
                rows = rows,
                checks = openChecks,
                reqs = openCheckReqs,
                checking = checking,
                onDismiss = { filePopupOpen = false }
            )
        }
    }

    if (confirmClearSelection) {
        SheetConfirm(
            onDismiss = { confirmClearSelection = false },
            message = "Clear selected cells? This can be undone.",
            actionText = "Clear",
            onConfirm = {
                val items = selectedItems
                confirmClearSelection = false
                io { store.clearCells(items) }
                val sel = selectedCell
                if (sel != null && items.contains(sel)) draft = ""
                // Action completed: leave multi-cell mode.
                selectedItems = emptySet()
                selectionMode = false
            }
        )
    }

    if (confirmDeleteDead) {
        val n = rows.count { it.status == "bad" || it.dead }
        SheetConfirm(
            onDismiss = { confirmDeleteDead = false },
            message = "Delete $n dead row" + if (n == 1) "?" else "s?",
            actionText = "Delete",
            onConfirm = {
                confirmDeleteDead = false
                deleteSheetDetailDeadRows(scope, appCtx, store)
            }
        )
    }

    if (confirmCompact) {
        SheetConfirm(
            onDismiss = { confirmCompact = false },
            message = "Remove empty rows between used rows to compact the sheet?",
            actionText = "Compact",
            onConfirm = {
                confirmCompact = false
                io { store.compactRows() }
            }
        )
    }

    val dotIdx = dotRowIdx
    if (dotIdx != null && dotWide) {
        // Expanded dot dialog from recorded check data. The anchored card
        // above handles the narrow state; expand swaps here, dock returns.
        val dotRow = rows.firstOrNull { it.rowIdx == dotIdx }
        if (dotRow == null) {
            dotRowIdx = null
        } else {
            SheetWideDotDialog(
                row = dotRow,
                check = openChecks[dotIdx],
                reqs = openCheckReqs[dotIdx] ?: emptyList(),
                dupSources = dotDups,
                fileName = openFile?.name ?: "",
                presetLabel = openFile?.preset?.name ?: "",
                checking = checking,
                tab = dotTab,
                onTabChange = { dotTab = it },
                onDock = { dotWide = false },
                onDismiss = { dotRowIdx = null }
            )
        }
    }

    val pick = picker
    val selPick = selectedCell
    if (pick != null && selPick != null) {
        val cur = styles[styleKey(selPick.first, selPick.second)]
        SheetStylePicker(
            picker = pick,
            current = cur,
            onSelect = { style ->
                io { store.setStyle(selPick.first, selPick.second, style) }
                picker = null
            },
            onDismiss = { picker = null }
        )
    }
}
