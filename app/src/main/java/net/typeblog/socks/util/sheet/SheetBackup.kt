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
import net.typeblog.socks.util.Constants.PREF_BACKUP_DIR
import net.typeblog.socks.util.Constants.PREF_BACKUP_ENABLED
import net.typeblog.socks.util.Constants.PREF_BACKUP_LAST_AT
import net.typeblog.socks.util.Constants.PREF_BACKUP_LAST_ERROR
import net.typeblog.socks.util.Constants.PREF_BACKUP_LAST_SIZE
import net.typeblog.socks.util.Constants.PREF_BACKUP_XLSX
import net.typeblog.socks.util.ProfileEntry
import net.typeblog.socks.util.ProfileManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * Owns one full snapshot of everything the Sheet tab stores, and writes it in
 * two shapes to one place outside the app sandbox:
 *
 *   kiloapp-backup.json  lossless, the only supported restore path
 *   kiloapp-backup.xlsx  the same snapshot as a workbook, for a human to read
 *
 * Both renderers consume the same [BackupSnapshot], so they cannot drift.
 *
 * Why not the database: /data/data and Android/data are both deleted on
 * uninstall and on clear app data, so a backup kept inside either is gone
 * exactly when it is needed. MediaStore Downloads survives both and needs no
 * permission on API 29+, so it is the always-on destination; a folder the
 * user picks through the Storage Access Framework is the optional addition
 * that can live on an SD card or in a cloud drive.
 */
object SheetBackup {
    const val FORMAT = 1
    const val JSON_NAME = "kiloapp-backup.json"
    const val XLSX_NAME = "kiloapp-backup.xlsx"
    const val PREV_JSON_NAME = "kiloapp-backup.prev.json"
    const val DOWNLOAD_SUBDIR = "KiloApp"

    // Layout inside the KiloApp folder. Grouping by kind keeps it browsable:
    // backup/ is the canonical pair, files/ and archive/ hold the per-file
    // workbooks, config/ holds the proxy profiles.
    private const val DIR_BACKUP = "backup"
    private const val DIR_FILES = "files"
    private const val DIR_ARCHIVE = "archive"
    private const val DIR_CONFIG = "config"
    private const val REL_JSON = DIR_BACKUP + "/kiloapp-backup.json"
    private const val REL_ALL = DIR_BACKUP + "/kiloapp-backup.xlsx"
    private const val REL_CONFIG = DIR_CONFIG + "/profiles.txt"

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
     * folder. Grouped by kind so the folder is browsable instead of one pile:
     *
     *   backup/   the canonical pair - json restores everything, xlsx is the
     *             whole app as one workbook
     *   files/    one workbook per active Sheet file
     *   archive/  one workbook per archived Sheet file
     *   config/   proxy profiles, readable
     *
     * Returns the JSON size, or -1 when nothing could be written. Never
     * throws: a backup failure must not take the edit that triggered it down.
     */
    fun backupNow(context: Context): Int {
        val app = context.applicationContext
        return try {
            val snap = dump(app)
            val artifacts = artifacts(app, snap)
            val json = artifacts[REL_JSON] ?: ByteArray(0)

            var written = 0
            var error: String? = null
            for ((rel, bytes) in artifacts) {
                val cut = rel.lastIndexOf('/')
                val dir = if (cut < 0) "" else rel.substring(0, cut)
                val name = rel.substring(cut + 1)
                val mime = if (rel.endsWith(".json")) JSON_MIME else XLSX_MIME
                val ok = writeDownloads(app, dir, name, mime, bytes)
                if (!ok) {
                    if (error == null) error = "Could not write $name to Downloads"
                } else if (rel == REL_JSON) {
                    writeLocalRotating(app, bytes)
                    written = bytes.size
                }
            }
            pruneDownloads(app, artifacts.keys)

            // The user-picked folder is written flat: the Storage Access
            // Framework has no portable way to create a nested directory, and
            // faking one with a slash in a file name only works on some
            // providers. Downloads, the default destination, gets the
            // subfolders.
            folderUri(app)?.let { tree ->
                for ((rel, bytes) in artifacts) {
                    val name = rel.substringAfterLast('/')
                    val mime = if (rel.endsWith(".json")) JSON_MIME else XLSX_MIME
                    if (!writeTree(app, Uri.parse(tree), name, mime, bytes) && error == null) {
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
            written
        } catch (e: Exception) {
            Log.e(TAG, "Backup failed", e)
            prefs(app).edit().putString(PREF_BACKUP_LAST_ERROR, e.message ?: "Backup failed").apply()
            -1
        }
    }

    private fun artifacts(context: Context, s: BackupSnapshot): Map<String, ByteArray> {
        val out = linkedMapOf<String, ByteArray>()
        out[REL_JSON] = toJson(s).toByteArray(Charsets.UTF_8)
        out[REL_ALL] = SheetBackupXlsx.write(s)

        // Archived files are exports like any other, but keeping them apart
        // stops the archive from crowding the files a user is working on.
        val used = mutableSetOf(REL_ALL.substringAfterLast('/').lowercase())
        for (f in s.files) {
            val bytes = SheetBackupXlsx.writeFile(s, f.id) ?: continue
            val name = fileSafeName(f.name.ifBlank { f.preset.title }, used)
            val rel = (if (f.archived) DIR_ARCHIVE else DIR_FILES) + "/" + name
            out[rel] = bytes
        }

        out[REL_CONFIG] = ProfileManager.getInstance(context).exportReadable()
            .toByteArray(Charsets.UTF_8)
        return out
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
        prefs(app).edit().putStringSet(PREF_BACKUP_XLSX, keep.toSet()).apply()
    }

    /** MediaStore normalises RELATIVE_PATH to a trailing slash, so every
     *  write and every delete has to build it the same way or the match
     *  silently misses and the file accumulates. */
    private fun downloadsRelative(dir: String): String {
        val base = Environment.DIRECTORY_DOWNLOADS + "/" + DOWNLOAD_SUBDIR
        return if (dir.isBlank()) base + "/" else "$base/$dir/"
    }

    /** App-private copy of the last two generations. Dies with the install
     *  like the database does, but costs nothing and gives in-app restore a
     *  source when the user has not picked a folder yet. */
    private fun writeLocalRotating(context: Context, bytes: ByteArray) {
        try {
            val dir = File(context.applicationContext.filesDir, "backup").apply { mkdirs() }
            val current = File(dir, JSON_NAME)
            if (current.exists() && current.length() > 0) {
                current.copyTo(File(dir, PREV_JSON_NAME), overwrite = true)
            }
            current.writeBytes(bytes)
        } catch (e: Exception) {
            Log.w(TAG, "Local backup copy failed", e)
        }
    }

    fun localPrevious(context: Context): String? = readLocal(context, PREV_JSON_NAME)

    fun localCurrent(context: Context): String? = readLocal(context, JSON_NAME)

    private fun readLocal(context: Context, name: String): String? = try {
        File(File(context.applicationContext.filesDir, "backup"), name)
            .takeIf { it.exists() && it.length() > 0 }?.readText()
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

// ── JSON: the lossless, canonical shape ────────────────────────────────

    fun toJson(s: BackupSnapshot): String {
        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("app", "kiloapp")
        root.put("at", s.at)

        val files = JSONArray()
        val rows = JSONArray()
        val styles = JSONArray()
        val hidden = JSONArray()
        val checks = JSONArray()
        val reqs = JSONArray()

        for (f in s.files) {
            files.put(
                JSONObject()
                    .put("id", f.id).put("name", f.name).put("preset", f.preset.name)
                    .put("password", f.password).put("archived", f.archived)
                    .put("deletedAt", f.deletedAt).put("createdAt", f.createdAt)
                    .put("updatedAt", f.updatedAt).put("seq", f.seq)
            )
            for (r in s.rows[f.id].orEmpty()) {
                rows.put(
                    JSONObject()
                        .put("fileId", f.id).put("rowIdx", r.rowIdx)
                        .put("cookies", r.cookies).put("twofakey", r.twofakey)
                        .put("uid", r.uid).put("status", r.status)
                        .put("hold", r.hold).put("approved", r.approved).put("dead", r.dead)
                )
            }
            for ((key, st) in s.styles[f.id].orEmpty()) {
                styles.put(
                    JSONObject()
                        .put("fileId", f.id).put("key", key)
                        .put("bg", st.bg).put("color", st.color).put("bold", st.bold)
                )
            }
            s.hidden[f.id].orEmpty().forEach { hidden.put(JSONObject().put("fileId", f.id).put("colKey", it)) }
            for ((idx, c) in s.checks[f.id].orEmpty()) {
                checks.put(
                    JSONObject()
                        .put("fileId", f.id).put("rowIdx", idx).put("checkedAt", c.checkedAt)
                        .put("uidOk", c.uidOk).put("uidError", c.uidError)
                        .put("simplePage", c.simplePage).put("simpleNumber", c.simpleNumber)
                        .put("simpleError", c.simpleError).put("advEligible", c.advEligible)
                        .put("advPage", c.advPage).put("advNumber", c.advNumber)
                        .put("advBan", c.advBan).put("advError", c.advError)
                )
            }
            for ((idx, list) in s.reqs[f.id].orEmpty()) {
                for ((i, q) in list.withIndex()) {
                    reqs.put(
                        JSONObject()
                            .put("fileId", f.id).put("rowIdx", idx).put("seq", i)
                            .put("kind", q.kind).put("method", q.method).put("url", q.url)
                            .put("status", q.status).put("durationMs", q.durationMs)
                            .put("reqNote", q.reqNote).put("resNote", q.resNote)
                            .put("error", q.error).put("at", q.at)
                    )
                }
            }
        }
        root.put("files", files)
        root.put("rows", rows)
        root.put("styles", styles)
        root.put("hidden", hidden)
        root.put("checks", checks)
        root.put("reqs", reqs)
        root.put("balance", s.balance)

        val txs = JSONArray()
        for (t in s.txs) {
            txs.put(
                JSONObject()
                    .put("id", t.id).put("createdAt", t.createdAt).put("type", t.type)
                    .put("amount", t.amount).put("balanceAfter", t.balanceAfter)
                    .put("title", t.title).put("detail", t.detail)
            )
        }
        root.put("txs", txs)

        val profiles = JSONArray()
        for (p in s.profiles) {
            profiles.put(
                JSONObject().put("k", p.key).put("t", p.type).put("v", p.value)
            )
        }
        root.put("profiles", profiles)
        return root.toString()
    }

    fun fromJson(raw: String): BackupSnapshot {
        val root = JSONObject(raw)
        if (root.optInt("format", 0) > FORMAT) {
            throw IllegalArgumentException("Backup is from a newer version of the app")
        }
        val rows = mutableMapOf<String, MutableList<SheetRow>>()
        val styles = mutableMapOf<String, MutableMap<String, CellStyle>>()
        val hidden = mutableMapOf<String, MutableSet<String>>()
        val checks = mutableMapOf<String, MutableMap<Int, RowCheck>>()
        val reqs = mutableMapOf<String, MutableMap<Int, MutableList<CheckReq>>>()

        val filesJson = root.optJSONArray("files") ?: JSONArray()
        val files = mutableListOf<SheetFile>()
        for (i in 0 until filesJson.length()) {
            val o = filesJson.getJSONObject(i)
            files.add(
                SheetFile(
                    id = o.getString("id"), name = o.getString("name"),
                    preset = SheetPreset.of(o.getString("preset")), password = o.getString("password"),
                    archived = o.optBoolean("archived", false), deletedAt = o.optLong("deletedAt", 0L),
                    createdAt = o.optLong("createdAt", 0L), updatedAt = o.optLong("updatedAt", 0L),
                    seq = o.optLong("seq", 0L)
                )
            )
        }

        val rowsJson = root.optJSONArray("rows") ?: JSONArray()
        for (i in 0 until rowsJson.length()) {
            val o = rowsJson.getJSONObject(i)
            val id = o.getString("fileId")
            rows.getOrPut(id) { mutableListOf() }.add(
                SheetRow(
                    rowIdx = o.getInt("rowIdx"),
                    cookies = o.optString("cookies", ""),
                    twofakey = o.optString("twofakey", ""),
                    uid = o.optString("uid", ""),
                    status = o.optString("status", ""),
                    hold = o.optBoolean("hold", false),
                    approved = o.optBoolean("approved", false),
                    dead = o.optBoolean("dead", false)
                )
            )
        }

        val stylesJson = root.optJSONArray("styles") ?: JSONArray()
        for (i in 0 until stylesJson.length()) {
            val o = stylesJson.getJSONObject(i)
            styles.getOrPut(o.getString("fileId")) { mutableMapOf() }[o.getString("key")] = CellStyle(
                bg = o.optStringOrNull("bg"), color = o.optStringOrNull("color"),
                bold = o.optBoolean("bold", false)
            )
        }

        val hiddenJson = root.optJSONArray("hidden") ?: JSONArray()
        for (i in 0 until hiddenJson.length()) {
            val o = hiddenJson.getJSONObject(i)
            hidden.getOrPut(o.getString("fileId")) { mutableSetOf() }.add(o.getString("colKey"))
        }

        val checksJson = root.optJSONArray("checks") ?: JSONArray()
        for (i in 0 until checksJson.length()) {
            val o = checksJson.getJSONObject(i)
            checks.getOrPut(o.getString("fileId")) { mutableMapOf() }[o.getInt("rowIdx")] = RowCheck(
                checkedAt = o.optLong("checkedAt", 0L),
                uidOk = o.optBooleanOrNull("uidOk"),
                uidError = o.optStringOrNull("uidError"),
                simplePage = o.optStringOrNull("simplePage"),
                simpleNumber = o.optStringOrNull("simpleNumber"),
                simpleError = o.optStringOrNull("simpleError"),
                advEligible = o.optBoolean("advEligible", false),
                advPage = o.optStringOrNull("advPage"),
                advNumber = o.optStringOrNull("advNumber"),
                advBan = o.optStringOrNull("advBan"),
                advError = o.optStringOrNull("advError")
            )
        }

        val reqsJson = root.optJSONArray("reqs") ?: JSONArray()
        for (i in 0 until reqsJson.length()) {
            val o = reqsJson.getJSONObject(i)
            reqs.getOrPut(o.getString("fileId")) { mutableMapOf() }
                .getOrPut(o.getInt("rowIdx")) { mutableListOf() }
                .add(
                    CheckReq(
                        kind = o.optString("kind", ""), method = o.optString("method", ""),
                        url = o.optString("url", ""), status = o.optInt("status", 0),
                        durationMs = o.optLong("durationMs", 0L),
                        reqNote = o.optStringOrNull("reqNote"), resNote = o.optStringOrNull("resNote"),
                        error = o.optStringOrNull("error"), at = o.optLong("at", 0L)
                    )
                )
        }

        val txsJson = root.optJSONArray("txs") ?: JSONArray()
        val txs = mutableListOf<WalletTx>()
        for (i in 0 until txsJson.length()) {
            val o = txsJson.getJSONObject(i)
            txs.add(
                WalletTx(
                    id = o.getString("id"), createdAt = o.optLong("createdAt", 0L),
                    type = o.optString("type", ""), amount = o.optDouble("amount", 0.0),
                    balanceAfter = o.optDouble("balanceAfter", 0.0), title = o.optString("title", ""),
                    detail = o.optStringOrNull("detail")
                )
            )
        }

        val profilesJson = root.optJSONArray("profiles") ?: JSONArray()
        val profiles = mutableListOf<ProfileEntry>()
        for (i in 0 until profilesJson.length()) {
            val o = profilesJson.getJSONObject(i)
            profiles.add(
                ProfileEntry(
                    key = o.getString("k"), type = o.optString("t", "s"),
                    value = o.optString("v", "")
                )
            )
        }

        return BackupSnapshot(
            at = root.optLong("at", 0L), files = files, rows = rows, styles = styles,
            hidden = hidden, checks = checks, reqs = reqs,
            balance = root.optDouble("balance", 0.0), txs = txs, profiles = profiles
        )
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key, "")

    private fun JSONObject.optBooleanOrNull(key: String): Boolean? =
        if (!has(key) || isNull(key)) null else optBoolean(key, false)

// ── Restore ────────────────────────────────────────────────────────────

    /** Replaces every sheet-owned table in one transaction, and overwrites the
     *  profile settings alongside. Either the whole snapshot lands or none of
     *  the database does, so a bad file can never leave a half-restored
     *  install. Profiles go first: that write is cheap and can fail on its
     *  own, and doing it before the transaction means a rejected profile
     *  import leaves the larger sheet data untouched. Returns the file, row
     *  and profile counts on success. */
    fun restore(context: Context, bytes: ByteArray): Triple<Int, Int, Int> {
        val snap = parse(bytes)
        if (snap.profiles.isNotEmpty()) {
            ProfileManager.getInstance(context.applicationContext).importEntries(snap.profiles)
        }
        val db = SheetDb(context.applicationContext)
        db.tx { d: SQLiteDatabase ->
            db.clearAllSheets(d)
            for (f in snap.files) db.insertFile(d, f)
            for (f in snap.files) {
                val id = f.id
                snap.rows[id]?.let { db.saveAllRows(d, id, it) }
                snap.styles[id]?.forEach { (key, st) ->
                    val parts = key.split(':', limit = 2)
                    if (parts.size == 2) {
                        val rowIdx = parts[0].toIntOrNull() ?: 0
                        db.saveStyle(d, id, rowIdx, parts[1], st)
                    }
                }
                snap.hidden[id]?.let { db.saveHidden(d, id, it) }
                val ck = snap.checks[id].orEmpty()
                val rq = snap.reqs[id].orEmpty()
                if (ck.isNotEmpty() || rq.isNotEmpty()) db.saveCheckDetails(d, id, ck, rq)
            }
            db.setWalletBalance(d, snap.balance)
            for (t in snap.txs) db.insertWalletTx(d, t)
        }
        return Triple(snap.files.size, snap.rowCount, snap.profileCount)
    }

// ── Pre-destructive snapshots ──────────────────────────────────────────

    private const val SNAP_DIR = "snapshots"
    private const val SNAP_LIMIT = 10
    private const val SNAP_PREFIX = "before-"

    data class Snapshot(val file: File, val label: String, val at: Long, val size: Long)

    /**
     * Copy the current state aside before something that can destroy it.
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
            val out = File(dir, "$SNAP_PREFIX${safe}_${System.currentTimeMillis()}.json")
            out.writeBytes(toJson(dump(app)).toByteArray(Charsets.UTF_8))
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
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?: return emptyList()
        return files.sortedByDescending { it.lastModified() }.mapNotNull { f ->
            val stem = f.name.removePrefix(SNAP_PREFIX).removeSuffix(".json")
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

    fun readSnapshot(s: Snapshot): String? = try {
        s.file.readText()
    } catch (e: Exception) {
        Log.w(TAG, "Could not read snapshot ${s.file.name}", e)
        null
    }

// ── The readable twin, and the reader for it ───────────────────────────

    /**
     * Load backup: accept either shape. The JSON dump is the canonical path
     * and the only one that carries styles, hidden columns and check history;
     * the workbook is the fallback for when all that survives is the file the
     * user could see and open. Detected by content, not by extension, because
     * a provider can hand back any name and a spreadsheet app may rewrite one.
     */
    fun parse(bytes: ByteArray): BackupSnapshot {
        if (isZip(bytes)) {
            return SheetBackupXlsx.read(bytes)
                ?: throw IllegalArgumentException("Not a KiloApp backup workbook")
        }
        return fromJson(String(bytes, Charsets.UTF_8))
    }

    /** Local files every PKZip file starts with, so an xlsx is recognised
     *  without trusting the name the picker reported. */
    private fun isZip(bytes: ByteArray): Boolean =
        bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
}
