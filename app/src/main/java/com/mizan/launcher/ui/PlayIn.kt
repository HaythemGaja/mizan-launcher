package com.mizan.launcher.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.mizan.launcher.core.PlayInLogic
import com.mizan.launcher.core.WatchMatch
import com.mizan.launcher.data.IptvClient
import com.mizan.launcher.data.PlayItem
import com.mizan.launcher.data.WatchPlay
import kotlin.concurrent.thread

/**
 * "Play in…" (v3.1, ask 40): hand a stream Sreeb already holds to
 * another installed video app, from the hold-OK card menus and the
 * player options bar.
 *
 * BOUNDARY (do not cross): only IPTV catalogue stream URLs Sreeb
 * already has are handed over. TMDB ids/titles are NEVER passed to
 * another app's catalogue or search — there is no "play this TMDB
 * title in another app" wiring anywhere in this feature. Discovery is
 * purely via the intent query (no hardcoded package names); an
 * app that does not register for video intents simply does not
 * appear in the picker.
 *
 * Privacy: an Xtream stream URL carries the panel credentials in
 * its query string. It leaves the app ONLY via the user's explicit
 * pick in this dialog, and never enters logs, diagnostics, or
 * DiagLog (only the target package is noted, and even that only
 * as a plain "play-in" breadcrumb without the URL).
 *
 * Threading: the PackageManager query runs on a worker (label
 * loads can hit disk); the dialog and the launch run on main.
 */
object PlayIn {

    /** The exact no-handler wording from the brief. */
    const val NO_HANDLER_TOAST = "No other video app can open this stream"

    /**
     * Offer the picker for an already-resolved stream URL.
     * Fail-open: no handler → toast; a dead activity → nothing.
     */
    fun show(activity: Activity, title: String, url: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        thread(name = "mizan-play-in-query") {
            val base = Intent(Intent.ACTION_VIEW)
                .setDataAndType(Uri.parse(url), "video/*")
            val candidates = try {
                val infos = activity.packageManager.queryIntentActivities(
                    base, PackageManager.MATCH_DEFAULT_ONLY
                )
                PlayInLogic.filter(
                    infos.map {
                        PlayInLogic.Candidate(
                            it.activityInfo.packageName,
                            it.loadLabel(activity.packageManager)?.toString()
                                ?: it.activityInfo.packageName
                        )
                    },
                    activity.packageName
                )
            } catch (e: Exception) {
                emptyList()
            }
            Handler(Looper.getMainLooper()).post {
                try {
                    if (activity.isFinishing || activity.isDestroyed) return@post
                    if (!PlayInLogic.hasHandler(candidates)) {
                        Toast.makeText(activity, NO_HANDLER_TOAST, Toast.LENGTH_LONG).show()
                        return@post
                    }
                    val labels = candidates.map { it.label }.toTypedArray()
                    AlertDialog.Builder(activity)
                        .setTitle("Play \"$title\" in…")
                        .setItems(labels) { _, which ->
                            val picked = candidates.getOrNull(which) ?: return@setItems
                            try {
                                val intent = Intent(base).setPackage(picked.pkg)
                                activity.startActivity(intent)
                            } catch (e: Exception) {
                                Toast.makeText(
                                    activity,
                                    "Couldn't open ${picked.label}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                        .show()
                } catch (e: Exception) {
                    // A dead activity must never kill the poster.
                }
            }
        }
    }

    /** Offer the picker for a PlayItem (URL resolved via IptvClient.playUrl). */
    fun showForPlayItem(activity: Activity, item: PlayItem) {
        val url = try {
            IptvClient.playUrl(activity, item)
        } catch (e: Exception) {
            null
        }
        if (url.isNullOrBlank()) {
            Toast.makeText(activity, "Can't open this stream in another app", Toast.LENGTH_LONG)
                .show()
            return
        }
        show(activity, item.title, url)
    }

    /**
     * Offer the picker for a matched tracker episode: resolve the
     * series entry, then the episode id + extension from the
     * series-info cache (fetching through it when cold — the same
     * resolution WatchPlay.playEpisode performs, minus the launch).
     * Any miss fails open with a toast, never a dead end.
     */
    fun showForEpisode(
        activity: Activity,
        match: WatchMatch.Match,
        season: Int,
        episode: Int,
        title: String
    ) {
        WatchPlay.resolveSeries(activity, match) { series ->
            if (activity.isFinishing || activity.isDestroyed) return@resolveSeries
            if (series == null) {
                Toast.makeText(activity, "Couldn't find this title on your IPTV", Toast.LENGTH_LONG)
                    .show()
                return@resolveSeries
            }
            fun start(info: com.mizan.launcher.core.IptvLogic.SeriesInfo?) {
                if (activity.isFinishing || activity.isDestroyed) return
                val ep = info?.let { WatchMatch.resolveEpisode(it, season, episode) }
                if (ep == null) {
                    Toast.makeText(
                        activity, "That episode isn't on your IPTV", Toast.LENGTH_LONG
                    ).show()
                    return
                }
                showForPlayItem(
                    activity,
                    PlayItem(
                        kind = com.mizan.launcher.core.IptvLogic.KIND_EPISODE,
                        source = com.mizan.launcher.core.IptvLogic.SRC_XTREAM,
                        id = ep.id, title = title, meta = ep.title,
                        ext = ep.ext, catKey = series.catKey, seriesId = series.id
                    )
                )
            }
            val cached = IptvClient.cachedSeriesInfo(activity, series.id)
            if (cached != null) {
                start(cached)
            } else {
                Toast.makeText(activity, "Finding the episode on your IPTV…", Toast.LENGTH_SHORT)
                    .show()
                IptvClient.fetchSeriesInfoAsync(activity, series.id) { info -> start(info) }
            }
        }
    }
}
