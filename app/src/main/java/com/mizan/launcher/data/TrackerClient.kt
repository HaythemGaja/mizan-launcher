package com.mizan.launcher.data

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.mizan.launcher.core.HomeCache
import com.mizan.launcher.core.TrackerLogic
import com.mizan.launcher.core.TrackerSlim
import java.io.File
import kotlin.concurrent.thread

/**
 * Launcher side of the library (v1.8–v3.6: the
 * TVShowsMoviesTracker sync bridge; v3.7: the launcher IS
 * the tracker). The tracker app's ContentProvider
 * (authority [AUTHORITY], permission [PERMISSION]) is
 * contacted ONLY by the one-time import
 * ([runOneTimeImport]) while [Prefs.libraryImported] is
 * false — first v3.7 launch, while the tracker app is
 * still present. After the flag is set, every tracker
 * read/write is local-only (filesDir/tracker/, the
 * v3.6 indexed store, extended in v3.7 with missed.txt):
 * a missing tracker app reads as normal, never as an
 * error state, and the user uninstalls the tracker app
 * himself after verifying v3.7 (see the v3.7 test
 * script). The tracker app's data is never deleted
 * from here.
 *
 * Everything here is FAIL-OPEN: no store, corrupt
 * store, missing TMDB key — all of it reads as "no
 * data" for the affected section and the home falls
 * back to its other rows. Snapshot reads run on
 * worker threads; the home build reads only the memo.
 *
 * v3.7 TMDB: direct TMDB with the launcher's own key
 * (Prefs.tmdbKey, data/TmdbDirect) is now the ONLY
 * TMDB path — the tracker-proxy tmdb_get path died
 * with the tracker app. The key stays masked in
 * Settings, prefs-only, never in diagnostics/logs.
 */
object TrackerClient {

    const val TRACKER_PKG = "com.haythem.tvtracker"
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
         * v3.7: the missed row's items (data/LibraryLocal's
         * missed worker, filesDir/tracker/missed.txt) —
         * passed unwatched episodes, most overdue first.
         * Empty on the legacy prefs path (missed lives
         * only in the store); the store path fills it.
         */
        val missed: List<TrackerLogic.UpNextItem> = emptyList(),
        /**
         * v2.1: the RAW /tracked JSON — the Library/details screens
         * need watched episode SETS + muted flags, which the parsed
         * [tracked] (watched counts, for search) doesn't carry.
         * v3.6: on the store path this is EMPTY by design —
         * episode sets live only in per-title episode files
         * (data/TrackerStoreDisk), and the fetch worker is
         * the only place that still parses this document,
         * once, to write the store.
         */
        val trackedJson: String = "",
        /** The provider's `updated` stamp (widget-refresh time), 0 if never. */
        val updated: Long,
        /** The /foryou mirror's own `updated` stamp (overlay pruning), 0 if never. */
        val forYouUpdated: Long = 0L,
        /** When the launcher last fetched (epoch ms), 0 if never. */
        val fetchedAt: Long,
        /**
         * v3.6: the tracker-store fingerprint this snapshot
         * came from ("" = legacy prefs parse, not a store
         * snapshot). cached() returns a store memo
         * instantly when the live index fingerprint still
         * matches this one — no prefs load, no parse.
         */
        val storeFingerprint: String = ""
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
            // v3.8: NEVER let a slim warm clobber a store memo.
            // The store snapshot carries missed (and is refreshed
            // by every local write via installStoreMemo); the
            // pre-v3.8 slim carried no missed at all, so a slim
            // warm landing after the cold chain ran silently
            // emptied tMissed on the home — the v3.7 Missed-row
            // bug on his TV (slim: hit, library missed 12, no
            // row). A store memo wins; the slim is only a bridge
            // when no store memo exists.
            if (memo != null && memo.storeFingerprint.isNotBlank()) return true
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
        memoSource = "slim"
        return Snapshots(
            upNext = slim.upNext, cont = slim.cont,
            movies = slim.movies, tracked = slim.tracked,
            forYou = slim.forYou,
            // v3.8: the slim carries missed now (TrackerSlim v2).
            // A v1 slim file decodes as null (version mismatch),
            // so this line can never serve the old missed-less
            // slim to the home again.
            missed = slim.missed,
            trackedJson = inputs.tracked ?: "",
            updated = slim.updated,
            forYouUpdated = slim.forYouUpdated,
            fetchedAt = slim.fetchedAt
        )
    }

    // ------------------------------------------------------------ v3.6 tracker store (ask 53)

    /**
     * v3.6: store-backed state, worker-maintained, freely
     * readable. [storePainted] = a home paint consumed a
     * store snapshot; [storeFetchLanded] = a fetch wrote
     * the store. Both plus store validity gate the prefs
     * eviction (step 8) — see [maybeEvictPrefs].
     */
    @Volatile
    private var storePainted = false

    @Volatile
    private var storeFetchLanded = false

    /** Eviction ran (successfully) in this process — maybeEvict is idempotent, this just avoids repeat work. */
    @Volatile
    private var evictionDone = false

    /** Last store diagnostics line (worker-updated; diagnostics reads this, never the files). */
    @Volatile
    var storeDiag: String = "absent"
        private set

    /** Whether a valid indexed store exists (small index read; worker threads only). */
    fun storeValid(ctx: Context): Boolean = TrackerStoreDisk.storeValid(ctx)

    /**
     * v3.6: the store-backed snapshot, or null when the
     * store is absent/corrupt. Worker threads only (file
     * IO). Sets [storePainted] and refreshes [storeDiag]
     * on a hit — the eviction gate's first leg.
     */
    fun storeSnapshots(ctx: Context): Snapshots? {
        return try {
            val snap = TrackerStoreDisk.snapshotsFromStore(ctx) ?: run {
                storeDiag = TrackerStoreDisk.diagLine(ctx, "prefs")
                return null
            }
            storePainted = true
            storeDiag = TrackerStoreDisk.diagLine(ctx, "store")
            snap
        } catch (e: Exception) {
            null
        }
    }

    /**
     * v3.6: the cold chain's TRACKER step — store first,
     * full parse only as fallback. On a store hit the memo
     * fills from line files (no JSON parse, no raw
     * /tracked string retained). On a miss the legacy
     * cached() parse runs and the store is written from
     * it on this same worker, so the NEXT open is
     * store-backed. Returns the snapshot that filled
     * the memo (or null when neither source had data).
     *
     * v3.7: before the legacy fallback, the one-time
     * import gets its chance (provider present + flag
     * unset) — so a first v3.7 launch with the tracker
     * app still installed imports its record into the
     * store instead of parsing stale prefs. After the
     * flag is set, this function never touches the
     * provider again.
     */
    fun storeOrCached(ctx: Context): Snapshots? {
        return try {
            storeSnapshots(ctx)?.let { snap ->
                cachedMemo = snap
                memoSource = "store"
                cachedMemoKey = memoKeyFor(
                    RawInputs(null, null, null, null, null, snap.updated, snap.forYouUpdated, snap.fetchedAt)
                )
                // Step 8, deferred: a store paint + a fetch
                // landing together unlock the eviction, on a
                // worker (never on the chain's caller thread
                // beyond spawning it).
                if (storeFetchLanded && !evictionDone) {
                    val appCtx = ctx.applicationContext
                    thread(name = "mizan-tracker-evict") {
                        if (maybeEvictPrefs(appCtx)) evictionDone = true
                    }
                }
                return snap
            }
            if (!Prefs(ctx).libraryImported) {
                if (runOneTimeImport(ctx) == ImportResult.IMPORTED) {
                    storeSnapshots(ctx)?.let { snap ->
                        cachedMemo = snap
                        memoSource = "store"
                        cachedMemoKey = memoKeyFor(
                            RawInputs(null, null, null, null, null, snap.updated, snap.forYouUpdated, snap.fetchedAt)
                        )
                        return snap
                    }
                }
            }
            val snap = cached(ctx)
            if (snap.hasData && snap.trackedJson.isNotBlank()) {
                try {
                    val lib = com.mizan.launcher.core.LibraryLogic.parseLibShows(snap.trackedJson)
                    if (TrackerStoreDisk.writeFromSnapshots(ctx, snap, lib) != null) {
                        // The store now holds the local record
                        // (built from prefs/mirror because the
                        // provider is already gone): the import
                        // phase is closed, and the eviction gate
                        // sees a landed store like a fetch's.
                        Prefs(ctx).libraryImported = true
                        storeFetchLanded = true
                        storeDiag = TrackerStoreDisk.diagLine(ctx, "store")
                    }
                } catch (e: Exception) {
                    // A failed store write leaves prefs/mirror as the source.
                }
            }
            snap
        } catch (e: Exception) {
            null
        }
    }

    /**
     * v3.7: install the store snapshot as the memo (worker
     * threads only). Local writes (data/LibraryLocal) call
     * this after every successful store mutation, so the
     * home's next build reads the new data from the memo
     * without any fetch — a mark-watched updates its own
     * rows immediately; it does not trigger a refresh.
     * A missing/corrupt store leaves the memo untouched
     * (the previous memo keeps rendering).
     */
    fun installStoreMemo(ctx: Context) {
        try {
            storeSnapshots(ctx)?.let { snap ->
                cachedMemo = snap
                memoSource = "store"
                cachedMemoKey = memoKeyFor(
                    RawInputs(null, null, null, null, null, snap.updated, snap.forYouUpdated, snap.fetchedAt)
                )
            }
        } catch (e: Exception) {
            // Memo install is best-effort; the next chain run re-reads the store.
        }
    }

    @Volatile
    private var memoStoreInFlight = false

    /**
     * v3.9: warm the memo from the indexed store ONLY (line
     * files, no JSON parse — the store path is what the
     * v3.5 "no parse on the open path" rule was protecting,
     * and line reads are bounded: tens of section lines +
     * one title line per show). Reports via [onWarmed] on
     * the worker thread (callers post to the main thread,
     * exactly like warmSlimAsync). Single-flight, so a
     * memo-less home build cannot spam workers on every
     * rebuild. This is the fix for the v3.8 on-device
     * empty-memo home: App.onCreate only warmed the slim
     * (absent on his TV), and the cold chain's store
     * install waited behind 10 s of survival — a fresh
     * activity instance (or a chain cancelled by
     * navigation) rendered discovery-only rows with no
     * hero, no Missed, no Continue and no My Library
     * entry row, while diagnostics (which reads the
     * index directly) correctly reported the store data.
     * A store warm never parses and never clobbers a
     * newer memo with older data: the store IS the
     * record, so installing it is always current.
     */
    fun warmStoreMemoAsync(ctx: Context, onWarmed: () -> Unit) {
        synchronized(this) {
            if (memoStoreInFlight) return
            memoStoreInFlight = true
        }
        val appCtx = ctx.applicationContext
        kotlin.concurrent.thread(name = "mizan-tracker-storewarm") {
            try {
                installStoreMemo(appCtx)
            } catch (e: Exception) {
                // A failed store read just leaves the memo empty; the home renders the shell.
            } finally {
                synchronized(this) { memoStoreInFlight = false }
            }
            try {
                onWarmed()
            } catch (e: Exception) {
                // The caller's rebuild guard handles dead activities.
            }
        }
    }

    /** v3.7: stamp a local library change (diagnostics `library:` line). Worker threads only. */
    fun noteLocalChange(ctx: Context) {
        try {
            Prefs(ctx).libraryLastChange = System.currentTimeMillis()
        } catch (e: Exception) {
            // Diagnostics bookkeeping only.
        }
    }

    /**
     * v3.7: the diagnostics `library:` line — the
     * launcher-library replacement for the old `tracker:`
     * line. Counts only (never titles, ids, or URLs):
     * store titles, episode files, missed items, last
     * local change, and whether the import phase is
     * still pending. A missing store is a normal state
     * (fresh install / store not built yet), never an
     * error state — the tracker app's absence is never
     * reported here at all.
     */
    fun libraryDiag(ctx: Context): String {
        return try {
            val idx = TrackerStoreDisk.readIndex(ctx)
            val missed = try {
                TrackerStoreDisk.readMissed(ctx).size
            } catch (e: Exception) {
                0
            }
            val last = try {
                Prefs(ctx).libraryLastChange
            } catch (e: Exception) {
                0L
            }
            val whenText = if (last > 0) {
                java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.getDefault())
                    .format(java.util.Date(last))
            } else {
                "never"
            }
            val imported = try {
                Prefs(ctx).libraryImported
            } catch (e: Exception) {
                false
            }
            val base = if (idx == null) {
                "no library store yet"
            } else {
                "${idx.titles} titles · ${idx.episodeFiles} episode files"
            }
            base + " · missed $missed · last change $whenText" +
                if (imported) "" else " · import pending"
        } catch (e: Exception) {
            "unavailable"
        }
    }

    // ------------------------------------------------------------ v3.7 one-time import (sole provider contact)

    /** Outcome of one import attempt (see [runOneTimeImport]). */
    enum class ImportResult { IMPORTED, STORE_CURRENT, NO_PROVIDER, NO_DATA }

    /** Serializes import attempts (chain step + explicit refresh may race on first launch). */
    private val importLock = Any()

    /**
     * v3.7: the ONE-TIME final import — the sole provider
     * contact left in the app, and only while
     * [Prefs.libraryImported] is false. Reads all five
     * provider paths once, writes everything into the
     * launcher-owned store (atomic tmp+rename, index
     * last, via the fetch worker's writer), and closes
     * the phase:
     * - IMPORTED: the store now holds the provider record.
     * - STORE_CURRENT: a valid store already holds a
     *   provider generation at least as new as the
     *   provider's stamp — no overwrite (this preserves
     *   any local writes since the store was built).
     * - NO_PROVIDER: the tracker app is absent — phase
     *   closed with nothing to import; the store (or an
     *   empty library) is the record from here on.
     * - NO_DATA: the provider answered nothing usable
     *   (or the store write failed) — the flag stays
     *   unset so a later launch retries, and the old
     *   store / prefs fallback keeps rendering (never
     *   an empty home from a failed import).
     * Callers must never call this when the flag is
     * already set — after the flag, no provider query
     * runs on any path, including this one.
     */
    fun runOneTimeImport(ctx: Context): ImportResult {
        synchronized(importLock) {
            val appCtx = ctx.applicationContext
            val prefs = Prefs(appCtx)
            if (prefs.libraryImported) return ImportResult.IMPORTED
            return try {
                if (!isInstalled(appCtx)) {
                    prefs.libraryImported = true
                    return ImportResult.NO_PROVIDER
                }
                val results = PATHS.map { queryPath(appCtx, it) }
                val anyAnswered = results.any { it.json != null }
                if (!anyAnswered) return ImportResult.NO_DATA
                val now = System.currentTimeMillis()
                var updated = 0L
                for (r in results) updated = maxOf(updated, r.updated)
                val snap = Snapshots(
                    upNext = TrackerLogic.parseUpNext(results[0].json),
                    cont = TrackerLogic.parseContinue(results[1].json),
                    movies = TrackerLogic.parseMovies(results[2].json),
                    tracked = TrackerLogic.parseTrackedShows(results[3].json),
                    forYou = TrackerLogic.parseForYou(results[4].json),
                    trackedJson = results[3].json ?: "",
                    updated = updated,
                    forYouUpdated = results[4].updated,
                    fetchedAt = now
                )
                if (!snap.hasData) return ImportResult.NO_DATA
                val storeIdx = TrackerStoreDisk.readIndex(appCtx)
                if (storeIdx != null && TrackerStoreDisk.storeValid(appCtx) &&
                    updated > 0 && updated <= storeIdx.updated
                ) {
                    prefs.libraryImported = true
                    return ImportResult.STORE_CURRENT
                }
                val lib = try {
                    com.mizan.launcher.core.LibraryLogic.parseLibShows(snap.trackedJson)
                } catch (e: Exception) {
                    return ImportResult.NO_DATA
                }
                val written = TrackerStoreDisk.writeFromSnapshots(appCtx, snap, lib)
                if (written == null) {
                    // A refused line or failed write leaves the
                    // old store untouched (the writer encodes
                    // everything before moving any file).
                    return ImportResult.NO_DATA
                }
                storeFetchLanded = true
                storeDiag = TrackerStoreDisk.diagLine(appCtx, "store")
                prefs.libraryImported = true
                ImportResult.IMPORTED
            } catch (e: Exception) {
                try { Prefs.noteError(appCtx, "libraryImport", e) } catch (_: Exception) { }
                ImportResult.NO_DATA
            }
        }
    }

    /**
     * v3.6 step 8 — prefs eviction, gated, worker only.
     * Deletes ONLY the five snapshot keys (plus the
     * foryou-updated stamp) after one store paint AND
     * one fetch landing into the store AND a valid store
     * right now. If any leg fails, the keys stay and the
     * home keeps falling back to prefs/mirror. On success
     * the slim file is retired (the store is the fast
     * path now; two fast paths are not carried forever).
     * Returns true only when the eviction actually ran.
     */
    fun maybeEvictPrefs(ctx: Context): Boolean {
        return try {
            val appCtx = ctx.applicationContext
            if (!com.mizan.launcher.core.TrackerStore.evictionAllowed(
                    storeValid(appCtx), storePainted, storeFetchLanded
                )
            ) {
                return false
            }
            val p = prefs(appCtx)
            p.edit()
                .remove(K_UPNEXT).remove(K_CONTINUE).remove(K_MOVIES)
                .remove(K_TRACKED).remove(K_FORYOU).remove(K_FORYOU_UPDATED)
                .apply()
            try {
                val slim = slimFile(appCtx)
                if (slim.isFile) slim.delete()
            } catch (e: Exception) {
                // Slim retirement is housekeeping; the eviction already ran.
            }
            storeDiag = TrackerStoreDisk.diagLine(appCtx, "store (prefs evicted)")
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Test/observer hook: whether the eviction gate currently passes (no side effects). */
    fun evictionGatePasses(ctx: Context): Boolean =
        com.mizan.launcher.core.TrackerStore.evictionAllowed(
            storeValid(ctx), storePainted, storeFetchLanded
        )

    /** The persisted fetch state, for diagnostics + the fallback line. */
    // v3.7: the old fetch-classification Status is gone with
    // the provider fetch — the diagnostics `library:` line
    // (libraryDiag above) replaces the `tracker:` line, and
    // a missing tracker app is a normal state, not a
    // classified failure. (core/TrackerStatus stays in the
    // tree unused by live code; it is not referenced from
    // any v3.7 path.)

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
     * v3.9: which source filled the current memo — "store" (the
     * indexed store, the record), "slim" (the slim file bridge),
     * "prefs" (the legacy full parse), "none" (no memo yet).
     * The home diagnostics `home:` line reports this, so a
     * photo can tell a store-served home from a memo-less
     * shell frame without code reading. Set wherever a memo
     * is installed; never read on a home build (diagnostics
     * only).
     */
    @Volatile
    var memoSource: String = "none"
        private set

    /**
     * v3.9: the last Watch home build's working-set summary,
     * written by MainActivity.buildTrackerHome (counts only,
     * never titles/ids). SettingsActivity's diagnostics
     * `home:` line prints this verbatim. "no home build yet"
     * before the first Watch home build of the process — an
     * honest state, not a missing line.
     */
    @Volatile
    var lastHomeDiag: String = "no home build yet"
        private set

    /** v3.9: record a Watch home build's working-set summary (see [lastHomeDiag]). */
    fun noteHomeBuild(diag: String) {
        lastHomeDiag = diag
    }

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
                // v3.8: the slim must carry missed. A legacy
                // prefs parse has no missed (it lives only in
                // the store), so fall back to the store's
                // missed.txt when the snapshot's list is empty
                // — the store is the missed record, and an
                // empty list there means genuinely none, not a
                // missing field.
                val missed = if (snap.missed.isNotEmpty()) {
                    snap.missed
                } else {
                    try {
                        TrackerStoreDisk.readMissed(appCtx)
                    } catch (e: Exception) {
                        emptyList()
                    }
                }
                val encoded = TrackerSlim.encode(
                    snap.upNext, snap.cont, snap.movies, snap.tracked,
                    snap.forYou, snap.updated, snap.forYouUpdated,
                    snap.fetchedAt, fingerprint, missed
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
            // v3.6: a store memo is returned instantly while
            // the live index fingerprint still matches it —
            // no prefs load, no parse. The index read is a
            // small file; the fingerprint moves only when a
            // fetch rewrites the store, and the chain then
            // refreshes the memo (see storeOrCached).
            val storeMemo = cachedMemo
            if (storeMemo != null && storeMemo.storeFingerprint.isNotBlank()) {
                val liveFp = try {
                    TrackerStoreDisk.readIndex(ctx)?.fingerprint
                } catch (e: Exception) {
                    null
                }
                if (liveFp != null && liveFp == storeMemo.storeFingerprint) return storeMemo
            }
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
            memoSource = "prefs"
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
    /**
     * v3.7: LOCAL-ONLY refresh — the old provider fetch is
     * gone. Re-reads the launcher-owned store into the memo
     * on a background thread and posts [onChanged] on the
     * main thread when the store fingerprint moved (or the
     * memo was empty). Explicit user actions (Refresh home
     * data, section Retry, Details/Library opens) use this
     * to pick up local writes; it never fetches anything
     * and never triggers a chain run by itself (the memo
     * install is the refresh). The one exception: while
     * the import flag is unset, this ALSO runs the
     * one-time import first (same gate as the cold chain)
     * — after the flag, no provider query runs here.
     * [force] is kept for call-site compatibility; a
     * local store read is cheap, so there is no throttle
     * any more (the v1.9 throttle existed for provider
     * round-trips). Single-flight via [inFlight].
     */
    fun refreshAsync(ctx: Context, force: Boolean = false, onChanged: () -> Unit) {
        val appCtx = ctx.applicationContext
        if (inFlight) return
        inFlight = true
        thread(name = "mizan-library-refresh") {
            try {
                if (!Prefs(appCtx).libraryImported) runOneTimeImport(appCtx)
                val beforeFp = cachedMemo?.storeFingerprint
                val snap = storeSnapshots(appCtx)
                var changed = false
                if (snap != null) {
                    cachedMemo = snap
                    memoSource = "store"
                    cachedMemoKey = memoKeyFor(
                        RawInputs(null, null, null, null, null, snap.updated, snap.forYouUpdated, snap.fetchedAt)
                    )
                    changed = beforeFp == null || beforeFp != snap.storeFingerprint
                }
                // The missed worker is stamp-gated (cheap
                // no-op when its input fingerprint and age
                // are unchanged) — an explicit refresh
                // (force) is a legitimate moment to force a
                // full recompute, so a show whose earlier
                // fetch failed gets retried.
                try { LibraryLocal.kickMissedAll(appCtx, force = force) } catch (_: Exception) { }
                if (changed) {
                    Handler(Looper.getMainLooper()).post {
                        try { onChanged() } catch (e: Exception) {
                            // A caller's rebuild must never kill the poster.
                        }
                    }
                }
            } catch (e: Exception) {
                Prefs.noteError(appCtx, "libraryRefresh", e)
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

    // ------------------------------------------------------------ v3.7 local writes
    // The provider action queue (mark_watched / add_title /
    // dismiss_foryou / unwatch / mark_season / remove_title /
    // set_muted) is DELETED with the tracker link. Every write
    // now goes through data/LibraryLocal (local store writes,
    // serialized worker) via data/TrackerActions, which keeps
    // the instant-render overlay + marked-set behaviour. The
    // only provider contact left is the one-time import's
    // queryPath above — reads, never calls.

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
     * v3.7: one TMDB read, DIRECT ONLY, with the launcher's
     * own key (Prefs.tmdbKey, data/TmdbDirect). The
     * tracker-proxy tmdb_get path is deleted with the
     * tracker link — there is no proxy any more, and no
     * fallback to one. MUST be called off the main thread
     * (up to 10 s of HTTP per path). Every outcome is
     * persisted for the diagnostics `tmdb:` line; key
     * material is never persisted. A missing key reports
     * "no_key" (the user-visible line names the launcher
     * Settings page, not the tracker app — see
     * core/LibraryLogic.tmdbUserLine).
     */
    fun tmdbGetBlocking(ctx: Context, path: String): Pair<String?, String?> {
        val appCtx = ctx.applicationContext
        val key = try {
            Prefs(appCtx).tmdbKey
        } catch (e: Exception) {
            ""
        }
        if (key.isBlank()) {
            noteTmdbOutcome(appCtx, "no_key", "not_set")
            return null to "no_key"
        }
        val (json, error) = TmdbDirect.getBlocking(appCtx, path, key)
        val outcome = if (json != null) "ok_direct" else error ?: "io"
        val directNote = if (json != null) "" else error ?: ""
        noteTmdbOutcome(appCtx, outcome, directNote)
        return json to error
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
