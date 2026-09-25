package net.typeblog.socks.ui.screens.sheet

import android.content.Context
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.typeblog.socks.ui.components.SsCheckIndicator
import net.typeblog.socks.ui.components.SsCheckboxSize
import net.typeblog.socks.util.sheet.CellStyle
import net.typeblog.socks.util.sheet.CheckReq
import net.typeblog.socks.util.sheet.DupSource
import net.typeblog.socks.util.sheet.FileDup
import net.typeblog.socks.util.sheet.RowCheck
import net.typeblog.socks.util.sheet.SheetDb
import net.typeblog.socks.util.sheet.SheetFile
import net.typeblog.socks.util.sheet.SheetRow

private val PALETTE = listOf(
    "#000000", "#434343", "#666666", "#999999", "#B7B7B7",
    "#CCCCCC", "#D9D9D9", "#ee0000", "#ff6d00", "#fbbc04",
    "#16a34a", "#00acc1", "#0070f3", "#6366f1", "#795548"
)

@Composable
private fun dotCardProvider(): androidx.compose.ui.window.PopupPositionProvider {
    val density = LocalDensity.current
    return remember(density) {
        val gap = with(density) { 4.dp.roundToPx() }
        object : androidx.compose.ui.window.PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: androidx.compose.ui.unit.IntRect,
                windowSize: androidx.compose.ui.unit.IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: androidx.compose.ui.unit.IntSize
            ): androidx.compose.ui.unit.IntOffset {
                val x = (anchorBounds.right - popupContentSize.width)
                    .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                var y = anchorBounds.bottom + gap
                if (y + popupContentSize.height > windowSize.height) {
                    y = (anchorBounds.top - popupContentSize.height - gap).coerceAtLeast(0)
                }
                return androidx.compose.ui.unit.IntOffset(x, y)
            }
        }
    }
}

@Composable
private fun cellBarProvider(): androidx.compose.ui.window.PopupPositionProvider {
    val density = LocalDensity.current
    return remember(density) {
        val gap = with(density) { 8.dp.roundToPx() }
        val margin = with(density) { 4.dp.roundToPx() }
        object : androidx.compose.ui.window.PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: androidx.compose.ui.unit.IntRect,
                windowSize: androidx.compose.ui.unit.IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: androidx.compose.ui.unit.IntSize
            ): androidx.compose.ui.unit.IntOffset {
                val x = (anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2)
                    .coerceIn(margin, (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin))
                var y = anchorBounds.top - popupContentSize.height - gap
                if (y < margin) y = anchorBounds.bottom + gap
                return androidx.compose.ui.unit.IntOffset(x, y)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CellPopBar(
    readOnly: Boolean,
    onCut: () -> Unit,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onDismiss: () -> Unit
) {
    Popup(
        popupPositionProvider = cellBarProvider(),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)
    ) {
        Row(
            modifier = Modifier
                .shadow(12.dp, androidx.compose.foundation.shape.RoundedCornerShape(28.dp))
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.surface)
        ) {
            if (!readOnly) CellBarButton("Cut", onCut)
            CellBarButton("Copy", onCopy)
            if (!readOnly) CellBarButton("Paste", onPaste)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowScope.CellBarButton(
    label: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
            .combinedClickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
internal fun SheetDotAnchor(
    row: SheetRow,
    check: RowCheck?,
    reqs: List<CheckReq>,
    dupSources: List<DupSource>,
    fileName: String,
    presetLabel: String,
    checking: Boolean,
    tab: Int,
    onTabChange: (Int) -> Unit,
    onToggleWide: () -> Unit,
    onDismiss: () -> Unit
) {
    Popup(
        popupPositionProvider = dotCardProvider(),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)
    ) {
        DotPopupCard(
            row = row,
            check = check,
            reqs = reqs,
            dupSources = dupSources,
            fileName = fileName,
            presetLabel = presetLabel,
            rowNo = row.rowIdx + 1,
            checking = checking,
            wide = false,
            onToggleWide = onToggleWide,
            showHeader = false,
            tab = tab,
            onTabChange = onTabChange
        )
    }
}

@Composable
internal fun SheetSkeleton() {
    val t = rememberInfiniteTransition(label = "skel")
    val a by t.animateFloat(
        initialValue = 0.45f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse"
    )
    val bone = MaterialTheme.colorScheme.surfaceVariant
    val line = MaterialTheme.colorScheme.outlineVariant
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp)
                .alpha(a),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(48.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(bone)
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(20.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                    .background(bone)
            )
            repeat(2) {
                Box(
                    modifier = Modifier.size(48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                            .background(bone)
                    )
                }
            }
            Box(
                modifier = Modifier
                    .width(94.dp)
                    .height(32.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.38f))
            )
            Box(
                modifier = Modifier.size(48.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(6.dp)
                        .height(20.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
                        .background(bone)
                )
            }
        }
        androidx.compose.foundation.layout.BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .alpha(a)
        ) {
            val fitW = (maxWidth - 72.dp) / 3
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .width(36.dp)
                            .height(36.dp)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    )
                    repeat(3) {
                        Box(
                            modifier = Modifier
                                .width(fitW)
                                .height(36.dp)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
                                .padding(horizontal = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.6f)
                                    .height(12.dp)
                                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
                                    .background(line)
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .width(36.dp)
                            .height(36.dp)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    )
                }
                repeat(12) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .width(36.dp)
                                .height(36.dp)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        )
                        repeat(3) {
                            Box(
                                modifier = Modifier
                                    .width(fitW)
                                    .height(36.dp)
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
                                    .background(MaterialTheme.colorScheme.surface)
                                    .padding(horizontal = 8.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(0.7f)
                                        .height(13.dp)
                                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
                                        .background(bone)
                                )
                            }
                        }
                        Box(
                            modifier = Modifier
                                .width(36.dp)
                                .height(36.dp)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
                                .background(MaterialTheme.colorScheme.surface),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(2.5.dp))
                                    .background(bone)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SheetRenameDialog(
    value: String,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    SheetModal(
        onDismiss = onDismiss,
        widthDp = 320,
        title = "Rename file"
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            SheetNameInput(value = value, onValueChange = onValueChange, onDone = onConfirm)
            Spacer(modifier = Modifier.size(12.dp))
            SheetModalFooter(
                onCancel = onDismiss,
                onConfirm = onConfirm,
                confirmText = "Rename"
            )
        }
    }
}

@Composable
internal fun SheetFileInspector(
    appCtx: Context,
    file: SheetFile,
    fileId: String,
    filePopupOpen: Boolean,
    rows: List<SheetRow>,
    checks: Map<Int, RowCheck>,
    reqs: Map<Int, List<CheckReq>>,
    checking: Boolean,
    onDismiss: () -> Unit
) {
    var inspectorDups by remember(fileId) { mutableStateOf<List<FileDup>>(emptyList()) }
    LaunchedEffect(filePopupOpen, rows) {
        if (filePopupOpen) {
            inspectorDups = withContext(Dispatchers.IO) {
                try {
                    SheetDb(appCtx).fileDups(file.id, rows)
                } catch (e: Exception) {
                    emptyList()
                }
            }
        }
    }
    FilePopup(
        preset = file.preset,
        rows = rows,
        checks = checks,
        reqs = reqs,
        fileDups = inspectorDups,
        checking = checking,
        onDismiss = onDismiss
    )
}

@Composable
internal fun SheetWideDotDialog(
    row: SheetRow,
    check: RowCheck?,
    reqs: List<CheckReq>,
    dupSources: List<DupSource>,
    fileName: String,
    presetLabel: String,
    checking: Boolean,
    tab: Int,
    onTabChange: (Int) -> Unit,
    onDock: () -> Unit,
    onDismiss: () -> Unit
) {
    DotPopup(
        row = row,
        check = check,
        reqs = reqs,
        dupSources = dupSources,
        onDismiss = onDismiss,
        fileName = fileName,
        presetLabel = presetLabel,
        rowNo = row.rowIdx + 1,
        checking = checking,
        startWide = true,
        onDock = onDock,
        tab = tab,
        onTabChange = onTabChange
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SheetStylePicker(
    picker: String,
    current: CellStyle?,
    onSelect: (CellStyle?) -> Unit,
    onDismiss: () -> Unit
) {
    SheetModal(
        onDismiss = onDismiss,
        widthDp = 320,
        title = if (picker == "text") "TEXT COLOR" else "CELL FILL",
        miniTitle = true
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (picker == "fill") {
                TextButton(
                    onClick = {
                        val next = (current ?: CellStyle()).copy(bg = null)
                        val clean = if (next.bg == null && next.color == null && !next.bold) null else next
                        onSelect(clean)
                    }
                ) { Text("No fill") }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant,
                        androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
                    )
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
            ) {
                for (hex in PALETTE) {
                    val c = parseHexColor(hex) ?: Color.Black
                    val active = if (picker == "text") current?.color == hex else current?.bg == hex
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(32.dp)
                            .background(c)
                            .border(
                                width = if (active) 2.dp else 0.dp,
                                color = if (active) MaterialTheme.colorScheme.onSurface else Color.Transparent
                            )
                            .combinedClickable(
                                onClick = {
                                    val base = current ?: CellStyle()
                                    onSelect(if (picker == "text") base.copy(color = hex) else base.copy(bg = hex))
                                }
                            )
                    )
                }
            }
        }
    }
}

@Composable
internal fun CheckSwitchRow(
    label: String,
    checked: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
            .toggleable(
                value = checked,
                role = androidx.compose.ui.semantics.Role.Switch,
                onValueChange = { onToggle() }
            )
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        MiniSwitch(checked = checked)
    }
}

@Composable
private fun MiniSwitch(checked: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(width = 36.dp, height = 20.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
            .background(
                if (checked) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .border(
                2.dp,
                if (checked) Color.Transparent else MaterialTheme.colorScheme.outlineVariant,
                androidx.compose.foundation.shape.RoundedCornerShape(50)
            )
            .padding(2.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .shadow(1.dp, androidx.compose.foundation.shape.RoundedCornerShape(50))
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surface)
        )
    }
}

@Composable
internal fun ColToggleBox(checked: Boolean) {
    SsCheckIndicator(checked = checked, size = SsCheckboxSize.SM)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun OverflowRow(
    label: String,
    labelSize: TextUnit = 13.sp,
    leading: (@Composable () -> Unit)? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
            .combinedClickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (leading != null) leading()
        Text(
            text = label,
            fontSize = labelSize,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
