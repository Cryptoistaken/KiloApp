package net.typeblog.socks.util

import android.util.Log
import net.typeblog.socks.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal client for the KiloSMS bot ext API (see KiloSMS bot/server.js).
 * Stdlib HttpURLConnection + org.json only — no new dependencies.
 * Auth is the Telegram-login admin session cookie, never a baked key.
 */
object SmsGateway {
    private const val TAG = "SmsGateway"
    private const val TIMEOUT_MS = 15000
    private const val GENERATE_TIMEOUT_MS = 120000

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

    data class MetaCountry(val prefix: String, val range: String)

    data class GatewayNumber(
        val full: String,
        val display: String,
        val country: String,
        val range: String,
        val verified: Boolean = false,
        val used: Boolean = false,
    )

    data class OtpMessage(
        val code: String,
        val text: String,
        val at: Long,
        val app: String = "",
    )

    data class OtpState(
        val code: String?,
        val msgs: List<OtpMessage>,
        val app: String = "",
    )

    data class GenerateResult(
        val number: GatewayNumber?,
        val checked: Int = 0,
        val discarded: Int = 0,
        val checkerDown: Boolean = false,
        val error: String = "",
    )

    val isConfigured: Boolean
        get() = SmsAuth.session().isNotEmpty()

    private fun authed(path: String, timeout: Int = TIMEOUT_MS): HttpURLConnection? {
        val cookie = SmsAuth.session()
        if (cookie.isEmpty()) return null
        return (URL(BuildConfig.KILOSMS_URL + path).openConnection() as HttpURLConnection).apply {
            setRequestProperty("Cookie", "admin_session=$cookie")
            connectTimeout = TIMEOUT_MS
            readTimeout = timeout
        }
    }

    private fun get(path: String): JSONObject? {
        val conn = authed(path) ?: return null
        return try {
            conn.requestMethod = "GET"
            if (conn.responseCode != 200) {
                Log.w(TAG, "GET $path -> ${conn.responseCode}")
                return null
            }
            JSONObject(conn.inputStream.bufferedReader().readText())
        } catch (e: Exception) {
            Log.w(TAG, "GET $path failed: ${e.message}")
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun safeStr(o: JSONObject, key: String): String {
        if (o.isNull(key)) return ""
        val v = o.optString(key)
        if (v.isBlank() || v.equals("null", ignoreCase = true)) return ""
        return v
    }

    /**
     * Server-checked provisioning: the bot round-robins its providers and
     * screens every number through its checker fleet before keeping the
     * ones matching [mode] (f = fresh, u = used, null = any).
     * The response is NDJSON progress lines plus one final result line.
     */
    fun generate(range: String, mode: Char?, onProgress: ((String) -> Unit)? = null): GenerateResult? {
        val conn = authed("/api/ext/generate", GENERATE_TIMEOUT_MS) ?: return null
        return try {
            val body = JSONObject()
                .put("range", range)
                .put("mode", mode?.toString() ?: "any")
                .put("count", 1)
                .put("service", "SMS")
                .toString()
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.outputStream.bufferedWriter().use { it.write(body) }
            if (conn.responseCode != 200) {
                Log.w(TAG, "POST /api/ext/generate -> ${conn.responseCode}")
                return GenerateResult(null, error = "Server error ${conn.responseCode}")
            }
            val lines = mutableListOf<String>()
            conn.inputStream.bufferedReader().use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    val t = line.trim()
                    if (t.startsWith("{")) {
                        try {
                            val o = JSONObject(t)
                            if (o.optString("type") == "progress") {
                                val text = safeStr(o, "text")
                                if (text.isNotEmpty()) onProgress?.invoke(text)
                                continue
                            }
                        } catch (_: Exception) {
                        }
                    }
                    lines.add(line)
                }
            }
            for (i in lines.size - 1 downTo 0) {
                val line = lines[i].trim()
                if (!line.startsWith("{")) continue
                val o = try {
                    JSONObject(line)
                } catch (_: Exception) {
                    continue
                }
                when (o.optString("type")) {
                    "result" -> {
                        val arr = o.optJSONArray("numbers")
                        val n = if (arr != null && arr.length() > 0) arr.optJSONObject(0) else null
                        if (n == null) {
                            val stats = o.optJSONObject("stats")
                            val down = stats?.optBoolean("checkerDown") == true
                            return GenerateResult(
                                null,
                                checked = stats?.optInt("checked") ?: 0,
                                discarded = stats?.optInt("discarded") ?: 0,
                                checkerDown = down,
                                error = if (down) "checker unavailable" else "none matching",
                            )
                        }
                        val fullNumber = safeStr(n, "fullNumber")
                        val digits = fullNumber.filter { it.isDigit() }
                        val st = o.optJSONObject("stats")
                        return GenerateResult(
                            GatewayNumber(
                                full = digits.ifEmpty { safeStr(n, "number") },
                                display = fullNumber.ifEmpty { "+" + safeStr(n, "number") },
                                country = safeStr(n, "country").ifEmpty { "Unknown" },
                                range = safeStr(n, "range").ifEmpty { range },
                                verified = n.optBoolean("verified"),
                                used = n.optBoolean("used"),
                            ),
                            checked = st?.optInt("checked") ?: 0,
                            discarded = st?.optInt("discarded") ?: 0,
                            checkerDown = st?.optBoolean("checkerDown") == true,
                        )
                    }
                    "error" -> return GenerateResult(null, error = o.optString("error").ifEmpty { "generate failed" })
                    else -> Unit
                }
            }
            GenerateResult(null, error = "empty response")
        } catch (e: Exception) {
            Log.w(TAG, "POST /api/ext/generate failed: ${e.message}")
            null
        } finally {
            conn.disconnect()
        }
    }

    /** Latest OTP for one number. The bot keeps one match per number. */
    fun otp(number: String, since: Long = 0): OtpState? {
        val digits = number.filter { it.isDigit() }
        val root = get("/api/ext/otp/$digits") ?: return null
        if (!root.optBoolean("ok") || !root.optBoolean("found")) {
            return OtpState(null, emptyList())
        }
        val code = safeStr(root, "code").ifEmpty { null } ?: return OtpState(null, emptyList())
        val text = safeStr(root, "message")
        return OtpState(code, listOf(OtpMessage(code, text, System.currentTimeMillis())))
    }

    private val codeRe = Regex("""\d{4,8}""")

    fun feed(limit: Int = 100): List<FeedItem>? {
        val root = get("/api/ext/feed?limit=$limit") ?: return null
        if (!root.optBoolean("ok")) return null
        val out = mutableListOf<FeedItem>()
        val arr = root.optJSONArray("feed") ?: return out
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val msg = safeStr(o, "message")
            val range = safeStr(o, "range")
            if (range.isEmpty()) continue
            out.add(
                FeedItem(
                    masked = if (range.startsWith("+")) range else "+$range",
                    svc = safeStr(o, "sid"),
                    method = safeStr(o, "method"),
                    app = safeStr(o, "app"),
                    code = codeRe.find(msg.replace(" ", ""))?.value ?: "",
                    msg = msg,
                    range = range,
                    at = o.optLong("time"),
                )
            )
        }
        return out
    }

    fun meta(): Pair<List<MetaCountry>, List<String>>? {
        val root = get("/api/ext/meta") ?: return null
        if (!root.optBoolean("ok")) return null
        val countries = mutableListOf<MetaCountry>()
        val cArr = root.optJSONArray("countries")
        if (cArr != null) {
            for (i in 0 until cArr.length()) {
                val o = cArr.optJSONObject(i) ?: continue
                countries.add(MetaCountry(o.optString("prefix"), o.optString("range")))
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
