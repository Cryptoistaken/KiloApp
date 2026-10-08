package net.typeblog.socks.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.typeblog.socks.util.SmsAuth
import net.typeblog.socks.util.SmsWatcher

/**
 * Telegram login gate for the SMS tab, mirroring the KiloSMS admin panel:
 * one tap opens the bot at start=login_<did>, then we poll until the bot
 * links the session. The session cookie is the tab's only credential.
 */
@Composable
internal fun SmsLoginGate() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf("") }

    fun startLogin() {
        if (checking) return
        checking = true
        error = ""
        scope.launch {
            val username = withContext(Dispatchers.IO) { SmsAuth.botUsername() }
            if (username.isNullOrEmpty()) {
                checking = false
                error = "Could not reach server"
                return@launch
            }
            val did = SmsAuth.newDeviceId()
            try {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(SmsAuth.loginUrl(did, username)))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {
                checking = false
                error = "No browser found"
                return@launch
            }
            var cookie: String? = null
            for (i in 1..30) {
                delay(2000)
                cookie = withContext(Dispatchers.IO) { SmsAuth.pollDevice(did) }
                if (cookie != null) break
            }
            if (cookie != null) {
                SmsAuth.saveSession(cookie)
                val admin = withContext(Dispatchers.IO) { SmsAuth.me() }
                if (admin == null) {
                    SmsAuth.clear()
                    error = "Login rejected. Ask admin for access."
                } else {
                    SmsWatcher.isAdmin = admin
                    SmsWatcher.loggedIn = true
                    SmsWatcher.refreshNow()
                }
            } else {
                error = "Not yet verified. Tap Login in the bot and try again."
            }
            checking = false
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "SMS",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Login with Telegram to get numbers.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = ::startLogin, enabled = !checking) {
            Text(if (checking) "Waiting for Telegram..." else "Login with Telegram")
        }
        if (error.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
