package net.typeblog.socks.util

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * File-backed event log for SMS pickup. logcat is empty on some devices
 * (seen on vivo), so errors and arrival proof go here too: filesDir
 * survives process death, and [LogCollector] includes the tail.
 */
object SmsLog {
    private const val FILE = "sms_events.log"
    private const val MAX_CHARS = 60_000

    fun log(context: Context?, tag: String, msg: String) {
        try {
            val ctx = context?.applicationContext ?: return
            val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            val line = "$ts $tag $msg\n"
            val f = File(ctx.filesDir, FILE)
            var cur = if (f.exists()) f.readText() else ""
            cur += line
            if (cur.length > MAX_CHARS) cur = cur.takeLast(MAX_CHARS)
            f.writeText(cur)
        } catch (_: Exception) {
        }
    }

    fun read(context: Context): String {
        return try {
            val f = File(context.filesDir, FILE)
            if (f.exists()) f.readText().takeLast(MAX_CHARS) else "(no sms events logged)"
        } catch (e: Exception) {
            "(sms events unreadable: ${e.message})"
        }
    }

    fun clear(context: Context) {
        try {
            File(context.filesDir, FILE).delete()
        } catch (_: Exception) {
        }
    }
}
