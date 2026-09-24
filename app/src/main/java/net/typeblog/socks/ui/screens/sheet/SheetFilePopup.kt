package net.typeblog.socks.ui.screens.sheet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import net.typeblog.socks.util.sheet.CheckReq
import net.typeblog.socks.util.sheet.FileDup
import net.typeblog.socks.util.sheet.RowCheck
import net.typeblog.socks.util.sheet.SheetPreset
import net.typeblog.socks.util.sheet.SheetRow

// File Inspector: the dot popup's compact card applied to the whole file.
// Opened from the file ... menu. It starts with the shared check strip and
// tabs, without the file-name header used by the row-level popup.
@Composable
fun FilePopup(
    preset: SheetPreset,
    rows: List<SheetRow>,
    checks: Map<Int, RowCheck>,
    reqs: Map<Int, List<CheckReq>>,
    fileDups: List<FileDup>,
    checking: Boolean,
    onDismiss: () -> Unit
) {
    var wide by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(0) }

    val totalRows = rows.size
    val alive = rows.count { it.status == "good" || it.status == "done" || it.status == "eligible" }
    val dead = rows.count { it.status == "bad" || it.dead }
    val pageRows = rows.count { it.status == "eligible" }
    val visibleFileDups = fileDups.filter {
        it.field.equals("cookie", ignoreCase = true) || it.field.equals("2fa", ignoreCase = true)
    }
    val hasDup = visibleFileDups.isNotEmpty()

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
    // Build a file-wide timeline from the recorded request metadata.
    val flatLines = remember(checks, flatReqs) {
        flatReqs.map { (ri, q) ->
            logLines(checks[ri], listOf(q)).firstOrNull()?.let { l ->
                l.copy(text = "R${ri + 1} ${l.text}")
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
                CheckStrip(
                    states = listOf(uidState, simState, advState, dupState),
                    onStripTap = { tab = 1 },
                    onDupTap = { tab = 2 },
                    wide = wide,
                    onToggleWide = { wide = !wide },
                    divider = false,
                    showExpand = true
                )
                PopupTabBar(
                    tabs = listOf(
                        "Details" to 0,
                        "Log" to 0,
                        "Dup" to visibleFileDups.size
                    ),
                    selected = tab,
                    onSelect = { tab = it }
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (wide) 420.dp else 260.dp)
                ) {
                    when (tab) {
                        0 -> FileDetailsPane(
                            preset = preset,
                            totalRows = totalRows,
                            alive = alive,
                            dead = dead,
                            dupRows = visibleFileDups.map { it.localRow }.distinct().size,
                            pageRows = pageRows,
                            checkedRows = checks.size
                        )
                        1 -> FileLogsPane(lines = flatLines)
                        else -> FileDupPane(dups = visibleFileDups)
                    }
                }
            }
        }
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
    checkedRows: Int
) {
    LazyColumn(modifier = Modifier.padding(vertical = 8.dp)) {
        item {
            InspectorDataLine(color = rowsIndicatorColor(), label = "total.rows", value = totalRows.toString())
            InspectorDataLine(color = AliveGreen, label = "alive", value = alive.toString())
            InspectorDataLine(color = DeadRed, label = "dead", value = dead.toString())
            InspectorDataLine(color = StatusYellow, label = "duplicates", value = dupRows.toString())
            if (preset == SheetPreset.PAGE) {
                InspectorDataLine(color = PageBlue, label = "page.eligible", value = pageRows.toString())
            }
            InspectorDataLine(
                color = MaterialTheme.colorScheme.primary,
                label = "checked.rows",
                value = checkedRows.toString()
            )
        }
    }
}

@Composable
private fun FileLogsPane(lines: List<LogLine>) {
    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            if (lines.isEmpty()) {
                EmptyPane("No activity for this file.")
            } else {
                LogTimeline(lines = lines)
            }
        }
    }
}

@Composable
private fun FileDupPane(dups: List<FileDup>) {
    if (dups.isEmpty()) {
        EmptyPane("No cookie or 2FA duplicates for this file.")
        return
    }
    LazyColumn(modifier = Modifier.padding(vertical = 4.dp)) {
        itemsIndexed(
            dups,
            key = { i, s -> "$i:${s.field}:${s.fileName}:${s.rowNo}:${s.localRow}" }
        ) { _, s ->
            val cell = when (s.field.lowercase()) {
                "cookie" -> "Cookie"
                "2fa" -> "2FA"
                else -> s.field
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                InspectorDataLine(
                    color = StatusYellow,
                    label = "Cell",
                    value = cell,
                    valueColor = StatusYellow
                )
                InspectorDataLine(
                    color = Color.Transparent,
                    label = "File",
                    value = s.fileName
                )
                InspectorDataLine(
                    color = Color.Transparent,
                    label = "This row",
                    value = s.localRow.toString()
                )
                InspectorDataLine(
                    color = Color.Transparent,
                    label = "Other row",
                    value = s.rowNo.toString()
                )
            }
        }
    }
}
