package com.mizan.launcher.core

/**
 * v2.0: the Quick settings popover's target table (pure data).
 *
 * Each action string equals the corresponding
 * `android.provider.Settings.ACTION_*` constant (repeated as literals
 * so this file stays Android-free and unit-testable). The popover
 * pre-filters targets whose intent does not resolve on the device —
 * a missing settings screen simply never appears, and "Sreeb Settings"
 * (empty action = open the launcher's own SettingsActivity) is always
 * present, last.
 */
object QuickSettings {

    data class Target(val label: String, val action: String)

    val TARGETS: List<Target> = listOf(
        Target("Wi-Fi", "android.settings.WIFI_SETTINGS"),
        Target("Bluetooth", "android.settings.BLUETOOTH_SETTINGS"),
        Target("Display", "android.settings.DISPLAY_SETTINGS"),
        Target("Sound", "android.settings.SOUND_SETTINGS"),
        Target("Storage", "android.settings.INTERNAL_STORAGE_SETTINGS"),
        Target("About device", "android.settings.DEVICE_INFO_SETTINGS"),
        Target("Sreeb Settings", "")
    )

    /** Targets usable on a device where [resolves] answers per action. */
    fun visible(targets: List<Target>, resolves: (String) -> Boolean): List<Target> =
        targets.filter { it.action.isEmpty() || resolves(it.action) }
}
