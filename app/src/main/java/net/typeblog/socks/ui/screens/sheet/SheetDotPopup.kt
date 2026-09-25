package net.typeblog.socks.ui.screens.sheet

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import net.typeblog.socks.R
import net.typeblog.socks.util.sheet.CheckReq
import net.typeblog.socks.util.sheet.DupSource
import net.typeblog.socks.util.sheet.RowCheck
import net.typeblog.socks.util.sheet.SheetRow

// Dot popup: port of the HTML mock (sheet-dot-popup-model.html).
// Header (dot + where + DUP/verdict stamps + UID copy + expand),
// check strip (UID / SIM / ADV / DUP with ok/bad/warn/mute/skip/run),
// Details / Log / Dup tabs, with log entries derived from the recorded
// check metadata. Request inspection is intentionally not shown.

// ── shared strip/log states (mock .chk classes) ──
internal enum class StripState { OK, BAD, WARN, MUTE, SKIP, RUN }

internal enum class VerdictKind { LIVE, DEAD, CHALLENGE, RUN, IDLE, WARN }

internal enum class LogCls { OK, BAD, RUN }

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
internal fun ExpandBtn(wide: Boolean, onToggle: () -> Unit) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(RoundedCornerShape(4.dp))
            .clickable { onToggle() }
            .padding(3.dp),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(if (wide) R.drawable.ic_ss_collapse else R.drawable.ic_ss_expand),
            contentDescription = if (wide) "Dock" else "Expand",
            modifier = Modifier.size(12.dp),
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurfaceVariant)
        )
    }
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
internal fun CheckStrip(
    states: List<StripState>,
    labels: List<String> = listOf("UID", "SIM", "ADV", "DUP"),
    onStripTap: () -> Unit,
    onDupTap: () -> Unit,
    wide: Boolean,
    onToggleWide: () -> Unit,
    divider: Boolean = true,
    showExpand: Boolean = true
) {
    // Mock: four equal cells, connectors run dot-center to dot-center,
    // expand floats top-left over the strip (small popup placement).
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            states.forEachIndexed { i, st ->
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.TopCenter
                ) {
                    if (i > 0) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .offset(y = 9.dp)
                                .fillMaxWidth(0.5f)
                                .padding(end = 9.dp)
                                .height(2.dp)
                                .clip(RoundedCornerShape(1.dp))
                                .background(stripLineColor(states[i - 1]))
                        )
                    }
                    if (i < states.size - 1) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(y = 9.dp)
                                .fillMaxWidth(0.5f)
                                .padding(start = 9.dp)
                                .height(2.dp)
                                .clip(RoundedCornerShape(1.dp))
                                .background(stripLineColor(st))
                        )
                    }
                    Column(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { if (i == 3) onDupTap() else onStripTap() }
                            .semantics {
                                role = Role.Button
                                contentDescription = labels.getOrNull(i) ?: "Status"
                                stateDescription = when (st) {
                                    StripState.OK -> "Passed"
                                    StripState.BAD -> "Failed"
                                    StripState.WARN -> "Duplicate"
                                    StripState.RUN -> "Running"
                                    StripState.SKIP -> "Skipped"
                                    StripState.MUTE -> "No data"
                                }
                            }
                            .padding(vertical = 2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        StripDot(state = st)
                        Spacer(modifier = Modifier.size(5.dp))
                        Text(
                            text = labels[i],
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        if (showExpand) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = (-10).dp, y = (-6).dp)
            ) {
                ExpandBtn(wide = wide, onToggle = onToggleWide)
            }
        }
    }
    // Mock small popup has no divider under the strip.
    if (divider) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant)
        )
    }
}

@Composable
private fun stripLineColor(st: StripState): Color {
    return when (st) {
        StripState.OK -> AliveGreen.copy(alpha = 0.4f)
        StripState.BAD -> DeadRed.copy(alpha = 0.4f)
        StripState.WARN -> StatusYellow.copy(alpha = 0.4f)
        // Mock has no run-colored connector: default line.
        else -> MaterialTheme.colorScheme.outlineVariant
    }
}

@Composable
internal fun StripDot(state: StripState) {
    when (state) {
        StripState.SKIP -> Box(
            modifier = Modifier
                .size(10.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(MaterialTheme.colorScheme.outlineVariant)
        )

        StripState.MUTE -> Box(
            modifier = Modifier
                .size(10.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(MaterialTheme.colorScheme.outlineVariant)
        )

        StripState.RUN -> {
            val t = rememberInfiniteTransition(label = "run")
            val a by t.animateFloat(
                initialValue = 1f, targetValue = 0.35f,
                animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse),
                label = "pulse"
            )
            val c = MaterialTheme.colorScheme.primary
            Box(
                modifier = Modifier.size(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(c.copy(alpha = 0.18f))
                )
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .alpha(a)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(c)
                )
            }
        }

        else -> {
            val c = when (state) {
                StripState.OK -> AliveGreen
                StripState.BAD -> DeadRed
                else -> StatusYellow
            }
            Box(
                modifier = Modifier.size(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(c.copy(alpha = 0.18f))
                )
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(c)
                )
            }
        }
    }
}

@Composable
internal fun PopupTabBar(tabs: List<Pair<String, Int>>, selected: Int, onSelect: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth()) {
            tabs.forEachIndexed { i, (label, count) ->
                val on = i == selected
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onSelect(i) }
                        .padding(top = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = label.uppercase(),
                            fontSize = 10.sp,
                            letterSpacing = 1.sp,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (on) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (count > 0) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .padding(horizontal = 6.dp, vertical = 1.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = count.toString(),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.size(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .background(
                                if (on) MaterialTheme.colorScheme.primary else Color.Transparent
                            )
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant)
        )
    }
}

@Composable
internal fun DetailRow(label: String, value: String, valueColor: Color? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(110.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = value,
            fontSize = 11.sp,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            fontWeight = if (valueColor != null) FontWeight.SemiBold else FontWeight.Normal,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

// Mock resCls: values color the dot + value, like .resrow.
internal fun resColor(v: String): Color? {
    if (v == "null") return null
    return when {
        Regex("^(valid|true|eligible|yes|live)$", RegexOption.IGNORE_CASE).matches(v) -> AliveGreen
        Regex("^(false|no|blocked|bad|dead|fail)$", RegexOption.IGNORE_CASE).matches(v) -> DeadRed
        Regex("^(unchecked|-)$", RegexOption.IGNORE_CASE).matches(v) -> null
        else -> null
    }
}

@Composable
internal fun ResRow(label: String, value: String) {
    val c = resColor(value)
    val mute = value == "null" || Regex("^(unchecked|-)$", RegexOption.IGNORE_CASE).matches(value)
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
                .background(c ?: MaterialTheme.colorScheme.outlineVariant)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            fontSize = 11.sp,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            fontWeight = if (c != null) FontWeight.SemiBold else FontWeight.Normal,
            color = c ?: if (mute) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
internal fun DetailsPane(check: RowCheck?) {
    if (check == null || !check.hasData) {
        EmptyPane("No details for this row.")
        return
    }
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        check.uidOk?.let { ResRow("uid.status", if (it) "valid" else "dead") }
        check.simplePage?.let { ResRow("simple.page", it) }
        check.simpleNumber?.let { ResRow("simple.number", it) }
        if (check.simpleError != null && check.simplePage == null) {
            ResRow("simple.error", check.simpleError)
        }
        // advEligible defaults false, so only show it once advanced really
        // ran (any adv field present) or it is genuinely true.
        val advRan = check.advPage != null || check.advNumber != null ||
                (check.advBan != null && check.advBan != "null") || check.advError != null
        if (check.advEligible || advRan) {
            ResRow("advanced.eligible", check.advEligible.toString())
        }
        check.advPage?.let { ResRow("advanced.page", it) }
        check.advNumber?.let { ResRow("advanced.number", it) }
        if (check.advBan != null && check.advBan != "null") ResRow("advanced.ban", check.advBan)
        if (check.advError != null) ResRow("advanced.error", check.advError)
    }
}

internal fun fmtTime(at: Long): String {
    if (at <= 0) return ""
    return java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(at))
}

internal data class LogLine(val text: String, val cls: LogCls, val at: Long)

internal fun logLines(check: RowCheck?, reqs: List<CheckReq>): List<LogLine> {
    val out = mutableListOf<LogLine>()
    for (t in reqs) {
        if (t.status == 0 && t.error == null) {
            val label = when (t.kind) {
                "uid" -> "UID running."
                "simple" -> "Simple running."
                "advanced" -> "Page running."
                else -> "Advanced running."
            }
            out.add(LogLine(label, LogCls.RUN, t.at))
            continue
        }
        // A successful trace means the checker request completed. Account or
        // page eligibility is a separate result and is shown in the message.
        when (t.kind) {
            "uid" -> {
                val requestOk = t.error == null && t.status in 200..299
                val alive = t.resNote.equals("valid", ignoreCase = true)
                val text = when {
                    !requestOk -> "UID failed."
                    alive -> "UID alive."
                    t.resNote.isNullOrBlank() -> "UID unknown."
                    else -> "UID dead."
                }
                out.add(LogLine(text, if (requestOk && alive) LogCls.OK else LogCls.BAD, t.at))
            }

            "simple" -> {
                val requestOk = t.error == null && t.status in 200..299
                val text = when {
                    !requestOk -> "Simple failed."
                    check?.simplePage?.isNotBlank() == true -> "Simple page found."
                    else -> "Simple no page."
                }
                out.add(LogLine(text, if (requestOk) LogCls.OK else LogCls.BAD, t.at))
            }

            "advanced" -> {
                val requestOk = t.error == null && t.status in 200..299
                val advError = check?.advError?.takeIf { it.isNotBlank() }
                val text = when {
                    !requestOk -> "Page failed."
                    advError == null -> "Page loaded."
                    advError.contains("not eligible", ignoreCase = true) ||
                            advError.contains("permission", ignoreCase = true) -> "Page not eligible."

                    else -> "Page error."
                }
                out.add(LogLine(text, if (requestOk) LogCls.OK else LogCls.BAD, t.at))
            }

            "graphql" -> {
                val requestOk = t.error == null && t.status in 200..299
                val eligible = check?.advEligible == true || t.resNote.equals("Eligible", ignoreCase = true)
                val text = when {
                    !requestOk -> "Advanced failed."
                    eligible -> "Page eligible."
                    else -> "Page not eligible."
                }
                out.add(LogLine(text, if (requestOk) LogCls.OK else LogCls.BAD, t.at))
            }
        }
    }
    return out.sortedBy { it.at }
}

@Composable
internal fun LogTimeline(lines: List<LogLine>) {
    LazyColumn(modifier = Modifier.padding(vertical = 4.dp)) {
        itemsIndexed(lines, key = { i, _ -> i }) { idx, l ->
            val dot = when (l.cls) {
                LogCls.OK -> AliveGreen
                LogCls.BAD -> DeadRed
                LogCls.RUN -> MaterialTheme.colorScheme.primary
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 0.dp),
                verticalAlignment = Alignment.Top
            ) {
                // Rail: vertical line through the dots, like .logs-list::before.
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.width(9.dp).padding(top = 5.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(if (idx == 0) 6.dp else 8.dp)
                            .background(
                                if (idx == 0) Color.Transparent
                                else MaterialTheme.colorScheme.outlineVariant
                            )
                    )
                    val runAlpha: Float = if (l.cls == LogCls.RUN) {
                        val t = rememberInfiniteTransition(label = "logrun")
                        val a by t.animateFloat(
                            initialValue = 1f, targetValue = 0.35f,
                            animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse),
                            label = "pulse"
                        )
                        a
                    } else 1f
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .alpha(runAlpha)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(dot)
                    )
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(if (idx == lines.size - 1) 6.dp else 12.dp)
                            .background(
                                if (idx == lines.size - 1) Color.Transparent
                                else MaterialTheme.colorScheme.outlineVariant
                            )
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = l.text,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 5.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = fmtTime(l.at),
                    fontSize = 10.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 5.dp)
                )
            }
        }
    }
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

@Composable
internal fun InspectorDataLine(
    color: Color,
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
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
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(110.dp)
        )
        Text(
            text = value,
            fontSize = 11.sp,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
internal fun DuplicatesPane(dupSources: List<DupSource>, localRow: Int) {
    if (dupSources.isEmpty()) {
        EmptyPane("No cookie or 2FA duplicates for this row.")
        return
    }
    LazyColumn(modifier = Modifier.padding(vertical = 4.dp)) {
        itemsIndexed(
            dupSources,
            key = { _, s -> "${s.fileName}:${s.rowNo}:${s.field}:${s.at}" }
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
                    value = localRow.toString()
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

@Composable
internal fun EmptyPane(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(28.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
