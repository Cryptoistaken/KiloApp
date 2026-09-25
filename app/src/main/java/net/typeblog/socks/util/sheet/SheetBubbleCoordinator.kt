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
        return SheetBubbleSnapshot(
            file,
            rows,
            findBubbleActiveRow(rows, file.preset),
            dups = try { db.crossDupCells(fileId, rows) } catch (_: Exception) { emptySet() },
            styles = try { db.loadStyles(fileId) } catch (_: Exception) { emptyMap() },
            hidden = try { db.loadHidden(fileId) } catch (_: Exception) { emptySet() },
            canUndo = hasHistory(fileId, undo = true),
            canRedo = hasHistory(fileId, undo = false)
        )
    }

    /** Whole-file UID check on every new cookie by default (ss_autoCheck). */
    fun isAutoCheckOn(): Boolean = try {
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(appContext)
            .getBoolean("ss_autoCheck", true)
    } catch (_: Exception) {
        true
    }

    fun setAutoCheckOn(on: Boolean) {
        try {
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(appContext)
                .edit().putBoolean("ss_autoCheck", on).apply()
        } catch (_: Exception) {
        }
    }

    /** File-scoped undo/redo over the DB history stacks (bubble writes included). */
    fun undo(fileId: String): SheetBubbleHistoryResult {
        val file = validFile(fileId)
            ?: return SheetBubbleHistoryResult(null, false)
        val prevData = try { db.loadUndoStack(fileId, 1) } catch (_: Exception) { emptyList() }
            .firstOrNull() ?: return SheetBubbleHistoryResult(load(fileId), false)
        applyHistory(file, decodeSheetRows(prevData), "undo")
        return SheetBubbleHistoryResult(load(fileId), true)
    }

    fun redo(fileId: String): SheetBubbleHistoryResult {
        val file = validFile(fileId)
            ?: return SheetBubbleHistoryResult(null, false)
        val nextData = try { db.loadRedoStack(fileId, 1) } catch (_: Exception) { emptyList() }
            .firstOrNull() ?: return SheetBubbleHistoryResult(load(fileId), false)
        applyHistory(file, decodeSheetRows(nextData), "redo")
        return SheetBubbleHistoryResult(load(fileId), true)
    }

    /**
     * File-scoped UID-liveness sweep over every row, mirroring the UID phase
     * of SheetStore.runCheck (dead wins, live never downgrades eligible,
     * offline falls back to the format heuristic). Check details are saved
     * behind the dot popup like the in-app check.
     */
    fun checkFile(fileId: String): SheetBubbleCheckResult {
        val file = validFile(fileId)
            ?: return SheetBubbleCheckResult(null, false, 0, 0)
        val cols = file.preset.columns
        val rows = rowsFor(fileId)
        val before = rows.map { it.copy() }
        val targets = rows.filter { it.isData(cols) && !it.locked && effUid(it).isNotEmpty() }
        if (targets.isEmpty()) return SheetBubbleCheckResult(load(fileId), false, 0, 0)
        val batch = try {
            SheetChecker.checkUids(targets.map { effUid(it) }.distinct())
        } catch (_: Exception) {
            null
        }
        val verdict = batch?.dead
        var valid = 0
        var dead = 0
        val now = System.currentTimeMillis()
        val checks = mutableMapOf<Int, RowCheck>()
        val reqs = mutableMapOf<Int, MutableList<CheckReq>>()
        val changed = linkedSetOf<Int>()
        rows.forEachIndexed { index, r ->
            if (!r.isData(cols) || r.locked) return@forEachIndexed
            if (r.cookies.isNotEmpty() && effUid(r).isNotEmpty()) {
                val uid = effUid(r)
                val alive = if (verdict == null) isValidUid(uid) else !verdict.contains(uid)
                if (alive) valid++ else dead++
                checks[r.rowIdx] = RowCheck(checkedAt = now, uidOk = alive)
                if (batch != null) {
                    reqs.getOrPut(r.rowIdx) { mutableListOf() }.add(
                        batch.trace.toCheckReq(if (alive) "valid" else (batch.names[uid] ?: "dead"))
                    )
                }
                val next = if (alive) {
                    r.copy(status = if (r.status == "eligible") "eligible" else "good", dead = false)
                } else {
                    r.copy(status = "bad", dead = true)
                }
                if (next != r) {
                    rows[index] = next
                    changed += index
                }
            } else if (r.uid.isNotEmpty() && !isValidUid(r.uid)) {
                dead++
                checks[r.rowIdx] = RowCheck(checkedAt = now, uidOk = false)
                val next = r.copy(status = "bad", dead = true)
                if (next != r) {
                    rows[index] = next
                    changed += index
                }
            } else {
                val want = if (r.status == "good" || r.status == "done") r.status else "pending"
                if (r.status != want) {
                    rows[index] = r.copy(status = want)
                    changed += index
                }
            }
        }
        if (changed.isNotEmpty()) {
            persistBubbleRows(file, before, rows, changed, "check")
            if (checks.isNotEmpty() || reqs.isNotEmpty()) {
                try {
                    db.tx { d -> db.saveCheckDetails(d, fileId, checks, reqs) }
                } catch (_: Exception) {
                }
            }
            store.reloadOpenFileIfMatches(fileId)
            store.refresh()
        }
        return SheetBubbleCheckResult(load(fileId), changed.isNotEmpty(), valid, dead)
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
        var cookieChanged = false
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
                cookieChanged = true
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
        return SheetBubbleCaptureResult(snapshot, changedRows.isNotEmpty(), message, cookieChanged)
    }

    private fun validFile(fileId: String): SheetFile? {
        val file = db.getFile(fileId)
        if (file == null || file.archived) {
            store.clearBubbleFileIfSelected(fileId)
            return null
        }
        return file
    }

    private fun hasHistory(fileId: String, undo: Boolean): Boolean = try {
        if (undo) db.loadUndoStack(fileId, 1).isNotEmpty()
        else db.loadRedoStack(fileId, 1).isNotEmpty()
    } catch (_: Exception) {
        false
    }

    private fun effUid(r: SheetRow): String = r.uid.ifEmpty {
        Regex("c_user=(\\d+)").find(r.cookies)?.groupValues?.get(1) ?: ""
    }

    /** Shared persist for bubble history/check writes (undo-aware, op-logged). */
    private fun persistBubbleRows(
        file: SheetFile,
        before: List<SheetRow>,
        after: List<SheetRow>,
        changed: Set<Int>,
        op: String,
        pushRedo: Boolean
    ) {
        val now = System.currentTimeMillis()
        val seq = file.seq + 1
        val stale = changed.filter { index ->
            val o = before.getOrNull(index)
            val n = after.getOrNull(index)
            o == null || n == null || o.cookies != n.cookies ||
                o.uid != n.uid || o.status != n.status || o.dead != n.dead
        }.toSet()
        db.tx { d ->
            db.saveAllRows(d, file.id, after)
            db.saveSnapshot(d, file.id, seq, encodeSheetRows(after))
            db.updateFile(d, file.copy(updatedAt = now, seq = seq))
            if (op == "undo") {
                db.insertRedo(d, file.id, encodeSheetRows(before))
                db.popUndo(d, file.id)
            } else {
                db.insertUndo(d, file.id, encodeSheetRows(before))
                if (pushRedo) db.popRedo(d, file.id) else db.clearRedo(d, file.id)
            }
            db.recordOp(d, file.id, op)
            db.deleteRowCheckData(d, file.id, stale)
        }
        store.reloadOpenFileIfMatches(file.id)
        store.refresh()
    }

    private fun applyHistory(file: SheetFile, restored: List<SheetRow>, op: String) {
        val before = rowsFor(file.id)
        val after = restored.mapIndexed { index, row ->
            if (row.rowIdx == index) row else row.copy(rowIdx = index)
        }.toMutableList()
        while (after.size < MAX_GRID_ROWS) after.add(SheetRow(rowIdx = after.size))
        val changed = after.indices.filter { after[it] != before.getOrNull(it) }.toSet()
        if (changed.isEmpty()) return
        persistBubbleRows(file, before, after, changed, op, pushRedo = op == "redo")
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
    val message: String,
    val cookieChanged: Boolean = false
)

data class SheetBubbleHistoryResult(
    val snapshot: SheetBubbleSnapshot?,
    val changed: Boolean
)

data class SheetBubbleCheckResult(
    val snapshot: SheetBubbleSnapshot?,
    val checked: Boolean,
    val valid: Int,
    val dead: Int
)
