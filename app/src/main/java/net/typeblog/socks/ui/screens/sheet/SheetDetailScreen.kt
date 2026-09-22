package net.typeblog.socks.ui.screens.sheet

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.clip
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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

private fun colWidthDp(colKey: String): androidx.compose.ui.unit.Dp = when (colKey) {
    "cookies" -> 180.dp
    "twofakey" -> 140.dp
    "uid" -> 100.dp
    else -> 140.dp
}

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
    var lastTapCell by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var lastTapTime by remember { mutableStateOf(0L) }
    var pendingTapJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    var checkMenu by remember { mutableStateOf(false) }
    var overflowMenu by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var picker by remember { mutableStateOf<String?>(null) }
    var confirmClearSelection by remember { mutableStateOf(false) }
    var confirmDeleteDead by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf(false) }
    var confirmCompact by remember { mutableStateOf(false) }
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

    var detailUploadMode by remember { mutableStateOf("replace") }
    val detailUploadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val mode = detailUploadMode
        scope.launch {
            val msg = withContext(Dispatchers.IO) {
                try {
                    val draft = parseUpload(appCtx, uri) ?: return@withContext "Import failed."
                    val f = openFile ?: return@withContext "Import failed."
                    val cols = f.preset.columns
                    val db = net.typeblog.socks.util.sheet.SheetDb(appCtx)
                    if (mode == "merge") {
                        val existing = db.loadRows(f.id)
                        val dataExisting = existing.filter { it.isData(cols) }
                        val incoming = draft.rows.mapIndexed { i, r ->
                            net.typeblog.socks.util.sheet.SheetRow(
                                rowIdx = dataExisting.size + i,
                                cookies = r.cookies,
                                twofakey = if (cols.any { it.key == "twofakey" }) r.twofakey else "",
                                uid = r.uid
                            )
                        }
                        val merged = (dataExisting + incoming).take(net.typeblog.socks.util.sheet.MAX_GRID_ROWS)
                        db.tx { d ->
                            db.saveAllRows(d, f.id, merged.mapIndexed { idx, r -> r.copy(rowIdx = idx) })
                            db.recordOp(d, f.id, "merge")
                        }
                        store.open(f.id)
                        "Merged " + incoming.size + " rows."
                    } else {
                        if (draft.rows.size > net.typeblog.socks.util.sheet.MAX_GRID_ROWS) {
                            return@withContext "Too many rows. Maximum " + net.typeblog.socks.util.sheet.MAX_GRID_ROWS + " rows allowed. Please split the file."
                        }
                        val cleaned = draft.rows.take(net.typeblog.socks.util.sheet.MAX_GRID_ROWS).mapIndexed { i, r ->
                            net.typeblog.socks.util.sheet.SheetRow(
                                rowIdx = i,
                                cookies = r.cookies,
                                twofakey = if (cols.any { it.key == "twofakey" }) r.twofakey else "",
                                uid = r.uid
                            )
                        }
                        db.tx { d ->
                            db.saveAllRows(d, f.id, cleaned)
                            db.recordOp(d, f.id, "replace")
                        }
                        store.open(f.id)
                        "Imported " + cleaned.size + " rows."
                    }
                } catch (e: Exception) {
                    "Import failed."
                }
            }
            toast(appCtx, msg)
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
                        contentDescription = "Undo",
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(onClick = { io { store.redo() } }, enabled = canRedo) {
                    Icon(
                        painter = painterResource(R.drawable.ic_ss_redo),
                        contentDescription = "Redo",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            // Check split button.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                    .background(
                        if (!checking && (readOnly || dupRows.isEmpty())) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.primary.copy(alpha = 0.38f)
                    )
            ) {
                Box(
                    modifier = Modifier
                        .combinedClickable(
                            enabled = !checking && (readOnly || dupRows.isEmpty()),
                            onClick = { doCheck() }
                        )
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (checking) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = "Checking",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    } else {
                        Text(
                            text = "Check",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
                if (!readOnly && !checking) {
                    Box {
                        Box(
                            modifier = Modifier
                                .combinedClickable(onClick = { checkMenu = true })
                                .padding(horizontal = 7.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_ss_check_arrow),
                                contentDescription = "More check options",
                                modifier = Modifier.size(10.dp),
                                tint = MaterialTheme.colorScheme.onPrimary
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
                TextButton(onClick = { overflowMenu = true }) {
                    Text(
                        text = "⋮",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(
                    expanded = overflowMenu,
                    onDismissRequest = { overflowMenu = false },
                    modifier = Modifier.widthIn(min = 160.dp)
                ) {
                    DropdownMenuItem(
                        text = { Text("Copy all data", fontSize = 13.sp, fontWeight = FontWeight.Medium) },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_ss_copy),
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        onClick = {
                            overflowMenu = false
                            copyAllData()
                        }
                    )
                    if (!readOnly) {
                        DropdownMenuItem(
                            text = { Text("Download xlsx", fontSize = 13.sp, fontWeight = FontWeight.Medium) },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(R.drawable.ic_ss_download),
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
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
                            text = { Text("Upload xlsx", fontSize = 13.sp, fontWeight = FontWeight.Medium) },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(R.drawable.ic_ss_upload),
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            onClick = {
                                overflowMenu = false
                                detailUploadMode = "replace"
                                detailUploadLauncher.launch("*/*")
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Merge", fontSize = 13.sp, fontWeight = FontWeight.Medium) },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(R.drawable.ic_ss_merge),
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            onClick = {
                                overflowMenu = false
                                detailUploadMode = "merge"
                                detailUploadLauncher.launch("*/*")
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Compact", fontSize = 13.sp, fontWeight = FontWeight.Medium) },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(R.drawable.ic_ss_compact),
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            onClick = {
                                overflowMenu = false
                                confirmCompact = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete inactive", fontSize = 13.sp, fontWeight = FontWeight.Medium) },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(R.drawable.ic_ss_trash),
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            onClick = {
                                overflowMenu = false
                                deleteDeadWithCount()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Restore last save", fontSize = 13.sp, fontWeight = FontWeight.Medium) },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(R.drawable.ic_ss_restore),
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            onClick = {
                                overflowMenu = false
                                confirmRestore = true
                            }
                        )
                    }
                    androidx.compose.material3.HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                    for (col in columns) {
                        val visible = !hidden.contains(col.key)
                        DropdownMenuItem(
                            text = { Text(col.label, fontSize = 12.sp, fontWeight = FontWeight.Medium) },
                            leadingIcon = {
                                ColToggleBox(checked = visible)
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
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, top = 12.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Archived file — view only. You can check UIDs and copy data.",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Box(
                        modifier = Modifier
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.primary)
                            .combinedClickable(onClick = { onRestoreArchived(fileId) })
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Restore",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
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
                        modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .width(36.dp)
                                .height(36.dp)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            contentAlignment = Alignment.Center
                        ) {}
                        for (col in visibleCols) {
                            Box(
                                modifier = Modifier
                                    .width(colWidthDp(col.key))
                                    .height(36.dp)
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
                                    .padding(horizontal = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = col.label,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Box(
                            modifier = Modifier
                                .width(36.dp)
                                .height(36.dp)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            contentAlignment = Alignment.Center
                        ) {}
                    }
                }
                items(rows, key = { it.rowIdx }) { row ->
                    val isDup = dupRows.contains(row.rowIdx)
                    val statusColor: Color? = when {
                        row.dead || row.status == "bad" -> DeadRed
                        isDup -> StatusYellow
                        row.status == "eligible" -> PageBlue
                        row.status == "good" || row.status == "done" -> AliveGreen
                        else -> null
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .width(36.dp)
                                .height(32.dp)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
                                .background(
                                    if (row.approved && statusColor != null) statusColor
                                    else MaterialTheme.colorScheme.surfaceVariant
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = (row.rowIdx + 1).toString(),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Normal,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                        for (col in visibleCols) {
                            val key = styleKey(row.rowIdx, col.key)
                            val st = styles[key]
                            val selKey = Pair(row.rowIdx, col.key)
                            val isActive = selectedCell == selKey && !selectionMode
                            val isMulti = selectedItems.contains(selKey)
                            val customBg = parseHexColor(st?.bg)
                            val fg = parseHexColor(st?.color)
                            val cellBg: Color = when {
                                customBg != null -> customBg
                                isMulti -> MaterialTheme.colorScheme.surfaceVariant
                                row.hold && statusColor != null -> statusColor
                                row.approved && statusColor != null -> statusColor
                                isDup && (col.key == "uid" || col.key == "cookies") -> StatusYellow.copy(alpha = 0.15f)
                                else -> Color.Transparent
                            }
                            val cellBorder: Color = when {
                                isActive -> MaterialTheme.colorScheme.onSurface
                                isMulti -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                                isDup && (col.key == "uid" || col.key == "cookies") -> StatusYellow
                                else -> MaterialTheme.colorScheme.outlineVariant
                            }
                            Box(
                                modifier = Modifier
                                    .width(colWidthDp(col.key))
                                    .height(36.dp)
                                    .border(1.dp, cellBorder)
                                    .background(cellBg)
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
                                            val now = System.currentTimeMillis()
                                            if (lastTapCell == selKey && now - lastTapTime < 400) {
                                                pendingTapJob?.cancel()
                                                pendingTapJob = null
                                                lastTapCell = null
                                                val v = row.cell(col.key)
                                                if (v.isNotEmpty()) {
                                                    clipboard.setText(AnnotatedString(v))
                                                    toast(appCtx, "Copied.")
                                                    selectedCell = selKey
                                                    draft = v
                                                } else {
                                                    val pasted = clipboard.getText()?.text ?: ""
                                                    if (pasted.isEmpty()) {
                                                        toast(appCtx, "Clipboard is empty.")
                                                    } else {
                                                        selectedCell = selKey
                                                        draft = pasted
                                                        scope.launch {
                                                            val ok = withContext(Dispatchers.IO) { store.setCell(selKey.first, selKey.second, pasted) }
                                                            if (!ok) toast(appCtx, "Duplicate value. Please use a unique value.")
                                                        }
                                                    }
                                                }
                                                return@combinedClickable
                                            }
                                            if (selectedCell != null && selectedCell != selKey) {
                                                commitDraft()
                                            }
                                            lastTapCell = selKey
                                            lastTapTime = now
                                            pendingTapJob?.cancel()
                                            val targetRow = row.rowIdx
                                            val targetCol = col.key
                                            val targetVal = row.cell(col.key)
                                            pendingTapJob = scope.launch {
                                                kotlinx.coroutines.delay(400)
                                                selectedCell = Pair(targetRow, targetCol)
                                                draft = targetVal
                                            }
                                        },
                                        onLongClick = {
                                            if (!readOnly && row.locked) {
                                                toast(
                                                    appCtx,
                                                    if (row.hold) "On hold. Editing is locked." else "Approved."
                                                )
                                                return@combinedClickable
                                            }
                                            pendingTapJob?.cancel()
                                            pendingTapJob = null
                                            lastTapCell = null
                                            haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                            selectionMode = true
                                            selectedCell = null
                                            selectedItems = selectedItems + selKey
                                        }
                                    )
                                    .padding(horizontal = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                // Perf: grid cells always render the committed value.
                                // Live typing lives only in the formula bar below, so
                                // keystrokes no longer recompose the whole grid.
                                Text(
                                    text = row.cell(col.key),
                                    fontSize = 13.sp,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    fontWeight = if (st?.bold == true) FontWeight.Bold else FontWeight.Normal,
                                    color = fg ?: MaterialTheme.colorScheme.onSurface,
                                    textDecoration = if (row.approved) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }
                        Box(
                            modifier = Modifier
                                .width(36.dp)
                                .height(36.dp)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
                                .background(MaterialTheme.colorScheme.surface),
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
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .combinedClickable(onClick = { io { store.addRow() } })
                                .padding(vertical = 14.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Add row",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // Selection bar.
        if (selectionMode && selectedItems.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = selectedItems.size.toString() + " selected",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        SelBtn(label = "Copy", onClick = { copySelection() })
                        if (!readOnly) {
                            SelBtn(label = "Clear", onClick = { confirmClearSelection = true })
                        }
                        SelBtn(
                            label = "Select all",
                            onClick = {
                                val all = mutableSetOf<Pair<Int, String>>()
                                for (r in rows) {
                                    for (c in visibleCols) all.add(Pair(r.rowIdx, c.key))
                                }
                                selectedItems = all
                            }
                        )
                        SelBtn(
                            label = "Unselect all",
                            onClick = {
                                selectedItems = emptySet()
                                selectionMode = false
                            }
                        )
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
                val textColorHex = st?.color ?: "#000000"
                val cellBgHex = st?.bg
                Surface(
                    tonalElevation = 3.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(top = 6.dp, start = 12.dp, end = 12.dp, bottom = 10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = draft,
                                onValueChange = { draft = it },
                                placeholder = { Text("Enter value", fontSize = 16.sp) },
                                singleLine = true,
                                textStyle = androidx.compose.ui.text.TextStyle(
                                    fontSize = 16.sp,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                ),
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(32.dp),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { commitDraft() }),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                            )
                        }
                        Spacer(modifier = Modifier.size(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(9.dp))
                                    .background(
                                        if (bold) MaterialTheme.colorScheme.primaryContainer
                                        else androidx.compose.ui.graphics.Color.Transparent
                                    )
                                    .combinedClickable(onClick = {
                                        val cur = st
                                        val next = (cur ?: CellStyle()).copy(bold = !(cur?.bold ?: false))
                                        val clean = if (next.bg == null && next.color == null && !next.bold) null else next
                                        io { store.setStyle(sel.first, sel.second, clean) }
                                    }),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "B",
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 18.sp,
                                    color = if (bold) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .height(22.dp)
                                    .background(MaterialTheme.colorScheme.outlineVariant)
                            )
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(9.dp))
                                    .combinedClickable(onClick = { picker = "text" }),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_ss_textcolor),
                                    contentDescription = "Text color",
                                    modifier = Modifier.size(18.dp)
                                )
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(bottom = 2.dp)
                                        .size(width = 16.dp, height = 3.dp)
                                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(1.dp))
                                        .background(parseHexColor(textColorHex) ?: MaterialTheme.colorScheme.onSurface)
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(9.dp))
                                    .combinedClickable(onClick = { picker = "fill" }),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_ss_fill),
                                    contentDescription = "Cell fill",
                                    modifier = Modifier.size(18.dp)
                                )
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(bottom = 2.dp)
                                        .size(width = 16.dp, height = 3.dp)
                                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(1.dp))
                                        .background(parseHexColor(cellBgHex) ?: androidx.compose.ui.graphics.Color.Transparent)
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .height(22.dp)
                                    .background(MaterialTheme.colorScheme.outlineVariant)
                            )
                            IconButton(
                                onClick = {
                                    val v = draft.ifEmpty { rows.getOrNull(sel.first)?.cell(sel.second) ?: "" }
                                    if (v.isEmpty()) {
                                        toast(appCtx, "Cell is empty.")
                                    } else {
                                        clipboard.setText(AnnotatedString(v))
                                        toast(appCtx, "Copied.")
                                    }
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_ss_copy),
                                    contentDescription = "Copy",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        val pasted = clipboard.getText()?.text ?: ""
                                        if (pasted.isEmpty()) {
                                            toast(appCtx, "Clipboard is empty.")
                                        } else {
                                            draft = pasted
                                            val ok = withContext(Dispatchers.IO) { store.setCell(sel.first, sel.second, pasted) }
                                            if (!ok) toast(appCtx, "Duplicate value. Please use a unique value.")
                                        }
                                    }
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_ss_paste),
                                    contentDescription = "Paste",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            IconButton(
                                onClick = {
                                    draft = ""
                                    io { store.clearCells(setOf(sel)) }
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_ss_eraser),
                                    contentDescription = "Clear",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .height(22.dp)
                                    .background(MaterialTheme.colorScheme.outlineVariant)
                            )
                            IconButton(
                                onClick = { confirmCompact = true },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_ss_compact),
                                    contentDescription = "Compact",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            IconButton(
                                onClick = { deleteDeadWithCount() },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_ss_trash),
                                    contentDescription = "Delete inactive",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
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
            scope.launch {
                val ok = withContext(Dispatchers.IO) { store.renameFile(id, name) }
                toast(appCtx, if (ok) "File renamed." else "Unable to rename. Please try again.")
            }
        }
        SheetModal(
            onDismiss = {
                renameOpen = false
                renameText = ""
            },
            widthDp = 320,
            title = "Rename file"
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                SheetNameInput(value = renameText, onValueChange = { renameText = it }, onDone = { commitRename() })
                Spacer(modifier = Modifier.size(12.dp))
                SheetModalFooter(
                    onCancel = {
                        renameOpen = false
                        renameText = ""
                    },
                    onConfirm = { commitRename() },
                    confirmText = "Rename"
                )
            }
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
            }
        )
    }

    if (confirmDeleteDead) {
        val n = rows.count { it.status == "bad" || it.dead }
        SheetConfirm(
            onDismiss = { confirmDeleteDead = false },
            message = "Delete $n inactive row" + if (n == 1) "?" else "s?",
            actionText = "Delete",
            onConfirm = {
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
        )
    }

    if (confirmRestore) {
        SheetConfirm(
            onDismiss = { confirmRestore = false },
            message = "Restore the last saved version? Unsaved changes will be replaced. Your current state will remain in Undo.",
            actionText = "Restore",
            onConfirm = {
                confirmRestore = false
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { store.restoreSnapshot() }
                    toast(
                        appCtx,
                        if (ok) "Previous version restored. Your changes remain in Undo."
                        else "No earlier save to restore yet."
                    )
                }
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

    val pick = picker
    val selPick = selectedCell
    if (pick != null && selPick != null) {
        val cur = styles[styleKey(selPick.first, selPick.second)]
        SheetModal(
            onDismiss = { picker = null },
            widthDp = 320,
            title = if (pick == "text") "TEXT COLOR" else "CELL FILL",
            miniTitle = true
        ) {
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                ) {
                    for (hex in PALETTE) {
                        val c = parseHexColor(hex) ?: Color.Black
                        val active = if (pick == "text") cur?.color == hex else cur?.bg == hex
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(32.dp)
                                .background(c)
                                .border(
                                    width = if (active) 2.dp else 0.dp,
                                    color = if (active) MaterialTheme.colorScheme.onSurface else Color.Transparent
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
            }
        }
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

@Composable
private fun ColToggleBox(checked: Boolean) {
    Box(
        modifier = Modifier
            .size(16.dp)
            .border(
                1.5.dp,
                if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                androidx.compose.foundation.shape.RoundedCornerShape(3.dp)
            )
            .background(
                if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                androidx.compose.foundation.shape.RoundedCornerShape(3.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        if (checked) {
            Icon(
                painter = painterResource(R.drawable.ic_ss_check),
                contentDescription = null,
                modifier = Modifier.size(10.dp),
                tint = MaterialTheme.colorScheme.onPrimary
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SelBtn(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
            .combinedClickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
