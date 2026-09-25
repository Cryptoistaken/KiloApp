package net.typeblog.socks.ui.screens.sheet

import net.typeblog.socks.util.sheet.CopiedGrid
import net.typeblog.socks.util.sheet.SheetColumn
import net.typeblog.socks.util.sheet.SheetRow
import net.typeblog.socks.util.sheet.extractCUser

internal fun detailAllCells(
    rows: List<SheetRow>,
    visibleCols: List<SheetColumn>
): Set<Pair<Int, String>> = buildSet {
    for (row in rows) {
        for (col in visibleCols) add(Pair(row.rowIdx, col.key))
    }
}

internal fun detailRowCells(
    rowIdx: Int,
    visibleCols: List<SheetColumn>
): Set<Pair<Int, String>> =
    visibleCols.map { Pair(rowIdx, it.key) }.toSet()

internal fun detailColumnCells(
    rows: List<SheetRow>,
    colKey: String
): Set<Pair<Int, String>> =
    rows.map { Pair(it.rowIdx, colKey) }.toSet()

internal fun detailSortedKeys(
    cells: Set<Pair<Int, String>>,
    order: List<String>
): List<String> = cells.map { it.second }.sortedBy {
    order.indexOf(it).let { index -> if (index < 0) 999 else index }
}

internal fun detailGrid(
    preset: String,
    key: String,
    value: String
): CopiedGrid = CopiedGrid(
    preset = preset,
    columns = listOf(key),
    cells = listOf(listOf(Pair(key, value)))
)

internal fun detailDataCount(
    rows: List<SheetRow>,
    columns: List<SheetColumn>
): Int = if (columns.isEmpty()) 0 else rows.count { it.isData(columns) }

internal fun detailHasUidToCheck(
    rows: List<SheetRow>,
    columns: List<SheetColumn>
): Boolean = rows.any { row ->
    row.isData(columns) && !row.locked &&
            (row.uid.isNotEmpty() || extractCUser(row.cookies) != null)
}

internal fun detailCheckSummary(valid: Int, dead: Int): String = buildList {
    if (valid > 0) add("Alive $valid")
    if (dead > 0) add("Dead $dead")
}.joinToString(", ").let { summary ->
    if (summary.isEmpty()) "No UID to check." else "$summary."
}

internal fun detailUploadedRows(
    rows: List<SheetRow>,
    columns: List<SheetColumn>
): List<SheetRow> {
    val hasTwoFa = columns.any { it.key == "twofakey" }
    return rows.mapIndexed { index, row ->
        SheetRow(
            rowIdx = index,
            cookies = row.cookies,
            twofakey = if (hasTwoFa) row.twofakey else "",
            uid = row.uid
        )
    }
}
