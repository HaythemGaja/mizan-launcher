package com.mizan.launcher.data

import android.content.Context
import com.mizan.launcher.core.ForecastLogic
import com.mizan.launcher.core.WeatherLogic
import com.mizan.launcher.domain.CurrentWeather
import com.mizan.launcher.domain.DailyForecast
import com.mizan.launcher.domain.HourlyForecast
import com.mizan.launcher.domain.WeatherRepository
import com.mizan.launcher.domain.WeatherSnapshot
import com.mizan.launcher.domain.WeatherUnits
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * Open-Meteo forecast (MAP §5, no account/key). Default location
 * 39.1653 / -86.5264 (Bloomington, IN); user-changed locations
 * come from the global weather_lat/weather_lon prefs. Cached
 * snapshot + ~3 h staleness via core/RefreshPolicy semantics
 * (stale or explicit refresh); failures fall open to the cache.
 * Units follow the per-profile Weather setting.
 */
class WeatherRepositoryImpl(ctx: Context) : WeatherRepository {
    private val prefs = PrefsStore(ctx)
    private val ctxRef = ctx.applicationContext
    private val staleMs = 3L * 3600 * 1000

    private fun motifToGroup(motif: String): String = when (motif) {
        "sun", "moon" -> "clear"
        "partly" -> "partly"
        "cloud" -> "cloudy"
        "fog" -> "fog"
        "rain" -> "rain"
        "storm" -> "storm"
        "snow" -> "snow"
        else -> "cloudy"
    }

    private fun units(): WeatherUnits =
        if (prefs.weatherUnits() == com.mizan.launcher.core.WeatherUnits.CELSIUS)
            WeatherUnits.CELSIUS else WeatherUnits.FAHRENHEIT

    private fun parse(json: String, fetchedMs: Long): WeatherSnapshot? {
        return try {
            val o = JSONObject(json)
            val cur = o.optJSONObject("current")
            val hourly = o.optJSONObject("hourly")
            val daily = o.optJSONObject("daily")
            val units = units()
            val curWeather = if (cur != null) {
                val code = cur.optInt("weather_code", -1)
                val isDay = cur.optInt("is_day", 1) == 1
                val group = try {
                    motifToGroup(WeatherLogic.conditionFor(code, isDay).motif)
                } catch (e: Throwable) { "cloudy" }
                CurrentWeather(code, cur.optDouble("temperature_2m", 0.0), isDay,
                    cur.optDouble("wind_speed_10m", 0.0), units, group)
            } else null
            val hourList = ArrayList<HourlyForecast>()
            if (hourly != null) {
                val times = hourly.optJSONArray("time")
                val temps = hourly.optJSONArray("temperature_2m")
                val codes = hourly.optJSONArray("weather_code")
                val days = hourly.optJSONArray("is_day")
                if (times != null) {
                    // Next 24 h starting at the current hour (wraps past midnight):
                    // find the index of the current hour, then take 24.
                    val nowKey = java.time.LocalDateTime.now().toString().substring(0, 13)
                    var start = 0
                    for (i in 0 until times.length()) {
                        if (times.optString(i).startsWith(nowKey)) { start = i; break }
                    }
                    for (i in start until minOf(start + 24, times.length())) {
                        val t = times.optString(i)
                        val hour = t.substringAfter('T').substringBefore(':').toIntOrNull() ?: 0
                        val label = if (units == WeatherUnits.CELSIUS) {
                            // 24h labels plain; 12h labels wrap — ForecastLogic
                            // hourLabel handles both via use24h flag.
                            ForecastLogic.hourLabel(t, true)
                        } else ForecastLogic.hourLabel(t, false)
                        hourList.add(HourlyForecast(label,
                            temps?.optDouble(i, 0.0) ?: 0.0,
                            codes?.optInt(i, -1) ?: -1,
                            (days?.optInt(i, 1) ?: 1) == 1))
                    }
                }
            }
            val dayList = ArrayList<DailyForecast>()
            if (daily != null) {
                val times = daily.optJSONArray("time")
                val hi = daily.optJSONArray("temperature_2m_max")
                val lo = daily.optJSONArray("temperature_2m_min")
                val codes = daily.optJSONArray("weather_code")
                val precip = daily.optJSONArray("precipitation_sum")
                if (times != null) {
                    for (i in 0 until minOf(7, times.length())) {
                        val dateStr = times.optString(i)
                        val weekday = try {
                            LocalDate.parse(dateStr).dayOfWeek
                                .getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
                        } catch (e: Throwable) { "" }
                        val code = codes?.optInt(i, -1) ?: -1
                        val group = try {
                            motifToGroup(WeatherLogic.conditionFor(code, true).motif)
                        } catch (e: Throwable) { "cloudy" }
                        dayList.add(DailyForecast("$weekday $dateStr", group,
                            hi?.optDouble(i, 0.0) ?: 0.0, lo?.optDouble(i, 0.0) ?: 0.0,
                            if (precip != null && !precip.isNull(i)) precip.optDouble(i) else null))
                    }
                }
            }
            WeatherSnapshot(curWeather, hourList, dayList, fetchedMs)
        } catch (e: Throwable) { null }
    }

    override fun getCached(): WeatherSnapshot? {
        val raw = prefs.weatherData()
        if (raw.isBlank()) return null
        return parse(raw, prefs.weatherLastFetch())
    }

    override fun refresh(force: Boolean): WeatherSnapshot? {
        val cached = getCached()
        val stale = System.currentTimeMillis() - prefs.weatherLastFetch() > staleMs
        if (!force && !stale && cached != null) return cached
        val coreUnits = prefs.weatherUnits()
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${prefs.weatherLat()}" +
            "&longitude=${prefs.weatherLon()}" +
            "&current=temperature_2m,weather_code,is_day,wind_speed_10m" +
            "&hourly=temperature_2m,weather_code,is_day" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum" +
            "&temperature_unit=${coreUnits.apiTemperatureUnit}" +
            "&wind_speed_unit=${coreUnits.apiWindSpeedUnit}&timezone=auto&forecast_days=7"
        val r = HttpUtil.get(url)
        if (r.code in 200..299 && !r.body.isNullOrBlank()) {
            val now = System.currentTimeMillis()
            prefs.setWeatherData(r.body!!)
            prefs.setWeatherLastFetch(now)
            prefs.setWeatherCached(r.body!!)
            return parse(r.body!!, now) ?: cached
        }
        return cached // fail open to cache
    }

    /** Condition group for wallpaper art (current, else forecast-dominant, else worst). */
    fun conditionGroup(): String? {
        val snap = getCached() ?: return null
        val cur = snap.current
        if (cur != null) return cur.conditionGroup
        return snap.daily.firstOrNull()?.conditionGroup
    }
}
