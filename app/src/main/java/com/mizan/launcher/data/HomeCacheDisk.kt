package com.mizan.launcher.data

import android.content.Context
import com.mizan.launcher.core.HomeCache
import com.mizan.launcher.core.JsonMini
import com.mizan.launcher.core.asObj
import java.io.File
import kotlin.concurrent.thread

/**
 * The on-disk half of the local-first home cache (v3.1, ask 38).
 *
 * Layout under filesDir/home_cache/:
 *   tracker_<section>.json — one raw snapshot JSON per tracker
 *     provider path (upnext/continue/movies/tracked/foryou)
 *   manifest.json — {"upnext": {"t": <fetchedAtMs>, "b": <bytes>}, ...}
 *
 * Threading contract: ALL file IO here runs on a worker thread —
 * [putSnapshot] is called from TrackerClient's fetch worker,
 * [warmMirror] spawns its own worker, and the manifest is never
 * read on the main thread. The home build reads the worker-warmed
 * memo through TrackerClient (zero disk IO, zero network on its
 * path); [warmMirror] keeps only metadata ([mirrorMeta]), and a
 * prefs-miss fallback reads the one mirror file it needs from
 * disk on its worker ([mirrored]). A stale payload still
 * renders; only diagnostics names its age ([lastDiag]).
 * v3.5: the v3.1 in-memory mirror map is gone (see [mirrorMeta]).
 *
 * Credential rule (enforced, not just documented): [putSnapshot]
 * refuses any payload matching HomeCache.containsCredentialPattern
 * — stream URLs with panel credentials and key blobs can never
 * land in this directory, whatever the caller passed.
 */
object HomeCacheDisk {

    private const val DIR = "home_cache"
    private const val MANIFEST = "manifest.json"

    /**
     * v3.5 (spec §35): the mirror is METADATA ONLY now — section
     * -> file length in bytes (worker-populated; main-thread
     * reads are safe). The v3.1 shape kept all five snapshot
     * documents in memory as strings, a second copy beside the
     * prefs file's own copies — up to 10 MB of dead weight on a
     * normal device, where prefs already hold every section and
     * the mirror is never read. Contents are read from disk on
     * demand ([mirrored], worker threads only) instead.
     */
    @Volatile
    var mirrorMeta: Map<String, Long> = emptyMap()
        private set

    /** Whether a disk mirror file exists for this section (in-memory metadata; no IO). */
    fun hasMirror(section: String): Boolean = mirrorMeta.containsKey(section)

    /** Last composed diagnostics line (worker-updated; read freely from any thread). */
    @Volatile
    var lastDiag: String = "empty"
        private set

    @Volatile
    private var warmed = false

    private fun dir(ctx: Context): File = File(ctx.filesDir, DIR)

    private fun fileFor(ctx: Context, section: String): File =
        File(dir(ctx), "tracker_$section.json")

    /**
     * Stat the disk mirror files on a worker (idempotent).
     * Called from App.onCreate and lazily from the tracker fetch
     * worker; a prefs wipe after this point still leaves the home
     * rendering from the mirror via TrackerClient.cached() (which
     * reads the one file it needs from disk, on its worker).
     * v3.5: only stats are kept — the old shape read every file
     * into memory here, duplicating the prefs copies.
     */
    fun warmMirror(ctx: Context) {
        if (warmed) return
        warmed = true
        val appCtx = ctx.applicationContext
        thread(name = "mizan-home-cache-warm") {
            try {
                val meta = HashMap<String, Long>()
                for (section in HomeCache.TRACKER_SECTIONS) {
                    val f = fileFor(appCtx, section)
                    if (f.exists() && f.length() in 1..(2L * 1024L * 1024L)) {
                        meta[section] = f.length()
                    }
                }
                if (meta.isNotEmpty()) mirrorMeta = meta
                refreshDiag(appCtx)
            } catch (e: Exception) {
                // A mirror failure leaves the prefs cache as the
                // only source — exactly today's behaviour.
            }
        }
    }

    /**
     * Mirror one answered snapshot to disk. Worker-thread only
     * (the caller is TrackerClient's fetch worker). Atomic write
     * (tmp + rename), manifest update, oldest-first prune to the
     * cap. Refuses credential-shaped payloads outright.
     */
    fun putSnapshot(ctx: Context, section: String, json: String, fetchedAtMs: Long) {
        if (section !in HomeCache.TRACKER_SECTIONS) return
        if (HomeCache.containsCredentialPattern(json)) return
        try {
            val d = dir(ctx)
            if (!d.exists()) d.mkdirs()
            val target = fileFor(ctx, section)
            val tmp = File(d, "tracker_$section.tmp")
            tmp.writeText(json)
            if (target.exists()) target.delete()
            tmp.renameTo(target)
            // v3.5: metadata only — the bytes live on disk, not in memory.
            val updated = HashMap(mirrorMeta)
            updated[section] = json.length.toLong()
            mirrorMeta = updated
            val manifest = readManifest(ctx).toMutableMap()
            manifest[section] = HomeCache.Entry(section, fetchedAtMs, json.length.toLong())
            pruneToCap(ctx, manifest, keepSection = section)
            writeManifest(ctx, manifest)
            refreshDiag(ctx)
        } catch (e: Exception) {
            // Mirror writes are best-effort; the prefs cache and
            // the home are unaffected by a failure here.
        }
    }

    /**
     * The mirror value for one section, read from disk on demand.
     * WORKER THREADS ONLY — this is a bounded (2 MB) file read;
     * the home build never calls it (it reads the worker-warmed
     * memo), and the only caller is TrackerClient.cached() on its
     * worker. Null when no mirror file exists for the section.
     * v3.5: the old shape returned an in-memory copy; the copy
     * is gone (see [mirrorMeta]).
     */
    fun mirrored(ctx: Context, section: String): String? {
        return try {
            val f = fileFor(ctx, section)
            if (!f.isFile || f.length() !in 1..(2L * 1024L * 1024L)) null else f.readText()
        } catch (e: Exception) {
            null
        }
    }

    private fun readManifest(ctx: Context): Map<String, HomeCache.Entry> {
        return try {
            val f = File(dir(ctx), MANIFEST)
            if (!f.exists()) return emptyMap()
            val parsed = JsonMini.parse(f.readText()).asObj() ?: return emptyMap()
            val out = HashMap<String, HomeCache.Entry>()
            for ((section, v) in parsed) {
                val o = v.asObj() ?: continue
                val t = (o["t"] as? Number)?.toLong() ?: 0L
                val b = (o["b"] as? Number)?.toLong() ?: 0L
                out[section] = HomeCache.Entry(section, t, b)
            }
            out
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun writeManifest(ctx: Context, manifest: Map<String, HomeCache.Entry>) {
        try {
            val sb = StringBuilder("{")
            var first = true
            for ((section, e) in manifest) {
                if (!first) sb.append(",")
                first = false
                sb.append("\"").append(section).append("\":{\"t\":")
                    .append(e.fetchedAtMs).append(",\"b\":").append(e.bytes).append("}")
            }
            sb.append("}")
            File(dir(ctx), MANIFEST).writeText(sb.toString())
        } catch (e: Exception) {
            // Manifest loss is recoverable: files remain, and the
            // next put rebuilds the manifest from what it writes.
        }
    }

    private fun pruneToCap(
        ctx: Context,
        manifest: MutableMap<String, HomeCache.Entry>,
        keepSection: String
    ) {
        val entries = manifest.values.toList()
        val total = entries.sumOf { it.bytes }
        if (total <= HomeCache.TOTAL_CAP_BYTES) return
        val toDelete = HomeCache.prune(entries, keepSection)
        for (section in toDelete) {
            try { fileFor(ctx, section).delete() } catch (e: Exception) { }
            manifest.remove(section)
            val updated = HashMap(mirrorMeta)
            updated.remove(section)
            mirrorMeta = updated
        }
    }

    private fun refreshDiag(ctx: Context) {
        try {
            val manifest = readManifest(ctx)
            val present = manifest.keys
            val oldest = manifest.values.minOfOrNull { it.fetchedAtMs } ?: 0L
            val total = manifest.values.sumOf { it.bytes }
            lastDiag = HomeCache.diagLine(present, oldest, total, System.currentTimeMillis())
        } catch (e: Exception) {
            lastDiag = "unavailable"
        }
    }
}
