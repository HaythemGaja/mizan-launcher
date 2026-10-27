package com.mizan.launcher.data

import android.content.Context
import com.mizan.launcher.core.LibraryLogic
import com.mizan.launcher.core.TrackerLogic
import com.mizan.launcher.domain.AiredGate
import com.mizan.launcher.domain.EpisodeRef
import com.mizan.launcher.domain.HomeCard
import com.mizan.launcher.domain.IgnoredItem
import com.mizan.launcher.domain.MediaKind
import com.mizan.launcher.domain.PlanToWatchItem
import com.mizan.launcher.domain.TrackedMovie
import com.mizan.launcher.domain.TrackedShow
import com.mizan.launcher.domain.TrackerRepository
import com.mizan.launcher.domain.WatchProgress
import java.time.LocalDate

/**
 * Local-first tracker repository (MAP §5, post-v3.7: no provider
 * queries). Reads filesDir/tracker/ + the pending overlay; writes
 * go to the pending overlay only (optimistic) and retire
 * part-by-part as snapshots converge (core LibraryLogic prune).
 * Pending watched adds/removes are scoped PER SHOW
 * (v3.9 fix: pendingWatchedFor(tmdbId), never folded across shows).
 */
class TrackerRepositoryImpl(ctx: Context) : TrackerRepository {
    private val prefs = PrefsStore(ctx)
    private val disk = TrackerStoreDisk(ctx)
    private val today: String get() = LocalDate.now().toString()

    private fun pendingEntry(id: Long, type: String = "tv") =
        LibraryLogic.overlayEntry(prefs.pending(), type, id)

    override fun getWatchedEpisodes(showTmdbId: Long): Set<String> {
        val snap = disk.episodeFile(showTmdbId)?.watched ?: emptySet()
        val (adds, removes) = LibraryLogic.pendingWatchedFor(prefs.pending(), showTmdbId)
        return (snap + adds) - removes
    }

    private fun dismissedForYou(): Set<Pair<String, Long>> {
        // foryou_overlay: newline "type|id" lines; malformed lines skipped.
        val out = HashSet<Pair<String, Long>>()
        for (line in prefs.forYouOverlay().split('\n')) {
            val p = line.trim().split('|')
            if (p.size == 2) {
                val id = p[1].toLongOrNull() ?: continue
                out.add(p[0] to id)
            }
        }
        return out
    }

    override fun getTrackedShows(): List<TrackedShow> {
        val removed = prefs.pending().filter { it.removed }.map { it.type to it.tmdbId }.toSet()
        val added = prefs.pending().filter { it.addedTitle != null }
        val out = disk.titles().filter { it.type == "tv" && ("tv" to it.tmdbId) !in removed }
            .map {
                TrackedShow(it.tmdbId, it.title, it.poster.takeIf { p -> p.isNotBlank() },
                    it.backdrop.takeIf { b -> b.isNotBlank() }, "", null)
            }.toMutableList()
        for (a in added) {
            if (a.type == "tv" && out.none { it.tmdbId == a.tmdbId }) {
                out.add(TrackedShow(a.tmdbId, a.addedTitle ?: "", a.addedPoster.takeIf { it.isNotBlank() },
                    null, "", null))
            }
        }
        return out
    }

    override fun getTrackedMovies(): List<TrackedMovie> {
        val pending = prefs.pending()
        val removed = pending.filter { it.removed && it.type == "movie" }.map { it.tmdbId }.toSet()
        val out = disk.movies().filter { it.tmdbId !in removed }.map {
            val watched = pending.firstOrNull { p -> p.type == "movie" && p.tmdbId == it.tmdbId }?.movieWatched
                ?: it.watched
            TrackedMovie(it.tmdbId, it.title, it.poster.takeIf { p -> p.isNotBlank() }, null, "", null, watched)
        }.toMutableList()
        for (a in pending.filter { it.addedTitle != null && it.type == "movie" }) {
            if (out.none { it.tmdbId == a.tmdbId }) {
                out.add(TrackedMovie(a.tmdbId, a.addedTitle ?: "", a.addedPoster.takeIf { it.isNotBlank() },
                    null, "", null, a.movieWatched ?: false))
            }
        }
        return out
    }

    private fun upNextCard(u: TrackerLogic.UpNextItem): HomeCard = HomeCard(
        tmdbId = u.tmdbId, kind = MediaKind.SHOW, title = u.title,
        posterPath = u.poster.takeIf { it.isNotBlank() },
        backdropPath = u.backdrop.takeIf { it.isNotBlank() },
        nextSeason = u.season.takeIf { it > 0 }, nextEpisode = u.episode.takeIf { it > 0 },
        countdownLabel = TrackerLogic.countdownLabel(u.dateIso, today),
        progress = getProgress(u.tmdbId, MediaKind.SHOW),
    )

    override fun getUpNext(): List<HomeCard> = disk.upNext().map(::upNextCard)

    override fun getMissed(): List<HomeCard> {
        val stored = disk.missed()
        val items = if (stored.isNotEmpty()) stored else MissedWorker.computeMissed(prefs, disk)
        val filtered = TrackerLogic.missedRow(items, { u ->
            LibraryLogic.epKey(u.season, u.episode) in getWatchedEpisodes(u.tmdbId)
        }, today)
        // missedRow keeps most-overdue first; re-check per-show pending
        // scoping is inherent: isWatched above is computed per item tmdbId.
        return filtered.map { u -> upNextCard(u).copy(isMissed = true) }
    }

    override fun getContinueWatching(): List<HomeCard> {
        return disk.continueItems().map { c ->
            val next = disk.upNext().firstOrNull { it.tmdbId == c.tmdbId }
            val caught = c.total > 0 && c.watched >= c.total
            HomeCard(c.tmdbId, MediaKind.SHOW, c.title,
                posterPath = c.poster.takeIf { it.isNotBlank() },
                backdropPath = c.backdrop.takeIf { it.isNotBlank() },
                nextSeason = next?.season?.takeIf { it > 0 },
                nextEpisode = next?.episode?.takeIf { it > 0 },
                progress = WatchProgress(c.tmdbId, MediaKind.SHOW, c.watched, c.total,
                    next?.season?.takeIf { it > 0 }, next?.episode?.takeIf { it > 0 }, caught))
        }
        // Movies are not Continue rows in v3.9; they live in Library.
    }

    override fun getForYou(): List<HomeCard> {
        val tracked = disk.titles().map { it.tmdbId }.toSet()
        val dismissed = dismissedForYou()
        return disk.forYou().filter { it.id !in tracked && (it.type to it.id) !in dismissed }
            .map {
                HomeCard(it.id, if (it.type == "movie") MediaKind.MOVIE else MediaKind.SHOW,
                    it.title, posterPath = it.poster.takeIf { p -> p.isNotBlank() })
            }
    }

    override fun getPlanToWatch(): List<PlanToWatchItem> =
        disk.titles().filter { it.planned }.map {
            PlanToWatchItem(it.tmdbId, if (it.type == "movie") MediaKind.MOVIE else MediaKind.SHOW,
                it.title, it.poster.takeIf { p -> p.isNotBlank() })
        }

    override fun getIgnored(): List<IgnoredItem> {
        val dismissed = dismissedForYou()
        val out = ArrayList<IgnoredItem>()
        for (t in disk.titles()) {
            if (t.muted) out.add(IgnoredItem(t.tmdbId,
                if (t.type == "movie") MediaKind.MOVIE else MediaKind.SHOW,
                mutedNotifications = true))
        }
        for ((type, id) in dismissed) {
            out.add(IgnoredItem(id, if (type == "movie") MediaKind.MOVIE else MediaKind.SHOW,
                dismissedFromForYou = true))
        }
        return out
    }

    override fun getProgress(tmdbId: Long, kind: MediaKind): WatchProgress? {
        if (kind == MediaKind.MOVIE) {
            val m = disk.movies().firstOrNull { it.tmdbId == tmdbId } ?: return null
            return WatchProgress(tmdbId, kind, if (m.watched) 1 else 0, 1, null, null, m.watched)
        }
        val c = disk.continueItems().firstOrNull { it.tmdbId == tmdbId }
        val next = disk.upNext().firstOrNull { it.tmdbId == tmdbId }
        val title = disk.titles().firstOrNull { it.tmdbId == tmdbId }
        if (c == null && title == null) return null
        val watched = c?.watched ?: title?.watchedCount ?: 0
        val total = c?.total ?: 0
        return WatchProgress(tmdbId, kind, watched, total,
            next?.season?.takeIf { it > 0 }, next?.episode?.takeIf { it > 0 },
            total > 0 && watched >= total)
    }

    override fun getSeasonEpisodes(showTmdbId: Long, season: Int): List<EpisodeRef> {
        val dates = disk.seasonDates(showTmdbId).filter { it.first == season }.sortedBy { it.second }
        if (dates.isNotEmpty()) {
            return dates.map { EpisodeRef(showTmdbId, it.first, it.second, it.third) }
        }
        // Fallback: the show's Up Next episode when it is in this season.
        return disk.upNext().filter { it.tmdbId == showTmdbId && it.season == season }
            .map { EpisodeRef(showTmdbId, it.season, it.episode, it.dateIso) }
    }

    override fun getSeasons(showTmdbId: Long): List<Int> {
        val fromCache = disk.seasonDates(showTmdbId).map { it.first }.toSortedSet()
        if (fromCache.isNotEmpty()) return fromCache.toList()
        return disk.upNext().filter { it.tmdbId == showTmdbId && it.season > 0 }
            .map { it.season }.toSortedSet().toList()
    }

    override fun isMuted(tmdbId: Long, kind: MediaKind): Boolean {
        val pendingMuted = pendingEntry(tmdbId, if (kind == MediaKind.MOVIE) "movie" else "tv")?.muted
        if (pendingMuted != null) return pendingMuted
        val title = disk.titles().firstOrNull { it.tmdbId == tmdbId }
        if (title?.muted == true) return true
        return disk.episodeFile(tmdbId)?.muted == true
    }

    override fun toggleEpisode(episode: EpisodeRef) {
        val watched = LibraryLogic.epKey(episode.season, episode.episode) !in getWatchedEpisodes(episode.showTmdbId)
        markEpisodeWatched(episode, watched)
    }

    override fun markEpisodeWatched(episode: EpisodeRef, watched: Boolean) {
        // The unaired gate lives in the caller (AiredGate): this write
        // itself is gate-agnostic, matching the domain contract note.
        val now = System.currentTimeMillis()
        prefs.setPending(LibraryLogic.withEpisodeToggle(
            prefs.pending(), episode.showTmdbId, episode.season, episode.episode, watched, now))
        prefs.setLibraryLastChange(now)
        recomputeMissed(episode.showTmdbId)
    }

    override fun markSeasonWatched(showTmdbId: Long, season: Int, episodes: List<EpisodeRef>) {
        // f26a5f6: filter through AiredGate FIRST; write nothing if none aired.
        val aired = AiredGate.airedOnly(episodes)
        if (aired.isEmpty()) return
        val now = System.currentTimeMillis()
        prefs.setPending(LibraryLogic.withSeasonMark(
            prefs.pending(), showTmdbId, season, aired.map { it.episode }, now))
        prefs.setLibraryLastChange(now)
        recomputeMissed(showTmdbId)
    }

    override fun markMovieWatched(tmdbId: Long, watched: Boolean) {
        val now = System.currentTimeMillis()
        prefs.setPending(LibraryLogic.withMovieToggle(prefs.pending(), tmdbId, watched, now))
        prefs.setLibraryLastChange(now)
    }

    override fun addToLibrary(tmdbId: Long, kind: MediaKind) {
        val type = if (kind == MediaKind.MOVIE) "movie" else "tv"
        val now = System.currentTimeMillis()
        prefs.setPending(LibraryLogic.withAdded(prefs.pending(), type, tmdbId, "", "", "", now))
        prefs.setLibraryLastChange(now)
    }

    // Phase 2D enriched add (data gap (c)): the pending entry
    // carries the TMDB search hit's title + poster instead of
    // empty strings until a snapshot lands.
    override fun addToLibrary(tmdbId: Long, kind: MediaKind, title: String, posterPath: String?) {
        val type = if (kind == MediaKind.MOVIE) "movie" else "tv"
        val now = System.currentTimeMillis()
        prefs.setPending(LibraryLogic.withAdded(prefs.pending(), type, tmdbId,
            title, posterPath ?: "", "", now))
        prefs.setLibraryLastChange(now)
    }

    override fun removeFromLibrary(tmdbId: Long, kind: MediaKind) {
        val type = if (kind == MediaKind.MOVIE) "movie" else "tv"
        val now = System.currentTimeMillis()
        prefs.setPending(LibraryLogic.withRemoved(prefs.pending(), type, tmdbId, now))
        prefs.setLibraryLastChange(now)
    }

    override fun addToPlanToWatch(item: PlanToWatchItem) {
        // Plan-to-watch is a title-line flag in the store; the pending
        // overlay carries the add, and the store writer (integration
        // worker) materialises `planned` on convergence. Here we keep
        // the optimistic add visible via the overlay entry.
        val type = if (item.kind == MediaKind.MOVIE) "movie" else "tv"
        val now = System.currentTimeMillis()
        prefs.setPending(LibraryLogic.withAdded(
            prefs.pending(), type, item.tmdbId, item.title,
            item.posterPath ?: "", "", now))
        prefs.setLibraryLastChange(now)
    }

    override fun dismissForYou(tmdbId: Long, kind: MediaKind) {
        val type = if (kind == MediaKind.MOVIE) "movie" else "tv"
        val cur = prefs.forYouOverlay()
        val line = "$type|$tmdbId"
        if (line !in cur.split('\n')) {
            prefs.setForYouOverlay(if (cur.isBlank()) line else "$cur\n$line")
        }
    }

    override fun setMuted(tmdbId: Long, kind: MediaKind, muted: Boolean) {
        val type = if (kind == MediaKind.MOVIE) "movie" else "tv"
        prefs.setPending(LibraryLogic.withMuted(
            prefs.pending(), type, tmdbId, muted, System.currentTimeMillis()))
    }

    override fun recomputeMissed(showTmdbId: Long?) {
        MissedWorker.recompute(prefs, disk, showTmdbId)
    }
}
