package com.mizan.launcher.data

import android.content.Context
import com.mizan.launcher.core.TrackerLogic
import com.mizan.launcher.core.TrackerStore
import java.io.File

/**
 * Launcher-owned indexed tracker store, filesDir/tracker/ (MAP §4):
 * titles.jsonl, upnext.txt, continue.txt, movies.txt, foryou.txt,
 * missed.txt, index.json, episodes/tv_<id>.json, missed_cache/tv_<id>.txt
 * (lines `season|episode|yyyy-MM-dd`). All writes tmp+rename atomic;
 * all reads bounded and fail-open. No provider queries here — the
 * v3.7 one-time import flag (library_imported) closed that path.
 */
class TrackerStoreDisk(ctx: Context) {
    val dir: File = File(ctx.applicationContext.filesDir, "tracker").apply { mkdirs() }
    private val episodesDir: File = File(dir, "episodes").apply { mkdirs() }
    private val missedCacheDir: File = File(dir, "missed_cache").apply { mkdirs() }

    private fun file(name: String) = File(dir, name)
    private fun readLines(name: String): List<String> = try {
        val f = file(name)
        if (!f.isFile || f.length() > 4_000_000) emptyList() else f.readLines()
    } catch (e: Throwable) { emptyList() }

    private fun writeAtomic(target: File, content: String): Boolean = try {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(content)
        tmp.renameTo(target) || (target.delete() && tmp.renameTo(target))
    } catch (e: Throwable) { false }

    fun titles(): List<TrackerStore.Title> =
        readLines("titles.jsonl").mapNotNull(TrackerStore::decodeTitle)
    fun upNext(): List<TrackerLogic.UpNextItem> =
        readLines("upnext.txt").mapNotNull(TrackerStore::decodeUpNext)
    fun continueItems(): List<TrackerLogic.ContinueItem> =
        readLines("continue.txt").mapNotNull(TrackerStore::decodeContinue)
    fun movies(): List<TrackerLogic.MovieItem> =
        readLines("movies.txt").mapNotNull(TrackerStore::decodeMovie)
    fun forYou(): List<TrackerLogic.ForYouPick> =
        readLines("foryou.txt").mapNotNull(TrackerStore::decodeForYou)
    fun missed(): List<TrackerLogic.UpNextItem> =
        readLines("missed.txt").mapNotNull(TrackerStore::decodeUpNext)
    fun index(): TrackerStore.Index? = try {
        val f = file("index.json")
        if (!f.isFile || f.length() > 64_000) null
        else TrackerStore.decodeIndex(f.readText())
    } catch (e: Throwable) { null }

    fun episodeFile(tmdbId: Long): TrackerStore.EpisodeFile? = try {
        val f = File(episodesDir, "tv_$tmdbId.json")
        if (!f.isFile || f.length() > 1_000_000) null
        else TrackerStore.decodeEpisode(f.readText())
    } catch (e: Throwable) { null }

    fun writeEpisode(tmdbId: Long, ef: TrackerStore.EpisodeFile): Boolean {
        val json = TrackerStore.encodeEpisode(ef) ?: return false
        return writeAtomic(File(episodesDir, "tv_$tmdbId.json"), json)
    }
    fun deleteEpisode(tmdbId: Long): Boolean = try {
        val f = File(episodesDir, "tv_$tmdbId.json")
        !f.isFile || f.delete()
    } catch (e: Throwable) { false }

    fun writeSection(name: String, lines: List<String>): Boolean =
        writeAtomic(file(name), lines.joinToString("\n"))
    fun writeUpNext(items: List<TrackerLogic.UpNextItem>): Boolean {
        val lines = items.map { TrackerStore.encodeUpNext(it) ?: return false }
        return writeSection("upnext.txt", lines)
    }
    fun writeMissed(items: List<TrackerLogic.UpNextItem>): Boolean {
        val lines = items.map { TrackerStore.encodeUpNext(it) ?: return false }
        return writeSection("missed.txt", lines)
    }

    /** Season air dates for one show: (season, episode, yyyy-MM-dd). */
    fun seasonDates(tmdbId: Long): List<Triple<Int, Int, String>> {
        return try {
            val f = File(missedCacheDir, "tv_$tmdbId.txt")
            if (!f.isFile) return emptyList()
            f.readLines().mapNotNull { line ->
                val p = line.split('|')
                if (p.size != 3) return@mapNotNull null
                val s = p[0].toIntOrNull() ?: return@mapNotNull null
                val e = p[1].toIntOrNull() ?: return@mapNotNull null
                Triple(s, e, p[2])
            }
        } catch (e: Throwable) { emptyList() }
    }

    fun writeSeasonDates(tmdbId: Long, dates: List<Triple<Int, Int, String>>): Boolean {
        val content = dates.joinToString("\n") { "${it.first}|${it.second}|${it.third}" }
        return writeAtomic(File(missedCacheDir, "tv_$tmdbId.txt"), content)
    }
    fun seasonDatesAgeMs(tmdbId: Long): Long = try {
        val f = File(missedCacheDir, "tv_$tmdbId.txt")
        if (!f.isFile) Long.MAX_VALUE else System.currentTimeMillis() - f.lastModified()
    } catch (e: Throwable) { Long.MAX_VALUE }

    /** Counts only — never titles — for diagnostics. */
    fun diagLine(): String = try {
        val idx = index()
        TrackerStore.diagLine(idx, "tracker: titles=${titles().size} up=${upNext().size} " +
            "cont=${continueItems().size} movies=${movies().size} foryou=${forYou().size}")
    } catch (e: Throwable) { "tracker: unavailable" }
}
