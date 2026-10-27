package com.mizan.launcher.domain

/**
 * IPTV catalogues, guide, favorites, resume, locks and playback.
 * Xtream Codes API + M3U playlists are independent sources: channels
 * join Live rows but sources are NOT merged or de-duplicated.
 * Credentials live only in SharedPreferences — never in backups,
 * diagnostics, logs, or any file this app produces.
 */
data class IptvChannel(
    val id: String,
    val name: String,
    val categoryId: String,
    val categoryName: String,
    val streamUrl: String,
    val logoUrl: String? = null,
    /** EPG now-title; null when unknown. */
    val nowTitle: String? = null,
    /** Now-programme progress 0..1; null when unknown. */
    val nowProgress: Float? = null,
    val hasCatchUp: Boolean = false,
    val source: String = "xtream",
)

data class VodItem(
    val id: String,
    val name: String,
    val categoryId: String,
    val streamUrl: String,
    val containerExtension: String = "mp4",
    val rating: String? = null,
    val resumeMs: Long? = null,
    /** Finished = near end or >= 95%; unknown duration is NOT finished. */
    val finished: Boolean = false,
)

data class SeriesEpisode(
    val id: String,
    val season: Int,
    val episode: Int,
    val title: String,
    val streamUrl: String,
    val durationSecs: Long? = null,
    val plot: String = "",
    val resumeMs: Long? = null,
)

data class SeriesItem(
    val id: String,
    val name: String,
    val categoryId: String,
    val posterUrl: String? = null,
)

data class EpgProgramme(
    val channelId: String,
    val title: String,
    val startMs: Long,
    val endMs: Long,
    val description: String = "",
)

data class IptvCatalogueStatus(
    val liveCount: Int = 0,
    val vodCount: Int = 0,
    val seriesCount: Int = 0,
    val lastUpdateMs: Long = 0,
    /** Failure class only (never credentials, never in non-error lines). */
    val failureClass: String? = null,
)

/**
 * Source-qualified live category (Phase 2D canonical fix for the
 * Xtream/M3U numeric-id collision). [id] is the category id as
 * used by getLiveChannels (M3U ids carry the "m3u:" prefix);
 * [source] is "xtream" or "m3u". Callers that need the source
 * must use this; getLiveCategories() is kept for compatibility
 * and returns the same qualified ids.
 */
data class IptvCategory(
    val id: String,
    val name: String,
    val source: String,
)

interface IptvRepository {
    fun getStatus(): IptvCatalogueStatus

    /** Refresh catalogues + guide. On request only — never on home build. */
    fun refresh()

    fun getLiveChannels(categoryId: String? = null): List<IptvChannel>
    fun getLiveCategories(): List<Pair<String, String>> // id to name

    /** Canonical category list with the source attached (see IptvCategory). */
    fun getLiveCategoriesDetailed(): List<IptvCategory>

    /**
     * IPTV series id for a TMDB show, via the watch-match cache, or
     * null when unmatched. This is the Details IPTV series-info
     * fallback lookup: callers then call getSeriesEpisodes(seriesId).
     * Worker thread only (it may recompute the match set).
     */
    fun iptvSeriesForShow(tmdbId: Long): String?
    fun getVod(categoryId: String? = null): List<VodItem>
    fun getSeries(categoryId: String? = null): List<SeriesItem>
    fun getSeriesEpisodes(seriesId: String): List<SeriesEpisode>

    /** Now/next programmes; hidden/locked categories excluded. */
    fun getGuide(channelId: String): List<EpgProgramme>

    fun search(query: String): Triple<List<IptvChannel>, List<VodItem>, List<SeriesItem>>

    // Favorites / resume / locks (per profile; survive source clears).
    fun getFavorites(): List<String> // identity keys
    fun setFavorite(identityKey: String, favorite: Boolean)

    /**
     * Full-entry favorite write: carries the display name so the
     * Favorites tab keeps names (the key-only overload cannot
     * reconstruct them). [identityKey] is "source:kind:id".
     */
    fun setFavoriteWithMeta(identityKey: String, name: String, favorite: Boolean)

    fun getResume(identityKey: String): Long?
    fun setResume(identityKey: String, positionMs: Long?)

    /** Full-entry resume write: carries the title with the position. */
    fun setResumeWithMeta(identityKey: String, positionMs: Long?, title: String)
    fun getLockedCategories(): Set<String>
    fun setCategoryLocked(categoryId: String, locked: Boolean)
    fun isCategoryUnlocked(categoryId: String): Boolean

    /** Build a PlayItem for a channel / VOD / series episode. */
    fun playItemForChannel(channel: IptvChannel): PlayItem
    fun playItemForVod(vod: VodItem): PlayItem
    fun playItemForEpisode(episode: SeriesEpisode): PlayItem
}
