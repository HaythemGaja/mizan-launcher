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
 *
 * v3.0: EVERY read in this class goes through [PrefsSafe] — a stored
 * value whose type no longer matches its getter (planted by a backup
 * restore or a cross-version type change) used to throw
 * ClassCastException on hot paths on every launch; now it evicts
 * itself and reads as the default (spec §30).
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
            val raw = PrefsSafe.strOrNull(sp, "arrow_pin")
            if (raw != null && raw.isNotEmpty() && !ArrowPinCodec.isValidEncoded(raw)) {
                editor.remove("arrow_pin"); dirty = true
            }
            if (dirty) editor.apply()
        } catch (e: Exception) {
            // Migration must never crash the launcher; the getter also
            // validates on every read.
        }
    }

    /**
     * The profiles that exist (v3.14, core/ProfileSet owns the codec).
     * One global key ("profile_set", CSV like "1,2,4"); absent or
     * corrupt reads as {1, 2}, the pre-v3.14 world. The setter refuses
     * an empty or out-of-range set and keeps the old value instead
     * (fail-open: the set must never become empty through a write).
     */
    var profileSet: Set<Int>
        get() = com.mizan.launcher.core.ProfileSet.decode(
            PrefsSafe.strOrNull(sp, "profile_set")
        )
        set(v) {
            val clean = v.filter { it in 1..com.mizan.launcher.core.ProfileSet.MAX_PROFILES }.toSet()
            if (clean.isNotEmpty()) {
                sp.edit().putString(
                    "profile_set",
                    com.mizan.launcher.core.ProfileSet.encode(clean)
                ).apply()
            }
        }

    var currentProfile: Int
        get() = com.mizan.launcher.core.ProfileSet.resolveCurrent(
            PrefsSafe.int(sp, "current_profile", 1), profileSet
        )
        set(v) {
            sp.edit().putInt(
                "current_profile",
                com.mizan.launcher.core.ProfileSet.resolveCurrent(v, profileSet)
            ).apply()
        }

    private fun k(name: String) = "p$currentProfile.$name"

    // ---- per-profile appearance ----
    var themeId: String
        get() = PrefsSafe.str(sp, k("theme"), "apple_black")
        set(v) { sp.edit().putString(k("theme"), v).apply() }

    /** Wallpaper source: "theme" (use theme bg), "gradient", "solid", "image". */
    var wallpaperKind: String
        get() = PrefsSafe.str(sp, k("wall_kind"), "theme")
        set(v) { sp.edit().putString(k("wall_kind"), v).apply() }

    /** Gradient name, colour int (as string), or image content URI, per [wallpaperKind]. */
    var wallpaperValue: String
        get() = PrefsSafe.str(sp, k("wall_value"), "")
        set(v) { sp.edit().putString(k("wall_value"), v).apply() }

    /** Dominant colour extracted from the current wallpaper (0 = not computed). */
    var accentOverride: Int
        get() = PrefsSafe.int(sp, k("accent_override"), 0)
        set(v) { sp.edit().putInt(k("accent_override"), v).apply() }

    var iconShape: String
        get() = PrefsSafe.str(sp, k("icon_shape"), "rounded")
        set(v) { sp.edit().putString(k("icon_shape"), v).apply() }

    /**
     * The video player's aspect mode (v2.6): "fit" | "stretch" |
     * "zoom" (core/PlayerLogic owns the values + cycle). Per
     * profile; reads normalize through PlayerLogic so a garbled
     * stored value resolves to Fit instead of breaking the player.
     */
    var playerAspect: String
        get() = com.mizan.launcher.core.PlayerLogic.normalizeAspect(
            PrefsSafe.strOrNull(sp, k("player_aspect"))
        )
        set(v) {
            sp.edit().putString(
                k("player_aspect"),
                com.mizan.launcher.core.PlayerLogic.normalizeAspect(v)
            ).apply()
        }

    /** compact | standard | large */
    var appSize: String
        get() = PrefsSafe.str(sp, k("app_size"), "standard")
        set(v) { sp.edit().putString(k("app_size"), v).apply() }

    var columns: Int
        get() = PrefsSafe.int(sp, k("columns"), 6).coerceIn(3, 10)
        set(v) { sp.edit().putInt(k("columns"), v.coerceIn(3, 10)).apply() }

    /**
     * Home-screen style (v1.7, extended v1.8, reworked v2.2, v3.8):
     * "tv" (default) | "iptv" | "watch". Per profile. Reads and
     * writes both pass through the HomeStyle codec, so a missing
     * or garbled stored value always resolves to TV (never a
     * crash, and old backups restore as TV). v2.2 removed the
     * iPad style — a stored "ipad" decodes to TV; v3.8 removed
     * the Tracker-only style — a stored "tracker" decodes to
     * Watch (see core/HomeStyle).
     */
    var homeStyle: String
        get() = com.mizan.launcher.core.HomeStyle.normalize(PrefsSafe.strOrNull(sp, k("home_style")))
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
        get() = PrefsSafe.str(sp, k("tracker_marked"), "")
        set(v) { sp.edit().putString(k("tracker_marked"), v).apply() }

    /** The Tracker hero cycle position for this profile. */
    var trackerHeroIndex: Int
        get() = PrefsSafe.int(sp, k("tracker_hero_index"), 0)
        set(v) { sp.edit().putInt(k("tracker_hero_index"), v).apply() }

    /**
     * v2.3: the Watch style's "add your IPTV account" hint — once
     * dismissed it stays dismissed for this profile (the hint only
     * ever shows while NO IPTV source is configured at all).
     */
    var watchHintDismissed: Boolean
        get() = PrefsSafe.bool(sp, k("watch_hint_dismissed"), false)
        set(v) { sp.edit().putBoolean(k("watch_hint_dismissed"), v).apply() }

    // ---- per-profile v2.0 state ----

    /**
     * Tracker-view section controls (v2.0), encoded by
     * core/TrackerSections ("order-csv|hidden-csv"). Part of backups.
     */
    var trackerSections: String
        get() = PrefsSafe.str(sp, k("tracker_sections"), "")
        set(v) { sp.edit().putString(k("tracker_sections"), v).apply() }

    fun trackerSectionsConfig(): com.mizan.launcher.core.TrackerSections.Config {
        val cfg = com.mizan.launcher.core.TrackerSections.decode(trackerSections)
        // v2.8 one-time order migration (core/TrackerSections.
        // migrateOrder): a pre-v2.7 stored order had the v2.7 focus
        // rows appended at the very end, where Haythem never saw
        // them. The first read under v2.8 splices them into their
        // canonical slots, persists the result, and sets the flag
        // — later deliberate reorders (even back to the end) are
        // the user's and are never re-migrated.
        try {
            if (!PrefsSafe.bool(sp, k("tracker_sections_migrated_28"), false)) {
                val migrated = com.mizan.launcher.core.TrackerSections.migrateOrder(cfg)
                if (migrated != cfg) trackerSections =
                    com.mizan.launcher.core.TrackerSections.encode(migrated)
                sp.edit().putBoolean(k("tracker_sections_migrated_28"), true).apply()
                return migrated
            }
        } catch (e: Throwable) {
            // Migration must never break a read — the raw config serves.
            // (Throwable since v3.0: this runs on the cold-open path.)
        }
        return cfg
    }

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
        get() = PrefsSafe.str(sp, k("foryou_overlay"), "")
        set(v) { sp.edit().putString(k("foryou_overlay"), v).apply() }

    /**
     * The generalized pending-actions overlay (v2.1), encoded by
     * core/LibraryLogic: every write queued from the Library /
     * details / TMDB-search screens (episode toggles, season marks,
     * unwatch, add, remove, mute, movie toggles) renders instantly
     * from here and retires part-by-part as the tracker's snapshots
     * converge (LibraryLogic.prunePending). Like the marked-set,
     * optimistic local state — not part of backups.
     */
    var pendingOverlay: String
        get() = PrefsSafe.str(sp, k("pending_overlay"), "")
        set(v) { sp.edit().putString(k("pending_overlay"), v).apply() }

    // ---- TMDB API key (GLOBAL, v2.6) ----
    /**
     * An optional TMDB API key held by the LAUNCHER itself (the
     * tracker proxy stays the primary path — its key never leaves
     * the tracker; this one only powers data/TmdbDirect when the
     * proxy fails, spec §26). HARD RULE, identical to the IPTV
     * credentials below: the value lives only in this prefs file —
     * never in the backup/export payload, diagnostics, logs or
     * breadcrumbs; Settings shows set/not-set only.
     */
    var tmdbKey: String
        get() = PrefsSafe.str(sp, "launcher_tmdb_key", "")
        set(v) { sp.edit().putString("launcher_tmdb_key", v).apply() }

    // ---- IPTV account (GLOBAL, v2.2) ----
    /**
     * The IPTV sources are device-global (one household account,
     * like the weather location): an Xtream server/username/password
     * and/or an M3U playlist URL + optional XMLTV (EPG) URL.
     *
     * HARD RULE (spec §20.2): these values are CREDENTIALS. They
     * live only in this prefs file — never in the backup/export
     * payload, diagnostics, logs, or any file the app produces. The
     * M3U/EPG URLs may embed credentials of their own and get the
     * same treatment. The diagnostics line shows counts + state only.
     */
    var iptvServer: String
        get() = PrefsSafe.str(sp, "iptv_server", "")
        set(v) { sp.edit().putString("iptv_server", v).apply() }

    var iptvUser: String
        get() = PrefsSafe.str(sp, "iptv_user", "")
        set(v) { sp.edit().putString("iptv_user", v).apply() }

    var iptvPass: String
        get() = PrefsSafe.str(sp, "iptv_pass", "")
        set(v) { sp.edit().putString("iptv_pass", v).apply() }

    var iptvM3uUrl: String
        get() = PrefsSafe.str(sp, "iptv_m3u_url", "")
        set(v) { sp.edit().putString("iptv_m3u_url", v).apply() }

    var iptvEpgUrl: String
        get() = PrefsSafe.str(sp, "iptv_epg_url", "")
        set(v) { sp.edit().putString("iptv_epg_url", v).apply() }

    /** The Xtream account, normalized (core/IptvLogic owns the rules). */
    fun iptvAccount(): com.mizan.launcher.core.IptvLogic.Account =
        com.mizan.launcher.core.IptvLogic.account(iptvServer, iptvUser, iptvPass)

    /** True when at least one IPTV source (Xtream or M3U) is set up. */
    fun iptvAnyConfigured(): Boolean =
        iptvAccount().isConfigured || iptvM3uUrl.isNotBlank()

    /** Remove every IPTV source value (Settings › IPTV › Clear). Prefs only — nothing else stores them. */
    fun clearIptvAccount() {
        sp.edit()
            .remove("iptv_server").remove("iptv_user").remove("iptv_pass")
            .remove("iptv_m3u_url").remove("iptv_epg_url")
            .apply()
    }

    // ---- per-profile IPTV state (v2.2) ----
    /**
     * IPTV favorites, encoded by core/IptvLogic (one entry per
     * line). Per profile. Like the marked-set: local state, NOT
     * part of backups (entries can carry stream URLs).
     */
    var iptvFavorites: String
        get() = PrefsSafe.str(sp, k("iptv_favorites"), "")
        set(v) { sp.edit().putString(k("iptv_favorites"), v).apply() }

    fun iptvFavoriteList(): List<com.mizan.launcher.core.IptvLogic.FavEntry> =
        com.mizan.launcher.core.IptvLogic.decodeFavorites(iptvFavorites)

    fun setIptvFavoriteList(list: List<com.mizan.launcher.core.IptvLogic.FavEntry>) {
        iptvFavorites = com.mizan.launcher.core.IptvLogic.encodeFavorites(list)
    }

    /** IPTV resume positions, encoded by core/IptvLogic. Per profile, not in backups. */
    var iptvResume: String
        get() = PrefsSafe.str(sp, k("iptv_resume"), "")
        set(v) { sp.edit().putString(k("iptv_resume"), v).apply() }

    fun iptvResumeList(): List<com.mizan.launcher.core.IptvLogic.ResumeEntry> =
        com.mizan.launcher.core.IptvLogic.decodeResume(iptvResume)

    fun setIptvResumeList(list: List<com.mizan.launcher.core.IptvLogic.ResumeEntry>) {
        iptvResume = com.mizan.launcher.core.IptvLogic.encodeResume(list)
    }

    /** Explicitly pattern-locked IPTV categories, encoded by core/IptvLocks. Per profile. */
    var iptvLockedCats: String
        get() = PrefsSafe.str(sp, k("iptv_locked_cats"), "")
        set(v) { sp.edit().putString(k("iptv_locked_cats"), v).apply() }

    /** Per-category unlock stamps (epoch ms), encoded by core/IptvLocks. Per profile. */
    var iptvCatUnlockStamps: String
        get() = PrefsSafe.str(sp, k("iptv_cat_unlock_stamps"), "")
        set(v) { sp.edit().putString(k("iptv_cat_unlock_stamps"), v).apply() }

    /**
     * Custom app artwork (v2.0), encoded by core/ArtworkStore
     * (per package: PRESET index or image URL). Part of backups.
     */
    var artwork: String
        get() = PrefsSafe.str(sp, k("artwork"), "")
        set(v) { sp.edit().putString(k("artwork"), v).apply() }

    fun artworkMap(): Map<String, com.mizan.launcher.core.ArtworkStore.Entry> =
        com.mizan.launcher.core.ArtworkStore.decode(artwork)

    fun setArtwork(pkg: String, entry: com.mizan.launcher.core.ArtworkStore.Entry?) {
        artwork = com.mizan.launcher.core.ArtworkStore.encode(
            com.mizan.launcher.core.ArtworkStore.with(artworkMap(), pkg, entry)
        )
    }

    var layout: LayoutData
        get() = LayoutCodec.decode(PrefsSafe.strOrNull(sp, k("layout")))
        set(v) { sp.edit().putString(k("layout"), LayoutCodec.encode(v)).apply() }

    var lockedApps: Set<String>
        get() = PrefsSafe.stringSet(sp, k("locked"))
        set(v) { sp.edit().putStringSet(k("locked"), v).apply() }

    // ---- per-profile Reddit wallpaper source (v1.1) ----
    /** Comma-separated subreddit names (no "r/" prefix). */
    var redditSubs: String
        get() = PrefsSafe.strOrNull(sp, k("reddit_subs"))
            ?: com.mizan.launcher.core.RedditWallpapers.DEFAULT_SUBREDDITS.joinToString(",")
        set(v) { sp.edit().putString(k("reddit_subs"), v).apply() }

    /** Settings toggle, default OFF: include over_18 posts in results. */
    var redditAllowNsfw: Boolean
        get() = PrefsSafe.bool(sp, k("reddit_nsfw"), false)
        set(v) { sp.edit().putBoolean(k("reddit_nsfw"), v).apply() }

    /** Encoded cache of the last fetched candidate list (offline picker). */
    var redditCache: String
        get() = PrefsSafe.str(sp, k("reddit_cache"), "")
        set(v) { sp.edit().putString(k("reddit_cache"), v).apply() }

    /**
     * v1.6: one-line summary of the most recent Reddit fetch run for
     * this profile ("✓ 18 photos · 2:41 PM" / "✗ r/wallpaper
     * HTTP 403 · 0 photos · 2:41 PM"), built by core/RedditStatus.
     * Shown in Settings › Look & wallpaper › Reddit and the picker —
     * fetch failures are visible now, not silent.
     */
    var redditLastStatus: String
        get() = PrefsSafe.str(sp, k("reddit_last_status"), "")
        set(v) { sp.edit().putString(k("reddit_last_status"), v).apply() }

    /** "Mizan isn't your home screen yet" hint bar dismissed for this profile. */
    var homeHintDismissed: Boolean
        get() = PrefsSafe.bool(sp, k("home_hint_dismissed"), false)
        set(v) { sp.edit().putBoolean(k("home_hint_dismissed"), v).apply() }

    // ---- per-profile wallpaper rotation + Bing (v1.4) ----
    /** Rotation interval key (core/RotationLogic): off | 15m | 1h | 6h | daily. */
    var redditRotation: String
        get() = PrefsSafe.str(
            sp, k("reddit_rotation"), com.mizan.launcher.core.RotationLogic.OFF
        )
        set(v) { sp.edit().putString(k("reddit_rotation"), v).apply() }

    /** When the wallpaper last auto-rotated (epoch ms; 0 = never). */
    var redditRotationLast: Long
        get() = PrefsSafe.long(sp, k("reddit_rotation_last"), 0)
        set(v) { sp.edit().putLong(k("reddit_rotation_last"), v).apply() }

    /** Index into the cached candidate list of the currently-shown Reddit wallpaper. */
    var redditRotationIndex: Int
        get() = PrefsSafe.int(sp, k("reddit_rotation_index"), -1)
        set(v) { sp.edit().putInt(k("reddit_rotation_index"), v).apply() }

    /** Calendar day (core/BingWallpaper.dayKey) the cached Bing image belongs to. */
    var bingCacheDate: String
        get() = PrefsSafe.str(sp, k("bing_cache_date"), "")
        set(v) { sp.edit().putString(k("bing_cache_date"), v).apply() }

    /** Absolute path of the downloaded Bing image for [bingCacheDate]. */
    var bingCachePath: String
        get() = PrefsSafe.str(sp, k("bing_cache_path"), "")
        set(v) { sp.edit().putString(k("bing_cache_path"), v).apply() }

    // ---- per-profile Recently Used (v1.4) ----
    /** Encoded MRU launch list (core/RecentsStore). Not part of backups. */
    var recents: String
        get() = PrefsSafe.str(sp, k("recents"), "")
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
    /**
     * Which profile is the Guest profile: 0 = none, else a profile
     * in [profileSet] (v3.14: was hardcoded 1..2). A stored value
     * outside the set reads as 0 and writes clamp to 0 (fail-open:
     * the guest flag can never point at a profile that does not
     * exist). The flag itself is GLOBAL, not a profile setting —
     * deleting a profile clears it only when it pointed at the
     * deleted profile; resetting a profile never touches it.
     */
    var guestProfile: Int
        get() = com.mizan.launcher.core.ProfileSet.resolveGuest(
            PrefsSafe.int(sp, "guest_profile", 0), profileSet
        )
        set(v) {
            sp.edit().putInt(
                "guest_profile",
                com.mizan.launcher.core.ProfileSet.resolveGuest(v, profileSet)
            ).apply()
        }

    /** iOS-switch in Lock & profiles, default ON: gate Settings entry + switch-away while Guest is active. */
    var guestLockSettings: Boolean
        get() = PrefsSafe.bool(sp, "guest_lock_settings", true)
        set(v) { sp.edit().putBoolean("guest_lock_settings", v).apply() }

    // ---- per-profile custom name (v1.9) ----
    /**
     * The user-chosen name for [profile], stored RAW under that
     * profile's own key (independent of which profile is active);
     * "" = no custom name. All interpretation lives in
     * core/ProfileNames — this is storage only.
     */
    fun profileName(profile: Int): String =
        PrefsSafe.str(sp, "p$profile.profile_name", "")

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

    // ---- profile add / delete / reset (v3.14) ----
    /**
     * Add a profile: the lowest unused slot (1..MAX), or null when
     * all slots are taken — the caller must refuse a 5th profile
     * with a message, never a crash. The new slot needs no keys
     * written: every per-profile read defaults through k(), so a
     * fresh slot already reads as fresh defaults; only the set
     * itself is stored. Does NOT switch — callers switch into the
     * new profile explicitly so the setup flow is visible.
     */
    fun addProfile(): Int? {
        val existing = profileSet
        val next = com.mizan.launcher.core.ProfileSet.add(existing) ?: return null
        profileSet = existing + next
        return next
    }

    /**
     * Remove exactly one profile's own keys (the `p<N>.` prefix,
     * see core/ProfileSet.keysForProfile) and drop it from the set.
     * NEVER touches the shared library store, other profiles' keys,
     * the arrow lock, or safe mode — none of those keys carry the
     * profile prefix, so the filter cannot reach them. Returns
     * false (no change) when [target] does not exist or is the
     * last remaining profile. Deleting the active profile moves
     * the active profile to the lowest remaining one first;
     * deleting the Guest profile clears the Guest flag to None.
     * All writes land in one editor apply.
     */
    fun deleteProfile(target: Int): Boolean {
        val existing = profileSet
        val remaining = com.mizan.launcher.core.ProfileSet.remove(existing, target)
            ?: return false
        try {
            val editor = sp.edit()
            for (key in com.mizan.launcher.core.ProfileSet.keysForProfile(sp.all.keys, target)) {
                editor.remove(key)
            }
            editor.putString(
                "profile_set",
                com.mizan.launcher.core.ProfileSet.encode(remaining)
            )
            if (PrefsSafe.int(sp, "guest_profile", 0) == target) {
                editor.putInt("guest_profile", 0)
            }
            if (PrefsSafe.int(sp, "current_profile", 1) == target) {
                editor.putInt(
                    "current_profile",
                    com.mizan.launcher.core.ProfileSet.lowest(remaining)
                )
            }
            editor.apply()
        } catch (e: Exception) {
            return false
        }
        clearUnlockStamp()
        return true
    }

    /**
     * Reset one profile to fresh defaults WITHOUT removing the
     * slot: exactly the same key set as [deleteProfile] is
     * removed (so the name reverts to "Profile N" — a blank custom
     * name reads as the default label), but the set, the Guest
     * flag and the active profile are left exactly as they were.
     * The shared library, other profiles, the arrow lock and safe
     * mode are never touched (same prefix filter as delete).
     * Returns false (no change) when [target] does not exist.
     * Callers rebuild the home afterwards when the active
     * profile was reset, so defaults show at once.
     */
    fun resetProfile(target: Int): Boolean {
        if (target !in profileSet) return false
        try {
            val editor = sp.edit()
            for (key in com.mizan.launcher.core.ProfileSet.keysForProfile(sp.all.keys, target)) {
                editor.remove(key)
            }
            editor.apply()
        } catch (e: Exception) {
            return false
        }
        if (target == currentProfile) clearUnlockStamp()
        return true
    }

    // ---- sticky status bar (per profile, v1.9) ----
    /**
     * "Sticky status bar": when ON, the status bar renders in a
     * fixed header above the scrollable home instead of scrolling
     * away with it. Default OFF = the pre-v1.9 behaviour.
     */
    var stickyStatusBar: Boolean
        get() = PrefsSafe.bool(sp, k("sticky_status_bar"), false)
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
            PrefsSafe.int(sp, k("unlock_grace_min"), 0)
        )
        set(v) {
            sp.edit().putInt(k("unlock_grace_min"),
                com.mizan.launcher.core.UnlockGrace.normalize(v)).apply()
        }

    /** When the arrow pattern was last verified successfully (epoch ms; 0 = never). */
    var lastUnlockAt: Long
        get() = PrefsSafe.long(sp, "last_unlock_at", 0L)
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
        get() = PrefsSafe.str(sp, "speedup_last_result", "")
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
        get() = if (sp.contains("lock_settings")) PrefsSafe.bool(sp, "lock_settings", false)
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
            val raw = PrefsSafe.str(sp, "arrow_pin", "")
            return if (ArrowPinCodec.isValidEncoded(raw)) raw else ""
        }
        set(v) {
            sp.edit().putString("arrow_pin", if (ArrowPinCodec.isValidEncoded(v)) v else "").apply()
        }

    /** Wrong arrow-unlock attempts, for diagnostics. */
    var arrowAttempts: Int
        get() = PrefsSafe.int(sp, "arrow_attempts", 0)
        set(v) { sp.edit().putInt("arrow_attempts", v).apply() }

    var safeMode: Boolean
        get() = PrefsSafe.bool(sp, "safe_mode", false)
        set(v) { sp.edit().putBoolean("safe_mode", v).apply() }

    // ---- Launcher-owned library (GLOBAL, v3.7) ----
    /**
     * v3.7: the one-time tracker import is CLOSED once this
     * is true — set when (a) the import wrote the store,
     * (b) the store was already newer than the provider,
     * (c) the provider is absent (nothing to import), or
     * (d) the store was built from local prefs/mirror
     * (provider already gone). While false, the cold
     * chain and explicit refreshes may still query the
     * provider — that is the import's sole exception to
     * the no-provider-read rule. After true, NO code path
     * queries the tracker provider again, and a missing
     * tracker app reads as normal. GLOBAL (the store is
     * household data, not per-profile), like tmdbKey.
     */
    var libraryImported: Boolean
        get() = PrefsSafe.bool(sp, "library_imported", false)
        set(v) { sp.edit().putBoolean("library_imported", v).apply() }

    /**
     * v3.7: when the launcher library last changed by a
     * local user action (mark/add/remove/mute), epoch ms,
     * 0 = never. Shown in the diagnostics `library:` line
     * (counts only, never titles). GLOBAL, like the store.
     */
    var libraryLastChange: Long
        get() = PrefsSafe.long(sp, "library_last_change", 0L)
        set(v) { sp.edit().putLong("library_last_change", v).apply() }

    var weatherLat: String
        get() = PrefsSafe.str(sp, "weather_lat", "39.1653")
        set(v) { sp.edit().putString("weather_lat", v).apply() }

    var weatherLon: String
        get() = PrefsSafe.str(sp, "weather_lon", "-86.5264")
        set(v) { sp.edit().putString("weather_lon", v).apply() }

    var lastWeatherFetch: Long
        get() = PrefsSafe.long(sp, "weather_last_fetch", 0)
        set(v) { sp.edit().putLong("weather_last_fetch", v).apply() }

    /**
     * v3.2 (ask 43): the last EXPLICIT home-data refresh, for the
     * diagnostics `last refresh:` line — the proof no auto-refresh
     * runs any more. 0 = never; kind is "manual" (Refresh home
     * data) or "retry:<section key>". Written only by those two
     * paths, never by open/resume/switch.
     */
    var lastRefreshAt: Long
        get() = PrefsSafe.long(sp, "last_refresh_at", 0)
        set(v) { sp.edit().putLong("last_refresh_at", v).apply() }

    var lastRefreshKind: String
        get() = PrefsSafe.str(sp, "last_refresh_kind", "")
        set(v) { sp.edit().putString("last_refresh_kind", v).apply() }

    var cachedWeather: String
        get() = PrefsSafe.str(sp, "weather_cached", "")
        set(v) { sp.edit().putString("weather_cached", v).apply() }

    /**
     * Last fetched weather snapshot, encoded by data/Weather as
     * "code|isDay|temp|dailyCodeCsv" — drives the weather background
     * even when the status-bar widget is hidden. "" = never fetched.
     */
    var weatherData: String
        get() = PrefsSafe.str(sp, "weather_data", "")
        set(v) { sp.edit().putString("weather_data", v).apply() }

    /** Weather background state for diagnostics: "active: rain" / "fallback: ..." / "off". */
    var weatherBgStatus: String
        get() = PrefsSafe.str(sp, "weather_bg_status", "off")
        set(v) { sp.edit().putString("weather_bg_status", v).apply() }

    // ---- per-profile weather units (v1.2) ----
    /** Stored key of the WeatherUnits enum; invalid values read as Fahrenheit. */
    var weatherUnitsKey: String
        get() = WeatherUnits.fromKey(PrefsSafe.strOrNull(sp, k("weather_units"))).key
        set(v) { sp.edit().putString(k("weather_units"), WeatherUnits.fromKey(v).key).apply() }

    val weatherUnits: WeatherUnits get() = WeatherUnits.fromKey(weatherUnitsKey)

    companion object {
        /** Record a fail-open error for the diagnostics screen instead of crashing. */
        fun noteError(ctx: Context, where: String, e: Throwable) {
            try {
                // v2.9: the message is scrubbed at the sink
                // (core/DiagScrub) — a network exception's message
                // can embed the request URL, and Xtream/TMDB URLs
                // carry credentials/keys. No call site can leak
                // one into diagnostics or logs through here.
                val msg = com.mizan.launcher.core.DiagScrub.message(e.message)
                ctx.getSharedPreferences("mizan_launcher", Context.MODE_PRIVATE)
                    .edit()
                    .putString("last_error", "$where: ${e.javaClass.simpleName}: $msg")
                    .apply()
            } catch (ignored: Throwable) {
                // Diagnostics itself must never crash the launcher.
            }
        }

        fun lastError(ctx: Context): String =
            PrefsSafe.str(
                ctx.getSharedPreferences("mizan_launcher", Context.MODE_PRIVATE),
                "last_error", ""
            )

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
                val lines = PrefsSafe.str(p, "reddit_attempt_log", "")
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
                PrefsSafe.str(sp(ctx), "reddit_attempt_log", "")
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
                    .putInt("watcher_relaunches", PrefsSafe.int(p, "watcher_relaunches", 0) + 1)
                    .putLong("watcher_last_at", System.currentTimeMillis())
                    .apply()
            } catch (ignored: Throwable) {
                // Diagnostics must never crash the watcher.
            }
        }

        fun watcherRelaunches(ctx: Context): Int =
            try { PrefsSafe.int(sp(ctx), "watcher_relaunches", 0) } catch (e: Throwable) { 0 }

        fun watcherLastAt(ctx: Context): Long =
            try { PrefsSafe.long(sp(ctx), "watcher_last_at", 0) } catch (e: Throwable) { 0 }

        /** Timestamp of the last USER_PRESENT / SCREEN_ON recovery nudge. */
        fun noteScreenOnRecovery(ctx: Context) {
            try {
                sp(ctx).edit().putLong("screenon_last_at", System.currentTimeMillis()).apply()
            } catch (ignored: Throwable) {
            }
        }
    }
}
