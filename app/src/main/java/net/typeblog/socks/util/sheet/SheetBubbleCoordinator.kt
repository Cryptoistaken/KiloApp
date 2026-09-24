package net.typeblog.socks.util.sheet

import android.content.Context

/**
 * File-scoped coordinator for the floating Sheet bubble. It never publishes
 * openFile/openRows and therefore cannot replace the full Sheet editor state.
 */
class SheetBubbleCoordinator(context: Context) {
    private val appContext = context.applicationContext
    private val db = SheetDb(appContext)
    private val store = SheetStore.get(appContext)

    fun load(fileId: String): SheetBubbleSnapshot? {
        val file = db.getFile(fileId)
        if (file == null || file.archived) {
            store.clearBubbleFileIfSelected(fileId)
            return null
        }
        val rows = rowsFor(fileId)
        return SheetBubbleSnapshot(file, rows, findBubbleActiveRow(rows, file.preset))
    }

    fun capture(
        fileId: String,
        clipboardText: String?,
        markNo2Fa: Boolean
    ): SheetBubbleCaptureResult {
        val file = db.getFile(fileId)
            ?: return SheetBubbleCaptureResult(null, false, "Select a Sheet file first.")
        if (file.archived) {
            store.clearBubbleFileIfSelected(fileId)
            return SheetBubbleCaptureResult(null, false, "Select a Sheet file first.")
        }

        val previousRows = rowsFor(fileId)
        val rows = previousRows.toMutableList()
        val changedRows = linkedSetOf<Int>()
        var message = ""

        // Long press is deliberately applied before clipboard capture. A
        // marker on a cookie-less row keeps that row active for its cookie;
        // a marker on a complete cookie row advances to the next row.
        if (markNo2Fa && usesBubbleTwoFa(file.preset)) {
            val active = findBubbleActiveRow(rows, file.preset)
            if (active >= 0 && rows[active].twofakey.isEmpty()) {
                rows[active] = rows[active].copy(twofakey = NO_2FA)
                changedRows += active
                message = "No_2Fa saved."
            }
        }

        val parsed = parseBubbleClipboard(clipboardText?.take(32_000))
        if (parsed.type == BubbleClipboardType.COOKIE) {
            val active = findBubbleActiveRow(rows, file.preset)
            if (active >= 0 && rows[active].cookies.isBlank() && !cookieDuplicate(rows, active, parsed.value)) {
                rows[active] = rows[active].copy(
                    cookies = parsed.value,
                    uid = extractCUser(parsed.value) ?: "",
                    status = "",
                    dead = false
                )
                changedRows += active
                message = "Cookie saved."
            }
        } else if (parsed.type == BubbleClipboardType.TWO_FA && usesBubbleTwoFa(file.preset)) {
            val active = findBubbleActiveRow(rows, file.preset)
            if (active >= 0 && rows[active].twofakey.isBlank() && !keyDuplicate(rows, active, parsed.value)) {
                rows[active] = rows[active].copy(twofakey = parsed.value)
                changedRows += active
                message = "2FA key saved."
            }
        }

        if (changedRows.isNotEmpty()) {
            val now = System.currentTimeMillis()
            val seq = file.seq + 1
            val staleChecks = changedRows.filter { index ->
                val before = previousRows.getOrNull(index)
                val after = rows.getOrNull(index)
                before == null || after == null || before.cookies != after.cookies ||
                    before.uid != after.uid || before.status != after.status || before.dead != after.dead
            }.toSet()
            db.tx { d ->
                for (index in changedRows) {
                    rows.getOrNull(index)?.let { db.upsertRow(d, fileId, it) }
                }
                db.saveSnapshot(d, fileId, seq, encodeSheetRows(rows))
                db.updateFile(d, file.copy(updatedAt = now, seq = seq))
                db.insertUndo(d, fileId, encodeSheetRows(previousRows))
                db.clearRedo(d, fileId)
                db.recordOp(d, fileId, "bubble")
                db.deleteRowCheckData(d, fileId, staleChecks)
            }
            // Only publish editor state if the user happens to have this same
            // file open. A bubble write to another file leaves it untouched.
            store.reloadOpenFileIfMatches(fileId)
            store.refresh()
        }

        val snapshot = load(fileId)
        return SheetBubbleCaptureResult(snapshot, changedRows.isNotEmpty(), message)
    }

    private fun rowsFor(fileId: String): MutableList<SheetRow> {
        val loaded = db.loadRows(fileId)
        if (loaded.isEmpty()) {
            return MutableList(MAX_GRID_ROWS) { SheetRow(rowIdx = it) }
        }
        val normalized = loaded.mapIndexed { index, row ->
            if (row.rowIdx == index) row else row.copy(rowIdx = index)
        }.toMutableList()
        if (normalized.size < MAX_GRID_ROWS) {
            for (index in normalized.size until MAX_GRID_ROWS) {
                normalized.add(SheetRow(rowIdx = index))
            }
        }
        return normalized
    }

    private fun cookieDuplicate(rows: List<SheetRow>, active: Int, value: String): Boolean {
        val trimmed = value.trim()
        val uid = extractCUser(trimmed)
        return rows.any { row ->
            row.rowIdx != active && (row.cookies.trim() == trimmed || (uid != null && row.uid == uid))
        }
    }

    private fun keyDuplicate(rows: List<SheetRow>, active: Int, value: String): Boolean {
        val normalized = normalizeBubbleTwoFaKey(value) ?: return true
        return rows.any { row ->
            row.rowIdx != active &&
                row.twofakey.isNotBlank() &&
                !isNo2Fa(row.twofakey) &&
                normalizeBubbleTwoFaKey(row.twofakey) == normalized
        }
    }
}

data class SheetBubbleCaptureResult(
    val snapshot: SheetBubbleSnapshot?,
    val changed: Boolean,
    val message: String
)
