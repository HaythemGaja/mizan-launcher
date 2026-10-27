package com.mizan.launcher.data

import android.content.Context
import com.mizan.launcher.core.IptvEpg
import com.mizan.launcher.core.IptvLogic
import com.mizan.launcher.core.IptvStore
import java.io.File

/**
 * IPTV on-disk catalogue, filesDir/iptv/ (MAP §4): live.jsonl,
 * vod.jsonl, series.jsonl, epg.jsonl (one line per entry /
 * programme), index.json (totals + stamps), series_info/
 * (<fp>_<id>.json). Ingest swaps atomically (tmp+rename) and only
 * when every attempted endpoint succeeded (IptvStore
 * shouldSwapCatalogue); reads are bounded slices, never whole
 * hostile documents in memory at once beyond a single line.
 */
class IptvStoreDisk(ctx: Context) {
    val dir: File = File(ctx.applicationContext.filesDir, "iptv").apply { mkdirs() }
    private val seriesInfoDir: File = File(dir, "series_info").apply { mkdirs() }

    private fun file(name: String) = File(dir, name)
    private fun lines(name: String): Sequence<String> = try {
        val f = file(name)
        if (!f.isFile) emptySequence() else f.bufferedReader().lineSequence()
    } catch (e: Throwable) { emptySequence() }

    private fun writeAtomic(target: File, content: String): Boolean = try {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(content)
        tmp.renameTo(target) || (target.delete() && tmp.renameTo(target))
    } catch (e: Throwable) { false }

    fun writeLive(items: List<IptvLogic.LiveChannel>): Boolean =
        writeAtomic(file("live.jsonl"), items.joinToString("\n") { IptvStore.encodeLive(it) })
    fun writeVod(items: List<IptvLogic.VodItem>): Boolean =
        writeAtomic(file("vod.jsonl"), items.joinToString("\n") { IptvStore.encodeVod(it) })
    fun writeSeries(items: List<IptvLogic.SeriesItem>): Boolean =
        writeAtomic(file("series.jsonl"), items.joinToString("\n") { IptvStore.encodeSeries(it) })
    fun writeEpg(items: List<IptvEpg.Programme>): Boolean =
        writeAtomic(file("epg.jsonl"), items.joinToString("\n") { IptvStore.encodeEpg(it) })
    fun writeIndex(idx: IptvStore.Index): Boolean =
        writeAtomic(file("index.json"), IptvStore.encodeIndex(idx))
    fun index(): IptvStore.Index? = try {
        val f = file("index.json")
        if (!f.isFile || f.length() > 64_000) null else IptvStore.decodeIndex(f.readText())
    } catch (e: Throwable) { null }

    fun live(): List<IptvLogic.LiveChannel> = lines("live.jsonl").mapNotNull(IptvStore::decodeLive).toList()
    fun vod(): List<IptvLogic.VodItem> = lines("vod.jsonl").mapNotNull(IptvStore::decodeVod).toList()
    fun series(): List<IptvLogic.SeriesItem> = lines("series.jsonl").mapNotNull(IptvStore::decodeSeries).toList()
    fun epg(): List<IptvEpg.Programme> = lines("epg.jsonl").mapNotNull(IptvStore::decodeEpg).toList()
    fun epgFor(channelId: String): List<IptvEpg.Programme> =
        epg().filter { it.channelId == channelId }

    fun writeSeriesInfo(seriesId: Long, json: String): Boolean {
        val fp = (seriesId.toString().hashCode() and 0xffff).toString(16)
        return writeAtomic(File(seriesInfoDir, "${fp}_$seriesId.json"), json)
    }
    fun seriesInfo(seriesId: Long): String? = try {
        val fp = (seriesId.toString().hashCode() and 0xffff).toString(16)
        val f = File(seriesInfoDir, "${fp}_$seriesId.json")
        if (!f.isFile || f.length() > 1_000_000) null else f.readText()
    } catch (e: Throwable) { null }

    fun counts(): Triple<Int, Int, Int> = Triple(
        live().size, vod().size, series().size)
    fun lastUpdateMs(): Long = try {
        val f = file("index.json")
        index()?.fetchedAt ?: if (f.isFile) f.lastModified() else 0L
    } catch (e: Throwable) { 0L }
}
