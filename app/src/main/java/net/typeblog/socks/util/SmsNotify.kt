package net.typeblog.socks.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import net.typeblog.socks.MainActivity
import net.typeblog.socks.R

/**
 * OTP arrival notifications for numbers taken in the SMS tab.
 * The code stays visible in the title (readable even if the
 * background clipboard copy is blocked) plus a Copy action button.
 * All user text is plain ASCII.
 */
object SmsNotify {
    private const val CHANNEL = "sms_otp"
    const val ACTION_COPY = "net.typeblog.socks.SMS_COPY_CODE"
    const val EXTRA_CODE = "code"
    const val EXTRA_NOTIF = "notif"

    private fun manager(context: Context): NotificationManager? {
        return context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = manager(context) ?: return
        if (mgr.getNotificationChannel(CHANNEL) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL, "SMS codes", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    fun showCode(context: Context, display: String, code: String, message: String) {
        if (code.isEmpty()) return
        ensureChannel(context)
        val notifId = (display + code).hashCode()
        val copyIntent = Intent(context, SmsCopyReceiver::class.java).apply {
            action = "$ACTION_COPY.$notifId"
            putExtra(EXTRA_CODE, code)
            putExtra(EXTRA_NOTIF, notifId)
        }
        val copyPending = PendingIntent.getBroadcast(
            context, notifId, copyIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            context, notifId, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val body = if (message.isNotEmpty()) "$display: $message" else display
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("SMS code $code")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openPending)
            .setAutoCancel(true)
            .addAction(0, "Copy $code", copyPending)
            .build()
        manager(context)?.notify(notifId, notification)
    }

    fun cancel(context: Context, notifId: Int) {
        manager(context)?.cancel(notifId)
    }
}
