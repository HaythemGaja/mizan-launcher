package com.mizan.launcher.domain

/** Open-Meteo, no account/key. Units follow the Weather setting. */
enum class WeatherUnits { FAHRENHEIT, CELSIUS }

data class CurrentWeather(
    val conditionCode: Int,
    val temperature: Double,
    val isDay: Boolean,
    val windSpeed: Double,
    val units: WeatherUnits,
    /** Condition group: clear/partly/cloudy/fog/rain/storm/snow. */
    val conditionGroup: String = "cloudy",
)

data class HourlyForecast(
    val hourLabel: String,
    val temperature: Double,
    val conditionCode: Int,
    val isDay: Boolean,
)

data class DailyForecast(
    val weekdayAndDate: String,
    val conditionGroup: String,
    val high: Double,
    val low: Double,
    /** Precipitation, only when present. */
    val precipitation: Double? = null,
)

data class WeatherSnapshot(
    val current: CurrentWeather?,
    /** Next 24 hours starting at the current hour (wraps past midnight). */
    val hourly: List<HourlyForecast> = emptyList(),
    val daily: List<DailyForecast> = emptyList(), // 7-day outlook
    val fetchedMs: Long = 0,
)

interface WeatherRepository {
    /** Cached snapshot; null when nothing cached yet. */
    fun getCached(): WeatherSnapshot?

    /**
     * Fetch when stale (or forced). Staleness rule lives in
     * core/RefreshPolicy; implementations fail open to the cache.
     */
    fun refresh(force: Boolean = false): WeatherSnapshot?
}
