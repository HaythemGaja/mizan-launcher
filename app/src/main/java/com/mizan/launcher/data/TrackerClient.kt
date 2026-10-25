package com.mizan.launcher.data

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.mizan.launcher.core.HomeCache
import com.mizan.launcher.core.TrackerLogic
import com.mizan.launcher.core.TrackerSlim
import com.mizan.launcher.core.TrackerStatus
import java.io.File
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

    /** One consistent read of all the snapshots (+ provider stamps). */
    data class Snapshots(
        val upNext: List<TrackerLogic.UpNextItem>,
        val cont: List<TrackerLogic.ContinueItem>,
        val movies: List<TrackerLogic.MovieItem>,
        val tracked: List<TrackerLogic.TrackedShow>,
        /** v2.0: the /foryou picks (tracker ≥ 1.14.4; empty on older). */
        val forYou: List<TrackerLogic.ForYouPick> = emptyList(),
        /**
         * v2.1: the RAW /tracked JSON — the Library/details screens
         * need watched episode SETS + muted flags, which the parsed
         * [tracked] (watched counts, for search) doesn't carry.
         * Parsed by core/LibraryLogic.parseLibShows at the call site.
         */
        val trackedJson: String = "",
        /** The provider's `updated` stamp (widget-refresh time), 0 if never. */
        val updated: Long,
        /** The /foryou mirror's own `updated` stamp (overlay pruning), 0 if never. */
        val forYouUpdated: Long = 0L,
        /** When the launcher last fetched (epoch ms), 0 if never. */
        val fetchedAt: Long
    ) {
        /** Any usable tracker content at all (before marked filtering). */
        val hasData: Boolean
            get() = upNext.isNotEmpty() || cont.isNotEmpty() ||
                movies.isNotEmpty() || tracked.isNotEmpty()

        companion object {
            val EMPTY = Snapshots(
                upNext = emptyList(), cont = emptyList(), movies = emptyList(),
                tracked = emptyList(), updated = 0L, fetchedAt = 0L
            )
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
    private const val K_FORYOU = "tracker_cache_foryou"
    private const val K_FORYOU_UPDATED = "tracker_cache_foryou_updated"
    private const val K_UPDATED = "tracker_cache_updated"
    private const val K_FETCHED = "tracker_cache_fetched"
    /** v1.9: the classified outcome of the last fetch (TrackerStatus.Kind name). */
    private const val K_STATE = "tracker_state"
    /** v1.9: last fetch that returned DATA — the only throttle stamp. */
    private const val K_LAST_OK = "tracker_last_ok"

    /** Provider paths in fetch order — mirrors the keys list in refreshAsync. */
    private val PATHS = listOf("upnext", "continue", "movies", "tracked", "foryou")

    /**
     * v3.1 (ask 38): warm the on-disk home-cache mirror into
     * memory (delegates to data/HomeCacheDisk; spawns its own
     * worker, idempotent). Called from App.onCreate and from the
     * fetch worker — never from a home build.
     */
    fun warmDiskMirror(ctx: Context) = HomeCacheDisk.warmMirror(ctx)

    /**
     * v3.2 (ask 43): cheap stored-data probe — prefs strings
     * present, or a disk mirror present. No parse, no provider
     * query, so the home build can decide first-run seeding
     * without paying for data it already has.
     */
    fun hasStoredData(ctx: Context): Boolean {
        return try {
            val p = prefs(ctx)
            listOf(K_UPNEXT, K_CONTINUE, K_MOVIES, K_TRACKED, K_FORYOU).any {
                !PrefsSafe.strOrNull(p, it).isNullOrBlank() ||
                    HomeCacheDisk.hasMirror(it.removePrefix("tracker_cache_"))
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * v3.2: the worker-warmed parsed memo, WITHOUT parsing — the
     * home build's only snapshot read. cached() already memoizes
     * by raw-string key, but its first call in a process still
     * parses on the caller; prepareTrackerData must never be
     * that caller. Null until a worker (cold-warm, fetch
     * landing, explicit parse kick) has parsed.
     */
    fun memoOrNull(): Snapshots? = cachedMemo

    @Volatile
    private var memoSlimInFlight = false

    /**
     * v3.5 (spec §35): warm the memo from the SLIM file ONLY and
     * report back via [onWarmed] (which rebuilds). Single-flight.
     * This REPLACES warmMemoAsync: the old shape parsed the full
     * multi-megabyte snapshots on this worker — at App.onCreate
     * and on the first home build, beside discovery and the
     * match. The slim file (<= 512 KB, home-consumed fields
     * only) paints the same rows in milliseconds; when it misses
     * (first run, stale fingerprint) the memo simply stays empty
     * and the home shows its shell/LOADING rows until the cold
     * chain's TRACKER_FULL step parses post-survival. A slim
     * warm NEVER parses — that is the first-frame guarantee.
     * Never runs on the calling thread.
     */
    fun warmSlimAsync(ctx: Context, onWarmed: () -> Unit) {
        synchronized(this) {
            if (memoSlimInFlight) return
            memoSlimInFlight = true
        }
        val appCtx = ctx.applicationContext
        kotlin.concurrent.thread(name = "mizan-tracker-slimwarm") {
            try {
                trySlimOnly(appCtx)
            } catch (e: Exception) {
                // A failed slim read just leaves the memo empty; the home renders the shell.
            } finally {
                synchronized(this) { memoSlimInFlight = false }
            }
            try {
                onWarmed()
            } catch (e: Exception) {
                // The caller's rebuild guard handles dead activities.
            }
        }
    }

    /**
     * Slim-only memo fill (worker threads only): read the raw
     * inputs, and when the slim file matches their fingerprint,
     * install the slim snapshots as the memo. Returns true on a
     * hit. NEVER parses the full documents — a miss returns
     * false and leaves the memo untouched.
     */
    private fun trySlimOnly(ctx: Context): Boolean {
        return try {
            val inputs = readRawInputs(ctx)
            val key = memoKeyFor(inputs)
            val memo = cachedMemo
            if (memo != null && cachedMemoKey == key) return true
            val slimSnap = trySlimSnap(ctx, inputs) ?: return false
            cachedMemo = slimSnap
            cachedMemoKey = key
            true
        } catch (e: Exception) {
            false
        }
    }

    /** The five raw snapshot documents + stamps, from prefs (or the disk mirror fallback). */
    private data class RawInputs(
        val up: String?,
        val cont: String?,
        val movies: String?,
        val tracked: String?,
        val forYou: String?,
        val updated: Long,
        val forYouUpdated: Long,
        val fetchedAt: Long
    )

    private fun readRawInputs(ctx: Context): RawInputs {
        val p = prefs(ctx)
        return RawInputs(
            up = PrefsSafe.strOrNull(p, K_UPNEXT) ?: HomeCacheDisk.mirrored(ctx, "upnext"),
            cont = PrefsSafe.strOrNull(p, K_CONTINUE) ?: HomeCacheDisk.mirrored(ctx, "continue"),
            movies = PrefsSafe.strOrNull(p, K_MOVIES) ?: HomeCacheDisk.mirrored(ctx, "movies"),
            tracked = PrefsSafe.strOrNull(p, K_TRACKED) ?: HomeCacheDisk.mirrored(ctx, "tracked"),
            forYou = PrefsSafe.strOrNull(p, K_FORYOU) ?: HomeCacheDisk.mirrored(ctx, "foryou"),
            updated = PrefsSafe.long(p, K_UPDATED, 0L),
            forYouUpdated = PrefsSafe.long(p, K_FORYOU_UPDATED, 0L),
            fetchedAt = PrefsSafe.long(p, K_FETCHED, 0L)
        )
    }

    private fun memoKeyFor(inputs: RawInputs): List<Any?> = listOf(
        inputs.up, inputs.cont, inputs.movies, inputs.tracked, inputs.forYou,
        inputs.updated, inputs.forYouUpdated, inputs.fetchedAt
    )

    private fun slimFpFor(inputs: RawInputs): String = TrackerSlim.fingerprint(
        inputs.updated, inputs.fetchedAt,
        inputs.up?.length ?: 0, inputs.cont?.length ?: 0,
        inputs.movies?.length ?: 0, inputs.tracked?.length ?: 0,
        inputs.forYou?.length ?: 0
    )

    /** The slim snapshots for these inputs, or null on a slim miss (caller falls through to the full parse). */
    private fun trySlimSnap(ctx: Context, inputs: RawInputs): Snapshots? {
        val slim = readSlim(ctx, slimFpFor(inputs)) ?: return null
        return Snapshots(
            upNext = slim.upNext, cont = slim.cont,
            movies = slim.movies, tracked = slim.tracked,
            forYou = slim.forYou,
            trackedJson = inputs.tracked ?: "",
            updated = slim.updated,
            forYouUpdated = slim.forYouUpdated,
            fetchedAt = slim.fetchedAt
        )
    }

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
            PrefsSafe.strOrNull(prefs(ctx), K_STATE)
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

    // v2.5: parsed-snapshot memo. cached() used to re-parse all
    // five snapshot documents on EVERY call — and it is called
    // several times inside a single home build / details rebuild
    // / diagnostics composition (status() alone called it twice),
    // so one screen composition re-parsed the same JSON again and
    // again on the main thread. The memo keys on the raw stored
    // strings + the stamp fields: a fetch landing new documents
    // invalidates it by value, unchanged data returns the SAME
    // instance (callers may rely on reference-stable strings,
    // e.g. DetailsActivity's parse memo).
    @Volatile
    private var cachedMemo: Snapshots? = null

    @Volatile
    private var cachedMemoKey: List<Any?>? = null

    /**
     * Slim-cache diagnostics (v3.4, ask 49): what the last slim
     * attempt did — "absent" (no file), "hit, <age>" (served),
     * "miss" (present but stale/garbled). Read freely from any
     * thread; surfaced as the diagnostics `slim:` line. A miss
     * is not an error — the full parse below runs instead.
     */
    @Volatile
    var slimDiag: String = "absent"
        private set

    /** Slim file (lives beside the home-cache mirrors, same dir). */
    private fun slimFile(ctx: Context): File =
        File(File(ctx.filesDir, "home_cache"), "tracker_slim.json")

    /** Last slim fingerprint written (avoids a write storm on repeated parses). */
    @Volatile
    private var lastSlimFp: String? = null

    /**
     * Try the slim file (v3.4, ask 49): a bounded read (refused
     * past TrackerSlim.MAX_BYTES) + a small-document parse. Null
     * on any failure — the caller falls through to the full
     * parse, so a bad slim can never empty the home.
     */
    private fun readSlim(ctx: Context, expectedFp: String): TrackerSlim.Data? {
        return try {
            val f = slimFile(ctx)
            if (!f.isFile || f.length() > TrackerSlim.MAX_BYTES) {
                slimDiag = "absent"
                return null
            }
            val data = TrackerSlim.decode(f.readText(), expectedFp)
            if (data == null) {
                slimDiag = "miss"
                return null
            }
            slimDiag = "hit, " + HomeCache.ageLabel(
                data.fetchedAt, System.currentTimeMillis()
            )
            data
        } catch (e: Exception) {
            slimDiag = "miss"
            null
        }
    }

    /**
     * Persist the slim file on a worker (v3.4, ask 49): called
     * after a full parse only, never on the home build path.
     * Atomic (tmp + rename), credential-tripwired inside
     * TrackerSlim.encode (a null encode writes nothing).
     */
    private fun writeSlimAsync(
        ctx: Context,
        snap: Snapshots,
        fingerprint: String
    ) {
        if (lastSlimFp == fingerprint) return
        lastSlimFp = fingerprint
        val appCtx = ctx.applicationContext
        thread(name = "mizan-tracker-slim") {
            try {
                val encoded = TrackerSlim.encode(
                    snap.upNext, snap.cont, snap.movies, snap.tracked,
                    snap.forYou, snap.updated, snap.forYouUpdated,
                    snap.fetchedAt, fingerprint
                ) ?: return@thread
                val dir = File(appCtx.filesDir, "home_cache")
                if (!dir.exists()) dir.mkdirs()
                val target = slimFile(appCtx)
                val tmp = File(dir, "tracker_slim.tmp")
                tmp.writeText(encoded)
                if (target.exists()) target.delete()
                tmp.renameTo(target)
            } catch (e: Exception) {
                // Slim writes are best-effort; the full parse path is unaffected.
            }
        }
    }

    /**
     * The last-good snapshots, parsed. Never blocks on the provider.
     * v3.1 (ask 38, local-first): each path falls back to the
     * on-disk home-cache mirror (data/HomeCacheDisk, read from
     * disk on demand) when prefs hold nothing for it — a prefs
     * wipe or corrupt prefs file no longer empties the home.
     *
     * v3.5 CALL CONTRACT — this parses, so it is NOT a home-build
     * call any more. Allowed callers: the cold chain's
     * TRACKER_FULL step, the tracker fetch worker, on-demand
     * screens (Details/Library/diagnostics), and a forced
     * discovery run. The home build reads ONLY memoOrNull() plus
     * the slim warm (warmSlimAsync) — a full parse on the open
     * path is exactly the spike v3.5 removes.
     *
     * v3.4 (ask 49): the slim path — the slim file holds only
     * the home-consumed fields, so it parses in milliseconds;
     * the full parse below still runs whenever the fingerprint
     * moved (a fetch landing) and rewrites the slim for the next
     * cold start. trackedJson still rides the raw string (a
     * reference, never a parse) so Library screens keep their
     * watched sets.
     */
    fun cached(ctx: Context): Snapshots {
        return try {
            val inputs = readRawInputs(ctx)
            val key = memoKeyFor(inputs)
            val memo = cachedMemo
            if (memo != null && cachedMemoKey == key) return memo
            trySlimSnap(ctx, inputs)?.let { slimSnap ->
                cachedMemo = slimSnap
                cachedMemoKey = key
                return slimSnap
            }
            val snap = Snapshots(
                upNext = TrackerLogic.parseUpNext(inputs.up),
                cont = TrackerLogic.parseContinue(inputs.cont),
                movies = TrackerLogic.parseMovies(inputs.movies),
                tracked = TrackerLogic.parseTrackedShows(inputs.tracked),
                forYou = TrackerLogic.parseForYou(inputs.forYou),
                trackedJson = inputs.tracked ?: "",
                updated = inputs.updated,
                forYouUpdated = inputs.forYouUpdated,
                fetchedAt = inputs.fetchedAt
            )
            cachedMemo = snap
            cachedMemoKey = key
            writeSlimAsync(ctx, snap, slimFpFor(inputs))
            snap
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

    /** Whether a provider fetch is currently running (drives section LOADING placeholders). */
    fun isRefreshing(): Boolean = inFlight

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
                PrefsSafe.strOrNull(p, K_UPNEXT), PrefsSafe.strOrNull(p, K_CONTINUE),
                PrefsSafe.strOrNull(p, K_MOVIES), PrefsSafe.strOrNull(p, K_TRACKED),
                PrefsSafe.strOrNull(p, K_FORYOU)
            )
        } catch (e: Exception) {
            null
        }
        if (inFlight) return
        val lastOk = try {
            PrefsSafe.long(prefs(appCtx), K_LAST_OK, 0L)
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
                    queryPath(appCtx, "tracked"),
                    // v2.0: older trackers (< 1.14.4) have no /foryou
                    // path — the query just yields nothing and the
                    // For You section stays absent (fail-open).
                    queryPath(appCtx, "foryou")
                )
                val now = System.currentTimeMillis()
                val editor = prefs(appCtx).edit()
                editor.putLong(K_FETCHED, now)
                val keys = listOf(K_UPNEXT, K_CONTINUE, K_MOVIES, K_TRACKED, K_FORYOU)
                var updated = 0L
                var anyAnswered = false
                var security = false
                for ((i, r) in results.withIndex()) {
                    if (r.security) security = true
                    val json = r.json ?: continue
                    anyAnswered = true
                    editor.putString(keys[i], json)
                    updated = maxOf(updated, r.updated)
                    if (keys[i] == K_FORYOU && r.updated > 0) {
                        editor.putLong(K_FORYOU_UPDATED, r.updated)
                    }
                }
                if (updated > 0) editor.putLong(K_UPDATED, updated)
                editor.apply()
                // v3.1 (ask 38): mirror every answered snapshot to
                // the on-disk home cache on this same worker, so the
                // home keeps rendering last-known data even if prefs
                // are later wiped. putSnapshot refuses credential-
                // shaped payloads; snapshot JSON carries none.
                warmDiskMirror(appCtx)
                for ((i, r) in results.withIndex()) {
                    val json = r.json ?: continue
                    HomeCacheDisk.putSnapshot(appCtx, PATHS[i], json, now)
                }
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
                    results[2].json ?: before?.get(2), results[3].json ?: before?.get(3),
                    results[4].json ?: before?.get(4)
                )
                if (after != before || kind != kindBefore) {
                    // v3.5 (spec §35): a fetch landing re-runs the
                    // cold chain (TRACKER_FULL re-parses since the
                    // memo key moved, MATCH re-fingerprints and
                    // re-streams only on change) — this is how a
                    // post-survival fetch refreshes the home
                    // without any home build ever parsing. The
                    // chain is single-flight, so a landing during
                    // the survival run dedupes against it.
                    ColdChainRunner.kickAfterFetch(appCtx)
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
     * Queue an action with the tracker (fire-and-forget on a
     * background thread). The tracker applies queued actions at its
     * next boot; the launcher reflects them locally in the meantime
     * (marked-set / For You overlay). Contract: LAUNCHER_SYNC.md —
     * call(method, arg = tmdb id as string, extras).
     */
    private fun callAction(ctx: Context, method: String, tmdbId: Long, extras: Bundle) {
        val appCtx = ctx.applicationContext
        thread(name = "mizan-tracker-call") {
            try {
                appCtx.contentResolver.call(BASE, method, tmdbId.toString(), extras)
            } catch (e: SecurityException) {
                // The read permission is missing, so writes are too —
                // record the state the diagnostics line reports (v1.9).
                saveKind(appCtx, TrackerStatus.Kind.PERMISSION)
                Prefs.noteError(appCtx, "trackerCall:$method", e)
            } catch (e: Exception) {
                Prefs.noteError(appCtx, "trackerCall:$method", e)
            }
        }
    }

    /**
     * Queue a mark-watched action. Movies pass season/episode 0
     * (per contract).
     */
    fun markWatched(ctx: Context, tmdbId: Long, type: String, season: Int, episode: Int) {
        val extras = Bundle()
        extras.putString("type", if (type == "movie") "movie" else "tv")
        extras.putInt("season", season)
        extras.putInt("episode", episode)
        callAction(ctx, "mark_watched", tmdbId, extras)
    }

    /**
     * v2.0: queue an add_title action from a For You pick ("Add to my
     * list"). The tracker adds the title as planned at its next boot.
     * [releaseDate] is "" when unknown — For You picks carry no date.
     */
    fun addTitle(
        ctx: Context,
        tmdbId: Long,
        type: String,
        title: String,
        poster: String,
        releaseDate: String
    ) {
        val extras = Bundle()
        extras.putString("type", if (type == "movie") "movie" else "tv")
        extras.putString("title", title)
        extras.putString("poster", poster)
        extras.putString("releaseDate", releaseDate)
        callAction(ctx, "add_title", tmdbId, extras)
    }

    /** v2.0: queue a dismiss_foryou action ("Not interested"). */
    fun dismissForYou(ctx: Context, tmdbId: Long, type: String) {
        val extras = Bundle()
        extras.putString("type", if (type == "movie") "movie" else "tv")
        callAction(ctx, "dismiss_foryou", tmdbId, extras)
    }

    // ------------------------------------------------------------ v2.1 actions

    /**
     * v2.1: queue an unwatch action — one episode off a show, or a
     * movie's watched flag cleared (season/episode 0 for movies).
     */
    fun unwatch(ctx: Context, tmdbId: Long, type: String, season: Int, episode: Int) {
        val extras = Bundle()
        extras.putString("type", if (type == "movie") "movie" else "tv")
        extras.putInt("season", season)
        extras.putInt("episode", episode)
        callAction(ctx, "unwatch", tmdbId, extras)
    }

    /**
     * v2.1: queue a mark_season action. [episodesCsv] is the
     * authoritative episode list (core/LibraryLogic.seasonCsv of
     * the TMDB season payload) — the drain quickWatches exactly
     * those episodes.
     */
    fun markSeason(ctx: Context, tmdbId: Long, season: Int, episodesCsv: String) {
        val extras = Bundle()
        extras.putString("type", "tv")
        extras.putInt("season", season)
        extras.putString("episodes", episodesCsv)
        callAction(ctx, "mark_season", tmdbId, extras)
    }

    /** v2.1: queue a remove_title action (watchlist + progress go). */
    fun removeTitle(ctx: Context, tmdbId: Long, type: String) {
        val extras = Bundle()
        extras.putString("type", if (type == "movie") "movie" else "tv")
        callAction(ctx, "remove_title", tmdbId, extras)
    }

    /** v2.1: queue a set_muted action ("1" mutes, "0" unmutes). */
    fun setMuted(ctx: Context, tmdbId: Long, type: String, muted: Boolean) {
        val extras = Bundle()
        extras.putString("type", if (type == "movie") "movie" else "tv")
        extras.putString("muted", if (muted) "1" else "0")
        callAction(ctx, "set_muted", tmdbId, extras)
    }

    // ------------------------------------------------------------ v2.1 tmdb_get

    /** Global pref: the last tmdb_get outcome ("ok" or the error string). */
    private const val K_TMDB_LAST = "tracker_tmdb_last"
    /** Global pref (v2.6): the direct-path note for the diagnostics `tmdb:` line. */
    private const val K_TMDB_DIRECT = "tracker_tmdb_direct"
    /** Global pref: poster paths learned from tmdb_get (core codec). */
    private const val K_POSTER_CACHE = "tracker_poster_cache"

    /** The last tmdb_get outcome for the diagnostics `tmdb:` line. */
    fun lastTmdbOutcome(ctx: Context): String? {
        return try {
            PrefsSafe.strOrNull(prefs(ctx), K_TMDB_LAST)
        } catch (e: Exception) {
            null
        }
    }

    /** v2.6: the direct (launcher-key) path's note for the `tmdb:` line. */
    fun lastTmdbDirect(ctx: Context): String? {
        return try {
            PrefsSafe.strOrNull(prefs(ctx), K_TMDB_DIRECT)
        } catch (e: Exception) {
            null
        }
    }

    private fun noteTmdbOutcome(ctx: Context, outcome: String, direct: String) {
        try {
            prefs(ctx).edit()
                .putString(K_TMDB_LAST, outcome)
                .putString(K_TMDB_DIRECT, direct)
                .apply()
        } catch (e: Exception) {
            // Diagnostics bookkeeping only.
        }
    }

    /** Poster paths learned from tmdb_get detail fetches (may be empty). */
    fun posterCache(ctx: Context): Map<String, String> {
        return try {
            com.mizan.launcher.core.LibraryLogic.decodePosterCache(
                PrefsSafe.strOrNull(prefs(ctx), K_POSTER_CACHE)
            )
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /** Learn one title's poster path (from a details fetch). */
    fun notePoster(ctx: Context, type: String, tmdbId: Long, path: String) {
        notePath(ctx, K_POSTER_CACHE, type, tmdbId, path) { posterCache(ctx) }
    }

    /** Global pref: backdrop paths learned from tmdb_get (core codec). */
    private const val K_BACKDROP_CACHE = "tracker_backdrop_cache"

    /**
     * Backdrop paths learned from tmdb_get detail fetches. The hero
     * pool consults this for For You picks — the /foryou contract
     * carries no backdrop field, so a pick can only feature in the
     * hero once its backdrop has been learned here.
     */
    fun backdropCache(ctx: Context): Map<String, String> {
        return try {
            com.mizan.launcher.core.LibraryLogic.decodePathCache(
                PrefsSafe.strOrNull(prefs(ctx), K_BACKDROP_CACHE)
            )
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /** Learn one title's backdrop path (from a details fetch). */
    fun noteBackdrop(ctx: Context, type: String, tmdbId: Long, path: String) {
        notePath(ctx, K_BACKDROP_CACHE, type, tmdbId, path) { backdropCache(ctx) }
    }

    private fun notePath(
        ctx: Context,
        prefKey: String,
        type: String,
        tmdbId: Long,
        path: String,
        current: () -> Map<String, String>
    ) {
        if (path.isBlank() || tmdbId <= 0) return
        try {
            val key = com.mizan.launcher.core.LibraryLogic.posterKey(
                if (type == "movie") "movie" else "tv", tmdbId
            )
            val map = current()
            if (map[key] == path) return
            val next = LinkedHashMap(map)
            next[key] = path
            prefs(ctx).edit().putString(
                prefKey,
                com.mizan.launcher.core.LibraryLogic.encodePathCache(next)
            ).apply()
        } catch (e: Exception) {
            // A cache miss just means a glyph card / no hero next time.
        }
    }

    /**
     * One synchronous tmdb_get through the tracker provider (the
     * tracker's own key, never exposed). MUST be called off the main
     * thread (the provider itself does up to 10 s of HTTP inline).
     * Returns (json, null) on success or (null, error) with the
     * contract's error string; local failures (provider missing,
     * SecurityException) map to "io" for the caller's state machine
     * and are recorded via Prefs.noteError.
     */
    private fun tmdbGetViaProxy(appCtx: Context, path: String): Pair<String?, String?> {
        return try {
            val bundle = appCtx.contentResolver.call(BASE, "tmdb_get", path, null)
            val json = bundle?.getString("json")
            val error = bundle?.getString("error")
            when {
                json != null -> json to null
                error != null -> null to error
                else -> null to "io"
            }
        } catch (e: SecurityException) {
            saveKind(appCtx, TrackerStatus.Kind.PERMISSION)
            Prefs.noteError(appCtx, "tmdbGet", e)
            null to "io"
        } catch (e: Exception) {
            Prefs.noteError(appCtx, "tmdbGet", e)
            null to "io"
        }
    }

    /**
     * One TMDB read with the v2.6 resolution order
     * (core/LibraryLogic.resolveTmdb): the tracker proxy first;
     * on ANY proxy error, direct TMDB with the launcher's own key
     * (Prefs.tmdbKey, data/TmdbDirect) when one is set. Both paths
     * failing surfaces the PROXY error. MUST be called off the
     * main thread (up to 10 s of HTTP per path). Every outcome is
     * persisted for the diagnostics `tmdb:` line — which path won,
     * and, on failure, the direct path's own token (or that no
     * launcher key is set). Key material is never persisted.
     */
    fun tmdbGetBlocking(ctx: Context, path: String): Pair<String?, String?> {
        val appCtx = ctx.applicationContext
        val proxy = tmdbGetViaProxy(appCtx, path)
        val key = try {
            Prefs(appCtx).tmdbKey
        } catch (e: Exception) {
            ""
        }
        val resolution = com.mizan.launcher.core.LibraryLogic.resolveTmdb(
            proxy.first, proxy.second, key.isNotBlank()
        ) {
            TmdbDirect.getBlocking(appCtx, path, key)
        }
        val outcome = when {
            resolution.json != null && resolution.viaDirect -> "ok_direct"
            resolution.json != null -> "ok"
            else -> resolution.error ?: "io"
        }
        val directNote = when {
            resolution.json != null -> ""
            key.isBlank() -> "not_set"
            else -> resolution.directError ?: ""
        }
        noteTmdbOutcome(appCtx, outcome, directNote)
        return resolution.json to resolution.error
    }

    /**
     * tmdb_get on a background thread; [onResult] runs on the main
     * thread with (json, error) — exactly one of the two is non-null.
     */
    fun tmdbGet(ctx: Context, path: String, onResult: (String?, String?) -> Unit) {
        val appCtx = ctx.applicationContext
        thread(name = "mizan-tmdb-get") {
            val result = tmdbGetBlocking(appCtx, path)
            Handler(Looper.getMainLooper()).post {
                try { onResult(result.first, result.second) } catch (e: Exception) {
                    Prefs.noteError(appCtx, "tmdbGetCallback", e)
                }
            }
        }
    }
}
