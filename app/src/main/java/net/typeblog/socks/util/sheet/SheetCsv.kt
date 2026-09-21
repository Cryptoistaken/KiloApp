package net.typeblog.socks.util.sheet

// Minimal CSV for SAF export of a sheet. Quoted per RFC 4180 so commas,
// quotes and newlines inside cookies survive the round trip.
object SheetCsv {
    fun build(columns: List<SheetColumn>, rows: List<SheetRow>): String {
        val sb = StringBuilder()
        sb.append(columns.joinToString(",") { q(it.label) }).append("\n")
        for (r in rows) {
            if (!r.isData(columns)) continue
            sb.append(columns.joinToString(",") { q(r.cell(it.key)) }).append("\n")
        }
        return sb.toString()
    }

    private fun q(v: String): String {
        if (v.none { it == ',' || it == '"' || it == '\n' || it == '\r' }) return v
        return "\"" + v.replace("\"", "\"\"") + "\""
    }

    fun tsv(columns: List<SheetColumn>, rows: List<SheetRow>): String {
        val sb = StringBuilder()
        sb.append(columns.joinToString("\t") { it.label }).append("\n")
        for (r in rows) {
            if (!r.isData(columns)) continue
            sb.append(columns.joinToString("\t") { r.cell(it.key).replace("\t", " ") }).append("\n")
        }
        return sb.toString()
    }
}
