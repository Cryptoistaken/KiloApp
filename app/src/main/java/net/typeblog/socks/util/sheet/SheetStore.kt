package net.typeblog.socks.util.sheet

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

// In-memory working state over SheetDb. Every mutation writes the DB first
// (local-first), then refreshes the exposed flows. Undo/redo is per open
// file and persisted in undo_hist/redo_hist, so it survives app restarts.
class SheetStore private constructor(context: Context) {
    private val db = SheetDb(context.applicationContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val files = MutableStateFlow<List<SheetFile>>(emptyList())
    val archive = MutableStateFlow<List<SheetFile>>(emptyList())
    val balance = MutableStateFlow(0.0)
    val txs = MutableStateFlow<List<WalletTx>>(emptyList())

    val openFile = MutableStateFlow<SheetFile?>(null)
    val openRows = MutableStateFlow<List<SheetRow>>(emptyList())
    val openStyles = MutableStateFlow<Map<String, CellStyle>>(emptyMap())
    val openHidden = MutableStateFlow<Set<String>>(emptySet())
    // Cross-file duplicate marks for the open file ((rowIdx, colKey) cells).
    // Same-file repeats are blocked at entry — only collisions with OTHER
    // files flag, per cell.
    val openCrossDups = MutableStateFlow<Set<Pair<Int, String>>>(emptySet())
    // Check records behind the dot popup, keyed by rowIdx. Refreshed on
    // open and after every check; row edits drop stale rows (see
    // persistRows), reindexing ops drop the file.
    val openChecks = MutableStateFlow<Map<Int, RowCheck>>(emptyMap())
    val openCheckReqs = MutableStateFlow<Map<Int, List<CheckReq>>>(emptyMap())
    val checking = MutableStateFlow(false)

    private val undoStack = ArrayDeque<List<SheetRow>>()
    private val redoStack = ArrayDeque<List<SheetRow>>()
    val canUndo = MutableStateFlow(false)
    val canRedo = MutableStateFlow(false)

    init {
        refresh()
    }

    fun refresh() {
        scope.launch {
            files.value = db.listFiles(false)
            archive.value = db.listFiles(true)
            balance.value = db.walletBalance()
            txs.value = db.walletTxs()
        }
    }

    private fun pushUndo() {
        val prev = openRows.value.map { it.copy() }
        undoStack.addLast(prev)
        if (undoStack.size > 50) undoStack.removeFirst()
        redoStack.clear()
        canUndo.value = undoStack.isNotEmpty()
        canRedo.value = false
        // Persist pre-state so undo survives app restarts. New edits drop
        // the redo chain, same as memory.
        val fid = openFile.value?.id ?: return
        try {
            db.tx { d ->
                db.insertUndo(d, fid, rowsToJson(prev))
                db.clearRedo(d, fid)
            }
        } catch (e: Exception) {
            // Memory stack already updated; DB is best-effort here.
        }
    }

    fun undo(): Boolean {
        val prev = undoStack.removeLastOrNull() ?: return false
        val cur = openRows.value.map { it.copy() }
        redoStack.addLast(cur)
        val fid = openFile.value?.id
        if (fid != null) {
            try {
                db.tx { d ->
                    db.insertRedo(d, fid, rowsToJson(cur))
                    db.popUndo(d, fid)
                }
            } catch (e: Exception) {
            }
        }
        persistRows(prev, "undo")
        canUndo.value = undoStack.isNotEmpty()
        canRedo.value = true
        return true
    }

    fun redo(): Boolean {
        val next = redoStack.removeLastOrNull() ?: return false
        val cur = openRows.value.map { it.copy() }
        undoStack.addLast(cur)
        if (undoStack.size > 50) undoStack.removeFirst()
        val fid = openFile.value?.id
        if (fid != null) {
            try {
                db.tx { d ->
                    db.insertUndo(d, fid, rowsToJson(cur))
                    db.popRedo(d, fid)
                }
            } catch (e: Exception) {
            }
        }
        persistRows(next, "redo")
        canUndo.value = true
        canRedo.value = redoStack.isNotEmpty()
        return true
    }

    fun createFile(preset: SheetPreset, password: String): SheetFile {
        val now = System.currentTimeMillis()
        val names = files.value.map { it.name } + archive.value.map { it.name }
        val f = SheetFile(
            id = newFileId(), name = autoFileName(preset, names), preset = preset,
            password = password, archived = false, deletedAt = 0,
            createdAt = now, updatedAt = now, seq = 0
        )
        db.tx { d ->
            db.insertFile(d, f)
            db.recordOp(d, f.id, "create")
        }
        refresh()
        return f
    }

    fun renameFile(id: String, name: String): Boolean {
        val f = db.getFile(id) ?: return false
        val now = System.currentTimeMillis()
        db.tx { d ->
            db.updateFile(d, f.copy(name = name, updatedAt = now, seq = f.seq + 1))
            db.recordOp(d, id, "rename")
        }
        if (openFile.value?.id == id) openFile.value = db.getFile(id)
        refresh()
        return true
    }

    fun archiveFile(id: String, toArchive: Boolean) {
        val f = db.getFile(id) ?: return
        val now = System.currentTimeMillis()
        db.tx { d ->
            db.updateFile(
                d,
                f.copy(
                    archived = toArchive, deletedAt = if (toArchive) now else 0,
                    updatedAt = now, seq = f.seq + 1
                )
            )
            db.recordOp(d, id, if (toArchive) "archive" else "restore")
        }
        if (openFile.value?.id == id && toArchive) closeFile()
        refresh()
    }

    fun deleteForever(id: String) {
        db.tx { d ->
            db.deleteFileAll(d, id)
            db.recordOp(d, id, "purge")
        }
        if (openFile.value?.id == id) closeFile()
        refresh()
    }

    fun open(id: String): Boolean {
        val f = db.getFile(id) ?: return false
        // Atomic publish: load everything first, then flip all flows at
        // once. Publishing file/rows/styles/hidden one at a time (with DB
        // work between) recomposed the grid mid-load — a flash of hidden
        // columns and empty text before the file rendered.
        val rows = topUp(db.loadRows(id).ifEmpty { emptyPad(f.preset) }, f.preset)
        val styles = db.loadStyles(id)
        val hidden = db.loadHidden(id)
        val checks = db.loadRowChecks(id)
        val reqs = db.loadCheckReqs(id)
        val dups = try {
            db.crossDupCells(id, rows)
        } catch (e: Exception) {
            emptySet()
        }
        // Restore persisted undo/redo (survives app restarts). Corrupt
        // entries are skipped, never crash open.
        val savedUndo = try {
            db.loadUndoStack(id, 50)
        } catch (e: Exception) {
            emptyList()
        }
        val savedRedo = try {
            db.loadRedoStack(id, 50)
        } catch (e: Exception) {
            emptyList()
        }
        undoStack.clear()
        redoStack.clear()
        for (data in savedUndo) {
            try {
                undoStack.addLast(rowsFromJson(data).map { it.copy() })
            } catch (e: Exception) {
            }
        }
        for (data in savedRedo) {
            try {
                redoStack.addLast(rowsFromJson(data).map { it.copy() })
            } catch (e: Exception) {
            }
        }
        canUndo.value = undoStack.isNotEmpty()
        canRedo.value = redoStack.isNotEmpty()
        openFile.value = f
        openRows.value = rows
        openStyles.value = styles
        openHidden.value = hidden
        openChecks.value = checks
        openCheckReqs.value = reqs
        openCrossDups.value = dups
        return true
    }

    fun reloadChecks() {
        val f = openFile.value ?: run {
            openChecks.value = emptyMap()
            openCheckReqs.value = emptyMap()
            return
        }
        openChecks.value = db.loadRowChecks(f.id)
        openCheckReqs.value = db.loadCheckReqs(f.id)
    }

    // Wholesale drop (reindexing ops, import replace): DB + flows.
    fun dropCheckData() {
        val f = openFile.value ?: return
        db.tx { d -> db.clearCheckData(d, f.id) }
        openChecks.value = emptyMap()
        openCheckReqs.value = emptyMap()
    }

    fun closeFile() {
        openFile.value = null
        openRows.value = emptyList()
        openStyles.value = emptyMap()
        openHidden.value = emptySet()
        openCrossDups.value = emptySet()
        openChecks.value = emptyMap()
        openCheckReqs.value = emptyMap()
        undoStack.clear()
        redoStack.clear()
        canUndo.value = false
        canRedo.value = false
    }

    private fun emptyPad(preset: SheetPreset, n: Int = MAX_GRID_ROWS): List<SheetRow> {
        val cols = preset.columns
        return List(n) { i -> SheetRow(rowIdx = i).let { r -> if (cols.isEmpty()) r else r } }
    }

    private fun topUp(rows: List<SheetRow>, preset: SheetPreset): List<SheetRow> {
        val lastData = rows.indexOfLast { it.isData(preset.columns) }
        val want = (lastData + 51).coerceAtLeast(MAX_GRID_ROWS)
        if (rows.size >= want) return rows
        return rows + (rows.size until want).map { SheetRow(rowIdx = it) }
    }

    private fun persistRows(rows: List<SheetRow>, op: String) {
        val f = openFile.value ?: return
        val now = System.currentTimeMillis()
        val seq = f.seq + 1
        val snapshot = rowsToJson(rows)
        val prevByIdx = openRows.value.associateBy { it.rowIdx }
        // Check records follow the verdict: any row whose identity or
        // verdict changed leaves stale details behind, so drop them.
        // Reindexing ops (delete-dead, compact, restore, import) clear
        // the file explicitly — rowIdx keys would otherwise orphan.
        val stale = rows.mapNotNull { nr ->
            val o = prevByIdx[nr.rowIdx]
            if (o == null || o.cookies != nr.cookies || o.uid != nr.uid ||
                o.status != nr.status || o.dead != nr.dead
            ) nr.rowIdx else null
        }.toSet()
        db.tx { d ->
            db.saveAllRows(d, f.id, rows)
            db.saveSnapshot(d, f.id, seq, snapshot)
            db.updateFile(d, f.copy(updatedAt = now, seq = seq))
            db.recordOp(d, f.id, op)
            if (stale.isNotEmpty()) db.deleteRowCheckData(d, f.id, stale)
        }
        if (stale.isNotEmpty() && (openChecks.value.keys.any { it in stale } || openCheckReqs.value.keys.any { it in stale })) {
            openChecks.value = openChecks.value.filterKeys { it !in stale }
            openCheckReqs.value = openCheckReqs.value.filterKeys { it !in stale }
        }
        openFile.value = db.getFile(f.id)
        openRows.value = rows
        refreshCrossDups(rows)
        refresh()
    }

    // Recomputes cross-file dup marks. Callers already run on Dispatchers.IO
    // (open/persist paths), so the cross-file scan stays off the main thread.
    private fun refreshCrossDups(rows: List<SheetRow> = openRows.value) {
        val f = openFile.value ?: run {
            openCrossDups.value = emptySet()
            return
        }
        openCrossDups.value = try {
            db.crossDupCells(f.id, rows)
        } catch (e: Exception) {
            emptySet()
        }
    }

    // In-file duplicates are blocked, never marked: there is no yellow
    // indicator because a duplicate value can never be saved. The uid is
    // derived, not typed: with a cookie present any uid edit (paste, type
    // or clear) is rejected, and the cookie always overwrites it.
    // Returns the exact message to show when the value must be rejected,
    // null when OK. (Locked rows are messaged by the caller, so they pass
    // here.)
    fun rejectReason(rowIdx: Int, colKey: String, value: String): String? {
        val rows = openRows.value
        val cur = rows.getOrNull(rowIdx) ?: return "Couldn't save. Please try again."
        if (cur.locked) return null
        if (cur.cell(colKey) == value) return null
        if (colKey == "uid" && cur.cookies.isNotEmpty()) {
            return "UID comes from the cookie."
        }
        if (colKey == "twofakey" && value.isNotEmpty() && !isValidTwoFaKey(value)) {
            return "Invalid 2fa key."
        }
        if ((colKey == "uid" || colKey == "cookies" || colKey == "twofakey") && value.isNotEmpty() &&
            rows.any { it.rowIdx != rowIdx && it.cell(colKey) == value }
        ) {
            return "Duplicate " + when (colKey) {
                "cookies" -> "cookie."
                "twofakey" -> "2fa."
                else -> "uid."
            }
        }
        // Same account under a different cookie string: its c_user already
        // lives as another row's uid, so the cookie is refused as well.
        if (colKey == "cookies" && value.isNotEmpty()) {
            val extracted = extractCUser(value)
            if (extracted != null && rows.any { it.rowIdx != rowIdx && it.uid == extracted }) {
                return "Duplicate uid."
            }
        }
        return null
    }

    fun setCell(rowIdx: Int, colKey: String, value: String): Boolean {
        val f = openFile.value ?: return false
        val rows = openRows.value.toMutableList()
        if (rowIdx !in rows.indices) return false
        val cur = rows[rowIdx]
        if (cur.locked) return false
        if (cur.cell(colKey) == value) return true
        if (rejectReason(rowIdx, colKey, value) != null) return false
        // UID comes from the cookie, never from typing: committing a cookie
        // overwrites the uid with its c_user (or blanks it when the cookie
        // carries none), and clearing the cookie clears the uid with it.
        // The verdict goes with the identity: any cookie write also clears
        // the dot (status/dead), the auto-check re-verdicts right after.
        // Centralized here so every entry point (formula bar, double-tap
        // paste, quick paste button) behaves the same.
        if (colKey == "cookies") {
            pushUndo()
            rows[rowIdx] = if (value.isEmpty()) {
                cur.withCell(colKey, value).withCell("uid", "")
                    .copy(status = "", dead = false)
            } else {
                cur.withCell(colKey, value).withCell("uid", extractCUser(value) ?: "")
                    .copy(status = "", dead = false)
            }
            persistRows(topUp(rows, f.preset), "edit")
            return true
        }
        pushUndo()
        rows[rowIdx] = if (colKey == "uid") {
            cur.withCell(colKey, value).copy(status = "", dead = false)
        } else {
            cur.withCell(colKey, value)
        }
        persistRows(topUp(rows, f.preset), "edit")
        return true
    }

    // Internal grid clipboard: set by Copy, read by Paste, survives file
    // switches (open/close never clear it).
    val copiedGrid = MutableStateFlow<CopiedGrid?>(null)

    fun copyGrid(grid: CopiedGrid) {
        copiedGrid.value = grid
    }

    // Google-Sheets-style paste. The clipboard tiles from the anchor to fill
    // a bigger selection and overflows past a smaller one (or no selection).
    // Same preset pastes positionally; across presets values map by column
    // key and unknown keys skip. Cookies drag their c_user along (uid
    // derivation); every other cell goes through the same entry rules as
    // typing (duplicates, 2fa, locked rows skip). Single undo, single
    // persist. Returns PasteResult(pasted, skipped, cookiesWritten, note).
    fun pasteGrid(
        grid: CopiedGrid,
        anchor: Pair<Int, String>,
        area: Set<Pair<Int, String>>?,
        order: List<String>
    ): PasteResult {
        val f = openFile.value ?: return PasteResult(0, 0, false)
        if (grid.cells.isEmpty() || grid.columns.isEmpty() || order.isEmpty()) return PasteResult(0, 0, false)
        val rows = openRows.value
        if (rows.isEmpty()) return PasteResult(0, 0, false)
        val samePreset = grid.preset == f.preset.name
        val selRows: List<Int>
        val selCols: List<String>
        if (!area.isNullOrEmpty()) {
            selRows = area.map { it.first }.distinct().sorted()
            selCols = order.filter { k -> area.any { it.second == k } }
        } else {
            selRows = emptyList()
            selCols = emptyList()
        }
        val aRow = if (selRows.isNotEmpty()) selRows.first() else anchor.first
        val aKey = if (selCols.isNotEmpty()) selCols.first() else anchor.second
        val aCol = order.indexOf(aKey).let { if (it < 0) 0 else it }
        val rCount = grid.cells.size
        val cCount = grid.columns.size
        data class Write(val ri: Int, val ck: String, val value: String)
        val pass1 = mutableListOf<Write>() // everything except uid
        val pass2 = mutableListOf<Write>() // uid last, sees dragged cookies
        var skipped = 0
        for (i in 0 until maxOf(selRows.size, rCount)) {
            val ri = aRow + i
            if (ri !in rows.indices) {
                skipped += maxOf(selCols.size, cCount)
                continue
            }
            for (j in 0 until maxOf(selCols.size, cCount)) {
                val targetKey = if (j < selCols.size) selCols[j]
                else order.getOrNull(aCol + j)
                if (targetKey == null) {
                    skipped++
                    continue
                }
                val srcRow = grid.cells[i % rCount]
                val value = if (samePreset) {
                    srcRow.getOrNull(j % cCount)?.second
                } else {
                    srcRow.firstOrNull { it.first == targetKey }?.second
                }
                if (value == null) {
                    skipped++
                    continue
                }
                (if (targetKey == "uid") pass2 else pass1).add(Write(ri, targetKey, value))
            }
        }
        val w = rows.toMutableList()
        var pasted = 0
        var cookiesWritten = false
        var dirty = false
        // First notable skip message, for the result toast.
        var note: String? = null
        fun noteDup(ck: String) {
            if (note == null) note = "Duplicate " + when (ck) {
                "cookies" -> "cookie."
                "twofakey" -> "2fa."
                else -> "uid."
            }
        }
        for (t in pass1) {
            val cur = w.getOrNull(t.ri) ?: run { skipped++; continue }
            if (cur.locked) {
                skipped++
                continue
            }
            if (cur.cell(t.ck) == t.value) {
                pasted++
                continue
            }
            if (t.ck == "twofakey" && t.value.isNotEmpty() && !isValidTwoFaKey(t.value)) {
                skipped++
                continue
            }
            if ((t.ck == "cookies" || t.ck == "twofakey") && t.value.isNotEmpty() &&
                w.any { it.rowIdx != t.ri && it.cell(t.ck) == t.value }
            ) {
                noteDup(t.ck)
                skipped++
                continue
            }
            w[t.ri] = if (t.ck == "cookies") {
                cookiesWritten = true
                cur.withCell(t.ck, t.value).withCell("uid", extractCUser(t.value) ?: "")
                    .copy(status = "", dead = false)
            } else {
                cur.withCell(t.ck, t.value)
            }
            dirty = true
            pasted++
        }
        for (t in pass2) {
            val cur = w.getOrNull(t.ri) ?: run { skipped++; continue }
            if (cur.locked) {
                skipped++
                continue
            }
            if (cur.cookies.isNotEmpty()) {
                // Derived: only the cookie's own c_user may stand.
                if (cur.uid == t.value) pasted++
                else {
                    if (note == null) note = "UID comes from the cookie."
                    skipped++
                }
                continue
            }
            if (cur.uid == t.value) {
                pasted++
                continue
            }
            if (t.value.isNotEmpty() && w.any { it.rowIdx != t.ri && it.uid == t.value }) {
                noteDup("uid")
                skipped++
                continue
            }
            w[t.ri] = cur.withCell("uid", t.value).copy(status = "", dead = false)
            dirty = true
            pasted++
        }
        if (!dirty) return PasteResult(pasted, skipped, false, note)
        pushUndo()
        persistRows(topUp(w, f.preset), "paste")
        return PasteResult(pasted, skipped, cookiesWritten, note)
    }

    fun addRow(): Boolean {
        val f = openFile.value ?: return false
        if (openRows.value.size >= MAX_GRID_ROWS) return false
        pushUndo()
        val rows = openRows.value + SheetRow(rowIdx = openRows.value.size)
        persistRows(rows, "add-row")
        return true
    }

    // Infinite scroll: append empty rows when the user nears the end.
    fun growRows(count: Int): Boolean {
        val f = openFile.value ?: return false
        val room = MAX_GRID_ROWS - openRows.value.size
        if (room <= 0 || count <= 0) return false
        pushUndo()
        val n = minOf(count, room)
        val rows = openRows.value + (openRows.value.size until openRows.value.size + n).map {
            SheetRow(rowIdx = it)
        }
        persistRows(rows, "grow")
        return true
    }

    fun clearCells(cells: Set<Pair<Int, String>>) {
        val f = openFile.value ?: return
        val rows = openRows.value.toMutableList()
        var touched = false
        for ((ri, ck) in cells) {
            if (ri !in rows.indices || rows[ri].locked) continue
            // UID is derived: it can only go away with its cookie, never
            // alone — and clearing a cookie takes its uid with it.
            if (ck == "uid" && rows[ri].cookies.isNotEmpty()) continue
            if (rows[ri].cell(ck).isNotEmpty()) {
                rows[ri] = rows[ri].withCell(ck, "")
                // The verdict goes with the identity: clearing a cookie
                // (which takes its uid) or a uid also clears the dot.
                if (ck == "cookies") rows[ri] = rows[ri].withCell("uid", "")
                if (ck == "cookies" || ck == "uid") {
                    rows[ri] = rows[ri].copy(status = "", dead = false)
                }
                touched = true
            }
        }
        if (!touched) return
        pushUndo()
        persistRows(rows, "clear")
    }

    fun deleteDeadRows(): Int {
        val f = openFile.value ?: return 0
        val cols = f.preset.columns
        val dead = openRows.value.filter { it.status == "bad" || it.dead }
        if (dead.isEmpty()) return 0
        pushUndo()
        val kept = openRows.value.filterNot { it.status == "bad" || it.dead }
            .mapIndexed { i, r -> r.copy(rowIdx = i) }
        // Reindexed: rowIdx keys would orphan, drop the file's records.
        dropCheckData()
        persistRows(topUp(kept.ifEmpty { emptyPad(f.preset, 0) }, f.preset).ifEmpty { emptyPad(f.preset) }, "delete-dead")
        voidUnused(cols)
        return dead.size
    }

    private fun voidUnused(@Suppress("UNUSED_PARAMETER") cols: List<SheetColumn>) {
    }

    fun compactRows() {
        val f = openFile.value ?: return
        val cols = f.preset.columns
        val data = openRows.value.filter { it.isData(cols) }.mapIndexed { i, r -> r.copy(rowIdx = i) }
        pushUndo()
        // Reindexed: rowIdx keys would orphan, drop the file's records.
        dropCheckData()
        persistRows(topUp(data, f.preset).ifEmpty { emptyPad(f.preset) }, "compact")
    }

    fun setStyle(rowIdx: Int, colKey: String, style: CellStyle?) {
        val f = openFile.value ?: return
        db.tx { d ->
            db.saveStyle(d, f.id, rowIdx, colKey, style)
            db.recordOp(d, f.id, "style")
        }
        openStyles.value = db.loadStyles(f.id)
    }

    fun setHidden(hidden: Set<String>) {
        val f = openFile.value ?: return
        db.tx { d ->
            db.saveHidden(d, f.id, hidden)
            db.recordOp(d, f.id, "columns")
        }
        openHidden.value = hidden
    }

    fun restoreSnapshot(): Boolean {
        val f = openFile.value ?: return false
        val snap = db.latestSnapshot(f.id) ?: return false
        pushUndo()
        val rows = rowsFromJson(snap.second).mapIndexed { i, r -> r.copy(rowIdx = i) }
        // Reindexed: rowIdx keys would orphan, drop the file's records.
        dropCheckData()
        persistRows(topUp(rows, f.preset).ifEmpty { emptyPad(f.preset) }, "restore")
        return true
    }

    fun hasSnapshot(): Boolean {
        val f = openFile.value ?: return false
        return db.latestSnapshot(f.id) != null
    }

    fun runCheck(
        uidOn: Boolean = true,
        simpleOn: Boolean = false,
        advancedOn: Boolean = false,
        isPageFile: Boolean = false,
        done: (valid: Int, dead: Int) -> Unit
    ) {
        val f = openFile.value ?: return
        if (checking.value) return
        checking.value = true
        scope.launch {
            val cols = f.preset.columns
            var valid = 0
            var dead = 0
            var rows = openRows.value
            val now = System.currentTimeMillis()
            // Records behind the dot popup, keyed by rowIdx. Saved after
            // persistRows (which drops the stale ones first).
            val checks = mutableMapOf<Int, RowCheck>()
            val reqs = mutableMapOf<Int, MutableList<CheckReq>>()
            fun reqList(ri: Int) = reqs.getOrPut(ri) { mutableListOf() }
            // 1. UID liveness: one batched direct request (worker checkUids).
            // Falls back to the local format heuristic when offline.
            if (uidOn) {
                fun effUid(r: SheetRow): String = r.uid.ifEmpty {
                    Regex("c_user=(\\d+)").find(r.cookies)?.groupValues?.get(1) ?: ""
                }
                val batch: SheetChecker.UidBatch? = try {
                    SheetChecker.checkUids(
                        rows.filter { r -> r.isData(cols) && !r.locked && effUid(r).isNotEmpty() }
                            .map { effUid(it) }.distinct()
                    )
                } catch (e: Exception) {
                    null
                }
                val verdict = batch?.dead
                rows = rows.map { r ->
                    if (!r.isData(cols) || r.locked) {
                        r
                    } else if (r.cookies.isNotEmpty() && effUid(r).isNotEmpty()) {
                        // Dead wins over everything: a dead UID overwrites
                        // even "eligible" — never alive, never page, just dead.
                        // A live UID never downgrades "eligible" back to
                        // "good" (eligible rows are never page-checked again).
                        val uid = effUid(r)
                        val alive = if (verdict == null) isValidUid(uid) else !verdict.contains(uid)
                        if (alive) valid++ else dead++
                        checks[r.rowIdx] = RowCheck(checkedAt = now, uidOk = alive)
                        if (batch != null) {
                            reqList(r.rowIdx).add(
                                batch.trace.toCheckReq(
                                    if (alive) "valid" else (batch.names[uid] ?: "dead")
                                )
                            )
                        }
                        if (alive) {
                            r.copy(status = if (r.status == "eligible") "eligible" else "good", dead = false)
                        } else {
                            r.copy(status = "bad", dead = true)
                        }
                    } else if (r.uid.isNotEmpty() && !isValidUid(r.uid)) {
                        dead++
                        checks[r.rowIdx] = RowCheck(checkedAt = now, uidOk = false)
                        r.copy(status = "bad", dead = true)
                    } else {
                        r.copy(status = if (r.status == "good" || r.status == "done") r.status else "pending")
                    }
                }
            }
            // 2. Page sweeps: direct per-row scrapes, sequential like the
            // worker (first 25 candidates per run to avoid rate limits).
            // Eligible rows are never swept again — once eligible, only a
            // dead UID verdict (above) can move them. With the UID check
            // off, fresh unchecked rows are swept directly so a new cookie
            // is still page-checked.
            if (isPageFile && (simpleOn || advancedOn)) {
                fun effUid(r: SheetRow): String = r.uid.ifEmpty {
                    Regex("c_user=(\\d+)").find(r.cookies)?.groupValues?.get(1) ?: ""
                }
                val cands = rows.filter { r ->
                    r.isData(cols) && !r.locked && !r.approved && !r.hold && !r.dead &&
                        "c_user=" in r.cookies && effUid(r).isNotEmpty() &&
                        (r.status == "good" || (!uidOn && (r.status.isEmpty() || r.status == "pending")))
                }.take(25)
                var updated = rows
                for (r in cands) {
                    try {
                        if (simpleOn) {
                            val (res, traces) = SheetChecker.pageSimple(r.cookies)
                            val base = checks.getOrPut(r.rowIdx) { RowCheck(checkedAt = now) }
                            checks[r.rowIdx] = base.copy(
                                checkedAt = now,
                                simplePage = res.pageName, simpleNumber = res.linkedNumber,
                                simpleError = res.error
                            )
                            for (t in traces) {
                                reqList(r.rowIdx).add(
                                    t.toCheckReq(
                                        res.pageName?.let { "Page \"$it\"" }
                                            ?: res.error ?: "No page"
                                    )
                                )
                            }
                            if (res.error == null && res.eligible) {
                                updated = updated.map {
                                    if (it.rowIdx == r.rowIdx) it.copy(status = "eligible") else it
                                }
                            }
                        } else {
                            val (res, traces) = SheetChecker.pageAdvanced(r.cookies)
                            val base = checks.getOrPut(r.rowIdx) { RowCheck(checkedAt = now) }
                            checks[r.rowIdx] = base.copy(
                                checkedAt = now,
                                advEligible = res.eligible,
                                advPage = res.pageName, advNumber = res.linkedNumber,
                                advBan = res.banReason, advError = res.error
                            )
                            for (t in traces) {
                                reqList(r.rowIdx).add(
                                    when (t.kind) {
                                        "graphql" -> t.toCheckReq(
                                            if (res.eligible) "Eligible"
                                            else (res.error ?: res.banReason ?: "Not eligible")
                                        )
                                        else -> t.toCheckReq(t.error ?: "Page shell")
                                    }
                                )
                            }
                            if (res.error == null && res.eligible) {
                                updated = updated.map {
                                    if (it.rowIdx == r.rowIdx) it.copy(status = "eligible") else it
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // Challenges/rate limits: leave the row, try next.
                    }
                }
                rows = updated
            }
            withContext(Dispatchers.IO) {
                pushUndo()
                persistRows(rows, "check")
                if (checks.isNotEmpty() || reqs.isNotEmpty()) {
                    db.tx { d -> db.saveCheckDetails(d, f.id, checks, reqs) }
                    openChecks.value = checks.mapValues { it.value }
                    openCheckReqs.value = reqs.mapValues { it.value.toList() }
                }
            }
            checking.value = false
            done(valid, dead)
        }
    }

    fun requestWithdraw(amount: Double, method: String, account: String): Boolean {
        val bal = db.walletBalance()
        if (amount <= 0 || amount > bal) return false
        if (account.trim().isEmpty()) return false
        val now = System.currentTimeMillis()
        val after = bal - amount
        val t = WalletTx(
            id = newFileId(), createdAt = now, type = "DEBIT", amount = amount,
            balanceAfter = after, title = "Withdrawal: $method", detail = maskAccount(account)
        )
        db.tx { d ->
            db.setWalletBalance(d, after)
            db.insertWalletTx(d, t)
            db.recordOp(d, "wallet", "withdraw")
        }
        refresh()
        return true
    }

    companion object {
        @Volatile
        private var instance: SheetStore? = null

        fun get(context: Context): SheetStore {
            return instance ?: synchronized(this) {
                instance ?: SheetStore(context).also { instance = it }
            }
        }
    }

    private fun rowsToJson(rows: List<SheetRow>): String {
        val a = JSONArray()
        for (r in rows) {
            a.put(
                JSONObject()
                    .put("cookies", r.cookies).put("twofakey", r.twofakey)
                    .put("uid", r.uid).put("status", r.status)
                    .put("hold", r.hold).put("approved", r.approved).put("dead", r.dead)
            )
        }
        return a.toString()
    }

    private fun rowsFromJson(data: String): List<SheetRow> {
        val out = mutableListOf<SheetRow>()
        val a = JSONArray(data)
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            out.add(
                SheetRow(
                    rowIdx = i, cookies = o.optString("cookies"),
                    twofakey = o.optString("twofakey"), uid = o.optString("uid"),
                    status = o.optString("status"), hold = o.optBoolean("hold"),
                    approved = o.optBoolean("approved"), dead = o.optBoolean("dead")
                )
            )
        }
        return out
    }
}

fun maskAccount(account: String): String {
    val a = account.trim()
    if (a.isEmpty()) return "-"
    if (a.length > 12) return "${a.take(6)}...${a.takeLast(4)}"
    if (a.length > 4) return ".... ${a.takeLast(4)}"
    return a
}
