package com.mizan.launcher.data

import android.content.Context
import android.content.SharedPreferences
import com.mizan.launcher.core.ArrowPinCodec
import com.mizan.launcher.core.HomeStyle
import com.mizan.launcher.core.IptvLogic
import com.mizan.launcher.core.LayoutCodec
import com.mizan.launcher.core.LayoutData
import com.mizan.launcher.core.LibraryLogic
import com.mizan.launcher.core.ProfileNames
import com.mizan.launcher.core.TrackerLogic
import com.mizan.launcher.core.TrackerSections
import com.mizan.launcher.core.WeatherUnits

/**
 * Central prefs access for the data layer. Single SharedPreferences
 * file `mizan_launcher` (MODE_PRIVATE); every read via PrefsSafe.
 * Per-profile keys use the `p<profile>.` prefix (MAP §4).
 * Credentials are stored here only and are never exported/logged
 * by any data-layer class.
 */
class PrefsStore(ctx: Context) {
    val sp: SharedPreferences =
        ctx.applicationContext.getSharedPreferences("mizan_launcher", Context.MODE_PRIVATE)

    init {
        // v2.4 legacy purge + arrow-PIN migration discard (MAP §1.15/§1.13):
        // legacy numeric `pin` removed; a legacy numeric arrow_pin decodes empty.
        try {
            if (sp.contains("pin")) sp.edit().remove("pin").apply()
            val raw = PrefsSafe.strOrNull(sp, "arrow_pin")
            if (raw != null && ArrowPinCodec.isLegacyNumeric(raw)) {
                sp.edit().putString("arrow_pin", "").apply()
            }
        } catch (ignored: Throwable) { }
    }

    fun profile(): Int = PrefsSafe.int(sp, "current_profile", 1).coerceIn(1, 2)
    private fun k(name: String, profile: Int = profile()): String = "p$profile.$name"

    // ---- global ----
    fun arrowPin(): String = PrefsSafe.str(sp, "arrow_pin", "")
    fun setArrowPin(v: String) = PrefsSafe.setStr(sp, "arrow_pin", v)
    fun safeMode(): Boolean = PrefsSafe.bool(sp, "safe_mode", false)
    fun setSafeMode(v: Boolean) = PrefsSafe.setBool(sp, "safe_mode", v)
    fun guestProfile(): Int = PrefsSafe.int(sp, "guest_profile", 0)
    fun setGuestProfile(v: Int) = PrefsSafe.setInt(sp, "guest_profile", v.coerceIn(0, 2))
    fun guestLockSettings(): Boolean = PrefsSafe.bool(sp, "guest_lock_settings", true)
    fun setGuestLockSettings(v: Boolean) = PrefsSafe.setBool(sp, "guest_lock_settings", v)
    fun lastUnlockAt(): Long = PrefsSafe.long(sp, "last_unlock_at", 0L)
    fun stampUnlock(nowMs: Long) = PrefsSafe.setLong(sp, "last_unlock_at", nowMs)
    fun clearUnlockStamp() = PrefsSafe.setLong(sp, "last_unlock_at", 0L)
    fun lockSettingsRaw(): Boolean? =
        if (PrefsSafe.has(sp, "lock_settings")) PrefsSafe.bool(sp, "lock_settings", false) else null
    fun setLockSettings(v: Boolean?) {
        if (v == null) PrefsSafe.remove(sp, "lock_settings") else PrefsSafe.setBool(sp, "lock_settings", v)
    }
    fun speedUpLastResult(): String = PrefsSafe.str(sp, "speedup_last_result", "")
    fun setSpeedUpLastResult(v: String) = PrefsSafe.setStr(sp, "speedup_last_result", v)
    fun tmdbKey(): String = PrefsSafe.str(sp, "launcher_tmdb_key", "")
    fun setTmdbKey(v: String) = PrefsSafe.setStr(sp, "launcher_tmdb_key", v.take(80))
    fun weatherLat(): String = PrefsSafe.str(sp, "weather_lat", "39.1653")
    fun weatherLon(): String = PrefsSafe.str(sp, "weather_lon", "-86.5264")
    fun setWeatherLocation(lat: String, lon: String) {
        PrefsSafe.setStr(sp, "weather_lat", lat)
        PrefsSafe.setStr(sp, "weather_lon", lon)
    }
    fun weatherBgStatus(): String = PrefsSafe.str(sp, "weather_bg_status", "off")
    fun setWeatherBgStatus(v: String) = PrefsSafe.setStr(sp, "weather_bg_status", v)
    fun libraryImported(): Boolean = PrefsSafe.bool(sp, "library_imported", false)
    fun setLibraryImported() = PrefsSafe.setBool(sp, "library_imported", true)
    fun lastError(): String = PrefsSafe.str(sp, "last_error", "")
    fun noteErrorScrubbed(msg: String) {
        // Scrubbed upstream (DiagScrub); never store credential-looking values.
        if (msg.isBlank()) return
        PrefsSafe.setStr(sp, "last_error", msg.take(500))
    }
    fun watcherRelaunches(): Int = PrefsSafe.int(sp, "watcher_relaunches", 0)
    fun watcherLastAt(): Long = PrefsSafe.long(sp, "watcher_last_at", 0L)
    fun noteWatcherRelaunch(nowMs: Long) {
        PrefsSafe.setInt(sp, "watcher_relaunches", watcherRelaunches() + 1)
        PrefsSafe.setLong(sp, "watcher_last_at", nowMs)
    }
    fun screenOnRecoveries(): Int = PrefsSafe.int(sp, "screen_on_recoveries", 0)
    fun noteScreenOnRecovery() = PrefsSafe.setInt(sp, "screen_on_recoveries", screenOnRecoveries() + 1)
    fun lastBootAt(): Long = PrefsSafe.long(sp, "last_boot_at", 0L)
    fun setLastBootAt(v: Long) = PrefsSafe.setLong(sp, "last_boot_at", v)
    fun ownForegroundAt(): Long = PrefsSafe.long(sp, "own_foreground_at", 0L)
    fun setOwnForegroundAt(v: Long) = PrefsSafe.setLong(sp, "own_foreground_at", v)
    fun lastRefreshAt(): Long = PrefsSafe.long(sp, "last_refresh_at", 0L)
    fun setLastRefreshAt(v: Long) = PrefsSafe.setLong(sp, "last_refresh_at", v)
    fun weatherLastFetch(): Long = PrefsSafe.long(sp, "weather_last_fetch", 0L)
    fun setWeatherLastFetch(v: Long) = PrefsSafe.setLong(sp, "weather_last_fetch", v)
    fun weatherCached(): String = PrefsSafe.str(sp, "weather_cached", "")
    fun setWeatherCached(v: String) = PrefsSafe.setStr(sp, "weather_cached", v)
    fun weatherData(): String = PrefsSafe.str(sp, "weather_data", "")
    fun setWeatherData(v: String) = PrefsSafe.setStr(sp, "weather_data", v)

    // ---- IPTV account (global, never exported) ----
    fun iptvAccount(): IptvLogic.Account = IptvLogic.account(
        PrefsSafe.str(sp, "iptv_server", ""),
        PrefsSafe.str(sp, "iptv_user", ""),
        PrefsSafe.str(sp, "iptv_pass", ""),
    )
    fun setIptvAccount(server: String, user: String, pass: String) {
        val a = IptvLogic.account(server.take(120), user.take(60), pass.take(60))
        PrefsSafe.setStr(sp, "iptv_server", a.server)
        PrefsSafe.setStr(sp, "iptv_user", a.user)
        PrefsSafe.setStr(sp, "iptv_pass", a.pass)
    }
    fun iptvAnyConfigured(): Boolean {
        val a = iptvAccount()
        return a.server.isNotBlank() && a.user.isNotBlank() && a.pass.isNotBlank() ||
            m3uUrl().isNotBlank()
    }
    fun clearIptvSources() {
        PrefsSafe.setStr(sp, "iptv_server", "")
        PrefsSafe.setStr(sp, "iptv_user", "")
        PrefsSafe.setStr(sp, "iptv_pass", "")
        PrefsSafe.setStr(sp, "iptv_m3u_url", "")
        PrefsSafe.setStr(sp, "iptv_epg_url", "")
        // Favorites + resume STAY (MAP §3.9).
    }
    fun m3uUrl(): String = PrefsSafe.str(sp, "iptv_m3u_url", "")
    fun epgUrl(): String = PrefsSafe.str(sp, "iptv_epg_url", "")
    fun setPlaylistUrls(m3u: String, epg: String) {
        PrefsSafe.setStr(sp, "iptv_m3u_url", m3u.take(300))
        PrefsSafe.setStr(sp, "iptv_epg_url", epg.take(300))
    }

    // ---- per-profile ----
    fun layout(profile: Int = profile()): LayoutData =
        try { LayoutCodec.decode(PrefsSafe.strOrNull(sp, k("layout", profile))) }
        catch (e: Throwable) { LayoutData() }
    fun saveLayout(d: LayoutData, profile: Int = profile()) {
        try { PrefsSafe.setStr(sp, k("layout", profile), LayoutCodec.encode(d)) }
        catch (ignored: Throwable) { }
    }
    fun theme(profile: Int = profile()): String = PrefsSafe.str(sp, k("theme", profile), "apple_black")
    fun setTheme(v: String, profile: Int = profile()) = PrefsSafe.setStr(sp, k("theme", profile), v)
    fun wallKind(profile: Int = profile()): String = PrefsSafe.str(sp, k("wall_kind", profile), "theme")
    fun setWallKind(v: String, profile: Int = profile()) = PrefsSafe.setStr(sp, k("wall_kind", profile), v)
    fun wallValue(profile: Int = profile()): String = PrefsSafe.str(sp, k("wall_value", profile), "")
    fun setWallValue(v: String, profile: Int = profile()) = PrefsSafe.setStr(sp, k("wall_value", profile), v)
    fun iconShape(profile: Int = profile()): String = PrefsSafe.str(sp, k("icon_shape", profile), "rounded")
    fun appSize(profile: Int = profile()): String = PrefsSafe.str(sp, k("app_size", profile), "standard")
    fun columns(profile: Int = profile()): Int = PrefsSafe.int(sp, k("columns", profile), 6).coerceIn(3, 10)
    fun homeStyleRaw(profile: Int = profile()): String = HomeStyle.normalize(PrefsSafe.str(sp, k("home_style", profile), "tv"))
    fun setHomeStyleRaw(v: String, profile: Int = profile()) =
        PrefsSafe.setStr(sp, k("home_style", profile), HomeStyle.normalize(v))
    fun playerAspect(profile: Int = profile()): String = PrefsSafe.str(sp, k("player_aspect", profile), "fit")
    fun setPlayerAspect(v: String, profile: Int = profile()) = PrefsSafe.setStr(sp, k("player_aspect", profile), v)
    fun stickyStatusBar(profile: Int = profile()): Boolean = PrefsSafe.bool(sp, k("sticky_status_bar", profile), false)
    fun unlockGraceMin(profile: Int = profile()): Int = PrefsSafe.int(sp, k("unlock_grace_min", profile), 0)
    fun setUnlockGraceMin(v: Int, profile: Int = profile()) = PrefsSafe.setInt(sp, k("unlock_grace_min", profile), v)
    fun redditSubs(profile: Int = profile()): String =
        PrefsSafe.str(sp, k("reddit_subs", profile), "wallpaper,wallpapers,EarthPorn")
    fun redditNsfw(profile: Int = profile()): Boolean = PrefsSafe.bool(sp, k("reddit_nsfw", profile), false)
    fun redditRotation(profile: Int = profile()): String = PrefsSafe.str(sp, k("reddit_rotation", profile), "off")
    fun weatherUnits(profile: Int = profile()): WeatherUnits =
        WeatherUnits.fromKey(PrefsSafe.strOrNull(sp, k("weather_units", profile)))
    fun setWeatherUnits(v: WeatherUnits, profile: Int = profile()) =
        PrefsSafe.setStr(sp, k("weather_units", profile), v.key)
    fun profileNameRaw(profile: Int): String = PrefsSafe.str(sp, k("profile_name", profile), "")
    fun setProfileNameRaw(profile: Int, v: String) = PrefsSafe.setStr(sp, k("profile_name", profile), v)
    fun profileDisplayName(profile: Int): String = try {
        ProfileNames.display(profileNameRaw(profile), profile, guestProfile() == profile)
    } catch (e: Throwable) { "Profile $profile" }
    fun switchProfile(target: Int) {
        val t = target.coerceIn(1, 2)
        PrefsSafe.setInt(sp, "current_profile", t)
        clearUnlockStamp() // switching profiles always asks again
    }
    fun watchHintDismissed(profile: Int = profile()): Boolean =
        PrefsSafe.bool(sp, k("watch_hint_dismissed", profile), false) ||
            PrefsSafe.bool(sp, k("home_hint_dismissed", profile), false)
    fun setWatchHintDismissed(v: Boolean, profile: Int = profile()) {
        PrefsSafe.setBool(sp, k("watch_hint_dismissed", profile), v)
        PrefsSafe.setBool(sp, k("home_hint_dismissed", profile), v)
    }

    // ---- tracker overlays (per profile) ----
    fun marked(): List<TrackerLogic.MarkedEntry> =
        TrackerLogic.decodeMarked(PrefsSafe.strOrNull(sp, k("tracker_marked")))
    fun setMarked(list: List<TrackerLogic.MarkedEntry>) =
        PrefsSafe.setStr(sp, k("tracker_marked"), TrackerLogic.encodeMarked(list))
    fun pending(): List<LibraryLogic.PendingTitle> =
        LibraryLogic.decodePending(PrefsSafe.strOrNull(sp, k("pending_overlay")))
    fun setPending(list: List<LibraryLogic.PendingTitle>) =
        PrefsSafe.setStr(sp, k("pending_overlay"), LibraryLogic.encodePending(list))
    fun forYouOverlay(): String = PrefsSafe.str(sp, k("foryou_overlay"), "")
    fun setForYouOverlay(v: String) = PrefsSafe.setStr(sp, k("foryou_overlay"), v)
    fun trackerSectionsRaw(): String = PrefsSafe.str(sp, k("tracker_sections"), "")
    fun setTrackerSectionsRaw(v: String) = PrefsSafe.setStr(sp, k("tracker_sections"), v)
    fun trackerSectionsMigrated28(): Boolean = PrefsSafe.bool(sp, k("tracker_sections_migrated_28"), false)
    fun setTrackerSectionsMigrated28() = PrefsSafe.setBool(sp, k("tracker_sections_migrated_28"), true)
    fun trackerSectionsConfig(): TrackerSections.Config {
        val cfg = TrackerSections.decode(trackerSectionsRaw())
        // v2.8 one-time splice migration (MAP §3.4): stored orders gain
        // canonical slots once; later deliberate reorders are never re-migrated.
        if (!trackerSectionsMigrated28()) {
            val migrated = TrackerSections.migrateOrder(cfg)
            if (migrated != cfg) {
                setTrackerSectionsRaw(TrackerSections.encode(migrated))
            }
            setTrackerSectionsMigrated28()
            return migrated
        }
        return cfg
    }
    fun setTrackerSectionsConfig(c: TrackerSections.Config) =
        setTrackerSectionsRaw(TrackerSections.encode(c))

    // ---- IPTV per-profile state (survives source clears) ----
    fun iptvFavorites(): List<IptvLogic.FavEntry> =
        IptvLogic.decodeFavorites(PrefsSafe.strOrNull(sp, k("iptv_favorites")))
    fun setIptvFavorites(list: List<IptvLogic.FavEntry>) =
        PrefsSafe.setStr(sp, k("iptv_favorites"), IptvLogic.encodeFavorites(list))
    fun iptvResume(): List<IptvLogic.ResumeEntry> =
        IptvLogic.decodeResume(PrefsSafe.strOrNull(sp, k("iptv_resume")))
    fun setIptvResume(list: List<IptvLogic.ResumeEntry>) =
        PrefsSafe.setStr(sp, k("iptv_resume"), IptvLogic.encodeResume(list))
    fun iptvLockedCats(): Set<String> =
        com.mizan.launcher.core.IptvLocks.decodeKeys(PrefsSafe.strOrNull(sp, k("iptv_locked_cats")))
    fun setIptvLockedCats(s: Set<String>) =
        PrefsSafe.setStr(sp, k("iptv_locked_cats"), com.mizan.launcher.core.IptvLocks.encodeKeys(s))
    fun iptvUnlockStamps(): Map<String, Long> =
        com.mizan.launcher.core.IptvLocks.decodeStamps(PrefsSafe.strOrNull(sp, k("iptv_cat_unlock_stamps")))
    fun setIptvUnlockStamps(m: Map<String, Long>) =
        PrefsSafe.setStr(sp, k("iptv_cat_unlock_stamps"), com.mizan.launcher.core.IptvLocks.encodeStamps(m))
    fun artworkMap(): Map<String, com.mizan.launcher.core.ArtworkStore.Entry> =
        com.mizan.launcher.core.ArtworkStore.decode(PrefsSafe.strOrNull(sp, k("artwork")))
    fun setArtwork(pkg: String, entry: com.mizan.launcher.core.ArtworkStore.Entry?) {
        val m = artworkMap().toMutableMap()
        if (entry == null) m.remove(pkg) else m[pkg] = entry
        PrefsSafe.setStr(sp, k("artwork"), com.mizan.launcher.core.ArtworkStore.encode(m))
    }
    fun recents(): List<com.mizan.launcher.core.RecentsStore.Entry> =
        com.mizan.launcher.core.RecentsStore.decode(PrefsSafe.strOrNull(sp, k("recents")))
    fun setRecents(list: List<com.mizan.launcher.core.RecentsStore.Entry>) =
        PrefsSafe.setStr(sp, k("recents"), com.mizan.launcher.core.RecentsStore.encode(list))
    fun redditCache(): String = PrefsSafe.str(sp, k("reddit_cache"), "")
    fun setRedditCache(v: String) = PrefsSafe.setStr(sp, k("reddit_cache"), v)
    fun redditLastStatus(): String = PrefsSafe.str(sp, k("reddit_last_status"), "")
    fun setRedditLastStatus(v: String) = PrefsSafe.setStr(sp, k("reddit_last_status"), v)
    fun redditRotationLast(): Long = PrefsSafe.long(sp, k("reddit_rotation_last"), 0L)
    fun setRedditRotationLast(v: Long) = PrefsSafe.setLong(sp, k("reddit_rotation_last"), v)
    fun redditRotationIndex(): Int = PrefsSafe.int(sp, k("reddit_rotation_index"), -1)
    fun setRedditRotationIndex(v: Int) = PrefsSafe.setInt(sp, k("reddit_rotation_index"), v)
    fun bingCacheDate(): String = PrefsSafe.str(sp, k("bing_cache_date"), "")
    fun bingCachePath(): String = PrefsSafe.str(sp, k("bing_cache_path"), "")
    fun setBingCache(date: String, path: String) {
        PrefsSafe.setStr(sp, k("bing_cache_date"), date)
        PrefsSafe.setStr(sp, k("bing_cache_path"), path)
    }
    fun redditAttemptLog(): List<String> {
        val raw = PrefsSafe.str(sp, "reddit_attempt_log", "")
        return if (raw.isBlank()) emptyList() else raw.split('\n')
    }
    fun noteRedditAttempt(line: String) {
        val kept = (redditAttemptLog() + line).takeLast(12)
        PrefsSafe.setStr(sp, "reddit_attempt_log", kept.joinToString("\n"))
    }
    fun libraryLastChange(): Long = PrefsSafe.long(sp, "library_last_change", 0L)
    fun setLibraryLastChange(v: Long) = PrefsSafe.setLong(sp, "library_last_change", v)

    /** Settings export shape (MAP §3.10): excludes marked set, For You
     *  overlay, pending overlay, IPTV favorites/resume, TMDB key, IPTV
     *  credentials. Import is type-tolerant via PrefsSafe on read. */
    fun exportSettingsJson(profile: Int = profile()): String {
        val d = layout(profile)
        fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
        val lockedApps = PrefsSafe.stringSet(sp, "p$profile.locked").sorted()
            .joinToString(",") { "\"" + esc(it) + "\"" }
        return "{\"sreeb\":true,\"profile\":$profile,\"theme\":\"${esc(theme(profile))}\"," +
            "\"wall_kind\":\"${esc(wallKind(profile))}\",\"wall_value\":\"${esc(wallValue(profile))}\"," +
            "\"home_style\":\"${homeStyleRaw(profile)}\",\"icon_shape\":\"${esc(iconShape(profile))}\"," +
            "\"app_size\":\"${esc(appSize(profile))}\",\"columns\":${columns(profile)}," +
            "\"profile_name\":\"${esc(profileNameRaw(profile))}\"," +
            "\"tracker_sections\":\"${esc(trackerSectionsRaw())}\"," +
            "\"watch_hint_dismissed\":${watchHintDismissed(profile)}," +
            "\"lock_settings\":${lockSettingsRaw() ?: "null"}," +
            "\"locked_apps\":[$lockedApps]," +
            "\"layout\":\"${esc(try { LayoutCodec.encode(d) } catch (e: Throwable) { "" })}\"}"
    }
    fun importSettingsJson(json: String?): Boolean {
        if (json == null) return false
        return try {
            val o = org.json.JSONObject(json)
            if (!o.optBoolean("sreeb", false)) return false
            val profile = profile()
            if (o.has("theme")) setTheme(o.optString("theme", theme(profile)), profile)
            if (o.has("wall_kind")) setWallKind(o.optString("wall_kind", wallKind(profile)), profile)
            if (o.has("wall_value")) setWallValue(o.optString("wall_value", wallValue(profile)), profile)
            if (o.has("home_style")) setHomeStyleRaw(o.optString("home_style", "tv"), profile)
            if (o.has("tracker_sections")) setTrackerSectionsRaw(o.optString("tracker_sections", ""))
            if (o.has("watch_hint_dismissed")) setWatchHintDismissed(o.optBoolean("watch_hint_dismissed"), profile)
            if (o.has("locked_apps")) {
                val arr = o.optJSONArray("locked_apps")
                val set = HashSet<String>()
                if (arr != null) for (i in 0 until arr.length()) {
                    arr.optString(i).takeIf { it.isNotBlank() }?.let { set.add(it) }
                }
                PrefsSafe.setStringSet(sp, "p$profile.locked", set)
            }
            if (o.has("layout")) {
                try {
                    val d = LayoutCodec.decode(o.optString("layout", ""))
                    saveLayout(d, profile)
                } catch (ignored: Throwable) { }
            }
            true
        } catch (e: Throwable) { false }
    }
}
