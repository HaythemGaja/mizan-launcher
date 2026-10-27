package com.mizan.launcher.domain

/**
 * Use-case interfaces. Implementations land with the data layer
 * (or thin domain implementations over repositories); UI depends
 * on these interfaces, never on repository implementations.
 * All use cases are synchronous and must be called off the main
 * thread; the UI layer owns threading and section state machines
 * (LOADING / READY / EMPTY / ERROR + per-section retry).
 */
interface GetUpNextUseCase {
    operator fun invoke(): List<HomeCard>
}

interface GetMissedUseCase {
    operator fun invoke(): List<HomeCard>
}

interface GetContinueUseCase {
    operator fun invoke(): List<HomeCard>
}

/**
 * Hero pool order: Missed (most overdue first) -> Up Next ->
 * Continue (backdrop-first) -> For You. Backdrop presence never
 * reorders tiers. Fewer than 2 candidates never rotate (rotation
 * itself lives in core/HeroRotator).
 */
interface GetHeroUseCase {
    operator fun invoke(): HomeCard?
}

interface GetForYouUseCase {
    operator fun invoke(): List<HomeCard>
}

interface GetTrendingUseCase {
    fun trendingTv(): List<HomeCard>
    fun trendingMovies(): List<HomeCard>
}

/** Card play: matched card plays via IPTV; unmatched opens Details. */
interface PlayTitleUseCase {
    /** Returns the PlayItem, or null when the title must open Details. */
    fun playCard(card: HomeCard): PlayItem?
    fun playHeroPrimary(card: HomeCard): PlayItem?
    /** Matched show without a library twin plays from S1 E1, no mark token. */
    fun playFromFirstEpisode(card: HomeCard): PlayItem?

    /**
     * Phase 2D: true when the title has an IPTV match (drives the
     * Details ▶ glyph: aired AND matched). Worker thread only
     * (it may recompute the match set on first use).
     */
    fun isMatched(tmdbId: Long): Boolean

    /**
     * Phase 2D: play one specific episode of a matched show
     * (Details episode menu). Returns null when unmatched or the
     * episode is not on IPTV; the library mark token is attached
     * only for tracked shows (S1 E1 no-twin rule does not apply
     * here — a specific episode with no twin still plays, with a
     * null token, matching playCard's fail-open behaviour).
     */
    fun playEpisode(showTmdbId: Long, season: Int, episode: Int): PlayItem?
}

/**
 * Episode marking, with the AiredGate enforced by implementations:
 * unaired + unwatched toggles nothing (toast via AiredGate);
 * season marking filters through AiredGate first.
 */
interface MarkEpisodeUseCase {
    fun toggle(episode: EpisodeRef)
    fun markSeason(showTmdbId: Long, season: Int, episodes: List<EpisodeRef>)
    fun markMovie(tmdbId: Long, watched: Boolean)
}

interface SearchTitlesUseCase {
    /** Installed apps + tracker titles; tracker hits open Details. */
    fun searchApps(query: String): List<InstalledApp>
    fun searchTracker(query: String): List<HomeCard>
    fun searchTmdb(query: String): Result<List<TmdbSearchResult>>
}

interface GetNowPlayingUseCase {
    operator fun invoke(): List<NowPlayingItem>
}

interface GetForecastUseCase {
    operator fun invoke(): WeatherSnapshot?
}

interface GetDiagnosticsUseCase {
    operator fun invoke(): DiagnosticsSnapshot
}

/** Folder operations honouring core/LayoutStore rules (see AppsRepository). */
interface ManageFoldersUseCase {
    fun create(name: String): Folder
    fun rename(folderId: String, name: String)
    fun delete(folderId: String)
    fun moveEntry(entryKey: String, targetFolderId: String?)
}

/** Discovery: launcher-computed Coming Soon / Trending / For You. */
interface RefreshDiscoveryUseCase {
    /** 12 h trending TTL; disk file cache; single rebuild on change. */
    operator fun invoke(force: Boolean = false)
}
