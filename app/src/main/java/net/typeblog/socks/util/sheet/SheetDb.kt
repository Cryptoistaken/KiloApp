package net.typeblog.socks.util.sheet

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

// Local-first SQLite store for the Sheet tab. This database is the source of
// truth: every mutation writes here first, so sheets survive offline use,
// crashes and app updates. Online sync (when added) only backs this up.
// Note: Android deletes app-private data on uninstall, so uninstall survival
// needs a SAF export copy or an online backup, never this DB alone.
class SheetDb(context: Context) : SQLiteOpenHelper(context, "sheet.db", null, 1) {

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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    }

    private fun cv(vararg pairs: Pair<String, Any?>): ContentValues {
        val c = ContentValues()
        for ((k, v) in pairs) {
            when (v) {
                null -> c.putNull(k)
                is String -> c.put(k, v)
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

    fun recordOp(db: SQLiteDatabase, fileId: String, op: String) {
        db.insert("outbox", null, cv("ts" to System.currentTimeMillis(), "op" to op))
        db.insert("journal", null, cv("fileId" to fileId, "ts" to System.currentTimeMillis(), "op" to op))
        db.execSQL("DELETE FROM journal WHERE fileId=? AND rowid NOT IN (SELECT rowid FROM journal WHERE fileId=? ORDER BY ts DESC LIMIT 200)", arrayOf(fileId, fileId))
    }

    fun insertFile(db: SQLiteDatabase, f: SheetFile) {
        db.insertOrThrow(
            "files", null,
            cv("id" to f.id, "name" to f.name, "preset" to f.preset.name, "password" to f.password,
                "archived" to if (f.archived) 1 else 0, "deletedAt" to f.deletedAt,
                "createdAt" to f.createdAt, "updatedAt" to f.updatedAt, "seq" to f.seq)
        )
    }

    fun updateFile(db: SQLiteDatabase, f: SheetFile) {
        db.update(
            "files",
            cv("name" to f.name, "preset" to f.preset.name, "password" to f.password,
                "archived" to if (f.archived) 1 else 0, "deletedAt" to f.deletedAt,
                "updatedAt" to f.updatedAt, "seq" to f.seq),
            "id=?", arrayOf(f.id)
        )
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
        for (r in rows) {
            db.insert(
                "rows", null,
                cv("fileId" to fileId, "rowIdx" to r.rowIdx, "cookies" to r.cookies,
                    "twofakey" to r.twofakey, "uid" to r.uid, "status" to r.status,
                    "hold" to if (r.hold) 1 else 0, "approved" to if (r.approved) 1 else 0,
                    "dead" to if (r.dead) 1 else 0)
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
            "twofakey" to rows.mapNotNull { it.twofakey.ifEmpty { null } }.toSet()
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
            db.insertWithOnConflict(
                "styles", null,
                cv("fileId" to fileId, "rowIdx" to rowIdx, "colKey" to colKey,
                    "bg" to s.bg, "color" to s.color, "bold" to if (s.bold) 1 else 0),
                SQLiteDatabase.CONFLICT_REPLACE
            )
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
        for (k in hidden) db.insert("hidden_cols", null, cv("fileId" to fileId, "colKey" to k))
    }

    fun saveSnapshot(db: SQLiteDatabase, fileId: String, seq: Long, data: String) {
        db.insertWithOnConflict(
            "snapshots", null,
            cv("fileId" to fileId, "seq" to seq, "ts" to System.currentTimeMillis(), "data" to data),
            SQLiteDatabase.CONFLICT_REPLACE
        )
        db.execSQL(
            "DELETE FROM snapshots WHERE fileId=? AND seq NOT IN (SELECT seq FROM snapshots WHERE fileId=? ORDER BY seq DESC LIMIT 3)",
            arrayOf(fileId, fileId)
        )
    }

    fun latestSnapshot(fileId: String): Pair<Long, String>? {
        readableDatabase.rawQuery(
            "SELECT seq,data FROM snapshots WHERE fileId=? ORDER BY seq DESC LIMIT 1", arrayOf(fileId)
        ).use { c ->
            if (!c.moveToFirst()) return null
            return c.getLong(0) to c.getString(1)
        }
    }

    fun walletBalance(): Double {
        readableDatabase.rawQuery("SELECT v FROM wallet_kv WHERE k='balance'", null).use { c ->
            if (!c.moveToFirst()) return 0.0
            return c.getString(0).toDoubleOrNull() ?: 0.0
        }
    }

    fun setWalletBalance(db: SQLiteDatabase, v: Double) {
        db.insertWithOnConflict(
            "wallet_kv", null, cv("k" to "balance", "v" to v.toString()),
            SQLiteDatabase.CONFLICT_REPLACE
        )
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
            cv("id" to t.id, "createdAt" to t.createdAt, "type" to t.type,
                "amount" to t.amount, "balanceAfter" to t.balanceAfter,
                "title" to t.title, "detail" to t.detail)
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
                "(twofakey<>'' AND EXISTS (SELECT 1 FROM rows o WHERE o.fileId != r.fileId AND o.twofakey<>'' AND o.twofakey = r.twofakey))" +
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
