package net.typeblog.socks.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import net.typeblog.socks.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Telegram device-login for the SMS tab, mirroring the KiloSMS admin panel:
 * the app mints a device id, opens t.me/<bot>?start=login_<did>, then polls
 * /admin/api/auth/device until the bot links an admin_session to it.
 * The session cookie is the only credential the SMS tab uses afterwards.
 */
object SmsAuth {
    private const val TAG = "SmsAuth"
    private const val TIMEOUT_MS = 15000

    private var app: Context? = null
    private var store: SharedPreferences? = null

    fun init(context: Context) {
        app = context.applicationContext
        store = app!!.getSharedPreferences("sms_store", Context.MODE_PRIVATE)
    }

    fun session(): String = try {
        store?.getString("tg_session", "") ?: ""
    } catch (_: Exception) {
        ""
    }

    fun saveSession(cookie: String) {
        try {
            store?.edit()?.putString("tg_session", cookie)?.apply()
        } catch (_: Exception) {
        }
    }

    fun clear() {
        try {
            store?.edit()?.remove("tg_session")?.apply()
        } catch (_: Exception) {
        }
    }

    fun newDeviceId(): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        return (1..16).map { chars.random() }.joinToString("")
    }

    fun loginUrl(did: String, bot: String): String = "https://t.me/$bot?start=login_$did"

    /** Public bot info, no auth needed. Null when the backend is unreachable. */
    fun botUsername(): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(BuildConfig.KILOSMS_URL + "/admin/api/botinfo").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
            }
            if (conn.responseCode != 200) return null
            JSONObject(conn.inputStream.bufferedReader().readText()).optString("username").ifEmpty { null }
        } catch (e: Exception) {
            Log.w(TAG, "botinfo failed: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * One device-poll round. Returns the admin_session value once the user
     * tapped Login in the bot, null while still waiting or on failure.
     * Blocking: call from Dispatchers.IO only.
     */
    fun pollDevice(did: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(BuildConfig.KILOSMS_URL + "/admin/api/auth/device?token=$did").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = false
            }
            if (conn.responseCode != 200) return null
            if (!JSONObject(conn.inputStream.bufferedReader().readText()).optBoolean("ok")) return null
            conn.headerFields["Set-Cookie"]?.firstOrNull { it.startsWith("admin_session=") }
                ?.substringAfter("admin_session=")?.substringBefore(";")?.ifEmpty { null }
        } catch (e: Exception) {
            Log.w(TAG, "device poll failed: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** Best-effort server-side logout. Blocking: call from Dispatchers.IO only. */
    fun logoutRemote() {
        val cookie = session()
        if (cookie.isEmpty()) return
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(BuildConfig.KILOSMS_URL + "/admin/api/auth/logout").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Cookie", "admin_session=$cookie")
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
            }
            conn.responseCode
        } catch (_: Exception) {
        } finally {
            conn?.disconnect()
        }
    }
}
