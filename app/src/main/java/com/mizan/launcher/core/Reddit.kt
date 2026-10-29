package com.mizan.launcher.core

/**
 * Reddit wallpaper source — pure parsing + validation, no Android imports.
 *
 * A subreddit's top listing (https://www.reddit.com/r/<sub>/top.json)
 * contains a mix of image posts, videos, galleries, text posts, ads and
 * stickied moderator posts. This parser keeps only posts that can
 * actually become a wallpaper:
 *
 * ACCEPTED
 * - Direct image URLs: host i.redd.it, path ending .jpg/.jpeg/.png
 * - Otherwise, the post's preview image (images[0].source.url, HTML
 *   entities un-escaped) when the post itself links to an image page
 * - Landscape preferred: when preview dimensions are known, portrait
 *   images (height > width) are dropped
 *
 * REJECTED
 * - Stickied posts (mod announcements, not wallpapers)
 * - Video posts (is_video, or a v.redd.it URL)
 * - Gallery posts (is_gallery) — a gallery has no single wallpaper file;
 *   rejected rather than guessing at its first image
 * - NSFW posts (over_18) unless [allowNsfw] is true (default false;
 *   behind an explicit Settings toggle)
 * - Anything without a usable http(s) image URL
 *
 * Malformed JSON yields an empty list (fail-open), never an exception.
 */

/** One wallpaper candidate from a subreddit listing. */
data class RedditImage(
    val title: String,
    val url: String,
    val width: Int,
    val height: Int,
    val subreddit: String,
    /** Original post URL (for the picker label / future open-in-browser). */
    val postUrl: String
)

object RedditWallpapers {

    /** Default subreddits shipped with v1.1. */
    val DEFAULT_SUBREDDITS: List<String> = listOf("wallpaper", "wallpapers", "EarthPorn")

    /**
     * A subreddit name is 2–21 chars of letters, digits and underscores.
     * Anything else (spaces, slashes, "r/..." prefixes) is rejected so a
     * typo in Settings can never build a nonsense URL.
     */
    fun isValidSubreddit(name: String): Boolean {
        val n = name.trim()
        if (n.length !in 2..21) return false
        return n.all { it.isLetterOrDigit() || it == '_' }
    }

    /** Clean a user-typed subreddit name: trims, strips an "r/" prefix. */
    fun cleanSubreddit(name: String): String {
        var n = name.trim()
        if (n.startsWith("r/", ignoreCase = true)) n = n.substring(2)
        if (n.startsWith("/r/", ignoreCase = true)) n = n.substring(3)
        return n.trim()
    }

    /** Listing URL for one subreddit's top posts of the week. */
    fun listingUrl(subreddit: String, limit: Int = 25): String =
        "https://www.reddit.com/r/${cleanSubreddit(subreddit)}/top.json?t=week&limit=$limit"

    /**
     * Parse one subreddit's listing JSON into wallpaper candidates.
     * [subreddit] is stamped onto results whose post doesn't carry one.
     */
    fun parseListing(jsonText: String?, subreddit: String = "", allowNsfw: Boolean = false): List<RedditImage> {
        val root = JsonMini.parse(jsonText).asObj() ?: return emptyList()
        val data = root["data"].asObj() ?: return emptyList()
        val children = data["children"].asArr() ?: return emptyList()
        val out = ArrayList<RedditImage>()
        for (child in children) {
            try {
                val post = child.asObj()?.get("data").asObj() ?: continue
                parsePost(post, subreddit, allowNsfw)?.let { out.add(it) }
            } catch (e: Exception) {
                // One odd post must not lose the rest of the listing.
            }
        }
        return out
    }

    private fun parsePost(post: Map<String, Any?>, fallbackSub: String, allowNsfw: Boolean): RedditImage? {
        if (post["stickied"].asBool() == true) return null
        if (post["is_video"].asBool() == true) return null
        if (post["is_gallery"].asBool() == true) return null // documented: galleries rejected
        if (!allowNsfw && post["over_18"].asBool() == true) return null

        val url = post["url"].asStr()?.trim().orEmpty()
        if (url.contains("v.redd.it")) return null

        // Preview metadata (dimensions + a CDN image URL) when present.
        val preview = post["preview"].asObj()
        val previewImage = preview?.get("images").asArr()?.firstOrNull().asObj()
        val previewSource = previewImage?.get("source").asObj()
        val previewUrl = previewSource?.get("url").asStr()?.let { unescapeUrl(it) }
        val width = previewSource?.get("width").asInt() ?: post["thumbnail_width"].asInt() ?: 0
        val height = previewSource?.get("height").asInt() ?: post["thumbnail_height"].asInt() ?: 0

        val direct = isDirectImageUrl(url)
        val imageUrl = when {
            direct -> url
            !previewUrl.isNullOrBlank() && isHttpUrl(previewUrl) -> previewUrl
            else -> return null
        }

        // Landscape preferred: drop known-portrait images.
        if (width > 0 && height > 0 && height > width) return null

        val sub = post["subreddit"].asStr()?.takeIf { it.isNotBlank() } ?: fallbackSub
        val permalink = post["permalink"].asStr().orEmpty()
        return RedditImage(
            title = post["title"].asStr()?.trim().orEmpty(),
            url = imageUrl,
            width = width,
            height = height,
            subreddit = sub,
            postUrl = if (permalink.isNotBlank()) "https://www.reddit.com$permalink" else url
        )
    }

    /** Direct image file hosted on Reddit's image CDN. */
    fun isDirectImageUrl(url: String): Boolean {
        if (!isHttpUrl(url)) return false
        val lower = url.lowercase()
        val path = lower.substringBefore('?').substringBefore('#')
        if (!path.endsWith(".jpg") && !path.endsWith(".jpeg") && !path.endsWith(".png")) return false
        return lower.contains("://i.redd.it/")
    }

    private fun isHttpUrl(url: String): Boolean =
        url.startsWith("https://") || url.startsWith("http://")

    /** Reddit preview URLs arrive HTML-escaped (&amp;). */
    private fun unescapeUrl(url: String): String =
        url.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
}

/**
 * The endpoint fallback chain (v1.6), ordered by live evidence.
 *
 * Reddit now refuses unauthenticated `.json` listings from many
 * networks (the launcher got HTTP 403 HTML on Haythem's TV, and the
 * same 403 reproduces from this build machine with both the app's
 * old User-Agent and a browser User-Agent — it is not a UA problem).
 * Live tests 2026-10-21 (see spec §14.3):
 *   www.reddit.com .json → 403 (app UA and browser UA alike)
 *   api.reddit.com .json → 403
 *   old.reddit.com .json → 302 to a login page (no JSON at all)
 *   www.reddit.com .rss  → 200 — the Atom feed still works
 *
 * So the chain tries the rich .json routes first (they carry
 * dimensions, stickied and over_18 flags), then falls back to the
 * RSS/Atom feed of the same "top of the week" listing. An endpoint
 * only counts as usable when it yields at least one candidate —
 * a 200 login page parses to zero and the chain moves on.
 */
object RedditEndpoints {

    enum class Kind { JSON, RSS }

    data class Endpoint(val id: String, val kind: Kind, val url: String)

    /** The ordered chain for one subreddit. */
    fun chain(subreddit: String): List<Endpoint> {
        val sub = RedditWallpapers.cleanSubreddit(subreddit)
        // v3.16: RSS FIRST. Reddit now answers its anonymous JSON
        // listings with a 403 block page (measured on this box: all
        // three .json hosts), while the Atom feed still returns the
        // same posts. Trying JSON first therefore cost three wasted
        // round trips — and three recorded 403s — before the endpoint
        // that works. JSON stays as the fallback for the day Reddit
        // reverses that.
        return listOf(
            Endpoint("www-rss", Kind.RSS,
                "https://www.reddit.com/r/$sub/top/.rss?t=week"),
            Endpoint("www-json", Kind.JSON,
                "https://www.reddit.com/r/$sub/top.json?t=week&limit=25"),
            Endpoint("api-json", Kind.JSON,
                "https://api.reddit.com/r/$sub/top.json?t=week&limit=25"),
            Endpoint("old-json", Kind.JSON,
                "https://old.reddit.com/r/$sub/top.json?t=week&limit=25")
        )
    }

    /** An endpoint is usable only when it produced candidates. */
    fun isUsable(candidateCount: Int): Boolean = candidateCount > 0

    /** Accept header per kind (the fetcher sends it verbatim). */
    fun acceptHeader(kind: Kind): String = when (kind) {
        Kind.JSON -> "application/json"
        Kind.RSS -> "application/atom+xml, application/rss+xml, text/xml"
    }
}

/**
 * Parser for the RSS/Atom fallback (v1.6) — pure, no Android imports.
 *
 * A `top/.rss` entry carries: a `<title>` (wallpaper subs usually
 * embed the resolution, e.g. "Norway Sea [3840x2160]"), a comments
 * permalink, a `media:thumbnail` preview, and — in the HTML-escaped
 * content — a "[link]" anchor pointing at the full-size file on
 * i.redd.it. The parser takes the full-size i.redd.it file when one
 * is present, else the thumbnail preview (640 px — small for a TV
 * but a real wallpaper beats none, and it is the documented
 * fallback). Dimensions come from the title's [WxH] tag when present
 * and drive the same landscape preference as the JSON path.
 *
 * What RSS cannot tell us (documented, accepted): there is no
 * stickied flag and no over_18 flag in the feed. Logged-out feeds
 * omit most NSFW posts; a title literally tagged "nsfw" is still
 * rejected unless [allowNsfw]. Malformed XML yields an empty list.
 */
object RedditRss {

    private val entryRe = Regex("(?s)<entry>(.*?)</entry>")
    private val titleRe = Regex("(?s)<title>(.*?)</title>")
    private val thumbRe = Regex("media:thumbnail[^>]*url=\"([^\"]+)\"")
    private val dimsRe = Regex("\\[(\\d{3,5})\\s*[x×]\\s*(\\d{3,5})]")
    private val directRe = Regex("https://i\\.redd\\.it/[A-Za-z0-9]+\\.(?:jpg|jpeg|png)")

    fun parseFeed(xmlText: String?, subreddit: String = "", allowNsfw: Boolean = false): List<RedditImage> {
        if (xmlText.isNullOrBlank()) return emptyList()
        val out = ArrayList<RedditImage>()
        for (m in entryRe.findAll(xmlText)) {
            try {
                parseEntry(m.groupValues[1], subreddit, allowNsfw)?.let { out.add(it) }
            } catch (e: Exception) {
                // One odd entry must not lose the rest of the feed.
            }
        }
        return out
    }

    private fun parseEntry(entry: String, fallbackSub: String, allowNsfw: Boolean): RedditImage? {
        val title = titleRe.find(entry)?.groupValues?.get(1)
            ?.let { xmlUnescape(it) }?.trim().orEmpty()
        if (!allowNsfw && title.contains("nsfw", ignoreCase = true)) return null

        // Permalink: the entry's alternate link.
        val postUrl = Regex("(?s)<link[^>]*rel=\"alternate\"[^>]*href=\"([^\"]+)\"")
            .find(entry)?.groupValues?.get(1)
            ?: Regex("(?s)<link[^>]*href=\"([^\"]+)\"").find(entry)?.groupValues?.get(1)
            ?: ""

        val thumbnail = thumbRe.find(entry)?.groupValues?.get(1)
            ?.let { xmlUnescape(it) }

        // The escaped HTML content holds the full-size file link.
        val content = xmlUnescape(entry)
        val direct = directRe.find(content)?.value

        val imageUrl = when {
            direct != null -> direct
            !thumbnail.isNullOrBlank() -> thumbnail
            else -> return null
        }

        val dims = dimsRe.find(title)
        val width = dims?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val height = dims?.groupValues?.get(2)?.toIntOrNull() ?: 0
        // Landscape preferred, same rule as the JSON parser.
        if (width > 0 && height > 0 && height > width) return null

        return RedditImage(
            title = title,
            url = imageUrl,
            width = width,
            height = height,
            subreddit = fallbackSub,
            postUrl = postUrl
        )
    }

    /** XML entity un-escaping (named + decimal numeric references). */
    fun xmlUnescape(s: String): String {
        var out = s
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&#32;", " ")
        out = Regex("&#(\\d+);").replace(out) { m ->
            (m.groupValues[1].toIntOrNull()?.toChar() ?: '�').toString()
        }
        // &amp; last, so "&amp;lt;" becomes "&lt;" and not "<".
        return out.replace("&amp;", "&")
    }
}

/**
 * Visible fetch status (v1.6). Until now every Reddit failure was
 * silent fail-open — the feature looked simply "broken" for two
 * versions. Every attempt is now recorded (endpoint, subreddit, HTTP
 * code or error, candidates, time) and the latest run summarised into
 * one human line for Settings and the picker; the formatting lives
 * here so it is sim-tested.
 */
object RedditStatus {

    /** One recorded fetch attempt. [result] is "HTTP 403", "HTTP 200", or an error label. */
    data class Attempt(
        val endpointId: String,
        val subreddit: String,
        val result: String,
        val candidates: Int,
        val atMs: Long
    )

    /** "r/wallpaper · www-json · HTTP 403 · 0 photos" */
    fun attemptLine(a: Attempt): String =
        "r/${a.subreddit} · ${a.endpointId} · ${a.result} · ${a.candidates} " +
            (if (a.candidates == 1) "photo" else "photos")

    /**
     * The one-line summary of a whole fetch run, shown in Settings:
     * success — "✓ 18 photos · 2:41 PM"; failure — the last subreddit's
     * final result, "✗ r/wallpaper HTTP 403 · 0 photos · 2:41 PM".
     */
    fun runSummary(attempts: List<Attempt>, totalPhotos: Int, atMs: Long): String {
        val time = java.text.SimpleDateFormat("h:mm a", java.util.Locale.US)
            .format(java.util.Date(atMs))
        if (totalPhotos > 0) return "✓ $totalPhotos " +
            (if (totalPhotos == 1) "photo" else "photos") + " · $time"
        val last = attempts.lastOrNull()
        return if (last != null) {
            "✗ r/${last.subreddit} ${last.result} · 0 photos · $time"
        } else {
            "✗ no subreddits to try · $time"
        }
    }
}

/**
 * Line codec for the cached candidate list, so the picker still works
 * offline with already-downloaded images. Same escaping scheme as the
 * layout codec (% first, then | , newline). Fail-open on bad lines.
 */
object RedditCacheCodec {

    private fun esc(s: String): String = s
        .replace("%", "%25").replace("|", "%7C").replace(",", "%2C").replace("\n", "%0A")

    private fun unesc(s: String): String = s
        .replace("%0A", "\n").replace("%2C", ",").replace("%7C", "|").replace("%25", "%")

    fun encode(images: List<RedditImage>): String =
        images.joinToString("\n") { img ->
            listOf(esc(img.title), esc(img.url), img.width.toString(), img.height.toString(),
                esc(img.subreddit), esc(img.postUrl)).joinToString("|")
        }

    fun decode(raw: String?): List<RedditImage> {
        if (raw.isNullOrBlank()) return emptyList()
        val out = ArrayList<RedditImage>()
        for (line in raw.lines()) {
            try {
                val p = line.split("|")
                if (p.size != 6) continue
                out.add(
                    RedditImage(
                        title = unesc(p[0]), url = unesc(p[1]),
                        width = p[2].toIntOrNull() ?: 0, height = p[3].toIntOrNull() ?: 0,
                        subreddit = unesc(p[4]), postUrl = unesc(p[5])
                    )
                )
            } catch (e: Exception) {
                // Skip the bad line, keep the rest of the cache.
            }
        }
        return out
    }
}
