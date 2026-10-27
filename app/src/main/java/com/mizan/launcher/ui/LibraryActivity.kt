package com.mizan.launcher.ui

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout
import com.mizan.launcher.domain.HomeCard
import com.mizan.launcher.domain.MediaKind

/**
 * Library / My Library (MAP §1.4): single merged grid of tracked shows +
 * movies; sort = in-progress first (continue order), then Up Next, then
 * A–Z, finished last. Cards carry poster, progress line + fraction,
 * caught-up detection. Hold-OK menu: Details; Mark next episode watched
 * (shows) / Mark watched / unwatched (movies); Mute / Unmute; Remove
 * (confirmation). Optimistic overlay is owned by the data layer.
 */
class LibraryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL
        col.addView(Ui.sectionTitle(this, "My Library"))
        col.addView(Ui.button(this, "↻ Refresh") { recreate() })
        col.addView(Ui.button(this, "+ Add from TMDB") { startActivity(android.content.Intent(this, TmdbSearchActivity::class.java)) })
        setContentView(Ui.rootScrollView(this, col))
        Ui.runBg {
            val tracker = UiWiring.tracker
            val continueCards = try { tracker?.getContinueWatching() ?: emptyList() } catch (e: Throwable) { emptyList() }
            val upNext = try { tracker?.getUpNext() ?: emptyList() } catch (e: Throwable) { emptyList() }
            val shows = try { tracker?.getTrackedShows() ?: emptyList() } catch (e: Throwable) { emptyList() }
            val movies = try { tracker?.getTrackedMovies() ?: emptyList() } catch (e: Throwable) { emptyList() }
            val continueIds = continueCards.map { it.tmdbId }.toSet()
            val upNextIds = upNext.map { it.tmdbId }.toSet()
            val merged = mutableListOf<HomeCard>()
            merged += continueCards
            merged += upNext.filter { it.tmdbId !in continueIds }
            val restShows = shows.filter { it.tmdbId !in continueIds && it.tmdbId !in upNextIds }
                .sortedBy { it.title.lowercase() }
                .map { HomeCard(it.tmdbId, MediaKind.SHOW, it.title, it.posterPath, it.backdropPath) }
            val restMovies = movies.filter { it.tmdbId !in continueIds && it.tmdbId !in upNextIds }
            merged += restShows
            // Finished last: watched movies after the rest, still A–Z within the group.
            merged += restMovies.filter { !it.watched }.sortedBy { it.title.lowercase() }
                .map { HomeCard(it.tmdbId, MediaKind.MOVIE, it.title, it.posterPath, it.backdropPath) }
            merged += restMovies.filter { it.watched }.sortedBy { it.title.lowercase() }
                .map { HomeCard(it.tmdbId, MediaKind.MOVIE, it.title, it.posterPath, it.backdropPath) }
            Ui.runMain {
                if (merged.isEmpty()) { col.addView(Ui.stateText(this, "Library is empty — add titles from TMDB search")); return@runMain }
                merged.forEach { card ->
                    val prog = card.progress
                    val sub = when {
                        prog != null && prog.caughtUp -> "Caught up"
                        prog != null -> "${prog.watchedCount}/${prog.totalCount}"
                        else -> ""
                    }
                    col.addView(PosterCards.homeCard(this, card,
                        onClick = { Ui.openDetails(this, card.tmdbId, card.kind.name.lowercase(), card.title) },
                        onLong = { libraryMenu(card) }))
                    if (sub.isNotEmpty()) col.addView(Ui.text(this, sub, 14f, 0xFFAAAAAA.toInt()))
                }
            }
        }
    }
    private fun libraryMenu(card: HomeCard) {
        val tracker = UiWiring.tracker
        val items = mutableListOf<Pair<String, () -> Unit>>()
        items += "Details" to { Ui.openDetails(this, card.tmdbId, card.kind.name.lowercase(), card.title) }
        val ns = card.nextSeason; val ne = card.nextEpisode
        if (card.kind == MediaKind.SHOW && ns != null && ne != null) {
            items += "✓ Mark next episode watched" to {
                Ui.runBg { tracker?.markEpisodeWatched(com.mizan.launcher.domain.EpisodeRef(card.tmdbId, ns, ne, null), true); Ui.runMain { recreate() } }
            }
        }
        if (card.kind == MediaKind.MOVIE) {
            items += "✓ Mark watched" to { Ui.runBg { tracker?.markMovieWatched(card.tmdbId, true); Ui.runMain { recreate() } } }
            items += "↩ Mark unwatched" to { Ui.runBg { tracker?.markMovieWatched(card.tmdbId, false); Ui.runMain { recreate() } } }
        }
        val muted = tracker?.isMuted(card.tmdbId, card.kind) == true
        items += (if (muted) "🔔 Unmute notifications" else "🔇 Mute notifications") to {
            Ui.runBg { tracker?.setMuted(card.tmdbId, card.kind, !muted) }
        }
        items += "Remove from library…" to {
            Ui.confirm(this, "Remove from library", "Remove \"${card.title}\" from your library?") {
                Ui.runBg { tracker?.removeFromLibrary(card.tmdbId, card.kind); Ui.runMain { recreate() } }
            }
        }
        Ui.menu(this, card.title, items)
    }
}
