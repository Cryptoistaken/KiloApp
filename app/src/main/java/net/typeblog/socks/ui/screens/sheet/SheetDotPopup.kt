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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
// Details / Log / Req / Dup tabs, expandable request inspector, log
// entries that jump to their request. Request bodies and cookie values
// are never stored, so the inspector shows masked summaries only.

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
    isDup: Boolean,
    onDismiss: () -> Unit,
    fileName: String = "",
    presetLabel: String = "",
    rowNo: Int = row.rowIdx + 1,
    checking: Boolean = false,
    startWide: Boolean = false,
    onDock: (() -> Unit)? = null,
    tab: Int? = null,
    onTabChange: ((Int) -> Unit)? = null,
    jumpReq: Int? = null,
    onJumpReq: ((Int?) -> Unit)? = null
) {
    var wide by remember(startWide) { mutableStateOf(startWide) }
    var innerTab by remember { mutableStateOf(0) }
    var innerJump by remember { mutableStateOf<Int?>(null) }
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
            DotPopupCard(
                row = row,
                check = check,
                reqs = reqs,
                dupSources = dupSources,
                isDup = isDup,
                fileName = fileName,
                presetLabel = presetLabel,
                rowNo = rowNo,
                checking = checking,
                wide = wide,
                onToggleWide = { if (wide && onDock != null) onDock() else wide = !wide },
                showHeader = true,
                tab = tab ?: innerTab,
                onTabChange = onTabChange ?: { innerTab = it },
                jumpReq = jumpReq ?: innerJump,
                onJumpReq = onJumpReq ?: { innerJump = it }
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
    isDup: Boolean,
    fileName: String = "",
    presetLabel: String = "",
    rowNo: Int = row.rowIdx + 1,
    checking: Boolean = false,
    wide: Boolean = false,
    onToggleWide: () -> Unit = {},
    showHeader: Boolean = true,
    tab: Int = 0,
    onTabChange: (Int) -> Unit = {},
    jumpReq: Int? = null,
    onJumpReq: (Int?) -> Unit = {}
) {
    val hasDup = dupSources.isNotEmpty() || isDup
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
            onStripTap = { onTabChange(2) },
            onDupTap = { onTabChange(3) },
            wide = wide,
            onToggleWide = onToggleWide,
            divider = showHeader,
            showExpand = !showHeader
        )
        PopupTabBar(
            tabs = listOf(
                "Details" to 0,
                "Log" to 0,
                "Req" to reqs.size,
                "Dup" to dupSources.size
            ),
            selected = tab,
            onSelect = onTabChange
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = if (wide) 520.dp else 320.dp)
        ) {
            when (tab) {
                0 -> DetailsPane(check)
                1 -> LogsPane(
                    check = check,
                    reqs = reqs,
                    onJump = { idx ->
                        onJumpReq(idx)
                        onTabChange(2)
                    }
                )
                2 -> RequestsPane(reqs = reqs, jumpReq = jumpReq, onJumped = { onJumpReq(null) })
                else -> DuplicatesPane(
                    dupSources = dupSources,
                    fallbackAt = check?.checkedAt ?: 0
                )
            }
        }
    }
}

internal fun verdictFor(row: SheetRow, check: RowCheck?, checking: Boolean): VerdictKind {
    if (check == null && !checking) return VerdictKind.IDLE
    if (row.dead || row.status == "bad") {
        val err = (check?.advError ?: "") + " " + (check?.simpleError ?: "")
        return if (err.contains("2FA", ignoreCase = true) || err.contains("challenge", ignoreCase = true)) VerdictKind.CHALLENGE else VerdictKind.DEAD
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
            modifier = Modifier.size(16.dp),
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
                .size(7.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .border(
                    1.5.dp,
                    MaterialTheme.colorScheme.outlineVariant,
                    androidx.compose.foundation.shape.CircleShape
                )
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

internal fun fmtDur(ms: Long): String {
    if (ms <= 0) return ""
    return if (ms < 1000) "${ms}ms" else "%.1fs".format(ms / 1000.0)
}

internal data class LogLine(val text: String, val cls: LogCls, val at: Long, val reqIdx: Int)

internal fun logLines(check: RowCheck?, reqs: List<CheckReq>): List<LogLine> {
    val out = mutableListOf<LogLine>()
    for ((i, t) in reqs.withIndex()) {
        if (t.status == 0 && t.error == null) {
            val label = when (t.kind) {
                "uid" -> "UID check running."
                "simple" -> "Simple check running."
                "advanced" -> "Page check running."
                else -> "Advanced check running."
            }
            out.add(LogLine(label, LogCls.RUN, t.at, i))
            continue
        }
        when (t.kind) {
            "uid" -> {
                val ok = t.resNote == "valid"
                out.add(LogLine(if (ok) "UID check passed." else "UID check failed.", if (ok) LogCls.OK else LogCls.BAD, t.at, i))
            }
            "simple" -> {
                val ok = check?.simpleError == null && check?.simplePage != null
                out.add(LogLine(if (ok) "Simple check passed." else "Simple check failed.", if (ok) LogCls.OK else LogCls.BAD, t.at, i))
            }
            "advanced" -> {
                val ok = t.error == null
                out.add(LogLine(if (ok) "Page check passed." else "Page check failed.", if (ok) LogCls.OK else LogCls.BAD, t.at, i))
            }
            "graphql" -> {
                val ok = check?.advEligible == true
                out.add(LogLine(if (ok) "Advanced check passed." else "Advanced check failed.", if (ok) LogCls.OK else LogCls.BAD, t.at, i))
            }
        }
    }
    return out.sortedBy { it.at }
}

@Composable
internal fun LogSummary(lines: List<LogLine>) {
    val nOk = lines.count { it.cls == LogCls.OK }
    val nBad = lines.count { it.cls == LogCls.BAD }
    val nRun = lines.count { it.cls == LogCls.RUN }
    val bits = mutableListOf("$nOk passed")
    if (nBad > 0) bits.add("$nBad failed")
    if (nRun > 0) bits.add("$nRun running")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = (nOk + nBad + nRun).toString(),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "events · " + bits.joinToString(" · "),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.size(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp)),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            if (nOk > 0) Box(modifier = Modifier.weight(nOk.toFloat()).background(AliveGreen).height(4.dp))
            if (nBad > 0) Box(modifier = Modifier.weight(nBad.toFloat()).background(DeadRed).height(4.dp))
            if (nRun > 0) Box(modifier = Modifier.weight(nRun.toFloat()).background(MaterialTheme.colorScheme.primary).height(4.dp))
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
internal fun LogTimeline(lines: List<LogLine>, onJump: (Int) -> Unit) {
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
                    .clickable { onJump(l.reqIdx) }
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
internal fun LogsPane(check: RowCheck?, reqs: List<CheckReq>, onJump: (Int) -> Unit, prefix: (Int) -> String? = { null }) {
    val base = remember(check, reqs) { logLines(check, reqs) }
    if (base.isEmpty()) {
        EmptyPane("No activity for this row.")
        return
    }
    val lines = base.map { l ->
        val p = prefix(l.reqIdx)
        if (p == null) l else l.copy(text = "$p ${l.text}")
    }
    Column {
        LogSummary(lines)
        LogTimeline(lines = lines, onJump = onJump)
    }
}

@Composable
internal fun RequestsPane(
    reqs: List<CheckReq>,
    jumpReq: Int?,
    onJumped: () -> Unit,
    rowLabel: (Int) -> String? = { null }
) {
    if (reqs.isEmpty()) {
        EmptyPane("No requests for this row.")
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
    val totalMs = reqs.sumOf { it.durationMs }
    val nBad = reqs.count { it.error != null || (it.status != 0 && it.status !in 200..299) }
    val nRun = reqs.count { it.status == 0 && it.error == null }
    LazyColumn(state = listState, modifier = Modifier.padding(vertical = 4.dp)) {
        itemsIndexed(reqs, key = { i, _ -> i }) { i, q ->
            RequestRow(
                q = q,
                open = openIdx == i,
                rowTag = rowLabel(i),
                onToggle = { openIdx = if (openIdx == i) null else i }
            )
        }
        item {
            val foot = buildString {
                append("${reqs.size} requests")
                val t = fmtDur(totalMs)
                if (t.isNotEmpty()) append(" · $t total")
                if (nBad > 0) append(" · $nBad failed")
                if (nRun > 0) append(" · $nRun running")
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
internal fun RequestRow(q: CheckReq, open: Boolean, rowTag: String?, onToggle: () -> Unit) {
    // 0 Request, 1 Response, 2 Headers, 3 Timing (mock sub-tab order).
    var sub by remember(q) { mutableStateOf(0) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (open) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                else Color.Transparent
            )
            .clickable { onToggle() }
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "›",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .width(12.dp)
                    .alpha(if (open) 1f else 0.7f)
            )
            if (rowTag != null) {
                Text(
                    text = rowTag,
                    fontSize = 10.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(30.dp)
                )
            }
            val pending = q.status == 0 && q.error == null
            Text(
                text = when {
                    q.error != null -> "ERR"
                    pending -> "…"
                    else -> q.status.toString()
                },
                fontSize = 11.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                color = when {
                    pending -> MaterialTheme.colorScheme.onSurfaceVariant
                    q.error != null -> DeadRed
                    q.status in 200..299 -> AliveGreen
                    else -> DeadRed
                },
                modifier = Modifier.width(36.dp)
            )
            Text(
                text = q.method,
                fontSize = 11.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(44.dp)
            )
            Text(
                text = q.url,
                fontSize = 11.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = fmtDur(q.durationMs),
                fontSize = 10.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (open) {
            Spacer(modifier = Modifier.size(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                for ((si, label) in listOf("Request", "Response", "Headers", "Timing").withIndex()) {
                    val on = sub == si
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = label.uppercase(),
                            fontSize = 10.sp,
                            letterSpacing = 1.sp,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (on) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable { sub = si }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                        Box(
                            modifier = Modifier
                                .width(32.dp)
                                .height(2.dp)
                                .background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.size(2.dp))
            when (sub) {
                0 -> {
                    ReqSecCopy(label = "Request", text = "${q.method} ${q.url}")
                    DetailRow("Method", q.method)
                    DetailRow("URL", q.url)
                    q.reqNote?.let { DetailRow("Cookie", it) }
                }
                1 -> {
                    if (q.error != null && q.resNote == null) {
                        ReqSecCopy(label = "Error", text = null)
                        Text(
                            text = q.error,
                            fontSize = 11.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            color = DeadRed,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                        )
                    } else {
                        ResponseBody(q = q)
                    }
                }
                2 -> {
                    ReqSecCopy(label = "Headers", text = null)
                    DetailRow("Request URL", "https://" + q.url)
                    DetailRow("Request Method", q.method)
                    DetailRow(
                        "Status Code",
                        if (q.error != null) "error" else q.status.toString(),
                        if (q.error != null || (q.status != 0 && q.status !in 200..299)) DeadRed else AliveGreen
                    )
                    DetailRow("Cookie", q.reqNote ?: "-")
                }
                else -> {
                    ReqSecCopy(label = "Timing", text = null)
                    DetailRow("Total", fmtDur(q.durationMs).ifEmpty { "-" })
                    val at = fmtTime(q.at)
                    if (at.isNotEmpty()) DetailRow("At", at)
                }
            }
        }
    }
}

@Composable
internal fun ReqSecCopy(label: String, text: String?) {
    val clipboard = LocalClipboardManager.current
    var morphed by remember(text) { mutableStateOf(false) }
    LaunchedEffect(morphed) {
        if (morphed) {
            kotlinx.coroutines.delay(1200)
            morphed = false
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label.uppercase(),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        if (text != null) {
            Text(
                text = if (morphed) "COPIED" else "COPY",
                fontSize = 10.sp,
                letterSpacing = 1.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(2.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        clipboard.setText(AnnotatedString(text))
                        morphed = true
                    }
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }
    }
}

@Composable
internal fun ResponseBody(q: CheckReq) {
    // Filtered / Raw mini-tabs, like the mock. Bodies are never stored,
    // so both views render the recorded masked summary.
    var mini by remember(q) { mutableStateOf(0) }
    ReqSecCopy(label = "Response", text = q.resNote)
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for ((mi, label) in listOf("Filtered", "Raw").withIndex()) {
            val on = mini == mi
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = label.uppercase(),
                    fontSize = 10.sp,
                    letterSpacing = 1.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (on) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { mini = mi }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
                Box(
                    modifier = Modifier
                        .width(32.dp)
                        .height(2.dp)
                        .background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent)
                )
            }
        }
    }
    if (q.resNote != null) {
        if (mini == 0) {
            DetailRow("Result", q.resNote)
        } else {
            Text(
                text = q.resNote,
                fontSize = 11.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
    } else {
        EmptyPane("Empty body.")
    }
    q.error?.let {
        Text(
            text = it,
            fontSize = 11.sp,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            color = DeadRed,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        )
    }
}

@Composable
internal fun DuplicatesPane(dupSources: List<DupSource>, fallbackAt: Long) {
    if (dupSources.isEmpty()) {
        EmptyPane("No duplicates for this row.")
        return
    }
    LazyColumn(modifier = Modifier.padding(vertical = 4.dp)) {
        itemsIndexed(dupSources, key = { i, _ -> i }) { _, s ->
            val at = fmtTime(if (s.at > 0) s.at else fallbackAt)
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
                        text = s.fileName + " · row " + s.rowNo +
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
