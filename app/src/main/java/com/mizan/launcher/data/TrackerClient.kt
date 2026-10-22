package com.mizan.launcher.data

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.mizan.launcher.core.TrackerLogic
import com.mizan.launcher.core.TrackerStatus
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
 * view renders from the last-good cache in prefs.
 *
 * v1.9 hardening (Haythem's TV: tracker fine, launcher showed
 * NOTHING, silently): every fetch is now CLASSIFIED
 * (core/TrackerStatus.Kind) and the kind is persisted — the
 * diagnostics `tracker:` line and the Tracker view's fallback line
 * are worded from it, so no failure is silent anymore. And the
 * resume throttle stamps ONLY a successful fetch WITH data
 * ([K_LAST_OK]): v1.8 stamped the attempt time even when every
 * query failed, so one permission-blocked first fetch poisoned
 * retries for a minute at a time. After an error or an empty
 * result, EVERY resume retries.
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
    /** v1.9: the classified outcome of the last fetch (TrackerStatus.Kind name). */
    private const val K_STATE = "tracker_state"
    /** v1.9: last fetch that returned DATA — the only throttle stamp. */
    private const val K_LAST_OK = "tracker_last_ok"

    /** The persisted fetch state, for diagnostics + the fallback line. */
    data class Status(
        val kind: TrackerStatus.Kind,
        val installed: Boolean
    )

    /**
     * The last fetch's classified state. Before any fetch has run,
     * the state is derived: not installed → NOT_INSTALLED, installed
     * with an empty cache → EMPTY, installed with cached data → NONE.
     */
    fun status(ctx: Context): Status {
        val installed = isInstalled(ctx)
        val stored = try {
            prefs(ctx).getString(K_STATE, null)
        } catch (e: Exception) {
            null
        }
        val kind = when {
            stored != null -> try {
                TrackerStatus.Kind.valueOf(stored)
            } catch (e: Exception) {
                null
            }
            !installed -> TrackerStatus.Kind.NOT_INSTALLED
            cached(ctx).hasData -> TrackerStatus.Kind.NONE
            else -> TrackerStatus.Kind.EMPTY
        } ?: if (!installed) {
            TrackerStatus.Kind.NOT_INSTALLED
        } else if (cached(ctx).hasData) {
            TrackerStatus.Kind.NONE
        } else {
            TrackerStatus.Kind.EMPTY
        }
        return Status(kind, installed)
    }

    private fun saveKind(ctx: Context, kind: TrackerStatus.Kind) {
        try {
            prefs(ctx).edit().putString(K_STATE, kind.name).apply()
        } catch (e: Exception) {
            // State is diagnostics bookkeeping; never break a fetch.
        }
    }

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
     * Refresh the cache from the provider on a background thread.
     * The [REFRESH_MIN_AGE_MS] throttle applies ONLY after a
     * successful fetch WITH data (v1.9): after an error or an empty
     * result every call retries, so a transient or permission state
     * can never strand the view on a stale "nothing". [onChanged]
     * runs on the main thread when the snapshots changed OR the
     * classified state changed (the fallback wording depends on it).
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
        if (inFlight) return
        val lastOk = try {
            prefs(appCtx).getLong(K_LAST_OK, 0L)
        } catch (e: Exception) {
            0L
        }
        if (!force && lastOk > 0 &&
            System.currentTimeMillis() - lastOk < REFRESH_MIN_AGE_MS
        ) {
            return
        }
        val kindBefore = status(appCtx).kind
        inFlight = true
        thread(name = "mizan-tracker-fetch") {
            try {
                val results = listOf(
                    queryPath(appCtx, "upnext"),
                    queryPath(appCtx, "continue"),
                    queryPath(appCtx, "movies"),
                    queryPath(appCtx, "tracked")
                )
                val now = System.currentTimeMillis()
                val editor = prefs(appCtx).edit()
                editor.putLong(K_FETCHED, now)
                val keys = listOf(K_UPNEXT, K_CONTINUE, K_MOVIES, K_TRACKED)
                var updated = 0L
                var anyAnswered = false
                var security = false
                for ((i, r) in results.withIndex()) {
                    if (r.security) security = true
                    val json = r.json ?: continue
                    anyAnswered = true
                    editor.putString(keys[i], json)
                    updated = maxOf(updated, r.updated)
                }
                if (updated > 0) editor.putLong(K_UPDATED, updated)
                editor.apply()
                val snap = cached(appCtx)
                val kind = TrackerStatus.classify(
                    installed = isInstalled(appCtx),
                    anyAnswered = anyAnswered,
                    securityError = security,
                    hasData = snap.hasData
                )
                saveKind(appCtx, kind)
                // The throttle stamp is earned only by data (v1.9).
                if (snap.hasData) {
                    try {
                        prefs(appCtx).edit().putLong(K_LAST_OK, now).apply()
                    } catch (e: Exception) {
                        // Bookkeeping only.
                    }
                }
                val after = listOf(
                    results[0].json ?: before?.get(0), results[1].json ?: before?.get(1),
                    results[2].json ?: before?.get(2), results[3].json ?: before?.get(3)
                )
                if (after != before || kind != kindBefore) {
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

    /** One provider path's outcome: the JSON when it answered, plus
     * whether the failure was a SecurityException (v1.9 classification). */
    private data class PathResult(
        val json: String?,
        val updated: Long,
        val security: Boolean
    )

    /** One provider path → its [PathResult]; never throws (fail-open). */
    private fun queryPath(ctx: Context, path: String): PathResult {
        return try {
            val got = ctx.contentResolver.query(BASE.buildUpon().appendPath(path).build(),
                null, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use null
                val jsonIdx = c.getColumnIndex("json")
                val updIdx = c.getColumnIndex("updated")
                val json = if (jsonIdx >= 0) c.getString(jsonIdx) else null
                val updated = if (updIdx >= 0) c.getLong(updIdx) else 0L
                (json ?: "[]") to updated
            }
            PathResult(got?.first, got?.second ?: 0L, security = false)
        } catch (e: SecurityException) {
            PathResult(null, 0L, security = true)
        } catch (e: Exception) {
            PathResult(null, 0L, security = false)
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
            } catch (e: SecurityException) {
                // The read permission is missing, so writes are too —
                // record the state the diagnostics line reports (v1.9).
                saveKind(appCtx, TrackerStatus.Kind.PERMISSION)
                Prefs.noteError(appCtx, "trackerMark", e)
            } catch (e: Exception) {
                Prefs.noteError(appCtx, "trackerMark", e)
            }
        }
    }
}
