package com.mizan.launcher.data

import android.app.Activity
import android.content.Context
import android.widget.Toast
import com.mizan.launcher.core.IptvLogic
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
     * Fired (on the main thread) when a worker-thread recompute
     * lands a NEW match table — the Watch home sets this to
     * rebuild itself with fresh badges. Single slot: the home is
     * the only live consumer.
     */
    @Volatile
    var onMatchesChanged: (() -> Unit)? = null

    /**
     * The prefs-cached match table, decoded (no store access, no compute).
     * v3.2: memoized by the raw string — prepareWatchData runs on
     * every home rebuild, and the old code re-decoded the table
     * JSON on the main thread each time. A worker recompute landing
     * a new table changes the raw string and invalidates the memo.
     */
    @Volatile
    private var matchesMemoRaw: String? = null

    @Volatile
    private var matchesMemo: Map<String, WatchMatch.Match>? = null

    fun cachedMatches(ctx: Context): Map<String, WatchMatch.Match> {
        val p = prefs(ctx)
        val raw = try { PrefsSafe.strOrNull(p, K_MATCH_CACHE) } catch (e: Exception) { null }
        val memo = matchesMemo
        if (memo != null && matchesMemoRaw == raw) return memo
        val decoded = WatchMatch.decodeMatches(raw)
        val map = decoded.associateBy { "${it.type}|${it.tmdbId}" }
        matchesMemoRaw = raw
        matchesMemo = map
        return map
    }

    /**
     * The current match table, keyed "type|tmdbId".
     *
     * v2.5: this NEVER computes on the caller's thread. It returns
     * the prefs-cached table immediately and kicks a single-flight
     * worker recompute ([kickRecompute]); when the fingerprint
     * has moved and the worker lands a new table, the table is
     * persisted and [onMatchesChanged] fires on the main thread.
     *
     * Why: the v2.4 shape computed the match HERE — synchronously
     * when the store tuples were already loaded, and inside
     * IptvClient.tuplesAsync's main-thread Handler callback when
     * they were not. The 2026-10-22 22:50 dropbox ANR caught the
     * main thread inside WatchMatch.normalizeKey under
     * computeMatches under that Handler delivery: matching ran
     * on the main thread end-to-end, O(tracked × catalogue) with
     * a fresh normalization per comparison, 545 MB allocated in
     * the stall — and because the process died mid-compute, the
     * badges/play resolutions it was computing never landed.
     * Matching now runs worker-side over a once-built key index
     * (core/WatchMatch), and only the FINISHED table ever crosses
     * to the main thread.
     */
    fun matches(ctx: Context): Map<String, WatchMatch.Match> {
        kickRecompute(ctx)
        return cachedMatches(ctx)
    }

    @Volatile
    private var recomputeInFlight = false

    /** Single-flight acquire shared by the thread kick and the chain's synchronous run. */
    private fun tryAcquireRecompute(): Boolean {
        synchronized(this) {
            if (recomputeInFlight) return false
            recomputeInFlight = true
            return true
        }
    }

    private fun releaseRecompute() {
        synchronized(this) { recomputeInFlight = false }
    }

    private fun announceIfChanged(changed: Boolean) {
        if (!changed) return
        DiagLog.note("watch matches recomputed")
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try { onMatchesChanged?.invoke() } catch (e: Exception) {
                // The home's rebuild guard handles dead activities.
            }
        }
    }

    // ------------------------------------------------------------ visible-first recompute (v3.6, ask 53)

    /**
     * v3.6: visible-first matching for the OPEN path.
     * [visible] is hero pool + first window of each row,
     * in display order. Only those titles are matched,
     * over the streamed catalogue-key scan (no key list
     * materialised), and the results MERGE into the
     * cached table — a partial recompute never wipes
     * below-fold matches the deferred full pass already
     * landed. The full fingerprint is untouched here, so
     * the deferred pass still knows whether it is due.
     * Worker threads only; the caller rebuilds on
     * [onMatchesChanged] when the table changed.
     */
    fun kickVisibleRecompute(ctx: Context, visible: List<WatchMatch.TrackerTitle>) {
        if (visible.isEmpty()) return
        val appCtx = ctx.applicationContext
        thread(name = "mizan-watch-visible") {
            try {
                val tv = visible.filter { it.type == "tv" }
                val movies = visible.filter { it.type == "movie" }
                val found = LinkedHashMap<Long, WatchMatch.Match>()
                if (tv.isNotEmpty()) {
                    found.putAll(
                        WatchMatch.matchStreamed(tv) { IptvClient.keyFactory(appCtx, IptvLogic.KIND_SERIES)() }
                    )
                }
                if (movies.isNotEmpty()) {
                    found.putAll(
                        WatchMatch.matchStreamed(movies) { IptvClient.keyFactory(appCtx, IptvLogic.KIND_VOD)() }
                    )
                }
                if (found.isNotEmpty()) {
                    val merged = HashMap<String, WatchMatch.Match>()
                    merged.putAll(cachedMatches(appCtx))
                    for (m in found.values) merged["${m.type}|${m.tmdbId}"] = m
                    val before = cachedMatches(appCtx)
                    if (merged != before) {
                        try {
                            prefs(appCtx).edit()
                                .putString(K_MATCH_CACHE, WatchMatch.encodeMatches(merged.values))
                                .apply()
                        } catch (e: Exception) {
                            // The cache is an optimization; a failed write just retries next time.
                        }
                        matchesMemo = null
                        matchesMemoRaw = null
                        announceIfChanged(true)
                    }
                }
            } catch (e: Throwable) {
                try { Prefs.noteError(appCtx, "watchVisibleMatch", e) } catch (_: Exception) { }
            }
        }
    }

    /** True when any visible title still lacks a cached match (the open-path visible kick's guard). */
    fun visibleNeedsMatch(ctx: Context, visible: List<WatchMatch.TrackerTitle>): Boolean {
        if (visible.isEmpty()) return false
        val m = cachedMatches(ctx)
        return visible.any { m["${it.type}|${it.tmdbId}"] == null }
    }

    /** Kick the single-flight worker recompute (no-op while one is running). */
    fun kickRecompute(ctx: Context) {
        if (!tryAcquireRecompute()) return
        val appCtx = ctx.applicationContext
        thread(name = "mizan-watch-match") {
            var changed = false
            try {
                changed = recomputeOnWorker(appCtx)
            } catch (e: Throwable) {
                // Throwable since v3.0: an Error here (a hostile
                // catalogue during matching) degrades to the stale
                // table and is recorded — never a silent thread death.
                Prefs.noteError(appCtx, "watchMatch", e)
            } finally {
                releaseRecompute()
            }
            announceIfChanged(changed)
        }
    }

    /**
     * v3.5 (spec §35): run the recompute SYNCHRONOUSLY on the
     * caller's worker thread — the cold chain's MATCH step calls
     * this on the chain thread so the match never runs beside
     * another heavy step. Same single-flight flag as
     * [kickRecompute]: false when a recompute is already running
     * (the in-flight run covers this one). Never call on the
     * main thread: it may stream both catalogue files.
     */
    fun recomputeSyncNow(ctx: Context): Boolean {
        if (!tryAcquireRecompute()) return false
        val appCtx = ctx.applicationContext
        var changed = false
        try {
            changed = recomputeOnWorker(appCtx)
        } catch (e: Throwable) {
            Prefs.noteError(appCtx, "watchMatch", e)
        } finally {
            releaseRecompute()
        }
        announceIfChanged(changed)
        return true
    }

    /**
     * Worker-thread recompute.
     *
     * v3.5 (spec §35): FINGERPRINT FIRST. The old shape streamed
     * both catalogue files into memoized tuples BEFORE checking
     * whether anything moved — every cold start paid the full
     * scan even when the table was already fresh. Now the
     * fingerprint comes from the small store index (counts +
     * fetchedAt) plus the tracker stamps, and the catalogue
     * streams ONLY when the fingerprint moved. The stream itself
     * is key-only (data/IptvClient.keyedCatalogue): names are
     * dropped at key time, the key lists are worker-local and
     * released when the run ends — nothing is memoized any more.
     * Persists table + fingerprint. Returns true when a NEW
     * table landed.
     */
    private fun recomputeOnWorker(appCtx: Context): Boolean {
        val snap = TrackerClient.cached(appCtx)
        val titles = trackerTitles(snap)
        val keyHash = titles.map { "${it.type}:${it.tmdbId}" }.sorted().joinToString(",").hashCode()
        val counts = IptvClient.catalogueCounts(appCtx)
        val fp = WatchMatch.fingerprint(
            counts?.first ?: 0, counts?.second ?: 0,
            counts?.third ?: 0L, snap.updated, keyHash
        )
        val p = prefs(appCtx)
        val storedFp = try { PrefsSafe.strOrNull(p, K_MATCH_FP) } catch (e: Exception) { null }
        if (storedFp == fp) {
            val decoded = WatchMatch.decodeMatches(
                try { PrefsSafe.strOrNull(p, K_MATCH_CACHE) } catch (e: Exception) { null }
            )
            if (decoded.isNotEmpty() || titles.isEmpty()) return false
            // Fingerprint claims fresh but the cache is empty while
            // titles exist — fall through and recompute.
        }
        // Fingerprint moved (or the cache is empty): stream key-only, match, release.
        val keyed = IptvClient.keyedCatalogue(appCtx) ?: Pair(emptyList(), emptyList())
        val shows = WatchMatch.matchAllKeyed(
            titles.filter { it.type == "tv" }, keyed.first
        )
        val movies = WatchMatch.matchAllKeyed(
            titles.filter { it.type == "movie" }, keyed.second
        )
        val all = shows.values + movies.values
        try {
            p.edit()
                .putString(K_MATCH_CACHE, WatchMatch.encodeMatches(all))
                .putString(K_MATCH_FP, fp)
                .apply()
        } catch (e: Exception) {
            // The cache is an optimization; a failed write just
            // means the next kick recomputes again.
        }
        return true
    }

    /**
     * Coverage counts for the `watch:` diagnostics line. v2.5:
     * reads the CACHED table only (kicking a background recompute
     * for next time) — diagnostics composition must never trigger
     * the match it merely reports on.
     */
    fun stats(ctx: Context): WatchMatch.Stats {
        kickRecompute(ctx)
        val snap = TrackerClient.cached(ctx)
        val titles = trackerTitles(snap)
        val m = cachedMatches(ctx)
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
     * matched series that are ON SCREEN — the hero pool first,
     * then the visible rows, in display order ([seriesIds] is
     * already in that order, duplicates tolerated).
     *
     * v2.9 (ask F.25): resolution is driven by what is displayed,
     * NOT by a warm-up quota. The pre-v2.9 warm-up stopped at 6
     * series per session and only looked at Up Next, so a matched
     * hero show past the cap never resolved — and because the
     * hero only offered Play for already-resolved shows, it
     * rendered with no Play button at all. There is no cap now:
     * every displayed matched series is warmed, sequentially on
     * one worker thread (one panel request at a time IS the
     * politeness), each at most once per process (warmedSeries),
     * and cache hits are skipped. [onDone] fires on the caller's
     * thread once, only when at least one fetch SUCCEEDED (a
     * rebuild would change something).
     */
    fun warmUpDisplayed(
        ctx: Context,
        seriesIds: List<Long>,
        onDone: () -> Unit
    ) {
        val appCtx = ctx.applicationContext
        val wanted = ArrayList<Long>()
        for (id in seriesIds) {
            if (id <= 0L) continue
            if (id in warmedSeries) continue
            if (IptvClient.cachedSeriesInfo(appCtx, id) != null) continue
            warmedSeries.add(id)
            if (id !in wanted) wanted.add(id)
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
     * (or crossed ≥90%) — mark through the SAME funnel a
     * manual Mark watched uses (TrackerActions: local store
     * write + generalized overlay + marked-set), so home,
     * Library and details all render it at once.
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
                    activity, "✓ Marked watched",
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

    /**
     * The PlayItem a matched movie plays as (shared by [playMovie]
     * and ui/PlayIn's "Play in…" — one construction, two exits).
     */
    fun moviePlayItem(vod: IptvLogic.VodItem, token: Token?): PlayItem =
        PlayItem(
            kind = IptvLogic.KIND_VOD, source = IptvLogic.SRC_XTREAM,
            id = vod.id.toString(), title = vod.name, icon = vod.icon,
            meta = vod.categoryName, ext = vod.ext, catKey = vod.catKey,
            markTmdbId = token?.tmdbId ?: 0L,
            markType = token?.type ?: "",
            markSeason = token?.season ?: 0,
            markEpisode = token?.episode ?: 0
        )

    /** Play a matched movie from its VOD entry. */
    fun playMovie(activity: Activity, vod: IptvLogic.VodItem, token: Token?) {
        launch(activity, moviePlayItem(vod, token))
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
