package net.typeblog.socks.util.sheet

import java.util.UUID

const val MAX_GRID_ROWS = 500
const val ARCHIVE_KEEP_DAYS = 30
const val LOVE_PASSWORD = "Love@12345"
const val DGD_PASSWORD = "dgddigital"

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

data class CellStyle(val bg: String? = null, val color: String? = null, val bold: Boolean = false)

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
