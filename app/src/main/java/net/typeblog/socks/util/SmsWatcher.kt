package net.typeblog.socks.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlin.jvm.Volatile
import org.json.JSONArray
import org.json.JSONObject
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
    var app: String = "",
    /** Set when the server (or the fallback probe) cleared this number. */
    var fresh: Boolean = false,
    /** Set when the number is known to be already registered. */
    var used: Boolean = false,
    /** Range mode this number was asked for: "F", "U" or "". */
    var mode: String = "",
)

data class SmsCountry(
    val name: String,
    val flag: String,
    val prefix: String,
    val count: Int,
    val sampleRange: String = ""
)

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

/**
 * Emoji flag for an ISO-2 code. Empty string when the code is unusable, so
 * callers can hide the flag slot instead of drawing a placeholder.
 * Emoji generation lives in [Utility] (one home in util/); this only adapts
 * the empty-on-invalid contract the SMS rows rely on.
 */
fun smsFlagFor(iso: String): String {
    if (iso.length != 2) return ""
    return try {
        Utility.countryCodeToFlag(iso)
    } catch (_: Exception) {
        ""
    }
}

private fun isoForPrefix(prefix: String): String? {
    for (len in 4 downTo 1) {
        if (prefix.length >= len) {
            PrefixIsoFull[prefix.substring(0, len)]?.let { return it }
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

/** A typed range split into what the server gets and which check to apply. */
data class SmsRange(val range: String, val mode: Char?, val count: Int)

/**
 * Parses a typed range into its provision range and its fresh/used-check
 * mode. Ported from the KiloSMS parser: a `C`/`F` in the tail after the
 * range asks for a fresh number, `U` for a used one, a trailing 1-2 digit
 * group is a count, and a range with no wildcard gets `XXX` appended.
 * The suffix lives *after* the range, so it never reaches the server.
 *
 * The one home for this: the field, the hint, the provision call and the
 * pref writer all read the same result, so the typed text and the mode can
 * never disagree. Null when the text is not a range at all.
 */
fun smsParseRange(s: String): SmsRange? {
    val t = s.trim().uppercase().removePrefix("+")
    val base = Regex("^\\d{3,}X*").find(t)?.value ?: return null
    val tail = t.substring(base.length)
    // Count is any 1-2 digit group in the tail, so "F2" reads the same as it
    // does in the bot. Capped there too, and rejected below at 1 because
    // provision returns a single number.
    val count = Regex("\\d{1,2}").find(tail)?.value?.toIntOrNull()?.coerceAtMost(10) ?: 0
    val letter = Regex("[CFU]").find(tail)?.value?.firstOrNull()
    return SmsRange(
        range = if (base.contains('X')) base else base.take(6) + "XXX",
        mode = if (letter == 'C') 'F' else letter,
        count = count,
    )
}

/**
 * The range the app will actually act on, or null when the text asks for more
 * than one number. [smsParseRange] still reports a count so the caller can
 * tell "asked for 2" from "asked for none", but provision returns a single
 * SmsNum and a batch would mean reworking that path, so a count above 1 is
 * rejected rather than quietly serving one number.
 */
fun smsSingleRange(s: String): SmsRange? = smsParseRange(s)?.takeIf { it.count <= 1 }

/**
 * App-scoped SMS state. Outlives the SMS tab so OTP polling and arrival
 * notifications keep working while the user is on other tabs. A dataSync
 * foreground service ([SmsOtpService], started on every provision) keeps
 * the process + network alive while a number is waiting, so pickup is
 * instant even with the app backgrounded.
 */
object SmsWatcher {
    val mine = mutableStateListOf<SmsNum>()
    val expired = mutableStateListOf<SmsNum>()
    val feed = mutableStateListOf<SmsGateway.FeedItem>()
    val countries = mutableStateListOf<SmsCountry>()
    var now by mutableLongStateOf(System.currentTimeMillis())
    var busy by mutableStateOf(false)
    var progress by mutableStateOf("")
    var error by mutableStateOf("")
    var errorAt by mutableLongStateOf(0L)
    var revision by mutableLongStateOf(0L)
    var loggedIn by mutableStateOf(false)
    var isAdmin by mutableStateOf(false)

    /** Immediate feed refresh, e.g. right after login instead of the 60s tick. */
    fun refreshNow() {
        if (loggedIn) loadFeed()
    }

    // Fresh-check session totals, surfaced on the Activity page. A plain get
    // never touches these, so they only move for someone using the C suffix.
    var checkedCount by mutableIntStateOf(0)
    var freshCount by mutableIntStateOf(0)
    var skippedCount by mutableIntStateOf(0)

    private fun bumpChecked(n: Int) { checkedCount += n; persistCounters() }
    private fun bumpSkipped(n: Int) { skippedCount += n; persistCounters() }
    private fun bumpFresh() { freshCount += 1; persistCounters() }

    private fun persistCounters() {
        try {
            store?.edit()
                ?.putInt("fresh_checked", checkedCount)
                ?.putInt("fresh_fresh", freshCount)
                ?.putInt("fresh_skipped", skippedCount)
                ?.apply()
        } catch (_: Exception) {
        }
    }

    private fun loadCounters() {
        try {
            checkedCount = store?.getInt("fresh_checked", 0) ?: 0
            freshCount = store?.getInt("fresh_fresh", 0) ?: 0
            skippedCount = store?.getInt("fresh_skipped", 0) ?: 0
        } catch (_: Exception) {
        }
    }

    // OTP polling covers delivery on its own: the bot has no push stream,
    // so a 5s sweep while any number waits is the whole transport.
    @Volatile
    private var paused = false
    private var pollJob: Job? = null
    private val pollFinishers = mutableListOf<() -> Unit>()
    private val waitingNumbers = MutableStateFlow<List<String>>(emptyList())

    private var nextId = 1L
    private var started = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var app: Context? = null
    private var store: SharedPreferences? = null

    /**
     * Numbers + their SMS survive app updates and restarts (plain
     * SharedPreferences JSON, capped). Expiry is still 7 min from born,
     * so stale entries reload straight into Expired.
     */
    private fun save() {
        try {
            val arr = JSONArray()
            (mine + expired).take(40).forEach { n ->
                val o = JSONObject()
                o.put("id", n.id)
                o.put("display", n.display)
                o.put("full", n.full)
                o.put("country", n.country)
                o.put("range", n.range)
                o.put("born", n.born)
                o.put("svc", n.svc)
                o.put("code", n.code ?: "")
                o.put("app", n.app)
                o.put("f", n.fresh)
                o.put("u", n.used)
                o.put("mode", n.mode)
                val msgs = JSONArray()
                n.msgs.take(5).forEach { msgs.put(JSONObject().put("c", it.code).put("t", it.text).put("a", it.at)) }
                o.put("msgs", msgs)
                arr.put(o)
            }
            store?.edit()?.putString("nums_v1", arr.toString())?.putLong("next_id", nextId)?.apply()
        } catch (e: Exception) {
            // Persistence is best-effort; live state is unaffected.
        }
    }

    private fun load() {
        try {
            val raw = store?.getString("nums_v1", null) ?: return
            nextId = store?.getLong("next_id", 1L) ?: 1L
            val arr = JSONArray(raw)
            val loaded = mutableListOf<SmsNum>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val range = o.optString("range")
                val n = SmsNum(
                    id = o.optLong("id", nextId++),
                    display = o.optString("display").replace(" ", ""),
                    full = o.optString("full"),
                    country = o.optString("country"),
                    // The flag is derived from the range, never read back
                    // from disk: an older build stored the plain ISO code
                    // and that value would otherwise persist forever.
                    flag = smsCountryForPrefix(range.filter { it.isDigit() }).second,
                    range = range,
                    born = o.optLong("born"),
                    svc = o.optString("svc"),
                    code = o.optString("code").ifEmpty { null },
                    app = o.optString("app"),
                    fresh = o.optBoolean("f"),
                    used = o.optBoolean("u"),
                    mode = o.optString("mode"),
                )
                val msgs = o.optJSONArray("msgs")
                if (msgs != null) {
                    for (j in 0 until msgs.length()) {
                        val m = msgs.optJSONObject(j) ?: continue
                        n.msgs.add(SmsMsg(m.optString("c"), m.optString("t"), m.optLong("a")))
                    }
                }
                if (n.full.isNotEmpty()) loaded.add(n)
            }
            val t = System.currentTimeMillis()
            mine.clear()
            expired.clear()
            loaded.forEach {
                if (t - it.born >= SMS_EXPIRE_SEC * 1000) expired.add(it) else mine.add(it)
            }
            mine.sortByDescending { it.born }
            publishWaitingNumbers()
        } catch (e: Exception) {
            // Corrupt store: start fresh rather than crash.
        }
    }

    fun fail(msg: String) {
        error = msg
        errorAt = System.currentTimeMillis()
    }

    private fun publishWaitingNumbers() {
        val t = System.currentTimeMillis()
        waitingNumbers.value = mine
            .filter { it.code == null && t - it.born < SMS_EXPIRE_SEC * 1000 }
            .map { it.full }
        revision++
    }

    fun resumeWaiting() {
        paused = false
    }

    /**
     * Reloads numbers and counters from prefs after a backup replaced them.
     * Main thread only: the lists are Compose state.
     */
    fun reloadAfterRestore() {
        load()
        loadCounters()
        publishWaitingNumbers()
    }

    fun pauseWaiting(context: Context) {
        paused = true
        cancelHeartbeat(context)
    }

    fun cancelHeartbeat(context: Context) {
        try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            am.cancel(heartbeatPending(context))
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true
        app = context.applicationContext
        store = app!!.getSharedPreferences("sms_store", Context.MODE_PRIVATE)
        SmsAuth.init(context)
        loggedIn = SmsAuth.session().isNotEmpty()
        load()
        loadCounters()
        scope.launch {
            if (loggedIn) loadFeed()
            var tick = 0
            while (true) {
                delay(1000)
                tick++
                now = System.currentTimeMillis()
                val died = mine.filter { now - it.born >= SMS_EXPIRE_SEC * 1000 }
                if (died.isNotEmpty()) {
                    mine.removeAll(died)
                    expired.addAll(0, died)
                    if (expired.size > 30) expired.subList(30, expired.size).clear()
                    publishWaitingNumbers()
                    save()
                }
                if (loggedIn && !paused && tick % 5 == 0 && mine.any { it.code == null }) {
                    pollOtps()
                }
                if (tick % 60 == 0 && loggedIn) loadFeed()
            }
        }
    }

    fun loadFeed() {
        scope.launch {
            val result = withContext(Dispatchers.IO) { SmsGateway.feed(20) }
            if (result != null) {
                feed.clear()
                feed.addAll(result)
                revision++
            } else if (feed.isEmpty()) {
                fail("Could not load feed")
            }
            val meta = withContext(Dispatchers.IO) { SmsGateway.meta() }
            if (meta != null) {
                countries.clear()
                meta.first.forEach { mc ->
                    val (name, flag) = smsCountryForPrefix(mc.prefix)
                    val count = feed.count { it.range.startsWith(mc.prefix) }
                    countries.add(SmsCountry(name, flag, mc.prefix, count, mc.range))
                }
                countries.sortByDescending { it.count }
                revision++
            }
        }
    }

    /**
     * Provisions one number through the KiloSMS ext API, which screens it
     * server-side when [mode] is set. When the server could not verify
     * (checker down) the on-device [SmsFresh] probe labels the number as a
     * fallback instead of leaving it unknown.
     */
    fun provision(
        pat: String,
        replaceId: Long? = null,
        mode: Char? = null,
        onDone: (SmsNum?) -> Unit
    ) {
        if (busy) {
            onDone(null)
            return
        }
        if (!SmsGateway.isConfigured) {
            fail("Login with Telegram first")
            onDone(null)
            return
        }
        paused = false
        busy = true
        scope.launch {
            val modeStr = mode?.toString() ?: ""

            fun keep(g: SmsGateway.GatewayNumber, screened: Boolean, usedMark: Boolean): SmsNum {
                val (name, flag) = smsCountryForPrefix(pat.replace("X", "").replace("x", ""))
                val actualName = if (g.country.isNotEmpty() && g.country != "Unknown") g.country else name
                val n = SmsNum(
                    id = nextId++,
                    display = g.display.replace(" ", "").ifEmpty { "+" + g.full },
                    full = g.full,
                    country = actualName,
                    flag = flag,
                    range = pat.uppercase(),
                    born = System.currentTimeMillis(),
                    fresh = screened,
                    used = usedMark,
                    mode = modeStr,
                )
                if (replaceId != null) {
                    mine.removeAll { it.id == replaceId }
                    expired.removeAll { it.id == replaceId }
                }
                mine.add(0, n)
                publishWaitingNumbers()
                save()
                SmsLog.log(app, "GET", "provisioned ${n.display} range=$pat mode=$modeStr fresh=$screened used=$usedMark")
                return n
            }

            val res = withContext(Dispatchers.IO) {
                SmsGateway.generate(pat, mode) { text -> scope.launch { progress = text } }
            }
            if (res?.number != null) {
                val g = res.number
                bumpChecked(res.checked)
                if (res.discarded > 0) bumpSkipped(res.discarded)
                if (g.verified) bumpFresh()
                busy = false
                progress = ""
                app?.let {
                    SmsOtpService.start(it)
                    armHeartbeat(it)
                }
                onDone(keep(g, g.verified, g.used))
                return@launch
            }
            // Server could not serve a screened number: retry unchecked and
            // label locally with the on-device probe as a fallback.
            if (mode != null) {
                val plain = withContext(Dispatchers.IO) {
                    SmsGateway.generate(pat, null) { text -> scope.launch { progress = text } }
                }
                val g = plain?.number
                if (g != null && g.full.isNotEmpty()) {
                    val r = withContext(Dispatchers.IO) { SmsFresh.check(g.full) }
                    bumpChecked(1)
                    if (!r.ok) {
                        SmsLog.log(app, "CHECK", "${g.full} -> UNKNOWN (${r.error})")
                        busy = false
                        progress = ""
                        fail("Fresh check unavailable, try again")
                        app?.let { SmsNotify.buzzFail(it) }
                        onDone(keep(g, false, false))
                        return@launch
                    }
                    SmsLog.log(app, "CHECK", "${g.full} -> ${if (r.fresh) "FRESH" else "USED"} (local fallback)")
                    if (r.fresh) bumpFresh() else bumpSkipped(1)
                    busy = false
                    progress = ""
                    app?.let {
                        SmsOtpService.start(it)
                        armHeartbeat(it)
                    }
                    onDone(keep(g, r.fresh, !r.fresh))
                    return@launch
                }
            }
            busy = false
            progress = ""
            fail(if (!res?.error.isNullOrEmpty()) res!!.error else "No numbers available, try again")
            SmsLog.log(app, "GET", "provision FAILED for $pat")
            app?.let { SmsNotify.buzzFail(it) }
            onDone(null)
        }
    }

    private fun pollOtps(onFinished: (() -> Unit)? = null) {
        if (onFinished != null) pollFinishers += onFinished
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            try {
                pollOnce()
            } catch (e: Exception) {
                SmsLog.log(app, "POLL", "sweep failed: ${e.message}")
            } finally {
                pollJob = null
                val finishers = pollFinishers.toList()
                pollFinishers.clear()
                finishers.forEach { finish ->
                    try {
                        finish()
                    } catch (e: Exception) {
                        SmsLog.log(app, "POLL", "finish failed: ${e.message}")
                    }
                }
            }
        }
    }

    private fun applyOtp(
        number: String,
        code: String?,
        messages: List<SmsGateway.OtpMessage>,
        appName: String
    ): Boolean {
        val normalizedCode = code?.takeIf { it.isNotEmpty() }
            ?: messages.lastOrNull { it.code.isNotEmpty() }?.code
            ?: return false
        val n = mine.firstOrNull { digitsOnly(it.full) == number } ?: return false
        val now = System.currentTimeMillis()
        if (n.code != null || now - n.born >= SMS_EXPIRE_SEC * 1000) return false
        val normalizedMessages = messages.ifEmpty {
            listOf(SmsGateway.OtpMessage(normalizedCode, "", now, appName))
        }
        n.code = normalizedCode
        n.svc = "Facebook"
        val resolvedApp = appName.ifEmpty {
            normalizedMessages.lastOrNull { it.app.isNotEmpty() }?.app.orEmpty()
        }
        if (resolvedApp.isNotEmpty()) n.app = resolvedApp
        n.msgs.clear()
        normalizedMessages.forEach { message ->
            n.msgs.add(
                SmsMsg(
                    code = message.code,
                    text = message.text,
                    at = if (message.at > 0L) message.at else now,
                )
            )
        }
        publishWaitingNumbers()
        save()
        SmsLog.log(app, "OTP", "received $normalizedCode for $number")
        app?.let { SmsNotify.showCode(it, n.display, normalizedCode) }
        return true
    }

    /** One awaitable OTP sweep over all waiting numbers (parallel). */
    private suspend fun pollOnce() = supervisorScope {
        mine.toList().map { n ->
            async {
                if (n.code != null) return@async
                val since = n.msgs.maxOfOrNull { it.at } ?: 0L
                val state = withContext(Dispatchers.IO) { SmsGateway.otp(n.full, since) }
                if (state == null) {
                    SmsLog.log(app, "POLL", "fetch failed ${n.full}")
                    return@async
                }
                applyOtp(n.full, state.code, state.msgs, state.app)
            }
        }.awaitAll()
    }

    fun hasWaiting(): Boolean {
        val t = System.currentTimeMillis()
        return mine.any { it.code == null && t - it.born < SMS_EXPIRE_SEC * 1000 }
    }

    /**
     * RTC_WAKEUP heartbeat every 30s while numbers wait. Coroutine timers
     * and sockets can freeze with the process in background (vendor
     * freezers, Doze); an alarm still wakes the process briefly so at
     * least the poll runs. Self-chaining: each fire re-arms, stops when
     * nothing waits. Exact on API 31+ only with user grant, else inexact.
     */
    fun armHeartbeat(context: Context) {
        if (paused) return
        try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val pi = heartbeatPending(context)
            try {
                am.cancel(pi)
            } catch (_: Exception) {
            }
            if (!hasWaiting()) return
            val at = System.currentTimeMillis() + 30_000
            if (Build.VERSION.SDK_INT >= 31) {
                if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else if (Build.VERSION.SDK_INT >= 23) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                @Suppress("DEPRECATION") am.setExact(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (_: Exception) {
        }
    }

    private fun heartbeatPending(context: Context): PendingIntent {
        val i = Intent(context, SmsAlarmReceiver::class.java).setAction(SmsAlarmReceiver.ACTION_POLL)
        return PendingIntent.getBroadcast(
            context, 2001, i,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /** Alarm entry: poll once, re-arm the chain, release the receiver. */
    fun onHeartbeat(context: Context, finish: () -> Unit) {
        start(context.applicationContext)
        if (paused || !hasWaiting()) {
            finish()
            return
        }
        pollOtps {
            try {
                armHeartbeat(context.applicationContext)
            } catch (_: Exception) {
            }
            finish()
        }
    }

    private fun digitsOnly(s: String): String {
        return s.filter { it.isDigit() }
    }
}
