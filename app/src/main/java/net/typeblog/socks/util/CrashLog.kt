package net.typeblog.socks.util

import android.content.Context
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Last-crash catcher: logcat from another app cannot see our process, so
// uncaught exceptions are persisted here and shown on the next launch
// with a copy button. Chains to the previous handler so the system still
// reports the crash normally.
object CrashLog {
    private const val NAME = "crash.log"
    private const val MAX_CHARS = 24_000

    fun install(app: Context) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val ts = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
                val sw = java.io.StringWriter()
                e.printStackTrace(java.io.PrintWriter(sw))
                val entry = "--- $ts ${t.name} ${e.javaClass.name}: ${e.message}\n${sw}\n"
                val f = File(app.filesDir, NAME)
                val old = if (f.exists()) f.readText().takeLast(MAX_CHARS / 2) else ""
                f.writeText((old + entry).takeLast(MAX_CHARS))
            } catch (_: Exception) {
            }
            prev?.uncaughtException(t, e)
        }
    }

    fun pending(app: Context): String? {
        return try {
            val f = File(app.filesDir, NAME)
            if (f.exists() && f.length() > 0) f.readText() else null
        } catch (_: Exception) {
            null
        }
    }

    fun clear(app: Context) {
        try {
            File(app.filesDir, NAME).delete()
        } catch (_: Exception) {
        }
    }
}

@Composable
fun CrashReportDialog(text: String, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Crash report") },
        text = {
            Text(
                text = text.takeLast(8000),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .verticalScroll(rememberScrollState())
            )
        },
        confirmButton = {
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(text.takeLast(8000)))
            }) { Text("Copy") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    )
}
