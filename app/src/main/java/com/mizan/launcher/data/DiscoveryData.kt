package com.mizan.launcher.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.mizan.launcher.core.Discovery
import com.mizan.launcher.core.LibraryLogic
import com.mizan.launcher.core.TrackerLogic
import com.mizan.launcher.core.WatchMatch
import java.io.File
import kotlin.concurrent.thread

/**
 * Discovery data (v2.7) — the data-layer half of core/Discovery:
 * Coming Soon, Trending and (with a launcher TMDB key) For You,
 * computed IN THE LAUNCHER from TMDB, with the tracker snapshots
 * as the standing fallback.
 *
 * Discipline (the v2.4/v2.5 rules, applied to a new surface):
 * - NOTHING here runs on the main thread. [snapshot] returns the
 *   in-memory state as-is and kicks the single-flight worker; the
 *   worker does every fetch, parse and file read/write, then
 *   posts ONE [onChanged] to the main thread when the state
 *   actually moved. First paint never waits on TMDB.
 * - TMDB payloads are cached as files under filesDir/discovery
 *   (the giant-catalogue lesson: never in SharedPreferences),
 *   with per-file fetch stamps in one small prefs map. Trending
 *   TTL 12 h, details TTL 24 h, computed For You 24 h
 *   (core/Discovery) — stale-while-revalidate throughout: the
 *   stale set renders while the worker refreshes behind it.
 * - Every TMDB read goes through the v2.6 funnel
 *   (TrackerClient.tmdbGetBlocking: tracker proxy → launcher-key
 *   direct), so the key hygiene and error tokens of §26 apply
 *   unchanged. The For You COMPUTE additionally requires the
 *   launcher key (its discover step is direct-only — the proxy
 *   allowlist has no discover/ prefix); without one, the
 *   tracker's /foryou snapshot serves, exactly as in v2.6.
 */
object DiscoveryData {

    /** A trending title matched into the IPTV catalogue (IPTV home row). */
    data class TrendPlay(val item: Discovery.TrendItem, val iptvId: Long)

    /** The home-facing state: bounded lists only, safe to render. */
    data class State(
        val trendingTv: List<Discovery.TrendItem>,
        val trendingMovies: List<Discovery.TrendItem>,
        val comingSoon: List<Discovery.SoonItem>,
        /** Launcher-computed For You picks; [forYouComputed] false → ignore. */
        val forYou: List<TrackerLogic.ForYouPick>,
        val forYouComputed: Boolean,
        val trendingIptv: List<TrendPlay>
    ) {
        companion object {
            val EMPTY = State(
                emptyList(), emptyList(), emptyList(),
                emptyList(), false, emptyList()
            )
        }
    }

    @Volatile
    private var state: State = State.EMPTY

    @Volatile
    private var loaded = false

    /**
     * v3.1 (ask 39): one-shot Retry bypass — a per-section Retry
     * sets this, and the next worker run ignores every TTL once
     * (then clears it), so Retry always re-fetches instead of
     * re-reading a stale-but-"fresh" stamp.
     */
    @Volatile
    private var forceNext = false

    @Volatile
    private var inFlight = false

    /** The trending/all + store-stamp the IPTV matches were computed against. */
    @Volatile
    private var iptvMatchStamp: String = ""

    /**
     * Fired (on the main thread) when the worker lands a CHANGED
     * state — the homes set this to rebuild once. Single slot.
     */
    @Volatile
    var onChanged: (() -> Unit)? = null

    /**
     * The current state.
     * v3.2 (ask 43): kicks the worker ONLY until the first run
     * has hydrated the disk caches into memory — after that the
     * home build is purely passive. Network fetching inside the
     * worker is gated separately (see runWorker): TTL expiry is
     * no longer a fetch trigger; only an explicit refresh/Retry
     * (force) or a brand-new empty cache (seeding) fetches.
     * Never blocks.
     */
    /**
     * v3.5 (spec §35): the current state WITHOUT kicking. The
     * old shape kicked the worker on first read — and the home
     * build reads this on every build, so the discovery worker
     * (trend + details parses + shaping) used to start on the
     * open path beside the tracker parse and the match. Now the
     * kick belongs to the cold chain (post-survival) and to
     * explicit requests (preload callers, section Retry via
     * refreshNow); a home build that finds nothing loaded shows
     * the section LOADING placeholders until the chain lands.
     */
    fun snapshot(ctx: Context): State {
        return state
    }

    /** The current state WITHOUT kicking (diagnostics-adjacent reads). */
    fun peek(): State = state

    /**
     * Start the disk hydration worker (v3.5: the cold chain's
     * DISCOVERY step calls this post-survival — App.onCreate no
     * longer does, since the v3.4 process-start kick stacked the
     * discovery parse on top of the tracker parse and the match).
     * Same single-flight kick as before; network fetching inside
     * the worker is still gated exactly as in runWorker.
     */
    fun preload(ctx: Context) {
        if (!loaded) kick(ctx)
    }

    /**
     * v3.5 (spec §35): run the worker SYNCHRONOUSLY on the
     * caller's worker thread — the cold chain's DISCOVERY step
     * calls this on the chain thread so discovery never runs
     * beside the tracker parse or the match. Same single-flight
     * flag as [kick]: false when a run is already in flight.
     * Never call on the main thread: it parses cache files.
     */
    fun runNow(ctx: Context): Boolean {
        synchronized(this) {
            if (inFlight) return false
            inFlight = true
        }
        val appCtx = ctx.applicationContext
        var changed = false
        try {
            changed = runWorker(appCtx)
        } catch (e: Throwable) {
            Prefs.noteError(appCtx, "discovery", e)
        } finally {
            synchronized(this) { inFlight = false }
        }
        if (changed) {
            DiagLog.note("discovery refreshed")
            Handler(Looper.getMainLooper()).post {
                try { onChanged?.invoke() } catch (e: Exception) {
                    // The home's rebuild guard handles dead activities.
                }
            }
        }
        return true
    }

    /**
     * v3.1 (hero schedule): the last worker run's per-show TMDB
     * facts (next_episode_to_air date + season/episode), kept in
     * memory for the hero's date fill. Worker-written, freely
     * readable — the hero build never waits on a fetch for it.
     */
    @Volatile
    private var lastTvFacts: Map<Long, com.mizan.launcher.core.Discovery.TvFacts> = emptyMap()

    /** Last-known TMDB next-episode facts by show TMDB id (memory only). */
    fun tvFacts(): Map<Long, com.mizan.launcher.core.Discovery.TvFacts> = lastTvFacts

    /** Whether the worker's first run completed (drives section LOADING placeholders). */
    fun isLoaded(): Boolean = loaded

    /**
     * v3.1 (ask 39): per-section Retry for Coming Soon / Trending:
     * force one TTL-ignoring worker run. Single-flight via kick().
     */
    fun refreshNow(ctx: Context) {
        forceNext = true
        kick(ctx)
    }

    private fun kick(ctx: Context) {
        synchronized(this) {
            if (inFlight) return
            inFlight = true
        }
        val appCtx = ctx.applicationContext
        thread(name = "mizan-discovery") {
            var changed = false
            try {
                changed = runWorker(appCtx)
            } catch (e: Throwable) {
                // Throwable since v3.0 (same rule as the match worker).
                Prefs.noteError(appCtx, "discovery", e)
            } finally {
                synchronized(this) { inFlight = false }
            }
            if (changed) {
                DiagLog.note("discovery refreshed")
                Handler(Looper.getMainLooper()).post {
                    try { onChanged?.invoke() } catch (e: Exception) {
                        // The home's rebuild guard handles dead activities.
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------ cache files

    private const val DIR = "discovery"
    private const val F_TREND_TV = "trend_tv.json"
    private const val F_TREND_MOVIE = "trend_movie.json"
    private const val F_TREND_ALL = "trend_all.json"
    private const val F_FORYOU = "foryou.json"

    /** Prefs: per-file fetch stamps, one "name=epochMs" per line. */
    private const val K_STAMPS = "discovery_stamps"
    private const val K_SOURCE = "discovery_source"
    private const val K_SOON_N = "discovery_soon_n"
    private const val K_TREND_N = "discovery_trend_n"
    private const val K_FORYOU_N = "discovery_foryou_n"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences("mizan_launcher", Context.MODE_PRIVATE)

    private fun dir(ctx: Context): File {
        val d = File(ctx.filesDir, DIR)
        if (!d.isDirectory) d.mkdirs()
        return d
    }

    private fun readFile(ctx: Context, name: String): String? {
        return try {
            val f = File(dir(ctx), name)
            if (!f.isFile || f.length() > 8L * 1024 * 1024) null else f.readText()
        } catch (e: Exception) {
            null
        }
    }

    private fun writeFile(ctx: Context, name: String, content: String) {
        try {
            val d = dir(ctx)
            val tmp = File(d, "$name.tmp")
            tmp.writeText(content)
            if (!tmp.renameTo(File(d, name))) {
                tmp.copyTo(File(d, name), overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            // A failed cache write just means the next run refetches.
        }
    }

    private fun stamps(ctx: Context): MutableMap<String, Long> {
        val raw = try {
            PrefsSafe.strOrNull(prefs(ctx), K_STAMPS)
        } catch (e: Exception) {
            null
        } ?: return LinkedHashMap()
        val out = LinkedHashMap<String, Long>()
        for (line in raw.split('\n')) {
            val i = line.lastIndexOf('=')
            if (i <= 0) continue
            val ts = line.substring(i + 1).toLongOrNull() ?: continue
            out[line.substring(0, i)] = ts
        }
        return out
    }

    private fun saveStamps(ctx: Context, stamps: Map<String, Long>) {
        try {
            prefs(ctx).edit().putString(
                K_STAMPS,
                stamps.entries.joinToString("\n") { "${it.key}=${it.value}" }
            ).apply()
        } catch (e: Exception) {
            // Bookkeeping only.
        }
    }

    private fun detailsName(type: String, id: Long): String =
        "d_${if (type == "movie") "movie" else "tv"}_$id.json"

    /** Delete details cache files whose stamp aged past [maxAgeMs] (bounds the cache). */
    private fun pruneDetails(ctx: Context, stamps: MutableMap<String, Long>, now: Long, maxAgeMs: Long) {
        var dirty = false
        val it = stamps.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.key.startsWith("d_") && now - e.value > maxAgeMs) {
                try {
                    File(dir(ctx), e.key).delete()
                } catch (ex: Exception) {
                    // Pruning is housekeeping.
                }
                it.remove()
                dirty = true
            }
        }
        if (dirty) saveStamps(ctx, stamps)
    }

    // ------------------------------------------------------------ worker

    /**
     * The whole refresh, on the worker thread: load disk caches
     * into memory (first run), refetch whatever is stale, recompute
     * the shaped sets, persist diagnostics counts. Returns true
     * when the rendered state changed.
     */
    private fun runWorker(appCtx: Context): Boolean {
        val now = System.currentTimeMillis()
        val stamps = stamps(appCtx)
        var dirtyStamps = false

        if (!loaded) {
            state = loadFromDisk(appCtx)
            loaded = true
        }
        val force = forceNext
        forceNext = false
        var next = state

        // v3.2 (ask 43): the ONLY network-fetch gates. An explicit
        // request (manual Refresh / per-section Retry) forces;
        // a cache dir with none of the core files fetches once
        // (first-run seeding). A stale-but-present file NEVER
        // fetches — TTLs survive only as diagnostics age. The
        // shaping below (coming-soon from disk facts, for-you
        // from the cached picks file, trending∩IPTV from the
        // store) still runs every worker pass, disk-only.
        val cacheDir = dir(appCtx)
        val hasCoreCache = listOf(F_TREND_TV, F_TREND_MOVIE, F_TREND_ALL, F_FORYOU).any {
            try {
                java.io.File(cacheDir, it).isFile
            } catch (e: Exception) {
                false
            }
        }
        val allowFetch = force || !hasCoreCache

        // --- trending payloads (TTL 12 h, via the funnel) ---
        var trendingAll: List<Discovery.TrendItem> =
            Discovery.parseTrending(readFile(appCtx, F_TREND_ALL), null)
        fun refreshTrending(name: String, path: String, type: String?): Boolean {
            if (!allowFetch) return false
            val (json, _) = TrackerClient.tmdbGetBlocking(appCtx, path)
            if (json == null) return false
            writeFile(appCtx, name, json)
            stamps[name] = now
            dirtyStamps = true
            return true
        }
        if (refreshTrending(F_TREND_TV, "trending/tv/week", "tv")) {
            next = next.copy(
                trendingTv = Discovery.parseTrending(readFile(appCtx, F_TREND_TV), "tv")
            )
        }
        if (refreshTrending(F_TREND_MOVIE, "trending/movie/week", "movie")) {
            next = next.copy(
                trendingMovies = Discovery.parseTrending(readFile(appCtx, F_TREND_MOVIE), "movie")
            )
        }
        if (refreshTrending(F_TREND_ALL, "trending/all/week", null)) {
            trendingAll = Discovery.parseTrending(readFile(appCtx, F_TREND_ALL), null)
        }

        // --- facts for every tracked title (details TTL 24 h) ---
        // v3.5 (spec §35): NEVER parse here on an automatic run.
        // The old shape called cached(), which full-parses the
        // multi-megabyte snapshots when the memo is cold — so the
        // discovery worker used to parse the tracker documents a
        // second time, beside the tracker warm. The chain runs
        // TRACKER_FULL before DISCOVERY, so the memo is warm by
        // chain time; when it is not (and this run was not
        // explicitly forced by the user), the shaping pass is
        // skipped — the sections stay LOADING until the chain
        // lands. A forced run (manual Refresh / section Retry)
        // is user-initiated and may parse, as before.
        val snap = TrackerClient.memoOrNull()
            ?: if (force) TrackerClient.cached(appCtx) else return false
        val libShows = LibraryLogic.parseLibShows(snap.trackedJson)
        val tvFacts = HashMap<Long, Discovery.TvFacts?>()
        val movieFacts = HashMap<Long, Discovery.MovieFacts?>()
        for (show in snap.tracked) {
            val name = detailsName("tv", show.tmdbId)
            if (allowFetch) {
                val (json, _) = TrackerClient.tmdbGetBlocking(
                    appCtx, LibraryLogic.detailsPath("tv", show.tmdbId)
                )
                // Success AND failure are stamped (diagnostics age);
                // v3.2: no auto-refetch exists any more to throttle.
                writeFile(appCtx, name, json ?: "")
                stamps[name] = now
                dirtyStamps = true
            }
            tvFacts[show.tmdbId] = Discovery.parseTvFacts(readFile(appCtx, name))
        }
        for (movie in snap.movies) {
            val name = detailsName("movie", movie.tmdbId)
            if (allowFetch) {
                val (json, _) = TrackerClient.tmdbGetBlocking(
                    appCtx, LibraryLogic.detailsPath("movie", movie.tmdbId)
                )
                writeFile(appCtx, name, json ?: "")
                stamps[name] = now
                dirtyStamps = true
            }
            movieFacts[movie.tmdbId] = Discovery.parseMovieFacts(readFile(appCtx, name))
        }

        // --- coming soon (recomputed whenever facts or the library moved) ---
        val posterCache = TrackerClient.posterCache(appCtx)
        val contPosters = snap.cont.associate { it.tmdbId to it.poster }
        val upPosters = snap.upNext.associate { it.tmdbId to it.poster }
        fun showPoster(id: Long, facts: Discovery.TvFacts?): String =
            facts?.poster?.ifBlank { null }
                ?: contPosters[id]?.ifBlank { null }
                ?: upPosters[id]?.ifBlank { null }
                ?: posterCache["tv|$id"]
                ?: ""
        val soonShows = snap.tracked.mapNotNull { show ->
            val facts = tvFacts[show.tmdbId] ?: return@mapNotNull null
            if (facts.nextDateIso.isBlank()) return@mapNotNull null
            Discovery.ShowSoon(
                show.tmdbId, show.name, showPoster(show.tmdbId, facts),
                facts.nextDateIso, facts.nextSeason, facts.nextEpisode
            )
        }
        val soonMovies = snap.movies.mapNotNull { movie ->
            val facts = movieFacts[movie.tmdbId]
            // The snapshot's own release date wins (it is the
            // tracker's); the details payload fills a blank.
            val date = movie.releaseDate.ifBlank { facts?.releaseDate ?: "" }
            if (date.isBlank()) return@mapNotNull null
            Discovery.MovieSoon(
                movie.tmdbId, movie.title,
                movie.poster.ifBlank { facts?.poster ?: "" }, date
            )
        }
        val soon = Discovery.shapeComingSoon(soonShows, soonMovies, todayIso())
        if (soon != next.comingSoon) next = next.copy(comingSoon = soon)

        // --- for you, computed in the launcher (needs the launcher key) ---
        val launcherKey = try {
            Prefs(appCtx).tmdbKey
        } catch (e: Exception) {
            ""
        }
        if (launcherKey.isNotBlank()) {
            if (allowFetch) {
                val picks = computeForYou(appCtx, snap, libShows, tvFacts, movieFacts)
                if (picks != null) {
                    writeFile(appCtx, F_FORYOU, Discovery.encodePicks(picks))
                    stamps[F_FORYOU] = now
                    dirtyStamps = true
                    next = next.copy(forYou = picks, forYouComputed = true)
                }
            } else if (!next.forYouComputed) {
                val cachedPicks = TrackerLogic.parseForYou(readFile(appCtx, F_FORYOU))
                if (cachedPicks.isNotEmpty()) {
                    next = next.copy(forYou = cachedPicks, forYouComputed = true)
                }
            }
        }

        // --- trending ∩ IPTV catalogue (the IPTV home's row) ---
        val storeStamp = try {
            IptvClient.storeStamp(appCtx)
        } catch (e: Exception) {
            0L
        }
        val matchKey = "${stamps[F_TREND_ALL] ?: 0L}@$storeStamp"
        if (matchKey != iptvMatchStamp) {
            iptvMatchStamp = matchKey
            val plays = matchTrending(appCtx, trendingAll)
            if (plays != next.trendingIptv) next = next.copy(trendingIptv = plays)
        }

        pruneDetails(appCtx, stamps, now, Discovery.DETAILS_TTL_MS * 2)
        if (dirtyStamps) saveStamps(appCtx, stamps)

        // --- diagnostics bookkeeping (counts only; read-cheap) ---
        // v2.9 (ask H.30): the source word reflects what this pass
        // ACTUALLY supplied — the rendered For You is the
        // launcher's compute (forYouComputed) or the tracker's
        // mirror, and the Soon/Trending sets are launcher-shaped
        // only when they hold anything. (Pre-v2.9 the word came
        // from whether a launcher key was SET, so it claimed
        // "launcher" even when every fetch had failed and the
        // tracker snapshots were all that rendered.)
        val source = Discovery.diagSource(
            forYouComputed = next.forYouComputed,
            forYouFromTracker = !next.forYouComputed && snap.forYou.isNotEmpty(),
            launcherSetsLive = next.forYouComputed ||
                next.comingSoon.isNotEmpty() ||
                next.trendingTv.isNotEmpty() || next.trendingMovies.isNotEmpty()
        )
        val trendCount = next.trendingTv.size + next.trendingMovies.size
        val forYouCount = if (next.forYouComputed) {
            next.forYou.size
        } else {
            snap.forYou.size
        }
        try {
            prefs(appCtx).edit()
                .putString(K_SOURCE, source)
                .putInt(K_SOON_N, next.comingSoon.size)
                .putInt(K_TREND_N, trendCount)
                .putInt(K_FORYOU_N, forYouCount)
                .apply()
        } catch (e: Exception) {
            // Bookkeeping only.
        }

        val changed = next != state
        state = next
        // v3.1 (hero schedule): publish the facts for the hero's
        // date fill (null facts dropped — a show without a next
        // episode simply has no entry).
        lastTvFacts = tvFacts.entries.mapNotNull { (id, facts) ->
            facts?.let { id to it }
        }.toMap()
        return changed
    }

    /** First load: parse whatever the disk cache already holds. */
    private fun loadFromDisk(appCtx: Context): State {
        val forYou = TrackerLogic.parseForYou(readFile(appCtx, F_FORYOU))
        val keySet = try {
            Prefs(appCtx).tmdbKey.isNotBlank()
        } catch (e: Exception) {
            false
        }
        return State(
            trendingTv = Discovery.parseTrending(readFile(appCtx, F_TREND_TV), "tv"),
            trendingMovies = Discovery.parseTrending(readFile(appCtx, F_TREND_MOVIE), "movie"),
            comingSoon = emptyList(), // shaped by the worker pass below, same run
            forYou = forYou,
            forYouComputed = keySet && forYou.isNotEmpty(),
            trendingIptv = emptyList() // matched by the worker pass below, same run
        )
    }

    /**
     * The launcher For You compute (tracker computeForYou, ported
     * to core/Discovery's pure steps): affinity from tracked
     * titles' genres weighted by completion, top seeds,
     * recommendations per seed + discover over the top genres.
     * Null = the compute produced nothing usable (network down) —
     * the previously cached set, if any, keeps serving.
     */
    private fun computeForYou(
        appCtx: Context,
        snap: TrackerClient.Snapshots,
        libShows: List<LibraryLogic.LibShow>,
        tvFacts: Map<Long, Discovery.TvFacts?>,
        movieFacts: Map<Long, Discovery.MovieFacts?>
    ): List<TrackerLogic.ForYouPick>? {
        val watchedById = libShows.associate { it.tmdbId to it.watched.size }
        val inputs = ArrayList<Discovery.SeedInput>()
        for (show in snap.tracked) {
            val facts = tvFacts[show.tmdbId] ?: continue
            inputs.add(
                Discovery.SeedInput(
                    "tv", show.tmdbId, show.name,
                    Discovery.tvSeedWeight(watchedById[show.tmdbId] ?: show.watchedCount),
                    facts.voteAverage, facts.genreIds
                )
            )
        }
        for (movie in snap.movies) {
            val facts = movieFacts[movie.tmdbId] ?: continue
            inputs.add(
                Discovery.SeedInput(
                    "movie", movie.tmdbId, movie.title,
                    Discovery.movieSeedWeight(movie.watched),
                    facts.voteAverage, facts.genreIds
                )
            )
        }
        if (inputs.size < 3) return null
        val aff = Discovery.affinity(inputs)
        val topGenres = Discovery.topGenres(aff, 3)
        val lists = ArrayList<List<TrackerLogic.ForYouPick>>()
        var anyFetch = false
        for (seed in Discovery.pickSeeds(inputs)) {
            val (json, _) = TrackerClient.tmdbGetBlocking(
                appCtx, Discovery.recommendationsPath(seed)
            )
            if (json == null) continue
            anyFetch = true
            lists.add(Discovery.recPicks(seed, Discovery.parseCandidates(json, seed.type)))
        }
        if (topGenres.isNotEmpty()) {
            val byWeight = inputs.sortedByDescending { it.weight }
            for (type in listOf("tv", "movie")) {
                val (json, _) = TrackerClient.tmdbGetBlocking(
                    appCtx, Discovery.discoverPath(type, topGenres)
                )
                if (json == null) continue
                anyFetch = true
                lists.add(
                    Discovery.discoverPicks(
                        type, Discovery.parseCandidates(json, type), topGenres, aff
                    ) { gid ->
                        byWeight.firstOrNull { gid in it.genreIds }?.title
                    }
                )
            }
        }
        if (!anyFetch) return null
        val exclude = HashSet<String>()
        for (input in inputs) exclude.add(input.key())
        for (movie in snap.movies) if (movie.watched) exclude.add("movie|${movie.tmdbId}")
        return Discovery.mergePicks(lists, exclude, cap = 20)
    }

    /**
     * Match the trending/all list into the IPTV catalogue with the
     * repo-method matcher (core/WatchMatch over the key-only
     * stream — worker thread, keyed once, released after the
     * run). Titles already tracked are kept: the row is
     * "trending on YOUR IPTV", not a discovery gate.
     * v3.5: the old shape read the memoized tuples; the key-only
     * stream replaces them (same matches, no retained catalogue).
     */
    private fun matchTrending(
        appCtx: Context,
        trending: List<Discovery.TrendItem>
    ): List<TrendPlay> {
        if (trending.isEmpty()) return emptyList()
        val keyed = IptvClient.keyedCatalogue(appCtx) ?: return emptyList()
        if (keyed.first.isEmpty() && keyed.second.isEmpty()) return emptyList()
        val shows = WatchMatch.matchAllKeyed(
            trending.filter { it.type == "tv" }.map {
                WatchMatch.TrackerTitle(it.id, "tv", it.title, it.year.toIntOrNull() ?: 0)
            },
            keyed.first
        )
        val movies = WatchMatch.matchAllKeyed(
            trending.filter { it.type == "movie" }.map {
                WatchMatch.TrackerTitle(it.id, "movie", it.title, it.year.toIntOrNull() ?: 0)
            },
            keyed.second
        )
        val out = ArrayList<TrendPlay>()
        for (item in trending) {
            val m = (if (item.type == "movie") movies else shows)[item.id] ?: continue
            out.add(TrendPlay(item, m.iptvId))
        }
        return out
    }

    private fun todayIso(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date())

    // ------------------------------------------------------------ diagnostics

    /** The persisted discovery counts for the `discovery:` line (prefs only). */
    fun diagState(ctx: Context): Triple<String, Pair<Int, Int>, Int> {
        return try {
            val p = prefs(ctx)
            Triple(
                PrefsSafe.str(p, K_SOURCE, "tracker"),
                PrefsSafe.int(p, K_SOON_N, 0) to PrefsSafe.int(p, K_TREND_N, 0),
                PrefsSafe.int(p, K_FORYOU_N, 0)
            )
        } catch (e: Exception) {
            Triple("tracker", 0 to 0, 0)
        }
    }
}
