package com.mizan.launcher.core

/**
 * Weather presentation logic — pure functions, no Android imports.
 *
 * Two v1.2 features live here:
 * 1. Units: the user's choice (Fahrenheit default / Celsius) maps to
 *    Open-Meteo request parameters and to the symbols the UI renders.
 * 2. Weather background: a condition chosen from the WMO weather code
 *    (current conditions first; the day's forecast as fallback) maps to
 *    a colour palette + a motif key the Android side draws once on a
 *    canvas (sun/moon disc, cloud bands, rain streaks, snow dots…).
 *    No animation, no downloaded images.
 */

/** Weather display units, chosen per profile in Settings. */
enum class WeatherUnits(
    /** Stored prefs value. */
    val key: String,
    /** Open-Meteo `temperature_unit` parameter. */
    val apiTemperatureUnit: String,
    /** Open-Meteo `windspeed_unit` parameter. */
    val apiWindSpeedUnit: String,
    /** Symbol rendered after a temperature ("72°F"). */
    val tempSymbol: String,
    /** Symbol rendered after a wind speed. */
    val windSymbol: String
) {
    FAHRENHEIT("fahrenheit", "fahrenheit", "mph", "°F", "mph"),
    CELSIUS("celsius", "celsius", "kmh", "°C", "km/h");

    companion object {
        /** Parse a stored value; anything unknown falls back to Fahrenheit (the default). */
        fun fromKey(key: String?): WeatherUnits =
            values().firstOrNull { it.key == key } ?: FAHRENHEIT
    }
}

/** The background conditions the weather wallpaper knows how to draw. */
enum class WeatherCondition(val motif: String) {
    CLEAR_DAY("sun"),
    CLEAR_NIGHT("moon"),
    PARTLY("partly"),
    CLOUDY("cloud"),
    FOG("fog"),
    RAIN("rain"),
    STORM("storm"),
    SNOW("snow")
}

/** Gradient pair + motif accent for one condition. Colours are ARGB. */
data class WeatherPalette(val top: Int, val bottom: Int, val accent: Int, val motif: String)

object WeatherLogic {

    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    /**
     * Map an Open-Meteo WMO weather code to a background condition.
     * Day/night matters only for the clear sky (sun vs moon); every
     * other group draws the same day and night. Unknown codes draw as
     * cloudy — a safe, neutral picture (fail-open).
     */
    fun conditionFor(wmoCode: Int, isDay: Boolean): WeatherCondition = when (wmoCode) {
        0, 1 -> if (isDay) WeatherCondition.CLEAR_DAY else WeatherCondition.CLEAR_NIGHT
        2 -> WeatherCondition.PARTLY
        3 -> WeatherCondition.CLOUDY
        45, 48 -> WeatherCondition.FOG
        51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> WeatherCondition.RAIN
        95, 96, 99 -> WeatherCondition.STORM
        71, 73, 75, 77, 85, 86 -> WeatherCondition.SNOW
        else -> WeatherCondition.CLOUDY
    }

    /** Rough "how bad is it" rank — used to pick the day's dominant forecast condition. */
    fun severity(condition: WeatherCondition): Int = when (condition) {
        WeatherCondition.STORM -> 7
        WeatherCondition.SNOW -> 6
        WeatherCondition.RAIN -> 5
        WeatherCondition.FOG -> 4
        WeatherCondition.CLOUDY -> 3
        WeatherCondition.PARTLY -> 2
        WeatherCondition.CLEAR_DAY, WeatherCondition.CLEAR_NIGHT -> 1
    }

    /**
     * The day's dominant condition from a list of forecast WMO codes:
     * the most severe group present wins (a day with an afternoon storm
     * should look like a storm day, not like its clear morning). Ties
     * keep the earliest code. Empty list → null (no forecast).
     */
    fun dominantCondition(dailyCodes: List<Int>, isDay: Boolean = true): WeatherCondition? {
        var best: WeatherCondition? = null
        var bestSeverity = -1
        for (code in dailyCodes) {
            val c = conditionFor(code, isDay)
            val s = severity(c)
            if (s > bestSeverity) {
                best = c
                bestSeverity = s
            }
        }
        return best
    }

    /**
     * The background condition to draw: current conditions when known,
     * otherwise the day's dominant forecast condition, otherwise null
     * (caller falls back to the theme gradient — fail-open).
     */
    fun chooseCondition(currentCode: Int?, currentIsDay: Boolean, dailyCodes: List<Int>): WeatherCondition? {
        if (currentCode != null) return conditionFor(currentCode, currentIsDay)
        return dominantCondition(dailyCodes, currentIsDay)
    }

    /** Palette for a condition — our own colour values, fully opaque. */
    fun paletteFor(condition: WeatherCondition): WeatherPalette = when (condition) {
        WeatherCondition.CLEAR_DAY ->
            WeatherPalette(argb(38, 96, 168), argb(126, 178, 226), argb(255, 203, 92), "sun")
        WeatherCondition.CLEAR_NIGHT ->
            WeatherPalette(argb(6, 10, 32), argb(24, 32, 66), argb(214, 222, 245), "moon")
        WeatherCondition.PARTLY ->
            WeatherPalette(argb(52, 88, 138), argb(118, 148, 186), argb(255, 214, 130), "partly")
        WeatherCondition.CLOUDY ->
            WeatherPalette(argb(74, 84, 98), argb(44, 50, 60), argb(178, 190, 204), "cloud")
        WeatherCondition.FOG ->
            WeatherPalette(argb(112, 118, 124), argb(70, 74, 80), argb(205, 210, 214), "fog")
        WeatherCondition.RAIN ->
            WeatherPalette(argb(30, 48, 72), argb(14, 24, 40), argb(120, 178, 232), "rain")
        WeatherCondition.STORM ->
            WeatherPalette(argb(24, 24, 46), argb(8, 8, 20), argb(255, 222, 92), "storm")
        WeatherCondition.SNOW ->
            WeatherPalette(argb(128, 152, 182), argb(84, 104, 134), argb(238, 244, 252), "snow")
    }
}
