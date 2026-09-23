package net.typeblog.socks.ui.screens.sheet

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.typeblog.socks.R
import net.typeblog.socks.util.sheet.SheetFile
import net.typeblog.socks.util.sheet.SheetPreset

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun SheetFileCard(
    file: SheetFile,
    selected: Boolean,
    selectionMode: Boolean,
    onOpen: () -> Unit,
    onToggleSelect: () -> Unit,
    onDownload: () -> Unit = {},
    onSendCopy: () -> Unit = {},
    onRename: () -> Unit = {},
    onArchive: () -> Unit = {},
    list: Boolean = false,
    daysLeft: Int? = null,
    onRestore: (() -> Unit)? = null,
    onDeleteForever: (() -> Unit)? = null
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
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { if (selectionMode) onToggleSelect() else onOpen() },
                    onLongClick = { onToggleSelect() }
                )
                .padding(14.dp)
        ) {
            if (list) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(end = 28.dp)
                ) {
                    FileIconTile(preset = file.preset, tileDp = 36)
                    Column(modifier = Modifier.widthIn(min = 80.dp).weight(1f)) {
                        Text(
                            text = file.name,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val ts = fmtDate(if (file.updatedAt > 0) file.updatedAt else file.createdAt)
                        if (ts.isNotEmpty()) {
                            Text(text = ts, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, softWrap = false)
                        }
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        FileIndicators(file)
                    }
                    TypeBadgePill({
                        Image(painter = painterResource(R.drawable.ic_ss_facebook), contentDescription = null, modifier = Modifier.size(10.dp))
                    })
                    TypeBadgePill({ PasswordBadge(password = file.password) })
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    FileIconTile(preset = file.preset)
                    Spacer(modifier = Modifier.size(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = file.name,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        TypeBadgePill({
                            Image(painter = painterResource(R.drawable.ic_ss_facebook), contentDescription = null, modifier = Modifier.size(10.dp))
                        })
                        Spacer(modifier = Modifier.width(4.dp))
                        TypeBadgePill({ PasswordBadge(password = file.password) })
                    }
                    val ts = fmtDate(if (file.updatedAt > 0) file.updatedAt else file.createdAt)
                    if (ts.isNotEmpty()) {
                        Text(text = ts, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, softWrap = false, modifier = Modifier.padding(top = 1.dp))
                    }
                    Spacer(modifier = Modifier.size(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        FileIndicators(file)
                    }
                }
            }
            if (selectionMode) {
                SelectCheckBox(
                    selected = selected,
                    modifier = Modifier.align(Alignment.TopEnd)
                )
            }
            if (!selectionMode) {
                Box(modifier = Modifier.align(Alignment.TopEnd)) {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(24.dp)) {
                        Image(
                            painter = painterResource(R.drawable.ic_ss_more),
                            contentDescription = "More actions",
                            modifier = Modifier.size(14.dp),
                            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(MaterialTheme.colorScheme.onSurfaceVariant)
                        )
                    }
                    androidx.compose.material3.DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        SheetMenuItem(icon = R.drawable.ic_ss_cursor, label = if (selected) "Deselect" else "Select", onClick = { menuOpen = false; onToggleSelect() })
                        if (onRestore != null) {
                            SheetMenuItem(icon = R.drawable.ic_ss_restore, label = "Restore", onClick = { menuOpen = false; onRestore() })
                        }
                        if (onDeleteForever != null) {
                            SheetMenuItem(icon = R.drawable.ic_ss_trash, label = "Delete forever", onClick = { menuOpen = false; onDeleteForever() })
                        }
                        if (onRestore == null && onDeleteForever == null) {
                            SheetMenuItem(icon = R.drawable.ic_ss_download, label = "Download", onClick = { menuOpen = false; onDownload() })
                            SheetMenuItem(icon = R.drawable.ic_ss_send, label = "Send a copy", onClick = { menuOpen = false; onSendCopy() })
                            SheetMenuItem(icon = R.drawable.ic_ss_rename, label = "Rename", onClick = { menuOpen = false; onRename() })
                            SheetMenuItem(icon = R.drawable.ic_ss_archive_sel, label = "Archive", onClick = { menuOpen = false; onArchive() })
                        }
                    }
                }
            }
            if (daysLeft != null) {
                Text(
                    text = daysLeft.toString() + "d left",
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
private fun FileIndicators(file: SheetFile) {
    IndicatorCell(color = rowsIndicatorColor(), label = file.rowCount.toString())
    if (file.liveCount + file.deadCount > 0) {
        IndicatorCell(color = AliveGreen, label = file.liveCount.toString())
    }
    if (file.preset == SheetPreset.PAGE && file.pageCount > 0) {
        IndicatorCell(color = PageBlue, label = file.pageCount.toString())
    }
    if (file.liveCount + file.deadCount > 0) {
        IndicatorCell(color = DeadRed, label = file.deadCount.toString())
    }
    if (file.dupCount > 0) {
        IndicatorCell(color = DupYellow, label = file.dupCount.toString())
    }
}

@Composable
private fun IndicatorCell(color: Color, label: String) {
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
