package net.typeblog.socks.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 30s RTC_WAKEUP heartbeat while SMS numbers wait. Fires even when the
 * process was frozen in background: runs one OTP sweep, re-arms, stops
 * the chain when nothing waits.
 */
class SmsAlarmReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_POLL = "net.typeblog.socks.SMS_HEARTBEAT_POLL"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent?.action != ACTION_POLL) return
        SmsLog.log(context, "ALARM", "heartbeat poll")
        val result = goAsync()
        fun done() {
            try {
                result.finish()
            } catch (_: Exception) {
            }
        }
        try {
            SmsWatcher.onHeartbeat(context.applicationContext, ::done)
        } catch (_: Exception) {
            done()
        }
    }
}
