package com.mizan.launcher.data

import android.content.Context
import com.mizan.launcher.core.LibraryLogic
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Direct TMDB client (v2.6, spec §26) — TMDB access with the
 * LAUNCHER's own key (Prefs.tmdbKey), the fallback path behind
 * the tracker proxy in data/TrackerClient.tmdbGetBlocking (the
 * resolution order itself is core/LibraryLogic.resolveTmdb).
 *
 * Same contract as the tracker's tmdb_get: relative paths on the
 * tv/ movie/ search/ trending/ allowlist, (json, null) on HTTP
 * 200, else (null, "bad_path" | "no_key" | "http_<code>" |
 * "io:<exception class>"). 10 s timeouts. Worker-thread ONLY —
 * this does real network I/O inline.
 *
 * v2.7: the direct allowlist additionally accepts discover/ —
 * the tracker's proxy allowlist has no discover/ prefix (it
 * answers bad_path, and the funnel's fall-through lands here),
 * and the launcher's own For You engine (core/Discovery) needs
 * /discover for its genre step. Proxy-first semantics are
 * unchanged for every other path.
 *
 * Key hygiene (the IPTV-credential rule): the key is read from
 * prefs by the caller, used to build the request URL in memory,
 * and NEVER logged, returned, or persisted anywhere else — error
 * tokens carry the exception class name only, because exception
 * messages can embed the URL (and with it the key).
 */
object TmdbDirect {

    /** Response bodies are small JSON documents; cap runaway reads. */
    private const val MAX_BODY_BYTES = 4 * 1024 * 1024

    fun getBlocking(ctx: Context, path: String, key: String): Pair<String?, String?> {
        val p = path.trim().removePrefix("/")
        if (p.contains("://") || p.contains("..") ||
            !(p.startsWith("tv/") || p.startsWith("movie/") ||
                p.startsWith("search/") || p.startsWith("trending/") ||
                p.startsWith("discover/"))
        ) {
            return null to "bad_path"
        }
        val k = key.trim()
        if (k.isEmpty()) return null to "no_key"
        var conn: HttpURLConnection? = null
        try {
            val url = "https://api.themoviedb.org/3/" + p +
                (if (p.contains("?")) "&api_key=" else "?api_key=") +
                URLEncoder.encode(k, "UTF-8")
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "Sreeb/2.7")
            }
            val code = conn.responseCode
            if (code != 200) {
                return null to LibraryLogic.tmdbDirectHttpError(code)
            }
            return readBody(conn.inputStream) to null
        } catch (e: Exception) {
            Prefs.noteError(ctx, "tmdbDirect", RuntimeException(e.javaClass.simpleName))
            return null to LibraryLogic.tmdbDirectError(e)
        } finally {
            try {
                conn?.disconnect()
            } catch (e: Exception) {
                // Disconnect is best-effort.
            }
        }
    }

    private fun readBody(input: InputStream): String {
        val bos = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        input.use { stream ->
            while (true) {
                val n = stream.read(buf)
                if (n <= 0) break
                total += n
                if (total > MAX_BODY_BYTES) break
                bos.write(buf, 0, n)
            }
        }
        return bos.toString("UTF-8")
    }
}
