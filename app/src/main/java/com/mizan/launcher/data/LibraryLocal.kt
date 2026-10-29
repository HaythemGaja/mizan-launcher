package com.mizan.launcher.data

import android.content.Context
import com.mizan.launcher.core.LibraryLogic
import com.mizan.launcher.core.TrackerLogic
import com.mizan.launcher.core.TrackerStore
import kotlin.concurrent.thread

/**
 * The launcher-owned library's WRITE side (v3.7) — the
 * replacement for the tracker app's action queue.
 *
 * The launcher IS the tracker now: every write below
 * mutates filesDir/tracker/ directly (the v3.6 indexed
 * store, extended with missed.txt), atomically
 * (tmp+rename per file, index rewritten last), under
 * ONE lock, on a serialized worker — so rapid marks
 * cannot interleave file writes. After every successful
 * write the store memo is re-installed
 * (TrackerClient.installStoreMemo) and the
 * `library:` diagnostics stamp moves, so the home's
 * next build reads the new data with no fetch: a
 * mark-watched updates its own rows immediately; it
 * does not trigger a refresh (the no-refresh rule is
 * untouched — local writes are user actions, not
 * fetches).
 *
 * The pending overlay (data/TrackerActions) stays the
 * instant-render layer: it is written synchronously on
 * the UI thread BEFORE the store worker runs, so the
 * screen never waits on disk. The store worker folds
 * the overlay into its base state before applying the
 * new change (so a pre-v3.7 overlay entry, or a second
 * tap that landed before the first worker ran, is never
 * clobbered), and prunes the overlay against the new
 * store state afterwards (store-proven parts retire;
 * unproven parts stay rendered — see
 * LibraryLogic.prunePendingLocal).
 *
 * Fail-open, everywhere: a missing/corrupt episode
 * file for a show with a positive title count is NEVER
 * overwritten with a partial set (that would destroy
 * the true count) — the write becomes overlay-only and
 * the error is noted; the overlay keeps rendering the
 * truth until the file is repaired (a later import, or
 * a store rebuild). A TMDB failure in the derived
 * workers (next-episode refresh, missed recompute)
 * leaves the previous lines in place — never an empty
 * row, never an invented date.
 *
 * Threading: public queue* functions return at once
 * (they spawn the worker). All file IO here runs on
 * workers; nothing here runs on the main thread.
 * Network (TMDB, direct key only) runs OUTSIDE the
 * write lock; only the final file mutation holds it,
 * recomputed from fresh store state at that moment.
 */
object LibraryLocal {

    /** Serializes all store mutations (one writer at a time). */
    private val lock = Any()

    /** Missed full-recompute single-flight. */
    @Volatile
    private var missedAllInFlight = false

    /**
     * Fired (on a worker thread) after the missed file
     * changes — single-show or full recompute. MainActivity
     * installs this to rebuild the home when the Missed
     * row lands; it is null until then (a missed landing
     * with no listener simply waits for the next rebuild).
     */
    @Volatile
    var onMissedChanged: (() -> Unit)? = null

    /**
     * Device local date, yyyy-MM-dd (the missed row's "today").
     * v3.15: java.time, not SimpleDateFormat — the latter's first call
     * in a process builds ICU's currency table on the calling thread
     * (seconds on TV hardware); this is called per missed card.
     */
    fun todayIso(): String = java.time.LocalDate.now().toString()

    // ------------------------------------------------------------ overlay folding + pruning

    /** This title's overlay entry (or null), from the caller's profile prefs. */
    private fun overlayEntry(prefs: Prefs, type: String, tmdbId: Long): LibraryLogic.PendingTitle? =
        LibraryLogic.overlayEntry(LibraryLogic.decodePending(prefs.pendingOverlay), type, tmdbId)

    /** Base watched set with this title's pending adds/removes folded in (never clobbers pending work). */
    private fun foldedWatched(
        prefs: Prefs,
        tmdbId: Long,
        base: Set<String>
    ): Set<String> {
        val entry = overlayEntry(prefs, "tv", tmdbId) ?: return base
        return (base + entry.watchedAdd) - entry.watchedRemove
    }

    /**
     * Prune the caller's overlay against the CURRENT
     * store (worker only). Store-backed shows come from
     * libraryShows with full sets for overlay titles, so
     * convergence is exact for exactly the titles the
     * user just changed. Persists only when changed.
     * Callers must hold [lock] OR run after the write
     * they prune for — this reads the store, so it must
     * never race the writer; queue* callers run it
     * inside their locked section.
     */
    private fun pruneOverlayLocked(ctx: Context, prefs: Prefs) {
        try {
            val overlay = LibraryLogic.decodePending(prefs.pendingOverlay)
            if (overlay.isEmpty()) return
            val fullSetIds = overlay.filter { it.type == "tv" }.map { it.tmdbId }.toSet()
            val shows = TrackerStoreDisk.libraryShows(ctx, fullSetIds)
            val movies = TrackerStoreDisk.readMovies(ctx)
            val pruned = LibraryLogic.prunePendingLocal(overlay, shows, movies)
            if (pruned != overlay) {
                prefs.pendingOverlay = LibraryLogic.encodePending(pruned)
            }
        } catch (e: Exception) {
            // Pruning is convergence bookkeeping; a failed
            // prune leaves entries rendered (fail-open).
            try { Prefs.noteError(ctx, "libraryPrune", e) } catch (_: Exception) { }
        }
    }

    /** Finish a successful store mutation: index, memo, diagnostics stamp. Must run under [lock]. */
    private fun finishWriteLocked(ctx: Context, prefs: Prefs) {
        TrackerStoreDisk.rewriteIndex(ctx)
        TrackerClient.installStoreMemo(ctx)
        TrackerClient.noteLocalChange(ctx)
        pruneOverlayLocked(ctx, prefs)
    }

    // ------------------------------------------------------------ episode writes

    /** Toggle one episode's watched state (details checklist, home mark-watched, player auto-mark). */
    fun queueEpisodeToggle(
        ctx: Context,
        prefs: Prefs,
        tmdbId: Long,
        season: Int,
        episode: Int,
        watched: Boolean
    ) {
        val appCtx = ctx.applicationContext
        thread(name = "mizan-library-write") {
            try {
                var advanced = false
                synchronized(lock) {
                    val titles = TrackerStoreDisk.readTitles(appCtx)
                    var title = titles.firstOrNull { it.type == "tv" && it.tmdbId == tmdbId }
                    val entry = overlayEntry(prefs, "tv", tmdbId)
                    val epFile = TrackerStoreDisk.readEpisode(appCtx, tmdbId)
                    if (title == null && entry?.addedTitle == null) {
                        // Unknown title entirely — a stale screen's
                        // toggle. The overlay (if any) still renders;
                        // there is no store record to mutate.
                        return@thread
                    }
                    if (title == null) {
                        // Overlay-added title landing in the store
                        // for the first time (planned, per the add
                        // semantics): the title line + episode file
                        // are created from the overlay entry, then
                        // the toggle applies below.
                        title = TrackerStore.Title(
                            type = "tv", tmdbId = tmdbId, title = entry!!.addedTitle!!,
                            poster = entry.addedPoster, backdrop = "",
                            watchedCount = 0, planned = true, muted = entry.muted ?: false,
                            nextSeason = 0, nextEpisode = 0
                        )
                    }
                    val existing = title!!
                    if (epFile == null && existing.watchedCount > 0) {
                        // CORRUPT/MISSING episode file with a real
                        // count: overwriting it with the folded
                        // set would destroy the true count (e.g.
                        // 500 → 1). Overlay-only: the toggle keeps
                        // rendering via the overlay, and the store
                        // count stays intact until repair.
                        Prefs.noteError(
                            appCtx, "libraryWrite",
                            IllegalStateException("episode file missing for $tmdbId count ${existing.watchedCount}")
                        )
                        return@thread
                    }
                    val base = foldedWatched(prefs, tmdbId, epFile?.watched ?: emptySet())
                    val key = LibraryLogic.epKey(season, episode)
                    val newSet = if (watched) base + key else base - key
                    if (!TrackerStoreDisk.writeEpisode(
                            appCtx, tmdbId,
                            TrackerStore.EpisodeFile(epFile?.muted ?: existing.muted, newSet)
                        )
                    ) {
                        return@thread
                    }
                    var upNext = TrackerStoreDisk.readUpNext(appCtx)
                    // Advancing: a watched mark removes its own
                    // upnext line (the next-episode refresh
                    // below repopulates it with the true next
                    // episode); an unmark leaves the line for
                    // the refresh to correct.
                    if (watched) {
                        val before = upNext.size
                        upNext = upNext.filterNot {
                            it.tmdbId == tmdbId && it.season == season && it.episode == episode
                        }
                        advanced = upNext.size != before
                        if (advanced && !TrackerStoreDisk.writeUpNext(appCtx, upNext)) return@thread
                    }
                    val cont = TrackerStoreDisk.readContinue(appCtx).map { c ->
                        if (c.tmdbId == tmdbId) c.copy(watched = newSet.size) else c
                    }
                    if (!TrackerStoreDisk.writeContinue(appCtx, cont)) return@thread
                    val newTitles = titles.map { t ->
                        if (t.type == "tv" && t.tmdbId == tmdbId) {
                            t.copy(
                                watchedCount = newSet.size,
                                nextSeason = if (advanced) 0 else t.nextSeason,
                                nextEpisode = if (advanced) 0 else t.nextEpisode
                            )
                        } else t
                    } + if (titles.none { it.type == "tv" && it.tmdbId == tmdbId }) {
                        listOf(existing.copy(watchedCount = newSet.size))
                    } else {
                        emptyList()
                    }
                    if (!TrackerStoreDisk.writeTitles(appCtx, newTitles)) return@thread
                    finishWriteLocked(appCtx, prefs)
                }
                // Derived workers (outside the lock): the
                // next episode for this show, and its missed
                // row entry, both recompute from the new set.
                refreshNextEpisode(appCtx, tmdbId)
                recomputeMissedForShow(appCtx, tmdbId)
            } catch (e: Exception) {
                try { Prefs.noteError(appCtx, "libraryWrite", e) } catch (_: Exception) { }
            }
        }
    }

    /** Mark a whole season watched from its TMDB episode list. */
    fun queueSeasonMark(
        ctx: Context,
        prefs: Prefs,
        tmdbId: Long,
        season: Int,
        episodeNumbers: List<Int>
    ) {
        if (episodeNumbers.isEmpty()) return
        val appCtx = ctx.applicationContext
        thread(name = "mizan-library-write") {
            try {
                synchronized(lock) {
                    val titles = TrackerStoreDisk.readTitles(appCtx)
                    val title = titles.firstOrNull { it.type == "tv" && it.tmdbId == tmdbId }
                        ?: return@thread
                    val epFile = TrackerStoreDisk.readEpisode(appCtx, tmdbId)
                    if (epFile == null && title.watchedCount > 0) return@thread
                    val base = foldedWatched(prefs, tmdbId, epFile?.watched ?: emptySet())
                    val keys = episodeNumbers.filter { it > 0 }
                        .map { LibraryLogic.epKey(season, it) }.toSet()
                    val newSet = base + keys
                    if (!TrackerStoreDisk.writeEpisode(
                            appCtx, tmdbId,
                            TrackerStore.EpisodeFile(epFile?.muted ?: title.muted, newSet)
                        )
                    ) {
                        return@thread
                    }
                    val upNext = TrackerStoreDisk.readUpNext(appCtx).filterNot { item ->
                        item.tmdbId == tmdbId && LibraryLogic.epKey(item.season, item.episode) in keys
                    }
                    if (!TrackerStoreDisk.writeUpNext(appCtx, upNext)) return@thread
                    val cont = TrackerStoreDisk.readContinue(appCtx).map { c ->
                        if (c.tmdbId == tmdbId) c.copy(watched = newSet.size) else c
                    }
                    if (!TrackerStoreDisk.writeContinue(appCtx, cont)) return@thread
                    val newTitles = titles.map { t ->
                        if (t.type == "tv" && t.tmdbId == tmdbId) {
                            t.copy(watchedCount = newSet.size, nextSeason = 0, nextEpisode = 0)
                        } else t
                    }
                    if (!TrackerStoreDisk.writeTitles(appCtx, newTitles)) return@thread
                    finishWriteLocked(appCtx, prefs)
                }
                refreshNextEpisode(appCtx, tmdbId)
                recomputeMissedForShow(appCtx, tmdbId)
            } catch (e: Exception) {
                try { Prefs.noteError(appCtx, "libraryWrite", e) } catch (_: Exception) { }
            }
        }
    }

    // ------------------------------------------------------------ movie writes

    /** Toggle a movie's watched state (details + library menu + player auto-mark). */
    fun queueMovieToggle(ctx: Context, prefs: Prefs, tmdbId: Long, watched: Boolean) {
        val appCtx = ctx.applicationContext
        thread(name = "mizan-library-write") {
            try {
                synchronized(lock) {
                    val movies = TrackerStoreDisk.readMovies(appCtx)
                    val movie = movies.firstOrNull { it.tmdbId == tmdbId }
                    val entry = overlayEntry(prefs, "movie", tmdbId)
                    if (movie == null && entry?.addedTitle == null) return@thread
                    val newMovies = if (movie != null) {
                        movies.map { m -> if (m.tmdbId == tmdbId) m.copy(watched = watched) else m }
                    } else {
                        movies + TrackerLogic.MovieItem(
                            tmdbId, entry!!.addedTitle!!, entry.addedDate,
                            true, entry.addedPoster, watched
                        )
                    }
                    if (!TrackerStoreDisk.writeMovies(appCtx, newMovies)) return@thread
                    finishWriteLocked(appCtx, prefs)
                }
            } catch (e: Exception) {
                try { Prefs.noteError(appCtx, "libraryWrite", e) } catch (_: Exception) { }
            }
        }
    }

    // ------------------------------------------------------------ title writes

    /** Add a title (TMDB search / For You / Trending / Details "Add to my list"). */
    fun queueAddTitle(
        ctx: Context,
        prefs: Prefs,
        tmdbId: Long,
        type: String,
        title: String,
        poster: String,
        releaseDate: String,
        onDone: (() -> Unit)? = null
    ) {
        val appCtx = ctx.applicationContext
        thread(name = "mizan-library-write") {
            try {
                synchronized(lock) {
                    if (type == "movie") {
                        val movies = TrackerStoreDisk.readMovies(appCtx)
                        if (movies.none { it.tmdbId == tmdbId }) {
                            if (!TrackerStoreDisk.writeMovies(
                                    appCtx,
                                    movies + TrackerLogic.MovieItem(
                                        tmdbId, title, releaseDate, true, poster, false
                                    )
                                )
                            ) {
                                return@thread
                            }
                            // The overlay entry (if any) now
                            // converges: the title is present.
                            finishWriteLocked(appCtx, prefs)
                        }
                    } else {
                        val titles = TrackerStoreDisk.readTitles(appCtx)
                        if (titles.none { it.type == "tv" && it.tmdbId == tmdbId }) {
                            val entry = overlayEntry(prefs, "tv", tmdbId)
                            val newTitle = TrackerStore.Title(
                                type = "tv", tmdbId = tmdbId, title = title,
                                poster = poster, backdrop = "", watchedCount = 0,
                                planned = true, muted = entry?.muted ?: false,
                                nextSeason = 0, nextEpisode = 0
                            )
                            if (!TrackerStoreDisk.writeTitles(appCtx, titles + newTitle)) return@thread
                            if (!TrackerStoreDisk.writeEpisode(
                                    appCtx, tmdbId,
                                    TrackerStore.EpisodeFile(newTitle.muted, emptySet())
                                )
                            ) {
                                return@thread
                            }
                            finishWriteLocked(appCtx, prefs)
                        }
                    }
                }
                // Landing callback (worker thread): Details
                // uses it to flip into the tracked state the
                // moment the store holds the title.
                try { onDone?.invoke() } catch (_: Exception) { }
            } catch (e: Exception) {
                try { Prefs.noteError(appCtx, "libraryWrite", e) } catch (_: Exception) { }
            }
        }
    }

    /** Remove a title from the library (its progress goes with it). */
    fun queueRemoveTitle(ctx: Context, prefs: Prefs, tmdbId: Long, type: String) {
        val appCtx = ctx.applicationContext
        thread(name = "mizan-library-write") {
            try {
                synchronized(lock) {
                    if (type == "movie") {
                        val movies = TrackerStoreDisk.readMovies(appCtx)
                        if (!TrackerStoreDisk.writeMovies(
                                appCtx, movies.filterNot { it.tmdbId == tmdbId }
                            )
                        ) {
                            return@thread
                        }
                    } else {
                        val titles = TrackerStoreDisk.readTitles(appCtx)
                        if (!TrackerStoreDisk.writeTitles(
                                appCtx, titles.filterNot { it.type == "tv" && it.tmdbId == tmdbId }
                            )
                        ) {
                            return@thread
                        }
                        TrackerStoreDisk.deleteEpisode(appCtx, tmdbId)
                        val upNext = TrackerStoreDisk.readUpNext(appCtx)
                            .filterNot { it.tmdbId == tmdbId }
                        if (!TrackerStoreDisk.writeUpNext(appCtx, upNext)) return@thread
                        val cont = TrackerStoreDisk.readContinue(appCtx)
                            .filterNot { it.tmdbId == tmdbId }
                        if (!TrackerStoreDisk.writeContinue(appCtx, cont)) return@thread
                        val missed = TrackerStoreDisk.readMissed(appCtx)
                            .filterNot { it.tmdbId == tmdbId }
                        if (!TrackerStoreDisk.writeMissed(appCtx, missed)) return@thread
                    }
                    finishWriteLocked(appCtx, prefs)
                }
            } catch (e: Exception) {
                try { Prefs.noteError(appCtx, "libraryWrite", e) } catch (_: Exception) { }
            }
        }
    }

    /** Mute/unmute a title's notifications. Movies carry no muted flag in the store (overlay-only, as before). */
    fun queueMuted(ctx: Context, prefs: Prefs, tmdbId: Long, type: String, muted: Boolean) {
        if (type == "movie") return
        val appCtx = ctx.applicationContext
        thread(name = "mizan-library-write") {
            try {
                synchronized(lock) {
                    val titles = TrackerStoreDisk.readTitles(appCtx)
                    if (titles.none { it.type == "tv" && it.tmdbId == tmdbId }) return@thread
                    val epFile = TrackerStoreDisk.readEpisode(appCtx, tmdbId)
                    if (epFile != null) {
                        if (!TrackerStoreDisk.writeEpisode(
                                appCtx, tmdbId,
                                TrackerStore.EpisodeFile(muted, epFile.watched)
                            )
                        ) {
                            return@thread
                        }
                    }
                    val newTitles = titles.map { t ->
                        if (t.type == "tv" && t.tmdbId == tmdbId) t.copy(muted = muted) else t
                    }
                    if (!TrackerStoreDisk.writeTitles(appCtx, newTitles)) return@thread
                    finishWriteLocked(appCtx, prefs)
                }
            } catch (e: Exception) {
                try { Prefs.noteError(appCtx, "libraryWrite", e) } catch (_: Exception) { }
            }
        }
    }

    /** Dismiss a For You pick ("Not interested") — the pick leaves the foryou section. */
    fun queueDismissForYou(ctx: Context, tmdbId: Long, type: String) {
        val appCtx = ctx.applicationContext
        thread(name = "mizan-library-write") {
            try {
                synchronized(lock) {
                    val forYou = TrackerStoreDisk.readForYou(appCtx)
                    if (!TrackerStoreDisk.writeForYou(
                            appCtx,
                            forYou.filterNot { it.id == tmdbId && it.type == type }
                        )
                    ) {
                        return@thread
                    }
                    TrackerStoreDisk.rewriteIndex(appCtx)
                    TrackerClient.installStoreMemo(appCtx)
                    TrackerClient.noteLocalChange(appCtx)
                }
            } catch (e: Exception) {
                try { Prefs.noteError(appCtx, "libraryWrite", e) } catch (_: Exception) { }
            }
        }
    }

    // ------------------------------------------------------------ derived workers: next episode

    /**
     * Recompute one show's next episode from its watched
     * set + TMDB season data (direct key only), then
     * rewrite its upnext line, title next coords, and
     * continue line. This is what makes marks advance
     * one episode at a time: after S1E5 is marked, the
     * next unwatched episode (S1E6, or the next season's
     * opener) becomes the show's upnext/continue target —
     * and, when its air date is past, the missed row's
     * next entry for that show. A caught-up show loses
     * its upnext line and its continue sub becomes
     * "✓ All caught up". Network runs outside the lock;
     * the final mutation re-reads the episode file under
     * the lock, so a mark that landed mid-fetch is never
     * overwritten by stale data. TMDB unreachable (or no
     * key) → the upnext line stays removed until a later
     * successful refresh; the row entry simply stays
     * hidden (marked-set + overlay filter), never wrong.
     */
    fun refreshNextEpisode(ctx: Context, tmdbId: Long) {
        val appCtx = ctx.applicationContext
        thread(name = "mizan-next-ep") {
            try {
                val title = TrackerStoreDisk.readTitles(appCtx)
                    .firstOrNull { it.type == "tv" && it.tmdbId == tmdbId } ?: return@thread
                val epFile = TrackerStoreDisk.readEpisode(appCtx, tmdbId)
                if (epFile == null && title.watchedCount > 0) return@thread
                val (details, seasons) = fetchSeasonDates(appCtx, tmdbId) ?: return@thread
                synchronized(lock) {
                    val fresh = TrackerStoreDisk.readEpisode(appCtx, tmdbId)
                    if (fresh == null) {
                        val freshTitle = TrackerStoreDisk.readTitles(appCtx)
                            .firstOrNull { it.type == "tv" && it.tmdbId == tmdbId }
                        if (freshTitle != null && freshTitle.watchedCount > 0) return@thread
                    }
                    val watched = fresh?.watched ?: emptySet()
                    var next: Triple<Int, Int, String>? = null
                    var total = 0
                    for (season in seasons.sortedBy { it.season }) {
                        if (season.season < 1) continue
                        total += season.episodes.size
                        if (next == null) {
                            for ((ep, date) in season.episodes.sortedBy { it.first }) {
                                if (ep < 1) continue
                                if (LibraryLogic.epKey(season.season, ep) !in watched) {
                                    next = Triple(season.season, ep, date)
                                    break
                                }
                            }
                        }
                    }
                    val titles = TrackerStoreDisk.readTitles(appCtx)
                    // v3.8: poster cache is the third source — a
                    // title line with no poster must not rewrite
                    // the upnext line poster-less (that is how a
                    // poster-first card ends up showing a still).
                    val posterCache = try {
                        TrackerClient.posterCache(appCtx)
                    } catch (e: Exception) {
                        emptyMap()
                    }
                    val poster = title.poster.ifBlank { details.poster }
                        .ifBlank {
                            posterCache[com.mizan.launcher.core.LibraryLogic.posterKey("tv", tmdbId)]
                                .orEmpty()
                        }
                    val backdrop = title.backdrop.ifBlank { details.backdrop }
                    var upNext = TrackerStoreDisk.readUpNext(appCtx)
                        .filterNot { it.tmdbId == tmdbId }
                    if (next != null) {
                        val (s, e, date) = next
                        upNext = upNext + TrackerLogic.UpNextItem(
                            tmdbId = tmdbId, title = title.title,
                            sub = "S$s E$e", dateIso = date,
                            poster = poster, backdrop = backdrop,
                            overview = details.overview,
                            season = s, episode = e, type = "tv"
                        )
                    }
                    if (!TrackerStoreDisk.writeUpNext(appCtx, upNext)) return@thread
                    val cont = TrackerStoreDisk.readContinue(appCtx).map { c ->
                        if (c.tmdbId != tmdbId) c else c.copy(
                            watched = watched.size,
                            total = total,
                            sub = if (next == null) {
                                "✓ All caught up"
                            } else {
                                "Next S${next.first} E${next.second} · ${watched.size} of $total watched"
                            }
                        )
                    }
                    if (!TrackerStoreDisk.writeContinue(appCtx, cont)) return@thread
                    val newTitles = titles.map { t ->
                        if (t.type == "tv" && t.tmdbId == tmdbId) {
                            t.copy(
                                nextSeason = next?.first ?: 0,
                                nextEpisode = next?.second ?: 0
                            )
                        } else t
                    }
                    if (!TrackerStoreDisk.writeTitles(appCtx, newTitles)) return@thread
                    TrackerStoreDisk.rewriteIndex(appCtx)
                    TrackerClient.installStoreMemo(appCtx)
                }
            } catch (e: Exception) {
                try { Prefs.noteError(appCtx, "libraryNextEp", e) } catch (_: Exception) { }
            }
        }
    }

    /**
     * Fetch one show's season air dates (details +
     * per-season payloads, direct TMDB only), using the
     * 24 h per-show cache when fresh. Returns
     * (details, seasons) or null when TMDB is
     * unreachable / keyless / the show is unknown.
     * Seasons scan in ascending order and stop as soon
     * as the caller's [stopAfter] predicate holds — the
     * caller passes a predicate over the seasons fetched
     * so far, so a show whose next episode is in season
     * 1 never pays for seasons 2..N. The cache stores
     * every season fetched (later calls reuse them).
     */
    private fun fetchSeasonDates(
        ctx: Context,
        tmdbId: Long,
        stopAfter: ((List<TrackerLogic.SeasonAirDates>) -> Boolean)? = null
    ): Pair<LibraryLogic.TitleDetails, List<TrackerLogic.SeasonAirDates>>? {
        val cached = TrackerStoreDisk.readSeasonDates(ctx, tmdbId)
        val cachedFresh = cached != null &&
            TrackerStoreDisk.seasonDatesAgeMs(ctx, tmdbId) < 24L * 60L * 60L * 1000L
        val (detailsJson, _) = TrackerClient.tmdbGetBlocking(ctx, LibraryLogic.detailsPath("tv", tmdbId))
        val details = LibraryLogic.parseDetails(detailsJson, "tv") ?: return null
        if (cachedFresh && cached != null) return details to cached
        val regular = details.seasons.filter { it.number >= 1 }.sortedBy { it.number }
        val fetched = ArrayList<TrackerLogic.SeasonAirDates>()
        // Seed from any cached seasons first (a previous
        // partial fetch may already hold early seasons).
        val cachedBySeason = cached?.associateBy { it.season } ?: emptyMap()
        for (season in regular) {
            val fromCache = cachedBySeason[season.number]
            if (fromCache != null) {
                fetched.add(fromCache)
            } else {
                val (seasonJson, _) = TrackerClient.tmdbGetBlocking(
                    ctx, LibraryLogic.seasonPath(tmdbId, season.number)
                )
                val episodes = LibraryLogic.parseSeasonEpisodes(seasonJson)
                    .map { it.number to it.airDate }
                fetched.add(TrackerLogic.SeasonAirDates(season.number, episodes))
            }
            if (stopAfter != null && stopAfter(fetched)) break
        }
        val merged = (cached ?: emptyList()).filter { it.season !in fetched.map { s -> s.season } } + fetched
        TrackerStoreDisk.writeSeasonDates(ctx, tmdbId, merged.sortedBy { it.season })
        return details to merged.sortedBy { it.season }
    }

    // ------------------------------------------------------------ derived workers: missed row

    /**
     * Recompute ONE show's missed entry (after a local
     * mark/unmark for that show, or a title change).
     * Muted, planned, and untracked shows never enter
     * the row; a show with no passed unwatched episode
     * loses its lines. Network is cached-or-fetched like
     * the next-episode refresh, with early exit at the
     * first missed episode. v3.13: the show writes ONE
     * missed.txt line per passed unwatched episode (same
     * UpNext codec as before, so old single-line files
     * still decode); the home groups them into one card
     * per show. Writes missed.txt (+ its count stamp)
     * and fires [onMissedChanged].
     */
    fun recomputeMissedForShow(ctx: Context, tmdbId: Long) {
        val appCtx = ctx.applicationContext
        thread(name = "mizan-missed-one") {
            try {
                val title = TrackerStoreDisk.readTitles(appCtx)
                    .firstOrNull { it.type == "tv" && it.tmdbId == tmdbId } ?: return@thread
                if (title.planned || title.muted) {
                    synchronized(lock) {
                        val missed = TrackerStoreDisk.readMissed(appCtx)
                            .filterNot { it.tmdbId == tmdbId }
                        if (TrackerStoreDisk.writeMissed(appCtx, missed)) {
                            // v3.8: every missed write refreshes the
                            // memo, like the other two paths below —
                            // without it the home's rebuild reads a
                            // stale (slim) memo and the removed line
                            // keeps rendering.
                            TrackerClient.installStoreMemo(appCtx)
                            onMissedChanged?.invoke()
                        }
                    }
                    return@thread
                }
                val epFile = TrackerStoreDisk.readEpisode(appCtx, tmdbId)
                if (epFile == null && title.watchedCount > 0) return@thread
                val today = todayIso()
                val watched = epFile?.watched ?: emptySet()
                val (_, seasons) = fetchSeasonDates(appCtx, tmdbId) { fetched ->
                    TrackerLogic.allMissedEpisodes(watched, fetched, today).isNotEmpty()
                } ?: return@thread
                synchronized(lock) {
                    val freshTitle = TrackerStoreDisk.readTitles(appCtx)
                        .firstOrNull { it.type == "tv" && it.tmdbId == tmdbId } ?: return@thread
                    if (freshTitle.planned || freshTitle.muted) return@thread
                    val fresh = TrackerStoreDisk.readEpisode(appCtx, tmdbId)
                    val freshWatched = fresh?.watched
                        ?: if (freshTitle.watchedCount > 0) return@thread else emptySet()
                    val missedEps = TrackerLogic.allMissedEpisodes(freshWatched, seasons, today)
                    var missed = TrackerStoreDisk.readMissed(appCtx)
                        .filterNot { it.tmdbId == tmdbId }
                    if (missedEps.isNotEmpty()) {
                        // v3.8: poster cache fills a poster-less
                        // title line (same reason as the
                        // next-episode refresh below).
                        val missedPoster = freshTitle.poster.ifBlank {
                            try {
                                TrackerClient.posterCache(appCtx)[
                                    com.mizan.launcher.core.LibraryLogic.posterKey("tv", tmdbId)
                                ].orEmpty()
                            } catch (e: Exception) {
                                ""
                            }
                        }
                        for ((s, e, date) in missedEps) {
                            missed = missed + TrackerLogic.UpNextItem(
                                tmdbId = tmdbId, title = freshTitle.title,
                                sub = "S$s E$e", dateIso = date,
                                poster = missedPoster, backdrop = freshTitle.backdrop,
                                overview = "", season = s, episode = e, type = "tv"
                            )
                        }
                    }
                    if (!TrackerStoreDisk.writeMissed(appCtx, missed)) return@thread
                    val meta = TrackerStoreDisk.readMissedMeta(appCtx)
                    if (meta != null) {
                        TrackerStoreDisk.writeMissedMeta(
                            appCtx,
                            TrackerStoreDisk.MissedMeta(
                                updated = System.currentTimeMillis(),
                                count = missed.map { it.tmdbId }.toSet().size,
                                inputFingerprint = meta.inputFingerprint
                            )
                        )
                    }
                    TrackerClient.installStoreMemo(appCtx)
                    onMissedChanged?.invoke()
                }
            } catch (e: Exception) {
                try { Prefs.noteError(appCtx, "libraryMissed", e) } catch (_: Exception) { }
            }
        }
    }

    /**
     * Full missed recompute (cold-chain tail + explicit
     * refreshes): every tracked TV show, stamp-gated —
     * skipped entirely when the title input fingerprint
     * is unchanged AND the last run is under 24 h old
     * (unless [force]). Shows whose TMDB fetch fails
     * KEEP their previous line (stale beats absent);
     * shows that left the library drop out. Writes
     * missed.txt + its stamp once, then fires
     * [onMissedChanged]. Sequential and cancellable by
     * process death (no lock held during network).
     */
    fun kickMissedAll(ctx: Context, force: Boolean = false) {
        val appCtx = ctx.applicationContext
        synchronized(this) {
            if (missedAllInFlight) return
            missedAllInFlight = true
        }
        thread(name = "mizan-missed-all") {
            try {
                val titles = TrackerStoreDisk.readTitles(appCtx)
                val tvTitles = titles.filter { it.type == "tv" && !it.planned && !it.muted }
                val inputFp = TrackerStore.missedFingerprint(titles)
                if (!force) {
                    val meta = TrackerStoreDisk.readMissedMeta(appCtx)
                    if (meta != null && meta.inputFingerprint == inputFp &&
                        System.currentTimeMillis() - meta.updated < 24L * 60L * 60L * 1000L
                    ) {
                        return@thread
                    }
                }
                val today = todayIso()
                // v3.13: previous lines are kept PER SHOW (all of
                // them, not just one) when a show's episode file
                // is unreadable — a corrupt file must never
                // empty that show's missed entry.
                val previous = TrackerStoreDisk.readMissed(appCtx)
                    .groupBy { it.tmdbId }
                // v3.8: poster cache read once for the whole pass
                // (same backfill as the one-show path above).
                val missedPosterCache = try {
                    TrackerClient.posterCache(appCtx)
                } catch (e: Exception) {
                    emptyMap()
                }
                val out = ArrayList<TrackerLogic.UpNextItem>()
                for (show in tvTitles) {
                    val epFile = TrackerStoreDisk.readEpisode(appCtx, show.tmdbId)
                    if (epFile == null && show.watchedCount > 0) {
                        // Unknown set: keep the previous lines
                        // rather than drop a show on a corrupt
                        // file (the home filter still applies
                        // marked/overlay state to them).
                        previous[show.tmdbId]?.let { out.addAll(it) }
                        continue
                    }
                    val watched = epFile?.watched ?: emptySet()
                    val fetched = fetchSeasonDates(appCtx, show.tmdbId) { fetchedSoFar ->
                        TrackerLogic.allMissedEpisodes(watched, fetchedSoFar, today).isNotEmpty()
                    }
                    if (fetched == null) {
                        previous[show.tmdbId]?.let { out.addAll(it) }
                        continue
                    }
                    // v3.13: every passed unwatched episode, one
                    // missed.txt line each (same codec) — the
                    // home groups them into one card per show.
                    for ((s, e, date) in TrackerLogic.allMissedEpisodes(watched, fetched.second, today)) {
                        out.add(
                            TrackerLogic.UpNextItem(
                                tmdbId = show.tmdbId, title = show.title,
                                sub = "S$s E$e", dateIso = date,
                                poster = show.poster.ifBlank {
                                    missedPosterCache[
                                        com.mizan.launcher.core.LibraryLogic.posterKey("tv", show.tmdbId)
                                    ].orEmpty()
                                },
                                backdrop = show.backdrop,
                                overview = "", season = s, episode = e, type = "tv"
                            )
                        )
                    }
                }
                synchronized(lock) {
                    if (!TrackerStoreDisk.writeMissed(appCtx, out)) return@thread
                    TrackerStoreDisk.writeMissedMeta(
                        appCtx,
                        TrackerStoreDisk.MissedMeta(
                            updated = System.currentTimeMillis(),
                            // v3.13: the stamp counts SHOWS, not
                            // episode lines (out now holds one
                            // line per missed episode).
                            count = out.map { it.tmdbId }.toSet().size,
                            inputFingerprint = inputFp
                        )
                    )
                    TrackerClient.installStoreMemo(appCtx)
                }
                onMissedChanged?.invoke()
            } catch (e: Exception) {
                try { Prefs.noteError(appCtx, "libraryMissed", e) } catch (_: Exception) { }
            } finally {
                synchronized(this) { missedAllInFlight = false }
            }
        }
    }
}
