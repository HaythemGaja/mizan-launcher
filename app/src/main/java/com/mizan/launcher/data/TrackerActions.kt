package com.mizan.launcher.data

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.widget.Toast
import com.mizan.launcher.core.LibraryLogic
import com.mizan.launcher.core.TrackerLogic

/**
 * The launcher's library WRITE path (v2.1 queue funnel;
 * v3.7 local funnel) — one funnel for every write from
 * the Library, details and TMDB-search screens (plus the
 * For You "Add to my list"). Each write does three things:
 *   1. writes it to the launcher-owned store
 *      (data/LibraryLocal — the launcher IS the tracker
 *      now; there is no tracker app to queue to),
 *   2. mirrors it into this profile's generalized pending
 *      overlay (core/LibraryLogic) so every surface
 *      renders it instantly (the overlay is written
 *      FIRST, synchronously, so the screen never waits
 *      on the store worker),
 *   3. where the write is a watched-mark, ALSO updates the v1.8
 *      marked-set, so the home view's Up Next / hero
 *      hiding behaves exactly as if the mark had been tapped there
 *      (and an unwatch un-hides). The marked entry retires
 *      when the store proves it (home prepare prunes with
 *      a store-backed stamp — see MainActivity).
 * Nothing here blocks; the store calls are fire-and-forget
 * workers, and the overlay already rendered the result.
 */
object TrackerActions {

    private fun overlay(prefs: Prefs): List<LibraryLogic.PendingTitle> =
        LibraryLogic.decodePending(prefs.pendingOverlay)

    private fun save(prefs: Prefs, entries: List<LibraryLogic.PendingTitle>) {
        prefs.pendingOverlay = LibraryLogic.encodePending(entries)
    }

    // ------------------------------------------------------------ episode writes

    /** Toggle one episode's watched state (details checklist). */
    fun toggleEpisode(
        activity: Activity,
        prefs: Prefs,
        tmdbId: Long,
        season: Int,
        episode: Int,
        watched: Boolean
    ) {
        val now = System.currentTimeMillis()
        save(
            prefs,
            LibraryLogic.withEpisodeToggle(
                overlay(prefs), tmdbId, season, episode, watched, now
            )
        )
        if (watched) {
            LibraryLocal.queueEpisodeToggle(activity, prefs, tmdbId, season, episode, true)
            addMarked(prefs, tmdbId, "tv", season, episode, now)
        } else {
            LibraryLocal.queueEpisodeToggle(activity, prefs, tmdbId, season, episode, false)
            removeMarked(prefs, tmdbId, "tv", season, episode)
        }
    }

    /** Mark a whole season watched from its TMDB episode list. */
    fun markSeason(
        activity: Activity,
        prefs: Prefs,
        tmdbId: Long,
        season: Int,
        episodes: List<LibraryLogic.EpisodeInfo>
    ) {
        // Unaired episodes are never marked: only episodes that
        // have aired (or have no known date) count.
        val aired = episodes.filter { hasAired(it.airDate) }
        if (aired.isEmpty()) return
        val now = System.currentTimeMillis()
        save(
            prefs,
            LibraryLogic.withSeasonMark(
                overlay(prefs), tmdbId, season, aired.map { it.number }, now
            )
        )
        // The CSV was the tracker's drain input; the
        // local writer takes the episode numbers directly.
        LibraryLocal.queueSeasonMark(
            activity, prefs, tmdbId, season, aired.map { it.number }
        )
        for (ep in aired) {
            if (ep.number > 0) addMarked(prefs, tmdbId, "tv", season, ep.number, now)
        }
    }

    /**
     * Unaired gate, shared with the episode checklist: an
     * episode counts as aired when its TMDB air date is
     * blank (unknown — fail open, never invent a date) or
     * on/before the device's local today. ISO yyyy-MM-dd
     * strings compare chronologically.
     */
    private fun hasAired(airDate: String): Boolean {
        val d = airDate.trim()
        if (d.isBlank()) return true
        if (!d.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) return true
        return d <= java.time.LocalDate.now().toString()
    }

    // ------------------------------------------------------------ movie writes

    /** Toggle a movie's watched state (details + library menu). */
    fun toggleMovie(activity: Activity, prefs: Prefs, tmdbId: Long, watched: Boolean) {
        val now = System.currentTimeMillis()
        save(prefs, LibraryLogic.withMovieToggle(overlay(prefs), tmdbId, watched, now))
        LibraryLocal.queueMovieToggle(activity, prefs, tmdbId, watched)
        if (watched) {
            addMarked(prefs, tmdbId, "movie", 0, 0, now)
        } else {
            removeMarked(prefs, tmdbId, "movie", 0, 0)
        }
    }

    // ------------------------------------------------------------ title writes

    /** Add a title (TMDB search / For You): queued + pending in Library. */
    fun addTitle(
        activity: Activity,
        prefs: Prefs,
        tmdbId: Long,
        type: String,
        title: String,
        poster: String,
        releaseDate: String,
        onDone: (() -> Unit)? = null
    ) {
        save(
            prefs,
            LibraryLogic.withAdded(
                overlay(prefs), type, tmdbId, title, poster, releaseDate,
                System.currentTimeMillis()
            )
        )
        LibraryLocal.queueAddTitle(activity, prefs, tmdbId, type, title, poster, releaseDate, onDone)
    }

    /** Remove a title from the library (queued + hidden at once). */
    fun removeTitle(activity: Activity, prefs: Prefs, tmdbId: Long, type: String) {
        save(
            prefs,
            LibraryLogic.withRemoved(
                overlay(prefs), type, tmdbId, System.currentTimeMillis()
            )
        )
        // Its marked entries are meaningless once the title is gone.
        val kept = TrackerLogic.decodeMarked(prefs.trackerMarked)
            .filter { !(it.tmdbId == tmdbId && it.type == type) }
        prefs.trackerMarked = TrackerLogic.encodeMarked(kept)
        LibraryLocal.queueRemoveTitle(activity, prefs, tmdbId, type)
    }

    /** Mute/unmute a title's notifications (queued + rendered at once). */
    fun setMuted(
        activity: Activity,
        prefs: Prefs,
        tmdbId: Long,
        type: String,
        muted: Boolean
    ) {
        save(
            prefs,
            LibraryLogic.withMuted(
                overlay(prefs), type, tmdbId, muted, System.currentTimeMillis()
            )
        )
        LibraryLocal.queueMuted(activity, prefs, tmdbId, type, muted)
    }

    // ------------------------------------------------------------ marked-set sync

    private fun addMarked(prefs: Prefs, tmdbId: Long, type: String, season: Int, episode: Int, at: Long) {
        val entry = TrackerLogic.MarkedEntry(tmdbId, type, season, episode, at)
        val current = TrackerLogic.decodeMarked(prefs.trackerMarked)
        prefs.trackerMarked = TrackerLogic.encodeMarked(
            TrackerLogic.withMarked(current, entry)
        )
    }

    private fun removeMarked(prefs: Prefs, tmdbId: Long, type: String, season: Int, episode: Int) {
        val key = TrackerLogic.markedKey(tmdbId, type, season, episode)
        val current = TrackerLogic.decodeMarked(prefs.trackerMarked)
        prefs.trackerMarked = TrackerLogic.encodeMarked(
            current.filter { it.key() != key }
        )
    }

    /**
     * v3.7: home mark-watched (Up Next / Missed / hero
     * menus). Home marks never touched the pending
     * overlay — only the marked set (which hides the
     * card at once) — plus the store write, which
     * removes the upnext line and advances the show.
     * No overlay entry is created here, matching the
     * pre-v3.7 home behaviour exactly.
     */
    fun markWatchedHome(
        activity: Activity,
        prefs: Prefs,
        tmdbId: Long,
        type: String,
        season: Int,
        episode: Int
    ) {
        val now = System.currentTimeMillis()
        if (type == "movie") {
            LibraryLocal.queueMovieToggle(activity, prefs, tmdbId, true)
        } else {
            LibraryLocal.queueEpisodeToggle(activity, prefs, tmdbId, season, episode, true)
        }
        addMarked(prefs, tmdbId, type, season, episode, now)
    }

    /** v3.7: dismiss a For You pick (local foryou-section write; the overlay hides it at once in the caller). */
    fun dismissForYou(activity: Activity, tmdbId: Long, type: String) {
        LibraryLocal.queueDismissForYou(activity, tmdbId, type)
    }

    /** Confirm-then-remove dialog shared by Library + details. */
    fun confirmRemove(
        activity: Activity,
        prefs: Prefs,
        tmdbId: Long,
        type: String,
        title: String,
        onRemoved: () -> Unit
    ) {
        AlertDialog.Builder(activity)
            .setTitle("Remove “$title”?")
            .setMessage(
                "It leaves your library and its watch progress is cleared."
            )
            .setPositiveButton("Remove") { _, _ ->
                removeTitle(activity, prefs, tmdbId, type)
                Toast.makeText(
                    activity, "Removed from your library",
                    Toast.LENGTH_SHORT
                ).show()
                onRemoved()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Build the details-screen intent for a title. */
    fun detailsIntent(
        activity: Activity,
        tmdbId: Long,
        type: String,
        title: String,
        poster: String
    ): Intent {
        val intent = Intent(activity, com.mizan.launcher.ui.DetailsActivity::class.java)
        intent.putExtra(com.mizan.launcher.ui.DetailsActivity.EXTRA_ID, tmdbId)
        intent.putExtra(com.mizan.launcher.ui.DetailsActivity.EXTRA_TYPE, type)
        intent.putExtra(com.mizan.launcher.ui.DetailsActivity.EXTRA_TITLE, title)
        intent.putExtra(com.mizan.launcher.ui.DetailsActivity.EXTRA_POSTER, poster)
        return intent
    }
}
