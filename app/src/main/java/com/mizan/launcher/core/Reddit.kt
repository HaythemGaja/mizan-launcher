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
