package net.typeblog.socks.util.sheet

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// Minimal dependency-free xlsx writer for the "Send a copy" share sheet.
// Same content as [SheetCsv.build] (header labels + data rows in column
// order); cells use inline strings so no shared-strings table is needed.
// The output opens in Excel, Sheets and WPS. Kept dependency-free on
// purpose: a POI dependency would bloat the APK and slow the fast lane.
object SheetXlsx {
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
                sb.append(cell(colName(i) + r, row.cell(c.key)))
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
