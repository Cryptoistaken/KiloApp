package net.typeblog.socks.util

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * File-backed event log for SMS pickup. logcat is empty on some devices
 * (seen on vivo), so errors and arrival proof go here too: filesDir
 * survives process death, and [LogCollector] includes the tail.
 */
object SmsLog {
    private const val FILE = "sms_events.log"
    private const val MAX_CHARS = 60_000
    private val io = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "SmsLog").apply { isDaemon = true }
    }

    fun log(context: Context?, tag: String, msg: String) {
        val ctx = context?.applicationContext ?: return
        try {
            io.execute {
                try {
                    val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
                    val line = "$ts $tag $msg\n"
                    val file = File(ctx.filesDir, FILE)
                    var current = if (file.exists()) file.readText() else ""
                    current += line
                    if (current.length > MAX_CHARS) current = current.takeLast(MAX_CHARS)
                    file.writeText(current)
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
    }

    fun read(context: Context): String {
        return try {
            io.submit<String> {
                val file = File(context.applicationContext.filesDir, FILE)
                if (file.exists()) file.readText().takeLast(MAX_CHARS) else "(no sms events logged)"
            }.get()
        } catch (e: Exception) {
            "(sms events unreadable: ${e.message})"
        }
    }

    fun clear(context: Context) {
        val ctx = context.applicationContext
        try {
            io.execute { File(ctx.filesDir, FILE).delete() }
        } catch (_: Exception) {
        }
    }
}
