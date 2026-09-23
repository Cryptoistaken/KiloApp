package net.typeblog.socks.ui.screens.sheet

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.typeblog.socks.R
import net.typeblog.socks.util.sheet.SheetStore
import net.typeblog.socks.util.sheet.WalletTx
import java.util.Calendar
import java.util.Locale

private const val BDT_RATE = 120.0

private data class PayMethod(val id: String, val label: String, val icon: Int, val hint: String)

private val PAY_METHODS = listOf(
    PayMethod("bKash", "bKash", R.drawable.ic_ss_bkash, "BD mobile number"),
    PayMethod("Nagad", "Nagad", R.drawable.ic_ss_nagad, "BD mobile number"),
    PayMethod("USDT", "USDT", R.drawable.ic_ss_usdt, "BEP20 address"),
    PayMethod("Binance", "Binance", R.drawable.ic_ss_binance, "Binance UID")
)

private fun payKey(method: String): String = "sheet_pay_" + method

// Website WalletView ACCOUNT_RE: payout account must match the method format.
private val ACCOUNT_RE = mapOf(
    "bKash" to Regex("^(\\+?880|0)?1[3-9]\\d{8}$"),
    "Nagad" to Regex("^(\\+?880|0)?1[3-9]\\d{8}$"),
    "USDT" to Regex("^0x[a-fA-F0-9]{40}$"),
    "Binance" to Regex("^\\d{9,10}$")
)

private fun validAccount(method: String, account: String): Boolean =
    ACCOUNT_RE[method]?.matches(account.trim()) == true

private fun fmtBdt(balance: Double): String {
    val v = Math.round(balance * BDT_RATE)
    return String.format(Locale.US, "%,d", v)
}

private fun fmtDateTime(ts: Long): String {
    if (ts <= 0) return "-"
    val f = java.text.SimpleDateFormat("MMM d, yyyy HH:mm", Locale.US)
    return f.format(java.util.Date(ts))
}

private fun dayStartMillis(ts: Long): Long {
    val c = Calendar.getInstance()
    c.timeInMillis = ts
    c.set(Calendar.HOUR_OF_DAY, 0)
    c.set(Calendar.MINUTE, 0)
    c.set(Calendar.SECOND, 0)
    c.set(Calendar.MILLISECOND, 0)
    return c.timeInMillis
}

private fun groupLabel(ts: Long, todayStart: Long, yesterdayStart: Long, weekStart: Long): String {
    val d = dayStartMillis(ts)
    return when {
        d >= todayStart -> "Today"
        d >= yesterdayStart -> "Yesterday"
        d >= weekStart -> "This week"
        else -> "Older"
    }
}

private fun groupTransactions(txs: List<WalletTx>): List<Pair<String, List<WalletTx>>> {
    if (txs.isEmpty()) return emptyList()
    val now = System.currentTimeMillis()
    val todayStart = dayStartMillis(now)
    val yesterdayStart = todayStart - 86400000L
    val weekStart = todayStart - 7 * 86400000L
    val order = listOf("Today", "Yesterday", "This week", "Older")
    val map = LinkedHashMap<String, MutableList<WalletTx>>()
    for (tx in txs) {
        val label = groupLabel(tx.createdAt, todayStart, yesterdayStart, weekStart)
        map.getOrPut(label) { mutableListOf() }.add(tx)
    }
    return order.filter { map.containsKey(it) }.map { it to map[it]!!.toList() }
}

@Composable
fun SheetWalletTab(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val appCtx = remember(context) { context.applicationContext }
    val store = remember(appCtx) { SheetStore.get(appCtx) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val prefs = remember(appCtx) { PreferenceManager.getDefaultSharedPreferences(appCtx) }

    val balance by store.balance.collectAsState()
    val txs by store.txs.collectAsState()

    var showBdt by rememberSaveable { mutableStateOf(false) }
    var amount by rememberSaveable { mutableStateOf("") }
    var method by rememberSaveable { mutableStateOf("bKash") }
    var account by rememberSaveable { mutableStateOf("") }
    var saveAccount by rememberSaveable { mutableStateOf(true) }
    var filter by rememberSaveable { mutableStateOf("ALL") }
    var detail by remember { mutableStateOf<WalletTx?>(null) }
    var sending by remember { mutableStateOf(false) }
    var slideKey by remember { mutableStateOf(0) }

    LaunchedEffect(method) {
        val saved = prefs.getString(payKey(method), null)
        if (saved != null) account = saved
    }

    fun toast(msg: String) {
        Toast.makeText(appCtx, msg, Toast.LENGTH_SHORT).show()
    }

    fun submit() {
        if (sending) return
        val value = amount.toDoubleOrNull()
        if (value == null || value <= 0 || value > balance || !validAccount(method, account)) {
            toast(
                if (value != null && value > balance) "Amount exceeds your available balance."
                else "Please check the amount and account details."
            )
            return
        }
        sending = true
        val m = method
        val acct = account.trim()
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                store.requestWithdraw(value, m, acct)
            }
            sending = false
            slideKey++
            if (ok) {
                if (saveAccount) {
                    prefs.edit().putString(payKey(m), acct).apply()
                }
                amount = ""
                toast("Withdrawal submitted.")
            } else {
                toast("Submit failed. Try again.")
            }
        }
    }

    val sorted = remember(txs) { txs.sortedByDescending { it.createdAt } }
    val receivedCount = sorted.count { it.type == "CREDIT" }
    val sentCount = sorted.count { it.type != "CREDIT" }
    val filtered = remember(sorted, filter) {
        when (filter) {
            "RECEIVED" -> sorted.filter { it.type == "CREDIT" }
            "SENT" -> sorted.filter { it.type != "CREDIT" }
            else -> sorted
        }
    }
    val groups = remember(filtered) { groupTransactions(filtered) }
    val methodLabel = PAY_METHODS.firstOrNull { it.id == method }?.label ?: method
    val accountHint = PAY_METHODS.firstOrNull { it.id == method }?.hint ?: "Account"

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showBdt = !showBdt }
                            .padding(20.dp)
                    ) {
                        Text(
                            text = "Available balance",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(
                            text = if (showBdt) "BDT " + fmtBdt(balance) else "$" + fmtUsd(balance),
                            fontSize = 34.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(
                            text = "Earned from approved orders",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(
                            text = "Request a withdrawal",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.size(4.dp))
                        Text(
                            text = "Reviewed before payment.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.size(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Amount",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = {
                                amount = String.format(Locale.US, "%.2f", balance)
                            }) {
                                Text("Max")
                            }
                        }
                        OutlinedTextField(
                            value = amount,
                            onValueChange = { amount = it },
                            placeholder = { Text("0.00") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.size(12.dp))
                        Text(
                            text = "Payout account",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.size(4.dp))
                        OutlinedTextField(
                            value = account,
                            onValueChange = { account = it },
                            placeholder = { Text(accountHint) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.size(12.dp))
                        Text(
                            text = "Method",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            for (m in PAY_METHODS) {
                                val selected = method == m.id
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (selected) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surface
                                        }
                                    ),
                                    border = if (selected) {
                                        androidx.compose.foundation.BorderStroke(
                                            1.dp,
                                            MaterialTheme.colorScheme.primary
                                        )
                                    } else {
                                        androidx.compose.foundation.BorderStroke(
                                            1.dp,
                                            MaterialTheme.colorScheme.outline
                                        )
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { method = m.id }
                                            .padding(vertical = 10.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Image(
                                            painter = painterResource(m.icon),
                                            contentDescription = m.label,
                                            modifier = Modifier.size(32.dp)
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.size(8.dp))
                        SsCheckbox(
                            checked = saveAccount,
                            onCheckedChange = { saveAccount = it },
                            label = "Save account for " + methodLabel,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.size(12.dp))
                        val amountValue = amount.toDoubleOrNull()
                        val amountOk = amountValue != null && amountValue > 0 && amountValue <= balance
                        val accountOk = validAccount(method, account)
                        SlideToConfirmButton(
                            label = "Slide to withdraw",
                            disabled = sending || !amountOk || !accountOk,
                            resetKey = slideKey,
                            onConfirm = { submit() }
                        )
                    }
                }
            }

            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Balance history",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = filter == "ALL",
                            onClick = { filter = "ALL" },
                            label = { Text("All (" + sorted.size + ")") }
                        )
                        FilterChip(
                            selected = filter == "RECEIVED",
                            onClick = { filter = "RECEIVED" },
                            label = { Text("Received (" + receivedCount + ")") }
                        )
                        FilterChip(
                            selected = filter == "SENT",
                            onClick = { filter = "SENT" },
                            label = { Text("Sent (" + sentCount + ")") }
                        )
                    }
                }
            }

            if (filtered.isEmpty()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No transactions yet.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                for (g in groups) {
                    item(key = "header_" + g.first + "_" + filter) {
                        Text(
                            text = g.first,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }
                    items(g.second, key = { it.id }) { tx ->
                        val isCredit = tx.type == "CREDIT"
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { detail = tx }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = tx.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.size(2.dp))
                                    val sub = if (!tx.detail.isNullOrEmpty()) {
                                        tx.detail + " | " + fmtDate(tx.createdAt)
                                    } else {
                                        fmtDate(tx.createdAt)
                                    }
                                    Text(
                                        text = sub,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Spacer(modifier = Modifier.size(8.dp))
                                Text(
                                    text = (if (isCredit) "+" else "-") + "$" + fmtUsd(tx.amount),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isCredit) AliveGreen else DeadRed
                                )
                            }
                        }
                    }
                }
            }
        }

        val d = detail
        if (d != null) {
            val isCredit = d.type == "CREDIT"
            AlertDialog(
                onDismissRequest = { detail = null },
                title = { Text("Transaction details") },
                text = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = (if (isCredit) "+" else "-") + "$" + fmtUsd(d.amount),
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isCredit) AliveGreen else DeadRed
                        )
                        Text(
                            text = if (isCredit) "Received" else "Sent",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.size(12.dp))
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "Reason",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = d.title,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Spacer(modifier = Modifier.size(4.dp))
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "Date",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = fmtDateTime(d.createdAt),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Spacer(modifier = Modifier.size(4.dp))
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "Balance after",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "$" + fmtUsd(d.balanceAfter),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (!d.detail.isNullOrEmpty()) {
                            Spacer(modifier = Modifier.size(4.dp))
                            Row(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = "Detail",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = d.detail,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                        Spacer(modifier = Modifier.size(8.dp))
                        Spacer(modifier = Modifier.height(1.dp))
                        Spacer(modifier = Modifier.size(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = d.id,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = {
                                clipboard.setText(AnnotatedString(d.id))
                                toast("Copied")
                            }) {
                                Text("Copy")
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { detail = null }) {
                        Text("Close")
                    }
                }
            )
        }
    }
}
