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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import net.typeblog.socks.util.sheet.CheckReq
import net.typeblog.socks.util.sheet.DupSource
import net.typeblog.socks.util.sheet.RowCheck
import net.typeblog.socks.util.sheet.SheetRow

// Dot popup: port of the HTML mock (sheet-dot-popup-model.html).
// Header (dot + where + DUP/verdict stamps + UID copy + expand),
// check strip (UID / SIM / ADV / DUP with ok/bad/warn/mute/skip/run),
// Details / Log / Dup tabs, with log entries derived from the recorded
// check metadata. Request inspection is intentionally not shown.

@Composable
internal fun DotPopup(
    row: SheetRow,
    check: RowCheck?,
    reqs: List<CheckReq>,
    dupSources: List<DupSource>,
    onDismiss: () -> Unit,
    fileName: String = "",
    presetLabel: String = "",
    rowNo: Int = row.rowIdx + 1,
    checking: Boolean = false,
    startWide: Boolean = false,
    onDock: (() -> Unit)? = null,
    tab: Int? = null,
    onTabChange: ((Int) -> Unit)? = null
) {
    var wide by remember(startWide) { mutableStateOf(startWide) }
    var innerTab by remember { mutableStateOf(0) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = if (androidx.compose.foundation.isSystemInDarkTheme()) 0.5f else 0.25f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                ),
            contentAlignment = Alignment.Center
        ) {
            DotPopupCard(
                row = row,
                check = check,
                reqs = reqs,
                dupSources = dupSources,
                fileName = fileName,
                presetLabel = presetLabel,
                rowNo = rowNo,
                checking = checking,
                wide = wide,
                onToggleWide = { if (wide && onDock != null) onDock() else wide = !wide },
                showHeader = true,
                tab = tab ?: innerTab,
                onTabChange = onTabChange ?: { innerTab = it }
            )
        }
    }
}

// The card alone: used anchored under the dot (narrow, header-less like
// the mock small popup) and inside the wide dialog above.
@Composable
internal fun DotPopupCard(
    row: SheetRow,
    check: RowCheck?,
    reqs: List<CheckReq>,
    dupSources: List<DupSource>,
    fileName: String = "",
    presetLabel: String = "",
    rowNo: Int = row.rowIdx + 1,
    checking: Boolean = false,
    wide: Boolean = false,
    onToggleWide: () -> Unit = {},
    showHeader: Boolean = true,
    tab: Int = 0,
    onTabChange: (Int) -> Unit = {}
) {
    val visibleDupSources = dupSources.filter {
        it.field.equals("cookie", ignoreCase = true) || it.field.equals("2fa", ignoreCase = true)
    }
    val hasDup = visibleDupSources.isNotEmpty()
    val verdict = verdictFor(row, check, checking)
    val dotColor = when (verdict) {
        VerdictKind.DEAD, VerdictKind.CHALLENGE -> DeadRed
        VerdictKind.IDLE -> MaterialTheme.colorScheme.outlineVariant
        else -> AliveGreen
    }

    val uidState = when {
        checking && check == null -> StripState.RUN
        check?.uidOk == true -> StripState.OK
        check?.uidOk == false -> StripState.BAD
        check == null -> StripState.MUTE
        else -> StripState.SKIP
    }
    val simpleRan = check != null &&
            (check.simplePage != null || check.simpleNumber != null || check.simpleError != null)
    val simpleOk = simpleRan && check?.simpleError == null && check?.simplePage != null
    val advRan = check != null &&
            (check.advPage != null || check.advNumber != null || check.advBan != null || check.advError != null)
    val advOk = advRan && (check?.advEligible == true)
    val simState = when {
        simpleRan && simpleOk -> StripState.OK
        simpleRan -> StripState.BAD
        check == null -> StripState.MUTE
        else -> StripState.SKIP
    }
    val advState = when {
        advRan && advOk -> StripState.OK
        advRan -> StripState.BAD
        checking -> StripState.RUN
        check == null -> StripState.MUTE
        else -> StripState.SKIP
    }
    val dupState = if (hasDup) StripState.WARN else StripState.MUTE

    Column(
        modifier = (if (wide) Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .fillMaxHeight(0.88f)
        // Mock small popup is 248px; the header-less anchored card matches.
        else if (!showHeader) Modifier.width(248.dp)
        else Modifier.width(300.dp))
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
    ) {
        if (showHeader) {
            PopupHeader(
                dotColor = dotColor,
                where = "ROW-$rowNo / ${fileName.uppercase()} / ${presetLabel.uppercase()}",
                showDup = hasDup,
                verdict = verdict,
                copyText = row.uid.ifEmpty { null },
                wide = wide,
                onToggleWide = onToggleWide
            )
        }
        // Check strip: expand + UID / SIM / ADV / DUP.
        CheckStrip(
            states = listOf(uidState, simState, advState, dupState),
            onStripTap = { onTabChange(1) },
            onDupTap = { onTabChange(2) },
            wide = wide,
            onToggleWide = onToggleWide,
            divider = showHeader,
            showExpand = !showHeader
        )
        PopupTabBar(
            tabs = listOf(
                "Details" to 0,
                "Log" to 0,
                "Dup" to visibleDupSources.size
            ),
            selected = tab,
            onSelect = onTabChange
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (wide) 340.dp else 220.dp)
        ) {
            when (tab) {
                0 -> DetailsPane(check)
                1 -> LogsPane(check = check, reqs = reqs)
                else -> DuplicatesPane(
                    dupSources = visibleDupSources,
                    localRow = rowNo
                )
            }
        }
    }
}

internal fun verdictFor(row: SheetRow, check: RowCheck?, checking: Boolean): VerdictKind {
    if (check == null && !checking) return VerdictKind.IDLE
    if (check?.uidOk == false) {
        val err = (check.advError ?: "") + " " + (check.simpleError ?: "")
        return if (err.contains("2FA", ignoreCase = true) || err.contains(
                "challenge",
                ignoreCase = true
            )
        ) VerdictKind.CHALLENGE else VerdictKind.DEAD
    }
    if (row.dead || row.status == "bad") {
        val err = (check?.advError ?: "") + " " + (check?.simpleError ?: "")
        return if (err.contains("2FA", ignoreCase = true) || err.contains(
                "challenge",
                ignoreCase = true
            )
        ) VerdictKind.CHALLENGE else VerdictKind.DEAD
    }
    if (row.status == "good" || row.status == "done" || row.status == "eligible") {
        return if (checking) VerdictKind.RUN else VerdictKind.LIVE
    }
    if (checking || row.status == "pending") return VerdictKind.RUN
    return if (check != null) VerdictKind.LIVE else VerdictKind.IDLE
}

@Composable
internal fun VerdictStamp(kind: VerdictKind) {
    val label = when (kind) {
        VerdictKind.LIVE -> "[ LIVE ]"
        VerdictKind.DEAD -> "[ DEAD ]"
        VerdictKind.CHALLENGE -> "[ CHALLENGE ]"
        VerdictKind.RUN -> "[ RUNNING ]"
        VerdictKind.IDLE -> "[ NO DATA ]"
        VerdictKind.WARN -> "[ DUP ]"
    }
    val fg = when (kind) {
        VerdictKind.LIVE -> AliveGreen
        VerdictKind.DEAD, VerdictKind.CHALLENGE -> DeadRed
        VerdictKind.RUN -> MaterialTheme.colorScheme.primary
        VerdictKind.WARN -> StatusYellow
        VerdictKind.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = label,
        fontSize = 11.sp,
        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
        color = fg,
        modifier = Modifier
            .clip(RoundedCornerShape(2.dp))
            .background(fg.copy(alpha = 0.12f))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        maxLines = 1
    )
}

@Composable
internal fun PopupHeader(
    dotColor: Color,
    where: String,
    showDup: Boolean,
    verdict: VerdictKind,
    copyText: String?,
    wide: Boolean,
    onToggleWide: () -> Unit
) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(dotColor)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = where,
                fontSize = 10.sp,
                letterSpacing = 1.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (showDup) {
                Spacer(modifier = Modifier.width(6.dp))
                VerdictStamp(VerdictKind.WARN)
            }
            Spacer(modifier = Modifier.width(6.dp))
            VerdictStamp(verdict)
            Spacer(modifier = Modifier.width(4.dp))
            ExpandBtn(wide = wide, onToggle = onToggleWide)
        }
        if (!copyText.isNullOrEmpty()) {
            Spacer(modifier = Modifier.size(6.dp))
            Text(
                text = copyText,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    clipboard.setText(AnnotatedString(copyText))
                    toast(ctx, "Copied.")
                }
            )
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}


@Composable
internal fun LogsPane(check: RowCheck?, reqs: List<CheckReq>) {
    val lines = remember(check, reqs) { logLines(check, reqs) }
    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            if (lines.isEmpty()) {
                EmptyPane("No activity for this row.")
            } else {
                LogTimeline(lines = lines)
            }
        }
    }
}
