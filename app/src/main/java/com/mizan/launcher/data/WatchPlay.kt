package com.mizan.launcher.data

import android.app.Activity
import android.content.Context
import android.widget.Toast
import com.mizan.launcher.core.IptvLogic
import com.mizan.launcher.core.TrackerLogic
import com.mizan.launcher.core.WatchMatch
import com.mizan.launcher.ui.PlayerActivity
import java.util.Collections
import kotlin.concurrent.thread

/**
 * Watch glue (v2.3, spec §21) — the data-layer half of the Watch
 * home style: it computes + caches the tracker↔IPTV title matches
 * (core/WatchMatch owns the rules), resolves matched episodes
 * through the series-info cache, launches plays with the auto-mark
 * token attached, and owns the player's completion handler.
 *
 * Everything reads CACHED snapshots (TrackerClient + IptvClient);
 * the only network path is get_series_info through IptvClient's
 * cache, on background threads. The match cache stores ids + flags
 * only — no titles, no URLs, and (as everywhere in the IPTV code)
 * no credentials ever leave the account prefs.
 */
object WatchPlay {

    private const val K_MATCH_CACHE = "watch_match_cache"
    private const val K_MATCH_FP = "watch_match_fp"

    /** Proactive series-info warm-up cap per session (politeness to the panel). */
    private const val WARM_CAP = 6

    /** Series ids whose info fetch was already attempted this process (hit or miss). */
    private val warmedSeries: MutableSet<Long> =
        Collections.synchronizedSet(HashSet())

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences("mizan_launcher", Context.MODE_PRIVATE)

    // ------------------------------------------------------------ match inputs

    /** The tracker titles worth matching: every tracked show + every tracked movie. */
    fun trackerTitles(snap: TrackerClient.Snapshots): List<WatchMatch.TrackerTitle> {
        val out = ArrayList<WatchMatch.TrackerTitle>()
        val seen = HashSet<String>()
        fun addShow(tmdbId: Long, name: String) {
            if (tmdbId <= 0 || name.isBlank()) return
            if (seen.add("tv|$tmdbId")) {
                out.add(WatchMatch.TrackerTitle(tmdbId, "tv", name, 0))
            }
        }
        for (s in snap.tracked) addShow(s.tmdbId, s.name)
        for (i in snap.upNext) addShow(i.tmdbId, i.title)
        for (i in snap.cont) addShow(i.tmdbId, i.title)
        for (m in snap.movies) {
            if (m.tmdbId <= 0 || m.title.isBlank()) continue
            if (seen.add("movie|${m.tmdbId}")) {
                val year = m.releaseDate.take(4).toIntOrNull() ?: 0
                out.add(WatchMatch.TrackerTitle(m.tmdbId, "movie", m.title, year))
            }
        }
        return out
    }

    // ------------------------------------------------------------ matches

    /**
     * v2.4: fired (on the main thread) when a background tuple load
     * recomputes the match table — the Watch home sets this to
     * rebuild itself with fresh badges. Single slot: the home is
     * the only live consumer.
     */
    @Volatile
    var onMatchesChanged: (() -> Unit)? = null

    /** The prefs-cached match table, decoded (no store access). */
    fun cachedMatches(ctx: Context): Map<String, WatchMatch.Match> {
        val p = prefs(ctx)
        val decoded = WatchMatch.decodeMatches(
            try { p.getString(K_MATCH_CACHE, null) } catch (e: Exception) { null }
        )
        return decoded.associateBy { "${it.type}|${it.tmdbId}" }
    }

    /**
     * The current match table, keyed "type|tmdbId". v2.4: matching
     * runs over the store's lightweight TUPLES (id, title, year —
     * scanned from the .jsonl files on a worker thread by
     * IptvClient), never over materialized catalogues. When the
     * tuples are not loaded yet this returns the prefs-cached
     * table and kicks the load; the recomputation then fires
     * [onMatchesChanged]. Recomputed only when the fingerprint
     * moves (store refresh, tracker snapshot change); otherwise
     * decoded from the prefs cache.
     */
    fun matches(ctx: Context): Map<String, WatchMatch.Match> {
        val tuples = IptvClient.tuplesIfLoaded(ctx)
        if (tuples == null) {
            IptvClient.tuplesAsync(ctx) { series, vods ->
                computeMatches(ctx, series, vods)
                try { onMatchesChanged?.invoke() } catch (e: Exception) {
                    // The home's rebuild guard handles dead activities.
                }
            }
            return cachedMatches(ctx)
        }
        return computeMatches(ctx, tuples.first, tuples.second)
    }

    private fun computeMatches(
        ctx: Context,
        seriesTuples: List<com.mizan.launcher.core.IptvStore.Tuple>,
        vodTuples: List<com.mizan.launcher.core.IptvStore.Tuple>
    ): Map<String, WatchMatch.Match> {
        val snap = TrackerClient.cached(ctx)
        val titles = trackerTitles(snap)
        val keyHash = titles.map { "${it.type}:${it.tmdbId}" }.sorted().joinToString(",").hashCode()
        val fp = WatchMatch.fingerprint(
            seriesTuples.size, vodTuples.size,
            IptvClient.fetchedAt(ctx), snap.updated, keyHash
        )
        val p = prefs(ctx)
        val storedFp = try { p.getString(K_MATCH_FP, null) } catch (e: Exception) { null }
        if (storedFp == fp) {
            val decoded = WatchMatch.decodeMatches(
                try { p.getString(K_MATCH_CACHE, null) } catch (e: Exception) { null }
            )
            if (decoded.isNotEmpty() || titles.isEmpty()) {
                return decoded.associateBy { "${it.type}|${it.tmdbId}" }
            }
            // Fingerprint claims fresh but the cache is empty while
            // titles exist — fall through and recompute.
        }
        val shows = WatchMatch.matchAll(
            titles.filter { it.type == "tv" },
            seriesTuples.map { WatchMatch.Candidate(it.id, it.name, it.year) }
        )
        val movies = WatchMatch.matchAll(
            titles.filter { it.type == "movie" },
            vodTuples.map { WatchMatch.Candidate(it.id, it.name, it.year) }
        )
        val all = shows.values + movies.values
        try {
            p.edit()
                .putString(K_MATCH_CACHE, WatchMatch.encodeMatches(all))
                .putString(K_MATCH_FP, fp)
                .apply()
        } catch (e: Exception) {
            // The cache is an optimization; the computed map still returns.
        }
        return all.associateBy { "${it.type}|${it.tmdbId}" }
    }

    /** Coverage counts for the `watch:` diagnostics line. */
    fun stats(ctx: Context): WatchMatch.Stats {
        val snap = TrackerClient.cached(ctx)
        val titles = trackerTitles(snap)
        val m = matches(ctx)
        var resolved = 0
        for (item in snap.upNext) {
            val match = m["tv|${item.tmdbId}"] ?: continue
            val info = IptvClient.cachedSeriesInfo(ctx, match.iptvId) ?: continue
            if (WatchMatch.resolveEpisode(info, item.season, item.episode) != null) resolved++
        }
        return WatchMatch.Stats(
            showsMatched = titles.count { it.type == "tv" && m.containsKey("tv|${it.tmdbId}") },
            showsTotal = titles.count { it.type == "tv" },
            moviesMatched = titles.count { it.type == "movie" && m.containsKey("movie|${it.tmdbId}") },
            moviesTotal = titles.count { it.type == "movie" },
            episodesResolved = resolved
        )
    }

    /**
     * The VOD entry a movie match points at, resolved by scanning
     * the store file on a worker thread (v2.4: no materialized
     * catalogue exists to look it up in). The callback fires on
     * the main thread; null = the catalogue moved on.
     */
    fun resolveVod(ctx: Context, match: WatchMatch.Match, onResult: (IptvLogic.VodItem?) -> Unit) {
        val appCtx = ctx.applicationContext
        thread(name = "mizan-watch-resolve") {
            val vod = IptvClient.findVodBlocking(appCtx, match.iptvId)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try { onResult(vod) } catch (e: Exception) {
                    // A dead caller's update must never kill the poster.
                }
            }
        }
    }

    /** The series entry a show match points at — [resolveVod]'s twin. */
    fun resolveSeries(ctx: Context, match: WatchMatch.Match, onResult: (IptvLogic.SeriesItem?) -> Unit) {
        val appCtx = ctx.applicationContext
        thread(name = "mizan-watch-resolve") {
            val series = IptvClient.findSeriesBlocking(appCtx, match.iptvId)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try { onResult(series) } catch (e: Exception) {
                    // A dead caller's update must never kill the poster.
                }
            }
        }
    }

    // ------------------------------------------------------------ warm-up

    /**
     * Proactively fetch series info (through the cache) for the
     * matched shows in [upNext] that lack it — capped per session —
     * so their Play buttons resolve without a tap-time wait.
     * [onDone] fires on the caller's thread once, only when at least
     * one fetch SUCCEEDED (a rebuild would change something).
     */
    fun warmUp(
        ctx: Context,
        upNext: List<TrackerLogic.UpNextItem>,
        matches: Map<String, WatchMatch.Match>,
        onDone: () -> Unit
    ) {
        val appCtx = ctx.applicationContext
        val wanted = ArrayList<Long>()
        for (item in upNext) {
            val match = matches["tv|${item.tmdbId}"] ?: continue
            if (match.iptvId in warmedSeries) continue
            if (IptvClient.cachedSeriesInfo(appCtx, match.iptvId) != null) continue
            warmedSeries.add(match.iptvId)
            wanted.add(match.iptvId)
            if (wanted.size >= WARM_CAP) break
        }
        if (wanted.isEmpty()) return
        thread(name = "mizan-watch-warm") {
            var any = false
            for (id in wanted) {
                val latch = java.util.concurrent.CountDownLatch(1)
                val got = java.util.concurrent.atomic.AtomicBoolean(false)
                IptvClient.fetchSeriesInfoAsync(appCtx, id) { info ->
                    got.set(info != null)
                    latch.countDown()
                }
                try {
                    latch.await(45, java.util.concurrent.TimeUnit.SECONDS)
                } catch (e: Exception) {
                    // A stuck panel must not hang the warm-up thread forever.
                }
                if (got.get()) any = true
            }
            if (any) {
                try { onDone() } catch (e: Exception) {
                    // The caller's rebuild guard handles dead activities.
                }
            }
        }
    }

    // ------------------------------------------------------------ playback

    /** The auto-mark token for one Watch play (null = plain play, never auto-marks). */
    data class Token(val tmdbId: Long, val type: String, val season: Int, val episode: Int)

    /**
     * The player's completion handler: a token-carrying play ended
     * (or crossed ≥90%) — queue the mark through the SAME funnel a
     * manual Mark watched uses (TrackerActions: tracker queue +
     * generalized overlay + marked-set), so home, Library and
     * details all render it at once.
     */
    val completionHandler: (Activity, PlayItem) -> Unit = { activity, item ->
        try {
            if (item.hasMarkToken) {
                val prefs = Prefs(activity)
                if (item.markType == "movie") {
                    TrackerActions.toggleMovie(activity, prefs, item.markTmdbId, true)
                } else {
                    TrackerActions.toggleEpisode(
                        activity, prefs, item.markTmdbId,
                        item.markSeason, item.markEpisode, true
                    )
                }
                Toast.makeText(
                    activity, "✓ Marked watched — syncs when the tracker opens",
                    Toast.LENGTH_SHORT
                ).show()
            }
        } catch (e: Exception) {
            Prefs.noteError(activity, "watchAutoMark", e)
        }
    }

    private fun launch(activity: Activity, item: PlayItem) {
        PlayQueue.set(emptyList(), 0)
        PlayQueue.completionHandler = completionHandler
        activity.startActivity(PlayerActivity.intent(activity, item))
    }

    /** Play a matched movie from its VOD entry. */
    fun playMovie(activity: Activity, vod: IptvLogic.VodItem, token: Token?) {
        launch(
            activity,
            PlayItem(
                kind = IptvLogic.KIND_VOD, source = IptvLogic.SRC_XTREAM,
                id = vod.id.toString(), title = vod.name, icon = vod.icon,
                meta = vod.categoryName, ext = vod.ext, catKey = vod.catKey,
                markTmdbId = token?.tmdbId ?: 0L,
                markType = token?.type ?: "",
                markSeason = token?.season ?: 0,
                markEpisode = token?.episode ?: 0
            )
        )
    }

    /**
     * Play one tracker episode of a matched show: resolve the
     * episode id + extension from the series-info cache (fetching
     * through it when cold), then launch. Any failure — panel
     * unreachable, season/episode absent — calls [onFail] so the
     * caller can fall back to details / Open Tracker with a toast;
     * never a dead end.
     */
    fun playEpisode(
        activity: Activity,
        series: IptvLogic.SeriesItem,
        trackerTmdbId: Long,
        season: Int,
        episode: Int,
        trackerTitle: String,
        posterPath: String,
        withToken: Boolean,
        onFail: () -> Unit
    ) {
        val icon = if (posterPath.isNotBlank()) {
            TmdbImages.url("w342", posterPath)
        } else {
            series.cover
        }
        fun start(info: IptvLogic.SeriesInfo?) {
            if (activity.isFinishing || activity.isDestroyed) return
            val ep = info?.let { WatchMatch.resolveEpisode(it, season, episode) }
            if (ep == null) {
                onFail()
                return
            }
            launch(
                activity,
                PlayItem(
                    kind = IptvLogic.KIND_EPISODE, source = IptvLogic.SRC_XTREAM,
                    id = ep.id,
                    title = "$trackerTitle · S$season E$episode",
                    icon = icon, meta = ep.title, ext = ep.ext,
                    catKey = series.catKey, seriesId = series.id,
                    markTmdbId = if (withToken) trackerTmdbId else 0L,
                    markType = if (withToken) "tv" else "",
                    markSeason = if (withToken) season else 0,
                    markEpisode = if (withToken) episode else 0
                )
            )
        }
        val cached = IptvClient.cachedSeriesInfo(activity, series.id)
        if (cached != null) {
            start(cached)
        } else {
            Toast.makeText(activity, "Finding the episode on your IPTV…", Toast.LENGTH_SHORT).show()
            IptvClient.fetchSeriesInfoAsync(activity, series.id) { info -> start(info) }
        }
    }
}
