package net.typeblog.socks.ui.screens.sheet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
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

// Dot popup: the HTML mock (sheet-dot-popup-model.html) ported to Compose.
// Header-less card starting at the check strip, like the mock's small
// popup: Details / Logs / Requests / Duplicates tabs, expandable request
// rows, log entries that jump to their request. Everything shown comes
// from recorded check data — rows checked before recording landed show
// the verdict alone.
@Composable
fun DotPopup(
    row: SheetRow,
    check: RowCheck?,
    reqs: List<CheckReq>,
    dupSources: List<DupSource>,
    isDup: Boolean,
    onDismiss: () -> Unit
) {
    var wide by remember { mutableStateOf(false) }
    // 0 Details, 1 Logs, 2 Requests, 3 Duplicates (mock order).
    var tab by remember { mutableStateOf(0) }
    var jumpReq by remember { mutableStateOf<Int?>(null) }

    val uidState = check?.uidOk
    val simpleRan = check != null &&
        (check.simplePage != null || check.simpleNumber != null || check.simpleError != null)
    val simpleOk = simpleRan && check?.simpleError == null && check?.simplePage != null
    val advRan = check != null &&
        (check.advPage != null || check.advNumber != null || check.advBan != null || check.advError != null)
    val advOk = advRan && (check?.advEligible == true)

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
                    .heightIn(max = 600.dp)
                else Modifier.width(300.dp))
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
            ) {
                // Check strip: expand + UID / SIMPLE / ADVANCED / DUP.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { wide = !wide }
                            .padding(3.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.foundation.Image(
                            painter = painterResource(if (wide) R.drawable.ic_ss_collapse else R.drawable.ic_ss_expand),
                            contentDescription = if (wide) "Dock" else "Expand",
                            modifier = Modifier.size(16.dp),
                            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurfaceVariant)
                        )
                    }
                    CheckStripDot(Modifier.weight(1f), "UID", uidState, false, onClick = { tab = 2 })
                    CheckStripDot(Modifier.weight(1f), "SIMPLE", if (simpleRan) simpleOk else null, false, onClick = { tab = 2 })
                    CheckStripDot(Modifier.weight(1f), "ADVANCED", if (advRan) advOk else null, false, onClick = { tab = 2 })
                    CheckStripDot(Modifier.weight(1f), "DUP", null, isDup, onClick = { tab = 3 })
                }
                PopupTabBar(
                    tabs = listOf(
                        "Details" to 0,
                        "Logs" to 0,
                        "Requests" to reqs.size,
                        "Duplicates" to dupSources.size
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
                        0 -> DetailsPane(check)
                        1 -> LogsPane(
                            check = check,
                            reqs = reqs,
                            onJump = { idx ->
                                jumpReq = idx
                                tab = 2
                            }
                        )
                        2 -> RequestsPane(reqs = reqs, jumpReq = jumpReq, onJumped = { jumpReq = null })
                        else -> DuplicatesPane(dupSources = dupSources)
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckStripDot(modifier: Modifier = Modifier, label: String, ok: Boolean?, warn: Boolean, onClick: () -> Unit) {
    val color = when {
        warn -> StatusYellow
        ok == true -> AliveGreen
        ok == false -> DeadRed
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    Column(
        modifier = modifier
            .weight(1f)
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.size(4.dp))
        Text(
            text = label,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PopupTabBar(tabs: List<Pair<String, Int>>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(0.dp)
            )
    ) {
        tabs.forEachIndexed { i, (label, count) ->
            val on = i == selected
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(0.dp))
                    .clickable { onSelect(i) }
                    .padding(top = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = label.uppercase(),
                        fontSize = 10.sp,
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
}

@Composable
private fun DetailRow(label: String, value: String, valueColor: Color? = null) {
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

@Composable
private fun DetailsPane(check: RowCheck?) {
    if (check == null || !check.hasData) {
        EmptyPane("No details for this row.")
        return
    }
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        check.simplePage?.let { DetailRow("simple.page", it) }
        check.simpleNumber?.let { DetailRow("simple.number", it) }
        if (check.simpleError != null && check.simplePage == null) {
            DetailRow("simple.error", check.simpleError, DeadRed)
        }
        DetailRow(
            "advanced.eligible", check.advEligible.toString(),
            if (check.advEligible) AliveGreen else MaterialTheme.colorScheme.onSurfaceVariant
        )
        check.advPage?.let { DetailRow("advanced.page", it) }
        check.advNumber?.let { DetailRow("advanced.number", it) }
        if (check.advBan != null) DetailRow("advanced.ban", check.advBan)
        if (check.advError != null) DetailRow("advanced.error", check.advError, DeadRed)
    }
}

private fun fmtTime(at: Long): String {
    if (at <= 0) return ""
    return java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(at))
}

private data class LogLine(val text: String, val ok: Boolean, val at: Long, val reqIdx: Int)

private fun logLines(check: RowCheck?, reqs: List<CheckReq>): List<LogLine> {
    val out = mutableListOf<LogLine>()
    for ((i, t) in reqs.withIndex()) {
        when (t.kind) {
            "uid" -> {
                val ok = t.resNote == "valid"
                out.add(LogLine(if (ok) "UID check passed." else "UID check failed.", ok, t.at, i))
            }
            "simple" -> {
                val ok = check?.simpleError == null && check?.simplePage != null
                out.add(LogLine(if (ok) "Simple check passed." else "Simple check failed.", ok, t.at, i))
            }
            "advanced" -> {
                val ok = t.error == null
                out.add(LogLine(if (ok) "Page check passed." else "Page check failed.", ok, t.at, i))
            }
            "graphql" -> {
                val ok = check?.advEligible == true
                out.add(LogLine(if (ok) "Advanced check passed." else "Advanced check failed.", ok, t.at, i))
            }
        }
    }
    return out.sortedBy { it.at }
}

@Composable
private fun LogsPane(check: RowCheck?, reqs: List<CheckReq>, onJump: (Int) -> Unit) {
    val lines = remember(check, reqs) { logLines(check, reqs) }
    if (lines.isEmpty()) {
        EmptyPane("No activity for this row.")
        return
    }
    LazyColumn(modifier = Modifier.padding(vertical = 4.dp)) {
        itemsIndexed(lines, key = { _, l -> l.reqIdx }) { _, l ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onJump(l.reqIdx) }
                    .padding(horizontal = 12.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(if (l.ok) AliveGreen else DeadRed)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = l.text,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = fmtTime(l.at),
                    fontSize = 10.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun RequestsPane(reqs: List<CheckReq>, jumpReq: Int?, onJumped: () -> Unit) {
    if (reqs.isEmpty()) {
        EmptyPane("No requests for this row.")
        return
    }
    var openIdx by remember(reqs) { mutableStateOf<Int?>(null) }
    val listState = rememberLazyListState()
    LaunchedEffect(jumpReq) {
        if (jumpReq != null && jumpReq in reqs.indices) {
            openIdx = jumpReq
            listState.scrollToItem(jumpReq)
            onJumped()
        }
    }
    // 0 Request, 1 Response, 2 Timing.
    var sub by remember(reqs) { mutableStateOf(0) }
    LazyColumn(state = listState, modifier = Modifier.padding(vertical = 4.dp)) {
        itemsIndexed(reqs, key = { i, _ -> i }) { i, q ->
            val open = openIdx == i
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (open) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        else Color.Transparent
                    )
                    .clickable { openIdx = if (open) null else i }
                    .padding(horizontal = 12.dp, vertical = 7.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (q.error != null) "ERR" else q.status.toString(),
                        fontSize = 11.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = when {
                            q.error != null -> DeadRed
                            q.status in 200..299 -> AliveGreen
                            q.status == 0 -> MaterialTheme.colorScheme.onSurfaceVariant
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
                        text = if (q.durationMs > 0) "${q.durationMs}ms" else "",
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
                        for ((si, label) in listOf("Request", "Response", "Timing").withIndex()) {
                            val on = sub == si
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .clickable { sub = si }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label.uppercase(),
                                    fontSize = 10.sp,
                                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (on) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.size(2.dp))
                    when (sub) {
                        0 -> {
                            DetailRow("Method", q.method)
                            DetailRow("URL", q.url)
                            q.reqNote?.let { DetailRow("Cookie", it) }
                        }
                        1 -> {
                            DetailRow(
                                "Status",
                                if (q.error != null) "error" else q.status.toString(),
                                if (q.error != null || q.status !in 200..299) DeadRed else AliveGreen
                            )
                            q.resNote?.let { DetailRow("Result", it) }
                            q.error?.let { DetailRow("Error", it, DeadRed) }
                        }
                        else -> {
                            DetailRow("Total", if (q.durationMs > 0) "${q.durationMs}ms" else "-")
                            val at = fmtTime(q.at)
                            if (at.isNotEmpty()) DetailRow("At", at)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DuplicatesPane(dupSources: List<DupSource>) {
    if (dupSources.isEmpty()) {
        EmptyPane("No duplicates for this row.")
        return
    }
    LazyColumn(modifier = Modifier.padding(vertical = 4.dp)) {
        itemsIndexed(dupSources, key = { i, _ -> i }) { _, s ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(StatusYellow)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = s.fileName,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "row ${s.rowNo} · ${s.field}",
                    fontSize = 10.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun EmptyPane(text: String) {
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
