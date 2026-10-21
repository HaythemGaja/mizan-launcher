package com.mizan.launcher.data

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.mizan.launcher.core.TrackerLogic
import kotlin.concurrent.thread

/**
 * Launcher side of the TVShowsMoviesTracker sync bridge (v1.8).
 * Contract: tvshows-movies-tracker/LAUNCHER_SYNC.md — a read-only
 * ContentProvider (authority [AUTHORITY], guarded by the normal-level
 * [PERMISSION]) serving JSON snapshots, plus a `call("mark_watched")`
 * write-back that QUEUES an action the tracker applies at its next
 * boot. The tracker stays the single writer of watch progress.
 *
 * Everything here is FAIL-OPEN: tracker not installed, provider
 * missing, SecurityException, empty or malformed snapshots — all of
 * it reads as "no data" and the Tracker view falls back to the plain
 * clock/weather home. Queries run on a background thread only; the
 * view renders from the last-good cache in prefs, refreshed on
 * launcher resume when older than [REFRESH_MIN_AGE_MS].
 */
object TrackerClient {

    const val TRACKER_PKG = "com.haythem.tvtracker"
    const val TRACKER_LABEL = "TV Shows & Movies Tracker"
    const val AUTHORITY = "com.haythem.tvtracker.launcherdata"
    const val PERMISSION = "com.haythem.tvtracker.permission.LAUNCHER_READ"

    /** Resume-time refresh throttle: snapshots younger than this are reused. */
    const val REFRESH_MIN_AGE_MS = 60_000L

    private val BASE: Uri = Uri.parse("content://$AUTHORITY")

    /** One consistent read of all four snapshots (+ provider stamps). */
    data class Snapshots(
        val upNext: List<TrackerLogic.UpNextItem>,
        val cont: List<TrackerLogic.ContinueItem>,
        val movies: List<TrackerLogic.MovieItem>,
        val tracked: List<TrackerLogic.TrackedShow>,
        /** The provider's `updated` stamp (widget-refresh time), 0 if never. */
        val updated: Long,
        /** When the launcher last fetched (epoch ms), 0 if never. */
        val fetchedAt: Long
    ) {
        /** Any usable tracker content at all (before marked filtering). */
        val hasData: Boolean
            get() = upNext.isNotEmpty() || cont.isNotEmpty() ||
                movies.isNotEmpty() || tracked.isNotEmpty()

        companion object {
            val EMPTY = Snapshots(emptyList(), emptyList(), emptyList(), emptyList(), 0L, 0L)
        }
    }

    // The cache lives in the launcher's one prefs file, under global
    // (not per-profile) keys: tracker data belongs to the tracker,
    // not to a launcher profile. Profiles differ only in their
    // marked-set + hero index (see Prefs).
    private const val K_UPNEXT = "tracker_cache_upnext"
    private const val K_CONTINUE = "tracker_cache_continue"
    private const val K_MOVIES = "tracker_cache_movies"
    private const val K_TRACKED = "tracker_cache_tracked"
    private const val K_UPDATED = "tracker_cache_updated"
    private const val K_FETCHED = "tracker_cache_fetched"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences("mizan_launcher", Context.MODE_PRIVATE)

    /** The last-good snapshots, parsed. Never blocks on the provider. */
    fun cached(ctx: Context): Snapshots {
        return try {
            val p = prefs(ctx)
            Snapshots(
                upNext = TrackerLogic.parseUpNext(p.getString(K_UPNEXT, null)),
                cont = TrackerLogic.parseContinue(p.getString(K_CONTINUE, null)),
                movies = TrackerLogic.parseMovies(p.getString(K_MOVIES, null)),
                tracked = TrackerLogic.parseTrackedShows(p.getString(K_TRACKED, null)),
                updated = p.getLong(K_UPDATED, 0L),
                fetchedAt = p.getLong(K_FETCHED, 0L)
            )
        } catch (e: Exception) {
            Snapshots.EMPTY
        }
    }

    /** Whether the tracker app is installed (package visibility via <queries>). */
    fun isInstalled(ctx: Context): Boolean {
        return try {
            ctx.packageManager.getPackageInfo(TRACKER_PKG, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    @Volatile
    private var inFlight = false

    /**
     * Refresh the cache from the provider on a background thread when
     * the cache is older than [REFRESH_MIN_AGE_MS] (or [force]).
     * [onChanged] runs on the main thread ONLY when the snapshots
     * actually changed, so callers can rebuild without looping.
     */
    fun refreshAsync(ctx: Context, force: Boolean = false, onChanged: () -> Unit) {
        val appCtx = ctx.applicationContext
        val before = try {
            val p = prefs(appCtx)
            listOf(
                p.getString(K_UPNEXT, null), p.getString(K_CONTINUE, null),
                p.getString(K_MOVIES, null), p.getString(K_TRACKED, null)
            )
        } catch (e: Exception) {
            null
        }
        val fetchedAt = try {
            prefs(appCtx).getLong(K_FETCHED, 0L)
        } catch (e: Exception) {
            0L
        }
        if (inFlight) return
        if (!force && fetchedAt > 0 &&
            System.currentTimeMillis() - fetchedAt < REFRESH_MIN_AGE_MS
        ) {
            return
        }
        inFlight = true
        thread(name = "mizan-tracker-fetch") {
            try {
                val up = queryPath(appCtx, "upnext")
                val cont = queryPath(appCtx, "continue")
                val mov = queryPath(appCtx, "movies")
                val trk = queryPath(appCtx, "tracked")
                val now = System.currentTimeMillis()
                val editor = prefs(appCtx).edit()
                editor.putLong(K_FETCHED, now)
                var changed = false
                if (up != null || cont != null || mov != null || trk != null) {
                    // At least one path answered: store what we got
                    // (a path that failed keeps its last-good value).
                    var updated = 0L
                    if (up != null) { editor.putString(K_UPNEXT, up.first); updated = maxOf(updated, up.second) }
                    if (cont != null) { editor.putString(K_CONTINUE, cont.first); updated = maxOf(updated, cont.second) }
                    if (mov != null) { editor.putString(K_MOVIES, mov.first); updated = maxOf(updated, mov.second) }
                    if (trk != null) { editor.putString(K_TRACKED, trk.first); updated = maxOf(updated, trk.second) }
                    if (updated > 0) editor.putLong(K_UPDATED, updated)
                    val after = listOf(
                        up?.first ?: before?.get(0), cont?.first ?: before?.get(1),
                        mov?.first ?: before?.get(2), trk?.first ?: before?.get(3)
                    )
                    changed = after != before
                }
                editor.apply()
                if (changed) {
                    Handler(Looper.getMainLooper()).post {
                        try { onChanged() } catch (e: Exception) {
                            // A caller's rebuild must never kill the poster.
                        }
                    }
                }
            } catch (e: Exception) {
                Prefs.noteError(appCtx, "trackerFetch", e)
            } finally {
                inFlight = false
            }
        }
    }

    /** One provider path → (json, updated); null on ANY failure (fail-open). */
    private fun queryPath(ctx: Context, path: String): Pair<String, Long>? {
        return try {
            ctx.contentResolver.query(BASE.buildUpon().appendPath(path).build(),
                null, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return null
                val jsonIdx = c.getColumnIndex("json")
                val updIdx = c.getColumnIndex("updated")
                val json = if (jsonIdx >= 0) c.getString(jsonIdx) else null
                val updated = if (updIdx >= 0) c.getLong(updIdx) else 0L
                (json ?: "[]") to updated
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Queue a mark-watched action with the tracker (fire-and-forget
     * on a background thread). The tracker applies it at its next
     * boot; the launcher hides the item locally via its marked-set in
     * the meantime. Movies pass season/episode 0 (per contract).
     */
    fun markWatched(ctx: Context, tmdbId: Long, type: String, season: Int, episode: Int) {
        val appCtx = ctx.applicationContext
        thread(name = "mizan-tracker-mark") {
            try {
                val extras = Bundle()
                extras.putString("type", if (type == "movie") "movie" else "tv")
                extras.putInt("season", season)
                extras.putInt("episode", episode)
                appCtx.contentResolver.call(BASE, "mark_watched", tmdbId.toString(), extras)
            } catch (e: Exception) {
                Prefs.noteError(appCtx, "trackerMark", e)
            }
        }
    }
}
