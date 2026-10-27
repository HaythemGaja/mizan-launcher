package com.mizan.launcher.core

/**
 * Status-bar network label incl. the Wi-Fi network name (v1.3) — pure.
 *
 * Android reports the SSID via WifiManager with surrounding quotes
 * ("\"HomeWifi\"") and uses the sentinel "<unknown ssid>" when the
 * name isn't available to the app — which on many Google TV builds is
 * whenever the app lacks location permission. Mizan never asks for
 * location permission on the home screen; an unknown SSID simply means
 * the label falls back to plain "Wi-Fi" (icon-only naming), exactly
 * the pre-v1.3 behaviour. If the user grants location permission via
 * system settings, the real name appears on the next refresh.
 */
object WifiLabel {

    /** Transport kinds the status bar distinguishes. */
    enum class Kind { WIFI, ETHERNET, OTHER, OFFLINE }

    /**
     * Clean a raw SSID from WifiManager: strip quotes and whitespace.
     * Returns null for anything that isn't a usable name (null, empty,
     * the "<unknown ssid>" sentinel, "unknown", a bare quote pair).
     */
    fun cleanSsid(raw: String?): String? {
        if (raw == null) return null
        var s = raw.trim()
        if (s.length >= 2 && s.first() == '"' && s.last() == '"') {
            s = s.substring(1, s.length - 1).trim()
        }
        if (s.isEmpty()) return null
        if (s.equals("<unknown ssid>", ignoreCase = true)) return null
        if (s.equals("unknown", ignoreCase = true)) return null
        return s
    }

    /**
     * The full status-bar network text. Wi-Fi with a known SSID shows
     * the name next to the Wi-Fi glyph ("📶 HomeWifi"); without one it
     * shows the generic transport label, as before v1.3.
     */
    fun label(kind: Kind, rawSsid: String?): String = when (kind) {
        Kind.WIFI -> cleanSsid(rawSsid)?.let { "📶 $it" } ?: "● Wi-Fi"
        Kind.ETHERNET -> "● Ethernet"
        Kind.OTHER -> "● Online"
        Kind.OFFLINE -> "● Offline"
    }

    /**
     * The v2.1 status-cluster glyph for a transport kind: a compact
     * symbol with NO word label (the wordy "• Wi-Fi" text beside the
     * weather was the clutter Haythem's photo called out). [glyphAlpha]
     * dims it when the transport is off/absent.
     */
    fun glyph(kind: Kind): String = when (kind) {
        Kind.WIFI -> "📶"
        Kind.ETHERNET -> "🌐"
        Kind.OTHER -> "🌐"
        Kind.OFFLINE -> "📶"
    }

    /** Opacity for [glyph]: connected = full, other = soft, offline = dim. */
    fun glyphAlpha(kind: Kind): Float = when (kind) {
        Kind.WIFI -> 1f
        Kind.ETHERNET -> 1f
        Kind.OTHER -> 0.7f
        Kind.OFFLINE -> 0.35f
    }
}
