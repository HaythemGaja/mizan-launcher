package com.mizan.launcher.data

import android.content.SharedPreferences

/**
 * Type-tolerant prefs funnel (MAP §4/§6): every read goes through
 * here; a wrong-typed stored value evicts itself and reads as its
 * default. Never throws.
 */
object PrefsSafe {
    fun str(sp: SharedPreferences, key: String, def: String): String = try {
        sp.getString(key, def) ?: def
    } catch (e: ClassCastException) {
        try { sp.edit().remove(key).apply() } catch (ignored: Throwable) { }
        def
    } catch (e: Throwable) { def }

    fun strOrNull(sp: SharedPreferences, key: String): String? = try {
        sp.getString(key, null)
    } catch (e: ClassCastException) {
        try { sp.edit().remove(key).apply() } catch (ignored: Throwable) { }
        null
    } catch (e: Throwable) { null }

    fun bool(sp: SharedPreferences, key: String, def: Boolean): Boolean = try {
        sp.getBoolean(key, def)
    } catch (e: ClassCastException) {
        try { sp.edit().remove(key).apply() } catch (ignored: Throwable) { }
        def
    } catch (e: Throwable) { def }

    fun int(sp: SharedPreferences, key: String, def: Int): Int = try {
        sp.getInt(key, def)
    } catch (e: ClassCastException) {
        try { sp.edit().remove(key).apply() } catch (ignored: Throwable) { }
        def
    } catch (e: Throwable) { def }

    fun long(sp: SharedPreferences, key: String, def: Long): Long = try {
        sp.getLong(key, def)
    } catch (e: ClassCastException) {
        try { sp.edit().remove(key).apply() } catch (ignored: Throwable) { }
        def
    } catch (e: Throwable) { def }

    fun stringSet(sp: SharedPreferences, key: String): Set<String> = try {
        sp.getStringSet(key, emptySet()) ?: emptySet()
    } catch (e: ClassCastException) {
        try { sp.edit().remove(key).apply() } catch (ignored: Throwable) { }
        emptySet()
    } catch (e: Throwable) { emptySet() }

    fun setStringSet(sp: SharedPreferences, key: String, v: Set<String>) {
        try { sp.edit().putStringSet(key, v).apply() } catch (ignored: Throwable) { }
    }

    fun has(sp: SharedPreferences, key: String): Boolean = try {
        sp.contains(key)
    } catch (e: Throwable) { false }

    fun setStr(sp: SharedPreferences, key: String, v: String) {
        try { sp.edit().putString(key, v).apply() } catch (ignored: Throwable) { }
    }

    fun setBool(sp: SharedPreferences, key: String, v: Boolean) {
        try { sp.edit().putBoolean(key, v).apply() } catch (ignored: Throwable) { }
    }

    fun setInt(sp: SharedPreferences, key: String, v: Int) {
        try { sp.edit().putInt(key, v).apply() } catch (ignored: Throwable) { }
    }

    fun setLong(sp: SharedPreferences, key: String, v: Long) {
        try { sp.edit().putLong(key, v).apply() } catch (ignored: Throwable) { }
    }

    fun remove(sp: SharedPreferences, key: String) {
        try { sp.edit().remove(key).apply() } catch (ignored: Throwable) { }
    }
}
