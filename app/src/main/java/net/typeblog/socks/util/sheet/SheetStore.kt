package net.typeblog.socks.util.sheet

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import net.typeblog.socks.util.Constants.PREF_SHEET_BUBBLE_FILE_ID

private const val HISTORY_LIMIT = 20
private const val TAG = "SheetStore"

internal enum class SheetHistoryAction {
    NONE,
    PUSH,
    UNDO,
    REDO
}

internal data class SheetCheckDetails(
    val checks: Map<Int, RowCheck>,
    val reqs: Map<Int, List<CheckReq>>
)

private data class OpenSnapshot(
    val file: SheetFile,
    val rows: List<SheetRow>,
    val styles: Map<String, CellStyle>,
    val hidden: Set<String>,
    val checks: Map<Int, RowCheck>,
    val reqs: Map<Int, List<CheckReq>>,
    val dups: Set<Pair<Int, String>>,
    val undo: List<List<SheetRow>>,
    val redo: List<List<SheetRow>>
)

private data class RefreshSnapshot(
    val files: List<SheetFile>,
    val archive: List<SheetFile>,
    val balance: Double,
    val txs: List<WalletTx>
)

private data class CheckContext(
    val token: Long,
    val fileId: String,
    val sequence: Long,
    val generation: Long,
    val preset: SheetPreset,
    val rows: List<SheetRow>
)

// Local-first SQLite state for the Sheet tab. All row writes go through the
// serialized mutation path below so file sequence, history, and check records
// cannot diverge.
class SheetStore private constructor(context: Context) {
    private val db = SheetDb(context.applicationContext)
    private val bubbleFilePrefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val mutationLock = Any()
    private val openGeneration = java.util.concurrent.atomic.AtomicLong(0L)
    private var openingFileId: String? = null
    private var refreshGeneration = 0L
    private var checkToken = 0L

    val files = MutableStateFlow<List<SheetFile>>(emptyList())
    val archive = MutableStateFlow<List<SheetFile>>(emptyList())
    val balance = MutableStateFlow(0.0)
    val txs = MutableStateFlow<List<WalletTx>>(emptyList())

    val openFile = MutableStateFlow<SheetFile?>(null)
    val openRows = MutableStateFlow<List<SheetRow>>(emptyList())
    val openStyles = MutableStateFlow<Map<String, CellStyle>>(emptyMap())
    val openHidden = MutableStateFlow<Set<String>>(emptySet())
    val openCrossDups = MutableStateFlow<Set<Pair<Int, String>>>(emptySet())
    val openChecks = MutableStateFlow<Map<Int, RowCheck>>(emptyMap())
    val openCheckReqs = MutableStateFlow<Map<Int, List<CheckReq>>>(emptyMap())
    val checking = MutableStateFlow(false)

    val bubbleFileId = MutableStateFlow<String?>(null)

    private val undoStack = ArrayDeque<List<SheetRow>>()
    private val redoStack = ArrayDeque<List<SheetRow>>()
    val canUndo = MutableStateFlow(false)
    val canRedo = MutableStateFlow(false)

    init {
        bubbleFileId.value = bubbleFilePrefs.getString(PREF_SHEET_BUBBLE_FILE_ID, null)
        refresh()
    }

    private inline fun <T> locked(block: () -> T): T = synchronized(mutationLock, block)

    fun refresh() {
        val generation = locked { ++refreshGeneration }
        scope.launch {
            try {
                val next = locked {
                    RefreshSnapshot(
                        files = db.listFiles(false),
                        archive = db.listFiles(true),
                        balance = db.walletBalance(),
                        txs = db.walletTxs()
                    )
                }
                locked {
                    if (generation == refreshGeneration) {
                        files.value = next.files
                        archive.value = next.archive
                        balance.value = next.balance
                        txs.value = next.txs
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to refresh Sheet state", e)
            }
        }
    }

    fun selectBubbleFile(id: String): Boolean = locked {
        val file = try {
            db.getFile(id)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load bubble Sheet file $id", e)
            null
        }
        if (file == null || file.archived) return@locked false
        bubbleFilePrefs.edit().putString(PREF_SHEET_BUBBLE_FILE_ID, id).apply()
        bubbleFileId.value = id
        true
    }

    fun getActiveBubbleFile(): SheetFile? = locked {
        val id = bubbleFilePrefs.getString(PREF_SHEET_BUBBLE_FILE_ID, null)
        if (id == null) {
            bubbleFileId.value = null
            return@locked null
        }
        val file = try {
            db.getFile(id)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve active bubble Sheet file $id", e)
            null
        }
        if (file == null || file.archived) {
            bubbleFilePrefs.edit().remove(PREF_SHEET_BUBBLE_FILE_ID).apply()
            bubbleFileId.value = null
            null
        } else {
            file
        }
    }

    fun clearBubbleFile() = locked {
        bubbleFilePrefs.edit().remove(PREF_SHEET_BUBBLE_FILE_ID).apply()
        bubbleFileId.value = null
    }

    fun clearBubbleFileIfSelected(id: String) = locked {
        if (bubbleFilePrefs.getString(PREF_SHEET_BUBBLE_FILE_ID, null) == id) {
            bubbleFilePrefs.edit().remove(PREF_SHEET_BUBBLE_FILE_ID).apply()
            bubbleFileId.value = null
        }
    }

    private fun readHistoryLocked(fileId: String): Pair<List<List<SheetRow>>, List<List<SheetRow>>> {
        val undo = db.loadUndoStack(fileId, HISTORY_LIMIT).mapNotNull { data ->
            try {
                rowsFromJson(data).map { it.copy() }
            } catch (e: Exception) {
                Log.w(TAG, "Skipping corrupt undo history for $fileId", e)
                null
            }
        }.asReversed()
        val redo = db.loadRedoStack(fileId, HISTORY_LIMIT).mapNotNull { data ->
            try {
                rowsFromJson(data).map { it.copy() }
            } catch (e: Exception) {
                Log.w(TAG, "Skipping corrupt redo history for $fileId", e)
                null
            }
        }.asReversed()
        return undo to redo
    }


    private fun publishHistoryLocked() {
        canUndo.value = undoStack.isNotEmpty()
        canRedo.value = redoStack.isNotEmpty()
    }

    fun undo(): Boolean = locked {
        val file = openFile.value ?: return@locked false
        val target = undoStack.firstOrNull() ?: return@locked false
        val previous = openRows.value.map { it.copy() }
        persistRowsLocked(
            fileId = file.id,
            previous = previous,
            rows = topUp(target, file.preset),
            history = SheetHistoryAction.UNDO,
            expectedSequence = file.seq,
            expectedGeneration = openGeneration.get()
        )
    }

    fun redo(): Boolean = locked {
        val file = openFile.value ?: return@locked false
        val target = redoStack.firstOrNull() ?: return@locked false
        val previous = openRows.value.map { it.copy() }
        persistRowsLocked(
            fileId = file.id,
            previous = previous,
            rows = topUp(target, file.preset),
            history = SheetHistoryAction.REDO,
            expectedSequence = file.seq,
            expectedGeneration = openGeneration.get()
        )
    }

    fun createFile(preset: SheetPreset, password: String): SheetFile = locked {
        val now = System.currentTimeMillis()
        val names = files.value.map { it.name } + archive.value.map { it.name }
        val file = SheetFile(
            id = newFileId(),
            name = autoFileName(preset, names),
            preset = preset,
            password = password,
            archived = false,
            deletedAt = 0,
            createdAt = now,
            updatedAt = now,
            seq = 0
        )
        try {
            db.tx { d -> db.insertFile(d, file) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create Sheet file", e)
            throw e
        }
        refresh()
        file
    }

    fun renameFile(id: String, name: String): Boolean = locked {
        val file = try {
            db.getFile(id)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load Sheet file $id for rename", e)
            null
        } ?: return@locked false
        val now = System.currentTimeMillis()
        val renamed = file.copy(name = name, updatedAt = now, seq = file.seq + 1)
        try {
            db.tx { d -> db.updateFile(d, renamed) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to rename Sheet file $id", e)
            return@locked false
        }
        if (openFile.value?.id == id) openFile.value = renamed
        refresh()
        true
    }

    fun archiveFile(id: String, toArchive: Boolean) = locked {
        val file = try {
            db.getFile(id)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load Sheet file $id for archive change", e)
            null
        } ?: return@locked
        val now = System.currentTimeMillis()
        val changed = file.copy(
            archived = toArchive,
            deletedAt = if (toArchive) now else 0,
            updatedAt = now,
            seq = file.seq + 1
        )
        try {
            db.tx { d -> db.updateFile(d, changed) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to archive Sheet file $id", e)
            return@locked
        }
        if (toArchive) clearBubbleFileIfSelected(id)
        if (openFile.value?.id == id && toArchive) closeFile(id)
        refresh()
    }

    fun deleteForever(id: String) = locked {
        try {
            db.tx { d -> db.deleteFileAll(d, id) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete Sheet file $id", e)
            return@locked
        }
        clearBubbleFileIfSelected(id)
        if (openFile.value?.id == id) closeFile(id)
        refresh()
    }

    fun open(id: String): Boolean {
        val request = openGeneration.incrementAndGet()
        return locked {
            if (request != openGeneration.get()) return@locked false
            openingFileId = id
            checkToken++
            checking.value = false
            val file = try {
                db.getFile(id)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open Sheet file $id", e)
                null
            }
            if (file == null) {
                openingFileId = null
                return@locked false
            }
            val snapshot = try {
                loadOpenSnapshot(file)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load Sheet snapshot $id", e)
                null
            }
            if (snapshot == null) {
                openingFileId = null
                return@locked false
            }
            if (request != openGeneration.get() || openingFileId != id) {
                if (request == openGeneration.get() && openingFileId == id) openingFileId = null
                return@locked false
            }
            publishOpenSnapshotLocked(snapshot)
            openingFileId = null
            true
        }
    }


    fun closeFile(expectedFileId: String? = null) {
        locked {
            if (expectedFileId != null) {
                val openingMatches = openingFileId == expectedFileId
                val publishedMatches = openFile.value?.id == expectedFileId
                if (!openingMatches && !publishedMatches) return@locked
                // A newer open request owns the generation. Closing the old
                // screen must clear only the old publication, not cancel it.
                if (openingMatches || openingFileId == null) openGeneration.incrementAndGet()
                if (openingMatches) openingFileId = null
            } else {
                openGeneration.incrementAndGet()
                openingFileId = null
            }
            checkToken++
            checking.value = false
            openFile.value = null
            openRows.value = emptyList()
            openStyles.value = emptyMap()
            openHidden.value = emptySet()
            openCrossDups.value = emptySet()
            openChecks.value = emptyMap()
            openCheckReqs.value = emptyMap()
            undoStack.clear()
            redoStack.clear()
            canUndo.value = false
            canRedo.value = false
        }
    }

    private fun loadOpenSnapshot(file: SheetFile): OpenSnapshot {
        val rows = topUp(db.loadRows(file.id), file.preset)
        val dups = try {
            db.crossDupCells(file.id, rows)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to calculate cross-file duplicates for ${file.id}", e)
            emptySet()
        }
        val (undo, redo) = readHistoryLocked(file.id)
        return OpenSnapshot(
            file = file,
            rows = rows,
            styles = db.loadStyles(file.id),
            hidden = db.loadHidden(file.id),
            checks = db.loadRowChecks(file.id),
            reqs = db.loadCheckReqs(file.id),
            dups = dups,
            undo = undo,
            redo = redo
        )
    }

    private fun publishOpenSnapshotLocked(snapshot: OpenSnapshot) {
        openRows.value = snapshot.rows
        openStyles.value = snapshot.styles
        openHidden.value = snapshot.hidden
        openChecks.value = snapshot.checks
        openCheckReqs.value = snapshot.reqs
        openCrossDups.value = snapshot.dups
        undoStack.clear()
        redoStack.clear()
        snapshot.undo.forEach { undoStack.addLast(it) }
        snapshot.redo.forEach { redoStack.addLast(it) }
        publishHistoryLocked()
        openFile.value = snapshot.file
    }


    private fun topUp(rows: List<SheetRow>, preset: SheetPreset): List<SheetRow> {
        val normalized = meaningfulSheetRows(rows).mapIndexed { index, row ->
            if (row.rowIdx == index) row else row.copy(rowIdx = index)
        }
        val lastData = normalized.indexOfLast { it.isData(preset.columns) }
        val want = (lastData + 51).coerceAtLeast(MAX_GRID_ROWS)
        if (normalized.size >= want) return normalized
        return normalized + (normalized.size until want).map { SheetRow(rowIdx = it) }
    }

    private fun normalizedRows(rows: List<SheetRow>): List<SheetRow> =
        rows.take(MAX_GRID_ROWS).mapIndexed { index, row -> row.copy(rowIdx = index) }

    private fun staleChecks(previous: List<SheetRow>, rows: List<SheetRow>): Set<Int> {
        val before = previous.associateBy { it.rowIdx }
        val after = rows.associateBy { it.rowIdx }
        return (before.keys + after.keys).filter { before[it] != after[it] }.toSet()
    }

    private fun persistRowsLocked(
        fileId: String,
        previous: List<SheetRow>,
        rows: List<SheetRow>,
        history: SheetHistoryAction = SheetHistoryAction.PUSH,
        clearChecks: Boolean = false,
        checkDetails: SheetCheckDetails? = null,
        expectedSequence: Long? = null,
        expectedGeneration: Long? = null
    ): Boolean {
        if (openingFileId != null) return false
        if (expectedGeneration != null && expectedGeneration != openGeneration.get()) return false
        if (rows.size > MAX_GRID_ROWS) {
            Log.e(TAG, "Rejected Sheet row mutation over the $MAX_GRID_ROWS row limit for $fileId")
            return false
        }
        val current = try {
            db.getFile(fileId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load Sheet file $fileId before mutation", e)
            return false
        } ?: return false
        if (expectedSequence != null && current.seq != expectedSequence) return false

        val previousMeaningful = meaningfulSheetRows(previous)
        val nextMeaningful = meaningfulSheetRows(rows)
        val rowsChanged = previousMeaningful != nextMeaningful
        if (!rowsChanged && checkDetails != null && !clearChecks) {
            try {
                db.tx { d -> db.saveCheckDetails(d, fileId, checkDetails.checks, checkDetails.reqs) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist Sheet check details for $fileId", e)
                return false
            }
            val publish = openFile.value?.id == fileId &&
                    (expectedGeneration == null || expectedGeneration == openGeneration.get())
            if (publish) {
                openChecks.value = checkDetails.checks
                openCheckReqs.value = checkDetails.reqs
            }
            return true
        }
        if (!rowsChanged && checkDetails == null && !clearChecks && history == SheetHistoryAction.PUSH) {
            if (openFile.value?.id == fileId &&
                (expectedGeneration == null || expectedGeneration == openGeneration.get())
            ) {
                openRows.value = topUp(rows, current.preset)
            }
            return true
        }

        val now = System.currentTimeMillis()
        val sequence = current.seq + 1
        val updated = current.copy(updatedAt = now, seq = sequence)
        val stale = if (clearChecks) emptySet() else staleChecks(previous, rows)
        try {
            db.tx { d ->
                db.saveAllRows(d, fileId, rows)
                db.updateFile(d, updated)
                when (history) {
                    SheetHistoryAction.PUSH -> {
                        db.insertUndo(d, fileId, rowsToJson(previous))
                        db.clearRedo(d, fileId)
                    }

                    SheetHistoryAction.UNDO -> {
                        db.insertRedo(d, fileId, rowsToJson(previous))
                        db.popUndo(d, fileId)
                    }

                    SheetHistoryAction.REDO -> {
                        db.insertUndo(d, fileId, rowsToJson(previous))
                        db.popRedo(d, fileId)
                    }

                    SheetHistoryAction.NONE -> Unit
                }
                if (checkDetails != null) {
                    db.saveCheckDetails(d, fileId, checkDetails.checks, checkDetails.reqs)
                } else if (clearChecks) {
                    db.clearCheckData(d, fileId)
                } else if (stale.isNotEmpty()) {
                    db.deleteRowCheckData(d, fileId, stale)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist Sheet rows for $fileId", e)
            return false
        }

        val publish = openFile.value?.id == fileId &&
                (expectedGeneration == null || expectedGeneration == openGeneration.get())
        if (publish) {
            openFile.value = updated
            openRows.value = topUp(rows, updated.preset)
            when {
                checkDetails != null -> {
                    openChecks.value = checkDetails.checks
                    openCheckReqs.value = checkDetails.reqs
                }

                clearChecks -> {
                    openChecks.value = emptyMap()
                    openCheckReqs.value = emptyMap()
                }

                stale.isNotEmpty() -> {
                    openChecks.value = openChecks.value.filterKeys { it !in stale }
                    openCheckReqs.value = openCheckReqs.value.filterKeys { it !in stale }
                }
            }
            openCrossDups.value = crossDuplicates(fileId, openRows.value)
            when (history) {
                SheetHistoryAction.PUSH -> {
                    undoStack.addFirst(previousMeaningful)
                    while (undoStack.size > HISTORY_LIMIT) undoStack.removeLast()
                    redoStack.clear()
                }

                SheetHistoryAction.UNDO -> {
                    undoStack.removeFirstOrNull()
                    redoStack.addFirst(previousMeaningful)
                    while (redoStack.size > HISTORY_LIMIT) redoStack.removeLast()
                }

                SheetHistoryAction.REDO -> {
                    redoStack.removeFirstOrNull()
                    undoStack.addFirst(previousMeaningful)
                    while (undoStack.size > HISTORY_LIMIT) undoStack.removeLast()
                }

                SheetHistoryAction.NONE -> Unit
            }
            publishHistoryLocked()
        }
        refresh()
        return true
    }

    /** File-scoped bubble write; it shares the same transaction path as editor writes. */
    internal fun persistRowsForFile(
        fileId: String,
        previous: List<SheetRow>,
        rows: List<SheetRow>,
        expectedSequence: Long,
        history: SheetHistoryAction = SheetHistoryAction.PUSH,
        clearChecks: Boolean = false,
        checkDetails: SheetCheckDetails? = null
    ): Boolean = locked {
        persistRowsLocked(
            fileId = fileId,
            previous = previous,
            rows = rows,
            history = history,
            clearChecks = clearChecks,
            checkDetails = checkDetails,
            expectedSequence = expectedSequence
        )
    }

    internal fun undoFile(fileId: String): Boolean = locked {
        val file = try {
            db.getFile(fileId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load Sheet file $fileId for undo", e)
            null
        } ?: return@locked false
        val data = try {
            db.loadUndoStack(fileId, 1).lastOrNull()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load undo history for $fileId", e)
            null
        } ?: return@locked false
        val restored = try {
            rowsFromJson(data)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode undo history for $fileId", e)
            return@locked false
        }
        val before = try {
            db.loadRows(fileId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load rows before undo for $fileId", e)
            return@locked false
        }
        persistRowsLocked(
            fileId = fileId,
            previous = before,
            rows = topUp(restored, file.preset),
            history = SheetHistoryAction.UNDO,
            expectedSequence = file.seq
        )
    }

    internal fun redoFile(fileId: String): Boolean = locked {
        val file = try {
            db.getFile(fileId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load Sheet file $fileId for redo", e)
            null
        } ?: return@locked false
        val data = try {
            db.loadRedoStack(fileId, 1).lastOrNull()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load redo history for $fileId", e)
            null
        } ?: return@locked false
        val restored = try {
            rowsFromJson(data)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode redo history for $fileId", e)
            null
        } ?: return@locked false
        val before = try {
            db.loadRows(fileId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load rows before redo for $fileId", e)
            null
        } ?: return@locked false
        persistRowsLocked(
            fileId = fileId,
            previous = before,
            rows = topUp(restored, file.preset),
            history = SheetHistoryAction.REDO,
            expectedSequence = file.seq
        )
    }

    fun replaceRows(fileId: String, rows: List<SheetRow>): Int = locked {
        val file = try {
            db.getFile(fileId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load Sheet file $fileId for replace", e)
            null
        } ?: return@locked 0
        if (file.archived) return@locked 0
        if (rows.size > MAX_GRID_ROWS) {
            Log.e(TAG, "Rejected Sheet replace over the $MAX_GRID_ROWS row limit for $fileId")
            return@locked 0
        }
        val bounded = normalizedRows(rows)
        val before = try {
            db.loadRows(fileId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load rows before replace for $fileId", e)
            return@locked 0
        }
        val saved = meaningfulSheetRows(bounded).size
        if (!persistRowsLocked(
                fileId = fileId,
                previous = before,
                rows = bounded,
                history = SheetHistoryAction.PUSH,
                clearChecks = true,
                expectedSequence = file.seq
            )
        ) return@locked 0
        saved
    }

    fun mergeRows(fileId: String, incoming: List<SheetRow>): Int = locked {
        val file = try {
            db.getFile(fileId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load Sheet file $fileId for merge", e)
            null
        } ?: return@locked 0
        if (file.archived) return@locked 0
        val loaded = try {
            db.loadRows(fileId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load rows before merge for $fileId", e)
            return@locked 0
        }
        val previous = topUp(loaded, file.preset)
        val current = meaningfulSheetRows(loaded).mapIndexed { index, row ->
            row.copy(rowIdx = index)
        }
        val reindexed = loaded.indices.any { loaded[it].rowIdx != it } || loaded.size != current.size
        val room = MAX_GRID_ROWS - current.size
        if (room <= 0) return@locked 0
        val addition = meaningfulSheetRows(normalizedRows(incoming)).take(room)
        val merged = (current + addition).mapIndexed { index, row -> row.copy(rowIdx = index) }
        if (!reindexed && addition.isEmpty()) return@locked 0
        if (!persistRowsLocked(
                fileId = fileId,
                previous = previous,
                rows = merged,
                history = SheetHistoryAction.PUSH,
                clearChecks = reindexed,
                expectedSequence = file.seq
            )
        ) return@locked 0
        addition.size
    }

    private fun crossDuplicates(fileId: String, rows: List<SheetRow>): Set<Pair<Int, String>> = try {
        db.crossDupCells(fileId, rows)
    } catch (e: Exception) {
        Log.e(TAG, "Failed to calculate cross-file duplicates for $fileId", e)
        emptySet()
    }

    fun rejectReason(rowIdx: Int, colKey: String, value: String): String? = locked {
        rejectReasonLocked(rowIdx, colKey, value)
    }

    private fun rejectReasonLocked(rowIdx: Int, colKey: String, value: String): String? {
        val rows = openRows.value
        val current = rows.getOrNull(rowIdx) ?: return "Couldn't save. Please try again."
        if (current.locked || current.cell(colKey) == value) return null
        if (colKey == "uid" && current.cookies.isNotEmpty()) return "UID comes from the cookie."
        if (colKey == "twofakey" && value.isNotEmpty() && !isValidTwoFaValue(value)) {
            return "Invalid 2fa key."
        }
        if ((colKey == "uid" || colKey == "cookies" || colKey == "twofakey") && value.isNotEmpty() &&
            !(colKey == "twofakey" && isNo2Fa(value)) &&
            rows.any { it.rowIdx != rowIdx && it.cell(colKey) == value }
        ) {
            return "Duplicate " + when (colKey) {
                "cookies" -> "cookie."
                "twofakey" -> "2fa."
                else -> "uid."
            }
        }
        if (colKey == "cookies" && value.isNotEmpty()) {
            val extracted = extractCUser(value)
            if (extracted != null && rows.any { it.rowIdx != rowIdx && it.uid == extracted }) {
                return "Duplicate uid."
            }
        }
        return null
    }

    fun setCell(rowIdx: Int, colKey: String, value: String): Boolean = locked {
        val file = openFile.value ?: return@locked false
        val before = openRows.value
        if (rowIdx !in before.indices) return@locked false
        val current = before[rowIdx]
        if (current.locked) return@locked false
        if (current.cell(colKey) == value) return@locked true
        if (rejectReasonLocked(rowIdx, colKey, value) != null) return@locked false
        val rows = before.toMutableList()
        rows[rowIdx] = if (colKey == "cookies") {
            if (value.isEmpty()) {
                current.withCell(colKey, value).withCell("uid", "").copy(status = "", dead = false)
            } else {
                current.withCell(colKey, value).withCell("uid", extractCUser(value) ?: "")
                    .copy(status = "", dead = false)
            }
        } else if (colKey == "uid") {
            current.withCell(colKey, value).copy(status = "", dead = false)
        } else {
            current.withCell(colKey, value)
        }
        persistRowsLocked(
            fileId = file.id,
            previous = before,
            rows = rows,
            expectedSequence = file.seq,
            expectedGeneration = openGeneration.get()
        )
    }

    val copiedGrid = MutableStateFlow<CopiedGrid?>(null)

    fun copyGrid(grid: CopiedGrid) {
        copiedGrid.value = grid
    }

    fun pasteGrid(
        grid: CopiedGrid,
        anchor: Pair<Int, String>,
        area: Set<Pair<Int, String>>?,
        order: List<String>
    ): PasteResult = locked {
        pasteGridLocked(grid, anchor, area, order)
    }

    private fun pasteGridLocked(
        grid: CopiedGrid,
        anchor: Pair<Int, String>,
        area: Set<Pair<Int, String>>?,
        order: List<String>
    ): PasteResult {
        val file = openFile.value ?: return PasteResult(0, 0, false)
        if (grid.cells.isEmpty() || grid.columns.isEmpty() || order.isEmpty()) {
            return PasteResult(0, 0, false)
        }
        val before = openRows.value
        if (before.isEmpty()) return PasteResult(0, 0, false)
        val samePreset = grid.preset == file.preset.name
        val selectedRows: List<Int>
        val selectedCols: List<String>
        if (!area.isNullOrEmpty()) {
            selectedRows = area.map { it.first }.distinct().sorted()
            selectedCols = order.filter { key -> area.any { it.second == key } }
        } else {
            selectedRows = emptyList()
            selectedCols = emptyList()
        }
        val anchorRow = if (selectedRows.isNotEmpty()) selectedRows.first() else anchor.first
        val anchorKey = if (selectedCols.isNotEmpty()) selectedCols.first() else anchor.second
        val anchorColumn = order.indexOf(anchorKey).let { if (it < 0) 0 else it }
        val rowCount = grid.cells.size
        val columnCount = grid.columns.size

        data class Write(val row: Int, val key: String, val value: String)

        val firstPass = mutableListOf<Write>()
        val secondPass = mutableListOf<Write>()
        var skipped = 0
        for (i in 0 until maxOf(selectedRows.size, rowCount)) {
            val rowIndex = anchorRow + i
            if (rowIndex !in before.indices) {
                skipped += maxOf(selectedCols.size, columnCount)
                continue
            }
            for (j in 0 until maxOf(selectedCols.size, columnCount)) {
                val targetKey = if (j < selectedCols.size) selectedCols[j]
                else order.getOrNull(anchorColumn + j)
                if (targetKey == null) {
                    skipped++
                    continue
                }
                val sourceRow = grid.cells[i % rowCount]
                val value = if (samePreset) {
                    sourceRow.getOrNull(j % columnCount)?.second
                } else {
                    sourceRow.firstOrNull { it.first == targetKey }?.second
                }
                if (value == null) {
                    skipped++
                    continue
                }
                (if (targetKey == "uid") secondPass else firstPass)
                    .add(Write(rowIndex, targetKey, value))
            }
        }

        val rows = before.toMutableList()
        var pasted = 0
        var cookiesWritten = false
        var dirty = false
        var note: String? = null
        fun noteDuplicate(key: String) {
            if (note == null) {
                note = "Duplicate " + when (key) {
                    "cookies" -> "cookie."
                    "twofakey" -> "2fa."
                    else -> "uid."
                }
            }
        }

        for (write in firstPass) {
            val current = rows.getOrNull(write.row) ?: run {
                skipped++
                continue
            }
            if (current.locked) {
                skipped++
                continue
            }
            if (current.cell(write.key) == write.value) {
                pasted++
                continue
            }
            if (write.key == "twofakey" && write.value.isNotEmpty() && !isValidTwoFaValue(write.value)) {
                skipped++
                continue
            }
            if ((write.key == "cookies" || write.key == "twofakey") && write.value.isNotEmpty() &&
                !(write.key == "twofakey" && isNo2Fa(write.value)) &&
                rows.any { it.rowIdx != write.row && it.cell(write.key) == write.value }
            ) {
                noteDuplicate(write.key)
                skipped++
                continue
            }
            rows[write.row] = if (write.key == "cookies") {
                cookiesWritten = true
                current.withCell(write.key, write.value)
                    .withCell("uid", extractCUser(write.value) ?: "")
                    .copy(status = "", dead = false)
            } else {
                current.withCell(write.key, write.value)
            }
            dirty = true
            pasted++
        }
        for (write in secondPass) {
            val current = rows.getOrNull(write.row) ?: run {
                skipped++
                continue
            }
            if (current.locked) {
                skipped++
                continue
            }
            if (current.cookies.isNotEmpty()) {
                if (current.uid == write.value) pasted++
                else {
                    if (note == null) note = "UID comes from the cookie."
                    skipped++
                }
                continue
            }
            if (current.uid == write.value) {
                pasted++
                continue
            }
            if (write.value.isNotEmpty() && rows.any { it.rowIdx != write.row && it.uid == write.value }) {
                noteDuplicate("uid")
                skipped++
                continue
            }
            rows[write.row] = current.withCell("uid", write.value).copy(status = "", dead = false)
            dirty = true
            pasted++
        }
        if (!dirty) return PasteResult(pasted, skipped, false, note)
        val saved = persistRowsLocked(
            fileId = file.id,
            previous = before,
            rows = rows,
            expectedSequence = file.seq,
            expectedGeneration = openGeneration.get()
        )
        if (!saved) return PasteResult(0, 0, false, note)
        return PasteResult(pasted, skipped, cookiesWritten, note)
    }

    fun addRow(): Boolean = locked {
        val file = openFile.value ?: return@locked false
        if (openRows.value.size >= MAX_GRID_ROWS) return@locked false
        val before = openRows.value
        val rows = before + SheetRow(rowIdx = before.size)
        persistRowsLocked(
            fileId = file.id,
            previous = before,
            rows = rows,
            expectedSequence = file.seq,
            expectedGeneration = openGeneration.get()
        )
    }

    fun growRows(count: Int): Boolean = locked {
        val file = openFile.value ?: return@locked false
        if (count <= 0 || openRows.value.size >= MAX_GRID_ROWS) return@locked false
        val before = openRows.value
        val amount = minOf(count, MAX_GRID_ROWS - before.size)
        val rows = before + (before.size until before.size + amount).map { SheetRow(rowIdx = it) }
        persistRowsLocked(
            fileId = file.id,
            previous = before,
            rows = rows,
            expectedSequence = file.seq,
            expectedGeneration = openGeneration.get()
        )
    }

    fun clearCells(cells: Set<Pair<Int, String>>) = locked {
        val file = openFile.value ?: return@locked
        val before = openRows.value
        val rows = before.toMutableList()
        var touched = false
        for ((rowIndex, key) in cells) {
            if (rowIndex !in rows.indices || rows[rowIndex].locked) continue
            if (key == "uid" && rows[rowIndex].cookies.isNotEmpty()) continue
            if (rows[rowIndex].cell(key).isEmpty()) continue
            rows[rowIndex] = rows[rowIndex].withCell(key, "")
            if (key == "cookies") rows[rowIndex] = rows[rowIndex].withCell("uid", "")
            if (key == "cookies" || key == "uid") {
                rows[rowIndex] = rows[rowIndex].copy(status = "", dead = false)
            }
            touched = true
        }
        if (touched) {
            persistRowsLocked(
                fileId = file.id,
                previous = before,
                rows = rows,
                expectedSequence = file.seq,
                expectedGeneration = openGeneration.get()
            )
        }
    }

    fun deleteDeadRows(): Int = locked {
        val file = openFile.value ?: return@locked 0
        val before = openRows.value
        val dead = before.filter { it.status == "bad" || it.dead }
        if (dead.isEmpty()) return@locked 0
        val kept = before.filterNot { it.status == "bad" || it.dead }
            .mapIndexed { index, row -> row.copy(rowIdx = index) }
        val saved = persistRowsLocked(
            fileId = file.id,
            previous = before,
            rows = topUp(kept, file.preset),
            history = SheetHistoryAction.PUSH,
            clearChecks = true,
            expectedSequence = file.seq,
            expectedGeneration = openGeneration.get()
        )
        if (saved) dead.size else 0
    }

    fun compactRows() = locked {
        val file = openFile.value ?: return@locked
        val before = openRows.value
        val data = before.filter { it.isData(file.preset.columns) }
            .mapIndexed { index, row -> row.copy(rowIdx = index) }
        persistRowsLocked(
            fileId = file.id,
            previous = before,
            rows = topUp(data, file.preset),
            history = SheetHistoryAction.PUSH,
            clearChecks = true,
            expectedSequence = file.seq,
            expectedGeneration = openGeneration.get()
        )
    }

    fun setStyle(rowIdx: Int, colKey: String, style: CellStyle?) = locked {
        val file = openFile.value ?: return@locked
        try {
            db.tx { d -> db.saveStyle(d, file.id, rowIdx, colKey, style) }
            openStyles.value = db.loadStyles(file.id)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save Sheet style for ${file.id}", e)
        }
    }

    fun setHidden(hidden: Set<String>) = locked {
        val file = openFile.value ?: return@locked
        try {
            db.tx { d -> db.saveHidden(d, file.id, hidden) }
            openHidden.value = hidden
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save hidden columns for ${file.id}", e)
        }
    }


    fun runCheck(
        uidOn: Boolean = true,
        simpleOn: Boolean = false,
        advancedOn: Boolean = false,
        isPageFile: Boolean = false,
        done: (valid: Int, dead: Int) -> Unit
    ) {
        val context = locked {
            if (checking.value) return@locked null
            val file = openFile.value ?: return@locked null
            val token = ++checkToken
            checking.value = true
            CheckContext(
                token = token,
                fileId = file.id,
                sequence = file.seq,
                generation = openGeneration.get(),
                preset = file.preset,
                rows = openRows.value.map { it.copy() }
            )
        } ?: return

        scope.launch {
            var valid = 0
            var dead = 0
            var rows = context.rows
            val now = System.currentTimeMillis()
            val checks = mutableMapOf<Int, RowCheck>()
            val reqs = mutableMapOf<Int, MutableList<CheckReq>>()
            fun requestList(rowIndex: Int): MutableList<CheckReq> =
                reqs.getOrPut(rowIndex) { mutableListOf() }

            fun effectiveUid(row: SheetRow): String = row.uid.ifEmpty {
                Regex("c_user=(\\d+)").find(row.cookies)?.groupValues?.get(1) ?: ""
            }

            try {
                val columns = context.preset.columns
                if (uidOn) {
                    val targets = rows.filter { row ->
                        row.isData(columns) && !row.locked && effectiveUid(row).isNotEmpty()
                    }.map { effectiveUid(it) }.distinct()
                    val batch = if (targets.isNotEmpty()) {
                        try {
                            SheetChecker.checkUids(targets)
                        } catch (e: Exception) {
                            Log.w(TAG, "UID check failed; using local validation", e)
                            null
                        }
                    } else {
                        null
                    }
                    val deadUids = batch?.dead
                    rows = rows.map { row ->
                        if (!row.isData(columns) || row.locked) {
                            row
                        } else if (row.cookies.isNotEmpty() && effectiveUid(row).isNotEmpty()) {
                            val uid = effectiveUid(row)
                            val alive = if (deadUids == null) isValidUid(uid) else uid !in deadUids
                            if (alive) valid++ else dead++
                            checks[row.rowIdx] = RowCheck(checkedAt = now, uidOk = alive)
                            if (batch != null) {
                                requestList(row.rowIdx).add(
                                    batch.trace.toCheckReq(
                                        if (alive) "valid" else (batch.names[uid] ?: "dead")
                                    )
                                )
                            }
                            if (alive) {
                                row.copy(
                                    status = if (row.status == "eligible") "eligible" else "good",
                                    dead = false
                                )
                            } else {
                                row.copy(status = "bad", dead = true)
                            }
                        } else if (row.uid.isNotEmpty() && !isValidUid(row.uid)) {
                            dead++
                            checks[row.rowIdx] = RowCheck(checkedAt = now, uidOk = false)
                            row.copy(status = "bad", dead = true)
                        } else {
                            val status = if (row.status == "good" || row.status == "done") {
                                row.status
                            } else {
                                "pending"
                            }
                            row.copy(status = status)
                        }
                    }
                }

                if (isPageFile && (simpleOn || advancedOn)) {
                    val candidates = rows.filter { row ->
                        row.isData(columns) && !row.locked && !row.approved && !row.hold && !row.dead &&
                                "c_user=" in row.cookies && effectiveUid(row).isNotEmpty() &&
                                (row.status == "good" || (!uidOn && (row.status.isEmpty() || row.status == "pending")))
                    }.take(25)
                    val updated = rows.toMutableList()
                    for (row in candidates) {
                        try {
                            if (simpleOn) {
                                val (result, traces) = SheetChecker.pageSimple(row.cookies)
                                val base = checks[row.rowIdx] ?: RowCheck(checkedAt = now)
                                checks[row.rowIdx] = base.copy(
                                    checkedAt = now,
                                    simplePage = result.pageName,
                                    simpleNumber = result.linkedNumber,
                                    simpleError = result.error
                                )
                                traces.forEach { trace ->
                                    requestList(row.rowIdx).add(
                                        trace.toCheckReq(
                                            result.pageName?.let { "Page \"$it\"" }
                                                ?: result.error ?: "No page"
                                        )
                                    )
                                }
                                if (result.error == null && result.eligible) {
                                    updated[row.rowIdx] = updated[row.rowIdx].copy(status = "eligible")
                                }
                            } else {
                                val (result, traces) = SheetChecker.pageAdvanced(row.cookies)
                                val base = checks[row.rowIdx] ?: RowCheck(checkedAt = now)
                                checks[row.rowIdx] = base.copy(
                                    checkedAt = now,
                                    advEligible = result.eligible,
                                    advPage = result.pageName,
                                    advNumber = result.linkedNumber,
                                    advBan = result.banReason,
                                    advError = result.error
                                )
                                traces.forEach { trace ->
                                    requestList(row.rowIdx).add(
                                        trace.toCheckReq(
                                            when (trace.kind) {
                                                "graphql" -> if (result.eligible) "Eligible"
                                                else (result.error ?: result.banReason ?: "Not eligible")

                                                else -> trace.error ?: "Page shell"
                                            }
                                        )
                                    )
                                }
                                if (result.error == null && result.eligible) {
                                    updated[row.rowIdx] = updated[row.rowIdx].copy(status = "eligible")
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Page check failed for a Sheet row", e)
                        }
                    }
                    rows = updated
                }

                val details = if (checks.isNotEmpty() || reqs.isNotEmpty()) {
                    SheetCheckDetails(
                        checks = checks.toMap(),
                        reqs = reqs.mapValues { (_, list) -> list.toList() }
                    )
                } else {
                    null
                }
                if (rows != context.rows || details != null) {
                    val persisted = locked {
                        if (checkToken != context.token ||
                            openGeneration.get() != context.generation ||
                            openFile.value?.id != context.fileId
                        ) {
                            Log.i(TAG, "Skipped stale Sheet check for ${context.fileId}")
                            false
                        } else {
                            persistRowsLocked(
                                fileId = context.fileId,
                                previous = context.rows,
                                rows = rows,
                                checkDetails = details,
                                expectedSequence = context.sequence,
                                expectedGeneration = context.generation
                            )
                        }
                    }
                    if (!persisted) Log.i(TAG, "Sheet check result was not persisted for ${context.fileId}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Sheet check failed", e)
            } finally {
                locked {
                    if (checkToken == context.token) checking.value = false
                }
            }
            try {
                done(valid, dead)
            } catch (e: Exception) {
                Log.e(TAG, "Sheet check completion callback failed", e)
            }
        }
    }

    fun requestWithdraw(amount: Double, method: String, account: String): Boolean = locked {
        if (!amount.isFinite() || amount <= 0.0) return@locked false
        val trimmedAccount = account.trim()
        if (trimmedAccount.isEmpty()) return@locked false
        val balanceBefore = try {
            db.walletBalance()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read wallet balance", e)
            return@locked false
        }
        if (!balanceBefore.isFinite() || amount > balanceBefore) return@locked false
        val now = System.currentTimeMillis()
        val after = balanceBefore - amount
        val transaction = WalletTx(
            id = newFileId(),
            createdAt = now,
            type = "DEBIT",
            amount = amount,
            balanceAfter = after,
            title = "Withdrawal: $method",
            detail = maskAccount(trimmedAccount)
        )
        try {
            db.tx { d ->
                db.setWalletBalance(d, after)
                db.insertWalletTx(d, transaction)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist wallet withdrawal", e)
            return@locked false
        }
        refresh()
        true
    }

    companion object {
        @Volatile
        private var instance: SheetStore? = null

        fun get(context: Context): SheetStore {
            return instance ?: synchronized(this) {
                instance ?: SheetStore(context).also { instance = it }
            }
        }
    }

    private fun rowsToJson(rows: List<SheetRow>): String = encodeSheetRows(rows)

    private fun rowsFromJson(data: String): List<SheetRow> = decodeSheetRows(data)
}

fun maskAccount(account: String): String {
    val value = account.trim()
    if (value.isEmpty()) return "-"
    if (value.length > 12) return "${value.take(6)}...${value.takeLast(4)}"
    if (value.length > 4) return ".... ${value.takeLast(4)}"
    return value
}
