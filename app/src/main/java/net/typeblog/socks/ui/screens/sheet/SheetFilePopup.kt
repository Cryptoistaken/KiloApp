package net.typeblog.socks.ui.screens.sheet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import net.typeblog.socks.util.sheet.CheckReq
import net.typeblog.socks.util.sheet.FileDup
import net.typeblog.socks.util.sheet.RowCheck
import net.typeblog.socks.util.sheet.SheetPreset
import net.typeblog.socks.util.sheet.SheetRow

// File Inspector: the dot popup chrome applied to the whole file.
// Opened from the file ... menu. Header (dot + FILE / name / preset +
// verdict + name copy + expand), aggregate UID / SIM / ADV / DUP strip,
// Details / Log / Req / Dup tabs over file-wide data.
@Composable
fun FilePopup(
    fileName: String,
    preset: SheetPreset,
    rows: List<SheetRow>,
    checks: Map<Int, RowCheck>,
    reqs: Map<Int, List<CheckReq>>,
    fileDups: List<FileDup>,
    checking: Boolean,
    createdAt: Long,
    updatedAt: Long,
    onDismiss: () -> Unit
) {
    var wide by remember { mutableStateOf(true) }
    var tab by remember { mutableStateOf(0) }
    var jumpReq by remember { mutableStateOf<Int?>(null) }

    val totalRows = rows.size
    val alive = rows.count { it.status == "good" || it.status == "done" || it.status == "eligible" }
    val dead = rows.count { it.status == "bad" || it.dead }
    val pageRows = rows.count { it.status == "eligible" }
    val hasDup = fileDups.isNotEmpty()

    val verdict = when {
        checks.isEmpty() && !checking -> VerdictKind.IDLE
        checking && checks.isEmpty() -> VerdictKind.RUN
        dead > 0 -> VerdictKind.DEAD
        checking -> VerdictKind.RUN
        else -> VerdictKind.LIVE
    }
    val dotColor = when (verdict) {
        VerdictKind.DEAD, VerdictKind.CHALLENGE -> DeadRed
        VerdictKind.IDLE -> MaterialTheme.colorScheme.outlineVariant
        else -> AliveGreen
    }

    val uidVals = checks.values.mapNotNull { it.uidOk }
    val uidState = when {
        checking && checks.isEmpty() -> StripState.RUN
        uidVals.any { !it } -> StripState.BAD
        uidVals.any { it } -> StripState.OK
        checks.isNotEmpty() -> StripState.SKIP
        else -> StripState.MUTE
    }
    val simRan = checks.values.filter {
        it.simplePage != null || it.simpleNumber != null || it.simpleError != null
    }
    val simState = when {
        simRan.any { it.simpleError != null || it.simplePage == null } -> StripState.BAD
        simRan.isNotEmpty() -> StripState.OK
        checks.isNotEmpty() -> StripState.SKIP
        else -> StripState.MUTE
    }
    val advRan = checks.values.filter {
        it.advPage != null || it.advNumber != null || it.advBan != null || it.advError != null
    }
    val advState = when {
        advRan.any { !it.advEligible } -> StripState.BAD
        advRan.isNotEmpty() -> StripState.OK
        checking -> StripState.RUN
        checks.isNotEmpty() -> StripState.SKIP
        else -> StripState.MUTE
    }
    val dupState = if (hasDup) StripState.WARN else StripState.MUTE

    // Flat request list across rows, time-ordered, capped like the tab.
    val flatReqs = remember(reqs) {
        reqs.entries.sortedBy { it.key }.flatMap { (ri, list) ->
            list.map { ri to it }
        }.sortedBy { it.second.at }.take(200)
    }
    // Log lines over the flat list: reqIdx is the flat position so a tap
    // jumps to the right request (per-row indices would misfire).
    val flatLines = remember(checks, reqs, flatReqs) {
        flatReqs.mapIndexed { fi, (ri, q) ->
            logLines(checks[ri], listOf(q)).firstOrNull()?.let { l ->
                l.copy(text = "R${ri + 1} ${l.text}", reqIdx = fi)
            }
        }.filterNotNull().sortedBy { it.at }.take(200)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = if (androidx.compose.foundation.isSystemInDarkTheme()) 0.5f else 0.25f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = (if (wide) Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .fillMaxHeight(0.88f)
                else Modifier.width(300.dp))
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
            ) {
                PopupHeader(
                    dotColor = dotColor,
                    where = "FILE / ${fileName.uppercase()} / ${preset.name}",
                    showDup = hasDup,
                    verdict = verdict,
                    copyText = fileName,
                    wide = wide,
                    onToggleWide = { wide = !wide }
                )
                CheckStrip(
                    states = listOf(uidState, simState, advState, dupState),
                    onStripTap = { tab = 2 },
                    onDupTap = { tab = 3 },
                    wide = wide,
                    onToggleWide = { wide = !wide },
                    showExpand = false
                )
                PopupTabBar(
                    tabs = listOf(
                        "Details" to 0,
                        "Log" to 0,
                        "Req" to flatReqs.size,
                        "Dup" to fileDups.size
                    ),
                    selected = tab,
                    onSelect = { tab = it }
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .heightIn(max = if (wide) 520.dp else 320.dp)
                ) {
                    when (tab) {
                        0 -> FileDetailsPane(
                            preset = preset,
                            totalRows = totalRows,
                            alive = alive,
                            dead = dead,
                            dupRows = fileDups.map { it.localRow }.distinct().size,
                            pageRows = pageRows,
                            checkedRows = checks.size,
                            createdAt = createdAt,
                            updatedAt = updatedAt
                        )
                        1 -> FileLogsPane(lines = flatLines, onJump = { tab = 2; jumpReq = it })
                        2 -> FileRequestsPane(reqs = flatReqs, jumpReq = jumpReq, onJumped = { jumpReq = null })
                        else -> FileDupPane(dups = fileDups)
                    }
                }
            }
        }
    }
}

@Composable
private fun FileStatLine(color: Color, label: String, value: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value.toString(),
            fontSize = 11.sp,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun FileDetailsPane(
    preset: SheetPreset,
    totalRows: Int,
    alive: Int,
    dead: Int,
    dupRows: Int,
    pageRows: Int,
    checkedRows: Int,
    createdAt: Long,
    updatedAt: Long
) {
    LazyColumn(modifier = Modifier.padding(vertical = 8.dp)) {
        item {
            FileStatLine(color = rowsIndicatorColor(), label = "total.rows", value = totalRows)
            FileStatLine(color = AliveGreen, label = "alive", value = alive)
            FileStatLine(color = DeadRed, label = "dead", value = dead)
            FileStatLine(color = StatusYellow, label = "duplicates", value = dupRows)
            if (preset == SheetPreset.PAGE) {
                FileStatLine(color = PageBlue, label = "page.eligible", value = pageRows)
            }
            FileStatLine(
                color = MaterialTheme.colorScheme.primary,
                label = "checked.rows",
                value = checkedRows
            )
        }
        item {
            Spacer(modifier = Modifier.size(8.dp))
            DetailRow("Created", fmtDate(createdAt).ifEmpty { "-" })
            DetailRow("Updated", fmtDate(updatedAt).ifEmpty { "-" })
        }
    }
}

@Composable
private fun FileLogsPane(lines: List<LogLine>, onJump: (Int) -> Unit) {
    if (lines.isEmpty()) {
        EmptyPane("No activity for this file.")
        return
    }
    Column {
        LogSummary(lines)
        LogTimeline(lines = lines, onJump = onJump)
    }
}

@Composable
private fun FileRequestsPane(
    reqs: List<Pair<Int, CheckReq>>,
    jumpReq: Int?,
    onJumped: () -> Unit
) {
    if (reqs.isEmpty()) {
        EmptyPane("No requests for this file.")
        return
    }
    var openIdx by remember(reqs) { mutableStateOf<Int?>(null) }
    val listState = rememberLazyListState()
    LaunchedEffect(jumpReq) {
        if (jumpReq != null && jumpReq in reqs.indices) {
            openIdx = jumpReq
            try {
                listState.scrollToItem(jumpReq)
            } catch (e: Exception) {
                // List not laid out yet; the open highlight still applies.
            }
            onJumped()
        } else if (jumpReq != null) {
            onJumped()
        }
    }
    val totalMs = reqs.sumOf { it.second.durationMs }
    val nBad = reqs.count {
        it.second.error != null ||
            (it.second.status != 0 && it.second.status !in 200..299)
    }
    LazyColumn(state = listState, modifier = Modifier.padding(vertical = 4.dp)) {
        itemsIndexed(reqs, key = { i, _ -> i }) { i, (ri, q) ->
            RequestRow(
                q = q,
                open = openIdx == i,
                rowTag = "R${ri + 1}",
                onToggle = { openIdx = if (openIdx == i) null else i }
            )
        }
        item {
            val foot = buildString {
                append("${reqs.size} requests")
                val t = fmtDur(totalMs)
                if (t.isNotEmpty()) append(" · $t total")
                if (nBad > 0) append(" · $nBad failed")
            }
            Text(
                text = foot.uppercase(),
                fontSize = 10.sp,
                letterSpacing = 1.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
            )
        }
    }
}

@Composable
private fun FileDupPane(dups: List<FileDup>) {
    if (dups.isEmpty()) {
        EmptyPane("No duplicates for this file.")
        return
    }
    LazyColumn(modifier = Modifier.padding(vertical = 4.dp)) {
        itemsIndexed(dups, key = { i, _ -> i }) { _, s ->
            val at = fmtTime(s.at)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(StatusYellow)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = s.field.uppercase(),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.size(2.dp))
                    Text(
                        text = "row " + s.localRow + " · " + s.fileName + " · row " + s.rowNo +
                            if (at.isNotEmpty()) " · $at" else "",
                        fontSize = 10.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
