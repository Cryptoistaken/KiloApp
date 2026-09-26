package net.typeblog.socks.ui.screens.sheet

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.typeblog.socks.R
import net.typeblog.socks.ui.components.SsBanner
import net.typeblog.socks.ui.components.SsBannerStatus
import net.typeblog.socks.util.sheet.CellStyle
import net.typeblog.socks.util.sheet.CopiedGrid
import net.typeblog.socks.util.sheet.MAX_GRID_ROWS
import net.typeblog.socks.util.sheet.SheetColumn
import net.typeblog.socks.util.sheet.SheetFile
import net.typeblog.socks.util.sheet.SheetPreset
import net.typeblog.socks.util.sheet.SheetRow

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SheetDetailHeader(
    readOnly: Boolean,
    file: SheetFile?,
    rows: List<SheetRow>,
    visibleCols: List<SheetColumn>,
    columns: List<SheetColumn>,
    selectionMode: Boolean,
    selectedItems: Set<Pair<Int, String>>,
    hidden: Set<String>,
    canUndo: Boolean,
    canRedo: Boolean,
    checking: Boolean,
    autoCheck: Boolean,
    simpleCheck: Boolean,
    advancedCheck: Boolean,
    checkMenu: Boolean,
    overflowMenu: Boolean,
    onBack: () -> Unit,
    onToggleAll: (Set<Pair<Int, String>>) -> Unit,
    onCancelSelection: () -> Unit,
    onRename: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onCheck: () -> Unit,
    onCheckMenuChange: (Boolean) -> Unit,
    onToggleAutoCheck: () -> Unit,
    onToggleSimpleCheck: () -> Unit,
    onToggleAdvancedCheck: () -> Unit,
    onOverflowMenuChange: (Boolean) -> Unit,
    onInspector: () -> Unit,
    onDownload: () -> Unit,
    onShare: () -> Unit,
    onUploadReplace: () -> Unit,
    onUploadMerge: () -> Unit,
    onCompact: () -> Unit,
    onDeleteDead: () -> Unit,
    onToggleColumn: (SheetColumn, Boolean) -> Unit
) {
    if (selectionMode) {
        val allCellSet = remember(rows, visibleCols) {
            detailAllCells(rows, visibleCols)
        }
        SelectHeader(
            count = selectedItems.size,
            total = allCellSet.size,
            onToggleAll = { onToggleAll(allCellSet) },
            onCancel = onCancelSelection
        )
        return
    }

    val hasCheckableRows = remember(rows, columns) {
        detailHasUidToCheck(rows, columns)
    }
    val checkEnabled = !checking && hasCheckableRows
    val fname = file?.name ?: "Sheet"
    val fpreset = file?.preset
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(
                painter = painterResource(R.drawable.lucide_arrow_left),
                contentDescription = "Back",
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
        if (readOnly) {
            Row(
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (fpreset != null) PresetIcon(preset = fpreset, sizeDp = 14)
                Text(
                    text = fname,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        } else {
            TextButton(onClick = onRename, modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (fpreset != null) PresetIcon(preset = fpreset, sizeDp = 14)
                    Text(
                        text = fname,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        if (!readOnly) {
            IconButton(onClick = onUndo, enabled = canUndo) {
                Icon(
                    painter = painterResource(R.drawable.ic_ss_undo),
                    contentDescription = "Undo",
                    modifier = Modifier.size(18.dp)
                )
            }
            IconButton(onClick = onRedo, enabled = canRedo) {
                Icon(
                    painter = painterResource(R.drawable.ic_ss_redo),
                    contentDescription = "Redo",
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                .background(
                    if (checkEnabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.primary.copy(alpha = 0.38f)
                )
        ) {
            Box(
                modifier = Modifier
                    .combinedClickable(enabled = checkEnabled, onClick = onCheck)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center
            ) {
                if (checking) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = "Checking",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                } else {
                    Text(
                        text = "Check",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
            if (!readOnly && !checking) {
                Box {
                    Box(
                        modifier = Modifier
                            .combinedClickable { onCheckMenuChange(true) }
                            .padding(horizontal = 7.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_ss_check_arrow),
                            contentDescription = "More check options",
                            modifier = Modifier.size(10.dp),
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                    DropdownMenu(
                        expanded = checkMenu,
                        onDismissRequest = { onCheckMenuChange(false) }
                    ) {
                        CheckSwitchRow(
                            label = "UID check",
                            checked = autoCheck,
                            onToggle = onToggleAutoCheck
                        )
                        // Simple and Advanced are Page-file checks: the check
                        // run gates both sweeps on preset == PAGE, so on a
                        // Cookie or 2fa file these switches did nothing.
                        // Unknown file (null) keeps showing them rather than
                        // hiding rows that may be needed.
                        if (file == null || file.preset == SheetPreset.PAGE) {
                            CheckSwitchRow(
                                label = "Simple check",
                                checked = simpleCheck,
                                onToggle = onToggleSimpleCheck
                            )
                            CheckSwitchRow(
                                label = "Advanced check",
                                checked = advancedCheck,
                                onToggle = onToggleAdvancedCheck
                            )
                        }
                    }
                }
            }
        }
        Box {
            // The overflow affordance is a vertical-kebab icon, not a "More"
            // text label: it reads as an overflow control and keeps the top
            // bar narrow. Labelled on the button so it stays announced.
            IconButton(
                onClick = { onOverflowMenuChange(true) },
                modifier = Modifier.semantics { contentDescription = "More actions" }
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_ss_more_vert),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
            DropdownMenu(
                expanded = overflowMenu,
                onDismissRequest = { onOverflowMenuChange(false) },
                modifier = Modifier.widthIn(min = 160.dp)
            ) {
                OverflowRow(
                    label = "Inspector",
                    leading = {
                        Icon(
                            painter = painterResource(R.drawable.ic_ss_info),
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    onClick = {
                        onOverflowMenuChange(false)
                        onInspector()
                    }
                )
                if (!readOnly) {
                    OverflowRow(
                        label = "Download xlsx",
                        leading = {
                            Icon(
                                painter = painterResource(R.drawable.ic_ss_download),
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        onClick = {
                            onOverflowMenuChange(false)
                            onDownload()
                        }
                    )
                    OverflowRow(
                        label = "Send a copy",
                        leading = {
                            Icon(
                                painter = painterResource(R.drawable.ic_ss_send),
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        onClick = {
                            onOverflowMenuChange(false)
                            onShare()
                        }
                    )
                    OverflowRow(
                        label = "Upload xlsx",
                        leading = {
                            Icon(
                                painter = painterResource(R.drawable.ic_ss_upload),
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        onClick = {
                            onOverflowMenuChange(false)
                            onUploadReplace()
                        }
                    )
                    OverflowRow(
                        label = "Merge",
                        leading = {
                            Icon(
                                painter = painterResource(R.drawable.ic_ss_merge),
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        onClick = {
                            onOverflowMenuChange(false)
                            onUploadMerge()
                        }
                    )
                    OverflowRow(
                        label = "Compact",
                        leading = {
                            Icon(
                                painter = painterResource(R.drawable.ic_ss_compact),
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        onClick = {
                            onOverflowMenuChange(false)
                            onCompact()
                        }
                    )
                    OverflowRow(
                        label = "Delete Dead",
                        leading = {
                            Icon(
                                painter = painterResource(R.drawable.ic_ss_trash),
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        onClick = {
                            onOverflowMenuChange(false)
                            onDeleteDead()
                        }
                    )
                }
                androidx.compose.material3.HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
                for (col in columns) {
                    val visible = !hidden.contains(col.key)
                    OverflowRow(
                        label = col.label,
                        labelSize = 12.sp,
                        leading = { ColToggleBox(checked = visible) },
                        onClick = { onToggleColumn(col, visible) }
                    )
                }
            }
        }
    }
}

@Composable
internal fun SheetDetailArchivedBanner(onRestore: () -> Unit) {
    SsBanner(
        status = SsBannerStatus.INFO,
        title = "Archived",
        description = "View only. Cannot modify.",
        actionLabel = "Restore",
        actionIcon = R.drawable.ic_ss_restore,
        onAction = onRestore,
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp)
    )
}

@Composable
internal fun ColumnScope.SheetDetailEditor(
    readOnly: Boolean,
    rows: List<SheetRow>,
    visibleCols: List<SheetColumn>,
    styles: Map<String, CellStyle>,
    crossDups: Set<Pair<Int, String>>,
    selectedCell: Pair<Int, String>?,
    selectionMode: Boolean,
    selectedItems: Set<Pair<Int, String>>,
    menuCell: Pair<Int, String>?,
    dotRowIdx: Int?,
    gridState: LazyListState,
    dataCount: Int,
    gridClip: CopiedGrid?,
    draft: String,
    interactions: SheetGridInteractions,
    cellPopup: @Composable (Int, String) -> Unit,
    dotPopup: @Composable (SheetRow) -> Unit,
    footer: @Composable () -> Unit,
    onCopySelection: () -> Unit,
    onPasteSelection: (CopiedGrid) -> Unit,
    onClearSelection: () -> Unit,
    onDraftChange: (String) -> Unit,
    onCommitDraft: () -> Unit
) {
    if (visibleCols.isEmpty()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "All columns hidden. Use the menu.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        SheetGrid(
            rows = rows,
            visibleCols = visibleCols,
            styles = styles,
            crossDups = crossDups,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            selectedCell = selectedCell,
            selectionMode = selectionMode,
            selectedItems = selectedItems,
            menuCell = menuCell,
            dotRowIdx = dotRowIdx,
            listState = gridState,
            interactions = interactions,
            cellPopup = cellPopup,
            dotPopup = dotPopup,
            footer = footer
        )
    }

    if (selectionMode && selectedItems.isNotEmpty()) {
        val cellActions = buildList {
            add(
                SelectAction(
                    icon = R.drawable.ic_ss_copy,
                    label = "Copy",
                    onClick = onCopySelection
                )
            )
            val clip = gridClip
            if (clip != null && !readOnly) {
                add(
                    SelectAction(
                        icon = R.drawable.ic_ss_paste,
                        label = "Paste",
                        onClick = { onPasteSelection(clip) }
                    )
                )
            }
            if (!readOnly) {
                add(
                    SelectAction(
                        icon = R.drawable.ic_ss_eraser,
                        label = "Clear",
                        onClick = onClearSelection
                    )
                )
            }
        }
        SelectBottomBar(actions = cellActions)
    }

    if (!readOnly && !selectionMode && selectedCell != null) {
        Surface(
            tonalElevation = 3.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.fillMaxWidth()
                    .padding(top = 6.dp, start = 12.dp, end = 12.dp, bottom = 10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = onDraftChange,
                        placeholder = { Text("Enter value", fontSize = 16.sp) },
                        trailingIcon = {
                            if (draft.isNotEmpty()) {
                                IconButton(
                                    onClick = { onDraftChange("") },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_ss_clear_text),
                                        contentDescription = "Clear text",
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontSize = 16.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        ),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(32.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { onCommitDraft() }),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                    )
                }
            }
        }
    }
}
