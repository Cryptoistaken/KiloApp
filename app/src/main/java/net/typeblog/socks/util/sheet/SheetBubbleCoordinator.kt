package net.typeblog.socks.util.sheet

import android.content.Context
import net.typeblog.socks.util.Constants.PREF_SHEET_BUBBLE_FILE_ID

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

    /** Circle-menu Sheet glyph: the configured file's preset icon, else generic. */
    fun bubbleIconRes(): Int {
        return try {
            val id = androidx.preference.PreferenceManager.getDefaultSharedPreferences(appContext)
                .getString(PREF_SHEET_BUBBLE_FILE_ID, null)
                ?: return net.typeblog.socks.R.drawable.ic_tab_sheet
            val file = db.getFile(id)
            if (file == null || file.archived) return net.typeblog.socks.R.drawable.ic_tab_sheet
            when (file.preset) {
                SheetPreset.COOKIE -> net.typeblog.socks.R.drawable.ic_ss_cookie
                SheetPreset.COMBO -> net.typeblog.socks.R.drawable.ic_ss_twofa
                SheetPreset.PAGE -> net.typeblog.socks.R.drawable.ic_ss_page
            }
        } catch (_: Exception) {
            net.typeblog.socks.R.drawable.ic_tab_sheet
        }
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
        // The tables load oldest-first while pop removes the newest: the
        // step to restore is the LAST entry, not the first (first wipes
        // every change at once). Tables are capped at 50, so this is whole.
        val prevData = try { db.loadUndoStack(fileId, 50) } catch (_: Exception) { emptyList() }
            .lastOrNull() ?: return SheetBubbleHistoryResult(load(fileId), false)
        applyHistory(file, decodeSheetRows(prevData), "undo")
        return SheetBubbleHistoryResult(load(fileId), true)
    }

    fun redo(fileId: String): SheetBubbleHistoryResult {
        val file = validFile(fileId)
            ?: return SheetBubbleHistoryResult(null, false)
        val nextData = try { db.loadRedoStack(fileId, 50) } catch (_: Exception) { emptyList() }
            .lastOrNull() ?: return SheetBubbleHistoryResult(load(fileId), false)
        applyHistory(file, decodeSheetRows(nextData), "redo")
        return SheetBubbleHistoryResult(load(fileId), true)
    }

    /**
     * File-scoped check over every row, mirroring SheetStore.runCheck
     * phase by phase (UID liveness, then PAGE Simple/Advanced sweeps),
     * gated by the same toggles. Dead wins, live never downgrades
     * eligible, offline falls back to the format heuristic. One persist
     * (one undo entry); details saved behind the dot popup like in-app.
     * Phase bodies keep base indent so the mirrored store logic diffs
     * cleanly; the if-gates only wrap them.
     */
    fun checkFile(
        fileId: String,
        uidOn: Boolean = true,
        simpleOn: Boolean = false,
        advancedOn: Boolean = false
    ): SheetBubbleCheckResult {
        val file = validFile(fileId)
            ?: return SheetBubbleCheckResult(null, false, 0, 0)
        val cols = file.preset.columns
        val rows = rowsFor(fileId)
        val before = rows.map { it.copy() }
        val now = System.currentTimeMillis()
        val checks = mutableMapOf<Int, RowCheck>()
        val reqs = mutableMapOf<Int, MutableList<CheckReq>>()
        val changed = linkedSetOf<Int>()
        var valid = 0
        var dead = 0
        var eligible = 0
        // 1. UID liveness: one batched direct request (worker checkUids).
        if (uidOn) {
        val targets = rows.filter { it.isData(cols) && !it.locked && effUid(it).isNotEmpty() }
        if (targets.isNotEmpty()) {
        val batch = try {
            SheetChecker.checkUids(targets.map { effUid(it) }.distinct())
        } catch (_: Exception) {
            null
        }
        val verdict = batch?.dead
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
        }
        }
        // 2. Page sweeps: direct per-row scrapes, sequential like the
        // worker (first 25 candidates per run to avoid rate limits).
        // Eligible rows are never swept again — once eligible, only a
        // dead UID verdict (above) can move them. With UID off, fresh
        // unchecked rows are swept directly so a new cookie is still
        // page-checked.
        if (file.preset == SheetPreset.PAGE && (simpleOn || advancedOn)) {
        val cands = rows.filter { r ->
            r.isData(cols) && !r.locked && !r.approved && !r.hold && !r.dead &&
                "c_user=" in r.cookies && effUid(r).isNotEmpty() &&
                (r.status == "good" || (!uidOn && (r.status.isEmpty() || r.status == "pending")))
        }.take(25)
        for (r in cands) {
            try {
                if (simpleOn) {
                    val (res, traces) = SheetChecker.pageSimple(r.cookies)
                    val base = checks.getOrPut(r.rowIdx) { RowCheck(checkedAt = now) }
                    checks[r.rowIdx] = base.copy(
                        checkedAt = now,
                        simplePage = res.pageName, simpleNumber = res.linkedNumber,
                        simpleError = res.error
                    )
                    for (t in traces) {
                        reqs.getOrPut(r.rowIdx) { mutableListOf() }.add(
                            t.toCheckReq(
                                res.pageName?.let { "Page \"$it\"" }
                                    ?: res.error ?: "No page"
                            )
                        )
                    }
                    if (res.error == null && res.eligible) {
                        rows[r.rowIdx] = rows[r.rowIdx].copy(status = "eligible")
                        changed += r.rowIdx
                        eligible++
                    }
                } else {
                    val (res, traces) = SheetChecker.pageAdvanced(r.cookies)
                    val base = checks.getOrPut(r.rowIdx) { RowCheck(checkedAt = now) }
                    checks[r.rowIdx] = base.copy(
                        checkedAt = now,
                        advEligible = res.eligible,
                        advPage = res.pageName, advNumber = res.linkedNumber,
                        advBan = res.banReason, advError = res.error
                    )
                    for (t in traces) {
                        reqs.getOrPut(r.rowIdx) { mutableListOf() }.add(
                            when (t.kind) {
                                "graphql" -> t.toCheckReq(
                                    if (res.eligible) "Eligible"
                                    else (res.error ?: res.banReason ?: "Not eligible")
                                )
                                else -> t.toCheckReq(t.error ?: "Page shell")
                            }
                        )
                    }
                    if (res.error == null && res.eligible) {
                        rows[r.rowIdx] = rows[r.rowIdx].copy(status = "eligible")
                        changed += r.rowIdx
                        eligible++
                    }
                }
            } catch (_: Exception) {
                // Challenges/rate limits: leave the row, try next.
            }
        }
        }
        if (changed.isNotEmpty()) {
            persistBubbleRows(file, before, rows, changed, "check", pushRedo = false)
            if (checks.isNotEmpty() || reqs.isNotEmpty()) {
                try {
                    db.tx { d -> db.saveCheckDetails(d, fileId, checks, reqs) }
                } catch (_: Exception) {
                }
            }
            store.reloadOpenFileIfMatches(fileId)
            store.refresh()
        }
        return SheetBubbleCheckResult(load(fileId), changed.isNotEmpty(), valid, dead, eligible)
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

        // Bubble infinite scroll (in-app growRows parity at bubble scale):
        // with 4 or fewer trailing empty rows left, append 5 fresh ones so
        // the next captures always land. Growth rides the capture write.
        val grownRows = linkedSetOf<Int>()
        if (trailingEmptyCount(rows, file.preset) <= BUBBLE_MIN_TRAILING) {
            val start = rows.size
            repeat(BUBBLE_GROW_ROWS) {
                rows.add(SheetRow(rowIdx = start + it))
                grownRows += start + it
            }
        }

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

        if (changedRows.isNotEmpty() || grownRows.isNotEmpty()) {
            val now = System.currentTimeMillis()
            val seq = file.seq + 1
            val staleChecks = changedRows.filter { index ->
                val before = previousRows.getOrNull(index)
                val after = rows.getOrNull(index)
                before == null || after == null || before.cookies != after.cookies ||
                    before.uid != after.uid || before.status != after.status || before.dead != after.dead
            }.toSet()
            db.tx { d ->
                for (index in changedRows + grownRows) {
                    rows.getOrNull(index)?.let { db.upsertRow(d, fileId, it) }
                }
                db.saveSnapshot(d, fileId, seq, encodeSheetRows(rows))
                db.updateFile(d, file.copy(updatedAt = now, seq = seq))
                db.insertUndo(d, fileId, encodeSheetRows(previousRows))
                db.clearRedo(d, fileId)
                db.recordOp(d, fileId, if (changedRows.isNotEmpty()) "bubble" else "bubble-grow")
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

    /** Empty rows after the last data row; a dataless file has them all. */
    private fun trailingEmptyCount(rows: List<SheetRow>, preset: SheetPreset): Int {
        val lastData = rows.indexOfLast { it.isData(preset.columns) }
        return if (lastData < 0) rows.size else rows.size - 1 - lastData
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
    val dead: Int,
    val eligible: Int = 0
)
