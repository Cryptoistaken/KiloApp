package net.typeblog.socks.util

import android.util.Log
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import org.json.JSONObject

/**
 * On-device Facebook account probe for the SMS fresh check.
 *
 * Asks Facebook's own account-recovery GraphQL whether a number is already
 * registered. Ported from the KiloSMS FreshChecker service, minus its proxy
 * pool: app traffic already leaves through [net.typeblog.socks.SocksVpnService]'s
 * tunnel, so the active profile supplies the IP rotation the bot gets from
 * owlproxy. Fewer exits, and quality varies by profile.
 *
 * No session, no cookies, no LSD token. The endpoint does not validate them,
 * so the old cookie dance is deliberately absent here.
 *
 * Blocking, with worker-identical timeouts. Invoke from Dispatchers.IO only.
 */
object SmsFresh {
    private const val TAG = "SmsFresh"
    private const val URL = "https://www.facebook.com/api/graphql/"
    private const val DOC_ID = "26328147246854413"
    private const val TIMEOUT_MS = 20000

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

    /** fresh means no account was found, so the number is safe to register on. */
    data class Result(val ok: Boolean, val fresh: Boolean, val error: String? = null)

    private fun body(phone: String): String {
        val vars = JSONObject().put("params", JSONObject().apply {
            put("cipher_text", JSONObject.NULL)
            put("context", "recover")
            put("event_request_id", UUID.randomUUID().toString())
            put("friend_name", "")
            put("search_query", phone)
            put("waterfall_id", UUID.randomUUID().toString())
        }).toString()
        val p = java.net.URLEncoder.encode("variables", "UTF-8") + "=" +
            java.net.URLEncoder.encode(vars, "UTF-8") + "&" +
            java.net.URLEncoder.encode("doc_id", "UTF-8") + "=" + DOC_ID
        return p
    }

    /**
     * Blocking probe. [phone] with or without a leading plus. A failed probe
     * is never a pass: it returns ok=false so the caller can stop rather than
     * hand back a number nobody verified.
     */
    fun check(phone: String): Result {
        val p = if (phone.startsWith("+")) phone else "+$phone"
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                setRequestProperty("Origin", "https://www.facebook.com")
                setRequestProperty("Referer", "https://www.facebook.com/login/identify/")
            }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body(p)) }

            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "graphql -> $code")
                return Result(false, false, "HTTP $code")
            }
            var text = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            // Facebook's JSON responses are anti-hijack padded.
            if (text.startsWith("for (;;);")) text = text.substring(9)

            val root = JSONObject(text)
            val search = root.optJSONObject("data")?.optJSONObject("caa_ar_fb_account_search")
                ?: return Result(false, false, "Empty response")
            val accounts = search.optJSONArray("accounts")
            val found = accounts != null && accounts.length() > 0
            Log.i(TAG, "$p -> ${if (found) "USED" else "FRESH"}")
            Result(true, !found)
        } catch (e: Exception) {
            Log.w(TAG, "graphql failed: ${e.message}")
            Result(false, false, e.message ?: "request failed")
        } finally {
            conn?.disconnect()
        }
    }
}
