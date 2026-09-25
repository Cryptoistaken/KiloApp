package net.typeblog.socks.util.sheet

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

private const val SHEET_HISTORY_LIMIT = 20

// Local-first SQLite store for the Sheet tab. This database is the source of
// truth: every mutation writes here first, so sheets survive offline use,
// crashes and app updates. Online sync (when added) only backs this up.
// Note: Android deletes app-private data on uninstall, so uninstall survival
// needs a SAF export copy or an online backup, never this DB alone.
class SheetDb(context: Context) : SQLiteOpenHelper(context, "sheet.db", null, 3) {
    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE files(id TEXT PRIMARY KEY, name TEXT NOT NULL, preset TEXT NOT NULL, password TEXT NOT NULL, archived INTEGER NOT NULL DEFAULT 0, deletedAt INTEGER NOT NULL DEFAULT 0, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, seq INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE rows(fileId TEXT NOT NULL, rowIdx INTEGER NOT NULL, cookies TEXT NOT NULL DEFAULT '', twofakey TEXT NOT NULL DEFAULT '', uid TEXT NOT NULL DEFAULT '', status TEXT NOT NULL DEFAULT '', hold INTEGER NOT NULL DEFAULT 0, approved INTEGER NOT NULL DEFAULT 0, dead INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(fileId, rowIdx))")
        db.execSQL("CREATE TABLE styles(fileId TEXT NOT NULL, rowIdx INTEGER NOT NULL, colKey TEXT NOT NULL, bg TEXT, color TEXT, bold INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(fileId, rowIdx, colKey))")
        db.execSQL("CREATE TABLE hidden_cols(fileId TEXT NOT NULL, colKey TEXT NOT NULL, PRIMARY KEY(fileId, colKey))")
        db.execSQL("CREATE TABLE journal(fileId TEXT NOT NULL, ts INTEGER NOT NULL, op TEXT NOT NULL)")
        db.execSQL("CREATE INDEX idx_journal_file ON journal(fileId, ts)")
        db.execSQL("CREATE TABLE snapshots(fileId TEXT NOT NULL, seq INTEGER NOT NULL, ts INTEGER NOT NULL, data TEXT NOT NULL, PRIMARY KEY(fileId, seq))")
        db.execSQL("CREATE TABLE wallet_kv(k TEXT PRIMARY KEY, v TEXT NOT NULL)")
        db.execSQL("CREATE TABLE wallet_tx(id TEXT PRIMARY KEY, createdAt INTEGER NOT NULL, type TEXT NOT NULL, amount REAL NOT NULL, balanceAfter REAL NOT NULL, title TEXT NOT NULL, detail TEXT)")
        db.execSQL("CREATE TABLE outbox(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, op TEXT NOT NULL)")
        db.execSQL("CREATE TABLE row_checks(fileId TEXT NOT NULL, rowIdx INTEGER NOT NULL, checkedAt INTEGER NOT NULL DEFAULT 0, uidOk INTEGER, uidError TEXT, simplePage TEXT, simpleNumber TEXT, simpleError TEXT, advEligible INTEGER NOT NULL DEFAULT 0, advPage TEXT, advNumber TEXT, advBan TEXT, advError TEXT, PRIMARY KEY(fileId, rowIdx))")
        db.execSQL("CREATE TABLE check_reqs(id INTEGER PRIMARY KEY AUTOINCREMENT, fileId TEXT NOT NULL, rowIdx INTEGER NOT NULL, seq INTEGER NOT NULL, kind TEXT NOT NULL, method TEXT NOT NULL, url TEXT NOT NULL, status INTEGER NOT NULL DEFAULT 0, durationMs INTEGER NOT NULL DEFAULT 0, reqNote TEXT, resNote TEXT, error TEXT, at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX idx_reqs_row ON check_reqs(fileId, rowIdx, seq)")
        db.execSQL("CREATE TABLE undo_hist(id INTEGER PRIMARY KEY AUTOINCREMENT, fileId TEXT NOT NULL, ts INTEGER NOT NULL, data TEXT NOT NULL)")
        db.execSQL("CREATE INDEX idx_undo_file ON undo_hist(fileId, id)")
        db.execSQL("CREATE TABLE redo_hist(id INTEGER PRIMARY KEY AUTOINCREMENT, fileId TEXT NOT NULL, ts INTEGER NOT NULL, data TEXT NOT NULL)")
        db.execSQL("CREATE INDEX idx_redo_file ON redo_hist(fileId, id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("CREATE TABLE IF NOT EXISTS row_checks(fileId TEXT NOT NULL, rowIdx INTEGER NOT NULL, checkedAt INTEGER NOT NULL DEFAULT 0, uidOk INTEGER, uidError TEXT, simplePage TEXT, simpleNumber TEXT, simpleError TEXT, advEligible INTEGER NOT NULL DEFAULT 0, advPage TEXT, advNumber TEXT, advBan TEXT, advError TEXT, PRIMARY KEY(fileId, rowIdx))")
            db.execSQL("CREATE TABLE IF NOT EXISTS check_reqs(id INTEGER PRIMARY KEY AUTOINCREMENT, fileId TEXT NOT NULL, rowIdx INTEGER NOT NULL, seq INTEGER NOT NULL, kind TEXT NOT NULL, method TEXT NOT NULL, url TEXT NOT NULL, status INTEGER NOT NULL DEFAULT 0, durationMs INTEGER NOT NULL DEFAULT 0, reqNote TEXT, resNote TEXT, error TEXT, at INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_reqs_row ON check_reqs(fileId, rowIdx, seq)")
        }
        if (oldVersion < 3) {
            db.execSQL("CREATE TABLE IF NOT EXISTS undo_hist(id INTEGER PRIMARY KEY AUTOINCREMENT, fileId TEXT NOT NULL, ts INTEGER NOT NULL, data TEXT NOT NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_undo_file ON undo_hist(fileId, id)")
            db.execSQL("CREATE TABLE IF NOT EXISTS redo_hist(id INTEGER PRIMARY KEY AUTOINCREMENT, fileId TEXT NOT NULL, ts INTEGER NOT NULL, data TEXT NOT NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_redo_file ON redo_hist(fileId, id)")
        }
    }

    private fun cv(vararg pairs: Pair<String, Any?>): ContentValues {
        val c = ContentValues()
        for ((k, v) in pairs) {
            when (v) {
                null -> c.putNull(k)
                is String -> c.put(k, v)
                is Boolean -> c.put(k, if (v) 1 else 0)
                is Int -> c.put(k, v)
                is Long -> c.put(k, v)
                is Double -> c.put(k, v)
                else -> c.put(k, v.toString())
            }
        }
        return c
    }

    fun tx(block: (SQLiteDatabase) -> Unit) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            block(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }


    fun insertFile(db: SQLiteDatabase, f: SheetFile) {
        db.insertOrThrow(
            "files", null,
            cv(
                "id" to f.id, "name" to f.name, "preset" to f.preset.name, "password" to f.password,
                "archived" to if (f.archived) 1 else 0, "deletedAt" to f.deletedAt,
                "createdAt" to f.createdAt, "updatedAt" to f.updatedAt, "seq" to f.seq
            )
        )
    }

    fun updateFile(db: SQLiteDatabase, f: SheetFile) {
        val updated = db.update(
            "files",
            cv(
                "name" to f.name, "preset" to f.preset.name, "password" to f.password,
                "archived" to if (f.archived) 1 else 0, "deletedAt" to f.deletedAt,
                "updatedAt" to f.updatedAt, "seq" to f.seq
            ),
            "id=?", arrayOf(f.id)
        )
        if (updated != 1) throw IllegalStateException("Sheet file update failed: ${f.id}")
    }

    fun listFiles(archived: Boolean): List<SheetFile> {
        val db = readableDatabase
        val out = mutableListOf<SheetFile>()
        db.rawQuery(
            "SELECT id,name,preset,password,archived,deletedAt,createdAt,updatedAt,seq FROM files WHERE archived=? ORDER BY updatedAt DESC",
            arrayOf(if (archived) "1" else "0")
        ).use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                out.add(
                    SheetFile(
                        id = id, name = c.getString(1),
                        preset = SheetPreset.of(c.getString(2)), password = c.getString(3),
                        archived = c.getInt(4) == 1, deletedAt = c.getLong(5),
                        createdAt = c.getLong(6), updatedAt = c.getLong(7), seq = c.getLong(8),
                        rowCount = countDataRows(id), liveCount = countRows(id, liveOnly = true),
                        deadCount = countRows(id, deadOnly = true),
                        dupCount = countDups(id), pageCount = countPage(id)
                    )
                )
            }
        }
        return out
    }

    // Backup dump: every file regardless of archived state, without the five
    // per-file COUNT subqueries listFiles runs. Counts are a UI concern; a
    // dump reads raw columns once.
    fun allFiles(): List<SheetFile> {
        val db = readableDatabase
        val out = mutableListOf<SheetFile>()
        db.rawQuery(
            "SELECT id,name,preset,password,archived,deletedAt,createdAt,updatedAt,seq FROM files",
            null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    SheetFile(
                        id = c.getString(0), name = c.getString(1),
                        preset = SheetPreset.of(c.getString(2)), password = c.getString(3),
                        archived = c.getInt(4) == 1, deletedAt = c.getLong(5),
                        createdAt = c.getLong(6), updatedAt = c.getLong(7), seq = c.getLong(8)
                    )
                )
            }
        }
        return out
    }

    // Load-backup: drop every row of every sheet-owned table so a restore
    // cannot merge into leftovers from the previous install. The same list
    // deleteFileAll walks per file; keep the two in sync.
    fun clearAllSheets(db: SQLiteDatabase) {
        for (t in listOf(
            "rows", "styles", "hidden_cols", "row_checks", "check_reqs",
            "undo_hist", "redo_hist", "journal", "snapshots",
            "wallet_kv", "wallet_tx", "outbox", "files"
        )) {
            db.delete(t, null, null)
        }
    }

    fun getFile(id: String): SheetFile? {
        readableDatabase.rawQuery(
            "SELECT id,name,preset,password,archived,deletedAt,createdAt,updatedAt,seq FROM files WHERE id=?",
            arrayOf(id)
        ).use { c ->
            if (!c.moveToFirst()) return null
            return SheetFile(
                id = c.getString(0), name = c.getString(1),
                preset = SheetPreset.of(c.getString(2)), password = c.getString(3),
                archived = c.getInt(4) == 1, deletedAt = c.getLong(5),
                createdAt = c.getLong(6), updatedAt = c.getLong(7), seq = c.getLong(8)
            )
        }
    }

    fun deleteFileAll(db: SQLiteDatabase, id: String) {
        db.delete("rows", "fileId=?", arrayOf(id))
        db.delete("row_checks", "fileId=?", arrayOf(id))
        db.delete("check_reqs", "fileId=?", arrayOf(id))
        db.delete("undo_hist", "fileId=?", arrayOf(id))
        db.delete("redo_hist", "fileId=?", arrayOf(id))
        db.delete("styles", "fileId=?", arrayOf(id))
        db.delete("hidden_cols", "fileId=?", arrayOf(id))
        db.delete("journal", "fileId=?", arrayOf(id))
        db.delete("snapshots", "fileId=?", arrayOf(id))
        db.delete("files", "id=?", arrayOf(id))
    }

    fun loadRows(fileId: String): List<SheetRow> {
        val out = mutableListOf<SheetRow>()
        readableDatabase.rawQuery(
            "SELECT rowIdx,cookies,twofakey,uid,status,hold,approved,dead FROM rows WHERE fileId=? ORDER BY rowIdx",
            arrayOf(fileId)
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    SheetRow(
                        rowIdx = c.getInt(0), cookies = c.getString(1) ?: "",
                        twofakey = c.getString(2) ?: "", uid = c.getString(3) ?: "",
                        status = c.getString(4) ?: "", hold = c.getInt(5) == 1,
                        approved = c.getInt(6) == 1, dead = c.getInt(7) == 1
                    )
                )
            }
        }
        return out
    }

    fun saveAllRows(db: SQLiteDatabase, fileId: String, rows: List<SheetRow>) {
        db.delete("rows", "fileId=?", arrayOf(fileId))
        for (row in meaningfulSheetRows(rows)) {
            db.insertOrThrow(
                "rows", null,
                cv(
                    "fileId" to fileId, "rowIdx" to row.rowIdx, "cookies" to row.cookies,
                    "twofakey" to row.twofakey, "uid" to row.uid, "status" to row.status,
                    "hold" to if (row.hold) 1 else 0, "approved" to if (row.approved) 1 else 0,
                    "dead" to if (row.dead) 1 else 0
                )
            )
        }
    }

    // Cross-file duplicates: open-file (rowIdx, colKey) cells whose uid,
    // cookies or 2fa key also occur in any OTHER file. Same-file repeats
    // are blocked at entry. Kept for detail use; the grid never paints
    // them and the file indicator counts rows via countDups.
    fun crossDupCells(excludeFileId: String, rows: List<SheetRow>): Set<Pair<Int, String>> {
        val byCol = mapOf(
            "uid" to rows.mapNotNull { it.uid.ifEmpty { null } }.toSet(),
            "cookies" to rows.mapNotNull { it.cookies.ifEmpty { null } }.toSet(),
            "twofakey" to rows.mapNotNull { value ->
                value.twofakey.takeIf { it.isNotEmpty() && !isNo2Fa(it) }
            }.toSet()
        )
        if (byCol.values.all { it.isEmpty() }) return emptySet()
        val hits = mutableMapOf<String, MutableSet<String>>()
        for ((col, values) in byCol) {
            if (values.isEmpty()) continue
            val out = hits.getOrPut(col) { mutableSetOf() }
            // SQLite bind-variable limit: chunk the IN lists.
            for (chunk in values.chunked(400)) {
                val q = chunk.joinToString(",") { "?" }
                readableDatabase.rawQuery(
                    "SELECT DISTINCT $col FROM rows WHERE fileId != ? AND $col IN ($q)",
                    arrayOf(excludeFileId) + chunk.toTypedArray()
                ).use { c ->
                    while (c.moveToNext()) {
                        c.getString(0)?.let { out.add(it) }
                    }
                }
            }
        }
        if (hits.values.all { it.isEmpty() }) return emptySet()
        return buildSet {
            for (r in rows) {
                if (r.uid in (hits["uid"] ?: emptySet())) add(Pair(r.rowIdx, "uid"))
                if (r.cookies in (hits["cookies"] ?: emptySet())) add(Pair(r.rowIdx, "cookies"))
                if (r.twofakey in (hits["twofakey"] ?: emptySet())) add(Pair(r.rowIdx, "twofakey"))
            }
        }
    }

    // Check records behind the dot popup. A run replaces the file's
    // records wholesale; row edits drop single rows (see store).
    fun saveCheckDetails(
        db: SQLiteDatabase,
        fileId: String,
        checks: Map<Int, RowCheck>,
        reqs: Map<Int, List<CheckReq>>
    ) {
        db.delete("row_checks", "fileId=?", arrayOf(fileId))
        db.delete("check_reqs", "fileId=?", arrayOf(fileId))
        for ((ri, c) in checks) {
            db.insertOrThrow(
                "row_checks", null,
                cv(
                    "fileId" to fileId, "rowIdx" to ri, "checkedAt" to c.checkedAt,
                    "uidOk" to c.uidOk, "uidError" to c.uidError,
                    "simplePage" to c.simplePage, "simpleNumber" to c.simpleNumber,
                    "simpleError" to c.simpleError,
                    "advEligible" to if (c.advEligible) 1 else 0, "advPage" to c.advPage,
                    "advNumber" to c.advNumber, "advBan" to c.advBan, "advError" to c.advError
                )
            )
        }
        for ((ri, list) in reqs) {
            for ((i, q) in list.withIndex()) {
                db.insertOrThrow(
                    "check_reqs", null,
                    cv(
                        "fileId" to fileId, "rowIdx" to ri, "seq" to i,
                        "kind" to q.kind, "method" to q.method, "url" to q.url,
                        "status" to q.status, "durationMs" to q.durationMs,
                        "reqNote" to q.reqNote, "resNote" to q.resNote,
                        "error" to q.error, "at" to q.at
                    )
                )
            }
        }
    }

    fun loadRowChecks(fileId: String): Map<Int, RowCheck> {
        val out = mutableMapOf<Int, RowCheck>()
        readableDatabase.rawQuery(
            "SELECT rowIdx,checkedAt,uidOk,uidError,simplePage,simpleNumber,simpleError,advEligible,advPage,advNumber,advBan,advError FROM row_checks WHERE fileId=?",
            arrayOf(fileId)
        ).use { c ->
            while (c.moveToNext()) {
                out[c.getInt(0)] = RowCheck(
                    checkedAt = c.getLong(1),
                    uidOk = if (c.isNull(2)) null else c.getInt(2) == 1,
                    uidError = if (c.isNull(3)) null else c.getString(3),
                    simplePage = if (c.isNull(4)) null else c.getString(4),
                    simpleNumber = if (c.isNull(5)) null else c.getString(5),
                    simpleError = if (c.isNull(6)) null else c.getString(6),
                    advEligible = c.getInt(7) == 1,
                    advPage = if (c.isNull(8)) null else c.getString(8),
                    advNumber = if (c.isNull(9)) null else c.getString(9),
                    advBan = if (c.isNull(10)) null else c.getString(10),
                    advError = if (c.isNull(11)) null else c.getString(11)
                )
            }
        }
        return out
    }

    fun loadCheckReqs(fileId: String): Map<Int, List<CheckReq>> {
        val out = mutableMapOf<Int, MutableList<CheckReq>>()
        readableDatabase.rawQuery(
            "SELECT rowIdx,kind,method,url,status,durationMs,reqNote,resNote,error,at FROM check_reqs WHERE fileId=? ORDER BY rowIdx,seq",
            arrayOf(fileId)
        ).use { c ->
            while (c.moveToNext()) {
                out.getOrPut(c.getInt(0)) { mutableListOf() }.add(
                    CheckReq(
                        kind = c.getString(1) ?: "", method = c.getString(2) ?: "",
                        url = c.getString(3) ?: "", status = c.getInt(4),
                        durationMs = c.getLong(5),
                        reqNote = if (c.isNull(6)) null else c.getString(6),
                        resNote = if (c.isNull(7)) null else c.getString(7),
                        error = if (c.isNull(8)) null else c.getString(8),
                        at = c.getLong(9)
                    )
                )
            }
        }
        return out
    }

    fun deleteRowCheckData(db: SQLiteDatabase, fileId: String, rows: Set<Int>) {
        if (rows.isEmpty()) return
        // Small sets: one statement per row keeps the SQL static.
        for (ri in rows) {
            db.delete("row_checks", "fileId=? AND rowIdx=?", arrayOf(fileId, ri.toString()))
            db.delete("check_reqs", "fileId=? AND rowIdx=?", arrayOf(fileId, ri.toString()))
        }
    }

    fun clearCheckData(db: SQLiteDatabase, fileId: String) {
        db.delete("row_checks", "fileId=?", arrayOf(fileId))
        db.delete("check_reqs", "fileId=?", arrayOf(fileId))
    }

    // Duplicates tab: other files holding this row's values, with names.
    // at = the other file's row check time (0 when never checked).
    fun dupSources(fileId: String, row: SheetRow): List<DupSource> {
        val out = mutableListOf<DupSource>()
        fun query(col: String, value: String, field: String) {
            if (value.isEmpty() || (col == "twofakey" && isNo2Fa(value))) return
            readableDatabase.rawQuery(
                "SELECT f.name, o.rowIdx, r.checkedAt FROM rows o JOIN files f ON f.id=o.fileId " +
                        "LEFT JOIN row_checks r ON r.fileId=o.fileId AND r.rowIdx=o.rowIdx " +
                        "WHERE o.fileId != ? AND o.$col = ? ORDER BY f.name, o.rowIdx LIMIT 20",
                arrayOf(fileId, value)
            ).use { c ->
                while (c.moveToNext()) {
                    out.add(
                        DupSource(
                            c.getString(0) ?: "", c.getInt(1) + 1, field,
                            if (c.isNull(2)) 0 else c.getLong(2)
                        )
                    )
                }
            }
        }
        query("uid", row.uid, "uid")
        query("cookies", row.cookies, "cookie")
        query("twofakey", row.twofakey, "2fa")
        return out
    }

    // Inspector Dup tab: every value in this file that also exists in
    // another file, with the local row, the other file/row and its check
    // time. Distinct values are chunked under the SQLite bind limit and
    // the total is capped so huge files stay fast.
    fun fileDups(fileId: String, rows: List<SheetRow>): List<net.typeblog.socks.util.sheet.FileDup> {
        val out = mutableListOf<net.typeblog.socks.util.sheet.FileDup>()
        fun query(col: String, field: String, localOf: (SheetRow) -> String) {
            val localRows = mutableMapOf<String, Int>()
            for (r in rows) {
                val v = localOf(r)
                if (v.isNotEmpty() && !(col == "twofakey" && isNo2Fa(v)) && !localRows.containsKey(v)) {
                    localRows[v] = r.rowIdx + 1
                }
            }
            if (localRows.isEmpty()) return
            for (chunk in localRows.keys.chunked(400)) {
                if (out.size >= 200) return
                val q = chunk.joinToString(",") { "?" }
                readableDatabase.rawQuery(
                    "SELECT o.$col, f.name, o.rowIdx, r.checkedAt FROM rows o JOIN files f ON f.id=o.fileId " +
                            "LEFT JOIN row_checks r ON r.fileId=o.fileId AND r.rowIdx=o.rowIdx " +
                            "WHERE o.fileId != ? AND o.$col IN ($q) ORDER BY f.name, o.rowIdx LIMIT 200",
                    arrayOf(fileId) + chunk.toTypedArray()
                ).use { c ->
                    while (c.moveToNext() && out.size < 200) {
                        val v = c.getString(0) ?: continue
                        out.add(
                            net.typeblog.socks.util.sheet.FileDup(
                                field, c.getString(1) ?: "", c.getInt(2) + 1,
                                localRows[v] ?: 0,
                                if (c.isNull(3)) 0 else c.getLong(3)
                            )
                        )
                    }
                }
            }
        }
        query("uid", "uid") { it.uid }
        query("cookies", "cookie") { it.cookies }
        query("twofakey", "2fa") { it.twofakey }
        return out
    }

    fun loadStyles(fileId: String): Map<String, CellStyle> {
        val out = mutableMapOf<String, CellStyle>()
        readableDatabase.rawQuery(
            "SELECT rowIdx,colKey,bg,color,bold FROM styles WHERE fileId=?", arrayOf(fileId)
        ).use { c ->
            while (c.moveToNext()) {
                out["${c.getInt(0)}:${c.getString(1)}"] =
                    CellStyle(c.getString(2), c.getString(3), c.getInt(4) == 1)
            }
        }
        return out
    }

    fun saveStyle(db: SQLiteDatabase, fileId: String, rowIdx: Int, colKey: String, s: CellStyle?) {
        if (s == null || (s.bg == null && s.color == null && !s.bold)) {
            db.delete("styles", "fileId=? AND rowIdx=? AND colKey=?", arrayOf(fileId, rowIdx.toString(), colKey))
        } else {
            val inserted = db.insertWithOnConflict(
                "styles", null,
                cv(
                    "fileId" to fileId, "rowIdx" to rowIdx, "colKey" to colKey,
                    "bg" to s.bg, "color" to s.color, "bold" to if (s.bold) 1 else 0
                ),
                SQLiteDatabase.CONFLICT_REPLACE
            )
            if (inserted == -1L) throw IllegalStateException("Sheet style insert failed: $fileId/$rowIdx/$colKey")
        }
    }

    fun loadHidden(fileId: String): Set<String> {
        val out = mutableSetOf<String>()
        readableDatabase.rawQuery("SELECT colKey FROM hidden_cols WHERE fileId=?", arrayOf(fileId)).use { c ->
            while (c.moveToNext()) out.add(c.getString(0))
        }
        return out
    }

    fun saveHidden(db: SQLiteDatabase, fileId: String, hidden: Set<String>) {
        db.delete("hidden_cols", "fileId=?", arrayOf(fileId))
        for (k in hidden) db.insertOrThrow("hidden_cols", null, cv("fileId" to fileId, "colKey" to k))
    }


    // Persistent undo/redo: pre/post row states per file, survives app
    // restarts. Memory stacks mirror these tables while a file is open.
    fun insertUndo(db: SQLiteDatabase, fileId: String, data: String) {
        db.insertOrThrow("undo_hist", null, cv("fileId" to fileId, "ts" to System.currentTimeMillis(), "data" to data))
        db.execSQL(
            "DELETE FROM undo_hist WHERE fileId=? AND id NOT IN (SELECT id FROM undo_hist WHERE fileId=? ORDER BY id DESC LIMIT $SHEET_HISTORY_LIMIT)",
            arrayOf(fileId, fileId)
        )
    }

    fun loadUndoStack(fileId: String, limit: Int = SHEET_HISTORY_LIMIT): List<String> {
        val bounded = limit.coerceIn(0, SHEET_HISTORY_LIMIT)
        if (bounded == 0) return emptyList()
        val newestFirst = mutableListOf<String>()
        readableDatabase.rawQuery(
            "SELECT data FROM undo_hist WHERE fileId=? ORDER BY id DESC LIMIT $bounded", arrayOf(fileId)
        ).use { c ->
            while (c.moveToNext()) newestFirst.add(c.getString(0) ?: "")
        }
        return newestFirst.asReversed()
    }

    fun popUndo(db: SQLiteDatabase, fileId: String) {
        db.execSQL(
            "DELETE FROM undo_hist WHERE id=(SELECT id FROM undo_hist WHERE fileId=? ORDER BY id DESC LIMIT 1)",
            arrayOf(fileId)
        )
    }

    fun insertRedo(db: SQLiteDatabase, fileId: String, data: String) {
        db.insertOrThrow("redo_hist", null, cv("fileId" to fileId, "ts" to System.currentTimeMillis(), "data" to data))
        db.execSQL(
            "DELETE FROM redo_hist WHERE fileId=? AND id NOT IN (SELECT id FROM redo_hist WHERE fileId=? ORDER BY id DESC LIMIT $SHEET_HISTORY_LIMIT)",
            arrayOf(fileId, fileId)
        )
    }

    fun loadRedoStack(fileId: String, limit: Int = SHEET_HISTORY_LIMIT): List<String> {
        val bounded = limit.coerceIn(0, SHEET_HISTORY_LIMIT)
        if (bounded == 0) return emptyList()
        val newestFirst = mutableListOf<String>()
        readableDatabase.rawQuery(
            "SELECT data FROM redo_hist WHERE fileId=? ORDER BY id DESC LIMIT $bounded", arrayOf(fileId)
        ).use { c ->
            while (c.moveToNext()) newestFirst.add(c.getString(0) ?: "")
        }
        return newestFirst.asReversed()
    }

    fun popRedo(db: SQLiteDatabase, fileId: String) {
        db.execSQL(
            "DELETE FROM redo_hist WHERE id=(SELECT id FROM redo_hist WHERE fileId=? ORDER BY id DESC LIMIT 1)",
            arrayOf(fileId)
        )
    }

    fun clearRedo(db: SQLiteDatabase, fileId: String) {
        db.delete("redo_hist", "fileId=?", arrayOf(fileId))
    }

    fun walletBalance(): Double {
        readableDatabase.rawQuery("SELECT v FROM wallet_kv WHERE k='balance'", null).use { c ->
            if (!c.moveToFirst()) return 0.0
            return c.getString(0).toDoubleOrNull() ?: 0.0
        }
    }

    fun setWalletBalance(db: SQLiteDatabase, v: Double) {
        val inserted = db.insertWithOnConflict(
            "wallet_kv", null, cv("k" to "balance", "v" to v.toString()),
            SQLiteDatabase.CONFLICT_REPLACE
        )
        if (inserted == -1L) throw IllegalStateException("Wallet balance write failed")
    }

    fun walletTxs(): List<WalletTx> {
        val out = mutableListOf<WalletTx>()
        readableDatabase.rawQuery(
            "SELECT id,createdAt,type,amount,balanceAfter,title,detail FROM wallet_tx ORDER BY createdAt DESC LIMIT 200",
            null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    WalletTx(
                        id = c.getString(0), createdAt = c.getLong(1), type = c.getString(2),
                        amount = c.getDouble(3), balanceAfter = c.getDouble(4),
                        title = c.getString(5), detail = c.getString(6)
                    )
                )
            }
        }
        return out
    }

    fun insertWalletTx(db: SQLiteDatabase, t: WalletTx) {
        db.insertOrThrow(
            "wallet_tx", null,
            cv(
                "id" to t.id, "createdAt" to t.createdAt, "type" to t.type,
                "amount" to t.amount, "balanceAfter" to t.balanceAfter,
                "title" to t.title, "detail" to t.detail
            )
        )
    }

    private fun countDataRows(fileId: String): Int {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM rows WHERE fileId=? AND (cookies<>'' OR twofakey<>'' OR uid<>'' OR status<>'')",
            arrayOf(fileId)
        ).use { c -> return if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    private fun countRows(fileId: String, liveOnly: Boolean = false, deadOnly: Boolean = false): Int {
        val where = when {
            liveOnly -> "AND (status='good' OR status='done')"
            deadOnly -> "AND (status='bad' OR dead=1)"
            else -> ""
        }
        readableDatabase.rawQuery("SELECT COUNT(*) FROM rows WHERE fileId=? $where", arrayOf(fileId)).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    // Cross-file duplicate rows: rows of this file whose uid, cookie
    // or 2fa key also occurs in any OTHER file. In-file repeats are
    // blocked at entry, so the file indicator only ever counts cross
    // collisions; the grid itself never paints them.
    private fun countDups(fileId: String): Int {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM rows r WHERE fileId=? AND (" +
                    "(uid<>'' AND EXISTS (SELECT 1 FROM rows o WHERE o.fileId != r.fileId AND o.uid<>'' AND o.uid = r.uid)) OR " +
                    "(cookies<>'' AND EXISTS (SELECT 1 FROM rows o WHERE o.fileId != r.fileId AND o.cookies<>'' AND o.cookies = r.cookies)) OR " +
                    "(r.twofakey NOT IN ('', 'No_2Fa') AND EXISTS (SELECT 1 FROM rows o WHERE o.fileId != r.fileId AND o.twofakey NOT IN ('', 'No_2Fa') AND o.twofakey = r.twofakey))" +
                    ")",
            arrayOf(fileId)
        ).use { c -> return if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    private fun countPage(fileId: String): Int {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM rows WHERE fileId=? AND status='eligible'", arrayOf(fileId)
        ).use { c -> return if (c.moveToFirst()) c.getInt(0) else 0 }
    }
}
