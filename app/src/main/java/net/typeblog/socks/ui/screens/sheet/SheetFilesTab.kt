package net.typeblog.socks.ui.screens.sheet

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.typeblog.socks.util.sheet.SheetCsv
import net.typeblog.socks.util.sheet.SheetDb
import net.typeblog.socks.util.sheet.SheetFile
import net.typeblog.socks.util.sheet.SheetStore

@Composable
fun SheetFilesTab(
    onOpenFile: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val appCtx = remember(context) { context.applicationContext }
    val store = remember(appCtx) { SheetStore.get(appCtx) }
    val scope = rememberCoroutineScope()
    val files by store.files.collectAsState()

    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    val selectionMode = selectedIds.isNotEmpty()
    var isList by rememberSaveable { mutableStateOf(false) }

    var createMenu by remember { mutableStateOf(false) }
    var typePick by remember { mutableStateOf<UploadDraft?>(null) }
    var pwAsk by remember { mutableStateOf<PwAsk?>(null) }
    var renameTarget by remember { mutableStateOf<SheetFile?>(null) }
    var renameText by remember { mutableStateOf("") }
    var archiveTarget by remember { mutableStateOf<SheetFile?>(null) }
    var archiveBulk by remember { mutableStateOf(false) }
    var downloadTarget by remember { mutableStateOf<SheetFile?>(null) }

    fun io(block: suspend () -> Unit) {
        scope.launch { withContext(Dispatchers.IO) { block() } }
    }

    val downloadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri: Uri? ->
        val t = downloadTarget
        downloadTarget = null
        if (uri != null && t != null) {
            scope.launch {
                val msg = withContext(Dispatchers.IO) {
                    try {
                        val db = SheetDb(appCtx)
                        val f = db.getFile(t.id) ?: return@withContext "File not found."
                        val rows = db.loadRows(t.id)
                        if (rows.none { it.isData(f.preset.columns) }) {
                            return@withContext "Please add content first."
                        }
                        appCtx.contentResolver.openOutputStream(uri)?.use { out ->
                            out.write(SheetCsv.build(f.preset.columns, rows).toByteArray(Charsets.UTF_8))
                        } ?: return@withContext "Download failed."
                        "Downloaded " + sanitizeFileName(f.name) + ".csv"
                    } catch (e: Exception) {
                        "Download failed."
                    }
                }
                toast(appCtx, msg)
            }
        }
    }

    val uploadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val draft = withContext(Dispatchers.IO) {
                try {
                    parseUpload(appCtx, uri)
                } catch (e: Exception) {
                    null
                }
            }
            if (draft == null) {
                toast(appCtx, "Import failed.")
            } else {
                typePick = draft
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        floatingActionButton = {
            FloatingActionButton(
                onClick = { createMenu = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Create file")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (selectionMode) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val allIds = files.map { it.id }.toSet()
                    val allSelected = selectedIds.isNotEmpty() && selectedIds.containsAll(allIds)
                    TextButton(
                        onClick = {
                            selectedIds = if (allSelected) emptySet() else allIds
                        }
                    ) {
                        Text(if (allSelected) "Unselect all" else "Select all")
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = { if (selectedIds.isNotEmpty()) archiveBulk = true }
                    ) {
                        Text(
                            "Move to archive",
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = selectedIds.size.toString() + " selected",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = { selectedIds = emptySet() }) {
                        Text("Clear")
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = { isList = false }) {
                        Text(
                            "Grid",
                            fontWeight = if (!isList) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                    TextButton(onClick = { isList = true }) {
                        Text(
                            "List",
                            fontWeight = if (isList) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            if (files.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    EmptySheetState(
                        title = "No files yet",
                        sub = "Tap + to create your first file."
                    )
                }
            } else if (isList) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(files, key = { it.id }) { f ->
                        SheetFileCard(
                            file = f,
                            selected = selectedIds.contains(f.id),
                            selectionMode = selectionMode,
                            onOpen = { onOpenFile(f.id) },
                            onToggleSelect = {
                                selectedIds = if (selectedIds.contains(f.id)) {
                                    selectedIds - f.id
                                } else {
                                    selectedIds + f.id
                                }
                            },
                            onDownload = {
                                downloadTarget = f
                                downloadLauncher.launch(sanitizeFileName(f.name) + ".csv")
                            },
                            onRename = {
                                renameTarget = f
                                renameText = f.name
                            },
                            onArchive = { archiveTarget = f }
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(files, key = { it.id }) { f ->
                        SheetFileCard(
                            file = f,
                            selected = selectedIds.contains(f.id),
                            selectionMode = selectionMode,
                            onOpen = { onOpenFile(f.id) },
                            onToggleSelect = {
                                selectedIds = if (selectedIds.contains(f.id)) {
                                    selectedIds - f.id
                                } else {
                                    selectedIds + f.id
                                }
                            },
                            onDownload = {
                                downloadTarget = f
                                downloadLauncher.launch(sanitizeFileName(f.name) + ".csv")
                            },
                            onRename = {
                                renameTarget = f
                                renameText = f.name
                            },
                            onArchive = { archiveTarget = f }
                        )
                    }
                }
            }
        }
    }

    if (createMenu) {
        CreateFileMenuDialog(
            onDismiss = { createMenu = false },
            onPickPreset = { p ->
                createMenu = false
                pwAsk = PwAsk(preset = p, upload = null)
            },
            onPickUpload = {
                createMenu = false
                uploadLauncher.launch("*/*")
            }
        )
    }

    val tp = typePick
    if (tp != null) {
        TypePickDialog(
            draft = tp,
            onDismiss = { typePick = null },
            onPick = { p ->
                typePick = null
                pwAsk = PwAsk(preset = p, upload = tp)
            }
        )
    }

    val ask = pwAsk
    if (ask != null) {
        PasswordPickDialog(
            loveFirst = ask.upload?.loveHint == true,
            onDismiss = {
                pwAsk = null
                if (ask.upload != null) typePick = null
            },
            onPick = { password ->
                pwAsk = null
                typePick = null
                val up = ask.upload
                scope.launch {
                    val msg = withContext(Dispatchers.IO) {
                        try {
                            if (up == null) {
                                store.createFile(ask.preset, password)
                                presetTitle(ask.preset) + " file created."
                            } else {
                                importDraft(appCtx, store, ask.preset, password, up)
                            }
                        } catch (e: Exception) {
                            if (up == null) "Unable to create file. Please try again."
                            else "Unable to import file. Please try again."
                        }
                    }
                    toast(appCtx, msg)
                }
            }
        )
    }

    val rt = renameTarget
    if (rt != null) {
        RenameFileDialog(
            value = renameText,
            onValueChange = { renameText = it },
            onConfirm = {
                val name = renameText.trim()
                if (name.isEmpty()) {
                    toast(appCtx, "Enter a file name.")
                    return@RenameFileDialog
                }
                renameTarget = null
                renameText = ""
                scope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        store.renameFile(rt.id, name)
                    }
                    toast(appCtx, if (ok) "File renamed." else "Unable to rename. Please try again.")
                }
            },
            onDismiss = {
                renameTarget = null
                renameText = ""
            }
        )
    }

    val at = archiveTarget
    if (at != null) {
        ArchiveSingleDialog(
            onDismiss = { archiveTarget = null },
            onConfirm = {
                archiveTarget = null
                selectedIds = selectedIds - at.id
                io { store.archiveFile(at.id, true) }
                toast(appCtx, "File moved to archive.")
            }
        )
    }

    if (archiveBulk) {
        ArchiveBulkDialog(
            count = selectedIds.size,
            onDismiss = { archiveBulk = false },
            onConfirm = {
                val ids = selectedIds.toList()
                archiveBulk = false
                selectedIds = emptySet()
                io {
                    for (id in ids) store.archiveFile(id, true)
                }
                toast(appCtx, ids.size.toString() + " file" + (if (ids.size > 1) "s" else "") + " moved to archive.")
            }
        )
    }
}
