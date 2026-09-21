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
    onDownload: () -> Unit,
    onRename: () -> Unit,
    onArchive: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.surfaceVariant
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
                        style = MaterialTheme.typography.titleSmall,
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
                                icon = R.drawable.ic_ss_download,
                                label = "Download",
                                onClick = {
                                    menuOpen = false
                                    onDownload()
                                }
                            )
                            SheetMenuItem(
                                icon = R.drawable.ic_ss_pencil,
                                label = "Rename",
                                onClick = {
                                    menuOpen = false
                                    onRename()
                                }
                            )
                            SheetMenuItem(
                                icon = R.drawable.ic_ss_trash,
                                label = "Move to archive",
                                danger = true,
                                onClick = {
                                    menuOpen = false
                                    onArchive()
                                }
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.size(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                IndicatorCell(
                    color = rowsIndicatorColor(),
                    label = file.rowCount.toString()
                )
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
        }
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
