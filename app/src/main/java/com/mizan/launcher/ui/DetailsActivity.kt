package com.mizan.launcher.ui

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout
import com.mizan.launcher.domain.AiredGate
import com.mizan.launcher.domain.EpisodeRef
import com.mizan.launcher.domain.MediaKind

/**
 * Details (MAP §1.5 + §8.1 f26a5f6 unaired gate, EXACT).
 * Header: title, poster/backdrop, year, overview (snapshot overview when
 * TMDB fails), meta incl. "✓ Watched" / "Not watched" for movies.
 * TMDB details via tracker proxy first, launcher-key direct second
 * (that ordering lives in the data layer's TmdbRepository).
 *
 * UNAIRED GATE (AiredGate only — never re-implemented here):
 * - blank air date = aired (fail open); malformed = aired; today = aired.
 * - Unaired episode: NO ▶ glyph.
 * - Click / long-press on unaired+unwatched: NO toggle, NO menu, ONLY
 *   toast "Hasn't aired yet — airs <date>" (AiredGate.unairedMessage).
 * - Long-press on unaired+already-marked: ONLY "↩ Mark unwatched".
 * - Aired episode long-press: ▶ Play S<s> E<e> + ✓/↩ Mark watched.
 * - "✓ Mark season watched": counts only unwatched episodes that have
 *   aired (AiredGate.remainingAiredCount); hidden when 0; marking goes
 *   through the aired filter (repository contract).
 */
class DetailsActivity : Activity() {
    companion object {
        const val EXTRA_ID = "tmdb_id"
        const val EXTRA_TYPE = "tmdb_type"
        const val EXTRA_TITLE = "title"
        const val EXTRA_POSTER = "poster"
        const val EXTRA_SEASON = "season"
    }
    private var tmdbId: Long = 0
    private var kind: MediaKind = MediaKind.SHOW
    private var season: Int = 1
    private var episodes: List<EpisodeRef> = emptyList()
    private var watched: Set<String> = emptySet()
    private var movieWatched: Boolean = false
    // Phase 2D: IPTV-matched flag for this show (drives the ▶ glyph:
    // aired AND matched) and the IPTV series-info fallback marker.
    private var showMatched: Boolean = false
    private var iptvFallback: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tmdbId = intent.getLongExtra(EXTRA_ID, 0L)
        kind = if (intent.getStringExtra(EXTRA_TYPE) == "movie") MediaKind.MOVIE else MediaKind.SHOW
        val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL
        setContentView(Ui.rootScrollView(this, col))
        Ui.runBg {
            val tracker = UiWiring.tracker; val tmdb = UiWiring.tmdb
            val details = try { tmdb?.getDetails(tmdbId, kind)?.getOrNull() } catch (e: Throwable) { null }
            val title = details?.title ?: intent.getStringExtra(EXTRA_TITLE) ?: ""
            val seasons = if (kind == MediaKind.SHOW) {
                try { tracker?.getSeasons(tmdbId) ?: details?.seasons ?: emptyList() } catch (e: Throwable) { details?.seasons ?: emptyList() }
            } else emptyList()
            // Default season: first season with an unwatched episode (MAP §11 open item — implemented as stated).
            watched = try { tracker?.getWatchedEpisodes(tmdbId) ?: emptySet() } catch (e: Throwable) { emptySet() }
            movieWatched = try { tracker?.getTrackedMovies()?.firstOrNull { it.tmdbId == tmdbId }?.watched == true } catch (e: Throwable) { false }
            // Phase 2D matched flag (worker thread; match set may recompute once).
            showMatched = try { UiWiring.playTitle?.isMatched(tmdbId) == true } catch (e: Throwable) { false }
            Ui.runMain {
                col.addView(Ui.guarded(this) {
                    val c = LinearLayout(this); c.orientation = LinearLayout.VERTICAL
                    c.addView(Ui.text(this, title, 32f, 0xFFFFFFFF.toInt(), true))
                    if (details?.year != null) c.addView(Ui.text(this, details.year.toString(), 18f, 0xFFAAAAAA.toInt()))
                    if (details?.overview?.isNotEmpty() == true) c.addView(Ui.text(this, details.overview, 18f))
                    if (kind == MediaKind.MOVIE) c.addView(Ui.text(this, if (movieWatched) "✓ Watched" else "Not watched", 18f))
                    c
                })
                if (kind == MediaKind.MOVIE) movieActions(col, title) else showActions(col, seasons, title)
                if (kind == MediaKind.SHOW && seasons.isNotEmpty()) {
                    // Pick default season, then render its episodes.
                    Ui.runBg {
                        var picked = seasons.first()
                        val requested = intent.getIntExtra(EXTRA_SEASON, -1)
                        if (requested in seasons) picked = requested
                        else for (s in seasons) {
                            val eps = try { tracker?.getSeasonEpisodes(tmdbId, s) ?: tmdb?.getSeasonEpisodes(tmdbId, s)?.getOrNull() ?: emptyList() } catch (e: Throwable) { emptyList() }
                            if (eps.any { "S${it.season}x${it.episode}" !in watched && "${it.season}x${it.episode}" !in watched }) { picked = s; break }
                        }
                        season = picked
                        episodes = try { tracker?.getSeasonEpisodes(tmdbId, picked) ?: tmdb?.getSeasonEpisodes(tmdbId, picked)?.getOrNull() ?: emptyList() } catch (e: Throwable) { emptyList() }
                        // Phase 2D IPTV series-info fallback: when TMDB
                        // season data is missing, fill from the matched
                        // IPTV series (seasons ascend, episodes ascend +
                        // dedupe by number in the data layer's shaping;
                        // missing titles fall back to "Episode N" there).
                        if (episodes.isEmpty()) {
                            try {
                                val iptv = UiWiring.iptv
                                val seriesId = iptv?.iptvSeriesForShow(tmdbId)
                                if (seriesId != null) {
                                    val ieps = iptv.getSeriesEpisodes(seriesId)
                                        .filter { it.season == picked }
                                        .distinctBy { it.episode }
                                        .sortedBy { it.episode }
                                    if (ieps.isNotEmpty()) {
                                        episodes = ieps.map { EpisodeRef(tmdbId, it.season, it.episode, null, it.title.ifBlank { "Episode ${it.episode}" }) }
                                        iptvFallback = true
                                    }
                                }
                            } catch (e: Throwable) { }
                        }
                        Ui.runMain { renderSeason(col, title) }
                    }
                }
            }
        }
    }

    private fun epKey(e: EpisodeRef) = "${e.season}x${e.episode}"
    private fun isWatched(e: EpisodeRef): Boolean = epKey(e) in watched ||
        com.mizan.launcher.core.LibraryLogic.epKey(e.season, e.episode) in watched

    private fun movieActions(col: LinearLayout, title: String) {
        val tracker = UiWiring.tracker; val play = UiWiring.playTitle
        col.addView(Ui.button(this, "▶ Play on IPTV") {
            val card = com.mizan.launcher.domain.HomeCard(tmdbId, MediaKind.MOVIE, title)
            val item = play?.playCard(card)
            if (item != null) Ui.openPlayer(this, item) else Ui.toast(this, "Not matched on IPTV")
        })
        col.addView(Ui.button(this, if (movieWatched) "↩ Mark unwatched" else "✓ Mark watched") {
            Ui.runBg { tracker?.markMovieWatched(tmdbId, !movieWatched); Ui.runMain { recreate() } }
        })
        col.addView(Ui.button(this, "＋ Add to my list") {
            Ui.runBg { tracker?.addToPlanToWatch(com.mizan.launcher.domain.PlanToWatchItem(tmdbId, MediaKind.MOVIE, title)) }
        })
        val muted = tracker?.isMuted(tmdbId, kind) == true
        col.addView(Ui.button(this, if (muted) "🔔 Unmute notifications" else "🔇 Mute notifications") {
            Ui.runBg { tracker?.setMuted(tmdbId, kind, !muted) }
        })
        col.addView(Ui.button(this, "Remove from library…") {
            Ui.confirm(this, "Remove from library", "Remove \"$title\"?") {
                Ui.runBg { tracker?.removeFromLibrary(tmdbId, kind); Ui.runMain { finish() } }
            }
        })
    }

    private fun showActions(col: LinearLayout, seasons: List<Int>, title: String) {
        col.addView(Ui.text(this, "Seasons: " + seasons.joinToString(", "), 18f))
        seasons.forEach { s ->
            col.addView(Ui.button(this, "Season $s") {
                val i = android.content.Intent(this, DetailsActivity::class.java)
                i.putExtra(EXTRA_ID, tmdbId); i.putExtra(EXTRA_TYPE, if (kind == MediaKind.MOVIE) "movie" else "tv")
                i.putExtra(EXTRA_TITLE, title); i.putExtra(EXTRA_SEASON, s)
                startActivity(i); finish()
            })
        }
        col.addView(Ui.button(this, "Retry episodes") { recreate() })
    }

    private fun renderSeason(col: LinearLayout, title: String) {
        // Remove previously rendered season block (everything after the actions is rebuilt on recreate;
        // here we simply append the current season block and rely on recreate for season switches).
        if (iptvFallback) {
            col.addView(Ui.text(this, "▶ This show is on your IPTV — hold OK on an episode to play it.", 16f, 0xFFDDDDDD.toInt()))
        }
        val remaining = AiredGate.remainingAiredCount(episodes) { isWatched(it) }
        if (remaining > 0) {
            col.addView(Ui.button(this, "✓ Mark season watched ($remaining remaining)") {
                Ui.runBg {
                    UiWiring.tracker?.markSeasonWatched(tmdbId, season, episodes)
                    UiWiring.markEpisode?.markSeason(tmdbId, season, episodes)
                    Ui.runMain { recreate() }
                }
            })
        }
        if (episodes.isEmpty()) {
            col.addView(Ui.stateText(this, "No episodes for this season."))
            return
        }
        episodes.sortedWith(compareBy({ it.season }, { it.episode })).forEach { ep ->
            val aired = AiredGate.hasAired(ep.airDate)
            val watchedNow = isWatched(ep)
            // ▶ glyph ONLY when aired AND IPTV-matched (Phase 2D);
            // unaired episodes carry none. The play path re-checks too.
            val glyph = if (aired && showMatched) "▶ " else ""
            val check = if (watchedNow) "✓ " else "☐ "
            val label = "$check$glyph E${ep.episode} ${ep.title} ${ep.airDate ?: ""}".trim()
            val row = Ui.button(this, label) {
                // Click: unaired+unwatched -> toast only, no toggle. Aired -> toggle.
                if (!aired && !watchedNow) { Ui.toast(this, AiredGate.unairedMessage(ep.airDate)); return@button }
                if (!aired && watchedNow) { Ui.toast(this, AiredGate.unairedMessage(ep.airDate)); return@button }
                Ui.runBg { UiWiring.tracker?.toggleEpisode(ep); Ui.runMain { recreate() } }
            }
            row.setOnLongClickListener {
                when {
                    !aired && !watchedNow -> { Ui.toast(this, AiredGate.unairedMessage(ep.airDate)); true }
                    !aired && watchedNow -> {
                        Ui.menu(this, "E${ep.episode}", listOf("↩ Mark unwatched" to {
                            Ui.runBg { UiWiring.tracker?.markEpisodeWatched(ep, false); Ui.runMain { recreate() } }
                        })); true
                    }
                    else -> {
                        val items = mutableListOf<Pair<String, () -> Unit>>()
                        items += "▶ Play S${ep.season} E${ep.episode}" to {
                            val item = UiWiring.playTitle?.playEpisode(tmdbId, ep.season, ep.episode)
                            if (item != null) Ui.openPlayer(this, item) else Ui.toast(this, "Not matched on IPTV")
                        }
                        items += (if (watchedNow) "↩ Mark unwatched" else "✓ Mark watched") to {
                            Ui.runBg { UiWiring.tracker?.markEpisodeWatched(ep, !watchedNow); Ui.runMain { recreate() } }
                        }
                        Ui.menu(this, "E${ep.episode} ${ep.title}", items); true
                    }
                }
            }
            col.addView(row)
        }
    }
}
