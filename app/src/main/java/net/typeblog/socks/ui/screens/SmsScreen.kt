package net.typeblog.socks.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import net.typeblog.socks.ui.components.rememberPref
import androidx.preference.PreferenceManager
import kotlinx.coroutines.delay
import net.typeblog.socks.util.Constants.PREF_SMS_LAST_RANGE
import net.typeblog.socks.util.SmsNum
import net.typeblog.socks.util.SmsWatcher
import net.typeblog.socks.util.smsIsRangePat

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
    val haptic = LocalHapticFeedback.current

    var page by rememberSaveable { mutableStateOf(0) }
    val rangeState = rememberPref(prefs, PREF_SMS_LAST_RANGE) {
        it.getString(PREF_SMS_LAST_RANGE, null) ?: it.getString("kilo_range", "") ?: ""
    }
    val rangeText = rangeState.value
    var search by rememberSaveable { mutableStateOf("") }
    var numTab by rememberSaveable { mutableStateOf(0) }
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
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val now = SmsWatcher.now
    val revision = SmsWatcher.revision
    val mine = SmsWatcher.mine
    val expired = SmsWatcher.expired
    val feed = SmsWatcher.feed
    val countries = SmsWatcher.countries
    val mineSnapshot = remember(revision) { mine.toList() }
    val expiredSnapshot = remember(revision) { expired.toList() }
    val feedSnapshot = remember(revision) { feed.toList() }
    val countriesSnapshot = remember(revision) { countries.toList() }
    val busy = SmsWatcher.busy
    val error = SmsWatcher.error
    val errorAt = SmsWatcher.errorAt

    fun tapCopy(text: String) {
        if (text.isEmpty()) return
        clipboard.setText(AnnotatedString(text))
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        copied = text + "Copied"
    }

    fun onGet(pat: String) {
        if (smsIsRangePat(pat)) {
            SmsWatcher.provision(pat.filter { it.isDigit() || it == 'X' || it == 'x' }) { n ->
                if (n != null) {
                    sheet = Sheet.Item(n)
                    tapCopy(n.display)
                }
            }
        } else {
            sheet = Sheet.Methods
        }
    }

    fun onRegen(n: SmsNum) {
        SmsWatcher.provision(n.range, replaceId = n.id) { nn ->
            if (nn != null) {
                sheet = Sheet.Item(nn)
                tapCopy(nn.display)
            }
        }
    }

    // Close the item sheet only when its number is gone from BOTH lists.
    // mine and expired are disjoint, so testing mine alone made the sheet
    // snap shut the instant it opened for an expired number.
    val open = (sheet as? Sheet.Item)?.num
    LaunchedEffect(mineSnapshot, expiredSnapshot, open?.id) {
        if (open != null &&
            mineSnapshot.none { it.id == open.id } &&
            expiredSnapshot.none { it.id == open.id }
        ) sheet = null
    }

    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        when (page) {
            0 -> MainPage(
                now = now, revision = revision,
                mine = mineSnapshot, expired = expiredSnapshot,
                rangeText = rangeText,
                onRange = {
                    rangeState.value = it
                    prefs.edit().putString(PREF_SMS_LAST_RANGE, it).apply()
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
                now = now, mine = mineSnapshot, expired = expiredSnapshot,
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
                            tapCopy(nn.display)
                        }
                    }
                },
                onCopy = ::tapCopy,
                copied = copied,
            )

            2 -> FeedPage(
                now = now, revision = revision,
                mine = mineSnapshot, expired = expiredSnapshot,
                onBack = { page = 0 },
                onOpen = { sheet = Sheet.Item(it) },
                onRegen = ::onRegen,
                onCopy = ::tapCopy,
                copied = copied,
            )

            3 -> StatsPage(
                now = now, mine = mineSnapshot, expired = expiredSnapshot, onBack = { page = 0 },
                onCopy = ::tapCopy, copied = copied
            )
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

    val methodCounts = remember(feedSnapshot) {
        val byMethod = feedSnapshot.groupBy { it.method }
        val order = listOf("create", "forgot")
        ((order.filter { byMethod.containsKey(it) }) + (byMethod.keys - order.toSet()).sorted())
            .map { m ->
                val list = byMethod[m]!!
                val label = list.firstOrNull()?.methodName?.ifEmpty { null }
                    ?: if (m == "create") "Create New" else if (m == "forgot") "Forgot Password" else m
                MethodCount(m, label, list.size)
            }
    }
    sheet?.let { sh ->
        ModalBottomSheet(onDismissRequest = { sheet = null }, sheetState = sheetState) {
            when (sh) {
                is Sheet.Methods -> MethodSheet(
                    counts = methodCounts,
                    onPick = { m -> sheet = Sheet.Countries(m) },
                )

                is Sheet.Countries -> {
                    val rows = remember(sh.method, countriesSnapshot, feedSnapshot) {
                        countriesSnapshot.map { c ->
                            val hits = feedSnapshot.count { it.method == sh.method && it.range.startsWith(c.prefix) }
                            CountryRow(c, hits)
                        }.sortedByDescending { it.hits }
                    }
                    val label = methodCounts.firstOrNull { it.method == sh.method }?.label ?: sh.method
                    CountrySheet(
                        methodLabel = label,
                        rows = rows,
                        onBack = { sheet = Sheet.Methods },
                        onPick = { c -> sheet = Sheet.Confirm(c) },
                    )
                }

                is Sheet.Confirm -> ConfirmSheet(
                    country = sh.country, busy = busy,
                    onGet = { pat ->
                        SmsWatcher.provision(pat) { n ->
                            if (n != null) {
                                sheet = Sheet.Item(n)
                                tapCopy(n.display)
                            }
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
