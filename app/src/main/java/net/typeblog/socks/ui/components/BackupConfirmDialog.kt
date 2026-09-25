package net.typeblog.socks.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A backup file the user picked, held staged until they confirm. The counts
 * are captured at stage time, together with what the app holds right now, so
 * the dialog can show the direction of the change rather than only the size of
 * the incoming data.
 *
 * Not a data class: the payload is a ByteArray, whose generated equals and
 * hashCode would compare contents rather than identity.
 */
class StagedBackup(
    val bytes: ByteArray,
    val source: String,
    /** Dump time, or null for a workbook, which does not carry one. */
    val takenAt: Long?,
    val fileCount: Int,
    val rowCount: Int,
    val checkCount: Int,
    val reqCount: Int,
    val styleCount: Int,
    val hiddenCount: Int,
    val txCount: Int,
    val profileCount: Int,
    val balance: Double,
    val currentFiles: Int,
    val currentRows: Int,
    val currentTxCount: Int
) {
    /** Rows the app holds that the backup does not carry. */
    val rowsAtRisk: Int get() = (currentRows - rowCount).coerceAtLeast(0)
}

/**
 * Final gate before a restore replaces everything.
 *
 * The point is that it names what is about to be lost, not just what is
 * arriving: someone restoring a stale file should be able to see that they
 * are about to drop a hundred rows before they commit, which a row count of
 * the incoming file alone would not show.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BackupConfirmDialog(
    staged: StagedBackup,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val atRisk = staged.rowsAtRisk
    val losing = atRisk > 0 ||
            staged.currentFiles > staged.fileCount ||
            staged.currentTxCount > staged.txCount

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        title = { Text("Replace all data?") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {

                // Where this backup came from, and how old it is.
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer
                ) {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Text(
                            text = staged.source,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = staged.takenAt?.let {
                                "Taken " + SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(it))
                            } ?: "No timestamp - this could be older than it looks",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (staged.takenAt == null) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }

                // Incoming beside current, with the difference made explicit.
                Column(modifier = Modifier.padding(top = 16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = "BACKUP",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.End,
                            modifier = Modifier.width(COL_BACKUP)
                        )
                        Text(
                            text = "NOW",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.End,
                            modifier = Modifier.width(COL_NOW)
                        )
                        Spacer(modifier = Modifier.width(COL_DELTA))
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    StatRow("Files", staged.fileCount, staged.currentFiles)
                    ThinDivider()
                    StatRow("Rows", staged.rowCount, staged.currentRows)
                    if (staged.txCount > 0 || staged.currentTxCount > 0) {
                        ThinDivider()
                        StatRow("Wallet entries", staged.txCount, staged.currentTxCount)
                    }
                }

                // What else rides along, as pills rather than a sentence.
                val extras = buildList {
                    // ${...} not $staged.x: a bare $staged interpolates the
                    // object and leaves ".x" as literal text.
                    if (staged.checkCount > 0) add("${staged.checkCount} checked rows")
                    if (staged.reqCount > 0) add("${staged.reqCount} request traces")
                    if (staged.styleCount > 0) add("${staged.styleCount} styled cells")
                    if (staged.hiddenCount > 0) add("${staged.hiddenCount} hidden columns")
                    if (staged.profileCount > 0) add("${staged.profileCount} profile settings")
                    if (staged.balance != 0.0) add("balance ${staged.balance}")
                }
                if (extras.isNotEmpty()) {
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        for (label in extras) {
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.CheckCircle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.tertiary,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.padding(start = 5.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // The outcome, said once, in the colour it deserves.
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = if (losing) {
                        MaterialTheme.colorScheme.error.copy(alpha = 0.10f)
                    } else {
                        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.10f)
                    }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (losing && atRisk > 0) {
                                "Drops $atRisk ${if (atRisk == 1) "row" else "rows"} you have now. Cannot be undone."
                            } else if (losing) {
                                "Removes content you have now. Cannot be undone."
                            } else {
                                "Nothing is lost. This still cannot be undone."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = if (losing) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.tertiary
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (losing) {
                Button(
                    onClick = onConfirm,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = Color.White
                    )
                ) { Text("Replace anyway") }
            } else {
                Button(onClick = onConfirm) { Text("Replace") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun StatRow(label: String, incoming: Int, current: Int) {
    val delta = incoming - current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = incoming.toString(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.width(COL_BACKUP)
        )
        Text(
            text = current.toString(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.width(COL_NOW)
        )
        Box(
            modifier = Modifier.width(COL_DELTA),
            contentAlignment = Alignment.CenterEnd
        ) {
            DeltaChip(delta)
        }
    }
}

/**
 * Numeric columns are fixed width and right aligned so the figures line up and
 * can be compared down the column. The headers are short for the same reason:
 * "IN BACKUP" is wider than its column and ran into the next one.
 */
private val COL_BACKUP = 58.dp
private val COL_NOW = 44.dp
private val COL_DELTA = 48.dp

/** Plain ASCII signs, not arrows: user-visible text stays ASCII. */
@Composable
private fun DeltaChip(delta: Int) {
    val (text, tint) = when {
        delta > 0 -> "+$delta" to MaterialTheme.colorScheme.tertiary
        delta < 0 -> delta.toString() to MaterialTheme.colorScheme.error
        else -> "same" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = tint.copy(alpha = 0.14f)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = tint,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ThinDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    )
}
