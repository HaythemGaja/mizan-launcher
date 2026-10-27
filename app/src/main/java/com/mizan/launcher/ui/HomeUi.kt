package com.mizan.launcher.ui

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.mizan.launcher.core.HeroRotator
import com.mizan.launcher.domain.HomeCard
import com.mizan.launcher.domain.SectionIds
import com.mizan.launcher.domain.SectionState
import com.mizan.launcher.domain.SectionRender

/**
 * Home-screen components for the app layer (MainActivity) to host.
 * No MainActivity here by design: the integration worker embeds
 * [HomeUi.build] in its own activity. Hero = shell-frame presentation:
 * big featured title over full-bleed backdrop with left scrim; hero pool
 * order Missed (most overdue first) → Up Next → Continue (backdrop-first)
 * → For You; backdrop presence never reorders tiers. Hero rotation:
 * advances on a timer, pauses while focus is held, resumes after a delay,
 * Next advances immediately and resets the timer, <2 candidates never rotate.
 * Portrait 2:3 Up Next / Missed cards. IPTV lives under Watch's Live tab
 * (home-style picker offers TV / Watch only).
 */
object HomeUi {
    /** Contract the app layer implements to host the home UI. */
    interface Host {
        fun onCardPlay(card: HomeCard)
        fun onCardDetails(card: HomeCard)
        fun onCardMenu(card: HomeCard)
        fun onOpenLibrary()
        fun onOpenApps()
        fun onOpenSearch()
        fun onOpenSettings()
        fun onOpenIptv()
        fun onOpenForecast()
    }

    fun heroView(ctx: Context, card: HomeCard?, onPlay: () -> Unit, onNext: (() -> Unit)? = null): View {
        val col = LinearLayout(ctx); col.orientation = LinearLayout.VERTICAL
        col.setPadding(Ui.dp(ctx, 48), Ui.dp(ctx, 32), Ui.dp(ctx, 48), Ui.dp(ctx, 32))
        col.background = Ui.bg(0x88000000.toInt(), 20, ctx) // left-scrim approximation over backdrop
        if (card == null) {
            col.addView(Ui.text(ctx, "Sreeb", 36f, Color.WHITE, true))
            col.addView(Ui.text(ctx, "Your library will appear here", 18f, 0xFFAAAAAA.toInt()))
        } else {
            col.addView(Ui.text(ctx, card.title, 36f, Color.WHITE, true))
            val meta = listOf(card.countdownLabel, card.progress?.let { "${it.watchedCount}/${it.totalCount}" } ?: "").filter { it.isNotEmpty() }.joinToString(" · ")
            if (meta.isNotEmpty()) col.addView(Ui.text(ctx, meta, 18f, 0xFFDDDDDD.toInt()))
            col.addView(Ui.button(ctx, "▶ Play") { onPlay() })
            if (onNext != null) col.addView(Ui.button(ctx, "Next") { onNext() })
        }
        return col
    }

    /** A hero rotator driven by core/HeroRotator; caller supplies candidates + a render callback. */
    class HeroCarousel(private val onRender: (HomeCard?) -> Unit) {
        private var state = HeroRotator.initial(0, 0, System.currentTimeMillis())
        private var cards: List<HomeCard> = emptyList()
        private val handler = Handler(Looper.getMainLooper())
        private val tick = object : Runnable {
            override fun run() {
                val next = HeroRotator.tick(state, System.currentTimeMillis())
                if (next != null) { state = next; onRender(cards.getOrNull(state.index)) }
                handler.postDelayed(this, 8000)
            }
        }
        fun setCards(c: List<HomeCard>) {
            cards = c; state = HeroRotator.withCount(state, c.size, System.currentTimeMillis())
            onRender(c.getOrNull(state.index))
        }
        fun start() { handler.postDelayed(tick, 8000) }
        fun stop() { handler.removeCallbacks(tick) }
        fun focusHeld() { state = HeroRotator.focusGained(state) }
        fun focusReleased() { state = HeroRotator.focusReleased(state, System.currentTimeMillis()) }
        fun next() { state = HeroRotator.next(state, System.currentTimeMillis()); onRender(cards.getOrNull(state.index)) }
    }

    fun cardRow(ctx: Context, title: String, cards: List<HomeCard>, host: Host, state: SectionState = SectionState.READY, onRetry: (() -> Unit)? = null): View {
        val col = LinearLayout(ctx); col.orientation = LinearLayout.VERTICAL
        col.addView(Ui.sectionTitle(ctx, title))
        when (state) {
            SectionState.LOADING -> col.addView(Ui.stateText(ctx, "Loading…"))
            SectionState.EMPTY -> col.addView(Ui.stateText(ctx, "Nothing here yet"))
            SectionState.ERROR -> {
                col.addView(Ui.stateText(ctx, "This section hit a snag"))
                if (onRetry != null) col.addView(Ui.button(ctx, "Retry") { onRetry() })
            }
            SectionState.READY -> {
                val views = cards.take(20).map { card ->
                    PosterCards.homeCard(ctx, card,
                        onClick = { host.onCardPlay(card) },
                        onLong = { host.onCardMenu(card) })
                }
                col.addView(PosterCards.rowView(ctx, "", views).let {
                    // rowView adds its own title; strip by rebuilding without title
                    val inner = LinearLayout(ctx); inner.orientation = LinearLayout.HORIZONTAL
                    val hs = android.widget.HorizontalScrollView(ctx)
                    views.forEach { v -> inner.addView(v) }; hs.addView(inner); hs
                })
            }
        }
        return col
    }

    /** Unified pill tab bar (v2.9): tab pills left, status group right. Watch tabs: Home · Library · Apps · Live (Live LAST). */
    fun tabBar(ctx: Context, style: String, host: Host): View {
        val bar = LinearLayout(ctx); bar.orientation = LinearLayout.HORIZONTAL
        val tabs = if (style == "watch") listOf("Home" to {}, "Library" to { host.onOpenLibrary() }, "Apps" to { host.onOpenApps() }, "Live" to { host.onOpenIptv() })
            else listOf("Home" to {}, "Apps" to { host.onOpenApps() }, "Search" to { host.onOpenSearch() }, "Settings" to { host.onOpenSettings() })
        tabs.forEach { (label, action) -> bar.addView(Ui.button(ctx, label, action)) }
        // Status group: clock · date · weather pill (OK opens Forecast) + Guest pill handled by host via settings.
        bar.addView(Ui.button(ctx, "Forecast") { host.onOpenForecast() })
        return bar
    }

    /** One-time "add your IPTV account" hint on Watch, only while no IPTV source is configured. */
    fun watchHint(ctx: Context, onDismiss: () -> Unit, onOpenIptv: () -> Unit): View {
        val col = LinearLayout(ctx); col.orientation = LinearLayout.VERTICAL
        col.addView(Ui.text(ctx, "Add your IPTV account to play your titles", 18f, Color.WHITE, true))
        col.addView(Ui.button(ctx, "Add IPTV account") { onOpenIptv() })
        col.addView(Ui.button(ctx, "Dismiss") { onDismiss() })
        return col
    }

    /** Safe shell composition: plain frame, Favorites + Apps rows only, notice tile opening Settings. */
    fun safeShell(ctx: Context, host: Host): View {
        val col = LinearLayout(ctx); col.orientation = LinearLayout.VERTICAL
        col.addView(Ui.text(ctx, "Safe mode — plain launcher", 22f, Color.WHITE, true))
        col.addView(Ui.button(ctx, "Open Settings") { host.onOpenSettings() })
        col.addView(Ui.text(ctx, "Favorites + Apps only while safe mode is armed", 16f, 0xFFAAAAAA.toInt()))
        return col
    }

    /** Full Watch home composition from section renders, in canonical order, hero always first. */
    fun buildWatchHome(ctx: Context, sections: List<SectionRender>, heroCard: HomeCard?, host: Host): View {
        val col = LinearLayout(ctx); col.orientation = LinearLayout.VERTICAL
        col.addView(tabBar(ctx, "watch", host))
        val ordered = sections.sortedBy { SectionIds.WATCH_SECTIONS.indexOf(it.sectionId).let { i -> if (i < 0) 99 else i } }
        for (s in ordered) {
            if (s.sectionId == SectionIds.HERO) { col.addView(heroView(ctx, heroCard, { heroCard?.let { host.onCardPlay(it) } })); continue }
            val title = when (s.sectionId) {
                SectionIds.UP_NEXT -> "Up Next"; SectionIds.MISSED -> "Missed"
                SectionIds.CONTINUE -> "Continue Watching"; SectionIds.TRENDING_TV -> "Trending TV"
                SectionIds.TRENDING_MOVIES -> "Trending Movies"; SectionIds.FOR_YOU -> "For You"
                SectionIds.FAVORITES -> "Favorites"; SectionIds.FOLDERS -> "Folders"; SectionIds.APPS -> "Apps"
                else -> s.sectionId
            }
            col.addView(Ui.guarded(ctx) { cardRow(ctx, title, s.cards, host, s.state) })
        }
        return col
    }

    /** TV home rows: favorites, recents, mostused, folders, nowplaying, continue, inputs, livetv, apps. */
    fun buildTvHome(ctx: Context, rows: List<Pair<String, List<View>>>, host: Host): View {
        val col = LinearLayout(ctx); col.orientation = LinearLayout.VERTICAL
        col.addView(tabBar(ctx, "tv", host))
        rows.forEach { (title, views) ->
            if (views.isNotEmpty()) {
                col.addView(Ui.sectionTitle(ctx, title))
                val inner = LinearLayout(ctx); inner.orientation = LinearLayout.HORIZONTAL
                val hs = android.widget.HorizontalScrollView(ctx)
                views.take(20).forEach { inner.addView(it) }; hs.addView(inner); col.addView(hs)
            }
        }
        return col
    }

    /** Card hold-OK menu: Play, Play in…, Mark watched/unwatched, (movies: toggle), For You: Add / Dismiss. */
    fun cardMenu(ctx: Context, card: HomeCard, host: Host) {
        val tracker = UiWiring.tracker; val play = UiWiring.playTitle
        val items = mutableListOf<Pair<String, () -> Unit>>()
        items += "▶ Play" to { host.onCardPlay(card) }
        items += "Play in…" to { host.onCardPlay(card) } // app layer resolves external targets
        val ns = card.nextSeason; val ne = card.nextEpisode
        if (card.kind.name == "SHOW" && ns != null && ne != null) {
            items += "✓ Mark next episode watched" to {
                Ui.runBg {
                    tracker?.markEpisodeWatched(
                        com.mizan.launcher.domain.EpisodeRef(card.tmdbId, ns, ne, null), true)
                }
            }
        }
        if (card.kind.name == "MOVIE") {
            items += "✓ Mark watched" to { Ui.runBg { tracker?.markMovieWatched(card.tmdbId, true) } }
            items += "↩ Mark unwatched" to { Ui.runBg { tracker?.markMovieWatched(card.tmdbId, false) } }
        }
        items += "Details" to { host.onCardDetails(card) }
        if (card.isMissed) items += "Dismiss" to { Ui.runBg { tracker?.dismissForYou(card.tmdbId, card.kind) } }
        Ui.menu(ctx, card.title, items)
        @Suppress("UNUSED_VARIABLE") val unused = play
    }
}
