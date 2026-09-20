package net.typeblog.socks.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.typeblog.socks.MainActivity
import net.typeblog.socks.R

/**
 * Keeps OTP pickup alive while the user is out of the app.
 *
 * Polling + SSE live in [SmsWatcher] and only run while this process is
 * alive; without a foreground service the OS freezes the process minutes
 * after backgrounding (Doze/cached state) and the SMS only appears when
 * the user returns. This service holds a dataSync foreground service from
 * the Get-number tap until no number is waiting anymore, so arrival is
 * instant in background too. Started from a user tap (never from boot or
 * background), max lifetime is the 7-min number expiry.
 */
class SmsOtpService : Service() {

    companion object {
        private const val TAG = "SmsOtpService"
        private const val ACTION_WATCH = "net.typeblog.socks.SMS_OTP_WATCH"
        private const val ACTION_STOP = "net.typeblog.socks.SMS_OTP_STOP"
        private const val NOTIF_ID = 1001
        private const val CHANNEL = "sms_wait"

        fun start(context: Context) {
            val i = Intent(context, SmsOtpService::class.java).setAction(ACTION_WATCH)
            try {
                ContextCompat.startForegroundService(context, i)
            } catch (_: Exception) {
                // Background-start restriction: polling fallback covers it.
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        SmsWatcher.start(this)
        try {
            goForeground(pendingCount())
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed", e)
            stopSelf()
            return START_NOT_STICKY
        }
        // A foreground service keeps the process alive and network-allowed,
        // but NOT the CPU awake: without a partial wake lock the poll timer
        // and socket timeouts freeze on screen-off and everything fires at
        // once on wake. Held only while numbers wait (7-min max).
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KiloApp:sms-wait")
            wakeLock?.acquire(10 * 60 * 1000L)
        } catch (e: Exception) {
            Log.w(TAG, "wake lock acquire failed", e)
        }
        if (loop == null) {
            loop = scope.launch { watchLoop() }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        loop?.cancel()
        loop = null
        try {
            wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
        scope.cancel()
        super.onDestroy()
    }

    private fun pendingCount(): Int {
        val t = System.currentTimeMillis()
        return SmsWatcher.mine.count { it.code == null && t - it.born < SMS_EXPIRE_SEC * 1000 }
    }

    private suspend fun watchLoop() {
        var idleRounds = 0
        while (true) {
            delay(5000)
            // mine is Main-confined: read it there, and require 3 straight
            // empty reads before stopping (a single stale zero must never
            // kill the watch with numbers still waiting).
            val pending = withContext(Dispatchers.Main) { pendingCount() }
            if (pending == 0 || !SmsGateway.isConfigured) {
                if (++idleRounds >= 3) {
                    stopSelf()
                    break
                }
            } else {
                idleRounds = 0
                try {
                    notifyWaiting(pending)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (mgr.getNotificationChannel(CHANNEL) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL, "Waiting for SMS", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun goForeground(pending: Int) {
        val n = buildWaiting(pending)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIF_ID, n)
        }
    }

    private fun notifyWaiting(pending: Int) {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        mgr.notify(NOTIF_ID, buildWaiting(pending))
    }

    private fun buildWaiting(pending: Int): android.app.Notification {
        val openPending = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopPending = PendingIntent.getService(
            this, 0,
            Intent(this, SmsOtpService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = if (pending == 1) "1 number waiting for SMS" else "$pending numbers waiting for SMS"
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Waiting for SMS...")
            .setContentText(text)
            .setContentIntent(openPending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Stop", stopPending)
            .build()
    }
}
