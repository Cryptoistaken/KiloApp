package net.typeblog.socks.ui.screens.sheet

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.typeblog.socks.R
import net.typeblog.socks.util.sheet.SheetFile
import net.typeblog.socks.util.sheet.SheetPreset
import net.typeblog.socks.util.sheet.SheetStore
import net.typeblog.socks.util.sheet.daysLeft

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SheetArchiveTab(
    onOpenArchived: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val appCtx = remember(context) { context.applicationContext }
    val store = remember(appCtx) { SheetStore.get(appCtx) }
    val scope = rememberCoroutineScope()
    val archive by store.archive.collectAsState()

    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    val selectionMode = selectedIds.isNotEmpty()

    var deleteTarget by remember { mutableStateOf<SheetFile?>(null) }
    var restoreBulk by remember { mutableStateOf(false) }
    var deleteBulk by remember { mutableStateOf(false) }
    var isList by remember { mutableStateOf(false) }

    fun io(block: suspend () -> Unit) {
        scope.launch { withContext(Dispatchers.IO) { block() } }
    }

    fun toggleSelect(id: String) {
        selectedIds = if (selectedIds.contains(id)) selectedIds - id else selectedIds + id
    }

    val sorted = remember(archive) { archive.sortedByDescending { it.deletedAt } }

    Box(modifier = modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (selectionMode) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val allIds = sorted.map { it.id }.toSet()
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
                    onClick = { if (selectedIds.isNotEmpty()) restoreBulk = true }
                ) {
                    Text("Restore")
                }
                TextButton(
                    onClick = { if (selectedIds.isNotEmpty()) deleteBulk = true }
                ) {
                    Text(
                        "Delete forever",
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
            Spacer(modifier = Modifier.size(4.dp))
        }

        if (sorted.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                EmptySheetState(
                    icon = R.drawable.ic_ss_empty_archive,
                    title = "No archived files.",
                    sub = "Archived files are kept here for 30 days."
                )
            }
        } else if (isList) {
            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(sorted, key = { it.id }) { f ->
                    SheetFileCard(
                        file = f,
                        selected = selectedIds.contains(f.id),
                        selectionMode = selectionMode,
                        onOpen = { onOpenArchived(f.id) },
                        onToggleSelect = { toggleSelect(f.id) },
                        list = true,
                        daysLeft = daysLeft(f.deletedAt),
                        onRestore = {
                            selectedIds = selectedIds - f.id
                            io { store.archiveFile(f.id, false) }
                            toast(appCtx, "File restored.")
                        },
                        onDeleteForever = { deleteTarget = f }
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
                items(sorted, key = { it.id }) { f ->
                    SheetFileCard(
                        file = f,
                        selected = selectedIds.contains(f.id),
                        selectionMode = selectionMode,
                        onOpen = { onOpenArchived(f.id) },
                        onToggleSelect = { toggleSelect(f.id) },
                        list = false,
                        daysLeft = daysLeft(f.deletedAt),
                        onRestore = {
                            selectedIds = selectedIds - f.id
                            io { store.archiveFile(f.id, false) }
                            toast(appCtx, "File restored.")
                        },
                        onDeleteForever = { deleteTarget = f }
                    )
                }
            }
        }
        if (!selectionMode) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ArchiveViewSwitch(selected = !isList, icon = R.drawable.ic_ss_view_grid, label = "Grid view", onClick = { isList = false })
                ArchiveViewSwitch(selected = isList, icon = R.drawable.ic_ss_view_list, label = "List view", onClick = { isList = true })
            }
        }
    }

    val dt = deleteTarget
    if (dt != null) {
        SheetConfirm(
            onDismiss = { deleteTarget = null },
            message = "Permanently delete this file? This cannot be undone.",
            actionText = "Delete forever",
            onConfirm = {
                deleteTarget = null
                selectedIds = selectedIds - dt.id
                io { store.deleteForever(dt.id) }
                toast(appCtx, "File deleted forever.")
            }
        )
    }

    if (restoreBulk) {
        val n = selectedIds.size
        SheetConfirm(
            onDismiss = { restoreBulk = false },
            message = "Restore " + n + " file" + (if (n != 1) "s" else "") + "?",
            actionText = "Restore",
            onConfirm = {
                val ids = selectedIds.toList()
                restoreBulk = false
                selectedIds = emptySet()
                io {
                    for (id in ids) store.archiveFile(id, false)
                }
                toast(appCtx, ids.size.toString() + " file" + (if (ids.size != 1) "s" else "") + " restored.")
            }
        )
    }

    if (deleteBulk) {
        val n = selectedIds.size
        SheetConfirm(
            onDismiss = { deleteBulk = false },
            message = "Permanently delete " + n + " file" + (if (n != 1) "s" else "") + "? This cannot be undone.",
            actionText = "Delete forever",
            onConfirm = {
                val ids = selectedIds.toList()
                deleteBulk = false
                selectedIds = emptySet()
                io {
                    for (id in ids) store.deleteForever(id)
                }
                toast(appCtx, ids.size.toString() + " file" + (if (ids.size != 1) "s" else "") + " deleted forever.")
            }
        )
    }
}

@Composable
private fun ArchiveViewSwitch(selected: Boolean, icon: Int, label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
            .background(if (selected) MaterialTheme.colorScheme.surface else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(interactionSource = androidx.compose.runtime.remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(7.dp),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(icon),
            contentDescription = label,
            modifier = Modifier.size(16.dp),
            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(
                if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
            )
        )
    }
}
