package com.mizan.launcher.data

import com.mizan.launcher.core.DayPoint
import com.mizan.launcher.core.HourPoint
import com.mizan.launcher.core.WeatherUnits
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Weather from Open-Meteo (no API key), in the user's chosen units
 * (v1.2: Fahrenheit default, Celsius optional — per profile).
 *
 * fetch() runs on a background thread and delivers a [Snapshot]: the
 * display text for the status-bar widget plus the structured values
 * (WMO code, day/night, the day's forecast codes) that drive the
 * weather background. v1.4: the SAME call also requests the hourly
 * series and the full 7-day daily series (hi/lo/precipitation) for
 * the forecast screen, cached in the same snapshot — one fetch feeds
 * the widget, the background, and the forecast. Any failure delivers
 * null — the widget hides and the background falls back to the theme
 * gradient (fail-open).
 */
object Weather {

    /** One weather reading. [text] is the ready-to-render widget string. */
    data class Snapshot(
        val text: String,
        val code: Int,
        val isDay: Boolean,
        val temp: Double,
        val dailyCodes: List<Int>,
        /** v1.4: hourly series for the forecast strip (may be empty on old caches). */
        val hourly: List<HourPoint> = emptyList(),
        /** v1.4: full daily series for the forecast screen (may be empty on old caches). */
        val dailyFull: List<DayPoint> = emptyList()
    )

    fun fetch(lat: String, lon: String, units: WeatherUnits, callback: (Snapshot?) -> Unit) {
        val t = Thread {
            var result: Snapshot? = null
            try {
                val url = URL(
                    "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                        "&current=temperature_2m,weather_code,is_day" +
                        "&hourly=temperature_2m,weather_code" +
                        "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                        "&forecast_days=7&timezone=auto" +
                        "&temperature_unit=${units.apiTemperatureUnit}" +
                        "&windspeed_unit=${units.apiWindSpeedUnit}"
                )
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 6000
                conn.readTimeout = 6000
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(text)
                val current = root.getJSONObject("current")
                val temp = current.getDouble("temperature_2m")
                val code = current.optInt("weather_code", -1)
                val isDay = current.optInt("is_day", 1) == 1
                val daily = mutableListOf<Int>()
                val dailyFull = mutableListOf<DayPoint>()
                val dailyObj = root.optJSONObject("daily")
                val dailyArr = dailyObj?.optJSONArray("weather_code")
                if (dailyArr != null) {
                    for (i in 0 until dailyArr.length()) daily.add(dailyArr.optInt(i, -1))
                }
                if (dailyObj != null) {
                    val dates = dailyObj.optJSONArray("time")
                    val maxes = dailyObj.optJSONArray("temperature_2m_max")
                    val mins = dailyObj.optJSONArray("temperature_2m_min")
                    val precips = dailyObj.optJSONArray("precipitation_probability_max")
                    val n = dates?.length() ?: 0
                    for (i in 0 until n) {
                        dailyFull.add(
                            DayPoint(
                                dateIso = dates?.optString(i).orEmpty(),
                                code = dailyArr?.optInt(i, -1) ?: -1,
                                hi = maxes?.optDouble(i, Double.NaN) ?: Double.NaN,
                                lo = mins?.optDouble(i, Double.NaN) ?: Double.NaN,
                                // JSONObject.NULL / missing both read as "unavailable".
                                precipProb = if (precips != null && !precips.isNull(i)) {
                                    precips.optInt(i, -1).takeIf { it >= 0 }
                                } else null
                            )
                        )
                    }
                }
                val hourly = mutableListOf<HourPoint>()
                val hourlyObj = root.optJSONObject("hourly")
                if (hourlyObj != null) {
                    val times = hourlyObj.optJSONArray("time")
                    val temps = hourlyObj.optJSONArray("temperature_2m")
                    val codes = hourlyObj.optJSONArray("weather_code")
                    val n = times?.length() ?: 0
                    for (i in 0 until n) {
                        hourly.add(
                            HourPoint(
                                timeIso = times?.optString(i).orEmpty(),
                                temp = temps?.optDouble(i, Double.NaN) ?: Double.NaN,
                                code = codes?.optInt(i, -1) ?: -1
                            )
                        )
                    }
                }
                result = Snapshot(
                    text = "${temp.toInt()}${units.tempSymbol} ${codeToText(code)}",
                    code = code,
                    isDay = isDay,
                    temp = temp,
                    dailyCodes = daily,
                    hourly = hourly,
                    dailyFull = dailyFull
                )
            } catch (e: Exception) {
                // Fail-open: null result hides the widget quietly.
            }
            callback(result)
        }
        t.isDaemon = true
        t.start()
    }

    // ------------------------------------------------------------ snapshot codec

    /**
     * Persist a snapshot in Prefs so the weather background and the
     * forecast screen work on cold start, before (or without) a fresh
     * fetch. Format: the v1.2 four segments
     * "code|isDay|temp|dailyCodesCsv" followed by v1.4 segments
     * (daily dates/max/min/precip CSVs, then hourly time/temp/code
     * CSVs; "-" marks an unavailable precipitation value). Decoding a
     * v1.2/v1.3 string yields empty hourly/daily series — the older
     * fields are untouched. Decode fails open to null.
     */
    fun encodeSnapshot(s: Snapshot): String {
        val sb = StringBuilder()
        sb.append("${s.code}|${if (s.isDay) 1 else 0}|${s.temp}|${s.dailyCodes.joinToString(",")}")
        sb.append("|").append(s.dailyFull.joinToString(",") { it.dateIso })
        sb.append("|").append(s.dailyFull.joinToString(",") { it.hi.toString() })
        sb.append("|").append(s.dailyFull.joinToString(",") { it.lo.toString() })
        sb.append("|").append(s.dailyFull.joinToString(",") { it.precipProb?.toString() ?: "-" })
        sb.append("|").append(s.hourly.joinToString(",") { it.timeIso })
        sb.append("|").append(s.hourly.joinToString(",") { it.temp.toString() })
        sb.append("|").append(s.hourly.joinToString(",") { it.code.toString() })
        return sb.toString()
    }

    fun decodeSnapshot(raw: String?): Snapshot? {
        if (raw.isNullOrBlank()) return null
        return try {
            val parts = raw.split("|")
            if (parts.size < 3) return null
            val daily = parts.getOrNull(3)
                ?.split(",")
                ?.mapNotNull { it.trim().toIntOrNull() }
                ?: emptyList()
            fun csv(idx: Int): List<String> =
                parts.getOrNull(idx)?.takeIf { it.isNotEmpty() }?.split(",") ?: emptyList()
            val dates = csv(4)
            val maxes = csv(5)
            val mins = csv(6)
            val precips = csv(7)
            val dailyFull = dates.indices.mapNotNull { i ->
                val hi = maxes.getOrNull(i)?.toDoubleOrNull() ?: return@mapNotNull null
                val lo = mins.getOrNull(i)?.toDoubleOrNull() ?: return@mapNotNull null
                DayPoint(
                    dateIso = dates[i],
                    code = daily.getOrNull(i) ?: -1,
                    hi = hi,
                    lo = lo,
                    precipProb = precips.getOrNull(i)?.toIntOrNull()
                )
            }
            val hTimes = csv(8)
            val hTemps = csv(9)
            val hCodes = csv(10)
            val hourly = hTimes.indices.mapNotNull { i ->
                val t = hTemps.getOrNull(i)?.toDoubleOrNull() ?: return@mapNotNull null
                HourPoint(timeIso = hTimes[i], temp = t, code = hCodes.getOrNull(i)?.toIntOrNull() ?: -1)
            }
            Snapshot(
                text = "",
                code = parts[0].toInt(),
                isDay = parts[1] == "1",
                temp = parts[2].toDouble(),
                dailyCodes = daily,
                hourly = hourly,
                dailyFull = dailyFull
            )
        } catch (e: Exception) {
            null
        }
    }

    /** WMO weather codes used by Open-Meteo, condensed for a top-bar widget. */
    fun codeToText(code: Int): String = when (code) {
        0 -> "Clear sky"
        1 -> "Mostly clear"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Fog"
        51, 53, 55 -> "Drizzle"
        56, 57 -> "Freezing drizzle"
        61 -> "Light rain"
        63 -> "Rain"
        65 -> "Heavy rain"
        66, 67 -> "Freezing rain"
        71 -> "Light snow"
        73 -> "Snow"
        75 -> "Heavy snow"
        77 -> "Snow grains"
        80 -> "Light showers"
        81 -> "Showers"
        82 -> "Violent showers"
        85, 86 -> "Snow showers"
        95 -> "Thunderstorm"
        96, 99 -> "Thunderstorm, hail"
        else -> ""
    }
}
