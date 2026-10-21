package com.mizan.launcher.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.mizan.launcher.core.RedditCacheCodec
import com.mizan.launcher.core.RedditImage
import com.mizan.launcher.core.RotationLogic
import kotlin.concurrent.thread

/**
 * Reddit wallpaper advancement (v1.4), shared by the home screen's
 * auto-rotation check and Settings' "Next wallpaper" row.
 *
 * Advances to the next image in the already-fetched candidate list
 * (the picker cache). When the cache is empty it fetches a fresh list
 * first; when an advance wraps past the end of the list it also kicks
 * a background refresh so the NEXT cycle has fresh candidates.
 * Everything is fail-open: any failure leaves the current wallpaper,
 * the rotation index, and the last-rotation timestamp untouched.
 */
object WallpaperRotator {

    /**
     * Advance now. [onApplied] runs on the main thread after the new
     * wallpaper is in prefs (callers rebuild / toast there). It does
     * not run at all when nothing could be applied.
     */
    fun advanceReddit(ctx: Context, prefs: Prefs, onApplied: () -> Unit) {
        val cached = RedditCacheCodec.decode(prefs.redditCache)
        if (cached.isNotEmpty()) {
            val next = RotationLogic.nextIndex(prefs.redditRotationIndex, cached.size)
            if (next < 0) return
            val wraps = next == 0 && prefs.redditRotationIndex == cached.size - 1
            applyCandidate(ctx, prefs, cached[next], next, onApplied)
            if (wraps) refreshCacheInBackground(ctx, prefs)
            return
        }
        // Nothing cached yet: fetch a fresh list, then apply its first.
        val subs = prefs.redditSubs.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        RedditFetcher.fetch(subs, prefs.redditAllowNsfw, ctx) { images ->
            if (images.isEmpty()) {
                // v1.6: an empty rotation fetch used to fail silently.
                // The fetcher already recorded its endpoint attempts;
                // add the rotation context so diagnostics shows the
                // advance itself was the thing that found nothing.
                Prefs.noteRedditAttempt(
                    ctx,
                    com.mizan.launcher.core.RedditStatus.attemptLine(
                        com.mizan.launcher.core.RedditStatus.Attempt(
                            "rotation", subs.firstOrNull() ?: "", "no candidates", 0,
                            System.currentTimeMillis()
                        )
                    )
                )
                return@fetch
            }
            Handler(Looper.getMainLooper()).post {
                try {
                    prefs.redditCache = RedditCacheCodec.encode(images)
                } catch (e: Exception) {
                    // Cache write is best-effort; applying still proceeds.
                }
                applyCandidate(ctx, prefs, images[0], 0, onApplied)
            }
        }
    }

    /** Download (if needed) and apply one candidate, updating rotation state. */
    private fun applyCandidate(
        ctx: Context,
        prefs: Prefs,
        item: RedditImage,
        index: Int,
        onApplied: () -> Unit
    ) {
        thread(name = "mizan-rotate-dl") {
            val file = RedditFetcher.download(ctx, item)
            if (file == null) return@thread
            Handler(Looper.getMainLooper()).post {
                try {
                    prefs.wallpaperKind = "reddit"
                    prefs.wallpaperValue = file.absolutePath
                    prefs.redditRotationIndex = index
                    prefs.redditRotationLast = System.currentTimeMillis()
                    AccentUtil.recompute(ctx, prefs)
                    onApplied()
                } catch (e: Exception) {
                    Prefs.noteError(ctx, "wallpaperRotate", e)
                }
            }
        }
    }

    /** Quietly refresh the candidate cache for the next rotation cycle. */
    private fun refreshCacheInBackground(ctx: Context, prefs: Prefs) {
        val subs = prefs.redditSubs.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        RedditFetcher.fetch(subs, prefs.redditAllowNsfw, ctx) { images ->
            if (images.isEmpty()) return@fetch
            Handler(Looper.getMainLooper()).post {
                try {
                    prefs.redditCache = RedditCacheCodec.encode(images)
                    // The fresh list may order differently; restart the
                    // cycle from the top on the next advance.
                    prefs.redditRotationIndex = -1
                } catch (e: Exception) {
                    // Best-effort only.
                }
            }
        }
    }
}
