package com.mizan.launcher.data

import android.content.Context
import android.content.SharedPreferences
import com.mizan.launcher.core.ArrowPinCodec
import com.mizan.launcher.core.LayoutCodec
import com.mizan.launcher.core.LayoutData
import com.mizan.launcher.core.WeatherUnits

/**
 * All launcher settings, in one SharedPreferences file.
 * Per-profile values (theme, wallpaper, layout, locked apps...) are stored
 * under a `p<profile>.` prefix so profiles can never bleed into each other;
 * a few values (current profile, PIN, safe mode, weather location, last
 * error) are global.
 */
class Prefs(ctx: Context) {

    val sp: SharedPreferences = ctx.getSharedPreferences("mizan_launcher", Context.MODE_PRIVATE)

    init {
        // v1.2 migration: the v1.1 numeric PIN is retired. An old "pin"
        // value (and any numeric/invalid value under the new key) is
        // DISCARDED — never reinterpreted as arrows — so after the update
        // the lock is simply unset and Haythem can never be locked out of
        // his own apps by an upgrade. He sets an arrow sequence fresh.
        try {
            val editor = sp.edit()
            var dirty = false
            if (sp.contains("pin")) { editor.remove("pin"); dirty = true }
            val raw = sp.getString("arrow_pin", null)
            if (raw != null && raw.isNotEmpty() && !ArrowPinCodec.isValidEncoded(raw)) {
                editor.remove("arrow_pin"); dirty = true
            }
            if (dirty) editor.apply()
        } catch (e: Exception) {
            // Migration must never crash the launcher; the getter also
            // validates on every read.
        }
    }

    var currentProfile: Int
        get() = sp.getInt("current_profile", 1).coerceIn(1, 2)
        set(v) { sp.edit().putInt("current_profile", v.coerceIn(1, 2)).apply() }

    private fun k(name: String) = "p$currentProfile.$name"

    // ---- per-profile appearance ----
    var themeId: String
        get() = sp.getString(k("theme"), "apple_black") ?: "apple_black"
        set(v) { sp.edit().putString(k("theme"), v).apply() }

    /** Wallpaper source: "theme" (use theme bg), "gradient", "solid", "image". */
    var wallpaperKind: String
        get() = sp.getString(k("wall_kind"), "theme") ?: "theme"
        set(v) { sp.edit().putString(k("wall_kind"), v).apply() }

    /** Gradient name, colour int (as string), or image content URI, per [wallpaperKind]. */
    var wallpaperValue: String
        get() = sp.getString(k("wall_value"), "") ?: ""
        set(v) { sp.edit().putString(k("wall_value"), v).apply() }

    /** Dominant colour extracted from the current wallpaper (0 = not computed). */
    var accentOverride: Int
        get() = sp.getInt(k("accent_override"), 0)
        set(v) { sp.edit().putInt(k("accent_override"), v).apply() }

    var iconShape: String
        get() = sp.getString(k("icon_shape"), "rounded") ?: "rounded"
        set(v) { sp.edit().putString(k("icon_shape"), v).apply() }

    /** compact | standard | large */
    var appSize: String
        get() = sp.getString(k("app_size"), "standard") ?: "standard"
        set(v) { sp.edit().putString(k("app_size"), v).apply() }

    var columns: Int
        get() = sp.getInt(k("columns"), 6).coerceIn(3, 10)
        set(v) { sp.edit().putInt(k("columns"), v.coerceIn(3, 10)).apply() }

    /**
     * Home-screen style (v1.7, extended v1.8): "tv" (default) |
     * "ipad" | "tracker". Per profile — one profile can be iPad
     * while the other stays TV. Reads and writes both pass through
     * the HomeStyle codec, so a missing or garbled stored value
     * always resolves to TV (never a crash, and old backups restore
     * as TV).
     */
    var homeStyle: String
        get() = com.mizan.launcher.core.HomeStyle.normalize(sp.getString(k("home_style"), null))
        set(v) {
            sp.edit().putString(k("home_style"),
                com.mizan.launcher.core.HomeStyle.normalize(v)).apply()
        }

    // ---- per-profile Tracker view state (v1.8) ----
    /**
     * The local marked-set for the Tracker view, encoded by
     * core/TrackerLogic (one "tmdbId|type|season|episode|markedAt"
     * per line). Items the user marked watched FROM THIS PROFILE are
     * hidden optimistically until the tracker's snapshots converge
     * (TrackerLogic.pruneMarked). Not part of backups.
     */
    var trackerMarked: String
        get() = sp.getString(k("tracker_marked"), "") ?: ""
        set(v) { sp.edit().putString(k("tracker_marked"), v).apply() }

    /** The Tracker hero cycle position for this profile. */
    var trackerHeroIndex: Int
        get() = sp.getInt(k("tracker_hero_index"), 0)
        set(v) { sp.edit().putInt(k("tracker_hero_index"), v).apply() }

    // ---- per-profile v2.0 state ----

    /**
     * Tracker-view section controls (v2.0), encoded by
     * core/TrackerSections ("order-csv|hidden-csv"). Part of backups.
     */
    var trackerSections: String
        get() = sp.getString(k("tracker_sections"), "") ?: ""
        set(v) { sp.edit().putString(k("tracker_sections"), v).apply() }

    fun trackerSectionsConfig(): com.mizan.launcher.core.TrackerSections.Config =
        com.mizan.launcher.core.TrackerSections.decode(trackerSections)

    fun setTrackerSectionsConfig(c: com.mizan.launcher.core.TrackerSections.Config) {
        trackerSections = com.mizan.launcher.core.TrackerSections.encode(c)
    }

    /**
     * The local For You overlay (v2.0), encoded by core/TrackerLogic:
     * titles added/dismissed from the For You row hide immediately and
     * the entry retires once the tracker's snapshots converge. Like
     * the marked-set, this is optimistic local state — not in backups.
     */
    var forYouOverlay: String
        get() = sp.getString(k("foryou_overlay"), "") ?: ""
        set(v) { sp.edit().putString(k("foryou_overlay"), v).apply() }

    /**
     * Custom app artwork (v2.0), encoded by core/ArtworkStore
     * (per package: PRESET index or image URL). Part of backups.
     */
    var artwork: String
        get() = sp.getString(k("artwork"), "") ?: ""
        set(v) { sp.edit().putString(k("artwork"), v).apply() }

    fun artworkMap(): Map<String, com.mizan.launcher.core.ArtworkStore.Entry> =
        com.mizan.launcher.core.ArtworkStore.decode(artwork)

    fun setArtwork(pkg: String, entry: com.mizan.launcher.core.ArtworkStore.Entry?) {
        artwork = com.mizan.launcher.core.ArtworkStore.encode(
            com.mizan.launcher.core.ArtworkStore.with(artworkMap(), pkg, entry)
        )
    }

    var layout: LayoutData
        get() = LayoutCodec.decode(sp.getString(k("layout"), null))
        set(v) { sp.edit().putString(k("layout"), LayoutCodec.encode(v)).apply() }

    var lockedApps: Set<String>
        get() = sp.getStringSet(k("locked"), emptySet()) ?: emptySet()
        set(v) { sp.edit().putStringSet(k("locked"), v).apply() }

    // ---- per-profile Reddit wallpaper source (v1.1) ----
    /** Comma-separated subreddit names (no "r/" prefix). */
    var redditSubs: String
        get() = sp.getString(k("reddit_subs"), null)
            ?: com.mizan.launcher.core.RedditWallpapers.DEFAULT_SUBREDDITS.joinToString(",")
        set(v) { sp.edit().putString(k("reddit_subs"), v).apply() }

    /** Settings toggle, default OFF: include over_18 posts in results. */
    var redditAllowNsfw: Boolean
        get() = sp.getBoolean(k("reddit_nsfw"), false)
        set(v) { sp.edit().putBoolean(k("reddit_nsfw"), v).apply() }

    /** Encoded cache of the last fetched candidate list (offline picker). */
    var redditCache: String
        get() = sp.getString(k("reddit_cache"), "") ?: ""
        set(v) { sp.edit().putString(k("reddit_cache"), v).apply() }

    /**
     * v1.6: one-line summary of the most recent Reddit fetch run for
     * this profile ("✓ 18 photos · 2:41 PM" / "✗ r/wallpaper
     * HTTP 403 · 0 photos · 2:41 PM"), built by core/RedditStatus.
     * Shown in Settings › Look & wallpaper › Reddit and the picker —
     * fetch failures are visible now, not silent.
     */
    var redditLastStatus: String
        get() = sp.getString(k("reddit_last_status"), "") ?: ""
        set(v) { sp.edit().putString(k("reddit_last_status"), v).apply() }

    /** "Mizan isn't your home screen yet" hint bar dismissed for this profile. */
    var homeHintDismissed: Boolean
        get() = sp.getBoolean(k("home_hint_dismissed"), false)
        set(v) { sp.edit().putBoolean(k("home_hint_dismissed"), v).apply() }

    // ---- per-profile wallpaper rotation + Bing (v1.4) ----
    /** Rotation interval key (core/RotationLogic): off | 15m | 1h | 6h | daily. */
    var redditRotation: String
        get() = sp.getString(k("reddit_rotation"), com.mizan.launcher.core.RotationLogic.OFF)
            ?: com.mizan.launcher.core.RotationLogic.OFF
        set(v) { sp.edit().putString(k("reddit_rotation"), v).apply() }

    /** When the wallpaper last auto-rotated (epoch ms; 0 = never). */
    var redditRotationLast: Long
        get() = sp.getLong(k("reddit_rotation_last"), 0)
        set(v) { sp.edit().putLong(k("reddit_rotation_last"), v).apply() }

    /** Index into the cached candidate list of the currently-shown Reddit wallpaper. */
    var redditRotationIndex: Int
        get() = sp.getInt(k("reddit_rotation_index"), -1)
        set(v) { sp.edit().putInt(k("reddit_rotation_index"), v).apply() }

    /** Calendar day (core/BingWallpaper.dayKey) the cached Bing image belongs to. */
    var bingCacheDate: String
        get() = sp.getString(k("bing_cache_date"), "") ?: ""
        set(v) { sp.edit().putString(k("bing_cache_date"), v).apply() }

    /** Absolute path of the downloaded Bing image for [bingCacheDate]. */
    var bingCachePath: String
        get() = sp.getString(k("bing_cache_path"), "") ?: ""
        set(v) { sp.edit().putString(k("bing_cache_path"), v).apply() }

    // ---- per-profile Recently Used (v1.4) ----
    /** Encoded MRU launch list (core/RecentsStore). Not part of backups. */
    var recents: String
        get() = sp.getString(k("recents"), "") ?: ""
        set(v) { sp.edit().putString(k("recents"), v).apply() }

    /** Record a launch made from Mizan into this profile's MRU store. */
    fun recordLaunch(pkg: String) {
        try {
            val updated = com.mizan.launcher.core.RecentsStore.record(
                com.mizan.launcher.core.RecentsStore.decode(recents),
                pkg, System.currentTimeMillis(), "com.mizan.launcher"
            )
            recents = com.mizan.launcher.core.RecentsStore.encode(updated)
        } catch (e: Exception) {
            // Recents are best-effort bookkeeping; never break a launch.
        }
    }

    // ---- guest profile (global, v1.4) ----
    /** Which profile is the Guest profile: 0 = none, 1 or 2. */
    var guestProfile: Int
        get() = sp.getInt("guest_profile", 0).coerceIn(0, 2)
        set(v) { sp.edit().putInt("guest_profile", v.coerceIn(0, 2)).apply() }

    /** iOS-switch in Lock & profiles, default ON: gate Settings entry + switch-away while Guest is active. */
    var guestLockSettings: Boolean
        get() = sp.getBoolean("guest_lock_settings", true)
        set(v) { sp.edit().putBoolean("guest_lock_settings", v).apply() }

    // ---- per-profile custom name (v1.9) ----
    /**
     * The user-chosen name for [profile], stored RAW under that
     * profile's own key (independent of which profile is active);
     * "" = no custom name. All interpretation lives in
     * core/ProfileNames — this is storage only.
     */
    fun profileName(profile: Int): String =
        sp.getString("p$profile.profile_name", "") ?: ""

    fun setProfileName(profile: Int, raw: String) {
        sp.edit().putString(
            "p$profile.profile_name",
            com.mizan.launcher.core.ProfileNames.normalize(raw)
        ).apply()
    }

    /**
     * How a profile is named on screen (v1.9: a custom name wins;
     * otherwise a guest-flagged profile shows as "Guest", a personal
     * one as "Profile N" — the rule core/ProfileNames owns).
     */
    fun profileDisplayName(profile: Int): String =
        com.mizan.launcher.core.ProfileNames.display(
            profileName(profile), profile,
            isGuest = guestProfile != 0 && profile == guestProfile
        )

    /**
     * Switch the active profile AND clear the unlock-grace stamp
     * (v1.9): grace earned under one profile never carries into
     * another. Every profile switch in the app goes through here.
     */
    fun switchProfile(target: Int) {
        currentProfile = target
        clearUnlockStamp()
    }

    // ---- sticky status bar (per profile, v1.9) ----
    /**
     * "Sticky status bar": when ON, the status bar / iPad top strip
     * renders in a fixed header above the scrollable home instead of
     * scrolling away with it. Default OFF = the pre-v1.9 behaviour.
     */
    var stickyStatusBar: Boolean
        get() = sp.getBoolean(k("sticky_status_bar"), false)
        set(v) { sp.edit().putBoolean(k("sticky_status_bar"), v).apply() }

    // ---- unlock grace (per profile window + global stamp, v1.9) ----
    /**
     * "Re-ask for pattern" window in minutes (core/UnlockGrace:
     * 0 = every time [default], 5, 15, 30). Per profile — but the
     * stamp it is measured against is the ONE device-level
     * [lastUnlockAt] below.
     */
    var unlockGraceMinutes: Int
        get() = com.mizan.launcher.core.UnlockGrace.normalize(
            sp.getInt(k("unlock_grace_min"), 0)
        )
        set(v) {
            sp.edit().putInt(k("unlock_grace_min"),
                com.mizan.launcher.core.UnlockGrace.normalize(v)).apply()
        }

    /** When the arrow pattern was last verified successfully (epoch ms; 0 = never). */
    var lastUnlockAt: Long
        get() = sp.getLong("last_unlock_at", 0L)
        set(v) { sp.edit().putLong("last_unlock_at", v).apply() }

    /** Stamp a successful pattern verification (the grace clock). */
    fun stampUnlock() {
        lastUnlockAt = System.currentTimeMillis()
    }

    /** Drop the grace stamp (profile switch — see [switchProfile]). */
    fun clearUnlockStamp() {
        if (lastUnlockAt != 0L) lastUnlockAt = 0L
    }

    /** True while a pattern gate may pass silently under this profile's window. */
    fun withinUnlockGrace(): Boolean =
        com.mizan.launcher.core.UnlockGrace.withinGrace(
            unlockGraceMinutes, lastUnlockAt, System.currentTimeMillis()
        )

    /** Last "⚡ Speed up now" result line (Settings → Home & launcher, shown in diagnostics). */
    var speedupLastResult: String
        get() = sp.getString("speedup_last_result", "") ?: ""
        set(v) { sp.edit().putString("speedup_last_result", v).apply() }

    /**
     * "Lock Settings" (v1.5, global): Settings asks for the arrow
     * pattern on entry (enforced in SettingsActivity.onCreate via
     * core/SettingsGate). Until the user touches the switch, the
     * default is ON whenever a pattern is set — so the update protects
     * existing pattern users immediately — and OFF when there is no
     * pattern (there would be nothing to check against; enabling the
     * switch runs the set-pattern flow first). Once toggled, the
     * explicit stored value wins.
     */
    var lockSettings: Boolean
        get() = if (sp.contains("lock_settings")) sp.getBoolean("lock_settings", false)
        else arrowPin.isNotEmpty()
        set(v) { sp.edit().putBoolean("lock_settings", v).apply() }

    // ---- global ----
    /**
     * Arrow-lock sequence, encoded "UDLRU" (see ArrowPinCodec), or ""
     * when no lock is set. Only valid encodings are ever stored or
     * returned — garbage reads as unset (fail-open, never a lockout).
     * Legacy numeric PINs were discarded by the init migration above.
     */
    var arrowPin: String
        get() {
            val raw = sp.getString("arrow_pin", "") ?: ""
            return if (ArrowPinCodec.isValidEncoded(raw)) raw else ""
        }
        set(v) {
            sp.edit().putString("arrow_pin", if (ArrowPinCodec.isValidEncoded(v)) v else "").apply()
        }

    /** Wrong arrow-unlock attempts, for diagnostics. */
    var arrowAttempts: Int
        get() = sp.getInt("arrow_attempts", 0)
        set(v) { sp.edit().putInt("arrow_attempts", v).apply() }

    var safeMode: Boolean
        get() = sp.getBoolean("safe_mode", false)
        set(v) { sp.edit().putBoolean("safe_mode", v).apply() }

    var weatherLat: String
        get() = sp.getString("weather_lat", "39.1653") ?: "39.1653"
        set(v) { sp.edit().putString("weather_lat", v).apply() }

    var weatherLon: String
        get() = sp.getString("weather_lon", "-86.5264") ?: "-86.5264"
        set(v) { sp.edit().putString("weather_lon", v).apply() }

    var lastWeatherFetch: Long
        get() = sp.getLong("weather_last_fetch", 0)
        set(v) { sp.edit().putLong("weather_last_fetch", v).apply() }

    var cachedWeather: String
        get() = sp.getString("weather_cached", "") ?: ""
        set(v) { sp.edit().putString("weather_cached", v).apply() }

    /**
     * Last fetched weather snapshot, encoded by data/Weather as
     * "code|isDay|temp|dailyCodeCsv" — drives the weather background
     * even when the status-bar widget is hidden. "" = never fetched.
     */
    var weatherData: String
        get() = sp.getString("weather_data", "") ?: ""
        set(v) { sp.edit().putString("weather_data", v).apply() }

    /** Weather background state for diagnostics: "active: rain" / "fallback: ..." / "off". */
    var weatherBgStatus: String
        get() = sp.getString("weather_bg_status", "off") ?: "off"
        set(v) { sp.edit().putString("weather_bg_status", v).apply() }

    // ---- per-profile weather units (v1.2) ----
    /** Stored key of the WeatherUnits enum; invalid values read as Fahrenheit. */
    var weatherUnitsKey: String
        get() = WeatherUnits.fromKey(sp.getString(k("weather_units"), null)).key
        set(v) { sp.edit().putString(k("weather_units"), WeatherUnits.fromKey(v).key).apply() }

    val weatherUnits: WeatherUnits get() = WeatherUnits.fromKey(weatherUnitsKey)

    companion object {
        /** Record a fail-open error for the diagnostics screen instead of crashing. */
        fun noteError(ctx: Context, where: String, e: Throwable) {
            try {
                ctx.getSharedPreferences("mizan_launcher", Context.MODE_PRIVATE)
                    .edit()
                    .putString("last_error", "$where: ${e.javaClass.simpleName}: ${e.message ?: ""}")
                    .apply()
            } catch (ignored: Throwable) {
                // Diagnostics itself must never crash the launcher.
            }
        }

        fun lastError(ctx: Context): String =
            ctx.getSharedPreferences("mizan_launcher", Context.MODE_PRIVATE)
                .getString("last_error", "") ?: ""

        // ---- Reddit attempt log (global, v1.6) ----

        private const val REDDIT_LOG_MAX = 12

        /**
         * Append one formatted attempt line (core/RedditStatus.
         * attemptLine) to the global log, capped at the most recent
         * [REDDIT_LOG_MAX] lines. Copied out via diagnostics.
         */
        fun noteRedditAttempt(ctx: Context, line: String) {
            try {
                val p = sp(ctx)
                val lines = (p.getString("reddit_attempt_log", "") ?: "")
                    .lines().filter { it.isNotBlank() }
                val kept = (lines + line).takeLast(REDDIT_LOG_MAX)
                p.edit().putString("reddit_attempt_log", kept.joinToString("\n")).apply()
            } catch (ignored: Throwable) {
                // Diagnostics must never crash a fetch.
            }
        }

        /** The recorded Reddit attempt lines, oldest first. */
        fun redditAttemptLog(ctx: Context): List<String> =
            try {
                (sp(ctx).getString("reddit_attempt_log", "") ?: "")
                    .lines().filter { it.isNotBlank() }
            } catch (e: Throwable) { emptyList() }

        // ---- Home watcher diagnostics (global: the service is global) ----
        private fun sp(ctx: Context): SharedPreferences =
            ctx.getSharedPreferences("mizan_launcher", Context.MODE_PRIVATE)

        /** Record one watcher relaunch (count + timestamp) for diagnostics. */
        fun noteWatcherRelaunch(ctx: Context) {
            try {
                val p = sp(ctx)
                p.edit()
                    .putInt("watcher_relaunches", p.getInt("watcher_relaunches", 0) + 1)
                    .putLong("watcher_last_at", System.currentTimeMillis())
                    .apply()
            } catch (ignored: Throwable) {
                // Diagnostics must never crash the watcher.
            }
        }

        fun watcherRelaunches(ctx: Context): Int =
            try { sp(ctx).getInt("watcher_relaunches", 0) } catch (e: Throwable) { 0 }

        fun watcherLastAt(ctx: Context): Long =
            try { sp(ctx).getLong("watcher_last_at", 0) } catch (e: Throwable) { 0 }

        /** Timestamp of the last USER_PRESENT / SCREEN_ON recovery nudge. */
        fun noteScreenOnRecovery(ctx: Context) {
            try {
                sp(ctx).edit().putLong("screenon_last_at", System.currentTimeMillis()).apply()
            } catch (ignored: Throwable) {
            }
        }
    }
}
