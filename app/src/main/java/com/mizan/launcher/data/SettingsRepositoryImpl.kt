package com.mizan.launcher.data

import android.content.Context
import com.mizan.launcher.core.HomeStyle
import com.mizan.launcher.core.TrackerSections
import com.mizan.launcher.domain.GlobalSettings
import com.mizan.launcher.domain.HomeStyle as DomainHomeStyle
import com.mizan.launcher.domain.ProfileSettings
import com.mizan.launcher.domain.SettingsRepository
import com.mizan.launcher.domain.StatusBarSettings
import com.mizan.launcher.domain.WallpaperKind
import com.mizan.launcher.domain.WeatherUnits

/**
 * All MAP §3/§7 settings with their defaults. Home style offers
 * TV / Watch ONLY: legacy "ipad"→TV and "tracker"/"iptv"→WATCH
 * decode in core/HomeStyle and are mapped onto the domain enum
 * here. Status-bar settings live in the layout document's
 * status= line (core StatusBarConfig), not as separate keys.
 * Credentials are stored only, never exported (exportSettingsJson
 * in PrefsStore excludes them by construction).
 */
class SettingsRepositoryImpl(ctx: Context) : SettingsRepository {
    private val prefs = PrefsStore(ctx)

    private fun wallKindFrom(raw: String): WallpaperKind = when (raw) {
        "gradient" -> WallpaperKind.GRADIENT
        "solid", "solid_color", "color" -> WallpaperKind.SOLID_COLOR
        "picture", "my_picture" -> WallpaperKind.MY_PICTURE
        "reddit" -> WallpaperKind.REDDIT
        "bing" -> WallpaperKind.BING
        "weather" -> WallpaperKind.WEATHER
        else -> WallpaperKind.THEME
    }
    private fun wallKindTo(k: WallpaperKind): String = when (k) {
        WallpaperKind.GRADIENT -> "gradient"
        WallpaperKind.SOLID_COLOR -> "solid_color"
        WallpaperKind.MY_PICTURE -> "my_picture"
        WallpaperKind.REDDIT -> "reddit"
        WallpaperKind.BING -> "bing"
        WallpaperKind.WEATHER -> "weather"
        WallpaperKind.THEME -> "theme"
    }

    override fun getGlobal(): GlobalSettings = GlobalSettings(
        currentProfile = prefs.profile(),
        guestProfile = prefs.guestProfile(),
        guestLockSettings = prefs.guestLockSettings(),
        safeMode = prefs.safeMode(),
        lockSettings = prefs.lockSettingsRaw(),
        weatherLat = prefs.weatherLat(),
        weatherLon = prefs.weatherLon(),
        speedUpLastResult = prefs.speedUpLastResult(),
        watchHintDismissed = prefs.watchHintDismissed(),
    )

    override fun getProfile(profileId: Int): ProfileSettings {
        val id = profileId.coerceIn(1, 2)
        val d = prefs.layout(id)
        val sb = d.statusBar
        return ProfileSettings(
            profileId = id,
            name = prefs.profileDisplayName(id),
            isGuest = prefs.guestProfile() == id,
            themeId = prefs.theme(id),
            wallpaperKind = wallKindFrom(prefs.wallKind(id)),
            wallpaperValue = prefs.wallValue(id),
            iconShape = prefs.iconShape(id),
            appSize = prefs.appSize(id),
            gridColumns = prefs.columns(id),
            homeStyle = if (prefs.homeStyleRaw(id) == HomeStyle.WATCH) DomainHomeStyle.WATCH else DomainHomeStyle.TV,
            weatherUnits = if (prefs.weatherUnits(id) == com.mizan.launcher.core.WeatherUnits.CELSIUS)
                WeatherUnits.CELSIUS else WeatherUnits.FAHRENHEIT,
            statusBar = StatusBarSettings(
                showBar = sb.showBar, sticky = prefs.stickyStatusBar(id),
                showClock = sb.showClock, showDate = sb.showDate, showWeather = sb.showWeather,
                showNetworkIcon = sb.showNetwork, showBluetoothIcon = sb.showBluetooth,
                quickTiles = sb.showQuickTiles, wifiTile = sb.showTileWifi,
                bluetoothTile = sb.showTileBluetooth, soundTile = sb.showTileSound,
                pictureTile = sb.showTilePicture, settingsTile = sb.showTileSettings,
                searchTile = sb.showTileSearch, allAppsTile = sb.showTileAllApps,
                clock24h = sb.clock24h, showSeconds = sb.showSeconds,
                dateFormat = sb.dateFormat, textSize = sb.textSize,
                background = sb.background, backgroundOpacity = sb.opacity),
            playerAspect = prefs.playerAspect(id),
            redditSubs = prefs.redditSubs(id),
            redditAllowNsfw = prefs.redditNsfw(id),
            wallpaperRotation = prefs.redditRotation(id),
            unlockGraceMinutes = prefs.unlockGraceMin(id),
        )
    }

    override fun setProfile(settings: ProfileSettings) {
        val id = settings.profileId.coerceIn(1, 2)
        // Phase 2D fix: profile renames were dropped — setProfile
        // never persisted the name, so Settings rename rows silently
        // did nothing. Store the normalized custom name ("" when it
        // equals the computed default, so defaults keep tracking the
        // Guest flag instead of freezing as a stale label).
        val defaultName = com.mizan.launcher.core.ProfileNames.defaultLabel(id, settings.isGuest)
        prefs.setProfileNameRaw(id,
            if (settings.name == defaultName) "" else com.mizan.launcher.core.ProfileNames.normalize(settings.name))
        prefs.setTheme(settings.themeId, id)
        prefs.setWallKind(wallKindTo(settings.wallpaperKind), id)
        prefs.setWallValue(settings.wallpaperValue, id)
        PrefsSafe.setStr(prefs.sp, "p$id.icon_shape", settings.iconShape)
        PrefsSafe.setStr(prefs.sp, "p$id.app_size", settings.appSize)
        PrefsSafe.setInt(prefs.sp, "p$id.columns", settings.gridColumns.coerceIn(3, 10))
        prefs.setHomeStyleRaw(if (settings.homeStyle == DomainHomeStyle.WATCH) "watch" else "tv", id)
        prefs.setPlayerAspect(settings.playerAspect, id)
        PrefsSafe.setStr(prefs.sp, "p$id.reddit_subs", settings.redditSubs)
        PrefsSafe.setBool(prefs.sp, "p$id.reddit_nsfw", settings.redditAllowNsfw)
        PrefsSafe.setStr(prefs.sp, "p$id.reddit_rotation", settings.wallpaperRotation)
        prefs.setUnlockGraceMin(settings.unlockGraceMinutes, id)
        prefs.setWeatherUnits(
            if (settings.weatherUnits == WeatherUnits.CELSIUS)
                com.mizan.launcher.core.WeatherUnits.CELSIUS
            else com.mizan.launcher.core.WeatherUnits.FAHRENHEIT, id)
        val d = prefs.layout(id)
        val s = settings.statusBar
        d.statusBar = d.statusBar.copy(
            showBar = s.showBar, showClock = s.showClock, showDate = s.showDate,
            showWeather = s.showWeather, showNetwork = s.showNetworkIcon,
            showBluetooth = s.showBluetoothIcon, showQuickTiles = s.quickTiles,
            showTileWifi = s.wifiTile, showTileBluetooth = s.bluetoothTile,
            showTileSound = s.soundTile, showTilePicture = s.pictureTile,
            showTileSettings = s.settingsTile, showTileSearch = s.searchTile,
            showTileAllApps = s.allAppsTile, clock24h = s.clock24h,
            showSeconds = s.showSeconds, dateFormat = s.dateFormat,
            textSize = s.textSize, background = s.background, opacity = s.backgroundOpacity)
        PrefsSafe.setBool(prefs.sp, "p$id.sticky_status_bar", s.sticky)
        prefs.saveLayout(d, id)
    }

    override fun switchProfile(profileId: Int) = prefs.switchProfile(profileId)

    // Phase 2D global setters (domain gap (a)): these delegate to
    // PrefsStore keys that already existed; only the setters were
    // missing (guest setters are new one-liners in PrefsStore).
    override fun setWeatherLocation(lat: String, lon: String) =
        prefs.setWeatherLocation(lat, lon)

    override fun setGuestProfile(profileId: Int) =
        prefs.setGuestProfile(profileId)

    override fun setGuestLockSettings(enabled: Boolean) =
        prefs.setGuestLockSettings(enabled)

    override fun isTrackerSectionsMigrated(): Boolean =
        prefs.trackerSectionsMigrated28()
    override fun setHomeStyle(style: DomainHomeStyle) {
        prefs.setHomeStyleRaw(if (style == DomainHomeStyle.WATCH) "watch" else "tv")
    }
    override fun setSafeMode(enabled: Boolean) = prefs.setSafeMode(enabled)

    override fun getTvRowOrder(): List<String> = prefs.layout().rowOrder.toList()
    override fun getWatchSectionOrder(): List<String> {
        val cfg = prefs.trackerSectionsConfig()
        // Domain order uses "apps"/"trending_tv" style ids; core uses
        // "allapps"/"trendingtv". Map core -> domain on read.
        return listOf("hero") + cfg.order.map { coreToDomainSection(it) }
    }
    override fun setTvRowOrder(order: List<String>) {
        val d = prefs.layout()
        val clean = com.mizan.launcher.core.RowOrder.normalize(order)
        d.rowOrder.clear(); d.rowOrder.addAll(clean)
        prefs.saveLayout(d)
    }
    override fun setWatchSectionOrder(order: List<String>) {
        val coreOrder = order.filter { it != "hero" }.map { domainToCoreSection(it) }
        val cfg = prefs.trackerSectionsConfig()
        prefs.setTrackerSectionsConfig(TrackerSections.Config(cfg.hidden, coreOrder))
    }
    override fun getSectionVisibility(sectionId: String): Boolean {
        return when {
            sectionId == "hero" -> !prefs.trackerSectionsConfig().hidden.contains("hero")
            sectionId in listOf("favorites", "recents", "mostused", "folders", "nowplaying",
                "continue", "inputs", "livetv", "apps") ->
                prefs.layout().isRowVisible(sectionId)
            else -> !prefs.trackerSectionsConfig().hidden.contains(domainToCoreSection(sectionId))
        }
    }
    override fun setSectionVisibility(sectionId: String, visible: Boolean) {
        when {
            sectionId in listOf("favorites", "recents", "mostused", "folders", "nowplaying",
                "continue", "inputs", "livetv", "apps") -> {
                val d = prefs.layout()
                d.rowVisible[sectionId] = visible
                prefs.saveLayout(d)
            }
            else -> {
                val cfg = prefs.trackerSectionsConfig()
                val hidden = cfg.hidden.toMutableSet()
                val core = domainToCoreSection(sectionId)
                if (visible) hidden.remove(core) else hidden.add(core)
                prefs.setTrackerSectionsConfig(TrackerSections.Config(hidden, cfg.order))
            }
        }
    }
    private fun coreToDomainSection(core: String): String = when (core) {
        "allapps" -> "apps"
        "trendingtv" -> "trending_tv"
        "trendingmovies" -> "trending_movies"
        "foryou" -> "foryou"
        else -> core
    }
    private fun domainToCoreSection(domain: String): String = when (domain) {
        "apps" -> "allapps"
        "trending_tv" -> "trendingtv"
        "trending_movies" -> "trendingmovies"
        else -> domain
    }

    override fun getTmdbKey(): String = prefs.tmdbKey()
    override fun setTmdbKey(key: String) = prefs.setTmdbKey(key)
    override fun getIptvServer(): String = prefs.iptvAccount().server
    override fun getIptvUser(): String = prefs.iptvAccount().user
    override fun setIptvAccount(server: String, user: String, pass: String) =
        prefs.setIptvAccount(server, user, pass)
    override fun getM3uUrl(): String = prefs.m3uUrl()
    override fun getEpgUrl(): String = prefs.epgUrl()
    override fun setPlaylistUrls(m3u: String, epg: String) = prefs.setPlaylistUrls(m3u, epg)
    override fun clearIptvSources() = prefs.clearIptvSources()
}
