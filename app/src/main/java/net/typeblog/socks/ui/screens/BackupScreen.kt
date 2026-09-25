package net.typeblog.socks.ui.screens

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import net.typeblog.socks.R
import net.typeblog.socks.ui.components.ProtonSwitch
import net.typeblog.socks.ui.components.SettingsItem
import net.typeblog.socks.ui.components.rememberPref
import net.typeblog.socks.ui.screens.sheet.toast
import net.typeblog.socks.util.Constants.PREF_BACKUP_DIR
import net.typeblog.socks.util.Constants.PREF_BACKUP_ENABLED
import net.typeblog.socks.util.sheet.SheetBackup
import net.typeblog.socks.util.sheet.SheetStore

/**
 * Backup page. The mirror itself is written by SheetBackup off the back of
 * every store refresh; this page is where the user sees that it happened,
 * forces one, points it at a folder of their choosing, or puts it back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    val scope = rememberCoroutineScope()

    var auto by rememberPref(prefs, PREF_BACKUP_ENABLED) {
        it.getBoolean(PREF_BACKUP_ENABLED, true)
    }
    var folder by rememberPref(prefs, PREF_BACKUP_DIR) {
        it.getString(PREF_BACKUP_DIR, "") ?: ""
    }
    var busy by remember { mutableStateOf(false) }
    // Re-read when the page is entered, so copies taken from a destructive
    // action elsewhere in the app are listed by the time the user looks.
    var snapshots by remember { mutableStateOf(SheetBackup.listSnapshots(context)) }
    LaunchedEffect(Unit) { snapshots = SheetBackup.listSnapshots(context) }
    var lastAt by remember { mutableStateOf(SheetBackup.lastAt(context)) }
    var lastSize by remember { mutableStateOf(SheetBackup.lastSize(context)) }
    var lastError by remember { mutableStateOf(SheetBackup.lastError(context)) }
    // A restore is staged first: the file is parsed and counted before the
    // user is asked, so the confirmation can say what it will replace.
    var staged by remember { mutableStateOf<ByteArray?>(null) }
    var stagedCounts by remember { mutableStateOf<Triple<Int, Int, Int>?>(null) }

    // Reads a local generation and stages it behind the same confirmation the
    // file picker uses, so every restore path reports what it will replace.
    fun stageLocal(read: (Context) -> String?, missing: String) {
        scope.launch {
            busy = true
            val local = withContext(Dispatchers.IO) { read(context) }
            val bytes = local?.toByteArray(Charsets.UTF_8)
            val counts = if (bytes == null) null else withContext(Dispatchers.IO) {
                try {
                    val s = SheetBackup.parse(bytes)
                    Triple(s.files.size, s.rowCount, s.profileCount)
                } catch (_: Exception) {
                    null
                }
            }
            busy = false
            when {
                bytes == null -> toast(context, missing)
                counts == null -> toast(context, "That copy could not be read.")
                else -> {
                    staged = bytes
                    stagedCounts = counts
                }
            }
        }
    }

    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) {
            // Some providers grant only one flag; the write below is the real
            // test, so a refusal here is not worth failing the flow for.
        }
        prefs.edit().putString(PREF_BACKUP_DIR, uri.toString()).apply()
        folder = uri.toString()
    }

    val loadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            val stagedResult = withContext(Dispatchers.IO) {
                try {
                    val bytes = context.contentResolver.openInputStream(uri)
                        ?.use { it.readBytes() } ?: return@withContext null
                    // SheetBackup.parse sniffs JSON vs workbook by content.
                    val snap = SheetBackup.parse(bytes)
                    bytes to Triple(snap.files.size, snap.rowCount, snap.profileCount)
                } catch (_: Exception) {
                    null
                }
            }
            busy = false
            if (stagedResult == null) {
                toast(context, "That file is not a KiloApp backup.")
            } else {
                staged = stagedResult.first
                stagedCounts = stagedResult.second
            }
        }
    }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("Backup") },
                windowInsets = WindowInsets(0),
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
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
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
        ) {
            item {
                SectionTitle(text = "Backup")
                SettingsItem(
                    icon = painterResource(R.drawable.ic_ss_download),
                    label = "Back up now",
                    description = if (busy) "Working" else "Write a fresh copy to Downloads",
                    showChevron = false,
                    enabled = !busy,
                    onClick = {
                        scope.launch {
                            busy = true
                            val written = withContext(Dispatchers.IO) { SheetBackup.backupNow(context) }
                            busy = false
                            lastAt = SheetBackup.lastAt(context)
                            lastSize = SheetBackup.lastSize(context)
                            lastError = SheetBackup.lastError(context)
                            toast(
                                context,
                                if (written > 0) "Backup saved." else "Backup failed."
                            )
                        }
                    }
                )
                SettingsItem(
                    icon = painterResource(R.drawable.lucide_rotate_cw),
                    label = "Auto-backup",
                    description = if (auto) "On" else "Off",
                    showChevron = false,
                    trailing = {
                        ProtonSwitch(
                            checked = auto,
                            onCheckedChange = { on ->
                                auto = on
                                prefs.edit().putBoolean(PREF_BACKUP_ENABLED, on).apply()
                            }
                        )
                    }
                )
                SettingsItem(
                    icon = painterResource(R.drawable.ic_ss_archive_idle),
                    label = "Backup folder",
                    description = folderLabel(folder),
                    showChevron = true,
                    onClick = { folderLauncher.launch(null) }
                )
                SettingsItem(
                    icon = painterResource(R.drawable.lucide_check),
                    label = "Last backup",
                    description = lastBackupLabel(lastAt, lastSize, lastError),
                    showChevron = false
                )
            }

            item {
                SectionTitle(text = "Restore")
                SettingsItem(
                    icon = painterResource(R.drawable.ic_ss_upload),
                    label = "Load backup",
                    description = "Replace everything from a .json or .xlsx backup",
                    showChevron = false,
                    enabled = !busy,
                    onClick = { loadLauncher.launch(arrayOf("application/json", "*/*")) }
                )
                SettingsItem(
                    icon = painterResource(R.drawable.lucide_rotate_cw),
                    label = "Restore last saved copy",
                    description = "The copy this app kept on the device",
                    showChevron = false,
                    enabled = !busy,
                    onClick = { stageLocal(SheetBackup::localCurrent, "No saved copy on this device.") }
                )
                SettingsItem(
                    icon = painterResource(R.drawable.lucide_server),
                    label = "Restore previous copy",
                    description = "The generation before the last one",
                    showChevron = false,
                    enabled = !busy,
                    onClick = {
                        stageLocal(SheetBackup::localPrevious, "No previous copy on this device.")
                    }
                )
            }

            if (snapshots.isNotEmpty()) {
                item {
                    SectionTitle(text = "Saved copies")
                    for (s in snapshots) {
                        SettingsItem(
                            icon = painterResource(R.drawable.ic_ss_archive_idle),
                            label = "Before ${s.label}",
                            description = "${timeLabel(s.at)}, ${sizeText(s.size)}",
                            showChevron = false,
                            enabled = !busy,
                            onClick = {
                                val raw = SheetBackup.readSnapshot(s)
                                if (raw == null) {
                                    toast(context, "That copy could not be read.")
                                } else {
                                    scope.launch {
                                        busy = true
                                        val bytes = raw.toByteArray(Charsets.UTF_8)
                                        val counts = withContext(Dispatchers.IO) {
                                            try {
                                                val snap = SheetBackup.parse(bytes)
                                                Triple(snap.files.size, snap.rowCount, snap.profileCount)
                                            } catch (_: Exception) {
                                                null
                                            }
                                        }
                                        busy = false
                                        if (counts == null) {
                                            toast(context, "That copy could not be read.")
                                        } else {
                                            staged = bytes
                                            stagedCounts = counts
                                        }
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    staged?.let { raw ->
        val counts = stagedCounts
        AlertDialog(
            onDismissRequest = { staged = null },
            title = { Text("Replace all data?") },
            text = {
                Text(
                    "This replaces every file, row and profile setting currently " +
                            "in the app with ${counts?.first ?: 0} files, " +
                            "${counts?.second ?: 0} rows and ${counts?.third ?: 0} " +
                            "profile settings from the backup. It cannot be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    staged = null
                    scope.launch {
                        busy = true
                        val ok = withContext(Dispatchers.IO) {
                            try {
                                SheetBackup.restore(context, raw)
                                true
                            } catch (_: Exception) {
                                false
                            }
                        }
                        busy = false
                        if (ok) {
                            // Every cached view in the store predates the
                            // tables we just replaced.
                            SheetStore.get(context).onBackupRestored()
                            toast(context, "Backup restored.")
                        } else {
                            toast(context, "That backup could not be restored.")
                        }
                    }
                }) { Text("Replace") }
            },
            dismissButton = {
                TextButton(onClick = { staged = null }) { Text("Cancel") }
            }
        )
    }
}

private fun folderLabel(uri: String): String = when {
    uri.isBlank() -> "Downloads (default). Pick a folder for cloud or SD card"
    else -> uri.substringAfterLast('/').substringAfterLast(':').ifBlank { "Folder set" }
}

private fun lastBackupLabel(at: Long, size: Long, error: String?): String {
    if (error != null) return "Failed: $error"
    if (at <= 0L) return "Not backed up yet"
    return "${timeLabel(at)}, ${sizeText(size)}"
}

private fun timeLabel(at: Long): String =
    SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(at))

private fun sizeText(size: Long): String =
    if (size >= 1024) "${size / 1024} KB" else "$size B"

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp)
    )
}
