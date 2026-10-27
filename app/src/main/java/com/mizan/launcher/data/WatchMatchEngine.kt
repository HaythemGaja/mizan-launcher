package com.mizan.launcher.data

import android.content.Context
import com.mizan.launcher.core.IptvLogic
import com.mizan.launcher.core.WatchMatch
import com.mizan.launcher.domain.HomeCard
import com.mizan.launcher.domain.MediaKind
import com.mizan.launcher.domain.PlayItem
import com.mizan.launcher.domain.PlayKind
import com.mizan.launcher.domain.PlayTitleUseCase

/**
 * Watch matching (MAP §5): tracker title ↔ IPTV catalogue via
 * core/WatchMatch squash normalization; exact tier, then contains
 * tier over once-keyed candidates ('Preacher' matches 'Reacher'
 * by design — crude, pinned); year breaks same-name ties and
 * deprioritizes movies; ambiguity → first candidate flagged
 * `possible`; missing season/episode → unresolved (fail-open).
 * Runs on a worker thread only (caller-owned); never the main
 * thread. Match cache fingerprint + codec stores ids + flags,
 * never titles.
 */
class WatchMatchEngine(ctx: Context) {
    private val context = ctx.applicationContext
    private val prefs = PrefsStore(ctx)
    private val iptvDisk = IptvStoreDisk(ctx)
    private val tracker = TrackerRepositoryImpl(ctx)
    @Volatile private var cachedMatches: Map<Long, WatchMatch.Match> = emptyMap()
    @Volatile private var cachedFingerprint: String = ""

    data class MatchInfo(val iptvId: Long, val type: String, val exact: Boolean, val possible: Boolean)

    fun computeMatches(): Map<Long, WatchMatch.Match> {
        val series = iptvDisk.series()
        val vods = iptvDisk.vod()
        val titles = ArrayList<WatchMatch.TrackerTitle>()
        for (s in tracker.getTrackedShows()) {
            titles.add(WatchMatch.TrackerTitle(s.tmdbId, "tv", s.title, s.year ?: 0))
        }
        for (m in tracker.getTrackedMovies()) {
            titles.add(WatchMatch.TrackerTitle(m.tmdbId, "movie", m.title, m.year ?: 0))
        }
        // Trending titles also join the visible-first match set (v3.9).
        val discovery = DiscoveryWorker.loadCached(context)
        for (t in discovery.trendingTv) titles.add(WatchMatch.TrackerTitle(t.id, "tv", t.title, 0))
        for (t in discovery.trendingMovies) titles.add(WatchMatch.TrackerTitle(t.id, "movie", t.title, 0))
        val seriesCands = series.map { WatchMatch.Candidate(it.id, it.name, it.year) }
        val vodCands = vods.map { WatchMatch.Candidate(it.id, it.name, it.year) }
        val out = HashMap<Long, WatchMatch.Match>()
        // matchAll is per-candidate-set; run shows vs series, movies vs VOD.
        val showMatches = WatchMatch.matchAll(titles.filter { it.type == "tv" }, seriesCands)
        val movieMatches = WatchMatch.matchAll(titles.filter { it.type == "movie" }, vodCands)
        out.putAll(showMatches); out.putAll(movieMatches)
        val (lc, vc, _) = iptvDisk.counts()
        val fp = WatchMatch.fingerprint(series.size, vods.size, iptvDisk.lastUpdateMs(),
            0L, titles.map { it.tmdbId }.hashCode())
        cachedMatches = out; cachedFingerprint = fp
        return out
    }
    fun matches(): Map<Long, WatchMatch.Match> = cachedMatches
    fun matchFor(tmdbId: Long): WatchMatch.Match? = cachedMatches[tmdbId]

    /** Resolve a matched show episode to a stream URL, or null. */
    fun resolveEpisode(seriesIptvId: Long, season: Int, episode: Int): PlayItem? {
        val json = iptvDisk.seriesInfo(seriesIptvId) ?: return null
        val info = IptvLogic.parseSeriesInfo(json, seriesIptvId) ?: return null
        val ep = WatchMatch.resolveEpisode(info, season, episode) ?: return null
        return PlayItem(PlayKind.SERIES_EPISODE, ep.id, ep.title, ep.title,
            IptvLogic.episodeUrl(prefs.iptvAccount(), ep.id, ep.ext.ifBlank { "mp4" }),
            null, null, null, season, episode)
    }
    fun resolveMovie(vodIptvId: Long): PlayItem? {
        val v = iptvDisk.vod().firstOrNull { it.id == vodIptvId } ?: return null
        return PlayItem(PlayKind.VOD, v.id.toString(), v.name, null,
            IptvLogic.vodUrl(prefs.iptvAccount(), v.id, v.ext.ifBlank { "mp4" }),
            null, null, null, null, null)
    }
}

/** PlayTitle use case (domain): matched card plays via IPTV;
 *  unmatched opens Details (null). Matched show without a
 *  library twin plays from S1 E1 with no library mark token. */
class PlayTitleUseCaseImpl(ctx: Context) : PlayTitleUseCase {
    private val engine = WatchMatchEngine(ctx)
    private val tracker = TrackerRepositoryImpl(ctx)
    private val prefs = PrefsStore(ctx)

    private fun tokenFor(card: HomeCard): String? {
        // Library twin = tracked title with the same id; token is
        // the tracker mark key the player completion handler uses.
        val tracked = if (card.kind == MediaKind.MOVIE) tracker.getTrackedMovies().any { it.tmdbId == card.tmdbId }
            else tracker.getTrackedShows().any { it.tmdbId == card.tmdbId }
        if (!tracked) return null
        return if (card.kind == MediaKind.MOVIE) "movie|${card.tmdbId}"
        else "tv|${card.tmdbId}|${card.nextSeason ?: 0}|${card.nextEpisode ?: 0}"
    }

    override fun playCard(card: HomeCard): PlayItem? {
        val m = engine.matchFor(card.tmdbId) ?: return null // unmatched -> Details
        return when (card.kind) {
            MediaKind.MOVIE -> engine.resolveMovie(m.iptvId)?.copy(libraryMarkToken = tokenFor(card), tmdbId = card.tmdbId)
            MediaKind.SHOW -> {
                val s = card.nextSeason ?: return playFromFirstEpisode(card)
                val e = card.nextEpisode ?: return playFromFirstEpisode(card)
                engine.resolveEpisode(m.iptvId, s, e)
                    ?.copy(libraryMarkToken = tokenFor(card), tmdbId = card.tmdbId)
                    ?: playFromFirstEpisode(card)?.let { null } // S1E1 fallback handled below only for no-twin
                    ?: run {
                        // Matched but episode unresolved: fail open to Details.
                        null
                    }
            }
        }
    }

    override fun playHeroPrimary(card: HomeCard): PlayItem? = playCard(card)

    // Phase 2D (UI gap (c)): matched flag for the Details ▶ glyph.
    // Recomputes the match set on first use (worker thread only);
    // later calls reuse the cache until the cold chain recomputes.
    override fun isMatched(tmdbId: Long): Boolean {
        return try {
            (engine.matchFor(tmdbId) ?: engine.computeMatches()[tmdbId]) != null
        } catch (e: Throwable) { false }
    }

    // Phase 2D (UI gap (c)): play one specific episode. The token
    // is attached only for tracked shows; untracked matched shows
    // play with a null token (no library mark), like playCard.
    override fun playEpisode(showTmdbId: Long, season: Int, episode: Int): PlayItem? {
        return try {
            val m = engine.matchFor(showTmdbId) ?: engine.computeMatches()[showTmdbId] ?: return null
            if (m.type != "tv") return null
            val card = HomeCard(showTmdbId, MediaKind.SHOW, "", nextSeason = season, nextEpisode = episode)
            engine.resolveEpisode(m.iptvId, season, episode)
                ?.copy(libraryMarkToken = tokenFor(card), tmdbId = showTmdbId)
        } catch (e: Throwable) { null }
    }

    override fun playFromFirstEpisode(card: HomeCard): PlayItem? {
        val m = engine.matchFor(card.tmdbId) ?: return null
        if (card.kind == MediaKind.MOVIE) return engine.resolveMovie(m.iptvId)?.copy(tmdbId = card.tmdbId)
        // No library twin: S1 E1, NO library mark token (v3.9).
        // If S1 E1 is not on IPTV, fail open to Details (null).
        return engine.resolveEpisode(m.iptvId, 1, 1)?.copy(libraryMarkToken = null, tmdbId = card.tmdbId)
    }
}
