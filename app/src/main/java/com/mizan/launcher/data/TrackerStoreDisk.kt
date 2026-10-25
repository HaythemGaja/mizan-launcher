package com.mizan.launcher.data

import android.content.Context
import com.mizan.launcher.core.HomeCache
import com.mizan.launcher.core.LibraryLogic
import com.mizan.launcher.core.TrackerLogic
import com.mizan.launcher.core.TrackerStore
import com.mizan.launcher.core.asObj
import com.mizan.launcher.core.asStr
import java.io.File

/**
 * The on-disk half of the indexed tracker store (v3.6, ask
 * 53). Layout under filesDir/tracker/ (beside
 * filesDir/iptv/, same tmp+rename discipline):
 *   titles.jsonl, upnext.txt, continue.txt, movies.txt,
 *   foryou.txt, index.json, episodes/tv_<id>.json
 *
 * Threading contract: ALL file IO here runs on worker
 * threads — the fetch worker (write), the cold chain
 * (store-backed snapshot read), or an on-demand screen
 * (one episode file). The home build never calls this
 * directly; it reads TrackerClient's store-backed memo,
 * which is filled from here on a worker.
 *
 * Credential rule (enforced, not just documented): the
 * pure codecs refuse credential-shaped lines (null
 * encode), and a refused line aborts the whole write —
 * a half-written store never replaces a good one. The
 * store carries titles/poster paths/ids/counts only.
 *
 * Fail-open: a missing, corrupt, or fingerprint-less
 * index reads as "no store" — the caller falls back to
 * the prefs/mirror full parse, never an empty home.
 * An episode file that is missing or corrupt reads as
 * a count-only title (the title line's count still
 * renders); Details retries that one title, never the
 * whole library.
 */
object TrackerStoreDisk {

    const val DIR = "tracker"
    private const val F_TITLES = "titles.jsonl"
    private const val F_UPNEXT = "upnext.txt"
    private const val F_CONTINUE = "continue.txt"
    private const val F_MOVIES = "movies.txt"
    private const val F_FORYOU = "foryou.txt"
    /** v3.7: the missed row's items (UpNext line codec, one per passed unwatched episode). */
    private const val F_MISSED = "missed.txt"
    /** v3.7: the missed worker's stamp (updated, count, input fingerprint) — small JSON. */
    private const val F_MISSED_META = "missed.json"
    private const val F_INDEX = "index.json"
    private const val EPISODE_DIR = "episodes"
    /** v3.7: per-show season air-date cache (one small line file per show). */
    private const val MISSED_CACHE_DIR = "missed_cache"

    /** Max bytes for one episode file (one show's watched set is hundreds of keys at most). */
    private const val MAX_EPISODE_BYTES = 256L * 1024L

    /** Max bytes for the small index file. */
    private const val MAX_INDEX_BYTES = 64L * 1024L

    private fun dir(ctx: Context): File {
        val d = File(ctx.filesDir, DIR)
        if (!d.isDirectory) d.mkdirs()
        return d
    }

    private fun episodesDir(ctx: Context): File {
        val d = File(dir(ctx), EPISODE_DIR)
        if (!d.isDirectory) d.mkdirs()
        return d
    }

    private fun fileFor(ctx: Context, name: String): File = File(dir(ctx), name)

    private fun episodeFile(ctx: Context, tmdbId: Long): File =
        File(episodesDir(ctx), "tv_$tmdbId.json")

    private fun writeAtomic(target: File, content: String): Boolean {
        return try {
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(content)
            if (target.exists()) target.delete()
            tmp.renameTo(target)
        } catch (e: Exception) {
            false
        }
    }

    private fun readBounded(f: File, maxBytes: Long): String? {
        return try {
            if (!f.isFile || f.length() > maxBytes) null else f.readText()
        } catch (e: Exception) {
            null
        }
    }

    // ------------------------------------------------------------ index + validity

    /** The store's index, or null when absent/corrupt (caller falls back). */
    fun readIndex(ctx: Context): TrackerStore.Index? {
        return try {
            TrackerStore.decodeIndex(readBounded(fileFor(ctx, F_INDEX), MAX_INDEX_BYTES))
        } catch (e: Exception) {
            null
        }
    }

    /** Validity is index + the files the index claims: a count > 0 with no file is not a store. */
    fun storeValid(ctx: Context): Boolean {
        val idx = readIndex(ctx) ?: return false
        if (idx.fingerprint.isBlank()) return false
        fun present(name: String, count: Int): Boolean =
            count <= 0 || fileFor(ctx, name).isFile
        return present(F_TITLES, idx.titles) &&
            present(F_UPNEXT, idx.upnext) &&
            present(F_CONTINUE, idx.continueCount) &&
            present(F_MOVIES, idx.movies) &&
            present(F_FORYOU, idx.foryou)
    }

    /** Diagnostics line for the store (counts only). */
    fun diagLine(ctx: Context, fallback: String): String =
        TrackerStore.diagLine(readIndex(ctx), fallback)

    // ------------------------------------------------------------ fetch-time writer (worker only)

    /**
     * Stream-write the whole store from one parsed fetch.
     * This is the ONLY whole-document parse in v3.6's data
     * path, and it runs once, on the fetch worker, before
     * the home ever sees the data. Titles come from
     * [snap.tracked] + [libShows] (muted + watched sets);
     * posters/backdrops/next coords fill from /continue
     * and /upnext twins. Index is swapped LAST, so a
     * crash mid-write leaves the previous good index (or
     * none) in place — never a half store. Returns the
     * new index, or null when any line tripped the
     * credential tripwire or a write failed (the old
     * store, prefs, and mirror are untouched then).
     */
    fun writeFromSnapshots(
        ctx: Context,
        snap: TrackerClient.Snapshots,
        libShows: List<LibraryLogic.LibShow>
    ): TrackerStore.Index? {
        return try {
            val libById = libShows.associateBy { it.tmdbId }
            val contById = snap.cont.associateBy { it.tmdbId }
            val upById = snap.upNext.associateBy { it.tmdbId }
            val titles = ArrayList<TrackerStore.Title>()
            for (show in snap.tracked) {
                val lib = libById[show.tmdbId]
                val cont = contById[show.tmdbId]
                val up = upById[show.tmdbId]
                val title = TrackerStore.Title(
                    type = "tv", tmdbId = show.tmdbId, title = show.name,
                    poster = cont?.poster ?: up?.poster ?: "",
                    backdrop = cont?.backdrop ?: up?.backdrop ?: "",
                    watchedCount = show.watchedCount,
                    planned = show.planned,
                    muted = lib?.muted ?: false,
                    nextSeason = up?.season ?: 0,
                    nextEpisode = up?.episode ?: 0
                )
                titles.add(title)
            }
            // Encode everything FIRST (tripwire check) — a
            // refused line aborts before any file moves.
            val titleLines = ArrayList<String>()
            for (t in titles) titleLines.add(TrackerStore.encodeTitle(t) ?: return null)
            val upLines = ArrayList<String>()
            for (i in snap.upNext) upLines.add(TrackerStore.encodeUpNext(i) ?: return null)
            val contLines = ArrayList<String>()
            for (i in snap.cont) contLines.add(TrackerStore.encodeContinue(i) ?: return null)
            val movieLines = ArrayList<String>()
            for (i in snap.movies) movieLines.add(TrackerStore.encodeMovie(i) ?: return null)
            val forYouLines = ArrayList<String>()
            for (p in snap.forYou) forYouLines.add(TrackerStore.encodeForYou(p) ?: return null)
            val episodePayloads = ArrayList<Pair<Long, String>>()
            for (lib in libShows) {
                val json = TrackerStore.encodeEpisode(
                    TrackerStore.EpisodeFile(lib.muted, lib.watched)
                ) ?: return null
                episodePayloads.add(lib.tmdbId to json)
            }
            val fp = TrackerStore.fingerprint(
                snap.updated, titles, snap.upNext, snap.cont, snap.movies, snap.forYou
            )
            val index = TrackerStore.Index(
                titles = titles.size, upnext = snap.upNext.size,
                continueCount = snap.cont.size, movies = snap.movies.size,
                foryou = snap.forYou.size, updated = snap.updated,
                fetchedAt = snap.fetchedAt, fingerprint = fp,
                episodeFiles = episodePayloads.size
            )
            // Episode files first, then sections, index last.
            for ((id, json) in episodePayloads) {
                if (!writeAtomic(episodeFile(ctx, id), json)) return null
            }
            if (!writeAtomic(fileFor(ctx, F_TITLES), titleLines.joinToString("\n"))) return null
            if (!writeAtomic(fileFor(ctx, F_UPNEXT), upLines.joinToString("\n"))) return null
            if (!writeAtomic(fileFor(ctx, F_CONTINUE), contLines.joinToString("\n"))) return null
            if (!writeAtomic(fileFor(ctx, F_MOVIES), movieLines.joinToString("\n"))) return null
            if (!writeAtomic(fileFor(ctx, F_FORYOU), forYouLines.joinToString("\n"))) return null
            if (!writeAtomic(fileFor(ctx, F_INDEX), TrackerStore.encodeIndex(index))) return null
            index
        } catch (e: Exception) {
            null
        }
    }

    // ------------------------------------------------------------ reads (worker only)

    /** One page of title lines, line-by-line, stopping at the window (never the whole file). */
    fun readTitlePage(ctx: Context, offset: Int, limit: Int): Pair<List<TrackerStore.Title>, Int> {
        return try {
            val f = fileFor(ctx, F_TITLES)
            if (!f.isFile) return emptyList<TrackerStore.Title>() to 0
            f.bufferedReader().use { r ->
                TrackerStore.readPage(r.lineSequence(), offset, limit, TrackerStore::decodeTitle)
            }
        } catch (e: Exception) {
            emptyList<TrackerStore.Title>() to 0
        }
    }

    // ------------------------------------------------------------ v3.7 local writes (worker only)

    /**
     * v3.7: read one section's decoded items (small lists —
     * upnext/continue/movies/foryou/missed are tens of lines;
     * titles is one line per tracked title). Local writes
     * read-modify-write through these, never through the
     * memo (the memo is the reader's cache, not the record).
     */
    fun readUpNext(ctx: Context): List<TrackerLogic.UpNextItem> =
        readSectionLines(ctx, F_UPNEXT).mapNotNull(TrackerStore::decodeUpNext)

    fun readContinue(ctx: Context): List<TrackerLogic.ContinueItem> =
        readSectionLines(ctx, F_CONTINUE).mapNotNull(TrackerStore::decodeContinue)

    fun readMovies(ctx: Context): List<TrackerLogic.MovieItem> =
        readSectionLines(ctx, F_MOVIES).mapNotNull(TrackerStore::decodeMovie)

    fun readForYou(ctx: Context): List<TrackerLogic.ForYouPick> =
        readSectionLines(ctx, F_FORYOU).mapNotNull(TrackerStore::decodeForYou)

    fun readMissed(ctx: Context): List<TrackerLogic.UpNextItem> =
        readSectionLines(ctx, F_MISSED).mapNotNull(TrackerStore::decodeUpNext)

    /** Write one section's lines atomically; false when a line trips the tripwire or the write fails. */
    fun writeUpNext(ctx: Context, items: List<TrackerLogic.UpNextItem>): Boolean {
        val lines = ArrayList<String>()
        for (i in items) lines.add(TrackerStore.encodeUpNext(i) ?: return false)
        return writeAtomic(fileFor(ctx, F_UPNEXT), lines.joinToString("\n"))
    }

    fun writeContinue(ctx: Context, items: List<TrackerLogic.ContinueItem>): Boolean {
        val lines = ArrayList<String>()
        for (i in items) lines.add(TrackerStore.encodeContinue(i) ?: return false)
        return writeAtomic(fileFor(ctx, F_CONTINUE), lines.joinToString("\n"))
    }

    fun writeMovies(ctx: Context, items: List<TrackerLogic.MovieItem>): Boolean {
        val lines = ArrayList<String>()
        for (i in items) lines.add(TrackerStore.encodeMovie(i) ?: return false)
        return writeAtomic(fileFor(ctx, F_MOVIES), lines.joinToString("\n"))
    }

    fun writeForYou(ctx: Context, items: List<TrackerLogic.ForYouPick>): Boolean {
        val lines = ArrayList<String>()
        for (i in items) lines.add(TrackerStore.encodeForYou(i) ?: return false)
        return writeAtomic(fileFor(ctx, F_FORYOU), lines.joinToString("\n"))
    }

    fun writeMissed(ctx: Context, items: List<TrackerLogic.UpNextItem>): Boolean {
        val lines = ArrayList<String>()
        for (i in items) lines.add(TrackerStore.encodeUpNext(i) ?: return false)
        return writeAtomic(fileFor(ctx, F_MISSED), lines.joinToString("\n"))
    }

    /** Write the title lines atomically (titles.jsonl). */
    fun writeTitles(ctx: Context, titles: List<TrackerStore.Title>): Boolean {
        val lines = ArrayList<String>()
        for (t in titles) lines.add(TrackerStore.encodeTitle(t) ?: return false)
        return writeAtomic(fileFor(ctx, F_TITLES), lines.joinToString("\n"))
    }

    /** Write one show's episode file atomically. */
    fun writeEpisode(ctx: Context, tmdbId: Long, file: TrackerStore.EpisodeFile): Boolean {
        val json = TrackerStore.encodeEpisode(file) ?: return false
        return writeAtomic(episodeFile(ctx, tmdbId), json)
    }

    /** Delete one show's episode file (remove-title path; a missing file is already deleted). */
    fun deleteEpisode(ctx: Context, tmdbId: Long): Boolean {
        return try {
            val f = episodeFile(ctx, tmdbId)
            if (!f.isFile) true else f.delete()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * v3.7: refresh the index after a local write — counts
     * and content fingerprint recomputed from the files
     * now on disk, keeping the import's `updated` /
     * `fetchedAt` stamps (local writes are user actions,
     * not fetches, and must not fake a provider stamp).
     * Returns the new index, or null when a count file
     * cannot be read (the old index stays honoured then;
     * the memo refresh re-reads it, so a failed index
     * refresh never empties the home — it just leaves
     * the previous fingerprint in place until the next
     * successful write).
     */
    fun rewriteIndex(ctx: Context): TrackerStore.Index? {
        return try {
            val old = readIndex(ctx)
            val titles = readTitles(ctx)
            val up = readUpNext(ctx)
            val cont = readContinue(ctx)
            val movies = readMovies(ctx)
            val forYou = readForYou(ctx)
            val episodeFiles = try {
                episodesDir(ctx).listFiles()?.count { it.isFile && it.name.startsWith("tv_") } ?: 0
            } catch (e: Exception) {
                0
            }
            val index = TrackerStore.Index(
                titles = titles.size, upnext = up.size,
                continueCount = cont.size, movies = movies.size,
                foryou = forYou.size, updated = old?.updated ?: 0L,
                fetchedAt = old?.fetchedAt ?: 0L,
                fingerprint = TrackerStore.fingerprint(
                    old?.updated ?: 0L, titles, up, cont, movies, forYou
                ),
                episodeFiles = episodeFiles
            )
            if (!writeAtomic(fileFor(ctx, F_INDEX), TrackerStore.encodeIndex(index))) null else index
        } catch (e: Exception) {
            null
        }
    }

    // ------------------------------------------------------------ v3.7 missed worker files

    /** The missed worker's stamp, or null when never run / corrupt (worker re-runs, never empties the row). */
    data class MissedMeta(val updated: Long, val count: Int, val inputFingerprint: String)

    fun readMissedMeta(ctx: Context): MissedMeta? {
        return try {
            val json = readBounded(fileFor(ctx, F_MISSED_META), MAX_INDEX_BYTES) ?: return null
            val o = com.mizan.launcher.core.JsonMini.parse(json).asObj() ?: return null
            val fp = o["inputFingerprint"].asStr() ?: return null
            MissedMeta(
                updated = (o["updated"] as? Number)?.toLong() ?: 0L,
                count = (o["count"] as? Number)?.toInt() ?: 0,
                inputFingerprint = fp
            )
        } catch (e: Exception) {
            null
        }
    }

    fun writeMissedMeta(ctx: Context, meta: MissedMeta): Boolean {
        val json = "{\"updated\":${meta.updated},\"count\":${meta.count}," +
            "\"inputFingerprint\":\"${meta.inputFingerprint}\"}"
        if (HomeCache.containsCredentialPattern(json)) return false
        return writeAtomic(fileFor(ctx, F_MISSED_META), json)
    }

    private fun missedCacheDir(ctx: Context): File {
        val d = File(dir(ctx), MISSED_CACHE_DIR)
        if (!d.isDirectory) d.mkdirs()
        return d
    }

    private fun seasonDatesFile(ctx: Context, tmdbId: Long): File =
        File(missedCacheDir(ctx), "tv_$tmdbId.txt")

    /**
     * v3.7: one show's cached season air dates (season →
     * episodes), or null when never cached. Lines are
     * `season|episode|airDate` (pipe codec, air dates are
     * plain yyyy-MM-dd strings — no credentials, but the
     * writer still tripwires them like every store write).
     * The cache is a fetch accelerator, never the record:
     * a missing/corrupt cache just re-fetches from TMDB.
     */
    fun readSeasonDates(ctx: Context, tmdbId: Long): List<TrackerLogic.SeasonAirDates>? {
        return try {
            val f = seasonDatesFile(ctx, tmdbId)
            if (!f.isFile) return null
            val bySeason = LinkedHashMap<Int, ArrayList<Pair<Int, String>>>()
            for (line in f.readLines()) {
                if (line.isBlank()) continue
                val parts = line.split('|')
                if (parts.size < 3) continue
                val s = parts[0].toIntOrNull() ?: continue
                val e = parts[1].toIntOrNull() ?: continue
                if (s < 1 || e < 1) continue
                bySeason.getOrPut(s) { ArrayList() }.add(e to com.mizan.launcher.core.IptvLogic.unesc(parts[2]))
            }
            if (bySeason.isEmpty()) null else bySeason.entries.sortedBy { it.key }.map { (s, eps) ->
                TrackerLogic.SeasonAirDates(s, eps.sortedBy { it.first })
            }
        } catch (e: Exception) {
            null
        }
    }

    fun seasonDatesAgeMs(ctx: Context, tmdbId: Long): Long {
        return try {
            val f = seasonDatesFile(ctx, tmdbId)
            if (!f.isFile) Long.MAX_VALUE else System.currentTimeMillis() - f.lastModified()
        } catch (e: Exception) {
            Long.MAX_VALUE
        }
    }

    fun writeSeasonDates(ctx: Context, tmdbId: Long, seasons: List<TrackerLogic.SeasonAirDates>): Boolean {
        return try {
            val lines = ArrayList<String>()
            for (season in seasons.sortedBy { it.season }) {
                for ((ep, date) in season.episodes.sortedBy { it.first }) {
                    lines.add("${season.season}|$ep|${com.mizan.launcher.core.IptvLogic.esc(date)}")
                }
            }
            val content = lines.joinToString("\n")
            if (HomeCache.containsCredentialPattern(content)) return false
            writeAtomic(seasonDatesFile(ctx, tmdbId), content)
        } catch (e: Exception) {
            false
        }
    }

    /** All title lines (small: one per tracked title, counts only — no episode data). */
    fun readTitles(ctx: Context): List<TrackerStore.Title> {
        return try {
            val f = fileFor(ctx, F_TITLES)
            if (!f.isFile) return emptyList()
            f.bufferedReader().use { r ->
                r.lineSequence().mapNotNull(TrackerStore::decodeTitle).toList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun readSectionLines(ctx: Context, name: String): List<String> {
        return try {
            val f = fileFor(ctx, name)
            if (!f.isFile) emptyList() else f.readLines()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * A store-backed snapshot: section items decoded from
     * their line files (small, no episode sets), tracked
     * from the title lines. trackedJson is EMPTY by
     * design — no raw multi-megabyte string is retained
     * anywhere on this path; episode sets live only in
     * per-title files (see [readEpisode]). v3.7: the
     * missed row rides along (missed.txt, same codec).
     */
    fun snapshotsFromStore(ctx: Context): TrackerClient.Snapshots? {
        return try {
            val idx = readIndex(ctx) ?: return null
            val upNext = readSectionLines(ctx, F_UPNEXT).mapNotNull(TrackerStore::decodeUpNext)
            val cont = readSectionLines(ctx, F_CONTINUE).mapNotNull(TrackerStore::decodeContinue)
            val movies = readSectionLines(ctx, F_MOVIES).mapNotNull(TrackerStore::decodeMovie)
            val forYou = readSectionLines(ctx, F_FORYOU).mapNotNull(TrackerStore::decodeForYou)
            val missed = readSectionLines(ctx, F_MISSED).mapNotNull(TrackerStore::decodeUpNext)
            val tracked = readTitles(ctx).map {
                TrackerLogic.TrackedShow(it.tmdbId, it.title, it.watchedCount, it.planned)
            }
            TrackerClient.Snapshots(
                upNext = upNext, cont = cont, movies = movies, tracked = tracked,
                forYou = forYou, missed = missed, trackedJson = "",
                updated = idx.updated, forYouUpdated = idx.updated,
                fetchedAt = idx.fetchedAt, storeFingerprint = idx.fingerprint
            )
        } catch (e: Exception) {
            null
        }
    }

    /** One show's episode file, or null when absent/corrupt (caller renders count-only, never empties the home). */
    fun readEpisode(ctx: Context, tmdbId: Long): TrackerStore.EpisodeFile? {
        return try {
            TrackerStore.decodeEpisode(readBounded(episodeFile(ctx, tmdbId), MAX_EPISODE_BYTES))
        } catch (e: Exception) {
            null
        }
    }

    /** One title's full LibShow (title line + its episode file); count-only when the episode file is missing. */
    fun libShowFor(ctx: Context, tmdbId: Long): LibraryLogic.LibShow? {
        return try {
            val title = readTitles(ctx).firstOrNull { it.tmdbId == tmdbId } ?: return null
            val ep = readEpisode(ctx, tmdbId)
            if (ep != null) {
                LibraryLogic.LibShow(tmdbId, title.title, ep.muted, title.planned, ep.watched)
            } else {
                LibraryLogic.LibShow(
                    tmdbId, title.title, title.muted, title.planned,
                    emptySet(), watchedCount = title.watchedCount
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Library grid shows: title lines (count-only), except
     * the [fullSetIds] titles (pending-overlay titles,
     * bounded by the overlay size), which get their full
     * episode sets so prune convergence stays exact.
     */
    fun libraryShows(
        ctx: Context,
        fullSetIds: Set<Long>
    ): List<LibraryLogic.LibShow> {
        return try {
            readTitles(ctx).map { title ->
                if (title.tmdbId in fullSetIds) {
                    val ep = readEpisode(ctx, title.tmdbId)
                    if (ep != null) {
                        LibraryLogic.LibShow(
                            title.tmdbId, title.title, ep.muted, title.planned, ep.watched
                        )
                    } else {
                        LibraryLogic.LibShow(
                            title.tmdbId, title.title, title.muted, title.planned,
                            emptySet(), watchedCount = title.watchedCount
                        )
                    }
                } else {
                    LibraryLogic.LibShow(
                        title.tmdbId, title.title, title.muted, title.planned,
                        emptySet(), watchedCount = title.watchedCount
                    )
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Whether a credential-shaped payload would be refused (exposed for diagnostics-free checks). */
    fun tripsCredential(payload: String): Boolean = HomeCache.containsCredentialPattern(payload)
}
