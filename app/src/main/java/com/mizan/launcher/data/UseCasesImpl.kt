package com.mizan.launcher.data

import android.content.Context
import com.mizan.launcher.core.AppSearch
import com.mizan.launcher.core.LibraryLogic
import com.mizan.launcher.core.TrackerLogic
import com.mizan.launcher.domain.GetContinueUseCase
import com.mizan.launcher.domain.GetDiagnosticsUseCase
import com.mizan.launcher.domain.GetForYouUseCase
import com.mizan.launcher.domain.GetForecastUseCase
import com.mizan.launcher.domain.GetHeroUseCase
import com.mizan.launcher.domain.GetMissedUseCase
import com.mizan.launcher.domain.GetNowPlayingUseCase
import com.mizan.launcher.domain.GetTrendingUseCase
import com.mizan.launcher.domain.GetUpNextUseCase
import com.mizan.launcher.domain.HomeCard
import com.mizan.launcher.domain.ManageFoldersUseCase
import com.mizan.launcher.domain.MarkEpisodeUseCase
import com.mizan.launcher.domain.SearchTitlesUseCase
import com.mizan.launcher.domain.EpisodeRef
import com.mizan.launcher.domain.Folder
import com.mizan.launcher.domain.InstalledApp
import com.mizan.launcher.domain.MediaKind
import com.mizan.launcher.domain.AiredGate

/**
 * Domain use cases over the data repositories. Hero pool order:
 * Missed (most overdue first) → Up Next → Continue
 * (backdrop-first) → For You; backdrop presence never reorders
 * tiers; fewer than 2 candidates never rotate (rotation itself
 * lives in core/HeroRotator, owned by the UI worker).
 */
class UseCasesImpl(ctx: Context) {
    private val tracker = TrackerRepositoryImpl(ctx)
    private val apps = AppsRepositoryImpl(ctx)
    private val nowPlaying = NowPlayingRepositoryImpl(ctx)
    private val weather = WeatherRepositoryImpl(ctx)
    private val diagnostics = DiagnosticsRepositoryImpl(ctx)
    private val discovery = DiscoveryWorker(ctx)
    private val tmdb = TmdbRepositoryImpl(ctx)

    val getUpNext: GetUpNextUseCase = object : GetUpNextUseCase { override fun invoke() = tracker.getUpNext() }
    val getMissed: GetMissedUseCase = object : GetMissedUseCase { override fun invoke() = tracker.getMissed() }
    val getContinue: GetContinueUseCase = object : GetContinueUseCase { override fun invoke() = tracker.getContinueWatching() }
    val getForYou: GetForYouUseCase = object : GetForYouUseCase { override fun invoke() = tracker.getForYou() }
    val getHero: GetHeroUseCase = object : GetHeroUseCase {
      override fun invoke(): HomeCard? {
        val missed = tracker.getMissed()
        if (missed.isNotEmpty()) return missed.first()
        val up = tracker.getUpNext()
        if (up.isNotEmpty()) return up.first()
        val cont = tracker.getContinueWatching()
        val withBackdrop = cont.firstOrNull { it.backdropPath != null }
        if (withBackdrop != null) return withBackdrop
        if (cont.isNotEmpty()) return cont.first()
        return tracker.getForYou().firstOrNull()
      }
    }
    val getTrending: GetTrendingUseCase = object : GetTrendingUseCase {
        override fun trendingTv(): List<HomeCard> = discovery.trendingTvCards()
        override fun trendingMovies(): List<HomeCard> = discovery.trendingMovieCards()
    }
    val markEpisode: MarkEpisodeUseCase = object : MarkEpisodeUseCase {
        override fun toggle(episode: EpisodeRef) {
            // AiredGate enforced here: unaired + unwatched toggles
            // nothing (UI shows AiredGate.unairedMessage); unaired +
            // already-marked is the correction path (unwatch allowed).
            val watched = LibraryLogic.epKey(episode.season, episode.episode) in
                tracker.getWatchedEpisodes(episode.showTmdbId)
            if (AiredGate.isUnaired(episode.airDate) && !watched) return
            tracker.toggleEpisode(episode)
        }
        override fun markSeason(showTmdbId: Long, season: Int, episodes: List<EpisodeRef>) {
            tracker.markSeasonWatched(showTmdbId, season, episodes) // AiredGate inside
        }
        override fun markMovie(tmdbId: Long, watched: Boolean) {
            tracker.markMovieWatched(tmdbId, watched)
        }
    }
    val searchTitles: SearchTitlesUseCase = object : SearchTitlesUseCase {
        override fun searchApps(query: String): List<InstalledApp> {
            val all = apps.getAllApps()
            if (query.isBlank()) return all
            // core/AppSearch.filter: prefix before substring, order preserved.
            val items = all.map { AppSearch.Item(it.packageName, it.label) }
            val filtered = AppSearch.filter(items, query)
            val byPkg = all.associateBy { it.packageName }
            return filtered.mapNotNull { byPkg[it.pkg] }
        }
        override fun searchTracker(query: String): List<HomeCard> {
            if (query.isBlank()) return tracker.getUpNext() + tracker.getContinueWatching()
            val q = query.lowercase()
            val hits = ArrayList<HomeCard>()
            hits.addAll(tracker.getUpNext().filter { it.title.lowercase().contains(q) })
            hits.addAll(tracker.getContinueWatching().filter { it.title.lowercase().contains(q) })
            hits.addAll(tracker.getTrackedShows().filter { it.title.lowercase().contains(q) }
                .map { HomeCard(it.tmdbId, MediaKind.SHOW, it.title, it.posterPath, it.backdropPath) })
            hits.addAll(tracker.getTrackedMovies().filter { it.title.lowercase().contains(q) }
                .map { HomeCard(it.tmdbId, MediaKind.MOVIE, it.title, it.posterPath, it.backdropPath) })
            return hits.distinctBy { it.kind to it.tmdbId }
        }
        override fun searchTmdb(query: String) = tmdb.searchTitles(query)
    }
    val getNowPlaying: GetNowPlayingUseCase = object : GetNowPlayingUseCase { override fun invoke() = nowPlaying.getSessions() }
    val getForecast: GetForecastUseCase = object : GetForecastUseCase { override fun invoke() = weather.getCached() }
    val getDiagnostics: GetDiagnosticsUseCase = object : GetDiagnosticsUseCase { override fun invoke() = diagnostics.snapshot() }
    val manageFolders: ManageFoldersUseCase = object : ManageFoldersUseCase {
        override fun create(name: String): Folder = apps.createFolder(name)
        override fun rename(folderId: String, name: String) = apps.renameFolder(folderId, name)
        override fun delete(folderId: String) = apps.deleteFolder(folderId)
        override fun moveEntry(entryKey: String, targetFolderId: String?) =
            apps.moveEntry(entryKey, targetFolderId)
    }
}

/**
 * Wiring helper for the integration worker: one place that builds
 * every repository implementation for a Context. UI depends on
 * domain interfaces; this factory is data-layer owned.
 */
object DataFactory {
    fun tracker(ctx: Context) = TrackerRepositoryImpl(ctx)
    fun tmdb(ctx: Context) = TmdbRepositoryImpl(ctx)
    fun iptv(ctx: Context) = IptvRepositoryImpl(ctx)
    fun apps(ctx: Context) = AppsRepositoryImpl(ctx)
    fun weather(ctx: Context) = WeatherRepositoryImpl(ctx)
    fun settings(ctx: Context) = SettingsRepositoryImpl(ctx)
    fun playback(ctx: Context) = PlaybackRepositoryImpl(ctx)
    fun nowPlaying(ctx: Context) = NowPlayingRepositoryImpl(ctx)
    fun diagnostics(ctx: Context) = DiagnosticsRepositoryImpl(ctx)
    fun lock(ctx: Context) = LockRepositoryImpl(ctx)
    fun useCases(ctx: Context) = UseCasesImpl(ctx)
    fun playTitle(ctx: Context) = PlayTitleUseCaseImpl(ctx)
    fun discovery(ctx: Context) = DiscoveryWorker(ctx)
}
