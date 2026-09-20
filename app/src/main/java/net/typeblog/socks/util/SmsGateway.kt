package net.typeblog.socks.util

import android.util.Log
import net.typeblog.socks.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal client for the Go SMS gateway (see sms core/).
 * Stdlib HttpURLConnection + org.json only — no new dependencies.
 * Auth is the global app key baked via BuildConfig SMS_API_KEY.
 */
object SmsGateway {
    private const val TAG = "SmsGateway"
    private const val TIMEOUT_MS = 15000

    data class FeedItem(
        val masked: String,
        val svc: String,
        val method: String,
        val app: String,
        val code: String,
        val msg: String,
        val range: String,
        val at: Long,
        val lang: String = "",
        val appLabel: String = "",
        val methodLabel: String = "",
        val iso: String = "",
    ) {
        val appName: String get() = appLabel.ifEmpty { app }
        val methodName: String get() = methodLabel.ifEmpty { method }
    }

    data class GatewayNumber(
        val full: String,
        val display: String,
        val country: String,
        val range: String,
    )

    data class OtpState(
        val code: String?,
        val msgs: List<Pair<String, String>>,
    )

    fun provision(range: String): GatewayNumber? {
        if (!isConfigured) return null
        var conn: HttpURLConnection? = null
        return try {
            val body = JSONObject().put("range", range).toString()
            conn = (URL(BuildConfig.SMS_GATEWAY_URL + "/v1/numbers").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Authorization", "Bearer ${BuildConfig.SMS_API_KEY}")
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                doOutput = true
            }
            conn.outputStream.bufferedWriter().use { it.write(body) }
            if (conn.responseCode != 200) {
                Log.w(TAG, "POST /v1/numbers -> ${conn.responseCode}")
                return null
            }
            val root = JSONObject(conn.inputStream.bufferedReader().readText())
            if (!root.optBoolean("ok")) return null
            val n = root.optJSONObject("number") ?: return null
            GatewayNumber(
                full = n.optString("full"),
                display = n.optString("display"),
                country = n.optString("country"),
                range = n.optString("range"),
            )
        } catch (e: Exception) {
            Log.w(TAG, "POST /v1/numbers failed: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    fun otp(number: String, since: Long = 0): OtpState? {
        val root = get("/v1/otp?number=$number&since=$since") ?: return null
        if (!root.optBoolean("ok")) return null
        val msgs = mutableListOf<Pair<String, String>>()
        val arr = root.optJSONArray("msgs")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                msgs.add(o.optString("code") to o.optString("text"))
            }
        }
        val code = if (root.isNull("code")) null else root.optString("code")
        return OtpState(code, msgs)
    }

    val isConfigured: Boolean
        get() = BuildConfig.SMS_API_KEY.isNotEmpty()

    private fun get(path: String): JSONObject? {
        if (!isConfigured) return null
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(BuildConfig.SMS_GATEWAY_URL + path).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer ${BuildConfig.SMS_API_KEY}")
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
            }
            if (conn.responseCode != 200) {
                Log.w(TAG, "GET $path -> ${conn.responseCode}")
                return null
            }
            JSONObject(conn.inputStream.bufferedReader().readText())
        } catch (e: Exception) {
            Log.w(TAG, "GET $path failed: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    fun feed(limit: Int = 20): List<FeedItem>? {
        val root = get("/v1/feed?limit=$limit") ?: return null
        if (!root.optBoolean("ok")) return null
        val out = mutableListOf<FeedItem>()
        val arr = root.optJSONArray("items") ?: return out
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                FeedItem(
                    masked = o.optString("masked"),
                    svc = o.optString("svc"),
                    method = o.optString("method"),
                    app = o.optString("app"),
                    code = o.optString("code"),
                    msg = o.optString("msg"),
                    range = o.optString("range"),
                    at = o.optLong("at"),
                    lang = o.optString("lang"),
                    appLabel = o.optString("appLabel"),
                    methodLabel = o.optString("methodLabel"),
                    iso = o.optString("iso"),
                )
            )
        }
        return out
    }

    fun meta(): Pair<List<String>, List<String>>? {
        val root = get("/v1/meta") ?: return null
        if (!root.optBoolean("ok")) return null
        val countries = mutableListOf<String>()
        val cArr = root.optJSONArray("countries")
        if (cArr != null) {
            for (i in 0 until cArr.length()) {
                countries.add(cArr.optJSONObject(i)?.optString("prefix") ?: "")
            }
        }
        val services = mutableListOf<String>()
        val sArr = root.optJSONArray("services")
        if (sArr != null) {
            for (i in 0 until sArr.length()) {
                services.add(sArr.optJSONObject(i)?.optString("name") ?: "")
            }
        }
        return countries to services
    }
}
