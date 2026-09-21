package net.typeblog.socks.util

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent

/**
 * Copies an OTP code from the SMS notification action button,
 * then dismisses the notification.
 */
class SmsCopyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        if (intent.action?.startsWith(SmsNotify.ACTION_COPY) != true) return
        val code = intent.getStringExtra(SmsNotify.EXTRA_CODE) ?: return
        if (code.isEmpty()) return
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("SMS code", code))
            SmsNotify.buzzCopy(context)
        } catch (e: Exception) {
            // Background clipboard access may be blocked; the code stays
            // visible in the notification itself as fallback.
        }
        SmsNotify.cancel(context, intent.getIntExtra(SmsNotify.EXTRA_NOTIF, 0))
    }
}
