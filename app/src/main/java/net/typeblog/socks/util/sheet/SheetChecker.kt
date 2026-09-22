package net.typeblog.socks.util.sheet

import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject

// Direct port of the sheetsubmit backend worker checks
// (sheetsubmit/worker/index.ts) so the app verifies rows on-device with
// no backend proxy in the middle:
//
//   1. UID liveness  -> POST check.fb.tools/api/check/facebook (NDJSON)
//   2. Simple check   -> accountscenter.facebook.com scrape with the row cookies
//   3. Advanced check -> business.facebook.com scrape + GraphQL eligibility
//
// No server secrets are involved: the UID endpoint takes no auth and the
// FB scrapes use the row owner's own cookies. All calls are blocking with
// worker-identical timeouts — invoke from Dispatchers.IO only.
object SheetChecker {
    const val CHECK_URL = "https://check.fb.tools/api/check/facebook"
    private const val UA_IOS =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1"
    private const val UA_WIN =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

    private fun get(url: String, headers: Map<String, String>, timeoutMs: Int): Triple<Int, String, String> {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = true
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        val code = c.responseCode
        val text = try {
            (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.readText() ?: ""
        } catch (e: Exception) {
            ""
        } finally {
            c.disconnect()
        }
        return Triple(code, text, c.url.toString())
    }

    private fun post(
        url: String,
        body: String,
        contentType: String,
        headers: Map<String, String>,
        timeoutMs: Int
    ): Pair<Int, String> {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = true
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", contentType)
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            OutputStreamWriter(c.outputStream, Charsets.UTF_8).use { it.write(body) }
        } catch (e: Exception) {
            c.disconnect()
            throw e
        }
        val code = c.responseCode
        val text = try {
            (if (code == 429 || code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.readText() ?: ""
        } catch (e: Exception) {
            ""
        } finally {
            c.disconnect()
        }
        return code to text
    }

    // ── 1. UID liveness (batch ≤500). Returns the DEAD uids
    // (every uid whose status.name is not "valid").
    fun checkUidsDead(uids: List<String>): Set<String> {
        if (uids.isEmpty()) return emptySet()
        val payload = JSONObject()
            .put("inputData", org.json.JSONArray(uids.take(500)))
            .put("userLang", "en")
            .put("checkFriends", false)
            .toString()
        val (code, text) = post(
            CHECK_URL, payload, "application/json",
            mapOf("Accept" to "application/x-ndjson"), 30_000
        )
        if (code !in 200..299) throw IllegalStateException("checker responded $code")
        val dead = mutableSetOf<String>()
        for (line in text.split("\n")) {
            val start = line.indexOf("{")
            if (start < 0) continue
            try {
                val data = JSONObject(line.substring(start)).optJSONObject("data") ?: continue
                val uid = data.optString("uid").ifEmpty { data.optString("account") }
                if (uid.isNotEmpty() && data.optJSONObject("status")?.optString("name") != "valid") {
                    dead.add(uid)
                }
            } catch (e: Exception) {
                // NDJSON framing line — skip, like the worker.
            }
        }
        return dead
    }

    private fun challenged(html: String): Boolean =
        html.contains("checkpointSubmitButton") || html.contains("m_login_email") ||
            Regex("checkpoint|login_attempt|force_login", RegexOption.IGNORE_CASE)
                .containsMatchIn(html.take(5000))

    private val PAGES_RE = Regex(
        "\"identity_type\":\"FB_ADDITIONAL_PROFILE\"[^}]*?\"full_name\":\"([^\"]+)\"[^}]*?\"identity_type_string\":\"([^\"]+)\""
    )
    private val LINKED_RE = Regex(
        "\"__typename\":\"XFBFXSettingsContactPoint\"[^}]*?\"navigation_row_subtitle\":\"([^\"]+)\""
    )

    data class SimpleResult(
        val eligible: Boolean,
        val pageName: String?,
        val linkedNumber: String?,
        val error: String?
    )

    // ── 2. Simple check: accountscenter scrape → pages.
    fun pageSimple(cookie: String): SimpleResult {
        try {
            val (_, html) = get(
                "https://accountscenter.facebook.com/profiles",
                mapOf(
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Cookie" to cookie,
                    "sec-ch-ua-mobile" to "?1",
                    "sec-ch-ua-platform" to "\"iOS\"",
                    "sec-fetch-dest" to "document",
                    "sec-fetch-mode" to "navigate",
                    "sec-fetch-site" to "same-origin",
                    "upgrade-insecure-requests" to "1",
                    "User-Agent" to UA_IOS
                ),
                20_000
            )
            if (challenged(html)) {
                return SimpleResult(false, null, null, "Session requires 2FA or login challenge")
            }
            val names = PAGES_RE.findAll(html).map { it.groupValues[1] }.toList()
            val pageName = names.firstOrNull()
            return SimpleResult(
                eligible = names.isNotEmpty() && pageName != null,
                pageName = pageName,
                linkedNumber = LINKED_RE.find(html)?.groupValues?.get(1),
                error = null
            )
        } catch (e: Exception) {
            return SimpleResult(false, null, null, netError(e))
        }
    }

    data class AdvancedResult(
        val eligible: Boolean,
        val banReason: String?,
        val linkedNumber: String?,
        val pageName: String?,
        val error: String?
    )

    private val PAGE_ID_RES = listOf(
        Regex("\"pageID\"\\s*:\\s*\"(\\d{14,17})\""),
        Regex("\"page_id\"\\s*:\\s*(\\d{14,17})"),
        Regex("\"localScopeID\"\\s*:\\s*\"(\\d{14,17})\""),
        Regex("\"assetID\"\\s*:\\s*\"(\\d{14,17})\""),
        Regex("\"selectedPageId\"\\s*:\\s*\"(\\d{14,17})\""),
        Regex("\"ownerId\"\\s*:\\s*\"(\\d{14,17})\""),
        Regex("\"business_id\"\\s*:\\s*(\\d{14,17})"),
        Regex("\"actorID\"\\s*:\\s*\"(\\d{14,17})\"")
    )

    // ── 3. Advanced check: business.facebook.com scrape + GraphQL eligibility.
    fun pageAdvanced(cookie: String): AdvancedResult {
        fun fail(error: String) = AdvancedResult(false, null, null, null, error)
        try {
            val (_, html, finalUrl) = get(
                "https://business.facebook.com/latest/inbox/wec",
                mapOf(
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Cookie" to cookie,
                    "sec-fetch-dest" to "document",
                    "sec-fetch-mode" to "navigate",
                    "sec-fetch-site" to "none",
                    "User-Agent" to UA_WIN
                ),
                15_000
            )
            if (challenged(html)) return fail("Session requires 2FA or login challenge")
            if (html.contains("Insufficient Permission") || html.contains("You do not have the necessary permission")) {
                return fail("Not eligible for this page")
            }
            val cands = mutableListOf<String?>()
            cands.add(Regex("[?&](?:asset_id|page_id)[=_](\\d{14,17})").find(finalUrl)?.groupValues?.get(1))
            cands.add(Regex("/pages/(\\d{14,17})/").find(finalUrl)?.groupValues?.get(1))
            PAGE_ID_RES.forEach { cands.add(it.find(html)?.groupValues?.get(1)) }
            cands.add(Regex("c_user=(\\d+)").find(cookie)?.groupValues?.get(1))
            val pageId = cands.firstOrNull { !it.isNullOrEmpty() && it.matches(Regex("^\\d+$")) }
                ?: return fail("Invalid pageID")
            val dtsg = Regex("\"DTSGInitData\"[,\\[\\]\\s]*\\{[^}]*\"token\"\\s*:\\s*\"([^\"]+)\"")
                .find(html)?.groupValues?.get(1) ?: return fail("Could not extract fb_dtsg")
            val cuser = Regex("c_user=(\\d+)").find(cookie)?.groupValues?.get(1) ?: ""
            val dpr = (Regex("dpr=([\\d.]+)").find(cookie)?.groupValues?.get(1)?.toDoubleOrNull()
                ?.let { Math.round(it).toString() }) ?: "3"
            val enc = { s: String -> URLEncoder.encode(s, "UTF-8") }
            val form = "av=${enc(pageId)}&__user=${enc(cuser)}&dpr=${enc(dpr)}" +
                "&fb_dtsg=${enc(dtsg)}&__crn=${enc("comet.bizweb.BusinessCometBizSuiteInboxWhatsAppRoute")}" +
                "&fb_api_caller_class=${enc("RelayModern")}" +
                "&fb_api_req_friendly_name=${enc("WhatsAppOnboardingUnifiedInboxSurfaceQuery")}" +
                "&server_timestamps=${enc("true")}" +
                "&variables=${enc("{\"pageID\":\"$pageId\",\"wabaID\":\"\",\"hasWabaID\":false}")}" +
                "&doc_id=${enc("27161030553583658")}"
            val (gcode, gtext) = post(
                "https://business.facebook.com/api/graphql/", form,
                "application/x-www-form-urlencoded",
                mapOf(
                    "Accept" to "*/*",
                    "x-fb-friendly-name" to "WhatsAppOnboardingUnifiedInboxSurfaceQuery",
                    "Cookie" to cookie,
                    "User-Agent" to UA_WIN
                ),
                15_000
            )
            if (gcode == 429) return fail("Rate limited")
            if (gcode !in 200..299) return fail("GraphQL returned $gcode")
            if (gtext.contains("Insufficient Permission") || gtext.contains("You do not have the necessary permission")) {
                return fail("Not eligible for this page")
            }
            val json = try {
                JSONObject(gtext.replace(Regex("^for\\s*\\(;;\\)\\s*;?\\s*"), ""))
            } catch (e: Exception) {
                return fail("Invalid GraphQL JSON")
            }
            val data = json.optJSONObject("data") ?: return fail("Unexpected response structure")
            if (data.isNull("xfb_is_page_eligible_for_wa_link")) return fail("Unexpected response structure")
            val elig = data.optJSONObject("xfb_is_page_eligible_for_wa_link")
            val rawName = data.optJSONObject("page")?.optString("name")
            val pageName = if (!rawName.isNullOrBlank()) rawName else null
            val eligible = (elig?.optBoolean("is_eligible") == true) && pageName != null
            return AdvancedResult(
                eligible = eligible,
                banReason = elig?.optString("ban_reason")?.ifEmpty { null },
                linkedNumber = elig?.optString("page_whatsapp_number")?.ifEmpty { null },
                pageName = pageName,
                error = null
            )
        } catch (e: Exception) {
            return fail(netError(e))
        }
    }

    private fun netError(e: Exception): String {
        val msg = "${e.javaClass.simpleName} ${e.message}"
        return if (Regex("abort|timeout|network|fetch|connect|socket|host", RegexOption.IGNORE_CASE).containsMatchIn(msg)) {
            "Service unavailable"
        } else {
            e.message ?: "Check failed"
        }
    }
}
