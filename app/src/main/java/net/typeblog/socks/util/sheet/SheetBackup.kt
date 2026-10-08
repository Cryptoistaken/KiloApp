package net.typeblog.socks.util.sheet

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import androidx.preference.PreferenceManager
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import net.typeblog.socks.util.Constants
import net.typeblog.socks.util.Constants.PREF_BACKUP_DIR
import net.typeblog.socks.util.Constants.PREF_BACKUP_ENABLED
import net.typeblog.socks.util.Constants.PREF_BACKUP_LAST_AT
import net.typeblog.socks.util.Constants.PREF_BACKUP_LAST_ERROR
import net.typeblog.socks.util.Constants.PREF_BACKUP_LAST_SIZE
import net.typeblog.socks.util.Constants.PREF_BACKUP_XLSX
import net.typeblog.socks.util.ProfileEntry
import net.typeblog.socks.util.ProfileManager

/**
 * Full-app backup. One file is the whole restore story:
 *
 *   backup/backup.db   sheet.db plus two stamped tables (backup_meta,
 *                      backup_prefs: allowlisted app prefs, sms prefs and
 *                      decrypted proxy profiles). The ONLY restore source.
 *
 * Human copies, never restore sources:
 *
 *   files/<Name>.xlsx     one active Sheet file, same bytes as Download
 *   archive/<Name>.xlsx   one archived Sheet file, same bytes as Download
 *   profiles/<Name>.xlsx  one proxy string per profile (server:port:user:pass)
 *
 * Both live outside the app sandbox: MediaStore Downloads survives uninstall
 * and clear-data (API 29+), and a user-picked SAF folder can sit on an SD
 * card or cloud drive. Drive mirrors the same set.
 */
object SheetBackup {
    const val DB_NAME = "backup.db"
    const val DOWNLOAD_SUBDIR = "KiloApp"

    // Layout inside the KiloApp folder: backup/ restores everything, the
    // rest is grouped by kind so the folder stays browsable.
    private const val DIR_BACKUP = "backup"
    private const val DIR_FILES = "files"
    private const val DIR_ARCHIVE = "archive"
    private const val DIR_PROFILES = "profiles"
    private const val REL_DB = DIR_BACKUP + "/backup.db"

    private const val TAG = "SheetBackup"
    private const val DEBOUNCE_MS = 10_000L
    private const val JSON_MIME = "application/json"
    private const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

    // ── Snapshot ────────────────────────────────────────────────────────────

    /** One consistent read of every sheet-owned table. Reuses the live model
     *  types on purpose: a parallel set of backup-only classes would be a
     *  second definition of "a row" free to drift from the first. */
    data class BackupSnapshot(
        val at: Long,
        val files: List<SheetFile>,
        // fileId -> rows, styles ("rowIdx:colKey"), hidden cols, checks, reqs
        val rows: Map<String, List<SheetRow>>,
        val styles: Map<String, Map<String, CellStyle>>,
        val hidden: Map<String, Set<String>>,
        val checks: Map<String, Map<Int, RowCheck>>,
        val reqs: Map<String, Map<Int, List<CheckReq>>>,
        val balance: Double,
        val txs: List<WalletTx>,
        // Decrypted profile settings. These live outside sheet.db in the
        // Keystore-encrypted prefs, which platform backup cannot carry, so
        // they ride along in the mirror instead.
        val profiles: List<ProfileEntry>
    ) {
        val rowCount: Int get() = rows.values.sumOf { it.size }
        val profileCount: Int get() = profiles.size
    }

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(PREF_BACKUP_ENABLED, true)

    fun folderUri(context: Context): String? =
        prefs(context).getString(PREF_BACKUP_DIR, null)?.takeIf { it.isNotBlank() }

    fun lastAt(context: Context): Long = prefs(context).getLong(PREF_BACKUP_LAST_AT, 0L)

    fun lastSize(context: Context): Long = prefs(context).getLong(PREF_BACKUP_LAST_SIZE, 0L)

    fun lastError(context: Context): String? =
        prefs(context).getString(PREF_BACKUP_LAST_ERROR, null)?.takeIf { it.isNotBlank() }

    private fun prefs(context: Context) =
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    // ── Dump ───────────────────────────────────────────────────────────────

    fun dump(context: Context): BackupSnapshot {
        val app = context.applicationContext
        val db = SheetDb(app)
        val files = db.allFiles()
        return BackupSnapshot(
            at = System.currentTimeMillis(),
            files = files,
            rows = files.associate { it.id to db.loadRows(it.id) },
            styles = files.associate { it.id to db.loadStyles(it.id) },
            hidden = files.associate { it.id to db.loadHidden(it.id) },
            checks = files.associate { it.id to db.loadRowChecks(it.id) },
            reqs = files.associate { it.id to db.loadCheckReqs(it.id) },
            balance = db.walletBalance(),
            txs = db.walletTxs(),
            profiles = runCatching { ProfileManager.getInstance(app).exportEntries() }
                .onFailure { Log.e(TAG, "Profile export failed", it) }
                .getOrDefault(emptyList())
        )
    }

    // ── Auto backup ────────────────────────────────────────────────────────

    private val scheduler by lazy { Executors.newSingleThreadScheduledExecutor() }
    private val scheduled = AtomicBoolean(false)

    /** Coalescing post-change mirror. Every caller within the debounce window
     *  collapses into one write, so a burst of cell edits writes once. */
    fun schedule(context: Context) {
        val app = context.applicationContext
        if (!enabled(app)) return
        if (!scheduled.compareAndSet(false, true)) return
        try {
            scheduler.schedule({
                scheduled.set(false)
                try {
                    backupNow(app)
                } catch (e: Exception) {
                    Log.e(TAG, "Auto backup failed", e)
                }
            }, DEBOUNCE_MS, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            scheduled.set(false)
            Log.e(TAG, "Could not schedule auto backup", e)
        }
    }

    /**
     * Everything the mirror writes, keyed by its path inside the KiloApp
     * folder. backup/backup.db restores the whole app; files/, archive/
     * and profiles/ are human copies that restore never reads.
     *
     * Returns the db size, or -1 when nothing could be written. Never
     * throws: a backup failure must not take the edit that triggered it down.
     */
    fun backupNow(context: Context): Int {
        val app = context.applicationContext
        return try {
            val snap = dump(app)
            val artifacts = artifacts(app, snap)
            val dbBytes = artifacts[REL_DB] ?: ByteArray(0)

            var written = 0
            var error: String? = null
            for ((rel, bytes) in artifacts) {
                val cut = rel.lastIndexOf('/')
                val dir = if (cut < 0) "" else rel.substring(0, cut)
                val name = rel.substring(cut + 1)
                val ok = writeDownloads(app, dir, name, mimeFor(name), bytes)
                if (!ok) {
                    if (error == null) error = "Could not write $name to Downloads"
                } else if (rel == REL_DB) {
                    writeLocalRotating(app, bytes)
                    written = bytes.size
                }
            }
            // Paths the last run produced, before this run overwrites the
            // tracker: the Drive mirror prunes from the same set, so a
            // renamed file leaves no orphan in either destination.
            val prevPaths = trackedPaths(app)
            pruneDownloads(app, artifacts.keys)

            // The user-picked folder is written flat: the Storage Access
            // Framework has no portable way to create a nested directory, and
            // faking one with a slash in a file name only works on some
            // providers. Downloads, the default destination, gets the
            // subfolders.
            folderUri(app)?.let { tree ->
                for ((rel, bytes) in artifacts) {
                    val name = rel.substringAfterLast('/')
                    if (!writeTree(app, Uri.parse(tree), name, mimeFor(name), bytes) && error == null) {
                        error = "Could not write $name to the backup folder"
                    }
                }
            }

            prefs(app).edit().apply {
                if (written > 0) {
                    putLong(PREF_BACKUP_LAST_AT, snap.at)
                    putLong(PREF_BACKUP_LAST_SIZE, written.toLong())
                }
                if (error != null) putString(PREF_BACKUP_LAST_ERROR, error) else remove(PREF_BACKUP_LAST_ERROR)
            }.apply()
            // Drive mirror rides the same snapshot and bytes; it never throws,
            // so a Drive failure cannot take the local backup down with it.
            DriveSync.maybeAutoPush(app, snap.at, artifacts, prevPaths)
            written
        } catch (e: Exception) {
            Log.e(TAG, "Backup failed", e)
            prefs(app).edit().putString(PREF_BACKUP_LAST_ERROR, e.message ?: "Backup failed").apply()
            -1
        }
    }

    internal fun artifacts(context: Context, s: BackupSnapshot): Map<String, ByteArray> {
        val out = linkedMapOf<String, ByteArray>()
        // The one file restore reads. Stamped with prefs below, so it backs
        // up the whole app, not just the Sheet tables.
        out[REL_DB] = backupDbBytes(context)

        // Archived files are exports like any other, but keeping them apart
        // stops the archive from crowding the files a user is working on.
        // Byte-identical to Download/Share (same builder, same data guard).
        val used = mutableSetOf<String>()
        for (f in s.files) {
            val rows = s.rows[f.id].orEmpty()
            if (rows.none { it.isData(f.preset.columns) }) continue
            val bytes = SheetXlsx.build(f.preset.columns, rows)
            val name = fileSafeName(f.name.ifBlank { f.preset.title }, used)
            val rel = (if (f.archived) DIR_ARCHIVE else DIR_FILES) + "/" + name
            out[rel] = bytes
        }

        // One proxy string per profile (server:port:user:pass, the same
        // string the app copies). Human copy only: restore reads the
        // decrypted profiles stamped into backup.db instead.
        val usedProfiles = mutableSetOf<String>()
        val manager = ProfileManager.getInstance(context)
        for (name in manager.getProfiles()) {
            val p = try {
                manager.getProfile(name)
            } catch (_: Exception) {
                null
            } ?: continue
            val line = "${p.getServer()}:${p.getPort()}:${p.getUsername()}:${p.getPassword()}"
            if (line.isBlank() || line == ":::") continue
            val sheet = SheetXlsx.XlsxSheet(
                SheetXlsx.safeSheetName(name.ifBlank { "profile" }, usedProfiles),
                listOf("proxy"), listOf(listOf(line))
            )
            val fname = fileSafeName(name.ifBlank { "profile" }, usedProfiles)
            out[DIR_PROFILES + "/" + fname] = SheetXlsx.buildWorkbook(listOf(sheet))
        }
        return out
    }

    // ── Full-app database ──────────────────────────────────────────────

    /** First bytes of every SQLite file. Restore rejects anything else. */
    private const val SQLITE_MAGIC = "SQLite format 3\u0000"

    fun isBackupDb(bytes: ByteArray): Boolean =
        bytes.size > SQLITE_MAGIC.length &&
            String(bytes, 0, SQLITE_MAGIC.length, Charsets.UTF_8) == SQLITE_MAGIC

    /**
     * Default prefs that travel with the backup. Everything else (bubble
     * position, backup/sync bookkeeping, Drive state, update checks) is
     * per-device or regenerated, so it stays behind.
     */
    private val DEFAULT_PREF_ALLOW = setOf(
        Constants.PREF_THEME_MODE, Constants.PREF_AUTO_STOP,
        Constants.PREF_VPN_ACCELERATOR, Constants.PREF_ACCEL_PRIMARY, Constants.PREF_ACCEL_MODE,
        Constants.PREF_ACCEL_CACHE_IP, Constants.PREF_ACCEL_PROBE, Constants.PREF_ACCEL_INTERVAL_MS,
        Constants.PREF_ACCEL_DNS_CACHE, Constants.PREF_FLOATING_CONTROL, Constants.PREF_BUBBLE_STYLE,
        Constants.PREF_CIRCLE_ALIGN, Constants.PREF_CIRCLE_SIZE, Constants.PREF_SHEET_BUBBLE_FILE_ID,
        Constants.PREF_SMS_LAST_RANGE, Constants.PREF_ADV_PER_APP, Constants.PREF_ADV_APP_BYPASS,
        Constants.PREF_ADV_APP_LIST, Constants.PREF_SPLIT_SINGLE_MODE_MIGRATED,
        "ss_autoCheck", "ss_pageSimple", "ss_pageAdvanced", "ss_fileView",
    )

    /** sms_store keys that travel. tg_session never does: re-login after restore. */
    private val SMS_PREF_ALLOW = setOf(
        "nums_v1", "next_id", "fresh_checked", "fresh_fresh", "fresh_skipped",
    )

    /**
     * Full-app backup bytes: the live db plus stamped meta + prefs tables.
     * The live db is untouched; the stamping happens on a temp copy.
     * Blocking: call from Dispatchers.IO only.
     */
    internal fun backupDbBytes(context: Context): ByteArray {
        val app = context.applicationContext
        val snap = SheetDb(app).snapshotBytes(app)
        val tmp = File.createTempFile("backup-stamp", ".db", app.cacheDir)
        try {
            tmp.writeBytes(snap)
            val db = SQLiteDatabase.openDatabase(tmp.path, null, null, SQLiteDatabase.OPEN_READWRITE)
            try {
                stampBackupTables(db, app)
            } finally {
                try {
                    db.close()
                } catch (_: Exception) {
                }
            }
            return tmp.readBytes()
        } finally {
            try {
                tmp.delete()
            } catch (_: Exception) {
            }
        }
    }

    private fun stampBackupTables(db: SQLiteDatabase, app: Context) {
        db.execSQL("CREATE TABLE IF NOT EXISTS backup_meta(k TEXT PRIMARY KEY, v TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS backup_prefs(store TEXT NOT NULL, key TEXT NOT NULL, type TEXT NOT NULL, value TEXT NOT NULL, PRIMARY KEY(store, key))")
        db.delete("backup_meta", null, null)
        db.delete("backup_prefs", null, null)
        insertMeta(db, "at", System.currentTimeMillis().toString())
        insertMeta(db, "app", "kiloapp")
        insertMeta(db, "format", "2")
        snapshotPrefsInto(
            db, "default",
            PreferenceManager.getDefaultSharedPreferences(app).all, DEFAULT_PREF_ALLOW
        )
        snapshotPrefsInto(
            db, "sms",
            app.getSharedPreferences("sms_store", Context.MODE_PRIVATE).all, SMS_PREF_ALLOW
        )
        for (e in ProfileManager.getInstance(app).exportEntries()) {
            insertPref(db, "profile", e.key, e.type, e.value)
        }
    }

    private fun snapshotPrefsInto(
        db: SQLiteDatabase, store: String, all: Map<String, *>, allow: Set<String>
    ) {
        for ((k, v) in all) {
            if (k !in allow) continue
            val (t, s) = when (v) {
                is String -> "s" to v
                is Int -> "i" to v.toString()
                is Boolean -> "b" to v.toString()
                is Float -> "f" to v.toString()
                is Long -> "l" to v.toString()
                else -> continue
            }
            insertPref(db, store, k, t, s)
        }
    }

    private fun insertMeta(db: SQLiteDatabase, k: String, v: String) {
        val c = ContentValues().apply {
            put("k", k)
            put("v", v)
        }
        db.insertWithOnConflict("backup_meta", null, c, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun insertPref(db: SQLiteDatabase, store: String, k: String, t: String, v: String) {
        val c = ContentValues().apply {
            put("store", store)
            put("key", k)
            put("type", t)
            put("value", v)
        }
        db.insertWithOnConflict("backup_prefs", null, c, SQLiteDatabase.CONFLICT_REPLACE)
    }

    data class BackupSummary(
        val at: Long,
        val fileCount: Int,
        val rowCount: Int,
        val checkCount: Int,
        val reqCount: Int,
        val styleCount: Int,
        val hiddenCount: Int,
        val txCount: Int,
        val balance: Double,
        val profileCount: Int,
    )

    /**
     * Counts for the restore confirm dialog. Throws when the bytes are not
     * a backup db. Blocking: call from Dispatchers.IO only.
     */
    fun summarize(context: Context, bytes: ByteArray): BackupSummary {
        if (!isBackupDb(bytes)) throw IllegalArgumentException("Not a KiloApp backup")
        val tmp = File.createTempFile("backup-read", ".db", context.applicationContext.cacheDir)
        try {
            tmp.writeBytes(bytes)
            val db = SQLiteDatabase.openDatabase(tmp.path, null, null, SQLiteDatabase.OPEN_READONLY)
            try {
                fun count(sql: String): Int = try {
                    db.rawQuery(sql, null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
                } catch (_: Exception) {
                    0
                }
                fun meta(k: String): String = try {
                    db.rawQuery("SELECT v FROM backup_meta WHERE k='$k'", null).use { c ->
                        if (c.moveToFirst()) c.getString(0) else ""
                    }
                } catch (_: Exception) {
                    ""
                }
                val balance = try {
                    db.rawQuery("SELECT v FROM wallet_kv WHERE k='balance'", null).use { c ->
                        if (c.moveToFirst()) c.getString(0)?.toDoubleOrNull() ?: 0.0 else 0.0
                    }
                } catch (_: Exception) {
                    0.0
                }
                return BackupSummary(
                    at = meta("at").toLongOrNull() ?: 0L,
                    fileCount = count("SELECT COUNT(*) FROM files"),
                    rowCount = count("SELECT COUNT(*) FROM rows"),
                    checkCount = count("SELECT COUNT(*) FROM row_checks"),
                    reqCount = count("SELECT COUNT(*) FROM check_reqs"),
                    styleCount = count("SELECT COUNT(*) FROM styles"),
                    hiddenCount = count("SELECT COUNT(*) FROM hidden_cols"),
                    txCount = count("SELECT COUNT(*) FROM wallet_tx"),
                    balance = balance,
                    profileCount = count("SELECT COUNT(*) FROM backup_prefs WHERE store='profile'"),
                )
            } finally {
                try {
                    db.close()
                } catch (_: Exception) {
                }
            }
        } finally {
            try {
                tmp.delete()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Replaces the live db from backup bytes, then writes the stamped prefs
     * back (default + sms prefs, proxy profiles). The db swap lands first:
     * prefs apply only after it succeeded, so a bad file never leaves a
     * half-restored install. Blocking: call from Dispatchers.IO only. The
     * caller refreshes live views afterwards (SheetStore.onBackupRestored on
     * any thread, SmsWatcher.reloadAfterRestore on Main).
     */
    fun restore(context: Context, bytes: ByteArray) {
        if (!isBackupDb(bytes)) throw IllegalArgumentException("Not a KiloApp backup")
        val app = context.applicationContext
        if (!SheetStore.get(app).replaceDatabase(bytes)) {
            throw IllegalStateException("Could not replace database")
        }
        applyBackupPrefs(app, bytes)
    }

    private fun applyBackupPrefs(app: Context, bytes: ByteArray) {
        val tmp = File.createTempFile("backup-apply", ".db", app.cacheDir)
        try {
            tmp.writeBytes(bytes)
            val db = SQLiteDatabase.openDatabase(tmp.path, null, null, SQLiteDatabase.OPEN_READONLY)
            try {
                val byStore = mutableMapOf<String, MutableList<ProfileEntry>>()
                try {
                    db.rawQuery("SELECT store, key, type, value FROM backup_prefs", null).use { c ->
                        while (c.moveToNext()) {
                            val store = c.getString(0)
                            val e = ProfileEntry(
                                key = c.getString(1),
                                type = c.getString(2),
                                value = c.getString(3)
                            )
                            byStore.getOrPut(store) { mutableListOf() }.add(e)
                        }
                    }
                } catch (_: Exception) {
                    // No stamped prefs (e.g. a raw db handed in by hand):
                    // the tables alone still restored above.
                    return
                }
                applyPrefStore(
                    PreferenceManager.getDefaultSharedPreferences(app),
                    byStore["default"].orEmpty(), DEFAULT_PREF_ALLOW
                )
                applyPrefStore(
                    app.getSharedPreferences("sms_store", Context.MODE_PRIVATE),
                    byStore["sms"].orEmpty(), SMS_PREF_ALLOW
                )
                val profiles = byStore["profile"].orEmpty()
                if (profiles.isNotEmpty()) {
                    ProfileManager.getInstance(app).importEntries(profiles)
                }
            } finally {
                try {
                    db.close()
                } catch (_: Exception) {
                }
            }
        } finally {
            try {
                tmp.delete()
            } catch (_: Exception) {
            }
        }
    }

    private fun applyPrefStore(
        prefs: android.content.SharedPreferences,
        entries: List<ProfileEntry>,
        allow: Set<String>
    ) {
        if (entries.isEmpty()) return
        val ed = prefs.edit()
        for (e in entries) {
            if (e.key !in allow) continue
            when (e.type) {
                "s" -> ed.putString(e.key, e.value)
                "i" -> e.value.toIntOrNull()?.let { ed.putInt(e.key, it) }
                "b" -> ed.putBoolean(e.key, e.value.toBoolean())
                "f" -> e.value.toFloatOrNull()?.let { ed.putFloat(e.key, it) }
                "l" -> e.value.toLongOrNull()?.let { ed.putLong(e.key, it) }
            }
        }
        ed.apply()
    }

    /** Filesystem-safe export name. Wider than the workbook's own 31-character
     *  sheet-name rules, and it avoids the characters that break a FAT/exFAT SD
     *  card or a Windows copy of the folder. */
    private fun fileSafeName(raw: String, used: MutableSet<String>): String {
        val cleaned = raw.map { if (it in ILLEGAL_FILE_CHARS || it.code < 0x20) '_' else it }
            .joinToString("").trim().trimEnd('.', ' ').ifBlank { "Sheet" }
        val base = cleaned.take(180)
        var n = 1
        while (true) {
            val suffix = if (n == 1) "" else " $n"
            val candidate = base.take(180 - suffix.length) + suffix + ".xlsx"
            if (used.add(candidate.lowercase())) return candidate
            n++
        }
    }

    private const val ILLEGAL_FILE_CHARS = "<>:\"/\\|?*"

    /** Remove artifacts for Sheet files that no longer exist or were renamed.
     *  Tracked from the last run's path list rather than by scanning the
     *  folder, so a file the user put there themselves is never touched. The
     *  previous flat layout is in that list too, so moving into subfolders
     *  clears the root on the first run instead of leaving a stale copy. */
    internal fun trackedPaths(context: Context): Set<String> = try {
        prefs(context.applicationContext).getStringSet(PREF_BACKUP_XLSX, emptySet()).orEmpty().toSet()
    } catch (_: Exception) {
        emptySet()
    }

    private fun pruneDownloads(context: Context, keep: Set<String>) {
        val app = context.applicationContext
        val previous = try {
            prefs(app).getStringSet(PREF_BACKUP_XLSX, emptySet()).orEmpty().toSet()
        } catch (_: Exception) {
            emptySet()
        }
        for (stale in previous - keep) {
            val cut = stale.lastIndexOf('/')
            val dir = if (cut < 0) "" else stale.substring(0, cut)
            if (deleteDownloads(app, dir, stale.substring(cut + 1))) {
                Log.i(TAG, "Removed a backup file that is no longer produced: $stale")
            }
        }
        if (previous.isEmpty()) {
            // First run of this version: the old build wrote the config file
            // with a spreadsheet MIME, so MediaStore appended an extension and
            // every run added another "profiles.txt (N).xlsx". Those rows were
            // never in the tracked set, so clear them once. Bounded, inside our
            // own retired config folder, and matched on our own naming only.
            // The old kiloapp-backup.json/xlsx and profiles.txt need no
            // special case: they were tracked, so the loop above prunes them.
            deleteDownloads(app, "config", "profiles.txt.xlsx")
            for (n in 1..20) {
                if (!deleteDownloads(app, "config", "profiles.txt ($n).xlsx")) break
            }
        }
        prefs(app).edit().putStringSet(PREF_BACKUP_XLSX, keep.toSet()).apply()
    }

    /** Must agree with the file extension. A name that disagrees with its MIME
     *  type gets an extension appended by MediaStore, and then the
     *  replace-by-name misses that row forever - the next write finds the
     *  name taken and inserts "name (1)", so the folder grows a duplicate per
     *  backup run. */
    internal fun mimeFor(name: String): String = when {
        name.endsWith(".json") -> JSON_MIME
        name.endsWith(".txt") -> "text/plain"
        name.endsWith(".db") -> "application/octet-stream"
        else -> XLSX_MIME
    }

    /** MediaStore normalises RELATIVE_PATH to a trailing slash, so every
     *  write and every delete has to build it the same way or the match
     *  silently misses and the file accumulates. */
    private fun downloadsRelative(dir: String): String {
        val base = Environment.DIRECTORY_DOWNLOADS + "/" + DOWNLOAD_SUBDIR
        return if (dir.isBlank()) base + "/" else "$base/$dir/"
    }

    private const val LOCAL_DIR = "backup"
    private const val LOCAL_DB = "backup.db"
    private const val LOCAL_PREV_DB = "backup.prev.db"

    /** App-private copy of the last two generations. Dies with the install
     *  like the database does, but costs nothing and gives in-app restore a
     *  source when the user has not picked a folder yet. */
    private fun writeLocalRotating(context: Context, bytes: ByteArray) {
        try {
            val dir = File(context.applicationContext.filesDir, LOCAL_DIR).apply { mkdirs() }
            val current = File(dir, LOCAL_DB)
            if (current.exists() && current.length() > 0) {
                current.copyTo(File(dir, LOCAL_PREV_DB), overwrite = true)
            }
            current.writeBytes(bytes)
        } catch (e: Exception) {
            Log.w(TAG, "Local backup copy failed", e)
        }
    }

    fun localPrevious(context: Context): ByteArray? = readLocal(context, LOCAL_PREV_DB)

    fun localCurrent(context: Context): ByteArray? = readLocal(context, LOCAL_DB)

    private fun readLocal(context: Context, name: String): ByteArray? = try {
        File(File(context.applicationContext.filesDir, LOCAL_DIR), name)
            .takeIf { it.exists() && it.length() > 0 }?.readBytes()
    } catch (e: Exception) {
        Log.w(TAG, "Could not read $name", e)
        null
    }

// ── Destinations ───────────────────────────────────────────────────────

    /** insert() never replaces: a second row with the same display name is a
     *  second file, so an un-deleted mirror would pile up a new copy on every
     *  run and flood the folder. The match is scoped to our own subfolder -
     *  name alone would delete an unrelated file the user keeps in Downloads. */
    private fun deleteDownloads(context: Context, dir: String, name: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return try {
            val resolver = context.applicationContext.contentResolver
            val existing = resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} = ?",
                arrayOf(name, downloadsRelative(dir)), null
            )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null } ?: return false
            resolver.delete(
                ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, existing),
                null, null
            ) > 0
        } catch (e: Exception) {
            Log.e(TAG, "Downloads delete failed for $name", e)
            false
        }
    }

    private fun writeDownloads(context: Context, dir: String, name: String, mime: String, bytes: ByteArray): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // Pre-29 needs a storage permission this app does not ask for, so
            // the always-on destination is simply unavailable there and the
            // user-picked folder carries the backup instead.
            return false
        }
        return try {
            val resolver = context.applicationContext.contentResolver
            deleteDownloads(context, dir, name)

            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, downloadsRelative(dir))
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return false
            val ok = try {
                resolver.openOutputStream(uri)?.use { it.write(bytes) } != null
            } catch (e: Exception) {
                Log.e(TAG, "Downloads stream failed for $name", e)
                false
            }
            if (!ok) {
                // Never leave a stuck IS_PENDING row: it is invisible in
                // Downloads and would block the next write by name.
                resolver.delete(uri, null, null)
                return false
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Downloads write failed for $name", e)
            false
        }
    }

    /** Replace-by-name inside a persisted SAF tree. Framework calls only, so
     *  no androidx.documentfile dependency is pulled in for one write. */
    private fun writeTree(context: Context, tree: Uri, name: String, mime: String, bytes: ByteArray): Boolean {
        return try {
            val resolver = context.applicationContext.contentResolver
            val docId = DocumentsContract.getTreeDocumentId(tree)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            val existing = resolver.query(children, null, null, null, null)?.use { c ->
                // Literal column names: the documents contract fixes these as
                // _display_name and _id, and it keeps this file free of the
                // nested DocumentsContract.Column / BaseColumns lookups.
                val nameIdx = c.getColumnIndex("_display_name")
                val idIdx = c.getColumnIndex("_id")
                if (nameIdx < 0 || idIdx < 0) return@use null
                var found: String? = null
                while (c.moveToNext()) {
                    if (c.getString(nameIdx) == name) {
                        found = c.getString(idIdx)
                        break
                    }
                }
                found
            }
            if (existing != null) {
                resolver.delete(
                    DocumentsContract.buildDocumentUriUsingTree(tree, existing), null, null
                )
            }
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
            val created = DocumentsContract.createDocument(resolver, parent, mime, name) ?: return false
            resolver.openOutputStream(created)?.use { it.write(bytes) } ?: return false
            true
        } catch (e: Exception) {
            Log.e(TAG, "Backup folder write failed for $name", e)
            false
        }
    }

// ── Pre-destructive snapshots ──────────────────────────────────────────

    private const val SNAP_DIR = "snapshots"
    private const val SNAP_LIMIT = 10
    private const val SNAP_PREFIX = "before-"

    data class Snapshot(val file: File, val label: String, val at: Long, val size: Long)

    /**
     * Copy the current state aside before something that can destroy it.
     * A full backup.db per action: the same bytes the mirror writes, so a
     * snapshot restores through the same path.
     *
     * The rolling current/previous pair only remembers one step back, so two
     * destructive actions in a row overwrite the last good copy with an
     * already damaged one. A named snapshot per action leaves N recoverable
     * points for N actions instead of one.
     *
     * Local on purpose: these undo a mistake inside the running install, and
     * the always-current mirror out in Downloads is what has to survive an
     * uninstall. Dated files in the user's Downloads folder would only
     * clutter it.
     */
    fun snapshot(context: Context, label: String) {
        try {
            val app = context.applicationContext
            val dir = File(File(app.filesDir, "backup"), SNAP_DIR).apply { mkdirs() }
            val safe = label.map { if (it.isLetterOrDigit()) it else '_' }
                .joinToString("").trim('_').take(40).ifBlank { "change" }
            val out = File(dir, "$SNAP_PREFIX${safe}_${System.currentTimeMillis()}.db")
            out.writeBytes(backupDbBytes(app))
            pruneSnapshots(dir)
        } catch (e: Exception) {
            // A snapshot that fails must never block the action it protects.
            Log.e(TAG, "Snapshot failed for $label", e)
        }
    }

    private fun pruneSnapshots(dir: File) {
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith(SNAP_PREFIX) }
            ?.sortedByDescending { it.lastModified() } ?: return
        for (stale in files.drop(SNAP_LIMIT)) {
            try {
                stale.delete()
            } catch (_: Exception) {
            }
        }
    }

    /** Newest first. Label and timestamp are read back out of the filename so
     *  the list needs no parse. */
    fun listSnapshots(context: Context): List<Snapshot> {
        val dir = File(File(context.applicationContext.filesDir, "backup"), SNAP_DIR)
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".db") }
            ?: return emptyList()
        return files.sortedByDescending { it.lastModified() }.mapNotNull { f ->
            val stem = f.name.removePrefix(SNAP_PREFIX).removeSuffix(".db")
            val cut = stem.lastIndexOf('_')
            if (cut <= 0) return@mapNotNull null
            Snapshot(
                file = f,
                label = stem.substring(0, cut).replace('_', ' '),
                at = stem.substring(cut + 1).toLongOrNull() ?: f.lastModified(),
                size = f.length()
            )
        }
    }

    fun readSnapshot(s: Snapshot): ByteArray? = try {
        s.file.takeIf { it.exists() && it.length() > 0 }?.readBytes()
    } catch (e: Exception) {
        Log.w(TAG, "Could not read snapshot " + s.file.name, e)
        null
    }
}
