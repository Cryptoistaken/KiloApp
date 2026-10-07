package net.typeblog.socks.util.sheet

import android.app.Activity
import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.preference.PreferenceManager
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.suspendCancellableCoroutine
import net.typeblog.socks.BuildConfig
import net.typeblog.socks.util.Constants.PREF_DRIVE_ACCOUNT
import net.typeblog.socks.util.Constants.PREF_DRIVE_ENABLED
import net.typeblog.socks.util.Constants.PREF_DRIVE_FOLDER_IDS
import net.typeblog.socks.util.Constants.PREF_DRIVE_LAST_AT
import net.typeblog.socks.util.Constants.PREF_DRIVE_LAST_ERROR
import org.json.JSONObject
import kotlin.coroutines.resume

// Phase 1: Google identity + Drive permission. Sign-in (Credential Manager),
// Drive grant (AuthorizationClient, drive.file only), folder ensure (plain
// Drive REST, SheetChecker-style). No uploads yet — Phase 2 reuses
// SheetBackup.artifacts for those, so both destinations share one renderer.
// Network runs on the calling thread: call from Dispatchers.IO.
object DriveSync {
    const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
    const val FOLDER_NAME = "KiloApp"
    val SUBFOLDERS = listOf("backup", "files", "archive", "config")
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
        val cred = GoogleIdTokenCredential.createFrom(resp.credential.data)
        prefs(activity).edit().putString(PREF_DRIVE_ACCOUNT, cred.id).apply()
        return SignIn(cred.id)
    }

    // One consent screen for drive.file: the app sees only files it created.
    // Returns a short-lived access token for Drive REST calls.
    suspend fun authorize(activity: Activity): String {
        val req = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_FILE_SCOPE)))
            .build()
        return Identity.getAuthorizationClient(activity).authorize(req).await().accessToken
            ?: throw IllegalStateException("Empty access token")
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
        val code = c.responseCode
        val text = try {
            (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
        } catch (_: Exception) {
            ""
        }
        if (code !in 200..299) throw IllegalStateException("Drive $method $code: ${text.take(200)}")
        return if (text.isEmpty()) JSONObject() else JSONObject(text)
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resumeWithException(it) }
        addOnCanceledListener { cont.cancel() }
    }

    private fun prefs(context: Context) =
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
}
