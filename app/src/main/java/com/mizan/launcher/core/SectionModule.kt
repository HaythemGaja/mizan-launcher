package com.mizan.launcher.core

/**
 * One module per home section (v3.1, ask 39): every home section
 * has an explicit lifecycle, independent of the others, so a slow
 * section (Trending waiting on TMDB) can never delay or block any
 * other section's render.
 *
 * States (decided by [decide]):
 * - LOADING: the section's source worker is still running and the
 *   section has no data yet. Renders a "Loading…" placeholder
 *   (title + one non-focusable card — D-pad skippable, never
 *   focus-trapping). A section that already HAS data never flips
 *   back to LOADING while a refresh runs (stale-while-revalidate:
 *   it keeps showing last-known data).
 * - READY: data present, renders normally.
 * - EMPTY: no data, no worker running, no failure. Renders
 *   NOTHING — the standing no-bare-header rule (an empty section
 *   never renders a bare header) still holds; EMPTY exists in the
 *   machine so LOADING/ERROR are never confused with it.
 * - ERROR: the section's composition threw (or its loader
 *   reported failure). Renders its own error tile with its own
 *   "Retry" action, which re-runs ONLY that section's source
 *   ([retrySource] routes the key to its loader).
 *
 * Precedence in [decide]: a worker in flight wins over a recorded
 * failure (a retry in flight shows Loading, then lands on
 * READY/ERROR/EMPTY); a failure wins over stale data (a section
 * that just threw must not silently keep showing old cards).
 * The UI layer applies one refinement the pure machine cannot
 * see: a section WITH data that is ALSO mid-refresh stays READY
 * (the `loading` input is only true when the section has no data
 * yet — the caller computes it that way).
 */
object SectionModule {

    enum class State { LOADING, READY, EMPTY, ERROR }

    /** Which loader a section's Retry re-runs. NONE = re-render only (in-memory data). */
    enum class Source { TRACKER, DISCOVERY, IPTV, APPS, NONE }

    fun decide(hasData: Boolean, loading: Boolean, failed: Boolean): State = when {
        loading -> State.LOADING
        failed -> State.ERROR
        hasData -> State.READY
        else -> State.EMPTY
    }

    fun isRetryable(state: State): Boolean = state == State.ERROR

    /**
     * Retry routing (pure, sim-pinned). Tracker-family keys are
     * the TrackerSections keys; IPTV catalogue rows ride the IPTV
     * slice worker; favorites/apps are in-memory (AppCache) so
     * their retry is a re-render. Unknown keys route to NONE
     * (re-render) rather than crashing the menu.
     */
    fun retrySource(sectionKey: String): Source = when (sectionKey) {
        TrackerSections.HERO,
        TrackerSections.UPNEXT,
        TrackerSections.MISSED,
        TrackerSections.CONTINUE,
        TrackerSections.FORYOU -> Source.TRACKER
        TrackerSections.TRENDINGTV,
        TrackerSections.TRENDINGMOVIES -> Source.DISCOVERY
        // The IPTV trending row is discovery-computed; catalogue
        // rows ride the IPTV slice worker.
        "iptv-trending" -> Source.DISCOVERY
        "iptv-row" -> Source.IPTV
        TrackerSections.FAVORITES,
        TrackerSections.ALLAPPS -> Source.APPS
        else -> Source.NONE
    }

    /**
     * Whether a section with no data yet is LOADING (placeholder)
     * or EMPTY (skipped): tracker sections load while the tracker
     * fetch worker runs; discovery sections load until the
     * discovery worker's first run completes. The caller supplies
     * the two worker flags; this stays pure for the sim.
     */
    fun isLoading(
        sectionKey: String,
        hasData: Boolean,
        trackerFetching: Boolean,
        discoveryLoaded: Boolean
    ): Boolean {
        if (hasData) return false
        return when (retrySource(sectionKey)) {
            Source.TRACKER -> trackerFetching
            Source.DISCOVERY -> !discoveryLoaded
            else -> false
        }
    }
}
