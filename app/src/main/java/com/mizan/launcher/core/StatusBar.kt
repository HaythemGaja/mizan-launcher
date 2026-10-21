package com.mizan.launcher.core

/**
 * Status bar (top widget bar) configuration — pure data + codec.
 *
 * Everything is per-profile and persisted in the layout store's encoded
 * string (see LayoutCodec v2 lines), so one profile can run the clean
 * Apple-TV style (bar hidden) while another keeps the full bar.
 *
 * Defaults reproduce v1.0 behaviour exactly: bar shown, clock + date +
 * weather + network + quick tiles on, 12-hour clock, no seconds,
 * "Tue, Oct 20" date style, medium text, transparent background.
 */
data class StatusBarConfig(
    /** Master switch: false hides the whole bar (clean Apple-TV style). */
    val showBar: Boolean = true,
    val showClock: Boolean = true,
    val showDate: Boolean = true,
    val showWeather: Boolean = true,
    val showNetwork: Boolean = true,
    /** Bluetooth status icon (BT on/off) next to the network icon. */
    val showBluetooth: Boolean = true,
    /** The quick-settings tiles group (Wi-Fi/Bluetooth/Sound/Picture). */
    val showQuickTiles: Boolean = true,
    /** Per-tile switches (v1.3): a tile renders only when the master
     * [showQuickTiles] AND its own switch are both on. */
    val showTileWifi: Boolean = true,
    val showTileBluetooth: Boolean = true,
    /** Sound tile — independently hideable (Haythem's v1.3 ask). */
    val showTileSound: Boolean = true,
    val showTilePicture: Boolean = true,
    /**
     * The Search tile (v1.4): opens the app Search screen. Governed by
     * the same master + per-tile rule as the system tiles.
     */
    val showTileSearch: Boolean = true,
    /**
     * The All Apps tile (v1.5): opens the full-screen All Apps tab.
     * Governed by the same master + per-tile rule as the other tiles.
     */
    val showTileAllApps: Boolean = true,
    /**
     * The Settings gear tile in the bar. Hideable on its own — but the
     * corner gear that appears when the whole bar is hidden is a
     * separate safety element (see MainActivity) and is NOT this tile.
     */
    val showTileSettings: Boolean = true,
    /** true = 24-hour clock, false = 12-hour with AM/PM. */
    val clock24h: Boolean = false,
    val showSeconds: Boolean = false,
    /** Date style key: "weekday_month_day" | "iso" | "month_day_year". */
    val dateFormat: String = DATE_WEEKDAY_MONTH_DAY,
    /** Text size key: "small" | "medium" | "large". */
    val textSize: String = SIZE_MEDIUM,
    /** Bar background key: "transparent" | "theme" | "black". */
    val background: String = BG_TRANSPARENT,
    /** Background opacity: "solid" | "translucent". Ignored for transparent. */
    val opacity: String = OPACITY_SOLID
) {
    /** Clock pattern for java.text.SimpleDateFormat. */
    fun clockPattern(): String {
        val base = if (clock24h) "H:mm" else "h:mm"
        val withSeconds = if (showSeconds) base + ":ss" else base
        return if (clock24h) withSeconds else "$withSeconds a"
    }

    /** Date pattern for java.text.SimpleDateFormat. */
    fun datePattern(): String = DATE_PATTERNS[dateFormat] ?: DATE_PATTERNS.getValue(DATE_WEEKDAY_MONTH_DAY)

    /** Text scale factor applied to the bar's v1.0 text sizes. */
    fun textScale(): Float = when (textSize) {
        SIZE_SMALL -> 0.82f
        SIZE_LARGE -> 1.22f
        else -> 1.0f
    }

    fun isDefault(): Boolean = this == StatusBarConfig()

    companion object {
        const val DATE_WEEKDAY_MONTH_DAY = "weekday_month_day" // Tue, Oct 20
        const val DATE_ISO = "iso"                             // 2026-10-20
        const val DATE_MONTH_DAY_YEAR = "month_day_year"       // Oct 20, 2026

        val DATE_FORMATS: List<String> = listOf(DATE_WEEKDAY_MONTH_DAY, DATE_ISO, DATE_MONTH_DAY_YEAR)

        val DATE_LABELS: Map<String, String> = mapOf(
            DATE_WEEKDAY_MONTH_DAY to "Tue, Oct 20",
            DATE_ISO to "2026-10-20",
            DATE_MONTH_DAY_YEAR to "Oct 20, 2026"
        )

        val DATE_PATTERNS: Map<String, String> = mapOf(
            DATE_WEEKDAY_MONTH_DAY to "EEE, MMM d",
            DATE_ISO to "yyyy-MM-dd",
            DATE_MONTH_DAY_YEAR to "MMM d, yyyy"
        )

        const val SIZE_SMALL = "small"
        const val SIZE_MEDIUM = "medium"
        const val SIZE_LARGE = "large"
        val TEXT_SIZES: List<String> = listOf(SIZE_SMALL, SIZE_MEDIUM, SIZE_LARGE)

        const val BG_TRANSPARENT = "transparent"
        const val BG_THEME = "theme"
        const val BG_BLACK = "black"
        val BACKGROUNDS: List<String> = listOf(BG_TRANSPARENT, BG_THEME, BG_BLACK)

        const val OPACITY_SOLID = "solid"
        const val OPACITY_TRANSLUCENT = "translucent"
        val OPACITIES: List<String> = listOf(OPACITY_SOLID, OPACITY_TRANSLUCENT)

        /** ARGB alpha for a non-transparent background choice. */
        fun backgroundAlpha(opacity: String): Int = if (opacity == OPACITY_TRANSLUCENT) 0x99 else 0xFF
    }
}

/**
 * Codec for [StatusBarConfig] inside the layout string: one line.
 * v1.5 format: `status=<9 flags>|<7 tile flags>|<date>|<size>|<bg>|<opacity>`.
 * The tile segment only ever GROWS at the end: v1.4's 6-char segment
 * decodes with the All Apps tile ON (v1.5's default), v1.3's 5-char
 * segment with Search AND All Apps ON. v1.1/v1.2 format has no tile
 * segment (5 fields instead of 6) and decodes with every tile switch
 * ON (their behaviour). Values are validated against the known key
 * lists; unknown values fall back to defaults field-by-field
 * (fail-open), never throw.
 */
object StatusBarCodec {

    fun encode(c: StatusBarConfig): String {
        val flags = StringBuilder()
        for (b in listOf(c.showBar, c.showClock, c.showDate, c.showWeather, c.showNetwork,
            c.showBluetooth, c.showQuickTiles, c.clock24h, c.showSeconds)) {
            flags.append(if (b) '1' else '0')
        }
        val tiles = StringBuilder()
        for (b in listOf(c.showTileWifi, c.showTileBluetooth, c.showTileSound,
            c.showTilePicture, c.showTileSettings, c.showTileSearch, c.showTileAllApps)) {
            tiles.append(if (b) '1' else '0')
        }
        // Date/size/bg/opacity keys come from closed lists (letters, digits,
        // underscores only), so they need no escaping in the layout codec.
        return "$flags|$tiles|${c.dateFormat}|${c.textSize}|${c.background}|${c.opacity}"
    }

    fun decode(raw: String?): StatusBarConfig {
        if (raw.isNullOrBlank()) return StatusBarConfig()
        val parts = raw.split("|")
        val d = StatusBarConfig()
        val flags = parts.getOrNull(0).orEmpty()
        fun flag(i: Int, default: Boolean): Boolean = when (flags.getOrNull(i)) {
            '1' -> true
            '0' -> false
            else -> default
        }
        // Field positions shift by one when the v1.3 tile segment is
        // present (6 fields). A 5-field string is v1.1/v1.2: tiles all on.
        val hasTiles = parts.size >= 6
        val tiles = if (hasTiles) parts.getOrNull(1).orEmpty() else ""
        fun tile(i: Int, default: Boolean): Boolean = when (tiles.getOrNull(i)) {
            '1' -> true
            '0' -> false
            else -> default
        }
        val base = if (hasTiles) 2 else 1
        return StatusBarConfig(
            showBar = flag(0, d.showBar),
            showClock = flag(1, d.showClock),
            showDate = flag(2, d.showDate),
            showWeather = flag(3, d.showWeather),
            showNetwork = flag(4, d.showNetwork),
            showBluetooth = flag(5, d.showBluetooth),
            showQuickTiles = flag(6, d.showQuickTiles),
            clock24h = flag(7, d.clock24h),
            showSeconds = flag(8, d.showSeconds),
            showTileWifi = tile(0, d.showTileWifi),
            showTileBluetooth = tile(1, d.showTileBluetooth),
            showTileSound = tile(2, d.showTileSound),
            showTilePicture = tile(3, d.showTilePicture),
            showTileSettings = tile(4, d.showTileSettings),
            showTileSearch = tile(5, d.showTileSearch),
            showTileAllApps = tile(6, d.showTileAllApps),
            dateFormat = parts.getOrNull(base)?.takeIf { it in StatusBarConfig.DATE_FORMATS } ?: d.dateFormat,
            textSize = parts.getOrNull(base + 1)?.takeIf { it in StatusBarConfig.TEXT_SIZES } ?: d.textSize,
            background = parts.getOrNull(base + 2)?.takeIf { it in StatusBarConfig.BACKGROUNDS } ?: d.background,
            opacity = parts.getOrNull(base + 3)?.takeIf { it in StatusBarConfig.OPACITIES } ?: d.opacity
        )
    }
}
