package net.typeblog.socks

import android.app.Application
import android.os.Build
import androidx.preference.PreferenceManager
import net.typeblog.socks.util.Constants.PREF_ADV_APP_BYPASS
import net.typeblog.socks.util.Constants.PREF_ADV_APP_LIST
import net.typeblog.socks.util.Constants.PREF_ADV_PER_APP
import net.typeblog.socks.util.Constants.PREF_SPLIT_SINGLE_MODE_MIGRATED
import net.typeblog.socks.util.ProfileManager
import net.typeblog.socks.util.SmsWatcher

class SocksApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Crash catcher first: another app's logcat cannot see our
        // process, so uncaught exceptions persist to a file shown next
        // launch with a copy button.
        net.typeblog.socks.util.CrashLog.install(this)

        // Ensure default preference values are set before reading
        PreferenceManager.setDefaultValues(this, R.xml.settings, false)
        migrateSplitSingleMode()
        // App-scoped SMS polling + OTP notifications (SMS tab state
        // outlives the tab; runs while the app process is alive).
        SmsWatcher.start(this)

        // Re-mirror on every launch. SheetStore only instantiates when the
        // Sheet tab, the bubble grid or the Backup page is opened, so without
        // this the copy in Downloads could sit stale for days for someone who
        // never visits that tab - and a user who deleted the file would not
        // get it back until they happened to open the Sheet screen.
        //
        // Main process only: the VPN service runs in :vpn and initialises
        // this class too, and two processes racing the delete-then-insert
        // replace would leave a duplicate in Downloads.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P ||
            Application.getProcessName() == packageName
        ) {
            net.typeblog.socks.util.sheet.SheetBackup.schedule(this)
        }
    }

    /**
     * One-time migration for the single-mode (Include-only) split-tunneling
     * rework: wipes split-tunnel config (global keys + per-profile perapp /
     * appbypass / applist) while keeping proxy profiles (server, port,
     * credentials, route, dns) untouched, so split tunneling starts OFF for
     * updaters. Idempotent; safe to run in every process.
     */
    private fun migrateSplitSingleMode() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        if (prefs.getBoolean(PREF_SPLIT_SINGLE_MODE_MIGRATED, false)) return
        prefs.edit()
            .remove(PREF_ADV_PER_APP)
            .remove(PREF_ADV_APP_BYPASS)
            .remove(PREF_ADV_APP_LIST)
            .putBoolean(PREF_SPLIT_SINGLE_MODE_MIGRATED, true)
            .apply()
        try {
            val manager = ProfileManager.getInstance(this)
            for (name in manager.getProfiles()) {
                val profile = manager.getProfile(name) ?: continue
                if (profile.isPerApp() || profile.isBypassApp() || profile.getAppList().isNotEmpty()) {
                    profile.setIsPerApp(false)
                    profile.setIsBypassApp(false)
                    profile.setAppList("")
                }
            }
        } catch (_: Exception) {
            // Profiles stay as-is; engine treats any surviving perapp as
            // Include-only, so no Exclude behavior can leak through.
        }
    }
}
