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
// file and memory-only, matching the website behavior.
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
        undoStack.addLast(openRows.value.map { it.copy() })
        if (undoStack.size > 50) undoStack.removeFirst()
        redoStack.clear()
        canUndo.value = undoStack.isNotEmpty()
        canRedo.value = false
    }

    fun undo(): Boolean {
        val prev = undoStack.removeLastOrNull() ?: return false
        redoStack.addLast(openRows.value.map { it.copy() })
        persistRows(prev, "undo")
        canUndo.value = undoStack.isNotEmpty()
        canRedo.value = true
        return true
    }

    fun redo(): Boolean {
        val next = redoStack.removeLastOrNull() ?: return false
        undoStack.addLast(openRows.value.map { it.copy() })
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
        undoStack.clear()
        redoStack.clear()
        canUndo.value = false
        canRedo.value = false
        openFile.value = f
        openRows.value = db.loadRows(id).ifEmpty { emptyPad(f.preset) }
        openStyles.value = db.loadStyles(id)
        openHidden.value = db.loadHidden(id)
        return true
    }

    fun closeFile() {
        openFile.value = null
        openRows.value = emptyList()
        openStyles.value = emptyMap()
        openHidden.value = emptySet()
        undoStack.clear()
        redoStack.clear()
        canUndo.value = false
        canRedo.value = false
    }

    private fun emptyPad(preset: SheetPreset, n: Int = 100): List<SheetRow> {
        val cols = preset.columns
        return List(n) { i -> SheetRow(rowIdx = i).let { r -> if (cols.isEmpty()) r else r } }
    }

    private fun topUp(rows: List<SheetRow>, preset: SheetPreset): List<SheetRow> {
        val lastData = rows.indexOfLast { it.isData(preset.columns) }
        val want = (lastData + 51).coerceAtLeast(100)
        if (rows.size >= want) return rows
        return rows + (rows.size until want).map { SheetRow(rowIdx = it) }
    }

    private fun persistRows(rows: List<SheetRow>, op: String) {
        val f = openFile.value ?: return
        val now = System.currentTimeMillis()
        val seq = f.seq + 1
        val snapshot = rowsToJson(rows)
        db.tx { d ->
            db.saveAllRows(d, f.id, rows)
            db.saveSnapshot(d, f.id, seq, snapshot)
            db.updateFile(d, f.copy(updatedAt = now, seq = seq))
            db.recordOp(d, f.id, op)
        }
        openFile.value = db.getFile(f.id)
        openRows.value = rows
        refresh()
    }

    fun setCell(rowIdx: Int, colKey: String, value: String): Boolean {
        val f = openFile.value ?: return false
        val rows = openRows.value.toMutableList()
        if (rowIdx !in rows.indices) return false
        val cur = rows[rowIdx]
        if (cur.locked) return false
        if (cur.cell(colKey) == value) return true
        // Same-file duplicate guard on identity cells.
        if ((colKey == "uid" || colKey == "cookies") && value.isNotEmpty()) {
            val dup = rows.any { it.rowIdx != rowIdx && it.cell(colKey) == value }
            if (dup) return false
        }
        pushUndo()
        rows[rowIdx] = cur.withCell(colKey, value)
        persistRows(topUp(rows, f.preset), "edit")
        return true
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
            if (rows[ri].cell(ck).isNotEmpty()) {
                rows[ri] = rows[ri].withCell(ck, "")
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
            // 1. UID liveness: one batched direct request (worker checkUids).
            // Falls back to the local format heuristic when offline.
            if (uidOn) {
                fun effUid(r: SheetRow): String = r.uid.ifEmpty {
                    Regex("c_user=(\\d+)").find(r.cookies)?.groupValues?.get(1) ?: ""
                }
                val verdict: Set<String>? = try {
                    SheetChecker.checkUidsDead(
                        rows.filter { r -> r.isData(cols) && !r.locked && effUid(r).isNotEmpty() }
                            .map { effUid(it) }.distinct()
                    )
                } catch (e: Exception) {
                    null
                }
                rows = rows.map { r ->
                    if (!r.isData(cols) || r.locked) {
                        r
                    } else if (r.cookies.isNotEmpty() && effUid(r).isNotEmpty()) {
                        if (verdict == null) {
                            if (isValidUid(effUid(r))) {
                                valid++
                                r.copy(status = "good", dead = false)
                            } else {
                                dead++
                                r.copy(status = "bad", dead = true)
                            }
                        } else if (!verdict.contains(effUid(r))) {
                            valid++
                            r.copy(status = "good", dead = false)
                        } else {
                            dead++
                            r.copy(status = "bad", dead = true)
                        }
                    } else if (r.uid.isNotEmpty() && !isValidUid(r.uid)) {
                        dead++
                        r.copy(status = "bad", dead = true)
                    } else {
                        r.copy(status = if (r.status == "good" || r.status == "done") r.status else "pending")
                    }
                }
            }
            // 2. Page sweeps: direct per-row scrapes, sequential like the
            // worker (first 25 candidates per run to avoid rate limits).
            if (isPageFile && (simpleOn || advancedOn)) {
                val cands = rows.filter { r ->
                    r.isData(cols) && !r.locked && r.status == "good" &&
                        "c_user=" in r.cookies && !r.approved && !r.hold && !r.dead
                }.take(25)
                var updated = rows
                for (r in cands) {
                    try {
                        val ok = if (simpleOn) {
                            val res = SheetChecker.pageSimple(r.cookies)
                            res.error == null && res.eligible
                        } else {
                            val res = SheetChecker.pageAdvanced(r.cookies)
                            res.error == null && res.eligible
                        }
                        if (ok) {
                            updated = updated.map {
                                if (it.rowIdx == r.rowIdx) it.copy(status = "eligible") else it
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
            }
            checking.value = false
            done(valid, dead)
        }
    }

    fun dupRows(): Set<Int> {
        val seen = mutableMapOf<String, Int>()
        val dups = mutableSetOf<Int>()
        for (r in openRows.value) {
            for (key in listOf("uid", "cookies")) {
                val v = r.cell(key)
                if (v.isEmpty()) continue
                val first = seen.putIfAbsent("$key:$v", r.rowIdx)
                if (first != null) {
                    dups.add(first)
                    dups.add(r.rowIdx)
                }
            }
        }
        return dups
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
