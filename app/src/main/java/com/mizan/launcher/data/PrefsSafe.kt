package com.mizan.launcher.data

import android.content.SharedPreferences

/**
 * Type-tolerant SharedPreferences reads (v3.0, spec §30) — the
 * structural fix for the prefs ClassCastException class.
 *
 * Android's typed getters THROW ClassCastException when the stored
 * value's type differs from the requested one. Every reader in this
 * app shares ONE prefs file ("mizan_launcher"), and a wrong-typed
 * value is not hypothetical: backup restores plant values, stored
 * types changed across versions (the retired numeric PIN is the
 * documented case), and the tracker-snapshot documents live in the
 * same file. Before v3.0 almost every getter in data/Prefs and the
 * data clients read raw — one bad value meant an exception on a hot
 * path on EVERY launch: a crash/black-screen loop only clearing
 * the app's data could break.
 *
 * These helpers are the single funnel: a mismatched read EVICTS
 * the offending key (best-effort) and returns the caller's default,
 * so the app self-heals on the first bad read and the setting
 * simply reverts to its default — the fail-open outcome every
 * caller already expects from a missing key.
 */
object PrefsSafe {

    private fun evict(sp: SharedPreferences, key: String) {
        try {
            sp.edit().remove(key).apply()
        } catch (ignored: Throwable) {
            // Eviction is best-effort; the default already served.
        }
    }

    /** Stored string, or [def] when absent/null/wrong-typed (wrong type also evicts). */
    fun str(sp: SharedPreferences, key: String, def: String): String =
        try {
            sp.getString(key, def) ?: def
        } catch (e: ClassCastException) {
            evict(sp, key)
            def
        }

    /** Stored string or null (absent and wrong-typed both read as null). */
    fun strOrNull(sp: SharedPreferences, key: String): String? =
        try {
            sp.getString(key, null)
        } catch (e: ClassCastException) {
            evict(sp, key)
            null
        }

    fun int(sp: SharedPreferences, key: String, def: Int): Int =
        try {
            sp.getInt(key, def)
        } catch (e: ClassCastException) {
            evict(sp, key)
            def
        }

    fun long(sp: SharedPreferences, key: String, def: Long): Long =
        try {
            sp.getLong(key, def)
        } catch (e: ClassCastException) {
            evict(sp, key)
            def
        }

    fun bool(sp: SharedPreferences, key: String, def: Boolean): Boolean =
        try {
            sp.getBoolean(key, def)
        } catch (e: ClassCastException) {
            evict(sp, key)
            def
        }

    /** Stored string set, or empty when absent/wrong-typed. */
    fun stringSet(sp: SharedPreferences, key: String): Set<String> =
        try {
            sp.getStringSet(key, emptySet()) ?: emptySet()
        } catch (e: ClassCastException) {
            evict(sp, key)
            emptySet()
        }
}
