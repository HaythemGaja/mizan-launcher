package com.calm.launcher.data

import android.content.Context
import com.calm.launcher.core.Folder
import com.calm.launcher.core.Folders
import com.calm.launcher.core.HomeModel
import com.calm.launcher.core.Profiles

/**
 * Saved state. Per-profile values live under "p<id>.<name>" keys; shared values
 * (profile list, current profile, arrow pattern) are unprefixed. Reads fall back safely.
 */
class Store(ctx: Context) {
    private val prefs = ctx.applicationContext.getSharedPreferences("calm_launcher", Context.MODE_PRIVATE)

    // ---- shared ----
    var profiles: List<Int>
        get() = Profiles.decode(prefs.getString(KEY_PROFILES, null))
        set(v) { prefs.edit().putString(KEY_PROFILES, Profiles.encode(v)).apply() }

    var currentProfile: Int
        get() = Profiles.resolveCurrent(prefs.getInt(KEY_CURRENT, 1), profiles)
        set(v) { prefs.edit().putInt(KEY_CURRENT, v).apply() }

    /** The arrow lock pattern, e.g. "UUDL". Empty means no lock is set. */
    var arrowPattern: String
        get() = prefs.getString(KEY_PATTERN, "") ?: ""
        set(v) { prefs.edit().putString(KEY_PATTERN, v).apply() }

    // ---- per profile ----
    var favorites: List<String>
        get() = HomeModel.decodeList(str("favorites"))
        set(v) = putStr("favorites", HomeModel.encodeList(v))

    var recent: List<String>
        get() = HomeModel.decodeList(str("recent"))
        set(v) = putStr("recent", HomeModel.encodeList(v))

    var folders: List<Folder>
        get() = Folders.decode(str("folders"))
        set(v) = putStr("folders", Folders.encode(v))

    /** Locked items as tokens: "app:<pkg>" or "folder:<id>". */
    var locked: List<String>
        get() = HomeModel.decodeList(str("locked"))
        set(v) = putStr("locked", HomeModel.encodeList(v))

    var wallpaperIndex: Int
        get() = prefs.getInt(key("wallpaper"), 0)
        set(v) { prefs.edit().putInt(key("wallpaper"), v).apply() }

    var clock24h: Boolean
        get() = prefs.getBoolean(key("clock24h"), false)
        set(v) { prefs.edit().putBoolean(key("clock24h"), v).apply() }

    /** Add a profile in the lowest free slot. Returns its id, or null when full. */
    fun addProfile(): Int? {
        val id = Profiles.add(profiles) ?: return null
        profiles = profiles + id
        return id
    }

    /** Delete one profile's own settings and drop it from the list. */
    fun deleteProfile(id: Int): Boolean {
        val remaining = Profiles.remove(profiles, id) ?: return false
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith("p$id.") }.forEach { editor.remove(it) }
        editor.putString(KEY_PROFILES, Profiles.encode(remaining)).apply()
        if (currentProfile == id) currentProfile = remaining.min()
        return true
    }

    private fun key(name: String) = Profiles.key(currentProfile, name)
    private fun str(name: String): String? = prefs.getString(key(name), null)
    private fun putStr(name: String, value: String) { prefs.edit().putString(key(name), value).apply() }

    private companion object {
        const val KEY_PROFILES = "profiles"
        const val KEY_CURRENT = "current_profile"
        const val KEY_PATTERN = "arrow_pattern"
    }
}
