package net.typeblog.socks.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
)

/**
 * Final gate before a restore replaces everything.
 *
 * The point is that it names what is about to be lost, not just what is
 * arriving: someone restoring a stale file should be able to see that they
 * are about to drop a hundred rows before they commit, which a row count of
 * the incoming file alone would not show.
 */
@Composable
fun BackupConfirmDialog(
    staged: StagedBackup,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val losing = staged.currentRows > staged.rowCount ||
            staged.currentFiles > staged.fileCount ||
            staged.currentTxCount > staged.txCount

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Replace all data?") },
        text = {
            Column {
                Text(
                    text = staged.source,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                staged.takenAt?.let {
                    Text(
                        text = "Taken " + SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(it)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                Text(
                    text = if (staged.takenAt == null) {
                        "A workbook carries no timestamp, so this could be older than it looks."
                    } else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 2.dp)
                )

                CountRow("Files", staged.fileCount.toString(), staged.currentFiles.toString())
                CountRow("Rows", staged.rowCount.toString(), staged.currentRows.toString())
                if (staged.balance != 0.0 || staged.txCount > 0) {
                    CountRow("Wallet entries", staged.txCount.toString(), staged.currentTxCount.toString())
                }

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
                    Text(
                        text = "Also carries: " + extras.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                }

                Text(
                    text = if (losing) {
                        "This will drop what the app holds now. It cannot be undone."
                    } else {
                        "This replaces the app's data. It cannot be undone."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (losing) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(if (losing) "Replace anyway" else "Replace")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun CountRow(label: String, incoming: String, current: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = incoming,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "  (now $current)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
