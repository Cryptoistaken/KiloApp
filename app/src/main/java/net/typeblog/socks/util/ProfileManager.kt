package net.typeblog.socks.util

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import net.typeblog.socks.R
import net.typeblog.socks.util.Constants.PREF
import net.typeblog.socks.util.Constants.PREF_LAST_PROFILE
import net.typeblog.socks.util.Constants.PREF_PROFILE

/**
 * One stored profile setting, decrypted, for the backup mirror.
 *
 * The encrypted prefs file itself is worthless off-device: the master key is
 * a non-exportable AndroidKeyStore entry, so restoring its bytes onto another
 * phone yields prefs the app cannot read and ProfileManager wipes on sight.
 * The backup therefore carries the plaintext values with their type, and
 * importEntries writes them back through the ordinary path so the receiving
 * device re-encrypts them under its own key.
 */
data class ProfileEntry(val key: String, val type: String, val value: String)

class ProfileManager private constructor(context: Context) {
    private val mContext: Context = context.applicationContext
    private val mPref: SharedPreferences
    private val mFactory: ProfileFactory
    private val mProfiles = ArrayList<String>()

    init {
        mPref = try {
            createEncryptedPrefs(mContext)
        } catch (e: Exception) {
            try {
                mContext.deleteSharedPreferences(PREF)
                try {
                    val ks = java.security.KeyStore.getInstance("AndroidKeyStore")
                    ks.load(null)
                    ks.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
                } catch (_: Exception) {
                }
            } catch (_: Exception) {
            }
            createEncryptedPrefs(mContext)
        }
        mFactory = ProfileFactory.getInstance(mContext, mPref)
        reload()
    }

    private fun createEncryptedPrefs(ctx: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            ctx,
            PREF,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    @Synchronized
    fun reload() {
        mProfiles.clear()
        val defaultName = mContext.getString(R.string.prof_default)
        mProfiles.add(defaultName)
        val raw = mPref.getString(PREF_PROFILE, "") ?: ""
        val profiles = if (raw.isEmpty()) emptyList() else raw.split("\n").filter { it.isNotEmpty() }
        for (p in profiles) {
            if (p != defaultName) {
                mProfiles.add(p)
            }
        }
    }

    @Synchronized
    fun getProfiles(): Array<String> {
        return mProfiles.toTypedArray()
    }

    fun getProfile(name: String): Profile? {
        synchronized(this) { if (!mProfiles.contains(name)) return null }
        return mFactory.getProfile(name)
    }

    fun getDefault(): Profile {
        val key = synchronized(this) { mPref.getString(PREF_LAST_PROFILE, mProfiles[0])!! }
        return getProfile(key)!!
    }

    fun switchDefault(name: String) {
        synchronized(this) { if (!mProfiles.contains(name)) return }
        mPref.edit().putString(PREF_LAST_PROFILE, name).apply()
    }

    @Synchronized
    fun addProfile(name: String): Profile? {
        if (mProfiles.contains(name)) return null
        mProfiles.add(name)
        mPref.edit()
            .putString(PREF_PROFILE, Utility.join(mProfiles.drop(1), "\n"))
            .putString(PREF_LAST_PROFILE, name)
            .apply()
        reload()
        return getDefault()
    }

    @Synchronized
    fun removeProfile(name: String): Boolean {
        if (name == mProfiles[0] || !mProfiles.contains(name)) return false
        getProfile(name)!!.delete()
        mProfiles.remove(name)
        mPref.edit()
            .putString(PREF_PROFILE, Utility.join(mProfiles.drop(1), "\n"))
            .remove(PREF_LAST_PROFILE)
            .apply()
        reload()
        return true
    }

    @Synchronized
    fun renameProfile(oldName: String, newName: String): Boolean {
        if (oldName == newName) return true
        if (!mProfiles.contains(oldName)) return false
        if (oldName == mProfiles[0] || mProfiles.contains(newName)) return false
        val oldProfile = getProfile(oldName)!!
        oldProfile.copyTo(newName)
        oldProfile.delete()
        mProfiles[mProfiles.indexOf(oldName)] = newName
        val editor = mPref.edit()
            .putString(PREF_PROFILE, Utility.join(mProfiles.drop(1), "\n"))
        if (mPref.getString(PREF_LAST_PROFILE, null) == oldName) {
            editor.putString(PREF_LAST_PROFILE, newName)
        }
        editor.apply()
        reload()
        return true
    }

    /**
     * Backup: every profile setting this file holds, decrypted. Covers the
     * profile name list, the active profile and each profile's server, port,
     * credentials, DNS, route, per-app list and flags, because they all live
     * under the same prefs file.
     */
    @Synchronized
    fun exportEntries(): List<ProfileEntry> = mPref.all.mapNotNull { (k, v) ->
        when (v) {
            is String -> ProfileEntry(k, "s", v)
            is Int -> ProfileEntry(k, "i", v.toString())
            is Boolean -> ProfileEntry(k, "b", v.toString())
            is Float -> ProfileEntry(k, "f", v.toString())
            is Long -> ProfileEntry(k, "l", v.toString())
            // String sets and anything else are not written by Profile today;
            // skipping is safer than guessing a lossy encoding for them.
            else -> null
        }
    }

    /**
     * Load-backup: overwrite the settings the backup carries. Keys absent from
     * the backup are left alone rather than deleted, so a restore can never
     * remove a setting the older backup simply predates.
     */
    @Synchronized
    fun importEntries(entries: List<ProfileEntry>) {
        if (entries.isEmpty()) return
        val ed = mPref.edit()
        for (e in entries) {
            when (e.type) {
                "s" -> ed.putString(e.key, e.value)
                "i" -> e.value.toIntOrNull()?.let { ed.putInt(e.key, it) }
                "b" -> ed.putBoolean(e.key, e.value.toBoolean())
                "f" -> e.value.toFloatOrNull()?.let { ed.putFloat(e.key, it) }
                "l" -> e.value.toLongOrNull()?.let { ed.putLong(e.key, it) }
            }
        }
        ed.apply()
        reload()
    }

    /**
     * Human-readable form of the same profile settings exportEntries returns,
     * for the config file in the backup folder. Goes through the typed getters
     * rather than the raw pref keys, which are url-encoded profile names with
     * a suffix and are unreadable to a person.
     */
    @Synchronized
    fun exportReadable(): String {
        val sb = StringBuilder()
        sb.append("KiloApp proxy profiles\n")
        sb.append("Written ").append(
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
                .format(java.util.Date())
        ).append("\n")
        sb.append("Note: this file is plain text and holds your proxy credentials.\n")
        for (name in mProfiles) {
            val p = mFactory.getProfile(name) ?: continue
            sb.append("\n[").append(name).append("]\n")
            sb.append("server    = ").append(p.getServer()).append('\n')
            sb.append("port      = ").append(p.getPort()).append('\n')
            sb.append("username  = ").append(p.getUsername()).append('\n')
            sb.append("password  = ").append(p.getPassword()).append('\n')
            sb.append("dns       = ").append(p.getDns()).append(':').append(p.getDnsPort()).append('\n')
            sb.append("route     = ").append(p.getRoute()).append('\n')
            sb.append("ipv6      = ").append(p.hasIPv6()).append('\n')
            sb.append("udp       = ").append(p.hasUDP()).append('\n')
            sb.append("autostart = ").append(p.autoConnect()).append('\n')
            sb.append("per app   = ").append(p.isPerApp()).append('\n')
            if (p.isPerApp()) {
                sb.append("apps      = ").append(p.getAppList().replace(',', ' ')).append('\n')
            }
        }
        return sb.toString()
    }

    companion object {
        @Volatile
        private var sInstance: ProfileManager? = null

        @Synchronized
        fun getInstance(context: Context): ProfileManager {
            sInstance?.let { return it }
            val inst = ProfileManager(context.applicationContext)
            sInstance = inst
            return inst
        }
    }
}
