package net.typeblog.socks.ui.screens.sheet

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.AlertDialog
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

    fun io(block: suspend () -> Unit) {
        scope.launch { withContext(Dispatchers.IO) { block() } }
    }

    fun toggleSelect(id: String) {
        selectedIds = if (selectedIds.contains(id)) selectedIds - id else selectedIds + id
    }

    val sorted = remember(archive) { archive.sortedByDescending { it.deletedAt } }

    Column(modifier = modifier.fillMaxSize()) {
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
                    sub = "Kept here for 30 days."
                )
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
                    SheetArchiveCard(
                        file = f,
                        selected = selectedIds.contains(f.id),
                        selectionMode = selectionMode,
                        onOpen = { onOpenArchived(f.id) },
                        onToggleSelect = { toggleSelect(f.id) },
                        onRestore = {
                            selectedIds = selectedIds - f.id
                            io { store.archiveFile(f.id, false) }
                            toast(appCtx, "File restored.")
                        },
                        onDelete = { deleteTarget = f }
                    )
                }
            }
        }
    }

    val dt = deleteTarget
    if (dt != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete forever? Cannot undo.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteTarget = null
                        selectedIds = selectedIds - dt.id
                        io { store.deleteForever(dt.id) }
                        toast(appCtx, "File deleted forever.")
                    }
                ) {
                    Text("Delete forever", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (restoreBulk) {
        val n = selectedIds.size
        AlertDialog(
            onDismissRequest = { restoreBulk = false },
            title = {
                Text("Restore " + n + " file" + (if (n != 1) "s" else "") + "?")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val ids = selectedIds.toList()
                        restoreBulk = false
                        selectedIds = emptySet()
                        io {
                            for (id in ids) store.archiveFile(id, false)
                        }
                        toast(appCtx, ids.size.toString() + " file" + (if (ids.size != 1) "s" else "") + " restored.")
                    }
                ) {
                    Text("Restore")
                }
            },
            dismissButton = {
                TextButton(onClick = { restoreBulk = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (deleteBulk) {
        val n = selectedIds.size
        AlertDialog(
            onDismissRequest = { deleteBulk = false },
            title = {
                Text("Delete " + n + " file" + (if (n != 1) "s" else "") + " forever? Cannot undo.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val ids = selectedIds.toList()
                        deleteBulk = false
                        selectedIds = emptySet()
                        io {
                            for (id in ids) store.deleteForever(id)
                        }
                        toast(appCtx, ids.size.toString() + " file" + (if (ids.size != 1) "s" else "") + " deleted forever.")
                    }
                ) {
                    Text("Delete forever", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteBulk = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun SheetArchiveCard(
    file: SheetFile,
    selected: Boolean,
    selectionMode: Boolean,
    onOpen: () -> Unit,
    onToggleSelect: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.outlineVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { if (selectionMode) onToggleSelect() else onOpen() },
                    onLongClick = { onToggleSelect() }
                )
                .padding(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FileIconTile(preset = file.preset)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp)
                ) {
                    Text(
                        text = file.name,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val ts = fmtDate(if (file.updatedAt > 0) file.updatedAt else file.createdAt)
                    if (ts.isNotEmpty()) {
                        Text(
                            text = ts,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (selected) {
                    Image(
                        painter = painterResource(R.drawable.ic_ss_check),
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
                TypeBadgePill({
                    Image(
                        painter = painterResource(R.drawable.ic_ss_facebook),
                        contentDescription = null,
                        modifier = Modifier.size(10.dp)
                    )
                })
                Spacer(modifier = Modifier.width(4.dp))
                TypeBadgePill({ PasswordBadge(password = file.password) })
                if (!selectionMode) {
                    Box {
                        IconButton(
                            onClick = { menuOpen = true },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Image(
                                painter = painterResource(R.drawable.ic_ss_more),
                                contentDescription = "More actions",
                                modifier = Modifier.size(14.dp),
                                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }
                        androidx.compose.material3.DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false }
                        ) {
                            SheetMenuItem(
                                icon = R.drawable.ic_ss_square,
                                label = if (selected) "Deselect" else "Select",
                                onClick = {
                                    menuOpen = false
                                    onToggleSelect()
                                }
                            )
                            SheetMenuItem(
                                icon = R.drawable.ic_ss_restore,
                                label = "Restore",
                                onClick = {
                                    menuOpen = false
                                    onRestore()
                                }
                            )
                            SheetMenuItem(
                                icon = R.drawable.ic_ss_trash,
                                label = "Delete forever",
                                danger = true,
                                onClick = {
                                    menuOpen = false
                                    onDelete()
                                }
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.size(8.dp))
            Box(modifier = Modifier.fillMaxWidth()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth().padding(end = 52.dp)
                ) {
                    ArchiveIndicatorCell(
                        color = rowsIndicatorColor(),
                        label = file.rowCount.toString()
                    )
                    if (file.liveCount + file.deadCount > 0) {
                        ArchiveIndicatorCell(color = AliveGreen, label = file.liveCount.toString())
                    }
                    if (file.preset == SheetPreset.PAGE && file.pageCount > 0) {
                        ArchiveIndicatorCell(color = PageBlue, label = file.pageCount.toString())
                    }
                    if (file.liveCount + file.deadCount > 0) {
                        ArchiveIndicatorCell(color = DeadRed, label = file.deadCount.toString())
                    }
                    if (file.dupCount > 0) {
                        ArchiveIndicatorCell(color = DupYellow, label = file.dupCount.toString())
                    }
                }
                Text(
                    text = daysLeft(file.deletedAt).toString() + "d left",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 5.dp)
                )
            }
        }
    }
}

@Composable
private fun ArchiveIndicatorCell(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IndicatorSquare(color = color)
        Spacer(modifier = Modifier.width(3.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
