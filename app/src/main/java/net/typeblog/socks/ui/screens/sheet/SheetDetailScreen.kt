package net.typeblog.socks.ui.screens.sheet

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.typeblog.socks.R
import net.typeblog.socks.util.sheet.CellStyle
import net.typeblog.socks.util.sheet.SheetCsv
import net.typeblog.socks.util.sheet.SheetStore

private val PALETTE = listOf(
    "#000000", "#434343", "#666666", "#999999", "#B7B7B7",
    "#CCCCCC", "#D9D9D9", "#ee0000", "#ff6d00", "#fbbc04",
    "#16a34a", "#00acc1", "#0070f3", "#6366f1", "#795548"
)

private fun parseHexColor(hex: String?): Color? {
    if (hex == null) return null
    var h = hex.trim().removePrefix("#")
    if (h.length == 3) {
        h = h.map { "$it$it" }.joinToString("")
    }
    if (h.length != 6) return null
    return try {
        Color(
            red = h.substring(0, 2).toInt(16),
            green = h.substring(2, 4).toInt(16),
            blue = h.substring(4, 6).toInt(16)
        )
    } catch (e: Exception) {
        null
    }
}

private fun styleKey(rowIdx: Int, colKey: String): String = "$rowIdx:$colKey"

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

    val openFile by store.openFile.collectAsState()
    val rows by store.openRows.collectAsState()
    val styles by store.openStyles.collectAsState()
    val hidden by store.openHidden.collectAsState()
    val checking by store.checking.collectAsState()
    val canUndo by store.canUndo.collectAsState()
    val canRedo by store.canRedo.collectAsState()

    DisposableEffect(fileId) {
        val job = scope.launch(Dispatchers.IO) { store.open(fileId) }
        onDispose {
            job.cancel()
            store.closeFile()
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

    val dupRows = remember(rows) { store.dupRows() }

    var selectedCell by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var draft by remember { mutableStateOf("") }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedItems by remember { mutableStateOf(setOf<Pair<Int, String>>()) }

    var checkMenu by remember { mutableStateOf(false) }
    var overflowMenu by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var picker by remember { mutableStateOf<String?>(null) }
    var confirmClearSelection by remember { mutableStateOf(false) }
    var confirmDeleteDead by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf(false) }
    var downloadName by remember { mutableStateOf("") }

    fun io(block: suspend () -> Unit) {
        scope.launch { withContext(Dispatchers.IO) { block() } }
    }

    fun doCheck() {
        if (checking) return
        if (!readOnly && dupRows.isNotEmpty()) {
            toast(appCtx, "Remove duplicates first.")
            return
        }
        store.runCheck { valid, dead ->
            scope.launch {
                toast(appCtx, "Check done: $valid valid, $dead dead.")
            }
        }
    }

    fun copyAllData() {
        if (columns.isEmpty() || rows.none { it.isData(columns) }) {
            toast(appCtx, "Add content first.")
            return
        }
        clipboard.setText(AnnotatedString(SheetCsv.tsv(columns, rows)))
        toast(appCtx, "Copied.")
    }

    fun commitDraft() {
        val sel = selectedCell ?: return
        val (ri, ck) = sel
        val row = rows.getOrNull(ri) ?: return
        if (row.locked) {
            toast(appCtx, if (row.hold) "On hold. Editing is locked." else "Approved.")
            return
        }
        val value = draft
        if (row.cell(ck) == value) return
        if ((ck == "uid" || ck == "cookies") && value.isNotEmpty()) {
            val dup = rows.any { it.rowIdx != ri && it.cell(ck) == value }
            if (dup) {
                toast(appCtx, "Duplicate. Use a unique value.")
                return
            }
        }
        scope.launch {
            val ok = withContext(Dispatchers.IO) { store.setCell(ri, ck, value) }
            if (!ok) toast(appCtx, "Duplicate value. Please use a unique value.")
        }
    }

    fun copySelection() {
        if (selectedItems.isEmpty()) return
        val order = visibleCols.map { it.key }
        val byRow = selectedItems.groupBy { it.first }.toSortedMap()
        val lines = byRow.entries.map { (ri, cells) ->
            val keys = cells.map { it.second }.sortedBy { order.indexOf(it).let { i -> if (i < 0) 999 else i } }
            val r = rows.getOrNull(ri)
            keys.map { k -> r?.cell(k) ?: "" }.joinToString("\t")
        }
        clipboard.setText(AnnotatedString(lines.joinToString("\n")))
        toast(appCtx, "Copied.")
    }

    fun deleteDeadWithCount(): Int {
        val n = rows.count { it.status == "bad" || it.dead }
        if (n == 0) {
            toast(appCtx, "No inactive rows.")
            return 0
        }
        confirmDeleteDead = true
        return n
    }

    val downloadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri: Uri? ->
        val name = downloadName
        downloadName = ""
        if (uri != null) {
            scope.launch {
                val msg = withContext(Dispatchers.IO) {
                    try {
                        val f = openFile
                        val cols = f?.preset?.columns ?: emptyList()
                        val data = rows
                        if (cols.isEmpty() || data.none { it.isData(cols) }) {
                            return@withContext "Add content first."
                        }
                        appCtx.contentResolver.openOutputStream(uri)?.use { out ->
                            out.write(SheetCsv.build(cols, data).toByteArray(Charsets.UTF_8))
                        } ?: return@withContext "Download failed."
                        "Downloaded $name.csv"
                    } catch (e: Exception) {
                        "Download failed."
                    }
                }
                toast(appCtx, msg)
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Top row.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("Back") }
            val fname = openFile?.name ?: "Sheet"
            if (readOnly) {
                Text(
                    text = fname,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp)
                )
            } else {
                TextButton(
                    onClick = {
                        renameText = openFile?.name ?: ""
                        renameOpen = true
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = fname,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (!readOnly) {
                IconButton(onClick = { io { store.undo() } }, enabled = canUndo) {
                    Icon(
                        painter = painterResource(R.drawable.ic_ss_undo),
                        contentDescription = "Undo"
                    )
                }
                IconButton(onClick = { io { store.redo() } }, enabled = canRedo) {
                    Icon(
                        painter = painterResource(R.drawable.ic_ss_redo),
                        contentDescription = "Redo"
                    )
                }
            }
            // Check split button.
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = { doCheck() },
                    enabled = !checking
                ) {
                    if (checking) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Checking")
                    } else {
                        Text("Check")
                    }
                }
                if (!readOnly && !checking) {
                    Box {
                        IconButton(onClick = { checkMenu = true }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_ss_check_arrow),
                                contentDescription = "More check options"
                            )
                        }
                        DropdownMenu(
                            expanded = checkMenu,
                            onDismissRequest = { checkMenu = false }
                        ) {
                            CheckSwitchRow(
                                label = "UID check",
                                checked = autoCheck,
                                onToggle = {
                                    autoCheck = !autoCheck
                                    persistCheck("ss_autoCheck", autoCheck)
                                }
                            )
                            CheckSwitchRow(
                                label = "Simple check",
                                checked = simpleCheck,
                                onToggle = {
                                    simpleCheck = !simpleCheck
                                    persistCheck("ss_pageSimple", simpleCheck)
                                    if (simpleCheck) {
                                        advancedCheck = false
                                        persistCheck("ss_pageAdvanced", false)
                                    }
                                }
                            )
                            CheckSwitchRow(
                                label = "Advanced check",
                                checked = advancedCheck,
                                onToggle = {
                                    advancedCheck = !advancedCheck
                                    persistCheck("ss_pageAdvanced", advancedCheck)
                                    if (advancedCheck) {
                                        simpleCheck = false
                                        persistCheck("ss_pageSimple", false)
                                    }
                                }
                            )
                        }
                    }
                }
            }
            Box {
                IconButton(onClick = { overflowMenu = true }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_ss_more),
                        contentDescription = "More actions"
                    )
                }
                DropdownMenu(
                    expanded = overflowMenu,
                    onDismissRequest = { overflowMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Copy all data") },
                        onClick = {
                            overflowMenu = false
                            copyAllData()
                        }
                    )
                    if (!readOnly) {
                        DropdownMenuItem(
                            text = { Text("Download xlsx") },
                            onClick = {
                                overflowMenu = false
                                val f = openFile
                                if (f == null) {
                                    toast(appCtx, "Add content first.")
                                    return@DropdownMenuItem
                                }
                                if (rows.none { it.isData(f.preset.columns) }) {
                                    toast(appCtx, "Add content first.")
                                    return@DropdownMenuItem
                                }
                                val nm = sanitizeFileName(f.name)
                                downloadName = nm
                                downloadLauncher.launch("$nm.csv")
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Upload xlsx") },
                            onClick = {
                                overflowMenu = false
                                toast(appCtx, "Upload not available yet.")
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Merge") },
                            onClick = {
                                overflowMenu = false
                                toast(appCtx, "Merge not available yet.")
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Compact") },
                            onClick = {
                                overflowMenu = false
                                io { store.compactRows() }
                                toast(appCtx, "Sheet compacted.")
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete inactive") },
                            onClick = {
                                overflowMenu = false
                                deleteDeadWithCount()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Restore last save") },
                            onClick = {
                                overflowMenu = false
                                confirmRestore = true
                            }
                        )
                    }
                    for (col in columns) {
                        val visible = !hidden.contains(col.key)
                        DropdownMenuItem(
                            text = { Text(col.label) },
                            trailingIcon = {
                                Switch(
                                    checked = visible,
                                    onCheckedChange = null
                                )
                            },
                            onClick = {
                                val next = hidden.toMutableSet()
                                if (visible) next.add(col.key) else next.remove(col.key)
                                io { store.setHidden(next) }
                            }
                        )
                    }
                }
            }
        }

        if (readOnly) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Archived file. View only.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(onClick = { onRestoreArchived(fileId) }) {
                        Text("Restore")
                    }
                }
            }
        }

        // Grid.
        if (visibleCols.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "All columns hidden. Use the menu.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
            ) {
                stickyHeader {
                    Row(
                        modifier = Modifier.background(MaterialTheme.colorScheme.surface),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .width(44.dp)
                                .height(36.dp),
                            contentAlignment = Alignment.Center
                        ) {}
                        for (col in visibleCols) {
                            Box(
                                modifier = Modifier
                                    .width(140.dp)
                                    .height(36.dp)
                                    .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                                    .padding(horizontal = 8.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Text(
                                    text = col.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Box(
                            modifier = Modifier
                                .width(36.dp)
                                .height(36.dp),
                            contentAlignment = Alignment.Center
                        ) {}
                    }
                }
                items(rows, key = { it.rowIdx }) { row ->
                    val isDup = dupRows.contains(row.rowIdx)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .width(44.dp)
                                .height(44.dp)
                                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = (row.rowIdx + 1).toString(),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        for (col in visibleCols) {
                            val key = styleKey(row.rowIdx, col.key)
                            val st = styles[key]
                            val selKey = Pair(row.rowIdx, col.key)
                            val isActive = selectedCell == selKey && !selectionMode
                            val isMulti = selectedItems.contains(selKey)
                            val bg = parseHexColor(st?.bg)
                            val fg = parseHexColor(st?.color)
                            Box(
                                modifier = Modifier
                                    .width(140.dp)
                                    .height(44.dp)
                                    .border(
                                        width = if (isActive || isMulti) 2.dp else 0.5.dp,
                                        color = if (isActive || isMulti) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.outlineVariant
                                        }
                                    )
                                    .background(bg ?: Color.Transparent)
                                    .combinedClickable(
                                        onClick = {
                                            if (readOnly) {
                                                val v = row.cell(col.key)
                                                if (v.isEmpty()) {
                                                    toast(appCtx, "Cell is empty.")
                                                } else {
                                                    clipboard.setText(AnnotatedString(v))
                                                    toast(appCtx, "Copied.")
                                                }
                                                return@combinedClickable
                                            }
                                            if (selectionMode) {
                                                selectedItems = if (selectedItems.contains(selKey)) {
                                                    val next = selectedItems - selKey
                                                    if (next.isEmpty()) selectionMode = false
                                                    next
                                                } else {
                                                    selectedItems + selKey
                                                }
                                                return@combinedClickable
                                            }
                                            if (row.locked) {
                                                toast(
                                                    appCtx,
                                                    if (row.hold) "On hold. Editing is locked." else "Approved."
                                                )
                                                return@combinedClickable
                                            }
                                            selectedCell = selKey
                                            draft = row.cell(col.key)
                                        },
                                        onLongClick = {
                                            if (!readOnly && row.locked) {
                                                toast(
                                                    appCtx,
                                                    if (row.hold) "On hold. Editing is locked." else "Approved."
                                                )
                                                return@combinedClickable
                                            }
                                            selectionMode = true
                                            selectedCell = null
                                            selectedItems = selectedItems + selKey
                                        }
                                    )
                                    .padding(horizontal = 8.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                val showDraft = isActive && !readOnly
                                Text(
                                    text = if (showDraft) draft else row.cell(col.key),
                                    fontSize = 12.sp,
                                    fontWeight = if (st?.bold == true) FontWeight.Bold else FontWeight.Normal,
                                    color = fg ?: MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Box(
                            modifier = Modifier
                                .width(36.dp)
                                .height(44.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            StatusDot(
                                status = row.status,
                                dead = row.dead,
                                isDup = isDup
                            )
                        }
                    }
                }
                item {
                    if (!readOnly) {
                        TextButton(
                            onClick = { io { store.addRow() } },
                            modifier = Modifier
                                .width(44.dp + 140.dp * visibleCols.size.coerceAtLeast(1) + 36.dp)
                                .padding(vertical = 4.dp)
                        ) {
                            Text("Add row")
                        }
                    }
                }
            }
        }

        // Selection bar.
        if (selectionMode && selectedItems.isNotEmpty()) {
            Surface(
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                    Text(
                        text = selectedItems.size.toString() + " selected",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { copySelection() }) { Text("Copy") }
                        if (!readOnly) {
                            TextButton(
                                onClick = { confirmClearSelection = true }
                            ) {
                                Text("Clear", color = MaterialTheme.colorScheme.error)
                            }
                        }
                        TextButton(
                            onClick = {
                                val all = mutableSetOf<Pair<Int, String>>()
                                for (r in rows) {
                                    for (c in visibleCols) all.add(Pair(r.rowIdx, c.key))
                                }
                                selectedItems = all
                            }
                        ) { Text("Select all") }
                        TextButton(
                            onClick = {
                                selectedItems = emptySet()
                                selectionMode = false
                            }
                        ) { Text("Unselect all") }
                    }
                }
            }
        }

        // Quick edit bar.
        if (!readOnly && !selectionMode && selectedCell != null) {
            val sel = selectedCell
            if (sel != null) {
                val st = styles[styleKey(sel.first, sel.second)]
                val bold = st?.bold == true
                Surface(
                    tonalElevation = 3.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = draft,
                                onValueChange = { draft = it },
                                label = { Text("Formula") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { commitDraft() }),
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            TextButton(onClick = { commitDraft() }) { Text("Done") }
                        }
                        Spacer(modifier = Modifier.size(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            TextButton(
                                onClick = {
                                    val cur = st
                                    val next = (cur ?: CellStyle()).copy(bold = !(cur?.bold ?: false))
                                    val clean = if (next.bg == null && next.color == null && !next.bold) null else next
                                    io { store.setStyle(sel.first, sel.second, clean) }
                                }
                            ) {
                                Text(
                                    "B",
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 18.sp,
                                    color = if (bold) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                            }
                            IconButton(onClick = { picker = "text" }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_ss_textcolor),
                                    contentDescription = "Text color"
                                )
                            }
                            IconButton(onClick = { picker = "fill" }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_ss_fill),
                                    contentDescription = "Cell fill"
                                )
                            }
                            IconButton(
                                onClick = {
                                    val v = rows.getOrNull(sel.first)?.cell(sel.second) ?: ""
                                    if (v.isEmpty()) {
                                        toast(appCtx, "Cell is empty.")
                                    } else {
                                        clipboard.setText(AnnotatedString(v))
                                        toast(appCtx, "Copied.")
                                    }
                                }
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_ss_copy),
                                    contentDescription = "Copy cell"
                                )
                            }
                            IconButton(
                                onClick = {
                                    val pasted = clipboard.getText()?.text ?: ""
                                    if (pasted.isEmpty()) {
                                        toast(appCtx, "Clipboard is empty.")
                                    } else {
                                        draft = pasted
                                    }
                                }
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_ss_paste),
                                    contentDescription = "Paste cell"
                                )
                            }
                            IconButton(
                                onClick = {
                                    draft = ""
                                    io { store.clearCells(setOf(sel)) }
                                }
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_ss_eraser),
                                    contentDescription = "Clear cell"
                                )
                            }
                            IconButton(
                                onClick = {
                                    io { store.compactRows() }
                                    toast(appCtx, "Sheet compacted.")
                                }
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_ss_compact),
                                    contentDescription = "Compact rows"
                                )
                            }
                            IconButton(onClick = { deleteDeadWithCount() }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_ss_trash),
                                    contentDescription = "Delete inactive rows"
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (renameOpen && !readOnly) {
        AlertDialog(
            onDismissRequest = {
                renameOpen = false
                renameText = ""
            },
            title = { Text("Rename file") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("File name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = renameText.trim()
                        if (name.isEmpty()) {
                            toast(appCtx, "Enter a file name.")
                            return@TextButton
                        }
                        val id = openFile?.id ?: fileId
                        renameOpen = false
                        renameText = ""
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { store.renameFile(id, name) }
                            toast(appCtx, if (ok) "File renamed." else "Unable to rename. Please try again.")
                        }
                    }
                ) { Text("Rename") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        renameOpen = false
                        renameText = ""
                    }
                ) { Text("Cancel") }
            }
        )
    }

    if (confirmClearSelection) {
        AlertDialog(
            onDismissRequest = { confirmClearSelection = false },
            title = { Text("Clear selected cells?") },
            text = { Text("This can be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val items = selectedItems
                        confirmClearSelection = false
                        io { store.clearCells(items) }
                        val sel = selectedCell
                        if (sel != null && items.contains(sel)) draft = ""
                    }
                ) { Text("Clear", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearSelection = false }) { Text("Cancel") }
            }
        )
    }

    if (confirmDeleteDead) {
        val n = rows.count { it.status == "bad" || it.dead }
        AlertDialog(
            onDismissRequest = { confirmDeleteDead = false },
            title = { Text("Delete $n inactive row" + if (n == 1) "?" else "s?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDeleteDead = false
                        scope.launch {
                            val removed = withContext(Dispatchers.IO) { store.deleteDeadRows() }
                            toast(
                                appCtx,
                                if (removed > 0) "Deleted $removed inactive row" + if (removed == 1) "." else "s."
                                else "No inactive rows to delete."
                            )
                        }
                    }
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteDead = false }) { Text("Cancel") }
            }
        )
    }

    if (confirmRestore) {
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            title = { Text("Restore the last saved version?") },
            text = { Text("Replaces unsaved changes. Current state stays in Undo.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRestore = false
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { store.restoreSnapshot() }
                            toast(
                                appCtx,
                                if (ok) "Restored. Changes kept in Undo."
                                else "No earlier save to restore yet."
                            )
                        }
                    }
                ) { Text("Restore") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRestore = false }) { Text("Cancel") }
            }
        )
    }

    val pick = picker
    val selPick = selectedCell
    if (pick != null && selPick != null) {
        val cur = styles[styleKey(selPick.first, selPick.second)]
        AlertDialog(
            onDismissRequest = { picker = null },
            title = { Text(if (pick == "text") "Text color" else "Cell fill") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (pick == "fill") {
                        TextButton(
                            onClick = {
                                val next = (cur ?: CellStyle()).copy(bg = null)
                                val clean = if (next.bg == null && next.color == null && !next.bold) null else next
                                io { store.setStyle(selPick.first, selPick.second, clean) }
                                picker = null
                            }
                        ) { Text("No fill") }
                    }
                    for (chunk in PALETTE.chunked(5)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            for (hex in chunk) {
                                val c = parseHexColor(hex) ?: Color.Black
                                val active = if (pick == "text") cur?.color == hex else cur?.bg == hex
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .background(c)
                                        .border(
                                            width = if (active) 3.dp else 1.dp,
                                            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                        )
                                        .combinedClickable(
                                            onClick = {
                                                val base = cur ?: CellStyle()
                                                val next = if (pick == "text") base.copy(color = hex) else base.copy(bg = hex)
                                                io { store.setStyle(selPick.first, selPick.second, next) }
                                                picker = null
                                            }
                                        )
                                )
                            }
                        }
                        Spacer(modifier = Modifier.size(8.dp))
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { picker = null }) { Text("Close") }
            }
        )
    }
}

@Composable
private fun CheckSwitchRow(
    label: String,
    checked: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Switch(checked = checked, onCheckedChange = { onToggle() })
    }
}
