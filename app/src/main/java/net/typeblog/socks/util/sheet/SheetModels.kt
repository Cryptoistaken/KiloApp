package net.typeblog.socks.util.sheet

import java.util.UUID

const val MAX_GRID_ROWS = 500
const val ARCHIVE_KEEP_DAYS = 30
const val LOVE_PASSWORD = "Love@12345"
const val DGD_PASSWORD = "dgddigital"

// Display-only value written by the Sheet bubble when a row has no 2FA key.
// It remains in the local database, is excluded from duplicate checks, and
// is never treated as a real Base32 secret.
const val NO_2FA = "No_2Fa"

fun isNo2Fa(value: String?): Boolean = value == NO_2FA

fun isValidTwoFaValue(raw: String): Boolean =
    isNo2Fa(raw) || isValidTwoFaKey(raw)

enum class SheetPreset(val title: String, val desc: String) {
    COOKIE("Cookie", "cookies and uid"),
    COMBO("2fa", "cookies and 2fa and uid"),
    PAGE("Page", "full columns");

    val columns: List<SheetColumn>
        get() = when (this) {
            COOKIE -> listOf(SheetColumn("cookies", "cookies"), SheetColumn("uid", "uid"))
            COMBO, PAGE -> listOf(
                SheetColumn("cookies", "cookies"),
                SheetColumn("twofakey", "2fa key"),
                SheetColumn("uid", "uid")
            )
        }

    companion object {
        fun of(raw: String?): SheetPreset = when (raw?.lowercase()) {
            "cookie" -> COOKIE
            "combo", "2fa" -> COMBO
            else -> PAGE
        }
    }
}

data class SheetColumn(val key: String, val label: String)

data class SheetFile(
    val id: String,
    val name: String,
    val preset: SheetPreset,
    val password: String,
    val archived: Boolean,
    val deletedAt: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val seq: Long,
    val rowCount: Int = 0,
    val liveCount: Int = 0,
    val deadCount: Int = 0,
    val dupCount: Int = 0,
    val pageCount: Int = 0
) {
    val passwordShort: String
        get() = when (password) {
            DGD_PASSWORD -> "dgd"
            LOVE_PASSWORD -> "Love"
            else -> password.take(8)
        }
}

data class SheetRow(
    val rowIdx: Int,
    val cookies: String = "",
    val twofakey: String = "",
    val uid: String = "",
    val status: String = "",
    val hold: Boolean = false,
    val approved: Boolean = false,
    val dead: Boolean = false
) {
    val locked: Boolean get() = hold || approved
    fun cell(key: String): String = when (key) {
        "cookies" -> cookies
        "twofakey" -> twofakey
        "uid" -> uid
        else -> ""
    }
    fun withCell(key: String, value: String): SheetRow = when (key) {
        "cookies" -> copy(cookies = value)
        "twofakey" -> copy(twofakey = value)
        "uid" -> copy(uid = value)
        else -> this
    }
    fun isData(columns: List<SheetColumn>): Boolean {
        if (columns.any { cell(it.key).isNotEmpty() }) return true
        return uid.isNotEmpty() || status.isNotEmpty()
    }
}

// Per-row check record: the details behind the dot verdict, recorded on
// each check run and shown in the dot popup. Only rows checked after
// this landed carry data; older rows show the verdict alone.
data class RowCheck(
    val checkedAt: Long = 0,
    // Null = this check never ran for the row (strip dot stays muted).
    val uidOk: Boolean? = null,
    val uidError: String? = null,
    val simplePage: String? = null,
    val simpleNumber: String? = null,
    val simpleError: String? = null,
    val advEligible: Boolean = false,
    val advPage: String? = null,
    val advNumber: String? = null,
    val advBan: String? = null,
    val advError: String? = null
) {
    val hasData: Boolean get() = checkedAt > 0
}

// One traced HTTP call behind a row check. Cookie values are never
// stored: reqNote carries a masked summary (names + truncated values +
// length) and bodies are replaced by short extracted notes.
data class CheckReq(
    val kind: String,
    val method: String,
    val url: String,
    val status: Int = 0,
    val durationMs: Long = 0,
    val reqNote: String? = null,
    val resNote: String? = null,
    val error: String? = null,
    val at: Long = 0
)

// Another file holding the same value: the Duplicates tab source line.
// at = the other file's row check time (0 when never checked).
data class DupSource(
    val fileName: String,
    val rowNo: Int,
    val field: String,
    val at: Long = 0
)

// File-level duplicate: a value in this file also present in another
// file. localRow = 1-based row here holding the value.
data class FileDup(
    val field: String,
    val fileName: String,
    val rowNo: Int,
    val localRow: Int,
    val at: Long = 0
)

// Checker trace to stored record: same fields, no secrets either way.
fun SheetChecker.ReqTrace.toCheckReq(resNote: String? = this.resNote): CheckReq =
    CheckReq(kind, method, url, status, durationMs, reqNote, resNote, error, at)

data class CellStyle(val bg: String? = null, val color: String? = null, val bold: Boolean = false)

// Internal grid clipboard for Google-Sheets-style cross-file copy/paste
// (lives in memory, survives file switches): rows of (colKey, value) in
// source visible order, plus that order for positional tiling.
data class CopiedGrid(
    val preset: String,
    val columns: List<String>,
    val cells: List<List<Pair<String, String>>>
)

// Result of a grid paste: counts plus the first notable skip message
// ("Duplicate cookie.", "UID comes from the cookie."), so the toast can
// name it instead of counting it.
data class PasteResult(
    val pasted: Int,
    val skipped: Int,
    val cookiesWritten: Boolean,
    val note: String? = null
)

data class WalletTx(
    val id: String,
    val createdAt: Long,
    val type: String,
    val amount: Double,
    val balanceAfter: Double,
    val title: String,
    val detail: String? = null
)

fun newFileId(): String = UUID.randomUUID().toString()

fun autoFileName(preset: SheetPreset, existing: List<String>): String {
    val base = preset.title
    if (!existing.contains(base)) return base
    var n = 2
    while (existing.contains("$base $n")) n++
    return "$base $n"
}

fun daysLeft(deletedAt: Long, now: Long = System.currentTimeMillis()): Int {
    if (deletedAt <= 0) return ARCHIVE_KEEP_DAYS
    val used = ((now - deletedAt) / 86400000L).toInt()
    return (ARCHIVE_KEEP_DAYS - used).coerceAtLeast(0)
}

fun isValidUid(uid: String): Boolean = uid.trim().matches(Regex("\\d{4,}"))

fun extractCUser(cookies: String): String? =
    Regex("c_user=(\\d+)").find(cookies)?.groupValues?.get(1)

// Website parity (filetypes/validation.ts): a 2FA key is base32 —
// A-Z and 2-7 only, spaces/dashes ignored, at least 10 chars. The
// example shape T6XCY37NZMGVTFWHQ2546MGRXOTCSBDQ (32 chars) is just one
// length; shorter or longer keys are fine as long as they decode.
fun isValidTwoFaKey(raw: String): Boolean {
    val s = raw.replace(" ", "").replace("-", "").uppercase()
    return s.length >= 10 && s.matches(Regex("[A-Z2-7]+"))
}
