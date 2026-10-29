package com.mizan.launcher.data

import android.content.Context
import com.mizan.launcher.core.RedditEndpoints
import com.mizan.launcher.core.RedditImage
import com.mizan.launcher.core.RedditRss
import com.mizan.launcher.core.RedditStatus
import com.mizan.launcher.core.RedditWallpapers
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Reddit wallpaper networking + download, Android side.
 * Parsing/validation lives in core (pure, sim-tested); this class only
 * moves bytes.
 *
 * v1.6: Reddit refuses unauthenticated `.json` listings from many
 * networks now (HTTP 403 — live evidence in spec §14.3), so each
 * subreddit walks the core/RedditEndpoints fallback chain (three
 * .json hosts, then the RSS/Atom feed, which still answers) and stops
 * at the first endpoint yielding candidates. EVERY attempt is
 * recorded (Prefs.noteRedditAttempt) and each run ends with a
 * one-line status in Prefs.redditLastStatus: failures are visible in
 * Settings and diagnostics instead of failing silently like v1.1–v1.5.
 * The current wallpaper is still never touched by a failed fetch.
 */
object RedditFetcher {

    /**
     * Browser-like User-Agent. (Live tests showed the .json 403 is
     * IP-based, not UA-based — but a realistic UA costs nothing and
     * the RSS feed + image CDN both accept it.)
     */
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 12; TV) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36 MizanLauncher/1.6"
    const val MAX_IMAGE_BYTES: Long = 8L * 1024 * 1024

    /** Last fetch error, shown in diagnostics. Empty when the last fetch worked. */
    @Volatile var lastError: String = ""

    /**
     * Fetch top-of-week listings for [subreddits] on a background
     * thread, walking the fallback chain per subreddit. [ctx] (when
     * provided) receives the recorded attempts + run status.
     * [onDone] runs on that background thread with the merged
     * candidates (possibly empty) — callers hop to the UI thread.
     */
    fun fetch(
        subreddits: List<String>,
        allowNsfw: Boolean,
        ctx: Context? = null,
        onDone: (List<RedditImage>) -> Unit
    ) {
        thread(name = "mizan-reddit-fetch") {
            val all = ArrayList<RedditImage>()
            val attempts = ArrayList<RedditStatus.Attempt>()
            for (sub in subreddits) {
                val clean = RedditWallpapers.cleanSubreddit(sub)
                for (endpoint in RedditEndpoints.chain(clean)) {
                    val outcome = tryFetchEndpoint(endpoint, clean, allowNsfw)
                    attempts.add(outcome.attempt)
                    ctx?.let {
                        Prefs.noteRedditAttempt(it, RedditStatus.attemptLine(outcome.attempt))
                    }
                    if (RedditEndpoints.isUsable(outcome.attempt.candidates)) {
                        all.addAll(outcome.images)
                        break
                    }
                }
            }
            // De-duplicate by URL, keep first occurrence order.
            val seen = HashSet<String>()
            val merged = all.filter { seen.add(it.url) }
            val summary = RedditStatus.runSummary(attempts, merged.size, System.currentTimeMillis())
            ctx?.let {
                try { Prefs(it).redditLastStatus = summary } catch (e: Exception) {
                    // Status is diagnostics; never let it kill a fetch.
                }
            }
            lastError = if (merged.isEmpty()) {
                attempts.lastOrNull()?.let { "r/${it.subreddit}: ${it.result}" }
                    ?: "no subreddits configured"
            } else ""
            try { onDone(merged) } catch (e: Exception) {
                // Caller's callback must never kill the fetch thread noisily.
            }
        }
    }

    /** One endpoint try: the recorded attempt plus whatever it yielded. */
    private class FetchOutcome(
        val attempt: RedditStatus.Attempt,
        val images: List<RedditImage>
    )

    private fun tryFetchEndpoint(
        endpoint: RedditEndpoints.Endpoint,
        sub: String,
        allowNsfw: Boolean
    ): FetchOutcome {
        val now = System.currentTimeMillis()
        return try {
            val (code, body) = httpGet(endpoint)
            val images = when (endpoint.kind) {
                RedditEndpoints.Kind.JSON ->
                    RedditWallpapers.parseListing(body, sub, allowNsfw)
                RedditEndpoints.Kind.RSS ->
                    RedditRss.parseFeed(body, sub, allowNsfw)
            }
            FetchOutcome(
                RedditStatus.Attempt(endpoint.id, sub, "HTTP $code", images.size, now),
                images
            )
        } catch (e: Exception) {
            FetchOutcome(
                RedditStatus.Attempt(
                    endpoint.id, sub,
                    "${e.javaClass.simpleName}: ${e.message ?: "error"}", 0, now
                ),
                emptyList()
            )
        }
    }

    /**
     * GET one endpoint; returns (HTTP code, body text). Non-2xx codes
     * come back as data (the caller records them and the chain moves
     * on) — only transport failures throw.
     */
    private fun httpGet(endpoint: RedditEndpoints.Endpoint): Pair<Int, String> {
        // v3.16: Reddit rate-limits bursts with 429. One short retry
        // (honouring Retry-After when it is sane) turns the most common
        // transient failure into a success instead of an empty picker.
        var attempt = 0
        while (true) {
            val result = httpGetOnce(endpoint)
            val retryable = result.first == 429 || result.first == 503
            if (!retryable || attempt >= 1) return result
            attempt++
            try {
                val wait = retryAfterMs(endpoint).coerceIn(500L, 3_000L)
                Thread.sleep(wait)
            } catch (e: Exception) {
                // Interrupted: fall through to the retry immediately.
            }
        }
    }

    /** One GET; returns (HTTP code, body text). Non-2xx codes are data. */
    private fun httpGetOnce(endpoint: RedditEndpoints.Endpoint): Pair<Int, String> {
        val conn = URL(endpoint.url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", RedditEndpoints.acceptHeader(endpoint.kind))
            conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            conn.setRequestProperty("Cache-Control", "no-cache")
            lastRetryAfterMs = try {
                (conn.getHeaderField("Retry-After")?.trim()?.toLongOrNull() ?: 0L) * 1000L
            } catch (e: Exception) {
                0L
            }
            val code = conn.responseCode
            val body = try {
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            } catch (e: Exception) { "" }
            return code to body
        } finally {
            conn.disconnect()
        }
    }

    /** Retry-After of the last response, in ms (0 when absent/invalid). */
    @Volatile
    private var lastRetryAfterMs: Long = 0L

    private fun retryAfterMs(@Suppress("UNUSED_PARAMETER") endpoint: RedditEndpoints.Endpoint): Long =
        lastRetryAfterMs

    /** Directory (app-private) holding downloaded Reddit wallpapers. */
    fun wallpaperDir(ctx: Context): File {
        val dir = File(ctx.filesDir, "reddit_wallpapers")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** Stable local filename for a candidate URL. */
    fun localFileName(item: RedditImage): String {
        val ext = when {
            item.url.lowercase().substringBefore('?').endsWith(".png") -> "png"
            else -> "jpg"
        }
        return "reddit_" + Integer.toHexString(item.url.hashCode()) + "." + ext
    }

    fun localFile(ctx: Context, item: RedditImage): File = File(wallpaperDir(ctx), localFileName(item))

    fun isDownloaded(ctx: Context, item: RedditImage): Boolean {
        val f = localFile(ctx, item)
        return f.exists() && f.length() > 0
    }

    /**
     * Download [item]'s image into app-private storage, capped at
     * [MAX_IMAGE_BYTES]. Returns the file, or null on any failure
     * (offline, HTTP error, too large, undecodable). Partial files are
     * deleted so a broken download never becomes a broken wallpaper.
     */
    fun download(ctx: Context, item: RedditImage): File? {
        val target = localFile(ctx, item)
        if (target.exists() && target.length() > 0) return target
        val tmp = File(target.parentFile, target.name + ".part")
        return try {
            val conn = URL(item.url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 10_000
                conn.readTimeout = 20_000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", USER_AGENT)
                if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
                conn.inputStream.use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > MAX_IMAGE_BYTES) throw IllegalStateException("image too large")
                            out.write(buf, 0, n)
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
            // Must decode as a bitmap, else it's not a wallpaper.
            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(tmp.absolutePath, opts)
            if (opts.outWidth <= 0 || opts.outHeight <= 0) throw IllegalStateException("not an image")
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            target
        } catch (e: Exception) {
            lastError = "download: ${e.javaClass.simpleName}: ${e.message ?: ""}"
            try { tmp.delete() } catch (ignored: Exception) {}
            null
        }
    }
}
