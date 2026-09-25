package net.typeblog.socks.ui.screens.sheet

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.typeblog.socks.util.sheet.CopiedGrid
import net.typeblog.socks.util.sheet.MAX_GRID_ROWS
import net.typeblog.socks.util.sheet.SheetColumn
import net.typeblog.socks.util.sheet.SheetFile
import net.typeblog.socks.util.sheet.SheetPreset
import net.typeblog.socks.util.sheet.SheetRow
import net.typeblog.socks.util.sheet.SheetStore
import net.typeblog.socks.util.sheet.SheetXlsx

internal fun detailAutoCheckArmed(
    readOnly: Boolean,
    preset: SheetPreset?,
    autoCheck: Boolean,
    simpleCheck: Boolean,
    advancedCheck: Boolean
): Boolean = !readOnly &&
        (autoCheck || (preset == SheetPreset.PAGE && (simpleCheck || advancedCheck)))

internal fun runSheetDetailCheck(
    scope: CoroutineScope,
    appCtx: Context,
    store: SheetStore,
    rows: List<SheetRow>,
    columns: List<SheetColumn>,
    checking: Boolean,
    readOnly: Boolean,
    autoCheck: Boolean,
    simpleCheck: Boolean,
    advancedCheck: Boolean,
    preset: SheetPreset?
) {
    if (checking) return
    val checkable = detailHasUidToCheck(rows, columns)
    if (!checkable) {
        toast(appCtx, "No UID to check.")
        return
    }
    store.runCheck(
        autoCheck,
        simpleCheck && !readOnly,
        advancedCheck && !readOnly,
        preset == SheetPreset.PAGE
    ) { valid, dead, persisted ->
        scope.launch {
            toast(
                appCtx,
                if (persisted) detailCheckSummary(valid, dead)
                else "Check finished, but the result could not be saved."
            )
        }
    }
}

internal fun commitSheetDetailDraft(
    scope: CoroutineScope,
    appCtx: Context,
    store: SheetStore,
    rows: List<SheetRow>,
    selectedCell: Pair<Int, String>?,
    draft: String,
    onCookiesChanged: () -> Unit
) {
    val sel = selectedCell ?: return
    val (ri, ck) = sel
    val row = rows.getOrNull(ri) ?: return
    if (row.locked) {
        toast(appCtx, if (row.hold) "On hold. Editing is locked." else "Approved.")
        return
    }
    if (row.cell(ck) == draft) return
    store.rejectReason(ri, ck, draft)?.let { toast(appCtx, it); return }
    scope.launch {
        val ok = withContext(Dispatchers.IO) { store.setCell(ri, ck, draft) }
        if (!ok) {
            toast(appCtx, "Couldn't save. Please try again.")
        } else if (ck == "cookies") {
            onCookiesChanged()
        }
    }
}

internal fun copySheetDetailSelection(
    selectedItems: Set<Pair<Int, String>>,
    visibleCols: List<SheetColumn>,
    rows: List<SheetRow>,
    preset: String,
    store: SheetStore,
    clipboard: ClipboardManager,
    appCtx: Context,
    selectionMode: Boolean,
    onSelectionFinished: () -> Unit
) {
    if (selectedItems.isEmpty()) return
    val order = visibleCols.map { it.key }
    val byRow = selectedItems.groupBy { it.first }.toSortedMap()
    val sortedKeys = { cells: Set<Pair<Int, String>> -> detailSortedKeys(cells, order) }

    val gridRows = byRow.entries.map { (ri, cells) ->
        val row = rows.getOrNull(ri)
        sortedKeys(cells.toSet()).map { key -> Pair(key, row?.cell(key) ?: "") }
    }
    val keyOrder = sortedKeys(selectedItems)
    store.copyGrid(
        CopiedGrid(
            preset = preset,
            columns = keyOrder,
            cells = gridRows
        )
    )
    val lines = byRow.entries.map { (ri, cells) ->
        val keys = sortedKeys(cells.toSet())
        val row = rows.getOrNull(ri)
        keys.map { key -> row?.cell(key) ?: "" }.joinToString("\t")
    }
    clipboard.setText(AnnotatedString(lines.joinToString("\n")))
    toast(appCtx, "Copied.")
    if (selectionMode) onSelectionFinished()
}

internal fun pasteSheetDetailGrid(
    scope: CoroutineScope,
    appCtx: Context,
    store: SheetStore,
    grid: CopiedGrid,
    anchor: Pair<Int, String>,
    area: Set<Pair<Int, String>>?,
    visibleColumns: () -> List<String>,
    shouldClearSelection: () -> Boolean,
    onSelectionCleared: () -> Unit,
    onCookiesPasted: () -> Unit,
    onAnchorPasted: (Pair<Int, String>, String) -> Unit
) {
    scope.launch {
        val res = withContext(Dispatchers.IO) {
            store.pasteGrid(grid, anchor, area, visibleColumns())
        }
        val (pasted, skipped, cookies, note) = res
        if (pasted == 0 && skipped == 0) {
            toast(appCtx, "Nothing to paste.")
            return@launch
        }
        toast(
            appCtx,
            when {
                note != null && pasted > 0 -> "Pasted $pasted, $note"
                note != null -> note
                skipped > 0 -> "Pasted $pasted, skipped $skipped."
                else -> "Pasted $pasted."
            }
        )
        if (pasted > 0) {
            if (cookies) onCookiesPasted()
            if (shouldClearSelection()) onSelectionCleared()
            if (area == null) {
                val value = store.openRows.value.getOrNull(anchor.first)?.cell(anchor.second) ?: ""
                onAnchorPasted(anchor, value)
            }
        }
    }
}

internal fun detailCanDeleteDead(appCtx: Context, rows: List<SheetRow>): Boolean {
    val count = rows.count { it.status == "bad" || it.dead }
    if (count == 0) {
        toast(appCtx, "No dead rows.")
        return false
    }
    return true
}

internal fun sheetDetailDownloadName(
    appCtx: Context,
    file: SheetFile?,
    rows: List<SheetRow>
): String? {
    if (file == null || rows.none { it.isData(file.preset.columns) }) {
        toast(appCtx, "Add content first.")
        return null
    }
    return sanitizeFileName(file.name)
}

internal fun writeSheetDetailDownload(
    scope: CoroutineScope,
    appCtx: Context,
    uri: Uri?,
    name: String,
    file: () -> SheetFile?,
    rows: () -> List<SheetRow>
) {
    if (uri == null) return
    scope.launch {
        val msg = withContext(Dispatchers.IO) {
            try {
                val currentFile = file()
                val data = rows()
                val cols = currentFile?.preset?.columns ?: emptyList()
                if (cols.isEmpty() || data.none { it.isData(cols) }) {
                    return@withContext "Add content first."
                }
                appCtx.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(SheetXlsx.build(cols, data))
                } ?: return@withContext "Download failed."
                "Downloaded $name.xlsx"
            } catch (e: Exception) {
                "Download failed."
            }
        }
        toast(appCtx, msg)
    }
}

internal fun shareSheetDetailFile(
    scope: CoroutineScope,
    context: Context,
    appCtx: Context,
    file: () -> SheetFile?,
    rows: () -> List<SheetRow>
) {
    scope.launch {
        var uri: Uri? = null
        var subject = ""
        val err = withContext(Dispatchers.IO) {
            try {
                val currentFile = file()
                val data = rows()
                val cols = currentFile?.preset?.columns ?: emptyList()
                if (currentFile == null || cols.isEmpty() || data.none { it.isData(cols) }) {
                    return@withContext "Add content first."
                }
                appCtx.cacheDir.listFiles { cached ->
                    cached.isFile && cached.name.startsWith("share-") && cached.name.endsWith(".xlsx")
                }?.forEach {
                    try {
                        it.delete()
                    } catch (_: Exception) {
                    }
                }
                val out = java.io.File(appCtx.cacheDir, "share-" + sanitizeFileName(currentFile.name) + ".xlsx")
                out.writeBytes(SheetXlsx.build(cols, data))
                uri = FileProvider.getUriForFile(
                    appCtx, "${appCtx.packageName}.provider", out
                )
                subject = currentFile.name
                null
            } catch (e: Exception) {
                "Couldn't share."
            }
        }
        val sharedUri = uri
        if (sharedUri != null) {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                putExtra(Intent.EXTRA_STREAM, sharedUri)
                putExtra(Intent.EXTRA_SUBJECT, subject)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(Intent.createChooser(intent, "Send a copy"))
            } catch (e: Exception) {
                toast(appCtx, "No app can share this file.")
            }
        } else if (err != null) {
            toast(appCtx, err)
        }
    }
}

internal fun importSheetDetailFile(
    scope: CoroutineScope,
    appCtx: Context,
    uri: Uri,
    mode: String,
    file: () -> SheetFile?,
    store: SheetStore
) {
    scope.launch {
        val msg = withContext(Dispatchers.IO) {
            try {
                val draft = parseUpload(appCtx, uri) ?: return@withContext "Import failed."
                val target = file() ?: return@withContext "Import failed."
                val columns = target.preset.columns
                if (mode == "merge") {
                    val incoming = detailUploadedRows(draft.rows, columns)
                    val saved = store.mergeRows(target.id, incoming)
                        ?: return@withContext "Import failed."
                    "Merged $saved rows."
                } else {
                    if (draft.rows.size > MAX_GRID_ROWS) {
                        return@withContext "Too many rows. Maximum " +
                                MAX_GRID_ROWS +
                                " rows allowed. Please split the file."
                    }
                    val cleaned = detailUploadedRows(draft.rows, columns)
                    val saved = store.replaceRows(target.id, cleaned)
                        ?: return@withContext "Import failed."
                    "Imported $saved rows."
                }
            } catch (e: Exception) {
                "Import failed."
            }
        }
        toast(appCtx, msg)
    }
}

internal fun restoreSheetDetailFile(
    scope: CoroutineScope,
    appCtx: Context,
    store: SheetStore,
    fileId: String,
    onRestored: (String) -> Unit
) {
    scope.launch {
        withContext(Dispatchers.IO) {
            store.archiveFile(fileId, false)
            store.open(fileId)
        }
        toast(appCtx, "File restored.")
        onRestored(fileId)
    }
}

internal fun addSheetDetailRow(
    scope: CoroutineScope,
    appCtx: Context,
    store: SheetStore
) {
    scope.launch {
        val ok = withContext(Dispatchers.IO) { store.addRow() }
        if (!ok) toast(appCtx, "Row limit reached. Maximum 500 rows allowed.")
    }
}

internal fun renameSheetDetailFile(
    scope: CoroutineScope,
    appCtx: Context,
    store: SheetStore,
    fileId: String,
    name: String
) {
    scope.launch {
        val ok = withContext(Dispatchers.IO) { store.renameFile(fileId, name) }
        toast(appCtx, if (ok) "File renamed." else "Unable to rename. Please try again.")
    }
}

internal fun deleteSheetDetailDeadRows(
    scope: CoroutineScope,
    appCtx: Context,
    store: SheetStore
) {
    scope.launch {
        val removed = withContext(Dispatchers.IO) { store.deleteDeadRows() }
        toast(
            appCtx,
            when {
                removed == null -> "Couldn't delete dead rows. Please try again."
                removed > 0 -> "Deleted $removed dead row" + if (removed == 1) "." else "s."
                else -> "No dead rows to delete."
            }
        )
    }
}
