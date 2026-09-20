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

private val PrefixIsoFull = mapOf(
    "1" to "CA",
    "7" to "RU",
    "20" to "EG",
    "27" to "ZA",
    "30" to "GR",
    "31" to "NL",
    "32" to "BE",
    "33" to "FR",
    "34" to "ES",
    "36" to "HU",
    "39" to "IT",
    "40" to "RO",
    "41" to "CH",
    "43" to "AT",
    "44" to "GB",
    "45" to "DK",
    "46" to "SE",
    "47" to "NO",
    "48" to "PL",
    "49" to "DE",
    "51" to "PE",
    "52" to "MX",
    "53" to "CU",
    "54" to "AR",
    "55" to "BR",
    "56" to "CL",
    "57" to "CO",
    "58" to "VE",
    "60" to "MY",
    "61" to "AU",
    "62" to "ID",
    "63" to "PH",
    "64" to "NZ",
    "65" to "SG",
    "66" to "TH",
    "81" to "JP",
    "82" to "KR",
    "84" to "VN",
    "86" to "CN",
    "90" to "TR",
    "91" to "IN",
    "92" to "PK",
    "93" to "AF",
    "94" to "LK",
    "95" to "MM",
    "98" to "IR",
    "211" to "SS",
    "212" to "MA",
    "213" to "DZ",
    "216" to "TN",
    "218" to "LY",
    "220" to "GM",
    "221" to "SN",
    "222" to "MR",
    "223" to "ML",
    "224" to "GN",
    "225" to "CI",
    "226" to "BF",
    "227" to "NE",
    "228" to "TG",
    "229" to "BJ",
    "230" to "MU",
    "231" to "LR",
    "232" to "SL",
    "233" to "GH",
    "234" to "NG",
    "235" to "TD",
    "236" to "CF",
    "237" to "CM",
    "238" to "CV",
    "239" to "ST",
    "240" to "GQ",
    "241" to "GA",
    "242" to "CG",
    "243" to "CD",
    "244" to "AO",
    "245" to "GW",
    "246" to "IO",
    "247" to "AC",
    "248" to "SC",
    "249" to "SD",
    "250" to "RW",
    "251" to "ET",
    "252" to "SO",
    "253" to "DJ",
    "254" to "KE",
    "255" to "TZ",
    "256" to "UG",
    "257" to "BI",
    "258" to "MZ",
    "260" to "ZM",
    "261" to "MG",
    "262" to "RE",
    "263" to "ZW",
    "264" to "NA",
    "265" to "MW",
    "266" to "LS",
    "267" to "BW",
    "268" to "SZ",
    "269" to "KM",
    "290" to "SH",
    "291" to "ER",
    "297" to "AW",
    "298" to "FO",
    "299" to "GL",
    "350" to "GI",
    "351" to "PT",
    "352" to "LU",
    "353" to "IE",
    "354" to "IS",
    "355" to "AL",
    "356" to "MT",
    "357" to "CY",
    "358" to "FI",
    "359" to "BG",
    "370" to "LT",
    "371" to "LV",
    "372" to "EE",
    "373" to "MD",
    "374" to "AM",
    "375" to "BY",
    "376" to "AD",
    "377" to "MC",
    "378" to "SM",
    "379" to "VA",
    "380" to "UA",
    "381" to "RS",
    "382" to "ME",
    "383" to "XK",
    "385" to "HR",
    "386" to "SI",
    "387" to "BA",
    "389" to "MK",
    "420" to "CZ",
    "421" to "SK",
    "423" to "LI",
    "500" to "FK",
    "501" to "BZ",
    "502" to "GT",
    "503" to "SV",
    "504" to "HN",
    "505" to "NI",
    "506" to "CR",
    "507" to "PA",
    "508" to "PM",
    "509" to "HT",
    "590" to "GP",
    "591" to "BO",
    "592" to "GY",
    "593" to "EC",
    "594" to "GF",
    "595" to "PY",
    "596" to "MQ",
    "597" to "SR",
    "598" to "UY",
    "599" to "BQ",
    "670" to "TL",
    "672" to "NF",
    "673" to "BN",
    "674" to "NR",
    "675" to "PG",
    "676" to "TO",
    "677" to "SB",
    "678" to "VU",
    "679" to "FJ",
    "680" to "PW",
    "681" to "WF",
    "682" to "CK",
    "683" to "NU",
    "684" to "AS",
    "685" to "WS",
    "686" to "KI",
    "687" to "NC",
    "688" to "TV",
    "689" to "PF",
    "690" to "TK",
    "691" to "FM",
    "692" to "MH",
    "850" to "KP",
    "852" to "HK",
    "853" to "MO",
    "855" to "KH",
    "856" to "LA",
    "870" to "PN",
    "880" to "BD",
    "886" to "TW",
    "960" to "MV",
    "961" to "LB",
    "962" to "JO",
    "963" to "SY",
    "964" to "IQ",
    "965" to "KW",
    "966" to "SA",
    "967" to "YE",
    "968" to "OM",
    "970" to "PS",
    "971" to "AE",
    "972" to "IL",
    "973" to "BH",
    "974" to "QA",
    "975" to "BT",
    "976" to "MN",
    "977" to "NP",
    "992" to "TJ",
    "993" to "TM",
    "994" to "AZ",
    "995" to "GE",
    "996" to "KG",
    "998" to "UZ",
)

fun smsFlagFor(iso: String): String {
    if (iso.length != 2) return ""
    return iso.uppercase().map { 0x1F1E6 + (it.code - 'A'.code) }
        .map { Character.toChars(it).concatToString() }.joinToString("")
}

private fun isoForPrefix(prefix: String): String? {
    for (len in 4 downTo 1) {
        if (prefix.length >= len) {
            PrefixIsoFull[prefix.substring(0, len)]?.let { return it }
        }
    }
    return null
}

fun smsNameFlagForIso(iso: String): Pair<String, String> {
    if (iso.length != 2) return iso to ""
    val name = try {
        Locale("", iso).displayCountry.ifEmpty { iso }
    } catch (e: Exception) {
        iso
    }
    return name to smsFlagFor(iso)
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
