package net.typeblog.socks

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.typeblog.socks.ui.components.UpdateDialog
import net.typeblog.socks.ui.navigation.AppNavigation
import net.typeblog.socks.ui.theme.KiloProxyTheme
import net.typeblog.socks.util.Constants.PREF_FLOATING_CONTROL
import net.typeblog.socks.util.Constants.PREF_SKIPPED_UPDATE_VERSION
import net.typeblog.socks.util.CrashLog
import net.typeblog.socks.util.CrashReportDialog
import net.typeblog.socks.util.UpdateChecker

class MainActivity : ComponentActivity() {
    companion object {
        /** Open the app straight on the split-tunneling apps list. */
        const val EXTRA_OPEN_SPLIT_APPS = "open_split_apps"
        /** Open the app straight on the SMS tab (bubble SMS long-press). */
        const val EXTRA_OPEN_SMS = "open_sms"
        /** Open the app straight on the Sheet tab (Sheet bubble with no file). */
        const val EXTRA_OPEN_SHEET = "open_sheet"
    }

    // Incremented whenever an intent asks for the apps list (bubble
    // refuse-to-connect); AppNavigation observes it and navigates once.
    var splitAppsRequest by mutableStateOf(0)
        private set
    // Same pattern for the SMS tab (bubble circle-menu SMS long-press).
    var smsRequest by mutableStateOf(0)
        private set
    // Same pattern for the Sheet tab (Sheet bubble with no file configured).
    var sheetRequest by mutableStateOf(0)
        private set

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startFloatingControlIfPersisted()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        startFloatingControlIfPersisted()
        if (intent?.getBooleanExtra(EXTRA_OPEN_SPLIT_APPS, false) == true) {
            splitAppsRequest++
        }
        if (intent?.getBooleanExtra(EXTRA_OPEN_SMS, false) == true) {
            smsRequest++
        }
        if (intent?.getBooleanExtra(EXTRA_OPEN_SHEET, false) == true) {
            sheetRequest++
        }

        setContent {
            val context = this@MainActivity
            var updatePrompt by remember { mutableStateOf<UpdateChecker.UpdateInfo?>(null) }
            var crashReport by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(Unit) {
                crashReport = withContext(Dispatchers.IO) { CrashLog.pending(context) }
            }

            // Proactive update check: on launch, look for a newer release and prompt
            // the user once per version (they can update or skip). Runs on a
            // background thread so it never blocks first frame.
            LaunchedEffect(Unit) {
                val info = withContext(Dispatchers.IO) { UpdateChecker.check() }
                if (info != null) {
                    val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                    if (info.versionCode > prefs.getInt(PREF_SKIPPED_UPDATE_VERSION, 0)) {
                        updatePrompt = info
                    }
                }
            }

            KiloProxyTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNavigation(splitAppsSignal = splitAppsRequest, smsSignal = smsRequest, sheetSignal = sheetRequest)
                }
                updatePrompt?.let { info ->
                    UpdateDialog(
                        info = info,
                        dismissLabel = "Skip",
                        onDismiss = {
                            updatePrompt = null
                            PreferenceManager.getDefaultSharedPreferences(context)
                                .edit()
                                .putInt(PREF_SKIPPED_UPDATE_VERSION, info.versionCode)
                                .apply()
                        }
                    )
                }
                crashReport?.let { report ->
                    CrashReportDialog(
                        text = report,
                        onDismiss = {
                            crashReport = null
                            CrashLog.clear(context)
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_SPLIT_APPS, false)) {
            splitAppsRequest++
        }
        if (intent.getBooleanExtra(EXTRA_OPEN_SMS, false)) {
            smsRequest++
        }
        if (intent.getBooleanExtra(EXTRA_OPEN_SHEET, false)) {
            sheetRequest++
        }
    }

    private fun startFloatingControlIfPersisted() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        if (!prefs.getBoolean(PREF_FLOATING_CONTROL, false)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        FloatingControlService.start(this)
    }
}
