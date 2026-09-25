package net.typeblog.socks.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.typeblog.socks.R
import net.typeblog.socks.util.SMS_EXPIRE_SEC
import net.typeblog.socks.util.SmsCountry
import net.typeblog.socks.util.SmsMsg
import net.typeblog.socks.util.SmsNum
import net.typeblog.socks.util.smsTimeAgo
import kotlin.math.roundToInt

internal val CodeGreen = Color(0xFF16A34A)
private val Amber = Color(0xFFD97706)

private fun mmss(leftSec: Long): String {
    val m = (leftSec / 60).toString().padStart(2, '0')
    val s = (leftSec % 60).toString().padStart(2, '0')
    return "$m:$s"
}

private fun appLabelFor(app: String): String {
    return when (app) {
        "FB_LITE" -> "FB Lite"
        "FB_MAIN" -> "FB Main"
        "FB_WEB" -> "FB Web"
        else -> "Facebook"
    }
}

private fun appIcon(app: String): Int {
    return when (app) {
        "FB_LITE" -> R.drawable.ic_svc_facebook_blue
        else -> R.drawable.ic_svc_facebook
    }
}

private fun subLine(n: SmsNum, now: Long): String {
    val base = if (n.code != null && n.svc.isNotEmpty()) "${n.svc} - ${n.country}" else n.country
    return "$base - ${smsTimeAgo(n.born, now)}"
}

internal data class MethodCount(val method: String, val label: String, val hits: Int)
internal data class CountryRow(val country: SmsCountry, val hits: Int)

internal sealed class Sheet {
    data object Methods : Sheet()
    data class Countries(val method: String) : Sheet()
    data class Confirm(val country: SmsCountry) : Sheet()
    data class Item(val num: SmsNum) : Sheet()
}

@Composable
internal fun SwipeBox(
    onRight: () -> Unit,
    onLeft: (() -> Unit)? = null,
    rightLabel: String = "Open",
    leftLabel: String = "New",
    contentLabel: String? = "Open SMS number",
    padBottom: androidx.compose.ui.unit.Dp = 8.dp,
    content: @Composable () -> Unit,
) {
    var dx by remember { mutableStateOf(0f) }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(bottom = padBottom)
            .clip(RoundedCornerShape(12.dp))
            .then(
                // Null for non-row content (e.g. the analysis tiles): the
                // row-specific label and the Regenerate action would be
                // wrong there. The swipe gesture is unaffected.
                if (contentLabel == null) Modifier
                else Modifier.semantics {
                    role = Role.Button
                    contentDescription = contentLabel
                    onClick(label = contentLabel) {
                        onRight()
                        true
                    }
                    customActions = buildList {
                        if (onLeft != null) {
                            add(CustomAccessibilityAction("Regenerate number") {
                                onLeft.invoke()
                                true
                            })
                        }
                    }
                }
            )
    ) {
        if (dx != 0f) {
            Row(
                modifier = Modifier.matchParentSize()
                    .background(Color.Black)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = rightLabel,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelMedium
                )
                Spacer(Modifier.weight(1f))
                if (onLeft != null) {
                    Text(
                        text = leftLabel,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
        Box(
            modifier = Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .offset { IntOffset(dx.roundToInt(), 0) }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (dx > 120) onRight()
                            else if (dx < -120) onLeft?.invoke()
                            dx = 0f
                        }
                    ) { change, amount ->
                        change.consume()
                        dx = (dx + amount).coerceIn(-140f, 140f)
                    }
                }
        ) { content() }
    }
}

@Composable
internal fun MineRow(
    n: SmsNum,
    now: Long,
    onOpen: (SmsNum) -> Unit,
    onRegen: (SmsNum) -> Unit,
    onCopy: (String) -> Unit,
    copied: String?,
) {
    val isExpired = n.born + SMS_EXPIRE_SEC * 1000 <= now
    SwipeBox(onRight = { onOpen(n) }, onLeft = { onRegen(n) }) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp))
                .clickable(onClick = { onOpen(n) })
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Same flag cell as CountriesScreen/RecentsCard: a centred box
            // then a 12dp gap. Without the spacer the number and country
            // name sat flush against the flag.
            Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                Text(text = n.flag, fontSize = 20.sp, maxLines = 1)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = n.display,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subLine(n, now) + if (isExpired) " - expired" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (n.code != null) {
                Text(
                    text = if (copied == n.code + "Copied") "Copied" else n.code!!,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodySmall,
                    color = CodeGreen,
                    modifier = Modifier.clickable {
                        onCopy(n.code!!)
                    }
                )
            } else if (isExpired) {
                Text(
                    text = "expired",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
            } else {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            }
        }
    }
}

@Composable
internal fun ReceivedRow(
    n: SmsNum,
    m: SmsMsg,
    now: Long,
    onOpen: (SmsNum) -> Unit,
    onRegen: (SmsNum) -> Unit,
    onCopy: (String) -> Unit,
    copied: String?,
) {
    SwipeBox(onRight = { onOpen(n) }, onLeft = { onRegen(n) }) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp))
                .clickable(onClick = { onOpen(n) })
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Always reserve the flag slot: MineRow does, so a conditional
            // here would shift the number column between the two sections.
            Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                Text(text = n.flag, fontSize = 20.sp, maxLines = 1)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = n.display,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${n.svc.ifEmpty { n.country }} - ${smsTimeAgo(m.at, now)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = if (copied == m.code + "Copied" && m.code.isNotEmpty()) "Copied" else m.code,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodySmall,
                color = CodeGreen,
                modifier = Modifier.clickable(enabled = m.code.isNotEmpty()) { onCopy(m.code) }
            )
        }
    }
}

@Composable
internal fun MethodSheet(counts: List<MethodCount>, onPick: (String) -> Unit) {
    Text(
        text = "Method", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 16.dp)
    )
    if (counts.isEmpty()) {
        Text(
            text = "No methods yet",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp)
        )
    }
    LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        items(counts, key = { it.method }) { m ->
            Row(
                modifier = Modifier.fillMaxWidth().clickable(onClick = { onPick(m.method) }).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = m.label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = m.hits.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            HorizontalDivider()
        }
    }
}

@Composable
internal fun CountrySheet(
    methodLabel: String,
    rows: List<CountryRow>,
    onBack: () -> Unit,
    onPick: (SmsCountry) -> Unit
) {
    Text(
        text = "< Methods",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).clickable(onClick = onBack)
    )
    Text(
        text = "Country", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 16.dp)
    )
    Text(
        text = methodLabel,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp)
    )
    if (rows.isEmpty()) {
        Text(
            text = "No countries yet",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp)
        )
    }
    LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        // Index in the key: the gateway can repeat a prefix (or send a blank
        // one), and a duplicate key throws out of LazyColumn.
        itemsIndexed(rows, key = { index, r -> "${r.country.prefix}:$index" }) { _, r ->
            Row(
                modifier = Modifier.fillMaxWidth().clickable(onClick = { onPick(r.country) }).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                    Text(text = r.country.flag, fontSize = 24.sp, maxLines = 1)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = r.country.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "+${r.country.prefix}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = r.hits.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            HorizontalDivider()
        }
    }
}

@Composable
internal fun ConfirmSheet(country: SmsCountry, busy: Boolean, onGet: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(text = "Confirm", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(text = "Facebook - ${country.name} (${country.prefix})", style = MaterialTheme.typography.bodyLarge)
        if (country.sampleRange.isNotEmpty()) {
            Text(
                text = "Range ${country.sampleRange}",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { onGet(country.sampleRange.ifEmpty { country.prefix + "XXX" }) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (busy) "..." else "Get number")
        }
    }
}

@Composable
internal fun ItemSheet(
    num: SmsNum,
    now: Long,
    onCopy: (String) -> Unit,
    copied: String?,
) {
    val isExpired = num.born + SMS_EXPIRE_SEC * 1000 <= now
    val sub = subLine(num, now) + if (isExpired) " - expired" else ""
    val code = num.code
    val left = ((num.born + SMS_EXPIRE_SEC * 1000 - now) / 1000).coerceAtLeast(0)
    val waiting = num.code == null && !isExpired

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                Text(text = num.flag, fontSize = 30.sp, maxLines = 1)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = num.display,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.clickable { onCopy(num.display) }
                )
                Text(
                    text = sub,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!code.isNullOrEmpty()) {
                Text(
                    text = if (copied == code + "Copied") "Copied" else code,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 18.sp,
                    color = CodeGreen,
                    modifier = Modifier.clickable { onCopy(code) }
                )
            }
        }
        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
            ExpiryRing(left, SMS_EXPIRE_SEC, isExpired)
        }
        if (waiting) {
            Text(
                text = "Waiting for SMS...",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            )
        }
        // One Text with a styled span. Splitting the sentence into separate
        // composables broke the line in the middle of the message.
        num.msgs.asReversed().forEach { msg ->
            val c = msg.code
            val t = msg.text
            Column(
                Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp))
                    .clickable { onCopy(t) }
                    .padding(10.dp)
            ) {
                val idx = if (c.isNotEmpty()) t.indexOf(c) else -1
                Text(
                    text = if (idx < 0) {
                        buildAnnotatedString { append(t) }
                    } else {
                        buildAnnotatedString {
                            append(t.substring(0, idx))
                            withStyle(
                                SpanStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = CodeGreen
                                )
                            ) { append(c) }
                            append(t.substring(idx + c.length))
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        if (num.code != null) {
            FactRow("Service", appLabelFor(num.app), null, onCopy, copied, icon = appIcon(num.app))
        }
        if (num.range.isNotEmpty()) FactRow("Range", num.range, num.range, onCopy, copied)
    }
}

@Composable
private fun FactRow(
    key: String,
    value: String,
    copy: String?,
    onCopy: (String) -> Unit,
    copied: String?,
    icon: Int? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable(enabled = copy != null) { copy?.let(onCopy) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = key,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.weight(1f))
        if (icon != null) {
            Image(
                painter = painterResource(icon),
                contentDescription = null,
                modifier = Modifier.size(20.dp).padding(end = 6.dp)
            )
        }
        Text(
            text = if (copy != null && copied == copy + "Copied") "Copied" else value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            fontFamily = if (copy != null) FontFamily.Monospace else FontFamily.Default,
            textAlign = TextAlign.End
        )
    }
    HorizontalDivider()
}

@Composable
private fun ExpiryRing(leftSec: Long, totalSec: Long, expired: Boolean) {
    val frac = if (totalSec <= 0) 0f else leftSec.toFloat() / totalSec
    val color = when {
        expired || leftSec <= 0 -> MaterialTheme.colorScheme.error
        leftSec < 60 -> MaterialTheme.colorScheme.error
        leftSec < 180 -> Amber
        else -> CodeGreen
    }
    val label = if (expired || leftSec <= 0) "00:00" else mmss(leftSec)
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(56.dp)) {
        Canvas(Modifier.size(56.dp)) {
            val side = size.width - 10f
            drawArc(
                color = Color.LightGray,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = 10f),
                topLeft = Offset(5f, 5f),
                size = androidx.compose.ui.geometry.Size(side, side)
            )
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = 360f * frac,
                useCenter = false,
                style = Stroke(width = 10f, cap = StrokeCap.Round),
                topLeft = Offset(5f, 5f),
                size = androidx.compose.ui.geometry.Size(side, side)
            )
        }
        Text(
            text = label,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelMedium,
            color = color
        )
    }
}
