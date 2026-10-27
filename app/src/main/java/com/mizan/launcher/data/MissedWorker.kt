package com.mizan.launcher.data

import com.mizan.launcher.core.LibraryLogic
import com.mizan.launcher.core.TrackerLogic
import java.time.LocalDate

/**
 * Missed worker (MAP §2): recomputes the missed list per show and
 * for all shows, kicked on local changes. Purely local: season air
 * dates come from filesDir/tracker/missed_cache/ (populated from
 * TMDB season payloads by the tracker-refresh worker); watched
 * state is the episode file + the PER-SHOW pending overlay.
 * No ui/app dependency; drivable from any thread.
 */
object MissedWorker {
    fun computeMissed(prefs: PrefsStore, disk: TrackerStoreDisk): List<TrackerLogic.UpNextItem> {
        val today = LocalDate.now().toString()
        val out = ArrayList<TrackerLogic.UpNextItem>()
        val titles = disk.titles().filter { it.type == "tv" }
        for (t in titles) {
            val watched = run {
                val snap = disk.episodeFile(t.tmdbId)?.watched ?: emptySet()
                val (adds, removes) = LibraryLogic.pendingWatchedFor(prefs.pending(), t.tmdbId)
                (snap + adds) - removes
            }
            for ((s, e, date) in disk.seasonDates(t.tmdbId)) {
                if (date.isBlank() || date >= today) continue // missed = strictly past
                if (LibraryLogic.epKey(s, e) in watched) continue
                out.add(TrackerLogic.UpNextItem(
                    tmdbId = t.tmdbId, title = t.title, sub = "S$s E$e",
                    dateIso = date, poster = t.poster, backdrop = t.backdrop,
                    overview = "", season = s, episode = e, type = "tv"))
            }
        }
        return out.sortedWith(compareBy<TrackerLogic.UpNextItem> { it.dateIso }.thenBy { it.title.lowercase() })
    }

    /** Recompute (all shows; the per-show argument scopes the kick,
     *  the file itself is always the whole missed list) and persist. */
    fun recompute(prefs: PrefsStore, disk: TrackerStoreDisk, showTmdbId: Long? = null) {
        try {
            disk.writeMissed(computeMissed(prefs, disk))
        } catch (ignored: Throwable) { }
    }

    /** One-show view without touching the file (for tests / callers). */
    fun computeForShow(prefs: PrefsStore, disk: TrackerStoreDisk, showTmdbId: Long): List<TrackerLogic.UpNextItem> =
        computeMissed(prefs, disk).filter { it.tmdbId == showTmdbId }
}
