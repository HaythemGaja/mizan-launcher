package com.mizan.launcher.domain

/**
 * Tracker/library store. Local-first since v3.7: after the one-time
 * import flag is set, no implementation may query the tracker app's
 * provider again. All reads/writes are local (filesDir/tracker/ +
 * pending overlay). Implementations run on worker threads; callers
 * must never invoke these on the main thread.
 *
 * The pending overlay (optimistic writes) is owned by the data layer:
 * writes render instantly from it and retire part-by-part as snapshots
 * converge (newest toggle wins). Repositories expose the *effective*
 * (overlay-applied) view; raw snapshot access is a data-layer detail.
 */
interface TrackerRepository {

    // ---- Reads: effective (overlay-applied) views ----

    fun getTrackedShows(): List<TrackedShow>

    fun getTrackedMovies(): List<TrackedMovie>

    /** Next episode per tracked show, with countdown labels. */
    fun getUpNext(): List<HomeCard>

    /** Episodes whose air date passed unwatched (pending scoped per show). */
    fun getMissed(): List<HomeCard>

    /** In-progress shows/movies with progress coordinates. */
    fun getContinueWatching(): List<HomeCard>

    /** Launcher-computed picks, or tracker snapshot picks as fallback. */
    fun getForYou(): List<HomeCard>

    fun getPlanToWatch(): List<PlanToWatchItem>

    fun getIgnored(): List<IgnoredItem>

    fun getProgress(tmdbId: Long, kind: MediaKind): WatchProgress?

    /** Effective watched set for one show (marked set + pending overlay). */
    fun getWatchedEpisodes(showTmdbId: Long): Set<String>

    /** Episodes of one season, ascending, for Details. */
    fun getSeasonEpisodes(showTmdbId: Long, season: Int): List<EpisodeRef>

    /** Seasons present for a show, ascending, empty seasons dropped. */
    fun getSeasons(showTmdbId: Long): List<Int>

    fun isMuted(tmdbId: Long, kind: MediaKind): Boolean

    // ---- Writes (optimistic overlay + queue writers) ----

    /** Toggle one episode; the unaired gate lives in the caller (AiredGate). */
    fun toggleEpisode(episode: EpisodeRef)

    fun markEpisodeWatched(episode: EpisodeRef, watched: Boolean)

    /**
     * Mark a whole season: implementations MUST filter through
     * AiredGate first and write nothing when no episode has aired.
     */
    fun markSeasonWatched(showTmdbId: Long, season: Int, episodes: List<EpisodeRef>)

    fun markMovieWatched(tmdbId: Long, watched: Boolean)

    fun addToLibrary(tmdbId: Long, kind: MediaKind)

    /**
     * Enriched add: same pending-overlay write, but the entry
     * carries the title + poster path (e.g. from a TMDB search
     * hit) instead of empty strings until a snapshot lands.
     */
    fun addToLibrary(tmdbId: Long, kind: MediaKind, title: String, posterPath: String?)

    fun removeFromLibrary(tmdbId: Long, kind: MediaKind)

    fun addToPlanToWatch(item: PlanToWatchItem)

    fun dismissForYou(tmdbId: Long, kind: MediaKind)

    fun setMuted(tmdbId: Long, kind: MediaKind, muted: Boolean)

    /** Recompute the missed list for one show / all shows. */
    fun recomputeMissed(showTmdbId: Long? = null)
}
