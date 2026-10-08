package net.typeblog.socks.util.sheet

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import androidx.preference.PreferenceManager
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import net.typeblog.socks.BuildConfig
import net.typeblog.socks.util.Constants.PREF_DRIVE_ACCOUNT
import net.typeblog.socks.util.Constants.PREF_DRIVE_ENABLED
import net.typeblog.socks.util.Constants.PREF_DRIVE_FOLDER_IDS
import net.typeblog.socks.util.Constants.PREF_DRIVE_LAST_AT
import net.typeblog.socks.util.Constants.PREF_DRIVE_LAST_ERROR
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// Phase 1: Google identity + Drive permission. Sign-in (Credential Manager),
// Drive grant (AuthorizationClient, drive.file only), folder ensure (plain
// Drive REST, SheetChecker-style). No uploads yet — Phase 2 reuses
// SheetBackup.artifacts for those, so both destinations share one renderer.
// Network runs on the calling thread: call from Dispatchers.IO.
object DriveSync {
    const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
    const val FOLDER_NAME = "KiloApp"
    val SUBFOLDERS = listOf("backup", "files", "archive", "profiles")
    private const val FOLDER_MIME = "application/vnd.google-apps.folder"
    private const val TIMEOUT_MS = 20000

    data class SignIn(val email: String)
    data class FolderIds(val root: String, val children: Map<String, String>)

    fun webClientId(): String = BuildConfig.DRIVE_WEB_CLIENT_ID
    fun configured(): Boolean = webClientId().isNotEmpty()

    // Bottom-sheet account picker. First run shows every Google account on
    // the device (no pre-filter); later runs can filter to authorized ones.
    suspend fun signIn(activity: Activity): SignIn {
        val opt = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(webClientId())
            .build()
        val resp = CredentialManager.create(activity)
            .getCredential(activity, GetCredentialRequest(listOf(opt)))
        val raw = resp.credential
        if (raw.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            throw IllegalStateException("Not a Google account")
        }
        val cred = GoogleIdTokenCredential.createFrom(raw.data)
        prefs(activity).edit().putString(PREF_DRIVE_ACCOUNT, cred.id).apply()
        return SignIn(cred.id)
    }

    // One consent screen for drive.file: the app sees only files it created.
    // Returns a short-lived access token for Drive REST calls. When Google
    // wants the consent screen first, the result carries a PendingIntent
    // instead of a token: launch it and call authorize again after it
    // returns OK. Swallowing that answer is exactly the "Empty access
    // token" failure.
    class DriveResolutionRequired(val resolution: PendingIntent) : Exception("Needs consent")

    suspend fun authorize(activity: Activity): String {
        val req = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_FILE_SCOPE)))
            .build()
        val res = Identity.getAuthorizationClient(activity).authorize(req).await()
        res.accessToken?.let { return it }
        throw DriveResolutionRequired(
            res.pendingIntent ?: throw IllegalStateException("Empty access token")
        )
    }

    // KiloApp/ + the four mirror subfolders, created once then cached by id.
    fun ensureFolders(context: Context, accessToken: String): FolderIds {
        val root = findFolder(accessToken, FOLDER_NAME, null)
            ?: createFolder(accessToken, FOLDER_NAME, null)
        val children = SUBFOLDERS.associateWith { name ->
            findFolder(accessToken, name, root) ?: createFolder(accessToken, name, root)
        }
        val cached = JSONObject().put("root", root)
        for ((k, v) in children) cached.put(k, v)
        prefs(context).edit().putString(PREF_DRIVE_FOLDER_IDS, cached.toString()).apply()
        return FolderIds(root, children)
    }

    suspend fun signOut(context: Context) {
        try {
            CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())
        } catch (_: Exception) {
        }
        prefs(context).edit().remove(PREF_DRIVE_ACCOUNT).remove(PREF_DRIVE_FOLDER_IDS).apply()
    }

    fun account(context: Context): String? =
        prefs(context).getString(PREF_DRIVE_ACCOUNT, null)?.takeIf { it.isNotBlank() }

    fun enabled(context: Context): Boolean =
        prefs(context).getBoolean(PREF_DRIVE_ENABLED, true)

    fun lastAt(context: Context): Long = prefs(context).getLong(PREF_DRIVE_LAST_AT, 0L)

    fun lastError(context: Context): String? =
        prefs(context).getString(PREF_DRIVE_LAST_ERROR, null)?.takeIf { it.isNotBlank() }

    fun setLastError(context: Context, msg: String?) {
        prefs(context).edit().apply {
            if (msg != null) putString(PREF_DRIVE_LAST_ERROR, msg) else remove(PREF_DRIVE_LAST_ERROR)
        }.apply()
    }

    private fun findFolder(token: String, name: String, parent: String?): String? {
        var q = "mimeType = '$FOLDER_MIME' and name = '$name' and trashed = false"
        if (parent != null) q += " and '$parent' in parents"
        val url = "https://www.googleapis.com/drive/v3/files?q=" +
            URLEncoder.encode(q, "UTF-8") + "&fields=files(id)&spaces=drive&pageSize=1"
        val files = driveCall(token, "GET", url, null).optJSONArray("files") ?: return null
        return if (files.length() > 0) files.getJSONObject(0).optString("id", null) else null
    }

    private fun createFolder(token: String, name: String, parent: String?): String {
        val meta = JSONObject().put("name", name).put("mimeType", FOLDER_MIME)
        if (parent != null) meta.put("parents", org.json.JSONArray().put(parent))
        return driveCall(token, "POST", "https://www.googleapis.com/drive/v3/files?fields=id", meta.toString())
            .optString("id", null) ?: throw IllegalStateException("Folder create returned no id")
    }

    private fun driveCall(token: String, method: String, url: String, body: String?): JSONObject {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
        }
        return readJson(c, "$method")
    }

    // ── Phase 2: mirror push/pull ─────────────────────────────────────────

    data class PushResult(val uploaded: Int, val pruned: Int)
    private data class Remote(val id: String, val hash: String?)
    private const val TAG = "DriveSync"

    fun FolderIds.childFor(dir: String): String? = children[dir]

    /** Uploads every artifact, updating in place by name, and deletes remote
     *  files our last run tracked but this one no longer produces (renames
     *  only — never anything the user put there themselves). Files whose
     *  SHA-256 already matches the remote copy are skipped, so quiet files
     *  cost one listing and keep their modifiedTime. */
    fun push(
        context: Context, token: String, folders: FolderIds,
        artifacts: Map<String, ByteArray>, previous: Set<String>
    ): PushResult {
        val byFolder = mutableMapOf<String, MutableMap<String, Remote>>()
        fun listing(dir: String): MutableMap<String, Remote> {
            val fid = folders.childFor(dir) ?: throw IllegalStateException("No folder for $dir")
            return byFolder.getOrPut(fid) { listFiles(token, fid) }
        }
        var uploaded = 0
        for ((rel, bytes) in artifacts) {
            val cut = rel.lastIndexOf('/')
            if (cut < 0) continue
            val dir = rel.substring(0, cut)
            val name = rel.substring(cut + 1)
            folders.childFor(dir) ?: continue
            val hash = sha256(bytes)
            val cur = listing(dir)[name]
            if (cur != null && cur.hash == hash) continue
            val id = if (cur != null) {
                updateFile(token, cur.id, name, SheetBackup.mimeFor(name), bytes, dir, hash)
                cur.id
            } else {
                createFile(token, folders.childFor(dir)!!, name, SheetBackup.mimeFor(name), bytes, dir, hash)
            }
            listing(dir)[name] = Remote(id, hash)
            uploaded++
        }
        var pruned = 0
        for (stale in previous - artifacts.keys) {
            val cut = stale.lastIndexOf('/')
            if (cut < 0) continue
            val dir = stale.substring(0, cut)
            val name = stale.substring(cut + 1)
            folders.childFor(dir) ?: continue
            val id = listing(dir).remove(name)?.id ?: continue
            if (deleteFile(token, id)) pruned++
        }
        return PushResult(uploaded, pruned)
    }

    /** Raw bytes of backup/backup.db, or null when it is not there.
     *  Feeds SheetBackup.restore. */
    fun pullBackupDb(token: String, folders: FolderIds): ByteArray? {
        val fid = folders.childFor("backup") ?: return null
        val id = listFiles(token, fid)[SheetBackup.DB_NAME]?.id ?: return null
        return driveBytes(token, id)
    }

    /** Remote modifiedTime of the backup db, 0 when absent/unreadable. */
    fun remoteDbTime(token: String, folders: FolderIds): Long {
        return try {
            val fid = folders.childFor("backup") ?: return 0
            val id = listFiles(token, fid)[SheetBackup.DB_NAME]?.id ?: return 0
            val t = driveCall(token, "GET", "https://www.googleapis.com/drive/v3/files/$id?fields=modifiedTime", null)
                .optString("modifiedTime", "")
            parseTime(t)
        } catch (_: Exception) {
            0
        }
    }

    /** Debounced-mirror hook: same trigger as the Downloads mirror, same
     *  bytes. Last-write-wins by snapshot time; a newer remote only records
     *  an error for the Backup page to surface, never overwrites local.
     *  Never throws: a Drive failure must not take the local backup down. */
    fun maybeAutoPush(context: Context, snapAt: Long, artifacts: Map<String, ByteArray>, previous: Set<String>) {
        val app = context.applicationContext
        try {
            if (!configured() || !enabled(app) || account(app) == null) return
            val token = silentToken(app) ?: run { setLastError(app, "Sign in again"); return }
            val folders = cachedFolders(app) ?: ensureFolders(app, token)
            if (remoteDbTime(token, folders) > snapAt) {
                setLastError(app, "Drive has a newer backup")
                return
            }
            val res = push(app, token, folders, artifacts, previous)
            prefs(app).edit().putLong(PREF_DRIVE_LAST_AT, snapAt).remove(PREF_DRIVE_LAST_ERROR).apply()
            Log.i(TAG, "Drive push ok: " + res.uploaded + " up, " + res.pruned + " pruned")
        } catch (e: Exception) {
            Log.e(TAG, "Drive auto push failed", e)
            setLastError(app, e.message ?: "Drive sync failed")
        }
    }

    fun cachedFolders(context: Context): FolderIds? {
        return try {
            val j = JSONObject(folderJson(context) ?: return null)
            val root = j.optString("root", "")
            if (root.isEmpty()) return null
            val kids = SUBFOLDERS.associateWith { j.optString(it, "") }.filterValues { it.isNotEmpty() }
            if (kids.size != SUBFOLDERS.size) return null
            FolderIds(root, kids)
        } catch (_: Exception) {
            null
        }
    }

    fun folderJson(context: Context): String? =
        prefs(context).getString(PREF_DRIVE_FOLDER_IDS, null)?.takeIf { it.isNotBlank() }

    /** Short human reason for a sign-in/authorize failure. Shown on the
     *  Backup page, so a failure reports itself instead of "Sign in failed"
     *  with nothing behind it. */
    fun failureReason(e: Exception): String {
        Log.e(TAG, "Drive auth failed", e)
        return when (e) {
            is DriveResolutionRequired -> "Needs consent"
            is GetCredentialCancellationException -> "Sign in cancelled"
            is NoCredentialException -> "No Google account on device"
            is GoogleIdTokenParsingException -> "Bad token response"
            is ApiException -> when (e.statusCode) {
                7 -> "No connection"
                10 -> "App not registered (SHA-1)"
                16 -> "Sign in cancelled"
                12500 -> "Google rejected sign in (account?)"
                12501 -> "Sign in cancelled"
                else -> "Google error " + e.statusCode
            }
            else -> (e.message?.take(80)?.ifBlank { null } ?: "Sign in failed")
        }
    }

    private fun silentToken(context: Context): String? = try {
        val req = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_FILE_SCOPE)))
            .build()
        Tasks.await(Identity.getAuthorizationClient(context).authorize(req), 30, TimeUnit.SECONDS).accessToken
    } catch (_: Exception) {
        null
    }

    private fun listFiles(token: String, folderId: String): MutableMap<String, Remote> {
        val out = mutableMapOf<String, Remote>()
        var page: String? = null
        do {
            var url = "https://www.googleapis.com/drive/v3/files?q=" +
                URLEncoder.encode("'$folderId' in parents and trashed = false", "UTF-8") +
                "&fields=files(id,name,modifiedTime,appProperties),nextPageToken&spaces=drive&pageSize=1000"
            if (page != null) url += "&pageToken=" + URLEncoder.encode(page, "UTF-8")
            val j = driveCall(token, "GET", url, null)
            val arr = j.optJSONArray("files") ?: break
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out[o.optString("name", "")] =
                    Remote(o.optString("id", ""), o.optJSONObject("appProperties")?.optString("kiloHash"))
            }
            page = j.optString("nextPageToken", null)?.takeIf { it.isNotEmpty() }
        } while (page != null)
        return out
    }

    private fun createFile(token: String, folderId: String, name: String, mime: String, bytes: ByteArray, dir: String, hash: String): String {
        val id = uploadFile(token, "POST",
            "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id",
            name, mime, bytes, dir, hash, folderId)
        return id ?: throw IllegalStateException("Drive create returned no id")
    }

    private fun updateFile(token: String, fileId: String, name: String, mime: String, bytes: ByteArray, dir: String, hash: String) {
        uploadFile(token, "PATCH",
            "https://www.googleapis.com/upload/drive/v3/files/$fileId?uploadType=multipart&fields=id",
            name, mime, bytes, dir, hash, null)
    }

    private fun deleteFile(token: String, fileId: String): Boolean = try {
        driveCall(token, "DELETE", "https://www.googleapis.com/drive/v3/files/$fileId", null)
        true
    } catch (_: Exception) {
        false
    }

    private fun uploadFile(
        token: String, method: String, url: String, name: String, mime: String,
        bytes: ByteArray, dir: String, hash: String, parent: String?
    ): String? {
        val boundary = "kiloapp" + System.currentTimeMillis()
        val meta = JSONObject().put("name", name).put("mimeType", mime)
            .put("appProperties", JSONObject().put("kilo", "1").put("kind", dir).put("kiloHash", hash))
        if (parent != null) meta.put("parents", JSONArray().put(parent))
        val head = ("--" + boundary + "\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n" +
            meta.toString() + "\r\n--" + boundary + "\r\nContent-Type: " + mime + "\r\n\r\n")
            .toByteArray(Charsets.UTF_8)
        val tail = ("\r\n--" + boundary + "--\r\n").toByteArray(Charsets.UTF_8)
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            doOutput = true
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
            outputStream.use { it.write(head); it.write(bytes); it.write(tail) }
        }
        return readJson(c, method).optString("id", null)
    }

    private fun driveBytes(token: String, fileId: String): ByteArray {
        val c = (URL("https://www.googleapis.com/drive/v3/files/$fileId?alt=media").openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Authorization", "Bearer $token")
        }
        if (c.responseCode !in 200..299) {
            val err = try {
                c.errorStream?.bufferedReader()?.readText() ?: ""
            } catch (_: Exception) {
                ""
            }
            throw IllegalStateException("Drive download " + c.responseCode + ": " + err.take(200))
        }
        return c.inputStream.use { it.readBytes() }
    }

    private fun readJson(c: HttpURLConnection, what: String): JSONObject {
        val code = c.responseCode
        val text = try {
            (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
        } catch (_: Exception) {
            ""
        }
        if (code !in 200..299) throw IllegalStateException("Drive $what $code: ${text.take(200)}")
        return if (text.isEmpty()) JSONObject() else JSONObject(text)
    }

    private fun sha256(bytes: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(d.size * 2)
        for (b in d) sb.append(((b.toInt() and 0xff) + 0x100).toString(16).substring(1))
        return sb.toString()
    }

    private fun parseTime(raw: String): Long {
        for (pat in listOf("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'")) {
            try {
                return SimpleDateFormat(pat, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.parse(raw)?.time ?: 0
            } catch (_: Exception) {
            }
        }
        return 0
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resumeWithException(it) }
        addOnCanceledListener { cont.cancel() }
    }

    private fun prefs(context: Context) =
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
}
