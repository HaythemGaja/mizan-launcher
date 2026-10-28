package com.calm.launcher.data

import android.content.Context
import com.calm.launcher.core.HomeModel

/** Saved launcher state. Every read falls back to a safe default. */
class Store(ctx: Context) {
    private val prefs = ctx.applicationContext.getSharedPreferences("calm_launcher", Context.MODE_PRIVATE)

    var favorites: List<String>
        get() = HomeModel.decodeList(prefs.getString(KEY_FAVORITES, null))
        set(v) { prefs.edit().putString(KEY_FAVORITES, HomeModel.encodeList(v)).apply() }

    var recent: List<String>
        get() = HomeModel.decodeList(prefs.getString(KEY_RECENT, null))
        set(v) { prefs.edit().putString(KEY_RECENT, HomeModel.encodeList(v)).apply() }

    var clock24h: Boolean
        get() = prefs.getBoolean(KEY_CLOCK_24H, false)
        set(v) { prefs.edit().putBoolean(KEY_CLOCK_24H, v).apply() }

    private companion object {
        const val KEY_FAVORITES = "favorites"
        const val KEY_RECENT = "recent"
        const val KEY_CLOCK_24H = "clock_24h"
    }
}
