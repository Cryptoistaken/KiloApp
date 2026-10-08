package net.typeblog.socks.ui.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.api.identity.Identity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import net.typeblog.socks.ui.components.BackupConfirmDialog
import net.typeblog.socks.ui.components.ProtonSwitch
import net.typeblog.socks.ui.components.SettingsItem
import net.typeblog.socks.ui.components.StagedBackup
import net.typeblog.socks.ui.components.rememberPref
import net.typeblog.socks.ui.screens.sheet.toast
import net.typeblog.socks.util.Constants.PREF_BACKUP_DIR
import net.typeblog.socks.util.Constants.PREF_BACKUP_ENABLED
import net.typeblog.socks.util.Constants.PREF_DRIVE_ENABLED
import net.typeblog.socks.util.sheet.DriveSync
import net.typeblog.socks.util.DocNames
import net.typeblog.socks.util.SmsWatcher
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

    // Collected, not read once: the store builds its lists in an async
    // refresh, so store.files.value is still empty on the first read and the
    // dialog would report "now 0" against a real current state.
    val store = SheetStore.get(context)
    val liveFiles by store.files.collectAsStateWithLifecycle()
    val liveArchive by store.archive.collectAsStateWithLifecycle()
    val liveTxs by store.txs.collectAsStateWithLifecycle()

    var auto by rememberPref(prefs, PREF_BACKUP_ENABLED) {
        it.getBoolean(PREF_BACKUP_ENABLED, true)
    }
    var folder by rememberPref(prefs, PREF_BACKUP_DIR) {
        it.getString(PREF_BACKUP_DIR, "") ?: ""
    }
    var busy by remember { mutableStateOf(false) }
    // Sign-in has its own flag so the row answers the tap synchronously and
    // keeps its own "Signing in..." state apart from backup's busy.
    var signingIn by remember { mutableStateOf(false) }
    // Re-read when the page is entered, so copies taken from a destructive
    // action elsewhere in the app are listed by the time the user looks.
    var snapshots by remember { mutableStateOf(SheetBackup.listSnapshots(context)) }
    // Lightweight flags for the expandable device-copy row: counts only,
    // bytes load on tap. Refreshed with the snapshots after every backup.
    var hasCurrent by remember { mutableStateOf(SheetBackup.hasLocalCurrent(context)) }
    var hasPrevious by remember { mutableStateOf(SheetBackup.hasLocalPrevious(context)) }
    var deviceExpanded by remember { mutableStateOf(false) }
    fun refreshLocalCopies() {
        snapshots = SheetBackup.listSnapshots(context)
        hasCurrent = SheetBackup.hasLocalCurrent(context)
        hasPrevious = SheetBackup.hasLocalPrevious(context)
    }
    LaunchedEffect(Unit) { refreshLocalCopies() }
    var lastAt by remember { mutableStateOf(SheetBackup.lastAt(context)) }
    var lastSize by remember { mutableStateOf(SheetBackup.lastSize(context)) }
    var lastError by remember { mutableStateOf(SheetBackup.lastError(context)) }
    // Google Drive: account + last sync, re-read after every Drive action
    // because the push runs inside backupNow, not in this composable.
    var driveAccount by remember { mutableStateOf(DriveSync.account(context)) }
    var driveLastAt by remember { mutableStateOf(DriveSync.lastAt(context)) }
    var driveLastError by remember { mutableStateOf(DriveSync.lastError(context)) }
    var driveAuto by rememberPref(prefs, PREF_DRIVE_ENABLED) {
        it.getBoolean(PREF_DRIVE_ENABLED, true)
    }
    val activity = context as? Activity

    fun refreshDrive() {
        driveAccount = DriveSync.account(context)
        driveLastAt = DriveSync.lastAt(context)
        driveLastError = DriveSync.lastError(context)
    }
    // A restore is staged first: the file is parsed and counted, and what
    // the app holds right now is captured alongside, before anyone is asked.
    var staged by remember { mutableStateOf<StagedBackup?>(null) }
    // Consent continuation: when Google answers authorize with "show the
    // consent screen", the flow pauses here and resumes after it returns OK.
    var retryAfterConsent by remember { mutableStateOf<(suspend (String) -> Unit)?>(null) }

    // Pairs the bytes with the current state, so the dialog can show what
    // replacing would gain as well as what it would drop.
    fun stage(
        bytes: ByteArray,
        source: String,
        sum: net.typeblog.socks.util.sheet.SheetBackup.BackupSummary
    ): StagedBackup {
        val live = liveFiles + liveArchive
        return StagedBackup(
            bytes = bytes,
            source = source,
            takenAt = sum.at.takeIf { it > 0L },
            fileCount = sum.fileCount,
            rowCount = sum.rowCount,
            checkCount = sum.checkCount,
            reqCount = sum.reqCount,
            styleCount = sum.styleCount,
            hiddenCount = sum.hiddenCount,
            txCount = sum.txCount,
            profileCount = sum.profileCount,
            balance = sum.balance,
            currentFiles = live.size,
            currentRows = live.sumOf { it.rowCount },
            currentTxCount = liveTxs.size
        )
    }

    // Reads a local generation and stages it behind the same confirmation the
    // file picker uses, so every restore path reports what it will replace.
    fun stageLocal(read: (Context) -> ByteArray?, missing: String, source: String) {
        scope.launch {
            busy = true
            val bytes = withContext(Dispatchers.IO) { read(context) }
            val sum = if (bytes == null) null else withContext(Dispatchers.IO) {
                try {
                    SheetBackup.summarize(context, bytes)
                } catch (_: Exception) {
                    null
                }
            }
            busy = false
            when {
                bytes == null -> toast(context, missing)
                sum == null -> toast(context, "That copy could not be read.")
                else -> staged = stage(bytes, source, sum)
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
                    val name = DocNames.display(context, uri, "the file you picked")
                    Triple(bytes, SheetBackup.summarize(context, bytes), name)
                } catch (_: Exception) {
                    null
                }
            }
            busy = false
            if (stagedResult == null) {
                toast(context, "That file is not a KiloApp backup.")
            } else {
                staged = stage(stagedResult.first, stagedResult.third, stagedResult.second)
            }
        }
    }

    val resolveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { res ->
        val retry = retryAfterConsent
        retryAfterConsent = null
        if (res.resultCode == Activity.RESULT_OK && retry != null && res.data != null) {
            scope.launch {
                busy = true
                try {
                    // Official flow: the token comes back in the result
                    // intent itself — no second authorize call needed.
                    val authRes = Identity.getAuthorizationClient(context)
                        .getAuthorizationResultFromIntent(res.data)
                    retry(authRes.accessToken ?: throw IllegalStateException("Empty access token"))
                } catch (e: Exception) {
                    DriveSync.setLastError(context, DriveSync.failureReason(e))
                    toast(context, "Drive failed.")
                }
                busy = false
                refreshDrive()
            }
        } else {
            // No usable grant: drop the account sign-in saved before consent,
            // or the page would show a signed-in state with a dead token.
            scope.launch {
                withContext(Dispatchers.IO) { DriveSync.signOut(context) }
                refreshDrive()
                toast(context, "Drive permission declined.")
            }
        }
    }

    // Runs block with a Drive token, pausing for Google's consent screen
    // when asked and resuming after it returns OK. Used by sign-in and
    // restore-from-Drive.
    suspend fun withDriveToken(act: Activity, block: suspend (String) -> Unit) {
        try {
            block(DriveSync.authorize(act))
        } catch (e: DriveSync.DriveResolutionRequired) {
            retryAfterConsent = block
            resolveLauncher.launch(IntentSenderRequest.Builder(e.resolution.intentSender).build())
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
                // Status card: the one-glance state. Headline shows backed up /
                // failed / never, detail shows date + size, Drive line shows
                // sync state. All read-only; the button below is the action.
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    shape = RoundedCornerShape(16.dp),
                    tonalElevation = 1.dp
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        val failed = lastError != null
                        val never = !failed && lastAt <= 0L
                        Text(
                            text = when {
                                failed -> "Backup failed"
                                never -> "Not backed up yet"
                                else -> "Backed up"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = when {
                                failed -> MaterialTheme.colorScheme.error
                                never -> MaterialTheme.colorScheme.onSurfaceVariant
                                else -> MaterialTheme.colorScheme.onSurface
                            }
                        )
                        if (!never) {
                            Text(
                                text = if (failed) "Failed: $lastError"
                                else "${timeLabel(lastAt)}, ${sizeText(lastSize)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                        if (DriveSync.configured() && driveAccount != null) {
                            val driveLine = if (driveLastError != null) "Drive sync failed"
                            else if (driveLastAt > 0L) "Also on Drive - ${timeLabel(driveLastAt)}"
                            else null
                            if (driveLine != null) {
                                Text(
                                    text = driveLine,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = {
                                scope.launch {
                                    busy = true
                                    val written = withContext(Dispatchers.IO) { SheetBackup.backupNow(context) }
                                    busy = false
                                    lastAt = SheetBackup.lastAt(context)
                                    lastSize = SheetBackup.lastSize(context)
                                    lastError = SheetBackup.lastError(context)
                                    withContext(Dispatchers.IO) { refreshLocalCopies() }
                                    refreshDrive()
                                    toast(
                                        context,
                                        if (written > 0) "Backup saved." else "Backup failed."
                                    )
                                }
                            },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (busy) "Backing up..." else "Back up now")
                        }
                    }
                }
            }
            item {
                SectionTitle(text = "Backup settings")
                SettingsItem(
                    icon = painterResource(R.drawable.lucide_rotate_cw),
                    label = "Auto-backup",
                    description = if (auto) "On, backs up after every change" else "Off, back up manually",
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
            }

            // Hidden until a Web client ID is baked in: without it sign-in
            // can never succeed, so the section would only confuse.
            if (DriveSync.configured()) {
                item {
                    SectionTitle(text = "Google Drive")
                    if (driveAccount == null) {
                        SettingsItem(
                            icon = painterResource(R.drawable.ic_name_person),
                            label = "Sign in with Google",
                            description = when {
                                signingIn -> "Signing in..."
                                driveLastError != null -> driveLastError
                                else -> "Back up to your Google Drive"
                            },
                            showChevron = false,
                            enabled = !busy && !signingIn,
                            onClick = {
                                val act = activity
                                if (act == null) {
                                    toast(context, "Open the app to sign in.")
                                } else {
                                    // Set synchronously: if Play-services hangs
                                    // (documented no-sheet hang), the row still
                                    // answers the tap instead of looking dead.
                                    signingIn = true
                                    scope.launch {
                                        busy = true
                                        try {
                                            val who = DriveSync.signIn(act)
                                            signingIn = false
                                            withDriveToken(act) {
                                                val written = withContext(Dispatchers.IO) {
                                                    SheetBackup.backupNow(context)
                                                }
                                                lastAt = SheetBackup.lastAt(context)
                                                lastSize = SheetBackup.lastSize(context)
                                                lastError = SheetBackup.lastError(context)
                                                refreshDrive()
                                                toast(
                                                    context,
                                                    if (written > 0) "Signed in as " + who.email
                                                    else "Signed in. Backup failed."
                                                )
                                            }
                                        } catch (e: Exception) {
                                            signingIn = false
                                            DriveSync.setLastError(context, DriveSync.failureReason(e))
                                            refreshDrive()
                                            toast(context, "Sign in failed.")
                                        }
                                        busy = false
                                    }
                                }
                            }
                        )
                    } else {
                        SettingsItem(
                            icon = painterResource(R.drawable.ic_name_person),
                            label = driveAccount ?: "",
                            description = if (driveLastError != null) "Failed: $driveLastError"
                            else if (driveLastAt > 0L) "Synced ${timeLabel(driveLastAt)}"
                            else "Not synced yet",
                            showChevron = false
                        )
                        SettingsItem(
                            icon = painterResource(R.drawable.lucide_rotate_cw),
                            label = "Drive auto-sync",
                            description = if (driveAuto) "On" else "Off",
                            showChevron = false,
                            trailing = {
                                ProtonSwitch(
                                    checked = driveAuto,
                                    onCheckedChange = { on ->
                                        driveAuto = on
                                        prefs.edit().putBoolean(PREF_DRIVE_ENABLED, on).apply()
                                    }
                                )
                            }
                        )
                        SettingsItem(
                            icon = painterResource(R.drawable.ic_ss_upload),
                            label = "Sync now",
                            description = "Write a fresh copy to Drive",
                            showChevron = false,
                            enabled = !busy,
                            onClick = {
                                scope.launch {
                                    busy = true
                                    val written = withContext(Dispatchers.IO) {
                                        SheetBackup.backupNow(context)
                                    }
                                    busy = false
                                    lastAt = SheetBackup.lastAt(context)
                                    lastSize = SheetBackup.lastSize(context)
                                    lastError = SheetBackup.lastError(context)
                                    withContext(Dispatchers.IO) { refreshLocalCopies() }
                                    refreshDrive()
                                    toast(
                                        context,
                                        when {
                                            written <= 0 -> "Backup failed."
                                            DriveSync.lastError(context) != null -> "Saved locally. Drive failed."
                                            else -> "Synced to Drive."
                                        }
                                    )
                                }
                            }
                        )
                        SettingsItem(
                            icon = painterResource(R.drawable.ic_ss_restore),
                            label = "Restore from Drive",
                            description = "Replace everything with your Drive copy",
                            showChevron = false,
                            enabled = !busy,
                            onClick = {
                                val act = activity
                                if (act == null) {
                                    toast(context, "Open the app to restore.")
                                } else {
                                    scope.launch {
                                        busy = true
                                        try {
                                            withDriveToken(act) { token ->
                                                val bytes = withContext(Dispatchers.IO) {
                                                    val folders = DriveSync.cachedFolders(context)
                                                        ?: DriveSync.ensureFolders(context, token)
                                                    DriveSync.pullBackupDb(token, folders)
                                                }
                                                val sum = try {
                                                    bytes?.let { SheetBackup.summarize(context, it) }
                                                } catch (_: Exception) {
                                                    null
                                                }
                                                if (bytes == null || sum == null) {
                                                    toast(context, "No Drive backup yet.")
                                                } else {
                                                    staged = stage(bytes, "Google Drive", sum)
                                                }
                                            }
                                        } catch (e: Exception) {
                                            DriveSync.setLastError(context, DriveSync.failureReason(e))
                                            refreshDrive()
                                            toast(context, "Could not reach Drive.")
                                        }
                                        busy = false
                                    }
                                }
                            }
                        )
                        SettingsItem(
                            icon = painterResource(R.drawable.ic_exit_duotone),
                            label = "Sign out",
                            description = "Stop syncing this device",
                            showChevron = false,
                            enabled = !busy,
                            iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                            onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) { DriveSync.signOut(context) }
                                    refreshDrive()
                                    toast(context, "Signed out.")
                                }
                            }
                        )
                    }
                }
            }

            item {
                SectionTitle(text = "Restore")
                SettingsItem(
                    icon = painterResource(R.drawable.ic_ss_upload),
                    label = "Restore from file",
                    description = "Pick a backup.db file to replace everything",
                    showChevron = false,
                    enabled = !busy,
                    onClick = { loadLauncher.launch(arrayOf("*/*")) }
                )
                val deviceCount =
                    (if (hasCurrent) 1 else 0) + (if (hasPrevious) 1 else 0) + snapshots.size
                if (deviceCount > 0) {
                    SettingsItem(
                        icon = painterResource(R.drawable.lucide_rotate_cw),
                        label = "Restore a device copy",
                        description = "$deviceCount copies on this device",
                        showChevron = true,
                        enabled = !busy,
                        onClick = { deviceExpanded = !deviceExpanded }
                    )
                }
                if (deviceCount > 0 && deviceExpanded) {
                    if (hasCurrent) {
                        SettingsItem(
                            icon = painterResource(R.drawable.lucide_rotate_cw),
                            label = "Last saved copy",
                            description = "The copy this app kept on the device",
                            showChevron = false,
                            enabled = !busy,
                            modifier = Modifier.padding(start = 16.dp),
                            onClick = {
                                stageLocal(
                                    SheetBackup::localCurrent,
                                    "No saved copy on this device.",
                                    "The copy this app kept on this device"
                                )
                            }
                        )
                    }
                    for (s in snapshots) {
                        SettingsItem(
                            icon = painterResource(R.drawable.ic_ss_archive_idle),
                            label = "Before ${s.label}",
                            description = "${timeLabel(s.at)}, ${sizeText(s.size)}",
                            showChevron = false,
                            enabled = !busy,
                            modifier = Modifier.padding(start = 16.dp),
                            onClick = {
                                val bytes = SheetBackup.readSnapshot(s)
                                if (bytes == null) {
                                    toast(context, "That copy could not be read.")
                                } else {
                                    scope.launch {
                                        busy = true
                                        val sum = withContext(Dispatchers.IO) {
                                            try {
                                                SheetBackup.summarize(context, bytes)
                                            } catch (_: Exception) {
                                                null
                                            }
                                        }
                                        busy = false
                                        if (sum == null) {
                                            toast(context, "That copy could not be read.")
                                        } else {
                                            staged = stage(bytes, "Saved before ${s.label}", sum)
                                        }
                                    }
                                }
                            }
                        )
                    }
                    if (hasPrevious) {
                        SettingsItem(
                            icon = painterResource(R.drawable.lucide_server),
                            label = "Previous copy",
                            description = "The generation before the last one",
                            showChevron = false,
                            enabled = !busy,
                            modifier = Modifier.padding(start = 16.dp),
                            onClick = {
                                stageLocal(
                                    SheetBackup::localPrevious,
                                    "No previous copy on this device.",
                                    "The previous copy on this device"
                                )
                            }
                        )
                    }
                }
            }
        }
    }

    staged?.let { ready ->
        BackupConfirmDialog(
            staged = ready,
            onConfirm = {
                val raw = ready.bytes
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
                        // Every cached view in the store predates
                        // the tables we just replaced.
                        SheetStore.get(context).onBackupRestored()
                        SmsWatcher.reloadAfterRestore()
                        toast(context, "Backup restored.")
                    } else {
                        toast(context, "That backup could not be restored.")
                    }
                }
            },
            onDismiss = { staged = null }
        )
    }
}

private fun folderLabel(uri: String): String = when {
    uri.isBlank() -> "Downloads (default). Pick a folder for cloud or SD card"
    else -> uri.substringAfterLast('/').substringAfterLast(':').ifBlank { "Folder set" }
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
