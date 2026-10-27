package com.mizan.launcher.core

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Weather forecast screen logic — pure functions, no Android imports.
 *
 * v1.4: the status-bar weather widget opens a full forecast. The data
 * comes from the SAME Open-Meteo call as the current conditions
 * (hourly temperature_2m/weather_code + daily hi/lo/precipitation),
 * cached alongside them. This file only shapes and formats that data:
 * - the hourly strip is the 24 hours starting at the CURRENT hour
 *   (indices wrap around the end of the hourly array, so a fetch made
 *   late in the forecast range still yields a full strip),
 * - day rows carry a weekday label, a short condition word, hi/lo in
 *   the profile's units, and precipitation probability when the API
 *   provided it.
 * Formatting is unit-aware throughout (WeatherUnits), and hour labels
 * follow the profile's 12/24h choice.
 */

/** One hour of forecast, as parsed from Open-Meteo's hourly arrays. */
data class HourPoint(
    /** Local ISO time from the API, e.g. "2026-10-20T14:00". */
    val timeIso: String,
    val temp: Double,
    /** WMO weather code. */
    val code: Int
)

/** One day of forecast, as parsed from Open-Meteo's daily arrays. */
data class DayPoint(
    /** Local ISO date from the API, e.g. "2026-10-20". */
    val dateIso: String,
    /** WMO weather code for the day. */
    val code: Int,
    val hi: Double,
    val lo: Double,
    /** Precipitation probability in percent, or null when unavailable. */
    val precipProb: Int?
)

/** One ready-to-render hourly cell. */
data class HourRow(val label: String, val temp: String, val condition: String)

/** One ready-to-render day row. [precip] is null when the API gave none. */
data class DayRow(val label: String, val condition: String, val hiLo: String, val precip: String?)

object ForecastLogic {

    /** Hours in the forecast strip. */
    const val STRIP_HOURS = 24

    /**
     * Index of the hour the user is currently in. Open-Meteo hourly
     * times are local ISO strings; [nowKey] is the same format's hour
     * prefix ("yyyy-MM-dd'T'HH", built by the Android side) and the
     * match is a plain prefix test. No match → -1 (caller starts at 0).
     */
    fun currentHourIndex(times: List<String>, nowKey: String): Int {
        if (nowKey.isEmpty()) return -1
        for (i in times.indices) {
            if (times[i].startsWith(nowKey)) return i
        }
        return -1
    }

    /**
     * The [count] hours starting at [startIndex], wrapping around the
     * end of the list (modulo) so the strip is always full when the
     * source has at least [count] entries total across the wrap.
     * A negative start clamps to 0; an empty source yields an empty
     * strip; a source shorter than [count] yields the whole source.
     */
    fun hourWindow(hours: List<HourPoint>, startIndex: Int, count: Int = STRIP_HOURS): List<HourPoint> {
        if (hours.isEmpty() || count <= 0) return emptyList()
        val start = startIndex.coerceIn(0, hours.size - 1)
        val n = minOf(count, hours.size)
        val out = ArrayList<HourPoint>(n)
        for (i in 0 until n) {
            out.add(hours[(start + i) % hours.size])
        }
        return out
    }

    /** Hour-of-day (0–23) parsed from an ISO local time; -1 when unparseable. */
    fun hourOfDay(timeIso: String): Int {
        val t = timeIso.indexOf('T')
        if (t < 0 || t + 2 >= timeIso.length) return -1
        return timeIso.substring(t + 1, t + 3).toIntOrNull() ?: -1
    }

    /**
     * "2 PM" / "11 AM" in 12-hour mode, "14:00" in 24-hour mode.
     * Unparseable input renders as an empty label (fail-open).
     */
    fun hourLabel(timeIso: String, use24h: Boolean): String {
        val h = hourOfDay(timeIso)
        if (h < 0) return ""
        return if (use24h) {
            "%02d:00".format(h)
        } else {
            val hour12 = when {
                h == 0 -> 12
                h > 12 -> h - 12
                else -> h
            }
            val suffix = if (h < 12) "AM" else "PM"
            "$hour12 $suffix"
        }
    }

    /** Temperature with the unit symbol, rounded to whole degrees ("72°F"). */
    fun tempText(temp: Double, units: WeatherUnits): String =
        "${Math.round(temp)}${units.tempSymbol}"

    /** Short condition word for a WMO code (from the background condition groups). */
    fun conditionLabel(code: Int): String = when (WeatherLogic.conditionFor(code, true)) {
        WeatherCondition.CLEAR_DAY, WeatherCondition.CLEAR_NIGHT -> "Clear"
        WeatherCondition.PARTLY -> "Partly cloudy"
        WeatherCondition.CLOUDY -> "Cloudy"
        WeatherCondition.FOG -> "Fog"
        WeatherCondition.RAIN -> "Rain"
        WeatherCondition.STORM -> "Storm"
        WeatherCondition.SNOW -> "Snow"
    }

    /** One hourly cell, formatted. */
    fun hourRow(point: HourPoint, units: WeatherUnits, use24h: Boolean): HourRow = HourRow(
        label = hourLabel(point.timeIso, use24h),
        temp = tempText(point.temp, units),
        condition = conditionLabel(point.code)
    )

    /**
     * Weekday + date label from an ISO date ("Tue, Oct 20"). Parsed
     * with java.time (available on Android 26+ and the desktop JVM the
     * sim runs on); unparseable input falls back to the raw string.
     */
    fun dayLabel(dateIso: String): String {
        return try {
            val date = LocalDate.parse(dateIso)
            date.format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US))
        } catch (e: Exception) {
            dateIso
        }
    }

    /** One day row, formatted: hi/lo with units, precip when available. */
    fun dayRow(day: DayPoint, units: WeatherUnits): DayRow = DayRow(
        label = dayLabel(day.dateIso),
        condition = conditionLabel(day.code),
        hiLo = "${tempText(day.hi, units)} / ${tempText(day.lo, units)}",
        precip = day.precipProb?.let { "💧 $it%" }
    )
}
