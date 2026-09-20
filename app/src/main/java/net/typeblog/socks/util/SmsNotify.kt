package net.typeblog.socks.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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

    fun showCode(context: Context, display: String, code: String, message: String) {        if (code.isEmpty()) return
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
        buzz(context)
        SmsLog.log(context, "OTP", "shown $code for $display")
    }

    /** Short tick for arrivals and copies (OTP shown, number copied). */
    fun buzz(context: Context) {
        vibrate(context, longArrayOf(0, 60), intArrayOf(0, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    /** Double tick for failures (provision returned nothing). */
    fun buzzFail(context: Context) {
        vibrate(
            context, longArrayOf(0, 80, 60, 80),
            intArrayOf(0, VibrationEffect.DEFAULT_AMPLITUDE, 0, VibrationEffect.DEFAULT_AMPLITUDE)
        )
    }

    private fun vibrate(context: Context, timings: LongArray, amplitudes: IntArray) {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator?.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
            } else {
                @Suppress("DEPRECATION")
                val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= 26) {
                    v?.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
                } else {
                    @Suppress("DEPRECATION") v?.vibrate(timings.sum())
                }
            }
        } catch (_: Exception) {
            // No vibrator: haptics are best-effort.
        }
    }

    fun cancel(context: Context, notifId: Int) {
        manager(context)?.cancel(notifId)
    }
}
