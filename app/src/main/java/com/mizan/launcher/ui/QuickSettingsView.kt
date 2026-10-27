package com.mizan.launcher.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.LinearLayout

/** Quick-settings popover (MAP §1.1): system intents filtered if unresolvable, plus internal rows. */
object QuickSettingsView {
    fun show(ctx: Context, onSortSections: () -> Unit, onRefreshHome: () -> Unit, onOpenSettings: () -> Unit) {
        val systemRows = listOf(
            "Wi-Fi" to Settings.ACTION_WIFI_SETTINGS,
            "Bluetooth" to Settings.ACTION_BLUETOOTH_SETTINGS,
            "Display" to Settings.ACTION_DISPLAY_SETTINGS,
            "Sound" to Settings.ACTION_SOUND_SETTINGS,
            "Storage" to Settings.ACTION_INTERNAL_STORAGE_SETTINGS,
            "About device" to Settings.ACTION_DEVICE_INFO_SETTINGS
        )
        val items = mutableListOf<Pair<String, () -> Unit>>()
        val pm = ctx.packageManager
        systemRows.forEach { (label, action) ->
            val intent = Intent(action)
            if (intent.resolveActivity(pm) != null) items += label to { ctx.startActivity(intent) }
        }
        items += "Sort sections" to onSortSections
        items += "Refresh home data" to onRefreshHome
        items += "Sreeb Settings" to onOpenSettings
        Ui.menu(ctx, "Quick settings", items)
    }
    /** Status-bar view (TV style): clock, date, weather, network/Bluetooth glyphs, Wi-Fi SSID, quick tiles, profile tile, Settings gear, corner gear fallback. */
    fun statusBar(ctx: Context, clockText: String, dateText: String, weatherText: String, guestActive: Boolean, onForecast: () -> Unit, onSettings: () -> Unit): LinearLayout {
        val bar = LinearLayout(ctx); bar.orientation = LinearLayout.HORIZONTAL
        bar.addView(Ui.button(ctx, "$clockText · $dateText · $weatherText") { onForecast() })
        if (guestActive) bar.addView(Ui.text(ctx, "Guest", 16f))
        bar.addView(Ui.button(ctx, "⚙") { onSettings() })
        return bar
    }
}
