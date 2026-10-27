package com.mizan.launcher.domain

/**
 * Settings. Home style offers TV / Watch ONLY (v3.9): saved "ipad"
 * decodes to TV, "tracker" and "iptv" decode to WATCH — that legacy
 * decode lives in core/HomeStyle and the data layer maps it onto
 * this enum; domain never sees the retired values.
 * Per-profile values are stored under a p<profile>. prefix;
 * current profile, pattern, safe mode, weather location and last
 * error are global. Profile count = 2 (ids 1-2) + optional Guest.
 */
enum class HomeStyle { TV, WATCH }

enum class WallpaperKind {
    THEME, GRADIENT, SOLID_COLOR, MY_PICTURE, REDDIT, BING, WEATHER,
}

data class StatusBarSettings(
    val showBar: Boolean = true,
    val sticky: Boolean = false,
    val showClock: Boolean = true,
    val showDate: Boolean = true,
    val showWeather: Boolean = true,
    val showNetworkIcon: Boolean = true,
    val showBluetoothIcon: Boolean = true,
    val quickTiles: Boolean = true,
    val wifiTile: Boolean = false,
    val bluetoothTile: Boolean = true,
    val soundTile: Boolean = true,
    val pictureTile: Boolean = true,
    val settingsTile: Boolean = true, // corner gear appears even when off
    val searchTile: Boolean = true,
    val allAppsTile: Boolean = true,
    val clock24h: Boolean = false,
    val showSeconds: Boolean = false,
    val dateFormat: String = "weekday_month_day",
    val textSize: String = "medium",
    val background: String = "transparent",
    val backgroundOpacity: String = "solid",
)

data class ProfileSettings(
    val profileId: Int,
    val name: String,
    val isGuest: Boolean = false,
    val themeId: String = "apple_black",
    val wallpaperKind: WallpaperKind = WallpaperKind.THEME,
    val wallpaperValue: String = "",
    val iconShape: String = "rounded",
    val appSize: String = "standard",
    val gridColumns: Int = 6, // 3..10
    val homeStyle: HomeStyle = HomeStyle.TV,
    val weatherUnits: WeatherUnits = WeatherUnits.FAHRENHEIT,
    val statusBar: StatusBarSettings = StatusBarSettings(),
    val playerAspect: String = "fit", // fit / stretch / zoom
    val redditSubs: String = "wallpaper,wallpapers,EarthPorn",
    val redditAllowNsfw: Boolean = false,
    val wallpaperRotation: String = "off", // off / 15m / 1h / 6h / daily
    val unlockGraceMinutes: Int = 0, // 0 = every time
)

data class GlobalSettings(
    val currentProfile: Int = 1,
    val guestProfile: Int = 0, // 0 = none
    val guestLockSettings: Boolean = true,
    val safeMode: Boolean = false,
    val lockSettings: Boolean? = null, // null: unset => pattern-set means locked
    val weatherLat: String = "39.1653",
    val weatherLon: String = "-86.5264",
    val speedUpLastResult: String = "",
    val watchHintDismissed: Boolean = false,
)

interface SettingsRepository {
    fun getGlobal(): GlobalSettings
    fun getProfile(profileId: Int): ProfileSettings
    fun setProfile(settings: ProfileSettings)

    // Phase 2D global setters (close the UI-layer gap: these three
    // global writes were read-only before). Weather location is
    // global (not per-profile); Guest designation + Guest lock are
    // global too. Implementations delegate to PrefsStore.
    fun setWeatherLocation(lat: String, lon: String)
    fun setGuestProfile(profileId: Int) // 0 = none, else 1..2
    fun setGuestLockSettings(enabled: Boolean)

    /** v2.8 tracker-sections migration flag, surfaced for diagnostics/UI. */
    fun isTrackerSectionsMigrated(): Boolean
    fun switchProfile(profileId: Int) // clears the unlock-grace stamp
    fun setHomeStyle(style: HomeStyle)
    fun setSafeMode(enabled: Boolean)

    /** TV row order + Watch section order; hero is hide/show-only. */
    fun getTvRowOrder(): List<String>
    fun getWatchSectionOrder(): List<String>
    fun setTvRowOrder(order: List<String>)
    fun setWatchSectionOrder(order: List<String>)
    fun getSectionVisibility(sectionId: String): Boolean
    fun setSectionVisibility(sectionId: String, visible: Boolean)

    // Credentials: stored only, never exported, never logged.
    fun getTmdbKey(): String
    fun setTmdbKey(key: String) // empty clears
    fun getIptvServer(): String
    fun getIptvUser(): String
    fun setIptvAccount(server: String, user: String, pass: String)
    fun getM3uUrl(): String
    fun getEpgUrl(): String
    fun setPlaylistUrls(m3u: String, epg: String)
    fun clearIptvSources() // favorites and resume positions STAY
}
