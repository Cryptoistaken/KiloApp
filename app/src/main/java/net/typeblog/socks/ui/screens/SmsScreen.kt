package net.typeblog.socks.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import kotlinx.coroutines.delay
import net.typeblog.socks.R
import net.typeblog.socks.util.SMS_EXPIRE_SEC
import net.typeblog.socks.util.SmsCountry
import net.typeblog.socks.util.SmsMsg
import net.typeblog.socks.util.SmsNum
import net.typeblog.socks.util.SmsWatcher
import net.typeblog.socks.util.smsIsRangePat
import net.typeblog.socks.util.smsTimeAgo
import java.util.Calendar
import kotlin.math.roundToInt

private const val RANGE_KEY = "kilo_range"
private val CodeGreen = Color(0xFF16A34A)
private val Amber = Color(0xFFD97706)

private fun mmss(leftSec: Long): String {    val m = (leftSec / 60).toString().padStart(2, '0')
    val s = (leftSec % 60).toString().padStart(2, '0')
    return "$m:$s"
}

private fun svcIcon(svc: String): Int? {
    val s = svc.uppercase()
    return when {
        s.startsWith("FACEBOOK") || s.startsWith("FB ") || s == "FB" -> R.drawable.ic_svc_facebook_blue
        s.startsWith("MESSENGER") -> R.drawable.ic_svc_messenger
        else -> null
    }
}

private fun subLine(n: SmsNum, now: Long): String {
    val base = if (n.code != null && n.svc.isNotEmpty()) "${n.svc} - ${n.country}" else n.country
    return "$base - ${smsTimeAgo(n.born, now)}"
}

/**
 * SMS tab — mirrors the kilosms mockup: main (analysis, hero range,
 * my-numbers preview, live preview), My numbers, Live, Activity pages,
 * plus country / confirm / item bottom sheets sharing one layout.
 * Numbers, polling and OTP notifications live in SmsWatcher so they
 * survive tab switches.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }
    val clipboard = LocalClipboardManager.current

    var page by remember { mutableStateOf(0) } // 0 main, 1 nums, 2 feed, 3 stats
    var rangeText by remember { mutableStateOf(prefs.getString(RANGE_KEY, "") ?: "") }
    var search by remember { mutableStateOf("") }
    var numTab by remember { mutableStateOf(0) } // 0 active, 1 expired
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var copied by remember { mutableStateOf<String?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // "Copied" feedback reverts after a moment instead of sticking
    // on the last copied row.
    LaunchedEffect(copied) {
        if (copied != null) {
            delay(1200)
            copied = null
        }
    }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val now = SmsWatcher.now
    val mine = SmsWatcher.mine
    val expired = SmsWatcher.expired
    val feed = SmsWatcher.feed
    val countries = SmsWatcher.countries
    val busy = SmsWatcher.busy
    val error = SmsWatcher.error
    val errorAt = SmsWatcher.errorAt

    fun tapCopy(text: String) {
        if (text.isEmpty()) return
        clipboard.setText(AnnotatedString(text))
        copied = text + "Copied"
    }

    fun onGet(pat: String) {
        if (smsIsRangePat(pat)) {
            SmsWatcher.provision(pat.filter { it.isDigit() || it == 'X' || it == 'x' }) { n ->
                if (n != null) sheet = Sheet.Item(n)
            }
        } else {
            sheet = Sheet.Countries
        }
    }

    fun onRegen(n: SmsNum) {
        SmsWatcher.provision(n.range) { nn ->
            if (nn != null) {
                mine.removeAll { it.id == n.id }
                sheet = Sheet.Item(nn)
            }
        }
    }

    // Close the item sheet if its number just expired.
    val open = (sheet as? Sheet.Item)?.num
    LaunchedEffect(mine.size, expired.size) {
        if (open != null && mine.none { it.id == open.id }) sheet = null
    }

    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        when (page) {
            0 -> MainPage(
                now = now, mine = mine, expired = expired,
                rangeText = rangeText,
                onRange = {
                    rangeText = it
                    prefs.edit().putString(RANGE_KEY, it).apply()
                },
                onGet = ::onGet,
                busy = busy,
                onOpenNums = { page = 1 },
                onOpenFeed = { page = 2 },
                onOpenStats = { page = 3 },
                onOpenMine = { sheet = Sheet.Item(it) },
                onRegen = ::onRegen,
                onCopy = ::tapCopy,
                copied = copied,
            )
            1 -> NumsPage(
                now = now, mine = mine, expired = expired,
                search = search, onSearch = { search = it },
                numTab = numTab, onTab = { numTab = it },
                onBack = { page = 0 },
                onOpenMine = { sheet = Sheet.Item(it) },
                onOpenExpired = { sheet = Sheet.Item(it) },
                onRegen = ::onRegen,
                onRangeGo = { pat ->
                    SmsWatcher.provision(pat) { nn ->
                        if (nn != null) {
                            search = ""
                            sheet = Sheet.Item(nn)
                        }
                    }
                },
                onCopy = ::tapCopy,
                copied = copied,
            )
            2 -> FeedPage(
                now = now, mine = mine, expired = expired,
                onBack = { page = 0 },
                onOpen = { sheet = Sheet.Item(it) },
                onRegen = ::onRegen,
                onCopy = ::tapCopy,
                copied = copied,
            )
            3 -> StatsPage(now = now, mine = mine, expired = expired, onBack = { page = 0 },
                onCopy = ::tapCopy, copied = copied)
        }
        if (error.isNotEmpty() && now - errorAt < 5000) {
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }
    }

    sheet?.let { sh ->
        ModalBottomSheet(onDismissRequest = { sheet = null }, sheetState = sheetState) {
            when (sh) {
                is Sheet.Countries -> CountrySheet(
                    countries = countries,
                    onPick = { c -> sheet = Sheet.Confirm(c) },
                )
                is Sheet.Confirm -> ConfirmSheet(
                    country = sh.country, busy = busy,
                    onGet = { pat ->
                        SmsWatcher.provision(pat) { n ->
                            if (n != null) sheet = Sheet.Item(n)
                        }
                    },
                )
                is Sheet.Item -> ItemSheet(
                    num = sh.num, now = now,
                    onCopy = ::tapCopy, copied = copied,
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

private sealed class Sheet {
    data object Countries : Sheet()
    data class Confirm(val country: SmsCountry) : Sheet()
    data class Item(val num: SmsNum) : Sheet()
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
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MainPage(
    now: Long,
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
    val all = mine + expired
    val recent = all.flatMap { n -> n.msgs.map { n to it } }.sortedByDescending { it.second.at }
    val otpCount = all.sumOf { it.msgs.size }
    val total = all.size
    val pct = if (total == 0) 0 else (mine.count { it.code != null } + expired.count { it.code != null }) * 100 / total
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Text(
                text = "SMS",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
            )
            SectionHead("Today analysis", onOpenStats)
            SwipeBox(onRight = onOpenStats, onLeft = onOpenStats, rightLabel = "Open", leftLabel = "Open", padBottom = 0.dp) {
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
        if (mine.isEmpty()) {
            item {
                Text(
                    text = "No numbers yet",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
        } else {
            items(mine.take(3), key = { it.id }) { n ->
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
            items(recent.take(3)) { (n, m) ->
                ReceivedRow(n, m, now, onOpenMine, onRegen, onCopy, copied)
            }
        }
    }
}

@Composable
private fun SwipeBox(
    onRight: () -> Unit,
    onLeft: (() -> Unit)? = null,
    rightLabel: String = "Open",
    leftLabel: String = "New",
    padBottom: androidx.compose.ui.unit.Dp = 8.dp,
    content: @Composable () -> Unit,
) {
    var dx by remember { mutableStateOf(0f) }
    Box(Modifier.fillMaxWidth().padding(bottom = padBottom).clip(RoundedCornerShape(12.dp))) {
        if (dx != 0f) {
            Row(
                modifier = Modifier.matchParentSize()
                    .background(Color.Black)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = rightLabel, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.weight(1f))
                if (onLeft != null) {
                    Text(text = leftLabel, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
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
private fun MineRow(
    n: SmsNum,
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
            Text(text = n.flag, fontSize = 20.sp, modifier = Modifier.width(28.dp))
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
                    text = subLine(n, now),
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
            } else {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            }
        }
    }
}

@Composable
private fun ReceivedRow(
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
        if (n.flag.isNotEmpty()) {
            Text(text = n.flag, fontSize = 20.sp, modifier = Modifier.width(28.dp))
        }
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
private fun NumsPage(
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
            if (list.isEmpty()) {
                Text("No expired numbers", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.id }) { n ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp))
                            .clickable(onClick = { onOpenExpired(n) })
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = n.flag, fontSize = 20.sp, modifier = Modifier.width(28.dp))
                        Column(Modifier.weight(1f)) {
                            Text(text = n.display, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                            Text(
                                text = subLine(n, now),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(text = "expired", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else {
            val list = mine.filter { q.isEmpty() || it.display.contains(q, true) }
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
private fun FeedPage(
    now: Long,
    mine: List<SmsNum>,
    expired: List<SmsNum>,
    onBack: () -> Unit,
    onOpen: (SmsNum) -> Unit,
    onRegen: (SmsNum) -> Unit,
    onCopy: (String) -> Unit,
    copied: String?,
) {
    val received = remember(mine.size, expired.size) {
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
            items(received.take(50)) { (n, m) ->
                ReceivedRow(n, m, now, onOpen, onRegen, onCopy, copied)
            }
        }
    }
}

@Composable
private fun StatsPage(
    now: Long,
    mine: List<SmsNum>,
    expired: List<SmsNum>,
    onBack: () -> Unit,
    onCopy: (String) -> Unit,
    copied: String?,
) {
    val all = remember(mine.size, expired.size) { mine + expired }
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
            Text("OTPs this week", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Bars(listOf("M", "T", "W", "T", "F", "S", "S"), week.toList())
            Spacer(Modifier.height(8.dp))
            Text("OTPs by hour", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Bars(listOf("12a", "3a", "6a", "9a", "12p", "3p", "6p", "9p"), hours.toList())
            Spacer(Modifier.height(8.dp))
            Text("Countries", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (byCty.isEmpty()) {
            item { Text("None", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            items(byCty.entries.sortedByDescending { it.value.size }) { (name, list) ->
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = list.firstOrNull()?.flag ?: "", fontSize = 20.sp, modifier = Modifier.width(28.dp))
                        Column(Modifier.weight(1f)) {
                            Text(text = name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
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
            Text("Recent", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (recent.isEmpty()) {
            item { Text("None", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            items(recent) { (n, m) ->
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
                Text(text = lab, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun CountrySheet(countries: List<SmsCountry>, onPick: (SmsCountry) -> Unit) {
    Text(
        text = "Country", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 16.dp)
    )
    LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        items(countries) { c ->
            Row(
                modifier = Modifier.fillMaxWidth().clickable(onClick = { onPick(c) }).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = c.flag, fontSize = 24.sp, modifier = Modifier.width(36.dp))
                Text(text = c.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(
                    text = "+${c.prefix}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun ConfirmSheet(country: SmsCountry, busy: Boolean, onGet: (String) -> Unit) {
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
private fun ItemSheet(
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
            if (num.flag.isNotEmpty()) Text(text = num.flag, fontSize = 30.sp, modifier = Modifier.width(40.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = num.display,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.clickable { onCopy(num.full) }
                )
                Text(text = sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        val msgs: List<Pair<String, String>> = num.msgs.map { it.code to it.text }
        msgs.reversed().forEach { (c, t) ->
            Column(
                Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp))
                    .clickable { onCopy(t) }
                    .padding(10.dp)
            ) {
                if (c.isNotEmpty() && t.contains(c)) {
                    val idx = t.indexOf(c)
                    Row {
                        Text(text = t.substring(0, idx), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = c,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.ExtraBold,
                            color = CodeGreen,
                            modifier = Modifier.clickable { onCopy(c) }
                        )
                    }
                    if (idx + c.length < t.length) {
                        Text(text = t.substring(idx + c.length), style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    Text(text = t, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (num.code != null) {
            FactRow("Service", "Facebook", null, onCopy, copied, icon = svcIcon("Facebook"))
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
        Text(text = key, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
