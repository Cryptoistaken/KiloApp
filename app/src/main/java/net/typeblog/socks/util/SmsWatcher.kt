package net.typeblog.socks.util

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.typeblog.socks.BuildConfig
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

const val SMS_EXPIRE_SEC = 7 * 60L

data class SmsMsg(val code: String, val text: String, val at: Long)

class SmsNum(
    val id: Long,
    var display: String,
    var full: String,
    var country: String,
    var flag: String,
    var range: String,
    var born: Long,
    var svc: String = "",
    var code: String? = null,
    val msgs: MutableList<SmsMsg> = mutableListOf(),
)

data class SmsCountry(val name: String, val flag: String, val prefix: String, val count: Int)

private val PrefixIso = mapOf(
    "229" to "BJ", "233" to "GH", "234" to "NG", "261" to "MG",
    "225" to "CI", "226" to "BF", "227" to "NE", "228" to "TG",
    "237" to "CM", "236" to "CF", "251" to "ET", "380" to "UA",
    "220" to "GM", "221" to "SN", "222" to "MR", "223" to "ML",
    "224" to "GN", "230" to "MU", "231" to "LR", "232" to "SL",
    "235" to "TD", "250" to "RW", "254" to "KE", "255" to "TZ",
    "256" to "UG", "20" to "EG", "27" to "ZA", "212" to "MA",
    "213" to "DZ", "216" to "TN", "44" to "GB", "33" to "FR",
)

fun smsFlagFor(iso: String): String {
    if (iso.length != 2) return ""
    return iso.uppercase().map { 0x1F1E6 + (it.code - 'A'.code) }
        .map { Character.toChars(it).concatToString() }.joinToString("")
}

private fun isoForPrefix(prefix: String): String? {
    for (len in 3 downTo 1) {
        if (prefix.length >= len) {
            PrefixIso[prefix.substring(0, len)]?.let { return it }
        }
    }
    return null
}

fun smsCountryForPrefix(prefix: String): Pair<String, String> {
    val iso = isoForPrefix(prefix) ?: return "Unknown" to ""
    val name = try {
        Locale("", iso).displayCountry.ifEmpty { iso }
    } catch (e: Exception) {
        iso
    }
    return name to smsFlagFor(iso)
}

fun smsMaskNum(full: String): String {
    val digits = full.filter { it.isDigit() }
    if (digits.length < 5) return "+$full"
    val i = digits.length / 2
    val m = digits.substring(0, i) + "X" + digits.substring(i + 1)
    return "+" + m.chunked(3).joinToString(" ")
}

fun smsTimeAgo(ts: Long, now: Long): String {
    val s = ((now - ts) / 1000).coerceAtLeast(0)
    if (s < 10) return "just now"
    if (s < 60) return "${s}s ago"
    val m = s / 60
    if (m < 60) return "${m}m ago"
    val h = m / 60
    if (h < 24) return "${h}h ago"
    return "${h / 24}d ago"
}

fun smsIsRangePat(s: String): Boolean {
    val t = s.replace(" ", "").replace("+", "")
    return t.length >= 3 && t.all { it.isDigit() || it == 'X' || it == 'x' } && t.any { it.isDigit() }
}

/**
 * App-scoped SMS state. Outlives the SMS tab so OTP polling and arrival
 * notifications keep working while the user is on other tabs.
 * Started once from the application class; polling only runs while the
 * app process is alive (no foreground service yet).
 */
object SmsWatcher {
    val mine = mutableStateListOf<SmsNum>()
    val expired = mutableStateListOf<SmsNum>()
    val feed = mutableStateListOf<SmsGateway.FeedItem>()
    val countries = mutableStateListOf<SmsCountry>()
    var now by mutableLongStateOf(System.currentTimeMillis())
    var busy by mutableStateOf(false)
    var error by mutableStateOf("")
    var errorAt by mutableLongStateOf(0L)
    // Last time the push stream delivered anything (event or heartbeat).
    // While fresh, the 5s OTP poll stands down to save requests.
    private var streamAliveAt = 0L

    private var nextId = 1L
    private var started = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var app: Context? = null

    fun fail(msg: String) {
        error = msg
        errorAt = System.currentTimeMillis()
    }

    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true
        app = context.applicationContext
        scope.launch {
            loadFeed()
            var tick = 0
            while (true) {
                delay(1000)
                tick++
                now = System.currentTimeMillis()
                val died = mine.filter { now - it.born >= SMS_EXPIRE_SEC * 1000 }
                if (died.isNotEmpty()) {
                    mine.removeAll(died)
                    expired.addAll(0, died)
                }
                if (tick % 5 == 0 && mine.any { it.code == null }) pollOtps()
                if (tick % 60 == 0) loadFeed()
            }
        }
        scope.launch { streamLoop() }
    }

    fun loadFeed() {
        scope.launch {
            val result = withContext(Dispatchers.IO) { SmsGateway.feed(20) }
            if (result != null) {
                feed.clear()
                feed.addAll(result)
            } else if (feed.isEmpty()) {
                fail("Could not load feed")
            }
            val meta = withContext(Dispatchers.IO) { SmsGateway.meta() }
            if (meta != null) {
                countries.clear()
                meta.first.forEach { prefix ->
                    val (name, flag) = smsCountryForPrefix(prefix)
                    val count = feed.count { it.range.startsWith(prefix) }
                    countries.add(SmsCountry(name, flag, prefix, count))
                }
                countries.sortByDescending { it.count }
            }
        }
    }

    fun provision(pat: String, onDone: (SmsNum?) -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            val g = withContext(Dispatchers.IO) { SmsGateway.provision(pat) }
            busy = false
            if (g == null || g.full.isEmpty()) {
                fail("No numbers available, try again")
                onDone(null)
                return@launch
            }
            val (name, flag) = smsCountryForPrefix(pat.replace("X", "").replace("x", ""))
            val actualName = if (g.country.isNotEmpty() && g.country != "Unknown") g.country else name
            val n = SmsNum(
                id = nextId++,
                display = g.display.ifEmpty { "+" + g.full },
                full = g.full,
                country = actualName,
                flag = flag,
                range = pat.uppercase(),
                born = System.currentTimeMillis(),
            )
            mine.add(0, n)
            onDone(n)
        }
    }

    private fun pollOtps() {
        if (System.currentTimeMillis() - streamAliveAt < 45000) return // push stream is healthy
        scope.launch {
            mine.toList().forEach { n ->
                if (n.code != null) return@forEach
                val st = withContext(Dispatchers.IO) { SmsGateway.otp(n.full) } ?: return@forEach
                val latest = st.msgs.lastOrNull() ?: return@forEach
                if (latest.first.isEmpty()) return@forEach
                n.code = latest.first
                n.svc = "Facebook"
                n.msgs.clear()
                st.msgs.forEach { n.msgs.add(SmsMsg(it.first, it.second, n.born)) }
                app?.let { SmsNotify.showCode(it, n.display, latest.first, latest.second) }
            }
        }
    }

    private fun digitsOnly(s: String): String {
        return s.filter { it.isDigit() }
    }

    /**
     * SSE push stream: one connection while any number is still waiting,
     * subscribed to exactly those numbers. Reconnects with backoff, with
     * fast-fail protection so a broken route can never hammer the backend:
     * connections living under 15s count as fast fails; 3 in a row park
     * the loop for 60s, and attempts are always >= 10s apart.
     */
    private suspend fun streamLoop() {
        var backoff = 5000L
        var fastFails = 0
        var lastAttempt = 0L
        while (true) {
            val waiting = mine.filter { it.code == null }.map { it.full }
            if (waiting.isEmpty() || !SmsGateway.isConfigured) {
                delay(5000)
                continue
            }
            val sinceAttempt = System.currentTimeMillis() - lastAttempt
            if (sinceAttempt < 10000) {
                delay(10000 - sinceAttempt)
            }
            lastAttempt = System.currentTimeMillis()
            val connectedAt = lastAttempt
            try {
                withContext(Dispatchers.IO) { readStream(waiting) }
                // Clean server-side close: normal reconnect, keep base backoff.
                backoff = 5000L
                fastFails = 0
            } catch (e: Exception) {
                val lived = System.currentTimeMillis() - connectedAt
                if (lived < 15000) {
                    fastFails++
                } else {
                    fastFails = 0
                    backoff = 5000L
                }
                if (fastFails >= 3) {
                    delay(60000)
                    fastFails = 0
                    backoff = 10000L
                } else {
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(30000L)
                }
            }
        }
    }

    private fun readStream(numbers: List<String>) {
        val url = "${BuildConfig.SMS_GATEWAY_URL}/v1/stream?numbers=" + numbers.joinToString(",")
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer ${BuildConfig.SMS_API_KEY}")
                setRequestProperty("Accept", "text/event-stream")
                connectTimeout = 15000
                readTimeout = 60000
            }
            if (conn.responseCode != 200) throw IllegalStateException("stream ${conn.responseCode}")
            val reader: BufferedReader = conn.inputStream.bufferedReader()
            var event = ""
            var data = ""
            while (true) {
                val line = reader.readLine() ?: throw IllegalStateException("stream closed")
                streamAliveAt = System.currentTimeMillis()
                when {
                    line.startsWith("event:") -> event = line.substringAfter(":").trim()
                    line.startsWith("data:") -> data += line.substringAfter(":").trim()
                    line.startsWith(":") -> Unit // heartbeat comment
                    line.isEmpty() -> {
                        if (event == "otp" && data.isNotEmpty()) onStreamOtp(data)
                        event = ""
                        data = ""
                    }
                }
            }
        } finally {
            conn?.disconnect()
        }
    }

    private fun onStreamOtp(data: String) {
        try {
            val o = JSONObject(data)
            val number = digitsOnly(o.optString("number"))
            val code = o.optString("code")
            val text = o.optString("text")
            if (number.isEmpty() || code.isEmpty()) return
            val n = mine.firstOrNull { digitsOnly(it.full) == number } ?: return
            if (n.code != null) return
            n.code = code
            n.svc = "Facebook"
            n.msgs.clear()
            n.msgs.add(SmsMsg(code, text, n.born))
            app?.let { SmsNotify.showCode(it, n.display, code, text) }
        } catch (e: Exception) {
            // Malformed event: ignore, polling fallback covers it.
        }
    }
}
