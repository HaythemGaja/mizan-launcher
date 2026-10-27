package com.mizan.launcher.app

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.media.tv.TvInputManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.mizan.launcher.core.Themes
import com.mizan.launcher.data.ColdChainRunner
import com.mizan.launcher.data.LiveTvData
import com.mizan.launcher.data.PrefsStore
import com.mizan.launcher.data.StartupHealthStore
import com.mizan.launcher.data.TrackerRefreshWorker
import com.mizan.launcher.data.BingFetcher
import com.mizan.launcher.data.RedditFetcher
import com.mizan.launcher.data.TvData
import com.mizan.launcher.domain.HomeCard
import com.mizan.launcher.domain.InstalledApp
import com.mizan.launcher.domain.MediaKind
import com.mizan.launcher.domain.SectionIds
import com.mizan.launcher.domain.SectionRender
import com.mizan.launcher.domain.SectionState
import com.mizan.launcher.ui.AllAppsActivity
import com.mizan.launcher.ui.DetailsActivity
import com.mizan.launcher.ui.FolderViewActivity
import com.mizan.launcher.ui.ForecastActivity
import com.mizan.launcher.ui.HomeUi
import com.mizan.launcher.ui.IptvActivity
import com.mizan.launcher.ui.LibraryActivity
import com.mizan.launcher.ui.PatternGate
import com.mizan.launcher.ui.PosterCards
import com.mizan.launcher.ui.QuickSettingsView
import com.mizan.launcher.ui.SearchActivity
import com.mizan.launcher.ui.SettingsActivity
import com.mizan.launcher.ui.Ui
import com.mizan.launcher.ui.UiWiring
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Home Activity (Phase 2D integration) — the launcher itself
 * (HOME / LEANBACK_LAUNCHER, singleTask, clearTaskOnLaunch,
 * excludeFromRecents, stateNotNeeded, per the manifest).
 *
 * Composition, staged exactly as MAP §1.1 describes:
 * 1. First frame = staged shell (frame + status bar + tab bar +
 *    "Loading…" section frames from prefs-light state only, no
 *    heavy parse). A frame probe records stage 1; the warmed
 *    rebuild records stage 2.
 * 2. Warmed home = full composition from the repositories via
 *    UiWiring, hosted through the ui layer's HomeUi components
 *    (tab bar, hero + carousel, card rows, safe shell, watch
 *    hint) — this activity owns no card rendering of its own
 *    beyond app/folder/system rows HomeUi does not cover.
 * 3. Safe shell (MAP §2 startup-health machine): after two
 *    consecutive uncleared starts the third launch renders
 *    HomeUi.safeShell (Favorites + Apps only, no wallpaper work,
 *    notice tile opening Settings). Safe mode (the setting) is
 *    the same composition.
 * 4. Emergency root: if even the shell composition throws, a
 *    bare root with an "open Settings" action renders instead
 *    of a black screen — and the startup clear is HELD, so a
 *    deterministic composition crash still arms the safe shell.
 * 5. Cold chain: after first frame + 10 s survival, the
 *    ColdChainRunner steps run sequentially on a worker thread
 *    (tracker full → match → discovery); a navigation-cancelled
 *    chain (onPause/onDestroy) restarts the clock on return.
 * 6. No auto-refresh on open: home data refreshes only via
 *    "Refresh home data" or a per-section Retry (both force).
 *
 * v3.9 shell-frame rule: a recreated activity with a warm memo
 * prepares tracker data on its first build (the repositories
 * read the on-disk store synchronously on the worker thread
 * below, so a warm store paints hero/Missed/Continue on build
 * one); only a genuinely memo-less build defers to the warm
 * workers, and that state is recorded in the diagnostics
 * breadcrumb (counts only, never titles).
 *
 * Honest limits in this host (see CHECKLIST.md): hold-and-drop
 * move mode has no domain contract (AppsRepository moves an
 * entry to a folder, not within-order repositioning), so no
 * Move entry is offered; the tuner Live TV row lists channels
 * but tuner playback via TvView is not wired; HDMI input tiles
 * list inputs (switching needs the TV's own source UI).
 */
class MainActivity : Activity(), HomeUi.Host {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: PrefsStore
    private lateinit var healthStore: StartupHealthStore
    private var safeShell = false
    private var fallbackUsed = false
    private var fullBuildOk = false
    private var startupCleared = false
    private var coldDone = false
    private var resumed = false
    private var destroyedFlag = false
    private var renderGeneration = 0
    private var carousel: HomeUi.HeroCarousel? = null
    private var heroContainer: LinearLayout? = null
    private var clockView: TextView? = null
    private var coldRunner: ColdChainRunner? = null

    private val clockTick = object : Runnable {
        override fun run() {
            try {
                val profile = UiWiring.settings?.getProfile(
                    UiWiring.settings?.getGlobal()?.currentProfile ?: 1)
                val pattern = if (profile?.statusBar?.clock24h == true) "HH:mm" else "h:mm"
                clockView?.text = SimpleDateFormat(pattern, Locale.getDefault()).format(Date())
            } catch (ignored: Throwable) { }
            val secs = try {
                UiWiring.settings?.getProfile(UiWiring.settings?.getGlobal()?.currentProfile ?: 1)
                    ?.statusBar?.showSeconds == true
            } catch (e: Throwable) { false }
            handler.postDelayed(this, if (secs) 1000L else 20000L)
        }
    }

    private val coldRunnable = Runnable {
        if (destroyedFlag || !resumed || coldDone) return@Runnable
        val runner = ColdChainRunner(this)
        coldRunner = runner
        Thread {
            try { runner.runAll(true) } catch (ignored: Throwable) { }
            if (!destroyedFlag) {
                coldDone = true
                Ui.runMain { rebuild() }
            }
        }.start()
    }

    private val startupClearRunnable = Runnable {
        if (!fallbackUsed && fullBuildOk && !startupCleared) {
            try { healthStore.clear() } catch (ignored: Throwable) { }
            startupCleared = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        App.wire(this)
        prefs = PrefsStore(this)
        healthStore = StartupHealthStore(this)
        try { prefs.setOwnForegroundAt(System.currentTimeMillis()) } catch (ignored: Throwable) { }
        safeShell = App.safeShellArmed || healthStore.isSafeShell() || prefs.safeMode()

        // Stage 1: staged shell, prefs-light only, no heavy parse.
        val shell = LinearLayout(this); shell.orientation = LinearLayout.VERTICAL
        shell.addView(Ui.text(this, "Sreeb", 28f, 0xFFFFFFFF.toInt(), true))
        try {
            shell.addView(HomeUi.tabBar(this,
                if (prefs.homeStyleRaw() == "watch") "watch" else "tv", this))
        } catch (ignored: Throwable) { }
        shell.addView(Ui.stateText(this, "Loading…"))
        setContentView(Ui.rootScrollView(this, shell))
        // Frame probe, stage 1 (first frame).
        shell.post {
            try {
                App.diagnostics?.let { d ->
                    d.startupFirstFrameMs = System.currentTimeMillis() - App.startupT0Ms
                    d.recordBreadcrumb("home: stage1 first-frame")
                }
            } catch (ignored: Throwable) { }
        }
        rebuild()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        try { prefs.setOwnForegroundAt(System.currentTimeMillis()) } catch (ignored: Throwable) { }
        carousel?.start()
        handler.post(clockTick)
        if (!coldDone) {
            handler.removeCallbacks(coldRunnable)
            handler.postDelayed(coldRunnable, 10_000)
        }
        if (!startupCleared) {
            handler.removeCallbacks(startupClearRunnable)
            handler.postDelayed(startupClearRunnable, 10_000)
        }
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        carousel?.stop()
        handler.removeCallbacks(clockTick)
        handler.removeCallbacks(coldRunnable)
        handler.removeCallbacks(startupClearRunnable)
        try { coldRunner?.cancel() } catch (ignored: Throwable) { }
    }

    override fun onDestroy() {
        destroyedFlag = true
        carousel?.stop()
        handler.removeCallbacks(coldRunnable)
        handler.removeCallbacks(startupClearRunnable)
        try { coldRunner?.cancel() } catch (ignored: Throwable) { }
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP && event.keyCode == KeyEvent.KEYCODE_SEARCH) {
            try { startActivity(Intent(this, SearchActivity::class.java)) } catch (ignored: Throwable) { }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // ---- HomeUi.Host ----

    override fun onCardPlay(card: HomeCard) {
        Thread {
            try {
                val item = UiWiring.playTitle?.playCard(card)
                Ui.runMain {
                    if (item != null) Ui.openPlayer(this, item)
                    else Ui.openDetails(this, card.tmdbId, card.kind.name.lowercase(), card.title)
                }
            } catch (e: Throwable) {
                Ui.runMain { Ui.openDetails(this, card.tmdbId, card.kind.name.lowercase(), card.title) }
            }
        }.start()
    }

    override fun onCardDetails(card: HomeCard) {
        Ui.openDetails(this, card.tmdbId, card.kind.name.lowercase(), card.title)
    }

    override fun onCardMenu(card: HomeCard) { HomeUi.cardMenu(this, card, this) }
    override fun onOpenLibrary() { startActivity(Intent(this, LibraryActivity::class.java)) }
    override fun onOpenApps() { startActivity(Intent(this, AllAppsActivity::class.java)) }
    override fun onOpenSearch() { startActivity(Intent(this, SearchActivity::class.java)) }
    override fun onOpenSettings() { startActivity(Intent(this, SettingsActivity::class.java)) }
    override fun onOpenIptv() { startActivity(Intent(this, IptvActivity::class.java)) }
    override fun onOpenForecast() { startActivity(Intent(this, ForecastActivity::class.java)) }

    // ---- Build ----

    private data class HomeData(
        val style: String,
        val profileName: String,
        val guestActive: Boolean,
        val weatherText: String,
        val clock24h: Boolean,
        val showBar: Boolean,
        val stickyBar: Boolean,
        val showClock: Boolean,
        val showDate: Boolean,
        val showWeather: Boolean,
        val hero: HomeCard?,
        val upNext: List<HomeCard>,
        val missed: List<HomeCard>,
        val continueCards: List<HomeCard>,
        val trendingTv: List<HomeCard>,
        val trendingMovies: List<HomeCard>,
        val forYou: List<HomeCard>,
        val favorites: List<InstalledApp>,
        val recents: List<InstalledApp>,
        val mostUsed: List<InstalledApp>,
        val folders: List<com.mizan.launcher.domain.Folder>,
        val appsRow: List<InstalledApp>,
        val nowPlaying: List<com.mizan.launcher.domain.NowPlayingItem>,
        val tvContinue: List<TvData.ContinueCard>,
        val liveTv: List<LiveTvData.Channel>,
        val liveTvState: String,
        val inputs: List<String>,
        val wallpaperUrl: String?,
        val themeId: String,
        val showWatchHint: Boolean,
        val watchOrder: List<String>,
        val tvOrder: List<String>,
        val sectionVisible: (String) -> Boolean,
    )

    private fun gather(): HomeData {
        val settings = UiWiring.settings
        val global = settings?.getGlobal()
        val profileId = global?.currentProfile ?: 1
        val profile = settings?.getProfile(profileId)
        val style = if (safeShell) "tv" else if (profile?.homeStyle?.name == "WATCH") "watch" else "tv"
        val guestActive = (global?.guestProfile ?: 0) != 0 && global?.guestProfile == profileId
        val weatherText = try {
            val snap = UiWiring.weather?.getCached()
            val cur = snap?.current
            if (cur != null) "${cur.temperature.toInt()}°" else ""
        } catch (e: Throwable) { "" }
        val apps = UiWiring.apps
        val favorites = try { apps?.getFavorites() ?: emptyList() } catch (e: Throwable) { emptyList() }
        val appsRow = try { apps?.getAppsRow() ?: emptyList() } catch (e: Throwable) { emptyList() }
        val folders = try { apps?.getFolders() ?: emptyList() } catch (e: Throwable) { emptyList() }
        val allApps = try { apps?.getAllApps() ?: emptyList() } catch (e: Throwable) { emptyList() }
        val byPkg = allApps.associateBy { it.packageName }
        val recents = try {
            UiWiring.apps?.getRecents()?.mapNotNull { byPkg[it.packageName] } ?: emptyList()
        } catch (e: Throwable) { emptyList() }
        val mostUsed = try {
            UiWiring.apps?.getMostUsed()?.mapNotNull { byPkg[it.packageName] } ?: emptyList()
        } catch (e: Throwable) { emptyList() }
        val hero = try { UiWiring.hero?.invoke() } catch (e: Throwable) { null }
        val upNext = try { UiWiring.upNext?.invoke() ?: emptyList() } catch (e: Throwable) { emptyList() }
        val missed = try { UiWiring.missed?.invoke() ?: emptyList() } catch (e: Throwable) { emptyList() }
        val continueCards = try {
            UiWiring.tracker?.getContinueWatching() ?: emptyList()
        } catch (e: Throwable) { emptyList() }
        val trendingTv = try { UiWiring.trending?.trendingTv() ?: emptyList() } catch (e: Throwable) { emptyList() }
        val trendingMovies = try { UiWiring.trending?.trendingMovies() ?: emptyList() } catch (e: Throwable) { emptyList() }
        val forYou = try { UiWiring.forYou?.invoke() ?: emptyList() } catch (e: Throwable) { emptyList() }
        val nowPlaying = try { UiWiring.nowPlaying?.getSessions() ?: emptyList() } catch (e: Throwable) { emptyList() }
        val tvContinue = try { TvData.continueWatching(this) } catch (e: Throwable) { emptyList() }
        val live = try { LiveTvData.channels(this) } catch (e: Throwable) { LiveTvData.Result("blocked", emptyList()) }
        val inputs = try {
            val tim = getSystemService(TvInputManager::class.java)
            tim?.tvInputList?.map { it.loadLabel(this@MainActivity)?.toString() ?: "" }?.filter { it.isNotBlank() } ?: emptyList()
        } catch (e: Throwable) { emptyList() }
        // Wallpaper source (URL or file path); the bitmap itself
        // loads on the main thread via ImageLoader (fail-open).
        val wallpaperUrl: String? = try {
            when (profile?.wallpaperKind?.name) {
                "MY_PICTURE" -> profile.wallpaperValue.ifBlank { null }
                // Reddit: rotation due-check on home build (wake) —
                // when the interval has elapsed, nextNow advances the
                // rotation index and fetches the next subreddit's top
                // image; otherwise the first valid subreddit's top image.
                "REDDIT" -> if (com.mizan.launcher.data.WallpaperRotator.due(prefs)) {
                    com.mizan.launcher.data.WallpaperRotator.nextNow(prefs)?.url
                } else prefs.redditSubs().split(',').map {
                    com.mizan.launcher.core.RedditWallpapers.cleanSubreddit(it)
                }.firstOrNull {
                    com.mizan.launcher.core.RedditWallpapers.isValidSubreddit(it)
                }?.let { RedditFetcher.fetchTopOfWeek(prefs, it)?.url }
                "BING" -> BingFetcher.fetchDaily(this, prefs)?.absolutePath
                else -> null
            }
        } catch (e: Throwable) { null }
        val showWatchHint = style == "watch" && !(global?.watchHintDismissed ?: false) &&
            try { !prefs.iptvAnyConfigured() } catch (e: Throwable) { false }
        val watchOrder = try { settings?.getWatchSectionOrder() ?: SectionIds.WATCH_SECTIONS } catch (e: Throwable) { SectionIds.WATCH_SECTIONS }
        val tvOrder = try { settings?.getTvRowOrder() ?: SectionIds.TV_ROWS } catch (e: Throwable) { SectionIds.TV_ROWS }
        val visible: (String) -> Boolean = { id ->
            try { settings?.getSectionVisibility(id) ?: true } catch (e: Throwable) { true }
        }
        return HomeData(style, profile?.name ?: "Profile $profileId", guestActive, weatherText,
            profile?.statusBar?.clock24h ?: false, profile?.statusBar?.showBar ?: true,
            profile?.statusBar?.sticky ?: false, profile?.statusBar?.showClock ?: true,
            profile?.statusBar?.showDate ?: true, profile?.statusBar?.showWeather ?: true,
            hero, upNext, missed, continueCards, trendingTv, trendingMovies, forYou,
            favorites, recents, mostUsed, folders, appsRow, nowPlaying, tvContinue,
            live.channels, live.state, inputs, wallpaperUrl, profile?.themeId ?: "apple_black",
            showWatchHint, watchOrder, tvOrder, visible)
    }

    private fun rebuild() {
        if (destroyedFlag) return
        val generation = ++renderGeneration
        Thread {
            val data = try { gather() } catch (e: Throwable) { null }
            Ui.runMain {
                if (destroyedFlag || generation != renderGeneration) return@runMain
                try {
                    if (data == null) throw IllegalStateException("home gather failed")
                    render(data, generation)
                    fullBuildOk = true
                    App.afterFirstFrame = true
                    try {
                        App.diagnostics?.let { d ->
                            d.startupFullMs = System.currentTimeMillis() - App.startupT0Ms
                            // v3.9 home: diagnostics line — counts only, never titles.
                            d.recordBreadcrumb("home: style=${data.style} hero=${if (data.hero != null) 1 else 0} " +
                                "missed=${data.missed.size} upnext=${data.upNext.size} " +
                                "continue=${data.continueCards.size} coldStart=true safeShell=$safeShell")
                        }
                    } catch (ignored: Throwable) { }
                } catch (e: Throwable) {
                    // Whole-home fallback HOLDS the startup clear, and
                    // the emergency root renders instead of a black screen.
                    fallbackUsed = true
                    fullBuildOk = false
                    try { UiWiring.diagnostics?.noteError("home build: " + e.javaClass.simpleName) } catch (ignored: Throwable) { }
                    setContentView(emergencyRoot())
                }
            }
        }.start()
    }

    private fun emergencyRoot(): View {
        val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL
        col.addView(Ui.text(this, "Sreeb hit a snag building home", 22f, 0xFFFFFFFF.toInt(), true))
        col.addView(Ui.button(this, "Open Settings") { onOpenSettings() })
        col.addView(Ui.button(this, "Retry") { fallbackUsed = false; rebuild() })
        return Ui.rootScrollView(this, col)
    }

    private fun render(data: HomeData, generation: Int) {
        // Stop the previous carousel before replacing the content.
        try { carousel?.stop() } catch (ignored: Throwable) { }
        carousel = null
        carouselRef = null
        val preset = Themes.preset(data.themeId)
        val root = LinearLayout(this); root.orientation = LinearLayout.VERTICAL
        root.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(preset.bgTop, preset.bgBottom))
        // Wallpaper bitmap, best-effort: theme gradient stays on failure.
        data.wallpaperUrl?.let { url ->
            val path = if (url.startsWith("http") || url.startsWith("content://")) url else url
            ImageLoader.loadBitmap(path) { bmp ->
                if (bmp != null && generation == renderGeneration) {
                    try { root.background = BitmapDrawable(resources, bmp) } catch (ignored: Throwable) { }
                }
            }
        }
        val content = LinearLayout(this); content.orientation = LinearLayout.VERTICAL
        // Status bar (TV style): clock · date · weather + Guest pill +
        // quick tiles. Sticky pins it above the scroll content; otherwise
        // it scrolls with home (MAP §3.5).
        val statusBar = buildStatusBar(data)
        if (data.showBar) {
            if (data.stickyBar) root.addView(statusBar) else content.addView(statusBar)
        }
        // Unified pill tab bar (Watch: Home · Library · Apps · Live, Live LAST).
        content.addView(HomeUi.tabBar(this, data.style, this))
        if (data.showWatchHint) {
            content.addView(HomeUi.watchHint(this,
                onDismiss = {
                    try { prefs.setWatchHintDismissed(true) } catch (ignored: Throwable) { }
                    rebuild()
                },
                onOpenIptv = { onOpenIptv() }))
        }
        if (safeShell) {
            content.addView(HomeUi.safeShell(this, this))
            content.addView(appRowView("Favorites", data.favorites))
            content.addView(appRowView("Apps", data.appsRow))
        } else if (data.style == "watch") {
            renderWatch(content, data)
        } else {
            renderTv(content, data)
        }
        val actions = LinearLayout(this); actions.orientation = LinearLayout.HORIZONTAL
        actions.addView(Ui.button(this, "Quick settings") {
            QuickSettingsView.show(this,
                onSortSections = { onOpenSettings() },
                onRefreshHome = { refreshHomeData() },
                onOpenSettings = { onOpenSettings() })
        })
        actions.addView(Ui.button(this, "Refresh home data") { refreshHomeData() })
        content.addView(actions)
        val scroll = Ui.rootScrollView(this, content)
        root.addView(scroll)
        setContentView(root)
    }

    private fun buildStatusBar(data: HomeData): View {
        // Own status row (instead of QuickSettingsView.statusBar) so
        // the clock TextView stays reachable for the 1 s / 20 s tick.
        val bar = LinearLayout(this); bar.orientation = LinearLayout.HORIZONTAL
        if (data.showClock) {
            clockView = Ui.text(this, "", 18f, 0xFFFFFFFF.toInt(), true)
            bar.addView(clockView)
        } else clockView = null
        if (data.showDate) {
            bar.addView(Ui.text(this, SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(Date()), 18f))
        }
        if (data.showWeather && data.weatherText.isNotEmpty()) {
            bar.addView(Ui.button(this, data.weatherText) { onOpenForecast() })
        }
        if (data.guestActive) bar.addView(Ui.text(this, "Guest", 16f))
        bar.addView(Ui.button(this, "Forecast") { onOpenForecast() })
        bar.addView(Ui.button(this, "⚙") { onOpenSettings() })
        return bar
    }

    private fun renderWatch(content: LinearLayout, data: HomeData) {
        // Hero carousel: candidates in pool order Missed → Up Next →
        // Continue (backdrop-first) → For You; <2 never rotate
        // (core HeroRotator enforces that inside HeroCarousel).
        val heroCandidates = ArrayList<HomeCard>()
        heroCandidates.addAll(data.missed)
        heroCandidates.addAll(data.upNext)
        val contSorted = data.continueCards.sortedWith(
            compareByDescending<HomeCard> { it.backdropPath != null })
        heroCandidates.addAll(contSorted)
        heroCandidates.addAll(data.forYou)
        val deduped = heroCandidates.distinctBy { it.kind to it.tmdbId }
        heroContainer = LinearLayout(this); heroContainer!!.orientation = LinearLayout.VERTICAL
        content.addView(heroContainer)
        val carousel = HomeUi.HeroCarousel { card ->
            heroContainer?.removeAllViews()
            heroContainer?.addView(HomeUi.heroView(this, card,
                onPlay = { card?.let { onCardPlay(it) } },
                onNext = { carouselRef?.next() }))
        }
        carouselRef = carousel
        carousel.setCards(deduped)
        carousel.start()
        this.carousel = carousel
        val sections = ArrayList<SectionRender>()
        fun addSection(id: String, cards: List<HomeCard>) {
            if (!data.sectionVisible(id)) return
            sections.add(SectionRender(id,
                if (cards.isEmpty()) SectionState.EMPTY else SectionState.READY, cards))
        }
        addSection(SectionIds.UP_NEXT, data.upNext)
        addSection(SectionIds.MISSED, data.missed)
        addSection(SectionIds.CONTINUE, data.continueCards)
        addSection(SectionIds.TRENDING_TV, data.trendingTv)
        addSection(SectionIds.TRENDING_MOVIES, data.trendingMovies)
        addSection(SectionIds.FOR_YOU, data.forYou)
        val order = data.watchOrder
        val ordered = sections.sortedBy { order.indexOf(it.sectionId).let { i -> if (i < 0) 99 else i } }
        for (s in ordered) {
            val title = when (s.sectionId) {
                SectionIds.UP_NEXT -> "Up Next"
                SectionIds.MISSED -> "Missed"
                SectionIds.CONTINUE -> "Continue Watching"
                SectionIds.TRENDING_TV -> "Trending TV"
                SectionIds.TRENDING_MOVIES -> "Trending Movies"
                SectionIds.FOR_YOU -> "For You"
                else -> s.sectionId
            }
            content.addView(Ui.guarded(this) {
                HomeUi.cardRow(this, title, s.cards, this, s.state,
                    onRetry = { refreshHomeData() })
            })
        }
        if (data.sectionVisible(SectionIds.FAVORITES)) content.addView(appRowView("Favorites", data.favorites))
        if (data.sectionVisible(SectionIds.FOLDERS)) content.addView(folderRowView(data))
        if (data.sectionVisible(SectionIds.APPS)) content.addView(appRowView("Apps", data.appsRow))
    }

    private var carouselRef: HomeUi.HeroCarousel? = null

    private fun renderTv(content: LinearLayout, data: HomeData) {
        val rows = LinkedHashMap<String, List<View>>()
        rows["favorites"] = appTiles(data.favorites)
        rows["recents"] = appTiles(data.recents)
        rows["mostused"] = appTiles(data.mostUsed)
        rows["folders"] = folderTiles(data)
        rows["nowplaying"] = data.nowPlaying.map { item ->
            PosterCards.textCard(this,
                item.title, item.artist + if (item.readsAsPlaying) " · Playing" else " · Paused",
                onClick = { Thread { try { UiWiring.nowPlaying?.toggle(item) } catch (ignored: Throwable) { } }.start() })
        }
        rows["continue"] = data.tvContinue.map { c ->
            PosterCards.textCard(this, c.title, "", onClick = { })
        }
        rows["inputs"] = data.inputs.map { label ->
            PosterCards.textCard(this, label, "HDMI input",
                onClick = { Ui.toast(this, "Switch inputs from your TV's source menu") })
        }
        rows["livetv"] = data.liveTv.map { ch ->
            PosterCards.textCard(this, ch.name, "Tuner channel",
                onClick = { Ui.toast(this, "Tuner playback is not wired in this build") })
        }
        rows["apps"] = appTiles(data.appsRow)
        val titles = mapOf(
            "favorites" to "Favorites", "recents" to "Recently Used",
            "mostused" to "Most Used", "folders" to "Folders",
            "nowplaying" to "Now Playing", "continue" to "Continue Watching",
            "inputs" to "Inputs", "livetv" to "Live TV", "apps" to "Apps")
        for (id in data.tvOrder) {
            if (!data.sectionVisible(id)) continue
            val views = rows[id] ?: continue
            if (views.isEmpty()) continue
            content.addView(Ui.sectionTitle(this, titles[id] ?: id))
            val inner = LinearLayout(this); inner.orientation = LinearLayout.HORIZONTAL
            val hs = HorizontalScrollView(this)
            views.take(20).forEach { inner.addView(it) }
            hs.addView(inner); content.addView(hs)
        }
        if (data.liveTv.isEmpty()) {
            // Tuner row stays hidden without data; the diagnostics
            // state string is recorded (counts/state only).
            try { UiWiring.diagnostics?.recordBreadcrumb("livetv: ${data.liveTvState}") } catch (ignored: Throwable) { }
        }
    }

    private fun appTiles(apps: List<InstalledApp>): List<View> =
        apps.map { app ->
            PosterCards.appTile(this, app,
                onClick = { launchApp(app) },
                onLong = { appMenu(app) })
        }

    private fun appRowView(title: String, apps: List<InstalledApp>): View {
        val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL
        if (apps.isEmpty()) return col
        col.addView(Ui.sectionTitle(this, title))
        val inner = LinearLayout(this); inner.orientation = LinearLayout.HORIZONTAL
        val hs = HorizontalScrollView(this)
        appTiles(apps).take(20).forEach { inner.addView(it) }
        hs.addView(inner); col.addView(hs)
        return col
    }

    private fun folderTiles(data: HomeData): List<View> =
        data.folders.filter { it.depth == 1 }.map { folder ->
            PosterCards.textCard(this, "▣ ${folder.name}", "${folder.entries.size} entries",
                onClick = { FolderViewActivity.open(this, folder.id) })
        }

    private fun folderRowView(data: HomeData): View {
        val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL
        val tiles = folderTiles(data)
        if (tiles.isEmpty()) return col
        col.addView(Ui.sectionTitle(this, "Folders"))
        val inner = LinearLayout(this); inner.orientation = LinearLayout.HORIZONTAL
        val hs = HorizontalScrollView(this)
        tiles.take(20).forEach { inner.addView(it) }
        hs.addView(inner); col.addView(hs)
        return col
    }

    private fun launchApp(app: InstalledApp) {
        val locked = try { UiWiring.lock?.isAppLocked(app.packageName) == true } catch (e: Throwable) { false }
        val patternSet = try { UiWiring.lock?.isPatternSet() == true } catch (e: Throwable) { false }
        val doLaunch = {
            Thread { try { UiWiring.apps?.recordLaunch(app.packageName) } catch (ignored: Throwable) { } }.start()
            if (!Ui.launchApp(this, app.packageName)) {
                Ui.toast(this, "Cannot launch ${app.label}")
            }
        }
        if (locked && patternSet) PatternGate.run(this) { doLaunch() } else doLaunch()
    }

    private fun appMenu(app: InstalledApp) {
        val items = mutableListOf<Pair<String, () -> Unit>>()
        items += "Hide app" to { Thread { try { UiWiring.apps?.setHidden(app.packageName, true); Ui.runMain { rebuild() } } catch (ignored: Throwable) { } }.start() }
        items += "App info" to {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${app.packageName}")))
            } catch (ignored: Throwable) { }
        }
        if (!app.isSystem) {
            items += "Uninstall" to {
                try { startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.packageName}"))) } catch (ignored: Throwable) { }
            }
        }
        val locked = try { UiWiring.lock?.isAppLocked(app.packageName) == true } catch (e: Throwable) { false }
        items += (if (locked) "Unlock app" else "Lock app") to {
            Thread { try { UiWiring.lock?.setAppLocked(app.packageName, !locked); Ui.runMain { rebuild() } } catch (ignored: Throwable) { } }.start()
        }
        Ui.menu(this, app.label, items)
    }

    private fun refreshHomeData() {
        Thread {
            try { TrackerRefreshWorker(this).refresh() } catch (ignored: Throwable) { }
            try { UiWiring.iptv } catch (ignored: Throwable) { }
            Ui.runMain { rebuild() }
        }.start()
    }
}
