package com.mizan.launcher.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.mizan.launcher.core.Design
import com.mizan.launcher.core.IptvLogic
import com.mizan.launcher.core.LibraryLogic
import com.mizan.launcher.core.TrackerLogic
import com.mizan.launcher.core.WatchMatch
import com.mizan.launcher.data.IptvClient
import com.mizan.launcher.data.Prefs
import com.mizan.launcher.data.TmdbImages
import com.mizan.launcher.data.TrackerActions
import com.mizan.launcher.data.TrackerClient
import com.mizan.launcher.data.WatchPlay

/**
 * Title details (v2.1) — one tracked title, in-launcher: backdrop
 * header, ★ rating, overview, and for shows a seasons → episodes
 * checklist. Episode/season data comes from TMDB through the
 * launcher's OWN direct key (data/TmdbDirect; v3.7 — the
 * tracker-proxy tmdb_get path died with the tracker app).
 * Its error strings drive the states — no_key → a friendly
 * "add your TMDB key in Sreeb Settings" state, http_/io → a
 * retry-able error line, bad_path → surfaced in Diagnostics
 * (tmdb: line).
 *
 * v3.7: every write is LOCAL (data/TrackerActions →
 * data/LibraryLocal) and mirrored in the pending overlay,
 * so toggles render instantly and converge when the store
 * worker lands. The launcher IS the tracker now, so the
 * old "episode tools unlock once this title is in the
 * tracker" gate is dead: tools are available for tracked
 * titles, and an untracked title gets an "Add to my list"
 * action that adds it locally — then the tools unlock
 * (the add's landing rebuild flips the screen into the
 * tracked state). A title added but whose store write
 * hasn't landed yet ("pending") still shows its note.
 *
 * Fail-open: with TMDB unreachable the screen still shows what the
 * snapshots know (title, poster, progress) plus the error line.
 *
 * v2.6 IPTV episode fallback (spec §26): when the TMDB details or
 * season load fails AND the title has a Watch match, the episode
 * lists are built from the IPTV side instead — the same
 * fingerprint-cached get_series_info document WatchPlay plays
 * from, shaped by core/LibraryLogic into the exact SeasonInfo /
 * EpisodeInfo lists the TMDB path renders, so watched overlays,
 * mark-watched and mark-season all work unchanged under a clear
 * "Episode list from IPTV — TMDB unreachable" header. No match or
 * no series info → the plain TMDB error screen, as before.
 */
class DetailsActivity : Activity() {

    companion object {
        const val EXTRA_ID = "tmdb_id"
        const val EXTRA_TYPE = "tmdb_type"
        const val EXTRA_TITLE = "title"
        const val EXTRA_POSTER = "poster"
    }

    private lateinit var prefs: Prefs
    private val accent: Int = Design.NETFLIX_RED

    private var tmdbId: Long = 0
    private var type: String = "tv"
    private var intentTitle: String = ""
    private var intentPoster: String = ""

    // Snapshot state (recomputed per rebuild).
    private var snapShow: LibraryLogic.LibShow? = null
    private var snapMovie: TrackerLogic.MovieItem? = null
    private var inSnapshot: Boolean = false

    // v2.3 Watch: this title's IPTV match (recomputed per rebuild
    // from data/WatchPlay's cache); null = not on the IPTV side.
    private var wMatch: WatchMatch.Match? = null

    // TMDB state.
    private var details: LibraryLogic.TitleDetails? = null
    private var detailsLoading = false
    private var detailsError: String? = null
    private var detailsTried = false
    private val seasonEpisodes = HashMap<Int, List<LibraryLogic.EpisodeInfo>>()
    private var seasonLoading: Int = -1
    private var seasonError: String? = null
    private var selectedSeason: Int = -1

    // v2.6 IPTV fallback state: the fetched series info, the
    // shaped season list (used when TMDB details never landed),
    // and which entries of seasonEpisodes came from the IPTV side
    // (so a later TMDB recovery can drop them and refill).
    private var iptvInfo: IptvLogic.SeriesInfo? = null
    private var iptvSeasons: List<LibraryLogic.SeasonInfo>? = null
    private val iptvEpisodeSeasons = HashSet<Int>()
    private var iptvFallbackLoading = false
    private var iptvFallbackTried = false

    private var contentBox: LinearLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        tmdbId = intent.getLongExtra(EXTRA_ID, 0L)
        type = if (intent.getStringExtra(EXTRA_TYPE) == "movie") "movie" else "tv"
        intentTitle = intent.getStringExtra(EXTRA_TITLE) ?: ""
        intentPoster = intent.getStringExtra(EXTRA_POSTER) ?: ""

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Design.NETFLIX_BG)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(Ui.dp(this, 48), Ui.dp(this, 22), Ui.dp(this, 48), Ui.dp(this, 30))
        scroll.addView(box)
        contentBox = box
        setContentView(scroll)

        com.mizan.launcher.data.DiagLog.note("details opened")
        rebuild()
        loadDetails()
        TrackerClient.refreshAsync(this) {
            if (readSnapshotState()) rebuild()
        }
    }

    // ------------------------------------------------------------ state

    // v3.6 (ask 53): NO whole-document parse here. This
    // screen needs exactly ONE title's watched set, so it
    // reads that title's episode file from the tracker
    // store (one small file, bounded by that show's
    // episodes). When the store has no episode file for
    // the title yet, the title line's count still renders
    // (count-only LibShow) and the checklist fills when
    // the store lands — the whole library is never parsed
    // to show one title. Movies use snapMovie, not this.
    private fun libShows(snap: TrackerClient.Snapshots): List<LibraryLogic.LibShow> {
        if (type == "movie") return emptyList()
        val show = try {
            com.mizan.launcher.data.TrackerStoreDisk.libShowFor(this, tmdbId)
        } catch (e: Exception) {
            null
        } ?: snap.tracked.firstOrNull { it.tmdbId == tmdbId }?.let {
            LibraryLogic.LibShow(it.tmdbId, it.name, false, it.planned, emptySet(), watchedCount = it.watchedCount)
        } ?: return emptyList()
        return listOf(show)
    }

    /** (Re)read this title's snapshot + overlay state; true if it changed. */
    private fun readSnapshotState(): Boolean {
        val snap = TrackerClient.cached(this)
        val shows = libShows(snap)
        val newShow = shows.firstOrNull { it.tmdbId == tmdbId }
        val newMovie = snap.movies.firstOrNull { it.tmdbId == tmdbId }
        val newIn = if (type == "movie") newMovie != null else newShow != null
        val changed = newShow != snapShow || newMovie != snapMovie || newIn != inSnapshot
        snapShow = newShow
        snapMovie = newMovie
        inSnapshot = newIn
        return changed
    }

    private fun overlayEntry(): LibraryLogic.PendingTitle? =
        LibraryLogic.overlayEntry(
            LibraryLogic.decodePending(prefs.pendingOverlay), type, tmdbId
        )

    /** True while the title is launcher-added but its store write hasn't landed yet. */
    private fun isPending(): Boolean =
        !inSnapshot && overlayEntry()?.addedTitle != null

    /** The show's effective watched set (snapshot ⊕ overlay). */
    private fun effectiveWatched(): Set<String> {
        val show = snapShow ?: return overlayEntry()?.watchedAdd ?: emptySet()
        return LibraryLogic.effectiveWatched(show, overlayEntry())
    }

    private fun effectiveMuted(): Boolean {
        overlayEntry()?.muted?.let { return it }
        return snapShow?.muted ?: false
    }

    private fun effectiveMovieWatched(): Boolean {
        overlayEntry()?.movieWatched?.let { return it }
        return snapMovie?.watched ?: false
    }

    private fun displayTitle(): String =
        details?.title?.ifBlank { intentTitle } ?: intentTitle

    private fun displayPoster(): String {
        val d = details
        if (d != null && d.poster.isNotBlank()) return d.poster
        if (intentPoster.isNotBlank()) return intentPoster
        val snap = TrackerClient.cached(this)
        snap.cont.firstOrNull { it.tmdbId == tmdbId }?.poster
            ?.takeIf { it.isNotBlank() }?.let { return it }
        snap.upNext.firstOrNull { it.tmdbId == tmdbId }?.poster
            ?.takeIf { it.isNotBlank() }?.let { return it }
        return TrackerClient.posterCache(this)[
            LibraryLogic.posterKey(type, tmdbId)
        ] ?: ""
    }

    // ------------------------------------------------------------ TMDB loads

    private fun loadDetails() {
        if (detailsLoading || tmdbId <= 0) return
        detailsLoading = true
        detailsTried = true
        TrackerClient.tmdbGet(this, LibraryLogic.detailsPath(type, tmdbId)) { json, error ->
            if (isFinishing || isDestroyed) return@tmdbGet
            detailsLoading = false
            if (error != null) {
                detailsError = error
            } else {
                detailsError = null
                details = LibraryLogic.parseDetails(json, type)
                if (details == null) {
                    detailsError = "io" // unparseable body — retry-able bucket
                } else {
                    val d = details!!
                    if (d.poster.isNotBlank()) {
                        TrackerClient.notePoster(this, type, tmdbId, d.poster)
                    }
                    if (d.backdrop.isNotBlank()) {
                        // Learned backdrops let this title feature in
                        // the hero when it appears as a For You pick
                        // (the /foryou shape carries no backdrop).
                        TrackerClient.noteBackdrop(this, type, tmdbId, d.backdrop)
                    }
                    if (type == "tv" && selectedSeason < 0) {
                        selectedSeason = defaultSeason(d)
                    }
                }
            }
            if (detailsError == null) {
                // TMDB is back: any IPTV-shaped episodes are stale
                // stand-ins — drop them so TMDB refills the lists.
                clearIptvFallback()
            }
            rebuild()
            maybeLoadSeason()
            if (detailsError != null) {
                // v2.6: TMDB failed — try the IPTV episode fallback.
                maybeStartIptvFallback()
            }
        }
    }

    /** First season with something unwatched; else first regular; else first. */
    private fun defaultSeason(d: LibraryLogic.TitleDetails): Int =
        defaultSeasonFrom(d.seasons)

    /** [defaultSeason] over a bare season list (the IPTV fallback's shape). */
    private fun defaultSeasonFrom(seasons: List<LibraryLogic.SeasonInfo>): Int {
        val watched = effectiveWatched()
        val regular = seasons.filter { it.number >= 1 }
        for (season in regular) {
            val allWatched = (1..season.episodeCount).all {
                LibraryLogic.epKey(season.number, it) in watched
            }
            if (!allWatched) return season.number
        }
        return regular.firstOrNull()?.number
            ?: seasons.firstOrNull()?.number ?: -1
    }

    private fun maybeLoadSeason() {
        val season = selectedSeason
        if (type != "tv" || season < 0) return
        if (seasonEpisodes.containsKey(season) || seasonLoading == season) return
        seasonLoading = season
        seasonError = null
        TrackerClient.tmdbGet(this, LibraryLogic.seasonPath(tmdbId, season)) { json, error ->
            if (isFinishing || isDestroyed) return@tmdbGet
            if (seasonLoading == season) seasonLoading = -1
            if (error != null) {
                seasonError = error
                rebuild()
                // v2.6: fill just this season from the IPTV side.
                maybeFillSeasonFromIptv(season)
                return@tmdbGet
            }
            val episodes = LibraryLogic.parseSeasonEpisodes(json)
            if (episodes.isEmpty()) {
                seasonError = "io"
                rebuild()
                maybeFillSeasonFromIptv(season)
                return@tmdbGet
            }
            seasonEpisodes[season] = episodes
            iptvEpisodeSeasons.remove(season) // TMDB data now owns this season
            seasonError = null
            rebuild()
        }
    }

    // ------------------------------------------------------------ IPTV episode fallback (v2.6)

    /** Drop every IPTV-shaped stand-in (TMDB recovered and refills). */
    private fun clearIptvFallback() {
        if (iptvSeasons == null && iptvEpisodeSeasons.isEmpty() && iptvInfo == null) return
        for (s in iptvEpisodeSeasons) seasonEpisodes.remove(s)
        iptvEpisodeSeasons.clear()
        iptvSeasons = null
        iptvInfo = null
        iptvFallbackTried = false
    }

    /**
     * The series info for this title's Watch match — from the
     * in-memory copy, else IptvClient's fingerprint-cached
     * get_series_info (worker-side fetch, main-thread callback).
     * Null info = no match / no series / panel failure: every
     * caller then keeps the plain TMDB error screen (fail-open).
     */
    private fun withIptvInfo(action: (IptvLogic.SeriesInfo?) -> Unit) {
        iptvInfo?.let {
            action(it)
            return
        }
        val match = try {
            WatchPlay.matches(this)["tv|$tmdbId"]
        } catch (e: Exception) {
            null
        }
        if (match == null) {
            action(null)
            return
        }
        iptvFallbackLoading = true
        WatchPlay.resolveSeries(this, match) { series ->
            if (isFinishing || isDestroyed) {
                iptvFallbackLoading = false
                return@resolveSeries
            }
            if (series == null) {
                iptvFallbackLoading = false
                action(null)
                return@resolveSeries
            }
            IptvClient.fetchSeriesInfoAsync(this, series.id) { info ->
                iptvFallbackLoading = false
                if (isFinishing || isDestroyed) return@fetchSeriesInfoAsync
                if (info != null) iptvInfo = info
                action(info)
            }
        }
    }

    /**
     * Details-level fallback: TMDB details failed — build the
     * whole season/episode structure from the IPTV side. Runs at
     * most once per details failure (a manual Retry re-arms it).
     */
    private fun maybeStartIptvFallback() {
        if (type != "tv" || iptvFallbackTried) return
        iptvFallbackTried = true
        if (iptvInfo == null) {
            val match = try {
                WatchPlay.matches(this)["tv|$tmdbId"]
            } catch (e: Exception) {
                null
            }
            if (match == null) return // no IPTV twin — the TMDB error screen stands
        }
        rebuild() // paints the "trying your IPTV…" line while the fetch runs
        withIptvInfo { info ->
            if (isFinishing || isDestroyed) return@withIptvInfo
            if (details != null) {
                // TMDB recovered while the fetch ran — its lists win.
                rebuild()
                return@withIptvInfo
            }
            if (info == null) {
                rebuild()
                return@withIptvInfo
            }
            val seasons = LibraryLogic.iptvSeasonInfos(info)
            if (seasons.isEmpty()) {
                rebuild()
                return@withIptvInfo
            }
            iptvSeasons = seasons
            for (s in seasons) {
                seasonEpisodes[s.number] = LibraryLogic.iptvEpisodes(info, s.number)
                iptvEpisodeSeasons.add(s.number)
            }
            if (selectedSeason < 0 || seasonEpisodes[selectedSeason] == null) {
                selectedSeason = defaultSeasonFrom(seasons)
            }
            rebuild()
        }
    }

    /** Season-level fallback: TMDB details landed but one season's episodes didn't. */
    private fun maybeFillSeasonFromIptv(season: Int) {
        if (type != "tv" || season < 0) return
        if (seasonEpisodes.containsKey(season)) return
        if (iptvInfo == null) {
            val match = try {
                WatchPlay.matches(this)["tv|$tmdbId"]
            } catch (e: Exception) {
                null
            }
            if (match == null) return
        }
        withIptvInfo { info ->
            if (isFinishing || isDestroyed || info == null) return@withIptvInfo
            fillSeasonFromIptv(season, info)
        }
    }

    /** Shape one IPTV season into the episode map and re-render. */
    private fun fillSeasonFromIptv(season: Int, info: IptvLogic.SeriesInfo) {
        val eps = LibraryLogic.iptvEpisodes(info, season)
        if (eps.isEmpty()) {
            rebuild()
            return
        }
        // A TMDB load that landed in the meantime wins.
        if (seasonEpisodes.containsKey(season) && season !in iptvEpisodeSeasons) return
        seasonEpisodes[season] = eps
        iptvEpisodeSeasons.add(season)
        seasonError = null
        rebuild()
    }

    // ------------------------------------------------------------ render

    /**
     * v3.0: every Details build runs behind this guard (the
     * IptvActivity rule): an escape — including an inflation OOM
     * on a rich title — is recorded and replaced by a worded line,
     * never a black screen or a dead process.
     */
    private fun rebuild() {
        try {
            rebuildInner()
        } catch (e: Throwable) {
            Prefs.noteError(this, "detailsRebuild", e)
            val box = contentBox ?: return
            try {
                box.removeAllViews()
                val line = TextView(this)
                line.text = "This title hit a snag — press Back and try again. " +
                    "The error is noted in the launcher's diagnostics."
                line.setTextColor(0xFFB3B3B3.toInt())
                line.textSize = 17f
                box.addView(line)
            } catch (ignored: Throwable) {
                // Leave whatever is on screen rather than crash.
            }
        }
    }

    private fun rebuildInner() {
        val box = contentBox ?: return
        readSnapshotState()
        // v2.3: the Watch match for this title (ids only, cached).
        wMatch = try {
            WatchPlay.matches(this)["$type|$tmdbId"]
        } catch (e: Exception) {
            null
        }
        val focusTag = (currentFocus?.tag as? String)
        box.removeAllViews()

        // Backdrop header.
        val d = details
        val backdropPath = when {
            d != null && d.backdrop.isNotBlank() -> d.backdrop
            else -> displayPoster()
        }
        if (backdropPath.isNotBlank()) {
            val frame = FrameLayout(this)
            val fh = (resources.displayMetrics.heightPixels * 0.38f).toInt()
            frame.layoutParams = LinearLayout.LayoutParams(-1, fh)
            val hasBackdrop = d != null && d.backdrop.isNotBlank()
            val headerUrl = TmdbImages.url(if (hasBackdrop) "w780" else "w500", backdropPath)
            if (com.mizan.launcher.core.Artwork.headerFit(hasBackdrop) ==
                com.mizan.launcher.core.Artwork.Fit.FIT_COMPLETE
            ) {
                // v2.9 (ask C.14): no backdrop — the header would
                // centre-crop the 2:3 poster into a wide banner
                // and cut it. Same treatment as the home's
                // landscape cards: the poster complete over a
                // blurred fill of itself (the tiny decode).
                val fill = ImageView(this)
                fill.scaleType = ImageView.ScaleType.CENTER_CROP
                Ui.roundClip(fill, Design.RADIUS_PANEL, this)
                frame.addView(fill, FrameLayout.LayoutParams(-1, -1))
                TmdbImages.load(headerUrl, fill, maxDim = 64)
                val fg = ImageView(this)
                fg.scaleType = ImageView.ScaleType.FIT_CENTER
                Ui.roundClip(fg, Design.RADIUS_PANEL, this)
                frame.addView(fg, FrameLayout.LayoutParams(-1, -1))
                TmdbImages.load(headerUrl, fg)
            } else {
                val iv = ImageView(this)
                iv.scaleType = ImageView.ScaleType.CENTER_CROP
                Ui.roundClip(iv, Design.RADIUS_PANEL, this)
                frame.addView(iv, FrameLayout.LayoutParams(-1, -1))
                TmdbImages.load(headerUrl, iv)
            }
            box.addView(frame)
        }

        val title = TextView(this)
        title.text = displayTitle()
        title.setTextColor(0xFFFFFFFF.toInt())
        title.textSize = 30f
        title.setTypeface(title.typeface, Typeface.BOLD)
        val tlp = LinearLayout.LayoutParams(-1, -2)
        tlp.setMargins(0, Ui.dp(this, 14), 0, 0)
        title.layoutParams = tlp
        box.addView(title)

        // Meta: ★ rating · year · status · progress summary.
        val meta = ArrayList<String>()
        if (d != null) {
            TrackerLogic.ratingText(d.voteAverage)?.let { meta.add(it) }
            if (d.year.isNotBlank()) meta.add(d.year)
            if (d.status.isNotBlank()) meta.add(d.status)
            if (type == "tv" && d.seasons.isNotEmpty()) {
                val n = d.seasons.count { it.number >= 1 }
                if (n > 0) meta.add(if (n == 1) "1 season" else "$n seasons")
            }
        }
        if (type == "movie") {
            meta.add(if (effectiveMovieWatched()) "✓ Watched" else "Not watched")
        } else if (inSnapshot) {
            val watched = effectiveWatched()
            if (watched.isNotEmpty()) {
                meta.add(
                    if (watched.size == 1) "1 episode watched"
                    else "${watched.size} episodes watched"
                )
            }
        }
        if (meta.isNotEmpty()) {
            val mv = TextView(this)
            mv.text = meta.joinToString("  ·  ")
            mv.setTextColor(0xFFB3B3B3.toInt())
            mv.textSize = 16f
            val mlp = LinearLayout.LayoutParams(-1, -2)
            mlp.setMargins(0, Ui.dp(this, 4), 0, 0)
            mv.layoutParams = mlp
            box.addView(mv)
        }

        if (isPending()) {
            box.addView(
                noteLine(
                    "Pending — adding to your library… episode tools unlock when it lands."
                )
            )
        }

        val overview = d?.overview?.ifBlank { null } ?: snapshotOverview()
        if (!overview.isNullOrBlank()) {
            val ov = TextView(this)
            ov.text = overview
            ov.setTextColor(0xFFDDDDDD.toInt())
            ov.textSize = 15f
            val olp = LinearLayout.LayoutParams(-1, -2)
            olp.setMargins(0, Ui.dp(this, 10), 0, 0)
            ov.layoutParams = olp
            box.addView(ov)
        }

        // TMDB state lines.
        when {
            detailsLoading ->
                box.addView(noteLine("Loading details from TMDB…"))
            detailsError != null -> {
                box.addView(noteLine(LibraryLogic.tmdbUserLine(detailsError)))
                if (LibraryLogic.tmdbRetryable(detailsError)) {
                    val retry = actionButton("Retry", "retry") {
                        detailsError = null
                        iptvFallbackTried = false // a fresh failure may re-try the fallback
                        loadDetails()
                        rebuild()
                    }
                    box.addView(buttonRow(listOf(retry)))
                }
            }
            detailsTried && d == null ->
                box.addView(noteLine("No details came back from TMDB."))
        }

        // Action buttons.
        val buttons = ArrayList<View>()
        if (type == "movie" && inSnapshot) {
            val watched = effectiveMovieWatched()
            buttons.add(
                actionButton(if (watched) "↩ Mark unwatched" else "✓ Mark watched", "movie_toggle") {
                    TrackerActions.toggleMovie(this, prefs, tmdbId, !watched)
                    Toast.makeText(this, "Saved to your library", Toast.LENGTH_SHORT).show()
                    rebuild()
                }
            )
        }
        // v3.7: untracked titles get "Add to my list" (the
        // launcher IS the tracker now — no gate, no
        // tracker app to wait for). The add lands on a
        // worker; its landing callback re-reads the store
        // and rebuilds, which flips the screen into the
        // tracked state and unlocks the episode tools.
        // Pending titles (added, write in flight) show
        // their note above instead of a second Add button.
        if (!inSnapshot && !isPending()) {
            buttons.add(
                actionButton("＋ Add to my list", "add_to_list") {
                    TrackerActions.addTitle(
                        this, prefs, tmdbId, type, displayTitle(), displayPoster(), ""
                    ) {
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) {
                                readSnapshotState()
                                rebuild()
                            }
                        }
                    }
                    Toast.makeText(this, "Added to your list", Toast.LENGTH_SHORT).show()
                    rebuild()
                }
            )
        }
        // v2.3 Watch: a matched movie plays in place. The auto-mark
        // token rides only for in-snapshot titles (a For You /
        // search-opened movie that isn't tracked yet just plays).
        val movieMatch = if (type == "movie") wMatch else null
        if (movieMatch != null) {
            buttons.add(
                actionButton("▶ Play on IPTV", "play_iptv") {
                    com.mizan.launcher.data.DiagLog.note("details: movie play tapped")
                    // v2.4: store resolution on a worker thread.
                    WatchPlay.resolveVod(this, movieMatch) { vod ->
                        if (isFinishing || isDestroyed) return@resolveVod
                        if (vod == null) {
                            Toast.makeText(
                                this, "That movie isn't on your IPTV right now",
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            WatchPlay.playMovie(
                                this, vod,
                                if (inSnapshot) {
                                    WatchPlay.Token(tmdbId, "movie", 0, 0)
                                } else {
                                    null
                                }
                            )
                        }
                    }
                }
            )
        }
        // v3.7: the More overflow menu held only the
        // tracker-open entry — deleted with the tracker
        // app. Mute / Remove live as their own buttons
        // below (when tracked); nothing else lived in
        // that menu.
        if (inSnapshot) {
            val muted = effectiveMuted()
            buttons.add(
                actionButton(
                    if (muted) "🔔 Unmute notifications" else "🔇 Mute notifications",
                    "mute"
                ) {
                    TrackerActions.setMuted(this, prefs, tmdbId, type, !muted)
                    Toast.makeText(
                        this,
                        if (muted) "Notifications unmuted" else "Notifications muted",
                        Toast.LENGTH_SHORT
                    ).show()
                    rebuild()
                }
            )
            buttons.add(
                actionButton("Remove from library…", "remove") {
                    TrackerActions.confirmRemove(this, prefs, tmdbId, type, displayTitle()) {
                        finish()
                    }
                }
            )
        }
        if (buttons.isNotEmpty()) box.addView(buttonRow(buttons))

        // Seasons + episodes (TV only). v2.6: the season source is
        // the TMDB details when they landed, else the IPTV
        // fallback's shaped list; with neither, the TMDB error
        // stands (plus a "trying IPTV" line while that fetch runs).
        val seasonSource: List<LibraryLogic.SeasonInfo> = when {
            d != null && d.seasons.isNotEmpty() -> d.seasons
            !iptvSeasons.isNullOrEmpty() -> iptvSeasons!!
            else -> emptyList()
        }
        if (type == "tv" && seasonSource.isNotEmpty()) {
            buildSeasonBlock(box, seasonSource)
        } else if (type == "tv" && detailsError != null) {
            if (iptvFallbackLoading) {
                box.addView(noteLine("Trying your IPTV for the episode list…"))
            } else if (inSnapshot) {
                box.addView(
                    noteLine("Episode lists need TMDB — fix the problem above, then reopen this title.")
                )
            }
        }

        // Restore focus to the same-role control after a rebuild.
        if (focusTag != null) {
            findByTag(box, focusTag)?.post {
                findByTag(box, focusTag)?.requestFocus()
            }
        }
    }

    private fun snapshotOverview(): String? {
        val snap = TrackerClient.cached(this)
        snap.upNext.firstOrNull { it.tmdbId == tmdbId }?.overview
            ?.takeIf { it.isNotBlank() }?.let { return it }
        snap.cont.firstOrNull { it.tmdbId == tmdbId }?.overview
            ?.takeIf { it.isNotBlank() }?.let { return it }
        return null
    }

    private fun buildSeasonBlock(
        box: LinearLayout,
        seasons: List<LibraryLogic.SeasonInfo>
    ) {
        val header = TextView(this)
        header.text = "EPISODES"
        header.setTextColor(accent)
        header.textSize = Design.SECTION_HEADER_SP
        header.letterSpacing = Design.SECTION_HEADER_TRACKING
        header.setTypeface(header.typeface, Typeface.BOLD)
        val hlp = LinearLayout.LayoutParams(-1, -2)
        hlp.setMargins(0, Ui.dp(this, 22), 0, Ui.dp(this, 8))
        header.layoutParams = hlp
        box.addView(header)

        // Season chips.
        val chips = LinearLayout(this)
        chips.orientation = LinearLayout.HORIZONTAL
        for (sInfo in seasons) {
            val chip = TextView(this)
            chip.text = if (sInfo.number == 0) {
                "Specials"
            } else {
                "Season ${sInfo.number}"
            }
            chip.tag = "season:${sInfo.number}"
            chip.setTextColor(
                if (sInfo.number == selectedSeason) 0xFFFFFFFF.toInt() else 0xFFB3B3B3.toInt()
            )
            chip.textSize = 15f
            chip.setTypeface(chip.typeface, Typeface.BOLD)
            chip.gravity = Gravity.CENTER
            chip.isFocusable = true
            chip.isClickable = true
            chip.setPadding(Ui.dp(this, 14), Ui.dp(this, 7), Ui.dp(this, 14), Ui.dp(this, 7))
            val bg = android.graphics.drawable.GradientDrawable()
            bg.cornerRadius = Ui.dp(this, Design.RADIUS_CHIP).toFloat()
            bg.setColor(
                if (sInfo.number == selectedSeason) {
                    Design.NETFLIX_RED
                } else {
                    Design.NETFLIX_BUTTON2_FILL
                }
            )
            chip.background = bg
            val clp = LinearLayout.LayoutParams(-2, -2)
            clp.setMargins(0, 0, Ui.dp(this, 8), 0)
            chip.layoutParams = clp
            chip.setOnClickListener {
                if (selectedSeason != sInfo.number) {
                    selectedSeason = sInfo.number
                    seasonError = null
                    rebuild()
                    maybeLoadSeason()
                }
            }
            chips.addView(chip)
        }
        val chipScroll = HorizontalScrollView(this)
        chipScroll.isHorizontalScrollBarEnabled = false
        chipScroll.addView(chips)
        box.addView(chipScroll)

        val season = selectedSeason
        if (season < 0) return
        // v2.6: say plainly when this season's list is the IPTV
        // fallback's, not TMDB's.
        if (season in iptvEpisodeSeasons) {
            box.addView(noteLine("Episode list from IPTV — TMDB unreachable"))
        }
        val episodes = seasonEpisodes[season]
        when {
            seasonLoading == season ->
                box.addView(noteLine("Loading episodes…"))
            seasonError != null && episodes == null -> {
                box.addView(noteLine(LibraryLogic.tmdbUserLine(seasonError)))
                if (LibraryLogic.tmdbRetryable(seasonError)) {
                    box.addView(
                        buttonRow(
                            listOf(
                                actionButton("Retry episodes", "retry_season") {
                                    seasonError = null
                                    rebuild()
                                    maybeLoadSeason()
                                }
                            )
                        )
                    )
                }
            }
            episodes != null -> {
                if (wMatch != null) {
                    // v2.3 Watch: matched show — the ▶ on each row
                    // plays it; say so once, above the list.
                    box.addView(
                        noteLine("▶ This show is on your IPTV — hold OK on an episode to play it.")
                    )
                }
                if (inSnapshot) {
                    val unwatchedLeft = episodes.count {
                        LibraryLogic.epKey(season, it.number) !in effectiveWatched() &&
                            hasAired(it.airDate)
                    }
                    if (unwatchedLeft > 0) {
                        box.addView(
                            buttonRow(
                                listOf(
                                    actionButton(
                                        "✓ Mark season watched",
                                        "mark_season"
                                    ) {
                                        TrackerActions.markSeason(
                                            this, prefs, tmdbId, season, episodes
                                        )
                                        Toast.makeText(
                                            this, "Season marked",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        rebuild()
                                    }
                                )
                            )
                        )
                    }
                }
                for (ep in episodes) {
                    box.addView(episodeRow(season, ep))
                }
            }
        }
    }

    /**
     * Unaired gate: an episode counts as aired when its TMDB air
     * date is blank (unknown — fail open, never invent a date)
     * or on/before the device's local today. ISO yyyy-MM-dd
     * strings compare chronologically, so a plain string
     * comparison is exact for well-formed dates; anything
     * else fails open too.
     */
    private fun hasAired(airDate: String): Boolean {
        val d = airDate.trim()
        if (d.isBlank()) return true
        if (!d.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) return true
        return d <= java.time.LocalDate.now().toString()
    }

    /** One episode checklist row: ✓ box + E<n> + name + air date; OK toggles. */
    private fun episodeRow(season: Int, ep: LibraryLogic.EpisodeInfo): View {
        val watched = effectiveWatched()
        val isWatched = LibraryLogic.epKey(season, ep.number) in watched
        val unaired = !hasAired(ep.airDate)
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.tag = "ep:$season:${ep.number}"
        row.isFocusable = true
        row.isClickable = true
        row.setPadding(Ui.dp(this, 12), Ui.dp(this, 9), Ui.dp(this, 12), Ui.dp(this, 9))
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.setMargins(0, Ui.dp(this, 3), 0, Ui.dp(this, 3))
        row.layoutParams = lp
        row.background = Ui.tileBackground(accent, false, 10, this)
        Ui.focusLift(row, accent, 10)

        // v3.13: the checkbox is its own D-pad-focusable toggle —
        // it runs exactly the row's click path (same unaired gate,
        // same toggle + rebuild), so checkbox, name tap and
        // long-press correction can never disagree.
        val boxView = TextView(this)
        boxView.isFocusable = true
        boxView.isClickable = true
        boxView.setOnClickListener { row.performClick() }
        boxView.text = if (isWatched) "✓" else ""
        boxView.setTextColor(0xFFFFFFFF.toInt())
        boxView.textSize = 16f
        boxView.setTypeface(boxView.typeface, Typeface.BOLD)
        boxView.gravity = Gravity.CENTER
        val boxBg = android.graphics.drawable.GradientDrawable()
        boxBg.cornerRadius = Ui.dp(this, 4).toFloat()
        if (isWatched) {
            boxBg.setColor(Design.NETFLIX_RED)
        } else {
            boxBg.setColor(0x00000000)
            boxBg.setStroke(Ui.dp(this, 2), 0xFF8C8C8C.toInt())
        }
        boxView.background = boxBg
        val blp = LinearLayout.LayoutParams(Ui.dp(this, 26), Ui.dp(this, 26))
        blp.setMargins(0, 0, Ui.dp(this, 12), 0)
        boxView.layoutParams = blp
        row.addView(boxView)

        val num = TextView(this)
        num.text = "E${ep.number}"
        num.setTextColor(0xFFB3B3B3.toInt())
        num.textSize = 15f
        num.setTypeface(num.typeface, Typeface.BOLD)
        val nlp = LinearLayout.LayoutParams(Ui.dp(this, 52), -2)
        num.layoutParams = nlp
        row.addView(num)

        val name = TextView(this)
        name.text = ep.name.ifBlank { "Episode ${ep.number}" }
        name.setTextColor(if (isWatched) 0xFF8C8C8C.toInt() else 0xFFFFFFFF.toInt())
        name.textSize = 16f
        name.maxLines = 1
        name.ellipsize = android.text.TextUtils.TruncateAt.END
        row.addView(name, LinearLayout.LayoutParams(0, -2, 1f))

        if (ep.airDate.isNotBlank()) {
            val date = TextView(this)
            date.text = ep.airDate
            date.setTextColor(0xFF8C8C8C.toInt())
            date.textSize = 13f
            row.addView(date)
        }

        // v2.3 Watch: a matched show's rows carry the ▶ affordance.
        // v3.13: the ▶ is a real D-pad-focusable Play BUTTON
        // (same playEpisodeFromDetails path as the long-press
        // menu, which keeps working) — Play no longer lives
        // only in the long-press menu.
        val showMatch = if (type == "tv") wMatch else null
        if (showMatch != null) {
            // Unaired episodes carry no ▶: there is nothing to play yet.
            if (!unaired) {
                val play = TextView(this)
                play.text = "▶"
                play.setTextColor(Design.NETFLIX_RED)
                play.textSize = 17f
                play.setTypeface(play.typeface, Typeface.BOLD)
                play.isFocusable = true
                play.isClickable = true
                play.setOnClickListener {
                    playEpisodeFromDetails(showMatch, season, ep.number)
                }
                val plp = LinearLayout.LayoutParams(-2, -2)
                plp.setMargins(Ui.dp(this, 14), 0, 0, 0)
                play.layoutParams = plp
                row.addView(play)
            }
            row.isLongClickable = true
            row.setOnLongClickListener {
                if (unaired && !isWatched) {
                    // Nothing to play and nothing to have watched:
                    // say so instead of offering dead actions.
                    Toast.makeText(
                        this,
                        if (ep.airDate.isNotBlank()) "Hasn't aired yet — airs ${ep.airDate}"
                        else "Hasn't aired yet",
                        Toast.LENGTH_SHORT
                    ).show()
                    true
                } else if (unaired) {
                    // Already (mis)marked: only offer the correction.
                    AlertDialog.Builder(this)
                        .setTitle(ep.name.ifBlank { "Episode ${ep.number}" })
                        .setItems(arrayOf("↩ Mark unwatched")) { _, _ ->
                            row.performClick()
                        }
                        .show()
                    true
                } else {
                    val labels = arrayOf(
                        "▶ Play S$season E${ep.number}",
                        if (isWatched) "↩ Mark unwatched" else "✓ Mark watched"
                    )
                    AlertDialog.Builder(this)
                        .setTitle(ep.name.ifBlank { "Episode ${ep.number}" })
                        .setItems(labels) { _, which ->
                            if (which == 0) {
                                playEpisodeFromDetails(showMatch, season, ep.number)
                            } else {
                                row.performClick()
                            }
                        }
                        .show()
                    true
                }
            }
        }

        row.setOnClickListener {
            if (!inSnapshot) {
                Toast.makeText(
                    this,
                    "Add this title to your list to use episode tools",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            if (unaired && !isWatched) {
                Toast.makeText(
                    this,
                    if (ep.airDate.isNotBlank()) "Hasn't aired yet — airs ${ep.airDate}"
                    else "Hasn't aired yet",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            TrackerActions.toggleEpisode(
                this, prefs, tmdbId, season, ep.number, !isWatched
            )
            Toast.makeText(
                this,
                if (!isWatched) "Marked watched" else "Marked unwatched",
                Toast.LENGTH_SHORT
            ).show()
            rebuild()
        }
        return row
    }

    /**
     * v2.3: play one episode of this (matched) show from details.
     * Resolution failure fails open with a toast — the checklist
     * stays fully usable either way.
     */
    private fun playEpisodeFromDetails(match: WatchMatch.Match, season: Int, episode: Int) {
        com.mizan.launcher.data.DiagLog.note("details: episode play tapped")
        // v2.4: store resolution on a worker thread.
        WatchPlay.resolveSeries(this, match) { series ->
            if (isFinishing || isDestroyed) return@resolveSeries
            if (series == null) {
                Toast.makeText(
                    this, "That show isn't on your IPTV right now", Toast.LENGTH_SHORT
                ).show()
                return@resolveSeries
            }
            WatchPlay.playEpisode(
                this, series, tmdbId, season, episode,
                displayTitle(), displayPoster(), withToken = inSnapshot
            ) {
                Toast.makeText(
                    this, "That episode isn't on your IPTV", Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // ------------------------------------------------------------ small builders

    private fun noteLine(text: String): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.setTextColor(0xFFB3B3B3.toInt())
        tv.textSize = 15f
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.setMargins(0, Ui.dp(this, 10), 0, 0)
        tv.layoutParams = lp
        return tv
    }

    private fun actionButton(text: String, tag: String, onClick: () -> Unit): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.tag = tag
        tv.setTextColor(0xFFFFFFFF.toInt())
        tv.textSize = 15f
        tv.setTypeface(tv.typeface, Typeface.BOLD)
        tv.gravity = Gravity.CENTER
        tv.isFocusable = true
        tv.isClickable = true
        tv.setPadding(Ui.dp(this, 16), Ui.dp(this, 9), Ui.dp(this, 16), Ui.dp(this, 9))
        tv.background = Ui.tileBackground(accent, false, Design.RADIUS_CHIP, this)
        Ui.focusLift(tv, accent, Design.RADIUS_CHIP)
        tv.setOnClickListener { onClick() }
        return tv
    }

    private fun buttonRow(buttons: List<View>): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        for ((i, b) in buttons.withIndex()) {
            val blp = LinearLayout.LayoutParams(-2, -2)
            if (i > 0) blp.setMargins(Ui.dp(this, 10), 0, 0, 0)
            b.layoutParams = blp
            row.addView(b)
        }
        val scroller = HorizontalScrollView(this)
        scroller.isHorizontalScrollBarEnabled = false
        val slp = LinearLayout.LayoutParams(-1, -2)
        slp.setMargins(0, Ui.dp(this, 14), 0, 0)
        scroller.layoutParams = slp
        scroller.addView(row)
        return scroller
    }

    private fun findByTag(root: View, tag: String): View? {
        if (root.tag == tag) return root
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) {
                findByTag(root.getChildAt(i), tag)?.let { return it }
            }
        }
        return null
    }
}
