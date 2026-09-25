package net.typeblog.socks.util.sheet

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// Minimal dependency-free xlsx writer for downloads and the "Send a copy"
// share sheet. Header labels + data rows in column order; cells use inline
// strings so no shared-strings table is needed.
// The output opens in Excel, Sheets and WPS. Kept dependency-free on
// purpose: a POI dependency would bloat the APK and slow the fast lane.
object SheetXlsx {
    // One worksheet of a multi-sheet workbook. Cells are pre-stringified:
    // the backup mirror encodes flags and longs as text, so the writer never
    // has to know the domain types.
    data class XlsxSheet(val name: String, val header: List<String>, val rows: List<List<String>>)

    fun build(columns: List<SheetColumn>, rows: List<SheetRow>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.entry("[Content_Types].xml", CONTENT_TYPES)
            zip.entry("_rels/.rels", RELS)
            zip.entry("docProps/core.xml", CORE)
            zip.entry("docProps/app.xml", APP)
            zip.entry("xl/workbook.xml", WORKBOOK)
            zip.entry("xl/_rels/workbook.xml.rels", WORKBOOK_RELS)
            zip.entry("xl/worksheets/sheet1.xml", sheet(columns, rows))
        }
        return out.toByteArray()
    }

    // Whole-install workbook for the backup mirror: one worksheet per Sheet
    // file plus the Wallet / Settings sheets. build() above stays the
    // single-grid export; this is the human-readable twin of the JSON dump.
    fun buildWorkbook(sheets: List<XlsxSheet>): ByteArray {
        val used = mutableSetOf<String>()
        val named = sheets.map { it.copy(name = safeSheetName(it.name, used)) }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.entry("[Content_Types].xml", contentTypes(named.size))
            zip.entry("_rels/.rels", RELS)
            zip.entry("docProps/core.xml", CORE)
            zip.entry("docProps/app.xml", APP)
            zip.entry("xl/workbook.xml", workbookXml(named))
            zip.entry("xl/_rels/workbook.xml.rels", workbookRels(named.size))
            for ((i, s) in named.withIndex()) {
                zip.entry("xl/worksheets/sheet${i + 1}.xml", grid(s.header, s.rows))
            }
        }
        return out.toByteArray()
    }

    // Excel caps a sheet name at 31 chars and rejects : \ / ? * [ ]. File
    // names are user-supplied, so sanitize and de-duplicate rather than
    // emitting a workbook that refuses to open. SheetBackupXlsx replays this
    // when reading a workbook back, so the two must stay in step: a reader
    // that derived labels differently would fail to match worksheets to files.
    internal fun safeSheetName(raw: String, used: MutableSet<String>): String {
        val cleaned = raw.map { if (it in ILLEGAL_NAME_CHARS || it.code < 0x20) '_' else it }
            .joinToString("").trim().ifEmpty { "Sheet" }
        val base = cleaned.take(31)
        if (used.add(base.lowercase())) return base
        var n = 2
        while (true) {
            val suffix = " $n"
            val candidate = base.take(31 - suffix.length) + suffix
            if (used.add(candidate.lowercase())) return candidate
            n++
        }
    }

    private fun grid(header: List<String>, rows: List<List<String>>): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        sb.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
        sb.append("<sheetData>")
        sb.append("<row r=\"1\">")
        for ((i, h) in header.withIndex()) sb.append(cell(colName(i) + "1", h))
        sb.append("</row>")
        var r = 1
        for (row in rows) {
            r++
            sb.append("<row r=\"$r\">")
            for ((i, v) in row.withIndex()) sb.append(cell(colName(i) + r, v))
            sb.append("</row>")
        }
        sb.append("</sheetData></worksheet>")
        return sb.toString()
    }

    private fun contentTypes(count: Int): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        sb.append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
        sb.append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
        sb.append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
        sb.append("<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>")
        sb.append("<Override PartName=\"/docProps/app.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.extended-properties+xml\"/>")
        sb.append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>")
        for (i in 1..count) {
            sb.append("<Override PartName=\"/xl/worksheets/sheet$i.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>")
        }
        sb.append("</Types>")
        return sb.toString()
    }

    private fun workbookXml(sheets: List<XlsxSheet>): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        sb.append("<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">")
        sb.append("<sheets>")
        for ((i, s) in sheets.withIndex()) {
            sb.append("<sheet name=\"${esc(s.name)}\" sheetId=\"${i + 1}\" r:id=\"rId${i + 1}\"/>")
        }
        sb.append("</sheets></workbook>")
        return sb.toString()
    }

    private fun workbookRels(count: Int): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        sb.append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
        for (i in 1..count) {
            sb.append("<Relationship Id=\"rId$i\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet$i.xml\"/>")
        }
        sb.append("</Relationships>")
        return sb.toString()
    }

    private fun ZipOutputStream.entry(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun sheet(columns: List<SheetColumn>, rows: List<SheetRow>): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        sb.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
        sb.append("<sheetData>")
        var r = 1
        sb.append("<row r=\"$r\">")
        for ((i, c) in columns.withIndex()) {
            sb.append(cell(colName(i) + r, c.label))
        }
        sb.append("</row>")
        for (row in rows) {
            if (!row.isData(columns)) continue
            r++
            sb.append("<row r=\"$r\">")
            for ((i, c) in columns.withIndex()) {
                val value = if (c.key == "twofakey" && isNo2Fa(row.cell(c.key))) "" else row.cell(c.key)
                sb.append(cell(colName(i) + r, value))
            }
            sb.append("</row>")
        }
        sb.append("</sheetData></worksheet>")
        return sb.toString()
    }

    private fun cell(ref: String, value: String): String =
        "<c r=\"$ref\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${esc(value)}</t></is></c>"

    private fun esc(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            when (ch) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                else -> if (ch.code >= 0x20 || ch == '\n' || ch == '\t') sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun colName(i: Int): String {
        var n = i
        val sb = StringBuilder()
        do {
            sb.append(('A' + (n % 26)))
            n = n / 26 - 1
        } while (n >= 0)
        return sb.reverse().toString()
    }

    private const val ILLEGAL_NAME_CHARS = ":\\/?*[]"

    private const val CONTENT_TYPES = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>" +
            "<Override PartName=\"/docProps/app.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.extended-properties+xml\"/>" +
            "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
            "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
            "</Types>"

    private const val RELS = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>" +
            "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/>" +
            "<Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties\" Target=\"docProps/app.xml\"/>" +
            "</Relationships>"

    private const val CORE = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\">" +
            "<dc:creator>KiloApp</dc:creator><cp:lastModifiedBy>KiloApp</cp:lastModifiedBy>" +
            "</cp:coreProperties>"

    private const val APP = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\">" +
            "<Application>KiloApp</Application>" +
            "</Properties>"

    private const val WORKBOOK = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
            "<sheets><sheet name=\"Sheet1\" sheetId=\"1\" r:id=\"rId1\"/></sheets>" +
            "</workbook>"

    private const val WORKBOOK_RELS = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>" +
            "</Relationships>"
}
