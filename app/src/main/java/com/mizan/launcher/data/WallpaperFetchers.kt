package com.mizan.launcher.data

import android.content.Context
import com.mizan.launcher.core.RotationLogic
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.time.LocalDate

/**
 * Reddit + Bing wallpaper fetchers + rotation (MAP §1.1/§3.2).
 * Reddit: top-of-week JSON chain www → api → old → www .rss
 * (RSS fallback feeds the same RedditImage model); only usable
 * landscape image posts survive (direct i.redd.it image, preview
 * fallback; stickied / video / gallery / NSFW-unless-allowed /
 * portrait rejected). Bing: HPImageArchive, relative URLs made
 * absolute, cached per day-key under filesDir/bing_wallpapers/.
 * Rotation is checked on home wake / clock tick (no background
 * service/timer). All failures fail open to null. No ui imports;
 * dp math is plain arithmetic here (TANGLE-1 resolution).
 */
object RedditFetcher {
    data class Image(val url: String, val title: String, val subreddit: String)

    fun fetchTopOfWeek(prefs: PrefsStore, subreddit: String): Image? {
        val allowNsfw = prefs.redditNsfw()
        val endpoints = listOf(
            "https://www.reddit.com/r/$subreddit/top/.json?t=week&limit=25",
            "https://api.reddit.com/r/$subreddit/top?t=week&limit=25",
            "https://old.reddit.com/r/$subreddit/top/.json?t=week&limit=25",
        )
        for (url in endpoints) {
            val r = HttpUtil.get(url, "application/json")
            prefs.noteRedditAttempt("$subreddit ${r.code}")
            if (r.code !in 200..299 || r.body.isNullOrBlank()) continue
            val img = parseJson(r.body!!, subreddit, allowNsfw)
            if (img != null) return img
        }
        // RSS fallback.
        val rss = HttpUtil.get("https://www.reddit.com/r/$subreddit/top/.rss?t=week")
        if (rss.code in 200..299 && !rss.body.isNullOrBlank()) {
            return parseRss(rss.body!!, subreddit)
        }
        return null
    }

    private fun parseJson(json: String, subreddit: String, allowNsfw: Boolean): Image? {
        return try {
            val data = JSONObject(json).optJSONObject("data") ?: return null
            val children = data.optJSONArray("children") ?: return null
            for (i in 0 until children.length()) {
                val post = children.optJSONObject(i)?.optJSONObject("data") ?: continue
                if (post.optBoolean("stickied", false)) continue
                if (post.optBoolean("is_video", false)) continue
                if (post.optBoolean("is_gallery", false)) continue
                if (post.optBoolean("over_18", false) && !allowNsfw) continue
                val url = post.optString("url_overridden_by_dest", post.optString("url", ""))
                if (url.isBlank()) continue
                val isImage = url.endsWith(".jpg", true) || url.endsWith(".jpeg", true) ||
                    url.endsWith(".png", true) || url.contains("i.redd.it")
                if (!isImage) continue
                val w = post.optInt("width", 0)
                val h = post.optInt("height", 0)
                if (w > 0 && h > 0 && w <= h) continue // portrait rejected
                return Image(url, post.optString("title", ""), subreddit)
            }
            null
        } catch (e: Throwable) { null }
    }
    private fun parseRss(xml: String, subreddit: String): Image? {
        // Minimal RSS scan: first <link> ending in an image extension.
        val re = Regex("<link>([^<]+\\.(?:jpg|jpeg|png))</link>", RegexOption.IGNORE_CASE)
        val m = re.find(xml) ?: return null
        return Image(m.groupValues[1], "", subreddit)
    }
}

object BingFetcher {
    fun fetchDaily(ctx: Context, prefs: PrefsStore): File? {
        val today = LocalDate.now().toString()
        if (prefs.bingCacheDate() == today) {
            val cached = File(prefs.bingCachePath())
            if (cached.isFile) return cached
        }
        val r = HttpUtil.get(
            "https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=1&mkt=en-US")
        if (r.code !in 200..299 || r.body.isNullOrBlank()) return null
        val rel = try {
            JSONObject(r.body!!).optJSONArray("images")?.optJSONObject(0)?.optString("url", "") ?: ""
        } catch (e: Throwable) { "" }
        if (rel.isBlank()) return null
        val abs = if (rel.startsWith("http")) rel else "https://www.bing.com$rel"
        return try {
            val dir = File(ctx.applicationContext.filesDir, "bing_wallpapers").apply { mkdirs() }
            val out = File(dir, "bing_$today.jpg")
            URL(abs).openStream().use { input ->
                FileOutputStream(out).use { output -> input.copyTo(output) }
            }
            prefs.setBingCache(today, out.absolutePath)
            out
        } catch (e: Throwable) { null }
    }
}

/** Wallpaper rotation: off / 15m / 1h / 6h / daily, checked on wake/tick. */
object WallpaperRotator {
    fun due(prefs: PrefsStore, nowMs: Long = System.currentTimeMillis()): Boolean {
        val interval = prefs.redditRotation()
        if (interval == RotationLogic.OFF) return false
        val last = prefs.redditRotationLast()
        val gap = when (interval) {
            RotationLogic.EVERY_15_MIN -> 15L * 60 * 1000
            RotationLogic.EVERY_1_HOUR -> 60L * 60 * 1000
            RotationLogic.EVERY_6_HOURS -> 6L * 60 * 60 * 1000
            RotationLogic.DAILY -> 24L * 60 * 60 * 1000
            else -> return false
        }
        return nowMs - last >= gap
    }
    fun noteRotated(prefs: PrefsStore, nowMs: Long = System.currentTimeMillis()) {
        prefs.setRedditRotationLast(nowMs)
        prefs.setRedditRotationIndex(prefs.redditRotationIndex() + 1)
    }
    /** "Next wallpaper" jumps immediately (MAP §3.2). */
    fun nextNow(prefs: PrefsStore): RedditFetcher.Image? {
        val subs = prefs.redditSubs().split(',')
            .map { com.mizan.launcher.core.RedditWallpapers.cleanSubreddit(it) }
            .filter { com.mizan.launcher.core.RedditWallpapers.isValidSubreddit(it) }
        val sub = subs.getOrNull((prefs.redditRotationIndex() + 1).mod(subs.size.coerceAtLeast(1)))
            ?: return null
        val img = RedditFetcher.fetchTopOfWeek(prefs, sub)
        if (img != null) noteRotated(prefs)
        return img
    }
}
