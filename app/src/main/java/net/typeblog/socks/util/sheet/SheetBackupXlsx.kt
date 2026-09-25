package net.typeblog.socks.util.sheet

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import net.typeblog.socks.util.ProfileEntry
import net.typeblog.socks.util.sheet.SheetBackup.BackupSnapshot

/**
 * The backup workbook format, both directions.
 *
 * One worksheet per Sheet file, plus "Files" (the inventory: id, preset,
 * password, archive state, timestamps), "Wallet" and "Profiles". The JSON dump
 * stays the canonical restore path; this is the same snapshot rendered for a
 * human, written to be re-importable so a user whose only surviving copy is
 * the spreadsheet is not left with nothing.
 *
 * The reader resolves worksheet names through xl/workbook.xml and handles
 * shared strings as well as inline ones, because opening a workbook in Excel
 * and saving it rewrites every cell into the shared-strings table. Round
 * tripping through a spreadsheet app is the expected case, not an edge case.
 */
internal object SheetBackupXlsx {
    private const val FILES_SHEET = "Files"
    private const val WALLET_SHEET = "Wallet"
    private const val PROFILES_SHEET = "Profiles"

    private val ROW_HEADER = listOf("row", "cookies", "2fa key", "uid", "status", "hold", "approved", "dead")
    private val FILES_HEADER =
        listOf("id", "name", "preset", "password", "archived", "deletedAt", "createdAt", "updatedAt", "seq")
    private val WALLET_HEADER = listOf("id", "created", "type", "amount", "balance after", "title", "detail")
    private val PROFILES_HEADER = listOf("key", "type", "value")

    // ── Write ──────────────────────────────────────────────────────────────

    fun write(s: BackupSnapshot): ByteArray {
        val sheets = mutableListOf<SheetXlsx.XlsxSheet>()

        sheets.add(
            SheetXlsx.XlsxSheet(
                FILES_SHEET, FILES_HEADER,
                s.files.map { f ->
                    listOf(
                        f.id, f.name, f.preset.name, f.password,
                        boolText(f.archived), f.deletedAt.toString(),
                        f.createdAt.toString(), f.updatedAt.toString(), f.seq.toString()
                    )
                }
            )
        )
        for (f in s.files) {
            sheets.add(
                SheetXlsx.XlsxSheet(
                    f.name.ifBlank { f.preset.title }, ROW_HEADER,
                    s.rows[f.id].orEmpty().map { r ->
                        listOf(
                            r.rowIdx.toString(), r.cookies, r.twofakey, r.uid, r.status,
                            boolText(r.hold), boolText(r.approved), boolText(r.dead)
                        )
                    }
                )
            )
        }
        if (s.txs.isNotEmpty() || s.balance != 0.0) {
            sheets.add(
                SheetXlsx.XlsxSheet(
                    WALLET_SHEET, WALLET_HEADER,
                    s.txs.map { t ->
                        listOf(
                            t.id, t.createdAt.toString(), t.type, t.amount.toString(),
                            t.balanceAfter.toString(), t.title, t.detail.orEmpty()
                        )
                    }
                )
            )
        }
        if (s.profiles.isNotEmpty()) {
            sheets.add(
                SheetXlsx.XlsxSheet(
                    PROFILES_SHEET, PROFILES_HEADER,
                    s.profiles.map { listOf(it.key, it.type, it.value) }
                )
            )
        }
        return SheetXlsx.buildWorkbook(sheets)
    }

    /** One Sheet file as a single-worksheet workbook, so it opens directly on
     *  that file instead of behind a tab. Null when the file holds nothing,
     *  which is also when the existing export refuses. */
    fun writeFile(s: BackupSnapshot, id: String): ByteArray? {
        val f = s.files.firstOrNull { it.id == id } ?: return null
        val rows = s.rows[id].orEmpty()
        if (rows.none { it.isData(f.preset.columns) }) return null
        return SheetXlsx.buildWorkbook(
            listOf(
                SheetXlsx.XlsxSheet(
                    f.name.ifBlank { f.preset.title }, ROW_HEADER,
                    rows.map { r ->
                        listOf(
                            r.rowIdx.toString(), r.cookies, r.twofakey, r.uid, r.status,
                            boolText(r.hold), boolText(r.approved), boolText(r.dead)
                        )
                    }
                )
            )
        )
    }

    // ── Read ───────────────────────────────────────────────────────────────

    /** Null when the bytes are not one of our backup workbooks, so the caller
     *  can say so rather than restore half a file. */
    fun read(bytes: ByteArray): BackupSnapshot? {
        val parts = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                val n = e.name
                if (n == "xl/workbook.xml" || n == "xl/_rels/workbook.xml.rels" ||
                    n == "xl/sharedStrings.xml" || (n.startsWith("xl/worksheets/") && n.endsWith(".xml"))
                ) {
                    parts[n] = readAll(zip)
                }
                e = zip.nextEntry
            }
        }
        val workbook = parts["xl/workbook.xml"] ?: return null
        val rels = parts["xl/_rels/workbook.xml.rels"] ?: return null
        if (parts.isEmpty()) return null

        val shared = parseSharedStrings(parts["xl/sharedStrings.xml"].orEmpty())
        val targetByRel = mutableMapOf<String, String>()
        for (m in Regex("<Relationship\\b([^>]*)/?>").findAll(rels)) {
            val id = attr(m.groupValues[1], "Id") ?: continue
            val target = attr(m.groupValues[1], "Target") ?: continue
            targetByRel[id] = if (target.startsWith("/")) target.removePrefix("/") else "xl/$target"
        }

        // Workbook order is the tab order the user sees, which is also the
        // order the data sheets were written in.
        val named = mutableListOf<Pair<String, List<List<String>>>>()
        for (m in Regex("<sheet\\b([^>]*)/?>").findAll(workbook)) {
            val name = unescape(attr(m.groupValues[1], "name").orEmpty())
            val rid = attr(m.groupValues[1], "r:id")
                ?: Regex("r:id=\"([^\"]+)\"").find(m.groupValues[1])?.groupValues?.get(1)
                ?: continue
            val grid = targetByRel[rid]?.let { parts[it] }?.let { parseSheet(it, shared) } ?: continue
            named.add(name to grid)
        }
        if (named.isEmpty()) return null

        val filesGrid = named.firstOrNull { it.first == FILES_SHEET }?.second ?: return null
        val files = parseFiles(filesGrid) ?: return null

        // Worksheet labels are sanitized and de-duplicated on write, so the
        // same transform is replayed over the Files names to rebuild the
        // name -> SheetFile mapping instead of trusting the visible label.
        val used = mutableSetOf<String>()
        val dataNames = files.map { SheetXlsx.safeSheetName(it.name.ifBlank { it.preset.title }, used) }
        val gridByName = named.associate { it.first to it.second }
        val fileByName = dataNames.withIndex().associate { (i, n) -> n to files[i] }

        val rows = mutableMapOf<String, MutableList<SheetRow>>()
        var matched = 0
        for (name in dataNames) {
            val grid = gridByName[name] ?: continue
            val target = fileByName[name] ?: continue
            val parsed = parseRows(grid) ?: continue
            rows[target.id] = parsed
            matched++
        }
        if (matched == 0) return null

        val txs = parseWallet(gridByName[WALLET_SHEET])
        // A workbook restore recovers the balance from the newest transaction
        // rather than storing it separately, so a non-zero balance with no
        // transactions behind it only survives the JSON dump. The JSON path is
        // the canonical one; the workbook is the "that is all I have left"
        // fallback, and this is what it gives up.
        val balance = txs.maxByOrNull { it.createdAt }?.balanceAfter ?: 0.0
        val profiles = parseProfiles(gridByName[PROFILES_SHEET])

        return BackupSnapshot(
            at = System.currentTimeMillis(),
            files = files,
            rows = rows,
            // Styles, hidden columns and check history are deliberately not
            // mirrored into the workbook: they are presentation and
            // diagnostics. The JSON dump is the path that carries them.
            styles = emptyMap(),
            hidden = emptyMap(),
            checks = emptyMap(),
            reqs = emptyMap(),
            balance = balance,
            txs = txs,
            profiles = profiles
        )
    }

    private fun parseFiles(grid: List<List<String>>): List<SheetFile>? {
        val head = grid.firstOrNull() ?: return null
        val iId = head.indexOfFirst { it.trim().equals("id", true) }
        val iName = head.indexOfFirst { it.trim().equals("name", true) }
        val iPreset = head.indexOfFirst { it.trim().equals("preset", true) }
        val iPw = head.indexOfFirst { it.trim().equals("password", true) }
        if (iId < 0 || iName < 0 || iPreset < 0) return null
        val out = mutableListOf<SheetFile>()
        for (r in grid.drop(1)) {
            val id = r.getOrNull(iId)?.trim().orEmpty()
            if (id.isEmpty()) continue
            out.add(
                SheetFile(
                    id = id,
                    name = r.getOrNull(iName).orEmpty(),
                    preset = SheetPreset.of(r.getOrNull(iPreset)?.trim()),
                    password = r.getOrNull(iPw).orEmpty(),
                    archived = boolOf(r.getOrNull(head.indexOfFirst { it.trim().equals("archived", true) })),
                    deletedAt = r.getOrNull(head.indexOfFirst { it.trim().equals("deletedAt", true) })
                        ?.toLongOrNull() ?: 0L,
                    createdAt = r.getOrNull(head.indexOfFirst { it.trim().equals("createdAt", true) })
                        ?.toLongOrNull() ?: 0L,
                    updatedAt = r.getOrNull(head.indexOfFirst { it.trim().equals("updatedAt", true) })
                        ?.toLongOrNull() ?: 0L,
                    seq = r.getOrNull(head.indexOfFirst { it.trim().equals("seq", true) })
                        ?.toLongOrNull() ?: 0L
                )
            )
        }
        return out.ifEmpty { null }
    }

    private fun parseRows(grid: List<List<String>>): MutableList<SheetRow>? {
        val head = grid.firstOrNull() ?: return null
        fun col(name: String) = head.indexOfFirst { it.trim().equals(name, true) }
        val iRow = col("row")
        val iCookie = col("cookies")
        val iTwofa = col("2fa key")
        val iUid = col("uid")
        val iStatus = col("status")
        val iHold = col("hold")
        val iApproved = col("approved")
        val iDead = col("dead")
        if (iCookie < 0 && iTwofa < 0 && iUid < 0) return null

        val out = mutableListOf<SheetRow>()
        for (line in grid.drop(1)) {
            fun at(i: Int) = if (i >= 0) line.getOrNull(i)?.trim().orEmpty() else ""
            val row = SheetRow(
                rowIdx = at(iRow).toIntOrNull() ?: out.size,
                cookies = at(iCookie),
                twofakey = at(iTwofa),
                uid = at(iUid),
                status = at(iStatus),
                hold = boolOf(at(iHold)),
                approved = boolOf(at(iApproved)),
                dead = boolOf(at(iDead))
            )
            // A worksheet the user tidied by hand can carry blank filler
            // rows; the JSON dump never stores those.
            if (row.isEmptyRow() && out.isNotEmpty()) continue
            out.add(row)
        }
        return out
    }

    private fun parseWallet(grid: List<List<String>>?): List<WalletTx> {
        val g = grid ?: return emptyList()
        val head = g.firstOrNull() ?: return emptyList()
        fun col(name: String) = head.indexOfFirst { it.trim().equals(name, true) }
        val iId = col("id")
        val iCreated = col("created")
        val iType = col("type")
        val iAmount = col("amount")
        val iAfter = col("balance after")
        val iTitle = col("title")
        val iDetail = col("detail")
        if (iId < 0 || iTitle < 0) return emptyList()
        val out = mutableListOf<WalletTx>()
        for (r in g.drop(1)) {
            val id = r.getOrNull(iId)?.trim().orEmpty()
            if (id.isEmpty()) continue
            out.add(
                WalletTx(
                    id = id,
                    createdAt = r.getOrNull(iCreated)?.toLongOrNull() ?: 0L,
                    type = r.getOrNull(iType)?.trim().orEmpty(),
                    amount = r.getOrNull(iAmount)?.toDoubleOrNull() ?: 0.0,
                    balanceAfter = r.getOrNull(iAfter)?.toDoubleOrNull() ?: 0.0,
                    title = r.getOrNull(iTitle).orEmpty(),
                    detail = r.getOrNull(iDetail)?.takeIf { it.isNotEmpty() }
                )
            )
        }
        return out
    }

    private fun parseProfiles(grid: List<List<String>>?): List<ProfileEntry> {
        val g = grid ?: return emptyList()
        val head = g.firstOrNull() ?: return emptyList()
        val iKey = head.indexOfFirst { it.trim().equals("key", true) }
        val iType = head.indexOfFirst { it.trim().equals("type", true) }
        val iValue = head.indexOfFirst { it.trim().equals("value", true) }
        if (iKey < 0) return emptyList()
        val out = mutableListOf<ProfileEntry>()
        for (r in g.drop(1)) {
            val k = r.getOrNull(iKey)?.trim().orEmpty()
            if (k.isEmpty()) continue
            out.add(
                ProfileEntry(
                    key = k,
                    type = r.getOrNull(iType)?.trim()?.takeIf { it.isNotEmpty() } ?: "s",
                    value = r.getOrNull(iValue).orEmpty()
                )
            )
        }
        return out
    }

    private fun boolText(v: Boolean): String = if (v) "1" else ""

    private fun boolOf(v: String?): Boolean {
        val s = v?.trim()?.lowercase().orEmpty()
        return s == "1" || s == "true" || s == "yes"
    }

    // ── Minimal xlsx parsing ───────────────────────────────────────────────

    private fun readAll(zip: ZipInputStream): String {
        val sb = StringBuilder()
        val buf = ByteArray(8192)
        while (true) {
            val n = zip.read(buf)
            if (n <= 0) break
            sb.append(String(buf, 0, n, Charsets.UTF_8))
        }
        return sb.toString()
    }

    private fun attr(attrs: String, name: String): String? =
        Regex("(?<![A-Za-z])" + Regex.escape(name) + "=\"([^\"]*)\"")
            .find(attrs)?.groupValues?.get(1)?.let { unescape(it) }

    private fun parseSharedStrings(xml: String): List<String> {
        // <si> may hold several <t> runs once a spreadsheet app splits styled
        // text, so the runs are concatenated instead of taking the first.
        val out = mutableListOf<String>()
        for (m in Regex("<si>(.*?)</si>", RegexOption.DOT_MATCHES_ALL).findAll(xml)) {
            val sb = StringBuilder()
            for (t in Regex("<t[^>]*>(.*?)</t>", RegexOption.DOT_MATCHES_ALL).findAll(m.groupValues[1])) {
                sb.append(unescape(t.groupValues[1]))
            }
            out.add(sb.toString())
        }
        return out
    }

    private fun parseSheet(xml: String, shared: List<String>): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        // The self-closing alternative must come first: without it a
        // "<c r=\"B1\"/>" would run on to the next cell's </c> and swallow it.
        val rowRe = Regex("<row\\b([^>]*?)/>|<row\\b([^>]*?)>(.*?)</row>", RegexOption.DOT_MATCHES_ALL)
        for (rm in rowRe.findAll(xml)) {
            val selfClosing = rm.groupValues[1].isNotEmpty() && rm.groupValues[3].isEmpty()
            rows.add(
                if (selfClosing) emptyList()
                else parseCells(rm.groupValues[3], shared)
            )
        }
        return rows
    }

    /** Cells are placed by their reference, so a row a spreadsheet app left
     *  with a gap in the middle still lands in the right column. */
    private fun parseCells(body: String, shared: List<String>): List<String> {
        val cells = mutableListOf<String>()
        val cellRe = Regex("<c\\b([^>]*?)/>|<c\\b([^>]*?)>(.*?)</c>", RegexOption.DOT_MATCHES_ALL)
        for (cm in cellRe.findAll(body)) {
            val attrs = if (cm.groupValues[1].isNotEmpty() && cm.groupValues[3].isEmpty()) {
                cm.groupValues[1]
            } else {
                cm.groupValues[2]
            }
            val inner = cm.groupValues[3]
            val col = Regex("r=\"([A-Z]+)\\d+\"").find(attrs)?.groupValues?.get(1)
                ?.let { columnIndex(it) } ?: cells.size
            val type = attr(attrs, "t").orEmpty()
            val text = when (type) {
                "inlineStr" -> Regex("<t[^>]*>(.*?)</t>", RegexOption.DOT_MATCHES_ALL)
                    .findAll(inner).joinToString("") { unescape(it.groupValues[1]) }

                "s" -> Regex("<v>(.*?)</v>", RegexOption.DOT_MATCHES_ALL)
                    .find(inner)?.groupValues?.get(1)?.toIntOrNull()
                    ?.let { shared.getOrNull(it).orEmpty() }.orEmpty()

                "str" -> Regex("<v>(.*?)</v>", RegexOption.DOT_MATCHES_ALL)
                    .find(inner)?.groupValues?.get(1)?.let { unescape(it) }.orEmpty()

                else -> Regex("<v>(.*?)</v>", RegexOption.DOT_MATCHES_ALL)
                    .find(inner)?.groupValues?.get(1).orEmpty()
            }
            while (cells.size < col) cells.add("")
            if (cells.size == col) cells.add(text) else cells[col] = text
        }
        return cells
    }

    private fun columnIndex(letters: String): Int {
        var n = 0
        for (ch in letters) n = n * 26 + (ch - 'A' + 1)
        return n - 1
    }

    private fun unescape(s: String): String = s
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
}
