package net.typeblog.socks.ui.screens

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.typeblog.socks.R
import net.typeblog.socks.ui.components.SsBanner
import net.typeblog.socks.ui.components.SsBannerStatus
import net.typeblog.socks.util.SmsNum
import net.typeblog.socks.util.smsIsRangePat
import net.typeblog.socks.util.smsTimeAgo
import java.util.Calendar
import kotlin.math.roundToInt

private fun openBackgroundSettings(ctx: Context) {
    // Battery exemption first (lets the watch run with the app closed),
    // then the exact-alarm grant (lets the 30s heartbeat fire in Doze).
    try {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm != null && !pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
            ctx.startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${ctx.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
    } catch (_: Exception) {
    }
    try {
        if (Build.VERSION.SDK_INT >= 31) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            if (am != null && !am.canScheduleExactAlarms()) {
                ctx.startActivity(
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    } catch (_: Exception) {
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SmsTopBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title) },
        windowInsets = WindowInsets(0),
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    painter = painterResource(R.drawable.lucide_arrow_left),
                    contentDescription = "Back"
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface
        )
    )
}

@Composable
private fun BgWatchBanner(waiting: Boolean, now: Long) {
    if (!waiting) return
    val ctx = LocalContext.current
    val restricted = remember(now) {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val batteryOk = pm?.isIgnoringBatteryOptimizations(ctx.packageName) != false
        val alarmOk = if (Build.VERSION.SDK_INT >= 31) {
            (ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager)?.canScheduleExactAlarms() != false
        } else true
        !(batteryOk && alarmOk)
    }
    if (!restricted) return
    SsBanner(
        status = SsBannerStatus.WARNING,
        title = "Background SMS may stall",
        description = "Allow background running so codes arrive with the app closed.",
        actionLabel = "Allow",
        onAction = { openBackgroundSettings(ctx) },
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

@Composable
private fun SectionHead(title: String, onOpen: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Icon(
            painter = painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun StatTiles(nums: Int, otps: Int, pct: Int, onOpen: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StatTile("Numbers", nums.toString(), Modifier.weight(1f))
        StatTile("OTPs", otps.toString(), Modifier.weight(1f))
        StatTile("Success", "$pct%", Modifier.weight(1f))
    }
}

@Composable
private fun StatTile(label: String, value: String, mod: Modifier) {
    Column(
        mod.background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp)).padding(10.dp)
    ) {
        Text(text = value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
internal fun MainPage(
    now: Long,
    revision: Long,
    mine: List<SmsNum>,
    expired: List<SmsNum>,
    rangeText: String,
    onRange: (String) -> Unit,
    onGet: (String) -> Unit,
    busy: Boolean,
    onOpenNums: () -> Unit,
    onOpenFeed: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenMine: (SmsNum) -> Unit,
    onRegen: (SmsNum) -> Unit,
    onCopy: (String) -> Unit,
    copied: String?,
) {
    // Keyed on revision, not on the lists: SmsNum is a plain class, so an
    // arriving OTP mutates the instance already inside the list and
    // remember(mine, expired) never invalidates on equal contents.
    val all = remember(revision) { mine + expired }
    val recent = remember(revision) { all.flatMap { n -> n.msgs.map { n to it } }.sortedByDescending { it.second.at } }
    val otpCount = all.sumOf { it.msgs.size }
    val total = all.size
    val pct = if (total == 0) 0 else (mine.count { it.code != null } + expired.count { it.code != null }) * 100 / total
    val lastNums = remember(mine, expired) { (mine + expired).sortedByDescending { it.born }.take(10) }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Text(
                text = "SMS",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
            )
            BgWatchBanner(mine.any { it.code == null }, now)
            SectionHead("Today analysis", onOpenStats)
            SwipeBox(
                onRight = onOpenStats,
                onLeft = onOpenStats,
                rightLabel = "Open",
                leftLabel = "Open",
                contentLabel = null,
                padBottom = 0.dp
            ) {
                StatTiles(total, otpCount, pct, onOpenStats)
            }
            Column(
                Modifier.fillMaxWidth().padding(top = 12.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp)).padding(12.dp)
            ) {
                Text(text = "New number", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = rangeText,
                    onValueChange = onRange,
                    placeholder = { Text("Enter range") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = { onGet(rangeText.trim()) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(if (busy) "..." else "Get number")
                }
            }
            SectionHead("My numbers", onOpenNums)
        }
        if (lastNums.isEmpty()) {
            item {
                Text(
                    text = "No numbers yet",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
        } else {
            items(lastNums, key = { it.id }) { n ->
                MineRow(n, now, onOpenMine, onRegen, onCopy, copied)
            }
        }
        item { SectionHead("Live", onOpenFeed) }
        if (recent.isEmpty()) {
            item {
                Text(
                    text = "No OTPs yet",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
        } else {
            items(
                recent.take(3),
                key = { item -> "${item.first.id}:${item.second.at}:${item.second.code}" }) { (n, m) ->
                ReceivedRow(n, m, now, onOpenMine, onRegen, onCopy, copied)
            }
        }
    }
}

@Composable
internal fun NumsPage(
    now: Long,
    mine: List<SmsNum>,
    expired: List<SmsNum>,
    search: String,
    onSearch: (String) -> Unit,
    numTab: Int,
    onTab: (Int) -> Unit,
    onBack: () -> Unit,
    onOpenMine: (SmsNum) -> Unit,
    onOpenExpired: (SmsNum) -> Unit,
    onRegen: (SmsNum) -> Unit,
    onRangeGo: (String) -> Unit,
    onCopy: (String) -> Unit,
    copied: String?,
) {
    Column(Modifier.fillMaxSize()) {
        SmsTopBar(title = "My numbers", onBack = onBack)
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(10.dp)).padding(4.dp)
        ) {
            TextButton(onClick = { onTab(0) }, modifier = Modifier.weight(1f)) {
                Text("Active", fontWeight = if (numTab == 0) FontWeight.Bold else FontWeight.Normal)
            }
            TextButton(onClick = { onTab(1) }, modifier = Modifier.weight(1f)) {
                Text("Expired", fontWeight = if (numTab == 1) FontWeight.Bold else FontWeight.Normal)
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = search,
            onValueChange = onSearch,
            placeholder = { Text("Search") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        val q = search.trim()
        if (smsIsRangePat(q)) {
            Spacer(Modifier.height(8.dp))
            Button(onClick = { onRangeGo(q.uppercase()) }, modifier = Modifier.fillMaxWidth()) {
                Text("Get Facebook number - ${q.uppercase()}")
            }
        }
        Spacer(Modifier.height(8.dp))
        if (numTab == 1) {
            val list = expired.filter { q.isEmpty() || it.display.contains(q, true) }
                .sortedByDescending { it.born }.take(10)
            if (list.isEmpty()) {
                Text("No expired numbers", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.id }) { n ->
                    MineRow(n, now, onOpenExpired, onRegen, onCopy, copied)
                }
            }
        } else {
            val list = mine.filter { q.isEmpty() || it.display.contains(q, true) }
                .sortedByDescending { it.born }.take(10)
            if (list.isEmpty()) {
                Text("No numbers yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.id }) { n ->
                    MineRow(n, now, onOpenMine, onRegen, onCopy, copied)
                }
            }
        }
    }
}

@Composable
internal fun FeedPage(
    now: Long,
    revision: Long,
    mine: List<SmsNum>,
    expired: List<SmsNum>,
    onBack: () -> Unit,
    onOpen: (SmsNum) -> Unit,
    onRegen: (SmsNum) -> Unit,
    onCopy: (String) -> Unit,
    copied: String?,
) {
    val received = remember(revision) {
        (mine + expired).flatMap { n -> n.msgs.map { n to it } }
            .sortedByDescending { it.second.at }
    }
    Column(Modifier.fillMaxSize()) {
        SmsTopBar(title = "Live", onBack = onBack)
        Spacer(Modifier.height(8.dp))
        if (received.isEmpty()) {
            Text("No OTPs yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(
                received.take(50),
                key = { item -> "${item.first.id}:${item.second.at}:${item.second.code}" }) { (n, m) ->
                ReceivedRow(n, m, now, onOpen, onRegen, onCopy, copied)
            }
        }
    }
}

@Composable
internal fun StatsPage(
    now: Long,
    mine: List<SmsNum>,
    expired: List<SmsNum>,
    onBack: () -> Unit,
    onCopy: (String) -> Unit,
    copied: String?,
) {
    val all = remember(mine, expired) { mine + expired }
    val otpCount = all.sumOf { it.msgs.size }
    val withCode = all.count { it.code != null }
    val pct = if (all.isEmpty()) 0 else withCode * 100 / all.size
    val waits = all.flatMap { n -> n.msgs.map { (it.at - n.born) / 1000 } }
    val avgWait = if (waits.isEmpty()) "-" else "${waits.average().roundToInt()}s"
    val week = IntArray(7)
    val hours = IntArray(8)
    all.forEach { n ->
        n.msgs.forEach { m ->
            val c = Calendar.getInstance().apply { timeInMillis = m.at }
            week[(c.get(Calendar.DAY_OF_WEEK) + 5) % 7]++
            hours[(c.get(Calendar.HOUR_OF_DAY) / 3).coerceIn(0, 7)]++
        }
    }
    val byCty = all.groupBy { it.country }
    val maxC = (byCty.values.maxOfOrNull { it.size } ?: 1).toFloat()
    val recent = all.flatMap { n -> n.msgs.map { n to it } }.sortedByDescending { it.second.at }.take(5)
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            SmsTopBar(title = "Activity", onBack = onBack)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Numbers", all.size.toString(), Modifier.weight(1f))
                StatTile("OTPs", otpCount.toString(), Modifier.weight(1f))
                StatTile("Success", "$pct%", Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Wait", avgWait, Modifier.weight(1f))
                StatTile("Active", mine.size.toString(), Modifier.weight(1f))
                StatTile("Expired", expired.size.toString(), Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "OTPs this week",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Bars(listOf("M", "T", "W", "T", "F", "S", "S"), week.toList())
            Spacer(Modifier.height(8.dp))
            Text(
                "OTPs by hour",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Bars(listOf("12a", "3a", "6a", "9a", "12p", "3p", "6p", "9p"), hours.toList())
            Spacer(Modifier.height(8.dp))
            Text(
                "Countries",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (byCty.isEmpty()) {
            item { Text("None", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            items(byCty.entries.sortedByDescending { it.value.size }, key = { it.key }) { (name, list) ->
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = list.firstOrNull()?.flag ?: "", fontSize = 20.sp, modifier = Modifier.width(28.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "${list.size} numbers - ${list.sumOf { it.msgs.size }} OTPs",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    LinearProgressIndicator(
                        progress = { list.size / maxC },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        color = CodeGreen,
                    )
                }
            }
        }
        item {
            Spacer(Modifier.height(8.dp))
            Text(
                "Recent",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (recent.isEmpty()) {
            item { Text("None", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            items(recent, key = { item -> "${item.first.id}:${item.second.at}:${item.second.code}" }) { (n, m) ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onCopy(m.code) }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${n.flag} ${n.display}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (copied == m.code + "Copied") "Copied" else m.code,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = CodeGreen,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = smsTimeAgo(m.at, now),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun Bars(labels: List<String>, values: List<Int>) {
    val max = (values.maxOrNull() ?: 1).coerceAtLeast(1).toFloat()
    Row(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp)).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        labels.forEachIndexed { i, lab ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.height(48.dp).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                    Box(
                        Modifier.fillMaxWidth().height((48 * values.getOrElse(i) { 0 } / max).dp)
                            .background(
                                if (i == labels.size - 1 && values.getOrElse(i) { 0 } > 0) CodeGreen
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                RoundedCornerShape(3.dp)
                            )
                    )
                }
                Text(
                    text = lab,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
