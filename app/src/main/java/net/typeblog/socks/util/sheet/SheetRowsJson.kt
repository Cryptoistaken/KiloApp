package net.typeblog.socks.util.sheet

import org.json.JSONArray
import org.json.JSONObject

/** Shared row serialization for snapshots, undo history, and file-scoped writes. */
fun encodeSheetRows(rows: List<SheetRow>): String {
    val array = JSONArray()
    for (row in rows) {
        array.put(
            JSONObject()
                .put("cookies", row.cookies)
                .put("twofakey", row.twofakey)
                .put("uid", row.uid)
                .put("status", row.status)
                .put("hold", row.hold)
                .put("approved", row.approved)
                .put("dead", row.dead)
        )
    }
    return array.toString()
}

fun decodeSheetRows(data: String): List<SheetRow> {
    val out = mutableListOf<SheetRow>()
    val array = JSONArray(data)
    for (index in 0 until array.length()) {
        val obj = array.getJSONObject(index)
        out.add(
            SheetRow(
                rowIdx = index,
                cookies = obj.optString("cookies"),
                twofakey = obj.optString("twofakey"),
                uid = obj.optString("uid"),
                status = obj.optString("status"),
                hold = obj.optBoolean("hold"),
                approved = obj.optBoolean("approved"),
                dead = obj.optBoolean("dead")
            )
        )
    }
    return out
}
