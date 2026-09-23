package net.typeblog.socks.ui.screens.sheet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import net.typeblog.socks.util.sheet.SheetPreset

// Whole-file popup: near-fullscreen card with live file stats and meta.
// Opened from the file ... menu, not tied to any row. Every number is
// computed from the open rows right now — no stored counters.
@Composable
fun FilePopup(
    fileName: String,
    preset: SheetPreset,
    totalRows: Int,
    alive: Int,
    dead: Int,
    dupRows: Int,
    pageRows: Int,
    checkedRows: Int,
    createdAt: Long,
    updatedAt: Long,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = if (androidx.compose.foundation.isSystemInDarkTheme()) 0.5f else 0.25f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .fillMaxHeight(0.88f)
                    .shadow(24.dp, RoundedCornerShape(12.dp))
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
                    .padding(20.dp)
            ) {
                Text(
                    text = fileName,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.size(4.dp))
                Text(
                    text = preset.title,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.size(16.dp))
                LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                    item {
                        FileStatRow(color = rowsIndicatorColor(), label = "Total rows", value = totalRows)
                        FileStatRow(color = AliveGreen, label = "Alive", value = alive)
                        FileStatRow(color = DeadRed, label = "Dead", value = dead)
                        FileStatRow(color = DupYellow, label = "Duplicates", value = dupRows)
                        if (preset == SheetPreset.PAGE) {
                            FileStatRow(color = PageBlue, label = "Page eligible", value = pageRows)
                        }
                        FileStatRow(
                            color = MaterialTheme.colorScheme.primary,
                            label = "Checked rows",
                            value = checkedRows
                        )
                    }
                    item {
                        Spacer(modifier = Modifier.size(16.dp))
                        FileMetaRow(label = "Created", value = fmtDate(createdAt))
                        FileMetaRow(label = "Updated", value = fmtDate(updatedAt))
                    }
                }
                Spacer(modifier = Modifier.size(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
                    SheetBtnGhost(label = "Close", onClick = onDismiss)
                }
            }
        }
    }
}

@Composable
private fun FileStatRow(color: Color, label: String, value: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IndicatorSquare(color = color)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value.toString(),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun FileMetaRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(80.dp)
        )
        Text(
            text = value.ifEmpty { "-" },
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
