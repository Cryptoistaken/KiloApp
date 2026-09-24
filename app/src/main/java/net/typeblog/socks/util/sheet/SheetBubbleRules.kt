package net.typeblog.socks.util.sheet

/** Pure rules shared by the native floating Sheet bubble. */
enum class BubbleClipboardType {
    EMPTY,
    COOKIE,
    TWO_FA,
    INVALID
}

data class BubbleClipboardValue(
    val type: BubbleClipboardType,
    val value: String = ""
)

data class SheetBubbleSnapshot(
    val file: SheetFile,
    val rows: List<SheetRow>,
    val activeRow: Int
)

/** The old bubble accepts Facebook cookies only when they look complete. */
fun isBubbleCookieText(raw: String): Boolean =
    raw.contains(Regex("c_user=\\d+")) && raw.contains(";") && raw.contains("=")

/** Normalize a pasted Base32 2FA key using the old bubble's 10-32 limit. */
fun normalizeBubbleTwoFaKey(raw: String): String? {
    val value = raw.replace(" ", "").replace("-", "").uppercase()
    return value.takeIf { it.length in 10..32 && it.matches(Regex("[A-Z2-7]+")) }
}

fun parseBubbleClipboard(raw: String?): BubbleClipboardValue {
    val value = raw.orEmpty().trim()
    if (value.isEmpty()) return BubbleClipboardValue(BubbleClipboardType.EMPTY)
    if (isBubbleCookieText(value)) return BubbleClipboardValue(BubbleClipboardType.COOKIE, value)
    val key = normalizeBubbleTwoFaKey(value)
    return if (key != null) {
        BubbleClipboardValue(BubbleClipboardType.TWO_FA, key)
    } else {
        BubbleClipboardValue(BubbleClipboardType.INVALID)
    }
}

fun usesBubbleTwoFa(preset: SheetPreset): Boolean = preset != SheetPreset.COOKIE

fun isBubbleRowComplete(row: SheetRow, preset: SheetPreset): Boolean {
    if (row.locked || row.cookies.isBlank()) return false
    return !usesBubbleTwoFa(preset) || isValidTwoFaValue(row.twofakey)
}

/** First unlocked incomplete row; -1 means the file has no writable active row. */
fun findBubbleActiveRow(rows: List<SheetRow>, preset: SheetPreset): Int =
    rows.indexOfFirst { !it.locked && !isBubbleRowComplete(it, preset) }
