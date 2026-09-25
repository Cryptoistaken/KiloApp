package net.typeblog.socks.util.sheet

import android.content.Context
import android.util.Log


/**
 * File-scoped coordinator for the floating Sheet bubble. It never publishes
 * openFile/openRows and therefore cannot replace the full Sheet editor state.
 */
private const val BUBBLE_TAG = "SheetBubble"

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
            dups = try {
                db.crossDupCells(fileId, rows)
            } catch (e: Exception) {
                Log.w(BUBBLE_TAG, "Failed to load Sheet bubble duplicates for $fileId", e)
                emptySet()
            },
            styles = try {
                db.loadStyles(fileId)
            } catch (e: Exception) {
                Log.w(BUBBLE_TAG, "Failed to load Sheet bubble styles for $fileId", e)
                emptyMap()
            },
            hidden = try {
                db.loadHidden(fileId)
            } catch (e: Exception) {
                Log.w(BUBBLE_TAG, "Failed to load Sheet bubble columns for $fileId", e)
                emptySet()
            },
            canUndo = hasHistory(fileId, undo = true),
            canRedo = hasHistory(fileId, undo = false)
        )
    }

    /** Circle-menu Sheet glyph: the configured file's preset icon, else generic. */
    fun bubbleIconRes(): Int = when (store.getActiveBubbleFile()?.preset) {
        SheetPreset.COOKIE -> net.typeblog.socks.R.drawable.ic_ss_cookie
        SheetPreset.COMBO -> net.typeblog.socks.R.drawable.ic_ss_twofa
        SheetPreset.PAGE -> net.typeblog.socks.R.drawable.ic_ss_page
        null -> net.typeblog.socks.R.drawable.ic_tab_sheet
    }

    /** Whole-file UID check on every new cookie by default (ss_autoCheck). */
    fun isAutoCheckOn(): Boolean = try {
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(appContext)
            .getBoolean("ss_autoCheck", true)
    } catch (e: Exception) {
        Log.w(BUBBLE_TAG, "Failed to read Sheet auto-check preference", e)
        true
    }


    /** File-scoped undo/redo over the store's persisted history. */
    fun undo(fileId: String): SheetBubbleHistoryResult {
        validFile(fileId) ?: return SheetBubbleHistoryResult(null, false)
        val changed = store.undoFile(fileId)
        return SheetBubbleHistoryResult(load(fileId), changed)
    }

    fun redo(fileId: String): SheetBubbleHistoryResult {
        validFile(fileId) ?: return SheetBubbleHistoryResult(null, false)
        val changed = store.redoFile(fileId)
        return SheetBubbleHistoryResult(load(fileId), changed)
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
        val details = if (checks.isNotEmpty() || reqs.isNotEmpty()) {
            SheetCheckDetails(
                checks = checks.toMap(),
                reqs = reqs.mapValues { (_, list) -> list.toList() }
            )
        } else {
            null
        }
        val persisted = if (changed.isNotEmpty() || details != null) {
            store.persistRowsForFile(
                fileId = fileId,
                previous = before,
                rows = rows,
                expectedSequence = file.seq,
                checkDetails = details
            )
        } else {
            false
        }
        if (!persisted && (changed.isNotEmpty() || details != null)) {
            Log.i(BUBBLE_TAG, "Skipped stale Sheet bubble check for $fileId")
        }
        return SheetBubbleCheckResult(load(fileId), persisted, valid, dead, eligible)
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

        val saved = if (changedRows.isNotEmpty()) {
            store.persistRowsForFile(
                fileId = fileId,
                previous = previousRows,
                rows = rows,
                expectedSequence = file.seq
            )
        } else {
            false
        }
        if (!saved && changedRows.isNotEmpty()) {
            Log.i(BUBBLE_TAG, "Skipped stale Sheet bubble capture for $fileId")
        }

        val snapshot = load(fileId)
        val changed = saved && changedRows.isNotEmpty()
        val resultMessage = if (changed) message else if (changedRows.isNotEmpty()) {
            "Couldn't save. Please try again."
        } else {
            ""
        }
        return SheetBubbleCaptureResult(snapshot, changed, resultMessage, changed && cookieChanged)
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
    } catch (e: Exception) {
        Log.w(BUBBLE_TAG, "Failed to load Sheet bubble history for $fileId", e)
        false
    }


    private fun effUid(r: SheetRow): String = r.uid.ifEmpty {
        Regex("c_user=(\\d+)").find(r.cookies)?.groupValues?.get(1) ?: ""
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
