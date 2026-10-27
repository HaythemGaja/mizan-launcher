package com.mizan.launcher.domain

/**
 * Pure tracker/library models. No Android types, no data/ui/app imports.
 * Data implementations map the on-disk store / snapshots onto these;
 * UI renders only these. Titles, ids and key material must never be
 * copied into diagnostics — diagnostics carry counts/state only.
 */
enum class MediaKind { SHOW, MOVIE }

data class EpisodeRef(
    val showTmdbId: Long,
    val season: Int,
    val episode: Int,
    /** Raw air-date string as stored, usually yyyy-MM-dd; may be blank. */
    val airDate: String?,
    val title: String = "",
)

data class TrackedShow(
    val tmdbId: Long,
    val title: String,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val overview: String = "",
    val year: Int? = null,
)

data class TrackedMovie(
    val tmdbId: Long,
    val title: String,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val overview: String = "",
    val year: Int? = null,
    val watched: Boolean = false,
)

/** In-progress position for a show or movie. */
data class WatchProgress(
    val tmdbId: Long,
    val kind: MediaKind,
    val watchedCount: Int,
    val totalCount: Int,
    /** Next unwatched coordinates; null when caught up / finished. */
    val nextSeason: Int? = null,
    val nextEpisode: Int? = null,
    val caughtUp: Boolean = false,
)

/** A title on the user's plan-to-watch list (untracked titles only). */
data class PlanToWatchItem(
    val tmdbId: Long,
    val kind: MediaKind,
    val title: String,
    val posterPath: String? = null,
)

/**
 * Ignored / muted titles. `ignored` drops a title from For You;
 * `notificationsMuted` silences its airing notifications only.
 */
data class IgnoredItem(
    val tmdbId: Long,
    val kind: MediaKind,
    val mutedNotifications: Boolean = false,
    val dismissedFromForYou: Boolean = false,
)

/** One row of the Up Next / Missed / Continue / For You home sections. */
data class HomeCard(
    val tmdbId: Long,
    val kind: MediaKind,
    val title: String,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    /** Next episode coordinates for shows; null for movies. */
    val nextSeason: Int? = null,
    val nextEpisode: Int? = null,
    /** Human countdown label, e.g. "today", "tomorrow", "in 3 d", or "". */
    val countdownLabel: String = "",
    val isMissed: Boolean = false,
    val progress: WatchProgress? = null,
)
