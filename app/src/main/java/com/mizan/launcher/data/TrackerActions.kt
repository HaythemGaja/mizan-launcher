package com.mizan.launcher.data

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.widget.Toast
import com.mizan.launcher.core.LibraryLogic
import com.mizan.launcher.core.TrackerLogic
import com.mizan.launcher.ui.ArrowPinDialog

/**
 * The launcher's tracker WRITE path (v2.1) — one funnel for every
 * queued action from the Library, details and TMDB-search screens
 * (plus the For You "Add to my list"). Each write does three things:
 *   1. queues the action with the tracker (data/TrackerClient —
 *      the tracker applies it at its next boot; it stays the
 *      single writer of watch progress),
 *   2. mirrors it into this profile's generalized pending overlay
 *      (core/LibraryLogic) so every surface renders it instantly,
 *   3. where the write is a watched-mark, ALSO updates the v1.8
 *      marked-set, so the home view's Up Next / hero
 *      hiding behaves exactly as if the mark had been tapped there
 *      (and an unwatch un-hides).
 * Nothing here blocks; the client calls are fire-and-forget.
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
            TrackerClient.markWatched(activity, tmdbId, "tv", season, episode)
            addMarked(prefs, tmdbId, "tv", season, episode, now)
        } else {
            TrackerClient.unwatch(activity, tmdbId, "tv", season, episode)
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
        val csv = LibraryLogic.seasonCsv(episodes)
        if (csv.isEmpty()) return
        val now = System.currentTimeMillis()
        save(
            prefs,
            LibraryLogic.withSeasonMark(
                overlay(prefs), tmdbId, season, episodes.map { it.number }, now
            )
        )
        TrackerClient.markSeason(activity, tmdbId, season, csv)
        for (ep in episodes) {
            if (ep.number > 0) addMarked(prefs, tmdbId, "tv", season, ep.number, now)
        }
    }

    // ------------------------------------------------------------ movie writes

    /** Toggle a movie's watched state (details + library menu). */
    fun toggleMovie(activity: Activity, prefs: Prefs, tmdbId: Long, watched: Boolean) {
        val now = System.currentTimeMillis()
        save(prefs, LibraryLogic.withMovieToggle(overlay(prefs), tmdbId, watched, now))
        if (watched) {
            TrackerClient.markWatched(activity, tmdbId, "movie", 0, 0)
            addMarked(prefs, tmdbId, "movie", 0, 0, now)
        } else {
            TrackerClient.unwatch(activity, tmdbId, "movie", 0, 0)
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
        releaseDate: String
    ) {
        save(
            prefs,
            LibraryLogic.withAdded(
                overlay(prefs), type, tmdbId, title, poster, releaseDate,
                System.currentTimeMillis()
            )
        )
        TrackerClient.addTitle(activity, tmdbId, type, title, poster, releaseDate)
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
        TrackerClient.removeTitle(activity, tmdbId, type)
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
        TrackerClient.setMuted(activity, tmdbId, type, muted)
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

    // ------------------------------------------------------------ open the tracker

    /**
     * Open the tracker app from a v2.1 screen, through the same
     * arrow-lock gate the Search screen uses (the tracker is a
     * launchable app; if this profile locked it, the lock applies).
     * Absent tracker → toast.
     */
    fun openTrackerGated(activity: Activity, prefs: Prefs, accent: Int) {
        val doOpen: () -> Unit = {
            try {
                val intent = AppLoader.launchIntent(
                    activity.packageManager, TrackerClient.TRACKER_PKG
                )
                if (intent == null) {
                    Toast.makeText(
                        activity, "TV Shows & Movies Tracker isn't installed",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    prefs.recordLaunch(TrackerClient.TRACKER_PKG)
                    activity.startActivity(intent)
                }
            } catch (e: Exception) {
                Prefs.noteError(activity, "v21OpenTracker", e)
                Toast.makeText(activity, "Couldn't open the tracker", Toast.LENGTH_SHORT).show()
            }
        }
        if (!prefs.lockedApps.contains(TrackerClient.TRACKER_PKG)) {
            doOpen()
            return
        }
        val stored = prefs.arrowPin
        if (stored.isEmpty()) {
            ArrowPinDialog.showSetup(
                activity = activity,
                title = "Set an arrow pattern to lock TV Shows & Movies Tracker",
                accent = accent,
                onDone = { seq ->
                    prefs.arrowPin = seq
                    Toast.makeText(activity, "Arrow lock set", Toast.LENGTH_SHORT).show()
                    doOpen()
                }
            )
            return
        }
        ArrowPinDialog.showUnlock(
            activity = activity,
            title = "TV Shows & Movies Tracker is locked",
            accent = accent,
            storedEncoded = stored,
            onSuccess = { doOpen() },
            onWrongAttempt = { prefs.arrowAttempts = prefs.arrowAttempts + 1 }
        )
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
                "It leaves your library and its watch progress is cleared. " +
                    "This applies next time the tracker app opens."
            )
            .setPositiveButton("Remove") { _, _ ->
                removeTitle(activity, prefs, tmdbId, type)
                Toast.makeText(
                    activity, "Removed — applies next time the tracker opens",
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
