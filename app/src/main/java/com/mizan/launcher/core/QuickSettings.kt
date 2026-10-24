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
 *
 * v3.1 (ask 41): "Sort sections" is an INTERNAL target (action
 * scheme `sreeb:` — not a system settings action). Internal
 * targets bypass the resolvability filter (they always resolve,
 * to the launcher's own SettingsActivity at that page) and the
 * click handler routes them by scheme, never via startActivity
 * on the raw action string.
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
        Target("Sort sections", "sreeb:sortsections"),
        Target("Sreeb Settings", "")
    )

    /** True for launcher-internal targets (scheme `sreeb:` — always resolvable). */
    fun isInternal(target: Target): Boolean = target.action.startsWith("sreeb:")

    /** Targets usable on a device where [resolves] answers per action. */
    fun visible(targets: List<Target>, resolves: (String) -> Boolean): List<Target> =
        targets.filter { it.action.isEmpty() || isInternal(it) || resolves(it.action) }
}
