package com.mizan.launcher.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.mizan.launcher.core.Design
import com.mizan.launcher.core.IptvEpg
import com.mizan.launcher.core.IptvLocks
import com.mizan.launcher.core.IptvLogic
import com.mizan.launcher.core.IptvStatus
import com.mizan.launcher.data.IptvClient
import com.mizan.launcher.data.PlayItem
import com.mizan.launcher.data.PlayQueue
import com.mizan.launcher.data.Prefs
import kotlin.concurrent.thread

/**
 * The IPTV browser (v2.2, spec §20.4; v2.4 data flow, spec §24) —
 * the LibraryActivity-shaped home for the IPTV catalogues: tabs
 * Live / Movies / Series / Favorites / Search, category chips per
 * tab, poster/logo grids.
 *
 * v2.4 DATA FLOW: the browser NEVER holds a catalogue. Categories
 * + counts come from the disk store's index (data/IptvClient.
 * snapshot — small, synchronous); items arrive as SLICES scanned
 * from the store files on worker threads (IptvClient.sliceAsync),
 * one window per tab/category, with "showing N of M" + Load more
 * walking the window forward. The player's zap queue is the
 * currently loaded slice. Refresh happens ONLY from the header
 * button (and Settings › IPTV) — opening or resuming this screen
 * fetches nothing.
 *
 * - Channel OK → play immediately. Movie OK → play (the player
 *   resumes a saved position). Series OK → an in-screen details
 *   view with seasons/episodes from get_series_info.
 * - Hold-OK anywhere → a menu: Play/Open, Favorite/Unfavorite,
 *   plus Watch from start on archive channels with EPG data.
 * - Category chips: OK selects; hold-OK locks/unlocks the
 *   category (spec §20.8) behind the profile's arrow pattern.
 *
 * Fail-open throughout: no account / no data / store trouble all
 * render as worded empty states (core/IptvStatus), never a crash.
 */
class IptvActivity : Activity() {

    companion object {
        const val TAB_LIVE = "live"
        const val TAB_MOVIES = "movies"
        const val TAB_SERIES = "series"
        const val TAB_FAVORITES = "favorites"
        const val TAB_SEARCH = "search"

        /** Hard ceiling on rendered cards per tab (Load-more stops here — narrow down instead). */
        private const val MAX_RENDERED = 1000

        private const val X_TAB = "tab"
        private const val X_SERIES_ID = "series_id"

        fun intent(ctx: Context, tab: String = TAB_LIVE, seriesId: Long = 0L): Intent {
            val i = Intent(ctx, IptvActivity::class.java)
            i.putExtra(X_TAB, tab)
            if (seriesId > 0L) i.putExtra(X_SERIES_ID, seriesId)
            return i
        }
    }

    private lateinit var prefs: Prefs
    private val accent: Int = Design.NETFLIX_RED

    private var contentBox: LinearLayout? = null
    private var tab: String = TAB_LIVE
    /** Selected category key per tab-kind (null = All). */
    private val selectedCat = HashMap<String, String?>()
    private var searchQuery: String = ""
    private var focusedKey: String? = null
    private var firstCard: View? = null

    // v2.4 slice state per catalogue kind: the loaded window, its
    // true total, the state key it belongs to (kind|category), a
    // generation counter that retires stale slice callbacks, and
    // whether a slice is in flight.
    private var liveItems: List<IptvLogic.LiveChannel> = emptyList()
    private var liveTotal = 0
    private var liveKey = ""
    private var liveGen = 0
    private var liveLoading = false

    private var vodItems: List<IptvLogic.VodItem> = emptyList()
    private var vodTotal = 0
    private var vodKey = ""
    private var vodGen = 0
    private var vodLoading = false

    private var seriesItems: List<IptvLogic.SeriesItem> = emptyList()
    private var seriesTotal = 0
    private var seriesKey = ""
    private var seriesGen = 0
    private var seriesLoading = false

    // Search slice state (per kind, keyed by the query).
    private var searchKey = ""
    private var searchGen = 0
    private var searchLive: List<IptvLogic.LiveChannel> = emptyList()
    private var searchLiveTotal = 0
    private var searchVods: List<IptvLogic.VodItem> = emptyList()
    private var searchVodTotal = 0
    private var searchSeries: List<IptvLogic.SeriesItem> = emptyList()
    private var searchSeriesTotal = 0
    private var searchLoading = false

    /** Favorites are a prefs list (small); paging is local. */
    private var favShown: Int = IptvLogic.GRID_CAP

    /** The store generation the loaded slices belong to. */
    private var sliceStamp: Long = Long.MIN_VALUE

    // Series details state.
    private var detailSeries: IptvLogic.SeriesItem? = null
    private var detailInfo: IptvLogic.SeriesInfo? = null
    private var detailLoading = false
    private var detailError: String? = null
    private var detailSeason: Int = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.mizan.launcher.data.DiagLog.note("iptv browser opened")
        prefs = Prefs(this)
        tab = intent.getStringExtra(X_TAB) ?: TAB_LIVE
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Design.NETFLIX_BG)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(Ui.dp(this, 48), Ui.dp(this, 26), Ui.dp(this, 48), Ui.dp(this, 30))
        scroll.addView(box)
        contentBox = box
        setContentView(scroll)

        val seriesId = intent.getLongExtra(X_SERIES_ID, 0L)
        if (seriesId > 0L) {
            tab = TAB_SERIES
            // v2.4: resolve the deep-linked series from the store
            // on a worker thread; the tab renders meanwhile.
            thread(name = "mizan-iptv-deeplink") {
                val s = IptvClient.findSeriesBlocking(this, seriesId)
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    if (s != null) {
                        openSeries(s)
                    }
                    rebuild()
                }
            }
        }
        rebuild()
        // Local EPG index from the store file (disk, not network).
        IptvClient.epgIndex(this) { if (!isFinishing) rebuild() }
    }

    override fun onResume() {
        super.onResume()
        // v2.4: resume rebuilds from the store as-is; it never
        // triggers a fetch (refresh is on request only).
        rebuild()
    }

    // ------------------------------------------------------------ lock state

    private fun allCategories(snap: IptvClient.Snapshot): List<IptvLogic.Category> {
        val out = ArrayList<IptvLogic.Category>()
        out.addAll(snap.allCats())
        // Categories referenced only by favorites (catalogue may
        // have moved on) still lock/unlock by key.
        val known = out.map { it.key }.toSet()
        for (fav in prefs.iptvFavoriteList()) {
            if (fav.catKey.isNotEmpty() && fav.catKey !in known) {
                val parts = fav.catKey.split(":")
                if (parts.size == 3) out.add(IptvLogic.Category(parts[2], "", parts[0], parts[1]))
            }
        }
        return out
    }

    private fun hiddenCatKeys(snap: IptvClient.Snapshot): Set<String> =
        IptvLocks.hiddenKeys(
            allCategories(snap),
            IptvLocks.decodeKeys(prefs.iptvLockedCats),
            IptvLocks.decodeStamps(prefs.iptvCatUnlockStamps),
            prefs.unlockGraceMinutes,
            System.currentTimeMillis(),
            IptvClient.sessionStartMs,
            prefs.arrowPin.isNotEmpty()
        )

    private fun categoryLocked(cat: IptvLogic.Category): Boolean =
        IptvLocks.isLocked(
            cat.key, cat.name,
            IptvLocks.decodeKeys(prefs.iptvLockedCats),
            IptvLocks.decodeStamps(prefs.iptvCatUnlockStamps),
            prefs.unlockGraceMinutes,
            System.currentTimeMillis(),
            IptvClient.sessionStartMs,
            prefs.arrowPin.isNotEmpty()
        )

    // ------------------------------------------------------------ render

    /**
     * v2.3.2: EVERY browser build runs behind this guard. Real
     * panel data must never leave this activity black: any escape
     * — exception or an inflation OOM — is recorded and replaced
     * by a worded error screen. (Throwable, not Exception: running
     * out of memory mid-inflate is precisely one of the failure
     * modes being defended against.)
     */
    private fun rebuild() {
        progressLineView = null
        try {
            rebuildInner()
        } catch (e: Throwable) {
            Prefs.noteError(this, "iptvbrowser", e)
            renderBrowserError()
        }
    }

    private fun renderBrowserError() {
        val box = contentBox ?: return
        try {
            box.removeAllViews()
            firstCard = null
            val title = TextView(this)
            title.text = "IPTV"
            title.setTextColor(0xFFFFFFFF.toInt())
            title.textSize = 28f
            title.setTypeface(title.typeface, Typeface.BOLD)
            box.addView(title)
            val line = TextView(this)
            line.text = "The IPTV browser hit a snag showing this — nothing is broken. " +
                "Press Back and try again; the error is noted in the launcher's diagnostics."
            line.setTextColor(0xFFB3B3B3.toInt())
            line.textSize = 17f
            val lp = LinearLayout.LayoutParams(-1, -2)
            lp.setMargins(0, Ui.dp(this, 14), 0, 0)
            line.layoutParams = lp
            box.addView(line)
        } catch (e: Throwable) {
            // Even the fallback failed — leave whatever is on
            // screen rather than crash the default home's process.
        }
    }

    private fun rebuildInner() {
        val box = contentBox ?: return
        val focusedBefore = focusedKey
        box.removeAllViews()
        firstCard = null

        val snap = IptvClient.snapshot(this)

        // A completed refresh swaps the store under the loaded
        // slices: retire every window so each tab re-reads its
        // slice from the new generation.
        val stamp = IptvClient.storeStamp(this)
        if (stamp != sliceStamp) {
            sliceStamp = stamp
            liveKey = ""; vodKey = ""; seriesKey = ""; searchKey = ""
            liveItems = emptyList(); vodItems = emptyList(); seriesItems = emptyList()
            // Retire in-flight slice callbacks (their results
            // belong to the old generation) and free the loading
            // flags they owned — their callbacks return early on
            // the generation check without touching either.
            liveGen++; vodGen++; seriesGen++; searchGen++
            liveLoading = false; vodLoading = false
            seriesLoading = false; searchLoading = false
        }

        // Header.
        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        val title = TextView(this)
        title.text = "IPTV"
        title.setTextColor(0xFFFFFFFF.toInt())
        title.textSize = 28f
        title.setTypeface(title.typeface, Typeface.BOLD)
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(headerButton("⟳ Refresh") {
            com.mizan.launcher.data.DiagLog.note("iptv refresh tapped")
            IptvClient.refreshAsync(this, force = true) { onIptvProgressTick() }
            Toast.makeText(this, "Refreshing IPTV…", Toast.LENGTH_SHORT).show()
        })
        box.addView(header)

        // Slim status line: ingest progress while a refresh runs,
        // else the store's age (v2.4: data loads on request only,
        // so its age is always worth showing).
        val progress = IptvClient.ingestProgressText()
        if (progress.isNotEmpty()) {
            progressLineView = countLine(box, progress)
        } else if (snap.fetchedAt > 0L) {
            val timeText = java.text.SimpleDateFormat("h:mm a", java.util.Locale.US)
                .format(java.util.Date(snap.fetchedAt))
            progressLineView = countLine(box, "Updated $timeText")
        }

        // Tabs.
        val tabsRow = LinearLayout(this)
        tabsRow.orientation = LinearLayout.HORIZONTAL
        val tabLp = LinearLayout.LayoutParams(-1, -2)
        tabLp.setMargins(0, Ui.dp(this, 12), 0, Ui.dp(this, 6))
        tabsRow.layoutParams = tabLp
        val tabs = listOf(
            TAB_LIVE to "📺 Live", TAB_MOVIES to "🎬 Movies", TAB_SERIES to "📚 Series",
            TAB_FAVORITES to "★ Favorites", TAB_SEARCH to "🔍 Search"
        )
        for ((key, label) in tabs) {
            val chip = chip(label, selected = tab == key && detailSeries == null) {
                tab = key
                detailSeries = null
                detailInfo = null
                focusedKey = null
                rebuild()
            }
            tabsRow.addView(chip)
        }
        box.addView(tabsRow)

        if (detailSeries != null) {
            buildSeriesDetail(box)
        } else when (tab) {
            TAB_MOVIES -> buildCatalogueTab(box, snap, IptvLogic.KIND_VOD)
            TAB_SERIES -> buildCatalogueTab(box, snap, IptvLogic.KIND_SERIES)
            TAB_FAVORITES -> buildFavoritesTab(box, snap)
            TAB_SEARCH -> buildSearchTab(box, snap)
            else -> buildCatalogueTab(box, snap, IptvLogic.KIND_LIVE)
        }

        val target = focusedBefore?.let { findFrameByTag(box, it) } ?: firstCard
        target?.post { target.requestFocus() }
    }

    private fun emptyState(box: LinearLayout, snap: IptvClient.Snapshot) {
        val kind = IptvClient.status(this)
        val line = TextView(this)
        line.text = IptvStatus.emptyLine(kind)
            ?: if (!snap.hasData) "Nothing here yet — try Refresh" else ""
        line.setTextColor(0xFFB3B3B3.toInt())
        line.textSize = 17f
        box.addView(line)
        if (kind == IptvStatus.Kind.NOT_CONFIGURED) {
            val btn = headerButton("Open Settings › IPTV") {
                startActivity(Intent(this, SettingsActivity::class.java))
            }
            val lp = LinearLayout.LayoutParams(-2, -2)
            lp.setMargins(0, Ui.dp(this, 12), 0, 0)
            btn.layoutParams = lp
            box.addView(btn)
            btn.post { btn.requestFocus() }
        }
    }

    // ------------------------------------------------------------ slices

    private fun kindKey(kind: String): String = kind + "|" + (selectedCat[kind] ?: "")

    private fun requestSlice(kind: String, append: Boolean) {
        val offset: Int
        val gen: Int
        when (kind) {
            IptvLogic.KIND_VOD -> {
                if (vodLoading) return
                vodLoading = true
                gen = ++vodGen
                offset = if (append) vodItems.size else 0
            }
            IptvLogic.KIND_SERIES -> {
                if (seriesLoading) return
                seriesLoading = true
                gen = ++seriesGen
                offset = if (append) seriesItems.size else 0
            }
            else -> {
                if (liveLoading) return
                liveLoading = true
                gen = ++liveGen
                offset = if (append) liveItems.size else 0
            }
        }
        IptvClient.sliceAsync(
            this, kind,
            catKey = selectedCat[kind],
            offset = offset,
            limit = IptvLogic.GRID_CAP
        ) { page ->
            if (isFinishing) return@sliceAsync
            when (kind) {
                IptvLogic.KIND_VOD -> {
                    if (gen != vodGen) return@sliceAsync
                    vodLoading = false
                    vodTotal = page.total
                    vodItems = if (append) vodItems + page.vods else page.vods
                }
                IptvLogic.KIND_SERIES -> {
                    if (gen != seriesGen) return@sliceAsync
                    seriesLoading = false
                    seriesTotal = page.total
                    seriesItems = if (append) seriesItems + page.series else page.series
                }
                else -> {
                    if (gen != liveGen) return@sliceAsync
                    liveLoading = false
                    liveTotal = page.total
                    liveItems = if (append) liveItems + page.live else page.live
                }
            }
            rebuild()
        }
    }

    /** Ensure the window for (kind, selected category) is loaded; true when it already was. */
    private fun ensureSlice(kind: String): Boolean {
        val key = kindKey(kind)
        val current = when (kind) {
            IptvLogic.KIND_VOD -> vodKey
            IptvLogic.KIND_SERIES -> seriesKey
            else -> liveKey
        }
        if (current == key) return true
        when (kind) {
            IptvLogic.KIND_VOD -> { vodKey = key; vodItems = emptyList(); vodTotal = 0 }
            IptvLogic.KIND_SERIES -> { seriesKey = key; seriesItems = emptyList(); seriesTotal = 0 }
            else -> { liveKey = key; liveItems = emptyList(); liveTotal = 0 }
        }
        requestSlice(kind, append = false)
        return false
    }

    private fun loadingFor(kind: String): Boolean = when (kind) {
        IptvLogic.KIND_VOD -> vodLoading
        IptvLogic.KIND_SERIES -> seriesLoading
        else -> liveLoading
    }

    private fun loadMoreButton(box: LinearLayout, label: String, onClick: () -> Unit) {
        val btn = headerButton(label) { onClick() }
        val lp = LinearLayout.LayoutParams(-2, -2)
        lp.setMargins(0, Ui.dp(this, 10), 0, Ui.dp(this, 6))
        btn.layoutParams = lp
        box.addView(btn)
    }

    // ------------------------------------------------------------ catalogue tabs

    private fun buildCatalogueTab(box: LinearLayout, snap: IptvClient.Snapshot, kind: String) {
        val categories = snap.categories(kind)
        val hidden = hiddenCatKeys(snap)

        if (kind == IptvLogic.KIND_LIVE) {
            val guideBtn = headerButton("📖 Programme guide") {
                startActivity(Intent(this, IptvGuideActivity::class.java))
            }
            val glp = LinearLayout.LayoutParams(-2, -2)
            glp.setMargins(0, Ui.dp(this, 6), 0, Ui.dp(this, 8))
            guideBtn.layoutParams = glp
            box.addView(guideBtn)
        }

        // Category chips: All + each category (locked ones shown
        // with 🔒 — they're unlockable from here).
        val chipScroller = HorizontalScrollView(this)
        val chipRow = LinearLayout(this)
        chipRow.orientation = LinearLayout.HORIZONTAL
        val selected = selectedCat[kind]
        chipRow.addView(chip("All", selected = selected == null) {
            selectedCat[kind] = null
            focusedKey = null
            rebuild()
        })
        for (cat in categories) {
            val locked = cat.key in hidden
            val label = (if (locked) "🔒 " else "") + cat.name
            val c = chip(label, selected = selected == cat.key) {
                selectedCat[kind] = cat.key
                focusedKey = null
                rebuild()
            }
            c.setOnLongClickListener {
                categoryMenu(cat)
                true
            }
            chipRow.addView(c)
        }
        chipScroller.addView(chipRow)
        box.addView(chipScroller)

        val selectedLocked = categories.any { it.key == selected && it.key in hidden }
        if (selectedLocked) {
            val line = TextView(this)
            line.text = "🔒 This category is locked. Hold OK on its chip to unlock it."
            line.setTextColor(0xFFB3B3B3.toInt())
            line.textSize = 16f
            val lp = LinearLayout.LayoutParams(-1, -2)
            lp.setMargins(0, Ui.dp(this, 14), 0, 0)
            line.layoutParams = lp
            box.addView(line)
            return
        }

        ensureSlice(kind)
        val loading = loadingFor(kind)
        when (kind) {
            IptvLogic.KIND_VOD -> {
                if (vodItems.isEmpty()) {
                    if (loading) countLine(box, "Loading…") else emptyState(box, snap)
                    return
                }
                countLine(
                    box,
                    if (vodTotal > vodItems.size) {
                        "${vodItems.size} of $vodTotal movies — OK plays, hold OK for options"
                    } else {
                        "$vodTotal movies — OK plays, hold OK for options"
                    }
                )
                grid(box, vodItems.map { vod -> vodCardData(vod) }, wide = false)
                if (vodItems.size < vodTotal) {
                    if (vodItems.size >= MAX_RENDERED) {
                        countLine(box, "Showing the first ${vodItems.size} — pick a category or use Search to narrow it down")
                    } else {
                        loadMoreButton(box, "Load more (${vodTotal - vodItems.size} more)") {
                            requestSlice(kind, append = true)
                        }
                    }
                }
            }
            IptvLogic.KIND_SERIES -> {
                if (seriesItems.isEmpty()) {
                    if (loading) countLine(box, "Loading…") else emptyState(box, snap)
                    return
                }
                countLine(
                    box,
                    if (seriesTotal > seriesItems.size) {
                        "${seriesItems.size} of $seriesTotal series — OK opens seasons & episodes"
                    } else {
                        "$seriesTotal series — OK opens seasons & episodes"
                    }
                )
                grid(box, seriesItems.map { s -> seriesCardData(s) }, wide = false)
                if (seriesItems.size < seriesTotal) {
                    if (seriesItems.size >= MAX_RENDERED) {
                        countLine(box, "Showing the first ${seriesItems.size} — pick a category or use Search to narrow it down")
                    } else {
                        loadMoreButton(box, "Load more (${seriesTotal - seriesItems.size} more)") {
                            requestSlice(kind, append = true)
                        }
                    }
                }
            }
            else -> {
                if (liveItems.isEmpty()) {
                    if (loading) countLine(box, "Loading…") else emptyState(box, snap)
                    return
                }
                countLine(
                    box,
                    if (liveTotal > liveItems.size) {
                        "${liveItems.size} of $liveTotal channels — OK plays, hold OK for options"
                    } else {
                        "$liveTotal channels — OK plays, hold OK for options"
                    }
                )
                grid(box, liveItems.map { ch -> liveCardData(ch, liveItems) }, wide = true)
                if (liveItems.size < liveTotal) {
                    if (liveItems.size >= MAX_RENDERED) {
                        countLine(box, "Showing the first ${liveItems.size} — pick a category or use Search to narrow it down")
                    } else {
                        loadMoreButton(box, "Load more (${liveTotal - liveItems.size} more)") {
                            requestSlice(kind, append = true)
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------ favorites + search tabs

    private fun buildFavoritesTab(box: LinearLayout, snap: IptvClient.Snapshot) {
        val hidden = hiddenCatKeys(snap)
        val favs = prefs.iptvFavoriteList().filter { it.catKey !in hidden }
        if (favs.isEmpty()) {
            val line = TextView(this)
            line.text = "No favorites yet. Hold OK on any channel, movie or series to add one."
            line.setTextColor(0xFFB3B3B3.toInt())
            line.textSize = 17f
            box.addView(line)
            return
        }
        // Local paging over the prefs list (v2.3.2's cap, kept):
        // page through gridPage-sized windows; Load more widens.
        val page = IptvLogic.gridPage(favs.take(favShown))
        countLine(
            box,
            if (page.total > page.items.size) {
                "${page.items.size} of ${page.total} favorites — hold OK to remove"
            } else {
                "${page.total} favorites — hold OK to remove"
            }
        )
        val liveFavs = page.items.filter { it.kind == IptvLogic.KIND_LIVE }
        grid(box, page.items.map { fav -> favCardData(fav, liveFavs) }, wide = false)
        if (page.total > page.items.size) {
            loadMoreButton(box, "Load more (${page.total - page.items.size} more)") {
                favShown += IptvLogic.GRID_CAP
                rebuild()
            }
        }
    }

    private fun requestSearchSlices(appendKind: String?) {
        val hidden = hiddenCatKeys(IptvClient.snapshot(this))
        val gen = searchGen
        fun fire(kind: String, offset: Int) {
            IptvClient.sliceAsync(
                this, kind, query = searchQuery, excludeCats = hidden,
                offset = offset, limit = IptvLogic.GRID_CAP
            ) { page ->
                if (isFinishing || gen != searchGen) return@sliceAsync
                when (kind) {
                    IptvLogic.KIND_LIVE -> {
                        searchLiveTotal = page.total
                        searchLive = if (offset > 0) searchLive + page.live else page.live
                    }
                    IptvLogic.KIND_VOD -> {
                        searchVodTotal = page.total
                        searchVods = if (offset > 0) searchVods + page.vods else page.vods
                    }
                    else -> {
                        searchSeriesTotal = page.total
                        searchSeries = if (offset > 0) searchSeries + page.series else page.series
                    }
                }
                if (appendKind == null) searchLoading = false
                rebuild()
            }
        }
        if (appendKind == null) {
            searchLoading = true
            fire(IptvLogic.KIND_LIVE, 0)
            fire(IptvLogic.KIND_VOD, 0)
            fire(IptvLogic.KIND_SERIES, 0)
        } else {
            when (appendKind) {
                IptvLogic.KIND_LIVE -> fire(appendKind, searchLive.size)
                IptvLogic.KIND_VOD -> fire(appendKind, searchVods.size)
                else -> fire(appendKind, searchSeries.size)
            }
        }
    }

    private fun buildSearchTab(box: LinearLayout, snap: IptvClient.Snapshot) {
        val btn = headerButton(
            if (searchQuery.isEmpty()) "⌨ Type to search…" else "⌨ “$searchQuery” — type to change"
        ) {
            KeyboardDialog.show(
                this, "Search IPTV", accent, searchQuery, 40, symbols = false
            ) { text ->
                searchQuery = text.trim()
                focusedKey = null
                searchKey = ""
                rebuild()
            }
        }
        box.addView(btn)
        if (searchQuery.isEmpty()) {
            countLine(box, "Searches live channels, movies and series by name.")
            return
        }
        if (searchKey != searchQuery) {
            searchKey = searchQuery
            searchGen++
            searchLive = emptyList(); searchLiveTotal = 0
            searchVods = emptyList(); searchVodTotal = 0
            searchSeries = emptyList(); searchSeriesTotal = 0
            requestSearchSlices(appendKind = null)
        }
        if (searchLive.isEmpty() && searchVods.isEmpty() && searchSeries.isEmpty()) {
            countLine(box, if (searchLoading) "Searching…" else "No matches for “$searchQuery”.")
            return
        }
        if (searchLive.isNotEmpty()) {
            sectionTitle(box, "Live — $searchLiveTotal")
            grid(box, searchLive.map { ch -> liveCardData(ch, searchLive) }, wide = true)
            if (searchLive.size < searchLiveTotal && searchLive.size < MAX_RENDERED) {
                loadMoreButton(box, "Load more live (${searchLiveTotal - searchLive.size} more)") {
                    requestSearchSlices(IptvLogic.KIND_LIVE)
                }
            }
        }
        if (searchVods.isNotEmpty()) {
            sectionTitle(box, "Movies — $searchVodTotal")
            grid(box, searchVods.map { vodCardData(it) }, wide = false)
            if (searchVods.size < searchVodTotal && searchVods.size < MAX_RENDERED) {
                loadMoreButton(box, "Load more movies (${searchVodTotal - searchVods.size} more)") {
                    requestSearchSlices(IptvLogic.KIND_VOD)
                }
            }
        }
        if (searchSeries.isNotEmpty()) {
            sectionTitle(box, "Series — $searchSeriesTotal")
            grid(box, searchSeries.map { seriesCardData(it) }, wide = false)
            if (searchSeries.size < searchSeriesTotal && searchSeries.size < MAX_RENDERED) {
                loadMoreButton(box, "Load more series (${searchSeriesTotal - searchSeries.size} more)") {
                    requestSearchSlices(IptvLogic.KIND_SERIES)
                }
            }
        }
    }

    // ------------------------------------------------------------ series detail

    private fun openSeries(series: IptvLogic.SeriesItem) {
        detailSeries = series
        detailInfo = null
        detailError = null
        detailSeason = -1
        detailLoading = true
        tab = TAB_SERIES
        thread(name = "mizan-iptv-series-info") {
            val (json, error) = IptvClient.fetchSeriesInfoBlocking(this, series.id)
            val info = if (json != null) IptvLogic.parseSeriesInfo(json, series.id) else null
            runOnUiThread {
                if (detailSeries?.id != series.id) return@runOnUiThread
                detailLoading = false
                detailInfo = info
                detailError = when {
                    info != null -> null
                    error == "not_configured" -> "Add your IPTV account in Settings › IPTV"
                    error == "auth" -> "IPTV login failed — check the account in Settings › IPTV"
                    else -> "Couldn't load this series — check your connection and try again"
                }
                if (info != null && info.seasons.isNotEmpty()) {
                    detailSeason = info.seasons.first().number
                }
                rebuild()
            }
        }
    }

    private fun buildSeriesDetail(box: LinearLayout) {
        val series = detailSeries ?: return
        val back = headerButton("‹ Back to series") {
            detailSeries = null
            detailInfo = null
            focusedKey = null
            rebuild()
        }
        box.addView(back)

        val title = TextView(this)
        title.text = series.name
        title.setTextColor(0xFFFFFFFF.toInt())
        title.textSize = 24f
        title.setTypeface(title.typeface, Typeface.BOLD)
        val tlp = LinearLayout.LayoutParams(-1, -2)
        tlp.setMargins(0, Ui.dp(this, 12), 0, 0)
        title.layoutParams = tlp
        box.addView(title)

        val plot = detailInfo?.plot?.ifEmpty { series.plot } ?: series.plot
        if (plot.isNotEmpty()) {
            val p = TextView(this)
            p.text = plot
            p.setTextColor(0xFFB3B3B3.toInt())
            p.textSize = 15f
            p.maxLines = 4
            p.ellipsize = android.text.TextUtils.TruncateAt.END
            val plp = LinearLayout.LayoutParams(-1, -2)
            plp.setMargins(0, Ui.dp(this, 4), 0, Ui.dp(this, 8))
            p.layoutParams = plp
            box.addView(p)
        }

        val info = detailInfo
        when {
            detailLoading -> countLine(box, "Loading seasons & episodes…")
            detailError != null -> {
                countLine(box, detailError!!)
                val retry = headerButton("⟳ Try again") { openSeries(series) }
                box.addView(retry)
            }
            info == null || info.seasons.isEmpty() -> countLine(box, "No episodes listed for this series.")
            else -> {
                // Season chips.
                val chipRow = LinearLayout(this)
                chipRow.orientation = LinearLayout.HORIZONTAL
                for (season in info.seasons) {
                    chipRow.addView(chip("Season ${season.number}", selected = detailSeason == season.number) {
                        detailSeason = season.number
                        rebuild()
                    })
                }
                box.addView(chipRow)
                val season = info.seasons.firstOrNull { it.number == detailSeason } ?: info.seasons.first()
                val resume = prefs.iptvResumeList()
                for (ep in season.episodes) {
                    box.addView(episodeRow(series, ep, IptvLogic.resumeFor(resume, episodeResumeKey(series, ep))))
                }
            }
        }
        // Favorite toggle for the series itself.
        val fav = IptvLogic.favOf(series)
        val favBtn = headerButton(
            if (IptvLogic.isFavorite(prefs.iptvFavoriteList(), fav.key)) "★ Favorited — hold to manage" else "☆ Add to favorites"
        ) { toggleFavorite(fav) }
        val flp = LinearLayout.LayoutParams(-2, -2)
        flp.setMargins(0, Ui.dp(this, 14), 0, 0)
        favBtn.layoutParams = flp
        box.addView(favBtn)
    }

    private fun episodeResumeKey(series: IptvLogic.SeriesItem, ep: IptvLogic.Episode): String =
        IptvLogic.resumeKey(IptvLogic.KIND_EPISODE, IptvLogic.SRC_XTREAM, ep.id)

    private fun episodeRow(
        series: IptvLogic.SeriesItem,
        ep: IptvLogic.Episode,
        resume: IptvLogic.ResumeEntry?
    ): View {
        val row = TextView(this)
        val dur = if (ep.durationSecs > 0) " · ${ep.durationSecs / 60} min" else ""
        val res = if (resume != null) {
            " · ▶ resume at ${formatClock(resume.posMs)}"
        } else ""
        row.text = "E${ep.num}  ${ep.title.ifEmpty { "Episode ${ep.num}" }}$dur$res"
        row.setTextColor(0xFFFFFFFF.toInt())
        row.textSize = 17f
        row.isFocusable = true
        row.isClickable = true
        row.setPadding(Ui.dp(this, 16), Ui.dp(this, 11), Ui.dp(this, 16), Ui.dp(this, 11))
        row.background = Ui.tileBackground(accent, false, 10, this)
        row.setOnFocusChangeListener { v, hasFocus ->
            v.background = Ui.tileBackground(accent, hasFocus, 10, this)
        }
        row.setOnClickListener { playEpisode(series, ep) }
        row.setOnLongClickListener {
            episodeMenu(series, ep, resume)
            true
        }
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.setMargins(0, Ui.dp(this, 4), 0, Ui.dp(this, 4))
        row.layoutParams = lp
        return row
    }

    // ------------------------------------------------------------ card data + grid

    private data class CardData(
        val tag: String,
        val image: String,
        val title: String,
        val meta: String,
        val line2: String?,
        val glyph: String,
        val progress: Float,
        val onClick: () -> Unit,
        val onLong: () -> Unit
    )

    private fun liveCardData(ch: IptvLogic.LiveChannel, siblings: List<IptvLogic.LiveChannel>): CardData {
        val index = IptvClient.epgIndex(this)
        val (now, _) = IptvEpg.nowNext(IptvLogic.epgFor(index, ch), System.currentTimeMillis())
        return CardData(
            tag = ch.key,
            image = ch.icon,
            title = ch.name,
            meta = ch.categoryName,
            line2 = now?.title,
            glyph = "📺",
            progress = if (now != null) IptvEpg.progress(now, System.currentTimeMillis()) else -1f,
            onClick = { playLive(ch, siblings) },
            onLong = { channelMenu(ch, siblings) }
        )
    }

    private fun vodCardData(vod: IptvLogic.VodItem): CardData {
        val resume = IptvLogic.resumeFor(prefs.iptvResumeList(), vod.key)
        return CardData(
            tag = vod.key,
            image = vod.icon,
            title = vod.name,
            meta = if (vod.rating.isNotEmpty()) "★ ${vod.rating}" else vod.categoryName,
            line2 = if (resume != null) "▶ Resume at ${formatClock(resume.posMs)}" else null,
            glyph = "🎬",
            progress = resume?.progress ?: -1f,
            onClick = { playVod(vod) },
            onLong = { vodMenu(vod, resume) }
        )
    }

    private fun seriesCardData(s: IptvLogic.SeriesItem): CardData = CardData(
        tag = s.key,
        image = s.cover,
        title = s.name,
        meta = if (s.rating.isNotEmpty()) "★ ${s.rating}" else s.categoryName,
        line2 = null,
        glyph = "📚",
        progress = -1f,
        onClick = { openSeries(s) },
        onLong = { seriesMenu(s) }
    )

    private fun favCardData(fav: IptvLogic.FavEntry, liveFavs: List<IptvLogic.FavEntry>): CardData =
        when (fav.kind) {
            IptvLogic.KIND_LIVE -> CardData(
                tag = fav.key, image = fav.icon, title = fav.name, meta = "Live", line2 = null,
                glyph = "📺", progress = -1f,
                onClick = {
                    val items = liveFavs.map { playItemOf(it) }
                    PlayQueue.set(items, liveFavs.indexOf(fav).coerceAtLeast(0))
                    startActivity(PlayerActivity.intent(this, playItemOf(fav)))
                },
                onLong = { favMenu(fav) }
            )
            IptvLogic.KIND_SERIES -> CardData(
                tag = fav.key, image = fav.icon, title = fav.name, meta = "Series", line2 = null,
                glyph = "📚", progress = -1f,
                onClick = {
                    // v2.4: resolve the series from the store on a
                    // worker thread; a miss opens a minimal detail
                    // built from the favorite itself (as before).
                    thread(name = "mizan-iptv-fav-series") {
                        val s = IptvClient.findSeriesBlocking(this, fav.id.toLongOrNull() ?: -1L)
                        runOnUiThread {
                            if (isFinishing) return@runOnUiThread
                            openSeries(
                                s ?: IptvLogic.SeriesItem(
                                    fav.id.toLongOrNull() ?: 0L, fav.name, fav.icon,
                                    fav.catKey.substringAfterLast(':'), "", "", ""
                                )
                            )
                            rebuild()
                        }
                    }
                },
                onLong = { favMenu(fav) }
            )
            else -> CardData(
                tag = fav.key, image = fav.icon, title = fav.name, meta = "Movie", line2 = null,
                glyph = "🎬", progress = -1f,
                onClick = {
                    PlayQueue.set(emptyList(), 0)
                    startActivity(PlayerActivity.intent(this, playItemOf(fav)))
                },
                onLong = { favMenu(fav) }
            )
        }

    private fun grid(box: LinearLayout, cards: List<CardData>, wide: Boolean) {
        val cols = IptvCards.columns(this, prefs, wide)
        var i = 0
        while (i < cards.size) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            for (data in cards.subList(i, minOf(i + cols, cards.size))) {
                val card = IptvCards.build(
                    activity = this,
                    prefs = prefs,
                    accent = accent,
                    imageUrl = data.image,
                    title = data.title,
                    meta = data.meta,
                    line2 = data.line2,
                    glyph = data.glyph,
                    wide = wide,
                    tag = data.tag,
                    progress = data.progress,
                    onFocus = { focusedKey = data.tag },
                    onClick = data.onClick,
                    onLong = data.onLong
                )
                if (firstCard == null) firstCard = (card as LinearLayout).getChildAt(0)
                row.addView(card)
            }
            box.addView(row)
            i += cols
        }
    }

    // ------------------------------------------------------------ playback

    private fun playItemOf(ch: IptvLogic.LiveChannel): PlayItem = PlayItem(
        kind = IptvLogic.KIND_LIVE, source = ch.source, id = ch.id.toString(),
        title = ch.name, icon = ch.icon, meta = ch.categoryName,
        catKey = ch.catKey, directUrl = ch.directUrl,
        tvArchive = ch.tvArchive, epgId = ch.epgId
    )

    private fun playItemOf(fav: IptvLogic.FavEntry): PlayItem = PlayItem(
        kind = fav.kind, source = fav.source, id = fav.id,
        title = fav.name, icon = fav.icon, ext = fav.ext,
        catKey = fav.catKey, directUrl = fav.directUrl
    )

    private fun playLive(ch: IptvLogic.LiveChannel, siblings: List<IptvLogic.LiveChannel>) {
        val items = siblings.map { playItemOf(it) }
        PlayQueue.set(items, siblings.indexOf(ch).coerceAtLeast(0))
        startActivity(PlayerActivity.intent(this, playItemOf(ch)))
    }

    private fun playVod(vod: IptvLogic.VodItem) {
        PlayQueue.set(emptyList(), 0)
        startActivity(
            PlayerActivity.intent(
                this,
                PlayItem(
                    kind = IptvLogic.KIND_VOD, source = IptvLogic.SRC_XTREAM,
                    id = vod.id.toString(), title = vod.name, icon = vod.icon,
                    meta = vod.categoryName, ext = vod.ext, catKey = vod.catKey
                )
            )
        )
    }

    private fun playEpisode(series: IptvLogic.SeriesItem, ep: IptvLogic.Episode) {
        PlayQueue.set(emptyList(), 0)
        startActivity(
            PlayerActivity.intent(
                this,
                PlayItem(
                    kind = IptvLogic.KIND_EPISODE, source = IptvLogic.SRC_XTREAM,
                    id = ep.id, title = "${series.name} · S${ep.season} E${ep.num}",
                    icon = series.cover, meta = ep.title, ext = ep.ext,
                    catKey = series.catKey, seriesId = series.id
                )
            )
        )
    }

    /** The catch-up item for a channel right now, or null (mirrors PlayerActivity's rule). */
    private fun catchUpItemFor(ch: IptvLogic.LiveChannel): PlayItem? {
        if (!ch.hasArchive) return null
        val programmes = IptvLogic.epgFor(IptvClient.epgIndex(this), ch)
        val (now, _) = IptvEpg.nowNext(programmes, System.currentTimeMillis())
        if (now == null) return null
        val durationMin = ((now.stopMs - now.startMs + 59_999L) / 60_000L).toInt().coerceAtLeast(1)
        return playItemOf(ch).copy(
            kind = IptvClient.KIND_SHIFT,
            title = ch.name + " — from the start",
            meta = now.title,
            shiftStartMs = now.startMs,
            shiftDurationMin = durationMin
        )
    }

    // ------------------------------------------------------------ menus

    private fun menu(title: String, labels: List<String>, actions: List<() -> Unit>) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(labels.toTypedArray()) { _, which -> actions[which].invoke() }
            .show()
    }

    private fun toggleFavorite(entry: IptvLogic.FavEntry) {
        val was = IptvLogic.isFavorite(prefs.iptvFavoriteList(), entry.key)
        prefs.setIptvFavoriteList(IptvLogic.toggleFavorite(prefs.iptvFavoriteList(), entry))
        Toast.makeText(
            this,
            if (was) "Removed from favorites" else "Added to favorites",
            Toast.LENGTH_SHORT
        ).show()
        rebuild()
    }

    private fun favLabel(entry: IptvLogic.FavEntry): String =
        if (IptvLogic.isFavorite(prefs.iptvFavoriteList(), entry.key)) {
            "★ Remove from favorites"
        } else {
            "☆ Add to favorites"
        }

    private fun channelMenu(ch: IptvLogic.LiveChannel, siblings: List<IptvLogic.LiveChannel>) {
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        labels.add("▶ Play")
        actions.add { playLive(ch, siblings) }
        val shift = catchUpItemFor(ch)
        if (shift != null) {
            labels.add("⏮ Watch from start")
            actions.add {
                PlayQueue.set(emptyList(), 0)
                startActivity(PlayerActivity.intent(this, shift))
            }
        }
        labels.add(favLabel(IptvLogic.favOf(ch)))
        actions.add { toggleFavorite(IptvLogic.favOf(ch)) }
        menu(ch.name, labels, actions)
    }

    private fun vodMenu(vod: IptvLogic.VodItem, resume: IptvLogic.ResumeEntry?) {
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        labels.add(if (resume != null) "▶ Play (resume at ${formatClock(resume.posMs)})" else "▶ Play")
        actions.add { playVod(vod) }
        if (resume != null) {
            labels.add("↺ Play from the beginning")
            actions.add {
                prefs.setIptvResumeList(IptvLogic.withoutResume(prefs.iptvResumeList(), vod.key))
                playVod(vod)
            }
        }
        labels.add(favLabel(IptvLogic.favOf(vod)))
        actions.add { toggleFavorite(IptvLogic.favOf(vod)) }
        menu(vod.name, labels, actions)
    }

    private fun seriesMenu(s: IptvLogic.SeriesItem) {
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        labels.add("Open seasons & episodes")
        actions.add { openSeries(s) }
        labels.add(favLabel(IptvLogic.favOf(s)))
        actions.add { toggleFavorite(IptvLogic.favOf(s)) }
        menu(s.name, labels, actions)
    }

    private fun episodeMenu(
        series: IptvLogic.SeriesItem,
        ep: IptvLogic.Episode,
        resume: IptvLogic.ResumeEntry?
    ) {
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        labels.add(if (resume != null) "▶ Play (resume at ${formatClock(resume.posMs)})" else "▶ Play")
        actions.add { playEpisode(series, ep) }
        if (resume != null) {
            labels.add("↺ Play from the beginning")
            actions.add {
                prefs.setIptvResumeList(
                    IptvLogic.withoutResume(prefs.iptvResumeList(), episodeResumeKey(series, ep))
                )
                playEpisode(series, ep)
            }
        }
        menu("S${ep.season} E${ep.num} — ${ep.title}", labels, actions)
    }

    private fun favMenu(fav: IptvLogic.FavEntry) {
        menu(fav.name, listOf("★ Remove from favorites"), listOf { toggleFavorite(fav) })
    }

    /** Hold-OK on a category chip: lock it, or unlock it through the pattern gate. */
    private fun categoryMenu(cat: IptvLogic.Category) {
        if (prefs.arrowPin.isEmpty()) {
            Toast.makeText(
                this,
                "Set an arrow pattern first (Settings › Lock & profiles) to lock categories",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val stored = IptvLocks.decodeKeys(prefs.iptvLockedCats)
        val lockedNow = categoryLocked(cat)
        if (!lockedNow) {
            menu(cat.name, listOf("🔒 Lock category"), listOf {
                prefs.iptvLockedCats = IptvLocks.encodeKeys(stored + cat.key)
                val stamps = IptvLocks.decodeStamps(prefs.iptvCatUnlockStamps) - cat.key
                prefs.iptvCatUnlockStamps = IptvLocks.encodeStamps(stamps)
                Toast.makeText(this, "“${cat.name}” locked", Toast.LENGTH_SHORT).show()
                rebuild()
            })
        } else {
            menu(cat.name, listOf("🔓 Unlock category"), listOf {
                PatternGate.run(this, prefs, accent, "Unlock “${cat.name}”") {
                    // An explicit lock is lifted for good; an
                    // adult-name lock re-arms when the stamp lapses
                    // (the name rule) — both go through the stamp.
                    prefs.iptvLockedCats = IptvLocks.encodeKeys(stored - cat.key)
                    val stamps = IptvLocks.decodeStamps(prefs.iptvCatUnlockStamps) +
                        (cat.key to System.currentTimeMillis())
                    prefs.iptvCatUnlockStamps = IptvLocks.encodeStamps(stamps)
                    Toast.makeText(this, "“${cat.name}” unlocked", Toast.LENGTH_SHORT).show()
                    rebuild()
                }
            })
        }
    }

    // ------------------------------------------------------------ small helpers

    private fun chip(label: String, selected: Boolean, onClick: () -> Unit): TextView {
        val tv = TextView(this)
        tv.text = label
        tv.setTextColor(0xFFFFFFFF.toInt())
        tv.textSize = 15f
        if (selected) tv.setTypeface(tv.typeface, Typeface.BOLD)
        tv.gravity = Gravity.CENTER
        tv.isFocusable = true
        tv.isClickable = true
        tv.setPadding(Ui.dp(this, 14), Ui.dp(this, 7), Ui.dp(this, 14), Ui.dp(this, 7))
        tv.background = Ui.tileBackground(
            if (selected) accent else 0xFF3A3A3A.toInt(), false, Design.RADIUS_CHIP, this
        )
        Ui.focusLift(tv, accent, Design.RADIUS_CHIP)
        tv.setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(-2, -2)
        lp.setMargins(0, 0, Ui.dp(this, 8), Ui.dp(this, 6))
        tv.layoutParams = lp
        return tv
    }

    private fun headerButton(text: String, onClick: () -> Unit): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.setTextColor(0xFFFFFFFF.toInt())
        tv.textSize = 16f
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

    private fun countLine(box: LinearLayout, text: String): TextView {
        val count = TextView(this)
        count.text = text
        count.setTextColor(0xFFB3B3B3.toInt())
        count.textSize = 15f
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.setMargins(0, Ui.dp(this, 4), 0, Ui.dp(this, 10))
        count.layoutParams = lp
        box.addView(count)
        return count
    }

    /**
     * The header status line (ingest progress / "Updated …") —
     * registered so ingest progress ticks can rewrite its text
     * (v2.5). Null whenever it isn't on screen; cleared on every
     * rebuild.
     */
    private var progressLineView: TextView? = null

    /**
     * Ingest progress callback (v2.5): while the ingest runs, a
     * tick updates ONLY the status line's text — the v2.4 shape
     * rebuilt the whole browser page on every tick (throttled to
     * one per ~2 s at the source, but the page is a large
     * catalogue grid, so a minutes-long ingest still meant
     * composing thousands of cards again and again on the main
     * thread). The completion callback — isIngesting() is false
     * by the time it runs — does the ONE full rebuild that lands
     * the new catalogue.
     */
    private fun onIptvProgressTick() {
        if (isFinishing || isDestroyed) return
        if (IptvClient.isIngesting()) {
            progressLineView?.text = IptvClient.ingestProgressText()
        } else {
            rebuild()
        }
    }

    private fun sectionTitle(box: LinearLayout, text: String) {
        val t = TextView(this)
        t.text = text
        t.setTextColor(0xFFFFFFFF.toInt())
        t.textSize = 19f
        t.setTypeface(t.typeface, Typeface.BOLD)
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.setMargins(0, Ui.dp(this, 12), 0, Ui.dp(this, 4))
        t.layoutParams = lp
        box.addView(t)
    }

    private fun findFrameByTag(root: View, tag: String): View? {
        if (root.tag == tag) return root
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) {
                findFrameByTag(root.getChildAt(i), tag)?.let { return it }
            }
        }
        return null
    }

    private fun formatClock(ms: Long): String {
        val totalSecs = (ms / 1000L).coerceAtLeast(0L)
        val h = totalSecs / 3600L
        val m = (totalSecs % 3600L) / 60L
        val s = totalSecs % 60L
        return if (h > 0) {
            String.format("%d:%02d:%02d", h, m, s)
        } else {
            String.format("%d:%02d", m, s)
        }
    }
}
