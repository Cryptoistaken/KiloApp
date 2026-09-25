package net.typeblog.socks.ui.screens.sheet

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.typeblog.socks.util.sheet.CellStyle
import net.typeblog.socks.util.sheet.SheetColumn
import net.typeblog.socks.util.sheet.SheetRow

/**
 * The Sheet file grid, shared by the in-app editor and the floating bubble.
 * Pure render driven by parameters: every interaction is an optional
 * callback, so the bubble hosts the exact same grid read-only
 * (interactions = null) while the editor passes its closures and popup
 * slots. Metrics default to the in-app sizes; the bubble passes compact
 * ones through the same layout code — no more hand-mirrored grids.
 */
class SheetGridInteractions(
    val onCellClick: (row: SheetRow, col: SheetColumn) -> Unit,
    val onCellLongClick: (row: SheetRow, col: SheetColumn) -> Unit,
    val onRowRailClick: (rowIdx: Int) -> Unit,
    val onRowRailLongClick: (rowIdx: Int) -> Unit,
    val onCornerClick: () -> Unit,
    val onCornerLongClick: () -> Unit,
    val onHeaderClick: (colKey: String) -> Unit,
    val onHeaderLongClick: (colKey: String) -> Unit,
    val onDotClick: (row: SheetRow) -> Unit,
    val onDotLongClick: (row: SheetRow) -> Unit
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SheetGrid(
    rows: List<SheetRow>,
    visibleCols: List<SheetColumn>,
    styles: Map<String, CellStyle>,
    crossDups: Set<Pair<Int, String>>,
    modifier: Modifier = Modifier,
    railWidth: Dp = 36.dp,
    rowHeight: Dp = 36.dp,
    cellTextSize: TextUnit = 13.sp,
    headerTextSize: TextUnit = 12.sp,
    railTextSize: TextUnit = 11.sp,
    selectedCell: Pair<Int, String>? = null,
    selectionMode: Boolean = false,
    selectedItems: Set<Pair<Int, String>> = emptySet(),
    menuCell: Pair<Int, String>? = null,
    dotRowIdx: Int? = null,
    interactions: SheetGridInteractions? = null,
    listState: LazyListState = rememberLazyListState(),
    cellPopup: (@Composable (rowIdx: Int, colKey: String) -> Unit)? = null,
    dotPopup: (@Composable (row: SheetRow) -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null
) {
    val inter = interactions
    BoxWithConstraints(modifier = modifier) {
        val cellW = (maxWidth - railWidth * 2) / visibleCols.size.coerceAtLeast(1)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState
        ) {
            stickyHeader {
                Row(
                    modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .width(railWidth)
                            .height(rowHeight)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            .then(
                                if (inter != null) Modifier.combinedClickable(
                                    onClick = inter.onCornerClick,
                                    onLongClick = inter.onCornerLongClick
                                ) else Modifier
                            ),
                        contentAlignment = Alignment.Center
                    ) {}
                    for (col in visibleCols) {
                        Box(
                            modifier = Modifier
                                .width(cellW)
                                .height(rowHeight)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
                                .then(
                                    if (inter != null) Modifier.combinedClickable(
                                        onClick = { inter.onHeaderClick(col.key) },
                                        onLongClick = { inter.onHeaderLongClick(col.key) }
                                    ) else Modifier
                                )
                                .padding(horizontal = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = col.label,
                                fontSize = headerTextSize,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .width(railWidth)
                            .height(rowHeight)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        contentAlignment = Alignment.Center
                    ) {}
                }
            }
            items(rows, key = { it.rowIdx }) { row ->
                // Cross-file dup paints the duplicate cell only; dots stay
                // on the account status and never change for duplicates.
                val statusColor: Color? = when {
                    row.dead || row.status == "bad" -> DeadRed
                    row.status == "eligible" -> PageBlue
                    row.status == "good" || row.status == "done" -> AliveGreen
                    else -> null
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .width(railWidth)
                            .height(rowHeight)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            .background(
                                if (row.approved && statusColor != null) statusColor
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .then(
                                if (inter != null) Modifier.combinedClickable(
                                    onClick = { inter.onRowRailClick(row.rowIdx) },
                                    onLongClick = { inter.onRowRailLongClick(row.rowIdx) }
                                ) else Modifier
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = (row.rowIdx + 1).toString(),
                            fontSize = railTextSize,
                            fontWeight = FontWeight.Normal,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                    for (col in visibleCols) {
                        val key = styleKey(row.rowIdx, col.key)
                        val st = styles[key]
                        val selKey = Pair(row.rowIdx, col.key)
                        val isActive = selectedCell == selKey && !selectionMode
                        val isMulti = selectedItems.contains(selKey)
                        val isDup = crossDups.contains(selKey)
                        val customBg = parseHexColor(st?.bg)
                        val fg = parseHexColor(st?.color)
                        val cellBg: Color = when {
                            customBg != null -> customBg
                            isMulti -> MaterialTheme.colorScheme.surfaceVariant
                            isDup -> StatusYellow.copy(alpha = 0.15f)
                            row.hold && statusColor != null -> statusColor
                            row.approved && statusColor != null -> statusColor
                            else -> Color.Transparent
                        }
                        val cellBorder: Color = when {
                            isActive -> MaterialTheme.colorScheme.onSurface
                            isMulti -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                            isDup -> StatusYellow
                            else -> MaterialTheme.colorScheme.outlineVariant
                        }
                        Box(
                            modifier = Modifier
                                .width(cellW)
                                .height(rowHeight)
                                .border(1.dp, cellBorder)
                                .background(cellBg)
                                .then(
                                    if (inter != null) Modifier.combinedClickable(
                                        onClick = { inter.onCellClick(row, col) },
                                        onLongClick = { inter.onCellLongClick(row, col) }
                                    ) else Modifier
                                )
                                .padding(horizontal = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            // Perf: grid cells always render the committed value.
                            // Live typing lives only in the formula bar below, so
                            // keystrokes no longer recompose the whole grid.
                            Text(
                                text = row.cell(col.key),
                                fontSize = cellTextSize,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                fontWeight = if (st?.bold == true) FontWeight.Bold else FontWeight.Normal,
                                color = fg ?: MaterialTheme.colorScheme.onSurface,
                                textDecoration = if (row.approved) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center
                            )
                            // Sheets-style tap-again bar: white floating line
                            // with text buttons above the selected cell.
                            if (menuCell == selKey) {
                                cellPopup?.invoke(row.rowIdx, col.key)
                            }
                        }
                    }
                    Box {
                        val holdP = remember(row.rowIdx) { Animatable(0f) }
                        val pressSource = remember(row.rowIdx) { MutableInteractionSource() }
                        val pressed by pressSource.collectIsPressedAsState()
                        LaunchedEffect(pressed) {
                            // Hold progress bar under the dot, like the mock
                            // hold-to-confirm. Release early snaps it back.
                            if (pressed) holdP.animateTo(1f, tween(500))
                            else holdP.snapTo(0f)
                        }
                        Box(
                            modifier = Modifier
                                .width(railWidth)
                                .height(rowHeight)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
                                .background(MaterialTheme.colorScheme.surface)
                                .then(
                                    if (inter != null) Modifier.combinedClickable(
                                        interactionSource = pressSource,
                                        indication = null,
                                        onClick = { inter.onDotClick(row) },
                                        onLongClick = { inter.onDotLongClick(row) }
                                    ) else Modifier
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            StatusDot(
                                status = row.status,
                                dead = row.dead,
                                isDup = false
                            )
                            if (holdP.value > 0f) {
                                Box(
                                    Modifier
                                        .align(Alignment.BottomCenter)
                                        .fillMaxWidth(holdP.value)
                                        .height(2.dp)
                                        .background(AliveGreen)
                                )
                            }
                        }
                        if (dotRowIdx == row.rowIdx) {
                            dotPopup?.invoke(row)
                        }
                    }
                }
            }
            if (footer != null) {
                item { footer() }
            }
        }
    }
}
