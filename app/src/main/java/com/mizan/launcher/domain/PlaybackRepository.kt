package com.mizan.launcher.domain

/**
 * Playback. No foreground service anywhere in the player.
 * PlayItem/PlayQueue shape mirrors the old data/IptvClient tail.
 */
enum class PlayKind { LIVE, VOD, SERIES_EPISODE }

data class PlayItem(
    val kind: PlayKind,
    /** Channel / VOD / episode id in its catalogue. */
    val id: String,
    val title: String,
    val episodeName: String? = null,
    val streamUrl: String,
    val resumeMs: Long? = null,
    /** Tracker library mark token; null = no library twin, no auto-mark. */
    val libraryMarkToken: String? = null,
    val tmdbId: Long? = null,
    val season: Int? = null,
    val episode: Int? = null,
)

data class PlayQueue(
    val items: List<PlayItem>,
    val currentIndex: Int = 0,
) {
    fun current(): PlayItem? = items.getOrNull(currentIndex)
    fun previous(): PlayItem? = items.getOrNull(currentIndex - 1)
    fun next(): PlayItem? = items.getOrNull(currentIndex + 1)
}

interface PlaybackRepository {
    fun saveResume(identityKey: String, positionMs: Long)
    fun clearResume(identityKey: String)

    /**
     * Phase 2D queue contract. Returns the episode queue around
     * [item] (neighbours for Prev/Next), or null when no queue
     * can be derived — callers then report "No queue neighbour"
     * honestly instead of inventing one. The data implementation
     * returns null: a PlayItem carries an episode id, not its
     * series id, so a series queue cannot be derived from it
     * alone; series-detail screens build neighbour lists from
     * IptvRepository.getSeriesEpisodes directly.
     */
    fun queueFor(item: PlayItem): PlayQueue?

    /**
     * Watch auto-mark: fires ONCE on playback ended (even at low
     * position) or at exactly 90% (not 89%); never twice, never
     * tokenless, never on a partial watch. Implementations dedupe.
     */
    fun maybeAutoMark(item: PlayItem, positionMs: Long, durationMs: Long, ended: Boolean)

    /** Aspect cycle: Fit -> Stretch -> Zoom -> Fit; persists per profile. */
    fun getAspect(): String
    fun setAspect(aspect: String)

    /** Speed ladder cycles and wraps (2x -> 0.5x). */
    fun nextSpeed(current: Double): Double
}
