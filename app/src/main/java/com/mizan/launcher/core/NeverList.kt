package com.mizan.launcher.core

/**
 * The never-kill list — pure data + matcher, no Android imports.
 *
 * This is ALL that remains of the v1.0–v1.4 Cleanup classifier in
 * v1.5: the Cleanup screen, its SAFE/REVIEW categories, and the ADB
 * export are gone (Haythem's order), but "⚡ Speed up now" survives,
 * and its kill list must never signal the packages the TV needs to
 * run. So the never-list data stays, as Speed Up's exclusion list —
 * nothing else uses it.
 *
 * A package is NEVER when its name matches, in order:
 *  1. the exact set (Play services, Google TV's home screen, Play
 *     Store, Settings, System UI, setup, Bluetooth, Mizan itself),
 *  2. a load-bearing prefix (framework / input-method trees),
 *  3. a load-bearing substring (keyboards, tuners, TV input…).
 */
object NeverList {

    private val NEVER_EXACT = setOf(
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.android.vending",
        "com.google.android.apps.tv.launcherx",
        "com.google.android.tvlauncher",
        "com.google.android.leanbacklauncher",
        "com.android.settings",
        "com.android.tv.settings",
        "com.android.systemui",
        "com.android.provision",
        "com.google.android.tungsten.setupwraith",
        "com.android.bluetooth",
        "com.mizan.launcher"
    )

    /** Substrings that mark a package as load-bearing, wherever they appear. */
    private val NEVER_CONTAINS = listOf(
        "setupwraith",
        ".setup",
        "systemui",
        "tvinput",
        ".tuner",
        "inputmethod",
        ".bluetooth",
        "tv.framework",
        ".ime",
        "keyboard"
    )

    /** Prefixes that are always load-bearing vendor/Google framework trees. */
    private val NEVER_PREFIX = listOf(
        "com.google.android.gms",
        "com.google.android.inputmethod",
        "com.android.inputmethod",
        "com.google.android.tv.framework"
    )

    /** True when [pkg] must never be signalled by Speed Up. */
    fun isNever(pkg: String): Boolean {
        val p = pkg.lowercase()
        if (p in NEVER_EXACT) return true
        if (NEVER_PREFIX.any { p.startsWith(it) }) return true
        if (NEVER_CONTAINS.any { p.contains(it) }) return true
        return false
    }
}
