package com.mizan.launcher.domain

/** TMDB image URL contract (sizes pinned by MAP §5). */
object TmdbImages {
    private const val BASE = "https://image.tmdb.org/t/p/"
    const val POSTER_SIZE = "w342"
    const val BACKDROP_SIZE = "w780"

    fun posterUrl(path: String?): String? =
        path?.takeIf { it.isNotBlank() }?.let { BASE + POSTER_SIZE + it }

    fun backdropUrl(path: String?): String? =
        path?.takeIf { it.isNotBlank() }?.let { BASE + BACKDROP_SIZE + it }
}

data class TmdbSearchResult(
    val tmdbId: Long,
    val kind: MediaKind,
    val title: String,
    val posterPath: String? = null,
    val overview: String = "",
    val year: Int? = null,
    /** True when the title is already in the library. */
    val alreadyAdded: Boolean = false,
)

data class TmdbTitleDetails(
    val tmdbId: Long,
    val kind: MediaKind,
    val title: String,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val overview: String = "",
    val year: Int? = null,
    val seasons: List<Int> = emptyList(),
)

/** Error kinds for TMDB access; tokens only, never exception messages. */
enum class TmdbErrorKind { NO_KEY, HTTP, IO, BAD_PATH, UNKNOWN }

/**
 * TMDB access: tracker proxy FIRST, launcher-key direct SECOND.
 * If both paths fail, callers surface the PROXY error (the direct
 * token is diagnostics-only). Implementations must never log a key
 * or carry exception messages (they can embed the key).
 */
interface TmdbRepository {
    fun searchTitles(query: String): Result<List<TmdbSearchResult>>
    fun getDetails(tmdbId: Long, kind: MediaKind): Result<TmdbTitleDetails>
    fun getSeasonEpisodes(tmdbId: Long, season: Int): Result<List<EpisodeRef>>
}
