package com.mizan.launcher.core

/**
 * v3.2 (ask 43): the no-refresh-on-open policy, as pure decisions
 * the sim pins. The home NEVER refreshes on open, on resume, or
 * on profile switch — the local cache is the only source until
 * the user explicitly requests a refresh. The only automatic
 * exception is first-run seeding on a genuinely empty cache.
 *
 * Deliberately NOT a fetch trigger any more: TTL expiry. A stale
 * cache renders stale until a manual refresh or a per-section
 * Retry; TTLs survive only as diagnostics age, never as a reason
 * to hit the network on the open path.
 *
 * Kept automatic (documented, not hidden): the app-list check
 * (PackageManager scan, change-gated, never blocking — a newly
 * installed app must appear without a manual refresh), the
 * Reddit/Bing calendar schedule (wallpaper changes on a due
 * date / new day, while the home is visible — a wallpaper
 * change, not a data refresh), and weather's 30-minute fetch
 * from the visible-home tick only (never from open/resume).
 */
object RefreshPolicy {

    /** What caused a would-be refresh. */
    enum class Trigger {
        OPEN, RESUME, PROFILE_SWITCH,
        MANUAL, SECTION_RETRY, FIRST_RUN_SEED
    }

    /** Automatic refresh is allowed for this trigger. */
    fun autoRefreshAllowed(trigger: Trigger): Boolean = when (trigger) {
        Trigger.MANUAL, Trigger.SECTION_RETRY, Trigger.FIRST_RUN_SEED -> true
        Trigger.OPEN, Trigger.RESUME, Trigger.PROFILE_SWITCH -> false
    }

    /** Tracker snapshot fetch on the home path: only when nothing is stored yet. */
    fun trackerSeedNeeded(hasStoredData: Boolean): Boolean = !hasStoredData

    /**
     * Discovery worker network fetch: only on an explicit request
     * (manual Refresh / per-section Retry, both force) or when
     * the cache file does not exist at all (first-run seeding).
     * A stale-but-present file never triggers a fetch.
     */
    fun discoveryFetchAllowed(force: Boolean, cacheFileExists: Boolean): Boolean =
        force || !cacheFileExists

    /** Weather fetch: only from the visible-home tick while stale — never from open/resume. */
    fun weatherFetchAllowed(stale: Boolean, fromVisibleTick: Boolean): Boolean =
        stale && fromVisibleTick

    /** Dynamic-accent recompute: only when the wallpaper actually changed. */
    fun accentRecomputeNeeded(lastWallpaper: String, currentWallpaper: String): Boolean =
        lastWallpaper != currentWallpaper
}
