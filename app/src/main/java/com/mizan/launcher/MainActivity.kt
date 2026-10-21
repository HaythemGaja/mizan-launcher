package com.mizan.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.mizan.launcher.core.Folder
import com.mizan.launcher.core.FolderEntry
import com.mizan.launcher.core.FolderGate
import com.mizan.launcher.core.FolderLogic
import com.mizan.launcher.core.FolderTree
import com.mizan.launcher.core.GuestGate
import com.mizan.launcher.core.MoveContainer
import com.mizan.launcher.core.MoveSessionMachine
import com.mizan.launcher.core.LayoutData
import com.mizan.launcher.core.NowPlayingLogic
import com.mizan.launcher.core.RecentsStore
import com.mizan.launcher.core.RotationLogic
import com.mizan.launcher.core.RowOrder
import com.mizan.launcher.core.Themes
import com.mizan.launcher.core.TileLayout
import com.mizan.launcher.core.WeatherLogic
import com.mizan.launcher.core.WifiLabel
import com.mizan.launcher.data.AccentUtil
import com.mizan.launcher.data.AppEntry
import com.mizan.launcher.data.AppLoader
import com.mizan.launcher.data.BingFetcher
import com.mizan.launcher.data.NowPlayingData
import com.mizan.launcher.data.Prefs
import com.mizan.launcher.data.TvData
import com.mizan.launcher.data.WallpaperRotator
import com.mizan.launcher.data.WatchItem
import com.mizan.launcher.data.Weather
import com.mizan.launcher.data.WeatherArt
import com.mizan.launcher.ui.ArrowPinDialog
import com.mizan.launcher.ui.ForecastActivity
import com.mizan.launcher.ui.SearchActivity
import com.mizan.launcher.ui.SettingsActivity
import com.mizan.launcher.ui.Ui
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The home screen. One calm screen, Views only:
 * top bar (clock/date, weather, network, quick tiles) + rows in the
 * profile's chosen order (Favorites, Folders, Continue Watching, Inputs,
 * All Apps). Rebuilt on resume so Settings changes apply immediately.
 * Every optional row hides itself on any failure (fail-open).
 */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var layout: LayoutData
    private var apps: List<AppEntry> = emptyList()
    private var appsByPkg: Map<String, AppEntry> = emptyMap()
    private var accent: Int = 0
    private var textColour: Int = 0
    private var subColour: Int = 0

    private var clockView: TextView? = null
    private var dateView: TextView? = null
    private var networkView: TextView? = null
    private var firstTile: View? = null

    private val handler = Handler(Looper.getMainLooper())
    private val clockTick = object : Runnable {
        override fun run() {
            updateClock()
            // v1.4 housekeeping rides the tick (throttled inside):
            // wallpaper rotation / Bing day change / Now Playing changes.
            housekeeping()
            // One second ticks only when the seconds display is on.
            val interval = if (!prefs.safeMode && layout.statusBar.showSeconds) 1_000L else 20_000L
            handler.postDelayed(this, interval)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        rebuild()
    }

    override fun onResume() {
        super.onResume()
        if (::prefs.isInitialized) rebuild()
        // The watcher reads this to avoid a relaunch loop against us.
        try {
            prefs.sp.edit().putLong("own_foreground_at", System.currentTimeMillis()).apply()
        } catch (e: Exception) {
            // Cosmetic coordination only.
        }
        handler.removeCallbacks(clockTick)
        handler.post(clockTick)
        // Rotation/Bing are checked on resume as well as on the tick
        // (the brief's service-free mechanism — no timers anywhere).
        checkRotationAndBing()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(clockTick)
    }

    /**
     * Move mode owns the D-pad while a tile is grabbed (v1.3) — but
     * only for home-screen sessions; folder-overlay sessions get their
     * keys from the dialog's own listener (separate window).
     */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        // The remote's Search key opens App Search (v1.4), unless a
        // move-mode session owns the D-pad right now.
        if (moveSession == null &&
            event.action == android.view.KeyEvent.ACTION_DOWN &&
            event.keyCode == android.view.KeyEvent.KEYCODE_SEARCH
        ) {
            try { startActivity(Intent(this, SearchActivity::class.java)) } catch (e: Exception) {
                Prefs.noteError(this, "searchKey", e)
            }
            return true
        }
        val session = moveSession
        if (session != null && session.container != MoveContainer.FOLDER &&
            event.action == android.view.KeyEvent.ACTION_DOWN
        ) {
            return handleMoveKey(event.keyCode)
        }
        return super.dispatchKeyEvent(event)
    }

    // ------------------------------------------------------------------ UI

    private fun rebuild() {
        try {
            layout = prefs.layout
            apps = AppCache.get(this)
            appsByPkg = apps.associateBy { it.pkg }
            resolveColours()
            setContentView(buildRoot())
            refreshWeather()
            firstTile?.post { if (currentFocus == null) firstTile?.requestFocus() }
        } catch (e: Exception) {
            Prefs.noteError(this, "rebuild", e)
        }
    }

    /** Theme colours for this profile, with Dynamic accent and Safe mode applied. */
    private fun resolveColours() {
        if (prefs.safeMode) {
            val p = Themes.preset("apple_black")
            accent = p.accent; textColour = p.text; subColour = p.subText
            return
        }
        val preset = Themes.preset(prefs.themeId)
        textColour = preset.text
        subColour = preset.subText
        accent = if (preset.dynamic) {
            if (prefs.accentOverride == 0) AccentUtil.recompute(this, prefs)
            if (prefs.accentOverride != 0) prefs.accentOverride else preset.accent
        } else preset.accent
    }

    private fun buildRoot(): View {
        val root = FrameLayout(this)

        // Background: drawn once. Image wallpaper wins; otherwise a gradient.
        val preset = Themes.preset(prefs.themeId)
        var top = preset.bgTop
        var bottom = preset.bgBottom
        var imageShown = false
        if (!prefs.safeMode && prefs.wallpaperKind != "weather") {
            prefs.weatherBgStatus = "off"
        }
        if (!prefs.safeMode) {
            when (prefs.wallpaperKind) {
                // Weather background (v1.2): drawn from the cached
                // snapshot's palette + motif. No data (offline / never
                // fetched) → leave the theme gradient in place below
                // (fail-open) and record why in diagnostics.
                "weather" -> {
                    val snap = Weather.decodeSnapshot(prefs.weatherData)
                    val cond = snap?.let {
                        WeatherLogic.chooseCondition(it.code, it.isDay, it.dailyCodes)
                    }
                    if (cond != null) {
                        val palette = WeatherLogic.paletteFor(cond)
                        val dm = resources.displayMetrics
                        val bmp = WeatherArt.render(palette, dm.widthPixels, dm.heightPixels)
                        val iv = ImageView(this)
                        iv.setImageBitmap(bmp)
                        iv.scaleType = ImageView.ScaleType.CENTER_CROP
                        root.addView(iv, FrameLayout.LayoutParams(-1, -1))
                        prefs.weatherBgStatus =
                            "active: ${cond.name.lowercase()} (${palette.motif})"
                        imageShown = true
                    } else {
                        prefs.weatherBgStatus = "fallback: no weather data (theme gradient shown)"
                    }
                }
                "gradient" -> Themes.gradient(prefs.wallpaperValue).let { top = it.top; bottom = it.bottom }
                "solid" -> (prefs.wallpaperValue.toIntOrNull() ?: top).let { top = it; bottom = it }
                "image", "reddit", "bing" -> {
                    // "image" stores a content URI; "reddit" (v1.1)
                    // and "bing" (v1.4) store a downloaded file's
                    // absolute path.
                    val bmp = if (prefs.wallpaperKind == "reddit" || prefs.wallpaperKind == "bing") {
                        AccentUtil.loadImageFile(prefs.wallpaperValue)
                    } else {
                        AccentUtil.loadImage(this, prefs.wallpaperValue)
                    }
                    if (bmp != null) {
                        val iv = ImageView(this)
                        iv.setImageBitmap(bmp)
                        iv.scaleType = ImageView.ScaleType.CENTER_CROP
                        root.addView(iv, FrameLayout.LayoutParams(-1, -1))
                        imageShown = true
                    }
                }
            }
        }
        if (!imageShown) {
            val grad = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom))
            root.background = grad
        }

        // v1.6: ONE scrim over every background kind (photo, gradient,
        // weather art) — the unified bottom-weighted gradient from
        // core/Design, replacing the per-kind flat scrims (0x40/0x66)
        // that made some wallpapers murky and others unreadable.
        val scrim = View(this)
        scrim.background = Ui.scrimDrawable()
        root.addView(scrim, FrameLayout.LayoutParams(-1, -1))

        val scroll = ScrollView(this)
        scroll.isFillViewport = true
        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(
            Ui.dp(this, com.mizan.launcher.core.Design.CONTENT_MARGIN_DP),
            Ui.dp(this, com.mizan.launcher.core.Design.SPACE_L),
            Ui.dp(this, com.mizan.launcher.core.Design.CONTENT_MARGIN_DP),
            Ui.dp(this, 40)
        )
        scroll.addView(content)
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))

        // Status bar hidden (clean Apple-TV style): the bar's Settings
        // tile goes with it, which would trap the user out of Settings.
        // Keep one dim corner gear as the escape hatch — it is part of
        // the frame, not the bar, and it's the only trace left.
        val barCfg = if (prefs.safeMode) {
            com.mizan.launcher.core.StatusBarConfig()
        } else {
            layout.statusBar
        }
        // v1.3: the corner gear also appears when the bar is shown but
        // its Settings tile was switched off (per-tile toggles) — there
        // must ALWAYS be a visible way back into Settings.
        if (!barCfg.showBar || !barCfg.showTileSettings) {
            val gear = quickTile("⚙") { openSettings() }
            gear.alpha = 0.55f
            val glp = FrameLayout.LayoutParams(-2, -2)
            glp.gravity = Gravity.TOP or Gravity.END
            glp.setMargins(0, Ui.dp(this, 18), Ui.dp(this, 40), 0)
            gear.layoutParams = glp
            root.addView(gear)
        }
        // Guest pill when the bar is fully hidden (v1.4): the pill that
        // normally lives in the bar (see buildTopBar) moves to the
        // top-LEFT corner — the top-right belongs to the corner gear —
        // so Guest mode is always visible somewhere on screen.
        if (!barCfg.showBar && guestActive()) {
            val pill = guestPill()
            val plp = FrameLayout.LayoutParams(-2, -2)
            plp.gravity = Gravity.TOP or Gravity.START
            plp.setMargins(Ui.dp(this, 40), Ui.dp(this, 18), 0, 0)
            pill.layoutParams = plp
            root.addView(pill)
        }

        // v1.7: the iPad home style renders an alternate root from the
        // SAME wallpaper/scrim/corner-gear/corner-pill frame above —
        // presentation only; every datum, menu, lock, gate and move
        // session is shared with the TV style. Safe mode forces TV.
        if (isIpadStyle()) {
            // The floating dock overlays the bottom of the scroll
            // content; pad the content so the grid's last row can
            // scroll fully clear of it.
            content.setPadding(
                Ui.dp(this, com.mizan.launcher.core.Design.CONTENT_MARGIN_DP),
                Ui.dp(this, com.mizan.launcher.core.Design.SPACE_L),
                Ui.dp(this, com.mizan.launcher.core.Design.CONTENT_MARGIN_DP),
                Ui.dp(this, 200)
            )
            buildIpadHome(root, content)
            homeRoot = root
            if (moveSession != null && moveSession?.container != MoveContainer.FOLDER) {
                attachMoveHint(root)
            }
            return root
        }

        content.addView(buildTopBar())

        // v1.4: stored orders from older versions lack the new rows;
        // RowOrder.normalize slots them at their canonical positions.
        val rowKeys = if (prefs.safeMode) listOf("favorites", "apps") else RowOrder.normalize(layout.rowOrder)
        for (key in rowKeys) {
            if (!prefs.safeMode && !layout.isRowVisible(key)) continue
            val section = try {
                when (key) {
                    "favorites" -> buildFavoritesSection()
                    "recents" -> if (prefs.safeMode) null else buildRecentsSection()
                    "folders" -> if (prefs.safeMode) null else buildFoldersSection()
                    "nowplaying" -> if (prefs.safeMode) null else buildNowPlayingSection()
                    "continue" -> if (prefs.safeMode) null else buildContinueSection()
                    "inputs" -> if (prefs.safeMode) null else buildInputsSection()
                    "apps" -> buildAppsSection()
                    else -> null
                }
            } catch (e: Exception) {
                Prefs.noteError(this, "row:$key", e)
                null
            }
            if (section != null) content.addView(section)
        }
        // Move mode's hint bar rides on every rebuild while a
        // home-screen session is active (folder sessions show the
        // grab-time toast instead — the dialog covers this window).
        homeRoot = root
        if (moveSession != null && moveSession?.container != MoveContainer.FOLDER) attachMoveHint(root)
        return root
    }

    // ---------------------------------------------------------------
    // v1.7 iPad home style — presentation + composition only. All
    // data, menus, locks, gates and move machinery are the TV style's.
    // ---------------------------------------------------------------

    /** The effective home style: safe mode always forces TV. */
    private fun homeStyleKey(): String =
        if (prefs.safeMode) com.mizan.launcher.core.HomeStyle.TV else prefs.homeStyle

    private fun isIpadStyle(): Boolean =
        com.mizan.launcher.core.HomeStyle.isIpad(homeStyleKey())

    /** The icon shape home tiles render with (iPad forces squircle). */
    private fun effectiveIconShape(): String =
        com.mizan.launcher.core.HomeStyle.effectiveIconShape(homeStyleKey(), prefs.iconShape)

    /**
     * The iPad home, top to bottom: slim strip, first-run hint,
     * widget rows (Continue Watching then Recently Used — fixed order
     * in this style), the main grid, and the floating dock pinned to
     * the root's bottom. Favorites live in the dock here (not a row);
     * HDMI inputs join the grid (not a row); folders are grid tiles.
     */
    private fun buildIpadHome(root: FrameLayout, content: LinearLayout) {
        content.addView(buildIpadStrip())
        buildHomeHint()?.let { content.addView(it) }
        if (layout.isRowVisible("continue")) {
            try {
                buildContinueSection()?.let { content.addView(it) }
            } catch (e: Exception) {
                Prefs.noteError(this, "row:continue", e)
            }
        }
        if (layout.isRowVisible("recents")) {
            try {
                buildRecentsSection()?.let { content.addView(it) }
            } catch (e: Exception) {
                Prefs.noteError(this, "row:recents", e)
            }
        }
        content.addView(buildIpadGrid())
        root.addView(buildIpadDock())
        updateClock()
    }

    /**
     * The slim iPadOS-like top strip: date + clock on the left,
     * weather / network / Bluetooth / Guest on the right — every
     * element gated by the SAME StatusBarConfig switches the TV hero
     * honours, so hiding the clock in Settings hides it here too.
     * With the whole bar hidden the strip collapses to nothing (the
     * corner gear + corner Guest pill in the frame still apply).
     */
    private fun buildIpadStrip(): View {
        val cfg = layout.statusBar
        val wrap = LinearLayout(this)
        wrap.orientation = LinearLayout.VERTICAL
        if (!cfg.showBar) return wrap
        val scale = cfg.textScale()
        val strip = LinearLayout(this)
        strip.orientation = LinearLayout.HORIZONTAL
        strip.gravity = Gravity.CENTER_VERTICAL
        if (cfg.background != "transparent") {
            val bg = GradientDrawable()
            bg.cornerRadius = Ui.dp(this, com.mizan.launcher.core.Design.RADIUS_PANEL).toFloat()
            bg.setColor(com.mizan.launcher.core.Design.PANEL_FILL)
            strip.background = bg
            strip.setPadding(
                Ui.dp(this, com.mizan.launcher.core.Design.SPACE_M),
                Ui.dp(this, com.mizan.launcher.core.Design.SPACE_S),
                Ui.dp(this, com.mizan.launcher.core.Design.SPACE_M),
                Ui.dp(this, com.mizan.launcher.core.Design.SPACE_S)
            )
        }

        val left = LinearLayout(this)
        left.orientation = LinearLayout.HORIZONTAL
        left.gravity = Gravity.CENTER_VERTICAL
        strip.addView(left, LinearLayout.LayoutParams(0, -2, 1f))

        dateView = null
        clockView = null
        if (cfg.showDate) {
            val d = TextView(this)
            d.setTextColor(subColour)
            d.textSize = com.mizan.launcher.core.Design.IPAD_STRIP_SP * scale
            dateView = d
            left.addView(d)
        }
        if (cfg.showClock) {
            val c = TextView(this)
            c.setTextColor(textColour)
            c.textSize = com.mizan.launcher.core.Design.IPAD_STRIP_SP * scale
            c.typeface = android.graphics.Typeface.DEFAULT_BOLD
            if (cfg.showDate) {
                val lp = LinearLayout.LayoutParams(-2, -2)
                lp.setMargins(Ui.dp(this, 12), 0, 0, 0)
                c.layoutParams = lp
            }
            clockView = c
            left.addView(c)
        }

        val right = LinearLayout(this)
        right.orientation = LinearLayout.HORIZONTAL
        right.gravity = Gravity.CENTER_VERTICAL
        if (cfg.showWeather) {
            val w = TextView(this)
            w.tag = "weather"
            w.setTextColor(textColour)
            w.textSize = com.mizan.launcher.core.Design.IPAD_STRIP_SP * scale
            w.isFocusable = true
            w.isClickable = true
            w.background = Ui.tileBackground(accent, false, com.mizan.launcher.core.Design.RADIUS_CHIP, this)
            w.setPadding(Ui.dp(this, 10), Ui.dp(this, 4), Ui.dp(this, 10), Ui.dp(this, 4))
            Ui.focusLift(w, accent, com.mizan.launcher.core.Design.RADIUS_CHIP)
            w.setOnClickListener {
                startActivity(Intent(this, ForecastActivity::class.java))
            }
            val lp = LinearLayout.LayoutParams(-2, -2)
            lp.setMargins(0, 0, Ui.dp(this, 12), 0)
            w.layoutParams = lp
            right.addView(w)
        }
        networkView = null
        if (cfg.showNetwork) {
            val n = TextView(this)
            n.setTextColor(subColour)
            n.textSize = com.mizan.launcher.core.Design.IPAD_STRIP_SP * scale
            networkView = n
            right.addView(n)
        }
        if (cfg.showBluetooth) {
            val btText = bluetoothLabel()
            if (btText.isNotEmpty()) {
                val bt = TextView(this)
                bt.text = btText
                bt.setTextColor(subColour)
                bt.textSize = com.mizan.launcher.core.Design.IPAD_STRIP_SP * scale
                val lp = LinearLayout.LayoutParams(-2, -2)
                lp.setMargins(Ui.dp(this, 12), 0, 0, 0)
                bt.layoutParams = lp
                right.addView(bt)
            }
        }
        if (guestActive()) {
            val pill = guestPill()
            val lp = LinearLayout.LayoutParams(-2, -2)
            lp.setMargins(Ui.dp(this, 12), 0, 0, 0)
            pill.layoutParams = lp
            right.addView(pill)
        }
        strip.addView(right)
        wrap.addView(strip)
        return wrap
    }

    /**
     * The iPad main grid: centered rows of boosted cells in three
     * composed segments (apps, root folders, inputs — see
     * [com.mizan.launcher.core.HomeStyle.composeGrid]). Every tile
     * carries its label under the icon, always.
     */
    private fun buildIpadGrid(): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        val glp = LinearLayout.LayoutParams(-1, -2)
        glp.setMargins(0, Ui.dp(this, com.mizan.launcher.core.Design.HERO_TO_ROWS_GAP_DP), 0, 0)
        box.layoutParams = glp

        val inputs = try { TvData.listInputs(this) } catch (e: Exception) { emptyList() }
        DiagCache.inputsCount = inputs.size
        val inputsById = inputs.associateBy { it.id }
        val comp = com.mizan.launcher.core.HomeStyle.composeGrid(
            installedOrder = orderedApps(apps).map { it.pkg },
            hidden = layout.hidden.toSet(),
            folders = layout.folders,
            inputsEnabled = layout.isRowVisible("inputs"),
            inputIds = inputs.map { it.id }
        )
        val cells = ArrayList<View>()
        for (pkg in comp.appPkgs) {
            appsByPkg[pkg]?.let { cells.add(appTile(it, Source.AllApps, ipad = true, large = true)) }
        }
        for (fid in comp.folderIds) {
            com.mizan.launcher.core.FolderTree.byId(layout.folders, fid)?.let {
                cells.add(folderTile(it, ipad = true, large = true, movable = true))
            }
        }
        for (iid in comp.inputIds) {
            inputsById[iid]?.let { cells.add(inputTile(it, ipad = true, large = true)) }
        }
        if (cells.isEmpty()) {
            val hint = TextView(this)
            hint.text = "No apps yet — open Settings to set up your home screen."
            hint.setTextColor(subColour)
            hint.textSize = 16f
            hint.gravity = Gravity.CENTER
            box.addView(hint)
            return box
        }
        for (chunk in cells.chunked(prefs.columns)) {
            val rowLayout = LinearLayout(this)
            rowLayout.orientation = LinearLayout.HORIZONTAL
            rowLayout.gravity = Gravity.CENTER
            for (cell in chunk) rowLayout.addView(cell)
            box.addView(rowLayout)
        }
        return box
    }

    /**
     * The floating dock: a translucent rounded panel pinned
     * bottom-center of the root — favorites (icons only), a divider,
     * then the fixed glyph buttons (see
     * [com.mizan.launcher.core.HomeStyle.composeDock]). Horizontally
     * scrollable when its contents overflow the screen.
     */
    private fun buildIpadDock(): View {
        val cfg = layout.statusBar
        val favEntries = layout.favorites
            .mapNotNull { appsByPkg[it] }
            .filter { it.pkg !in layout.hidden }
        val items = com.mizan.launcher.core.HomeStyle.composeDock(
            favorites = favEntries.map { it.pkg },
            showSearch = cfg.showTileSearch,
            showAllApps = cfg.showTileAllApps
        )
        val byPkg = favEntries.associateBy { it.pkg }
        val panel = LinearLayout(this)
        panel.orientation = LinearLayout.HORIZONTAL
        panel.gravity = Gravity.CENTER_VERTICAL
        val bg = GradientDrawable()
        bg.cornerRadius = Ui.dp(this, com.mizan.launcher.core.Design.DOCK_RADIUS_DP).toFloat()
        bg.setColor(com.mizan.launcher.core.Design.PANEL_FILL)
        panel.background = bg
        panel.elevation = Ui.dp(this, 8).toFloat()
        panel.setPadding(
            Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12)
        )
        for (item in items) {
            when (item.kind) {
                com.mizan.launcher.core.HomeStyle.DockKind.FAVORITE ->
                    byPkg[item.pkg]?.let {
                        panel.addView(appTile(it, Source.Favorites, ipad = true, ipadLabel = false))
                    }
                com.mizan.launcher.core.HomeStyle.DockKind.DIVIDER -> {
                    val m = currentTileMetrics()
                    val div = View(this)
                    div.setBackgroundColor(0x59FFFFFF)
                    val dlp = LinearLayout.LayoutParams(
                        Ui.dp(this, 1), m.tileHeightPx - Ui.dp(this, 36)
                    )
                    dlp.setMargins(Ui.dp(this, 8), 0, Ui.dp(this, 8), 0)
                    div.layoutParams = dlp
                    panel.addView(div)
                }
                com.mizan.launcher.core.HomeStyle.DockKind.SEARCH ->
                    panel.addView(dockButton("🔍") {
                        startActivity(Intent(this, SearchActivity::class.java))
                    })
                com.mizan.launcher.core.HomeStyle.DockKind.ALL_APPS ->
                    panel.addView(dockButton("▦") {
                        startActivity(Intent(this, com.mizan.launcher.ui.AllAppsActivity::class.java))
                    })
                com.mizan.launcher.core.HomeStyle.DockKind.PROFILE ->
                    panel.addView(dockButton("👤", onLongClick = {
                        showProfileSwitchMenu()
                        true
                    }) { showProfileSwitchMenu() })
                com.mizan.launcher.core.HomeStyle.DockKind.SETTINGS ->
                    panel.addView(dockButton("⚙") { openSettings() })
            }
        }
        val scroller = HorizontalScrollView(this)
        scroller.isHorizontalScrollBarEnabled = false
        scroller.addView(panel)
        val slp = FrameLayout.LayoutParams(-2, -2)
        slp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        slp.setMargins(Ui.dp(this, 24), 0, Ui.dp(this, 24), Ui.dp(this, 18))
        scroller.layoutParams = slp
        return scroller
    }

    /** One fixed dock button: a glyph cell the height of a dock tile. */
    private fun dockButton(
        glyph: String,
        onLongClick: (() -> Boolean)? = null,
        onClick: () -> Unit
    ): View {
        val m = currentTileMetrics()
        val b = TextView(this)
        b.text = glyph
        b.setTextColor(textColour)
        b.textSize = 30f
        b.gravity = Gravity.CENTER
        b.isFocusable = true
        b.isClickable = true
        val lp = LinearLayout.LayoutParams(Ui.dp(this, 68), m.tileHeightPx)
        lp.setMargins(Ui.dp(this, 2), 0, Ui.dp(this, 2), 0)
        b.layoutParams = lp
        b.background = Ui.tileBackground(accent, false, com.mizan.launcher.core.Design.RADIUS_PANEL, this)
        Ui.focusLift(b, accent, com.mizan.launcher.core.Design.RADIUS_PANEL)
        b.setOnClickListener { onClick() }
        if (onLongClick != null) b.setOnLongClickListener { onLongClick() }
        return b
    }

    /**
     * The always-on name under an iPad-style tile: single line,
     * ellipsized, with a soft shadow so it stays readable over any
     * wallpaper. Sits at the tile's bottom edge.
     */
    private fun ipadLabelView(name: String): TextView {
        val label = TextView(this)
        label.text = name
        label.setTextColor(textColour)
        label.textSize = com.mizan.launcher.core.Design.IPAD_LABEL_SP
        label.gravity = Gravity.CENTER
        label.maxLines = 1
        label.ellipsize = android.text.TextUtils.TruncateAt.END
        label.setShadowLayer(
            Ui.dp(this, 2).toFloat(), 0f, Ui.dp(this, 1).toFloat(), 0x99000000.toInt()
        )
        val llp = FrameLayout.LayoutParams(-1, -2)
        llp.gravity = Gravity.BOTTOM
        llp.setMargins(Ui.dp(this, 4), 0, Ui.dp(this, 4), Ui.dp(this, 5))
        label.layoutParams = llp
        return label
    }


    /** The current home root (for the move-mode hint bar). */
    private var homeRoot: FrameLayout? = null

    /**
     * Top bar + the not-default-home hint, wrapped in one vertical box.
     * The bar is driven by the profile's StatusBarConfig (v1.1): hidden
     * entirely for the clean Apple-TV style, or any mix of clock, date,
     * weather, network, Bluetooth and quick tiles. In Safe mode the
     * v1.0 default bar is used.
     */
    private fun buildTopBar(): View {
        val cfg = if (prefs.safeMode) {
            com.mizan.launcher.core.StatusBarConfig()
        } else {
            layout.statusBar
        }
        val scale = cfg.textScale()

        val wrap = LinearLayout(this)
        wrap.orientation = LinearLayout.VERTICAL

        if (cfg.showBar) {
            // v1.6 HERO zone: one composed block instead of a loose
            // strip of floating items. LEFT = the big clock + date with
            // a status line (weather, network, Bluetooth) beneath;
            // RIGHT = every quick tile + profile + Settings grouped in
            // ONE translucent rounded panel. The config toggles govern
            // exactly the same elements as the v1.1–v1.5 bar did.
            val hero = LinearLayout(this)
            hero.orientation = LinearLayout.HORIZONTAL
            hero.gravity = Gravity.TOP

            // Hero background follows the bar background config:
            // transparent (wallpaper + scrim show through), the
            // theme's top colour, or black — solid or translucent,
            // now as one rounded zone instead of a hard-edged strip.
            when (cfg.background) {
                com.mizan.launcher.core.StatusBarConfig.BG_THEME -> {
                    val alpha = com.mizan.launcher.core.StatusBarConfig.backgroundAlpha(cfg.opacity)
                    val bg = GradientDrawable()
                    bg.cornerRadius = Ui.dp(this, com.mizan.launcher.core.Design.RADIUS_PANEL).toFloat()
                    bg.setColor((alpha shl 24) or (Themes.preset(prefs.themeId).bgTop and 0x00FFFFFF))
                    hero.background = bg
                }
                com.mizan.launcher.core.StatusBarConfig.BG_BLACK -> {
                    val alpha = com.mizan.launcher.core.StatusBarConfig.backgroundAlpha(cfg.opacity)
                    val bg = GradientDrawable()
                    bg.cornerRadius = Ui.dp(this, com.mizan.launcher.core.Design.RADIUS_PANEL).toFloat()
                    bg.setColor(alpha shl 24)
                    hero.background = bg
                }
                else -> { /* transparent: no background */ }
            }
            if (cfg.background != com.mizan.launcher.core.StatusBarConfig.BG_TRANSPARENT) {
                hero.setPadding(
                    Ui.dp(this, com.mizan.launcher.core.Design.SPACE_M),
                    Ui.dp(this, 12),
                    Ui.dp(this, com.mizan.launcher.core.Design.SPACE_M),
                    Ui.dp(this, 12)
                )
            }

            // LEFT: clock + date (each independently toggleable),
            // then the status line, then the Guest pill.
            val left = LinearLayout(this)
            left.orientation = LinearLayout.VERTICAL
            clockView = null
            dateView = null
            if (cfg.showClock) {
                clockView = TextView(this).apply {
                    setTextColor(textColour)
                    textSize = com.mizan.launcher.core.Design.HERO_CLOCK_SP * scale
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                }
                left.addView(clockView)
            }
            if (cfg.showDate) {
                dateView = TextView(this).apply {
                    setTextColor(subColour)
                    textSize = com.mizan.launcher.core.Design.HERO_DATE_SP * scale
                }
                left.addView(dateView)
            }

            val statusLine = LinearLayout(this)
            statusLine.orientation = LinearLayout.HORIZONTAL
            statusLine.gravity = Gravity.CENTER_VERTICAL
            val slp = LinearLayout.LayoutParams(-2, -2)
            slp.setMargins(0, Ui.dp(this, com.mizan.launcher.core.Design.SPACE_S), 0, 0)
            statusLine.layoutParams = slp
            left.addView(statusLine)

            if (!prefs.safeMode && cfg.showWeather) {
                // Weather widget — text filled by refreshWeather(); hidden if empty.
                // v1.4: OK on the widget opens the full forecast screen.
                val weather = TextView(this).apply {
                    setTextColor(textColour)
                    textSize = com.mizan.launcher.core.Design.HERO_WEATHER_SP * scale
                    setPadding(Ui.dp(this@MainActivity, 12), Ui.dp(this@MainActivity, 4), Ui.dp(this@MainActivity, 12), Ui.dp(this@MainActivity, 4))
                    tag = "weather"
                    isFocusable = true
                    isClickable = true
                    background = Ui.tileBackground(accent, false, com.mizan.launcher.core.Design.RADIUS_CHIP, this@MainActivity)
                    setOnClickListener {
                        try { startActivity(Intent(this@MainActivity, ForecastActivity::class.java)) }
                        catch (e: Exception) { Prefs.noteError(this@MainActivity, "forecastOpen", e) }
                    }
                }
                Ui.focusLift(weather, accent, com.mizan.launcher.core.Design.RADIUS_CHIP)
                statusLine.addView(weather)
            }
            networkView = null
            if (cfg.showNetwork) {
                networkView = TextView(this).apply {
                    setTextColor(subColour)
                    textSize = com.mizan.launcher.core.Design.HERO_STATUS_SP * scale
                    setPadding(Ui.dp(this@MainActivity, 10), 0, Ui.dp(this@MainActivity, 10), 0)
                }
                statusLine.addView(networkView)
            }
            if (!prefs.safeMode && cfg.showBluetooth) {
                val bt = TextView(this)
                bt.setTextColor(subColour)
                bt.textSize = com.mizan.launcher.core.Design.HERO_STATUS_SP * scale
                bt.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 12), 0)
                bt.text = bluetoothLabel()
                statusLine.addView(bt)
            }

            // Guest pill (v1.4): while the Guest profile is active, a
            // subtle "Guest" tag sits under the status line. (Bar
            // hidden → the pill moves to the top-left corner; see
            // buildRoot.)
            if (!prefs.safeMode && guestActive()) {
                val pill = guestPill()
                (pill.layoutParams as? LinearLayout.LayoutParams)
                    ?.setMargins(0, Ui.dp(this, com.mizan.launcher.core.Design.SPACE_S), 0, 0)
                left.addView(pill)
            }
            hero.addView(left, LinearLayout.LayoutParams(0, -2, 1f))
            // RIGHT: ONE translucent rounded panel grouping the
            // quick tiles, the profile tile and the Settings tile —
            // a single organized block instead of loose floating
            // buttons scattered across the top of the screen.
            val panel = LinearLayout(this)
            panel.orientation = LinearLayout.HORIZONTAL
            panel.gravity = Gravity.CENTER_VERTICAL
            val panelBg = GradientDrawable()
            panelBg.cornerRadius = Ui.dp(this, com.mizan.launcher.core.Design.RADIUS_PANEL).toFloat()
            panelBg.setColor(com.mizan.launcher.core.Design.PANEL_FILL)
            panel.background = panelBg
            panel.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8))
            if (!prefs.safeMode && cfg.showQuickTiles) {
                panel.addView(buildQuickTiles())
            }

            // Profile tile (v1.4): the one consistent place profile
            // switching lives on the home screen. Click or hold-OK
            // opens the switch menu; switching TO Guest is instant,
            // switching AWAY from Guest may ask for the arrow pattern
            // (see requestProfileSwitch / core/GuestGate).
            if (!prefs.safeMode) {
                val profileTile = quickTile("👤 " + prefs.profileDisplayName(prefs.currentProfile)) {
                    showProfileSwitchMenu()
                }
                profileTile.setOnLongClickListener { showProfileSwitchMenu(); true }
                panel.addView(profileTile)
            }

            // The bar's Settings tile: always present in Safe mode;
            // otherwise governed by its own per-tile switch (v1.3).
            // When it's off, the corner gear in buildRoot() takes over
            // as the way into Settings.
            if (prefs.safeMode || cfg.showTileSettings) {
                val settingsTile = quickTile("⚙  Settings") { openSettings() }
                panel.addView(settingsTile)
            }
            hero.addView(panel)
            wrap.addView(hero)
        }

        buildHomeHint()?.let { wrap.addView(it) }
        updateClock()
        return wrap
    }

    /** "ᛒ BT on/off" label; empty when the device has no Bluetooth at all. */
    private fun bluetoothLabel(): String {
        return try {
            @Suppress("DEPRECATION")
            val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter() ?: return ""
            if (adapter.isEnabled) "ᛒ BT" else "ᛒ BT off"
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * One-line dismissible hint shown when Mizan is not the default home
     * and the watcher is off: without either, Home won't land here.
     */
    private fun buildHomeHint(): View? {
        if (prefs.safeMode || prefs.homeHintDismissed) return null
        return try {
            if (com.mizan.launcher.data.HomeStatus.isDefaultHome(this)) return null
            if (com.mizan.launcher.data.HomeStatus.isWatcherEnabled(this)) return null
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(-1, -2)
            lp.setMargins(0, Ui.dp(this, 10), 0, 0)
            row.layoutParams = lp
            row.setBackgroundColor(0x552196F3)
            row.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8))

            val label = TextView(this)
            label.text = "Mizan isn't your home screen yet — open Settings > Make Mizan your home screen"
            label.setTextColor(textColour); label.textSize = 16f
            row.addView(label, LinearLayout.LayoutParams(0, -2, 1f))

            val dismiss = TextView(this)
            dismiss.text = "Dismiss"
            dismiss.setTextColor(textColour); dismiss.textSize = 16f
            dismiss.isFocusable = true; dismiss.isClickable = true
            dismiss.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 6))
            Ui.focusLift(dismiss, accent, 10)
            dismiss.setOnClickListener {
                prefs.homeHintDismissed = true
                rebuild()
            }
            row.addView(dismiss)
            row
        } catch (e: Exception) {
            Prefs.noteError(this, "homeHint", e)
            null
        }
    }

    /**
     * Wi-Fi / Bluetooth / Sound / Picture tiles — each hides if its
     * intent resolves nowhere, and (v1.3) each is gated by its own
     * per-tile switch under the showQuickTiles master.
     */
    private fun buildQuickTiles(): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val cfg = layout.statusBar
        // v1.5: the All Apps tile opens the full-screen All Apps tab
        // (every installed app, foldered ones included). Same master +
        // per-tile rule as the other tiles via showTileAllApps.
        if (cfg.showTileAllApps) {
            row.addView(quickTile("▦ All Apps") {
                try {
                    startActivity(
                        Intent(this, com.mizan.launcher.ui.AllAppsActivity::class.java)
                    )
                } catch (e: Exception) { Prefs.noteError(this, "allAppsTile", e) }
            })
        }
        // v1.4: the Search tile opens Mizan's own Search screen (not a
        // system settings page); it follows the same master + per-tile
        // rule as the system tiles via showTileSearch.
        if (cfg.showTileSearch) {
            row.addView(quickTile("Search") {
                try { startActivity(Intent(this, SearchActivity::class.java)) }
                catch (e: Exception) { Prefs.noteError(this, "searchTile", e) }
            })
        }
        val candidates = listOf(
            Triple("Wi-Fi", Settings.ACTION_WIFI_SETTINGS, cfg.showTileWifi),
            Triple("Bluetooth", Settings.ACTION_BLUETOOTH_SETTINGS, cfg.showTileBluetooth),
            Triple("Sound", Settings.ACTION_SOUND_SETTINGS, cfg.showTileSound),
            Triple("Picture", Settings.ACTION_DISPLAY_SETTINGS, cfg.showTilePicture)
        )
        for ((label, action, enabled) in candidates) {
            if (!enabled) continue
            try {
                val intent = Intent(action)
                if (intent.resolveActivity(packageManager) == null) continue
                row.addView(quickTile(label) {
                    try { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    catch (e: Exception) { Prefs.noteError(this, "quickTile:$label", e) }
                })
            } catch (e: Exception) {
                // Fail-open: a broken tile simply doesn't appear.
            }
        }
        return row
    }

    // ------------------------------------------------------- guest / profiles (v1.4)

    /** True while the flagged Guest profile is the active profile. */
    private fun guestActive(): Boolean =
        GuestGate.isGuestActive(prefs.currentProfile, prefs.guestProfile)

    /** The subtle "Guest" tag shown while the Guest profile is active. */
    private fun guestPill(): TextView {
        val pill = TextView(this)
        pill.text = "Guest"
        pill.setTextColor(textColour)
        pill.textSize = 15f
        pill.gravity = Gravity.CENTER
        pill.setPadding(Ui.dp(this, 12), Ui.dp(this, 5), Ui.dp(this, 12), Ui.dp(this, 5))
        val lp = LinearLayout.LayoutParams(-2, -2)
        lp.setMargins(Ui.dp(this, 10), 0, Ui.dp(this, 6), 0)
        pill.layoutParams = lp
        pill.background = Ui.tileBackground(accent, false, 12, this)
        pill.alpha = 0.85f
        return pill
    }

    /**
     * Open Settings. Since v1.5 the entry gate lives INSIDE
     * SettingsActivity.onCreate (core/SettingsGate merges the global
     * "Lock Settings" switch with the Guest rule into one prompt), so
     * every entry point — this tile, the corner gear, watcher
     * deep-links — is covered exactly once, and nothing prompts here.
     */
    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    /** Profile switch menu on the status-bar profile tile (click or hold-OK). */
    private fun showProfileSwitchMenu() {
        val others = listOf(1, 2).filter { it != prefs.currentProfile }
        val labels = others.map { "Switch to ${prefs.profileDisplayName(it)}" }
        AlertDialog.Builder(this)
            .setTitle("Profile: ${prefs.profileDisplayName(prefs.currentProfile)}")
            .setItems(labels.toTypedArray()) { _, which -> requestProfileSwitch(others[which]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Switch profiles through the Guest gate: TO Guest is instant and
     * never gated; AWAY from Guest (lock on + pattern set) asks for the
     * pattern first; between personal profiles is free.
     */
    private fun requestProfileSwitch(target: Int) {
        val action = when {
            prefs.guestProfile != 0 && target == prefs.guestProfile ->
                GuestGate.Action.SWITCH_TO_GUEST
            guestActive() -> GuestGate.Action.SWITCH_AWAY_FROM_GUEST
            else -> GuestGate.Action.SWITCH_BETWEEN_PERSONAL
        }
        val decision = GuestGate.decide(
            guestActive(), prefs.guestLockSettings, prefs.arrowPin.isNotEmpty(), action
        )
        if (decision == GuestGate.Decision.REQUIRE_PATTERN) {
            ArrowPinDialog.showUnlock(
                this, "Enter pattern to leave Guest", accent, prefs.arrowPin,
                onSuccess = { doSwitchProfile(target) },
                onWrongAttempt = { prefs.arrowAttempts = prefs.arrowAttempts + 1 }
            )
        } else {
            doSwitchProfile(target)
        }
    }

    private fun doSwitchProfile(target: Int) {
        prefs.currentProfile = target
        lastNowPlayingSignature = null
        rebuild()
        Toast.makeText(this, "Switched to ${prefs.profileDisplayName(target)}", Toast.LENGTH_SHORT).show()
    }

    private fun quickTile(label: String, onClick: () -> Unit): TextView {
        val tv = TextView(this)
        tv.text = label
        tv.setTextColor(textColour)
        tv.textSize = 17f
        tv.gravity = Gravity.CENTER
        tv.isFocusable = true
        tv.isClickable = true
        tv.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8))
        val lp = LinearLayout.LayoutParams(-2, -2)
        lp.setMargins(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0)
        tv.layoutParams = lp
        Ui.focusLift(tv, accent, com.mizan.launcher.core.Design.RADIUS_CHIP)
        tv.background = Ui.tileBackground(accent, false, com.mizan.launcher.core.Design.RADIUS_CHIP, this)
        tv.setOnClickListener { onClick() }
        return tv
    }

    // -------------------------------------------------------------- sections

    private fun section(title: String): LinearLayout {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        // v1.6: the unified small-caps + accent-rule header.
        box.addView(Ui.sectionHeader(this, title, textColour, accent))
        return box
    }

    private fun buildFavoritesSection(): View {
        val box = section("Favorites")
        val favs = layout.favorites.mapNotNull { appsByPkg[it] }
            .filter { it.pkg !in layout.hidden }
        if (favs.isEmpty()) {
            val hint = TextView(this)
            hint.text = "No favorites yet — hold OK on any app below and choose “Add favorite”."
            hint.setTextColor(subColour); hint.textSize = 17f
            box.addView(hint)
            return box
        }
        val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL
        for (entry in favs) row.addView(appTile(entry, Source.Favorites))
        box.addView(horizontalScroller(row))
        return box
    }

    /**
     * Recently Used row (v1.4): the profile's MRU launches FROM Mizan
     * (core/RecentsStore). Hidden when the store is empty or every
     * recorded app was uninstalled/hidden since. Tiles are the uniform
     * app tiles; the hold-OK menu works as elsewhere (minus Move —
     * this row's order IS its recency).
     */
    private fun buildRecentsSection(): View? {
        val entries = RecentsStore.decode(prefs.recents)
            .mapNotNull { appsByPkg[it.pkg] }
            .filter { it.pkg !in layout.hidden }
        if (entries.isEmpty()) return null
        val box = section("Recently Used")
        val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL
        for (entry in entries) row.addView(appTile(entry, Source.Recents))
        box.addView(horizontalScroller(row))
        return box
    }

    // ------------------------------------------------------------ Now Playing (v1.4)

    /** Signature of the currently-shown Now Playing cards; null = not yet known. */
    private var lastNowPlayingSignature: String? = null

    private fun nowPlayingSignature(cards: List<NowPlayingLogic.Card>): String =
        cards.joinToString(";") { "${it.pkg}|${it.title}|${it.playing}" }

    /**
     * Now Playing row: wide cards for the active media sessions. The
     * row exists only when notification access is granted AND at least
     * one session maps to a card — otherwise it hides silently
     * (fail-open), and diagnostics records granted/session counts.
     */
    private fun buildNowPlayingSection(): View? {
        val granted = NowPlayingData.isGranted(this)
        DiagCache.nowPlayingGranted = granted
        if (!granted) {
            DiagCache.nowPlayingCount = 0
            lastNowPlayingSignature = ""
            return null
        }
        val live = NowPlayingData.activeCards(this)
        DiagCache.nowPlayingCount = NowPlayingData.sessionCount(this)
        lastNowPlayingSignature = nowPlayingSignature(live.map { it.card })
        if (live.isEmpty()) return null
        val box = section("Now Playing")
        val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL
        for (item in live) row.addView(nowPlayingCard(item))
        box.addView(horizontalScroller(row))
        return box
    }

    /**
     * One Now Playing card (the uniform wide size): artwork when the
     * session provides it, title + artist always visible, state glyph
     * in the corner. OK toggles play/pause; LEFT/RIGHT skip prev/next
     * when the session supports them (consumed only then — otherwise
     * focus moves along the row as usual).
     */
    private fun nowPlayingCard(item: NowPlayingData.LiveCard): View {
        val card = item.card
        val tile = baseTile(wide = true)
        if (item.artwork != null) {
            val iv = ImageView(this)
            iv.setImageBitmap(item.artwork)
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            tile.addView(iv, FrameLayout.LayoutParams(-1, -1))
        } else {
            val glyph = TextView(this)
            glyph.text = "♪"
            glyph.setTextColor(textColour)
            glyph.textSize = 40f
            glyph.gravity = Gravity.CENTER
            val glp = FrameLayout.LayoutParams(-1, -1)
            glyph.layoutParams = glp
            tile.addView(glyph)
        }
        // Title + artist plate along the bottom.
        val plate = LinearLayout(this)
        plate.orientation = LinearLayout.VERTICAL
        plate.setBackgroundColor(0xB3000000.toInt())
        plate.setPadding(Ui.dp(this, 10), Ui.dp(this, 5), Ui.dp(this, 10), Ui.dp(this, 6))
        val plp = FrameLayout.LayoutParams(-1, -2)
        plp.gravity = Gravity.BOTTOM
        plate.layoutParams = plp
        val title = TextView(this)
        title.text = card.title
        title.setTextColor(textColour); title.textSize = 15f
        title.maxLines = 1; title.ellipsize = android.text.TextUtils.TruncateAt.END
        plate.addView(title)
        if (card.artist.isNotEmpty()) {
            val artist = TextView(this)
            artist.text = card.artist
            artist.setTextColor(0xFFCCCCCC.toInt()); artist.textSize = 13f
            artist.maxLines = 1; artist.ellipsize = android.text.TextUtils.TruncateAt.END
            plate.addView(artist)
        }
        tile.addView(plate)
        val state = TextView(this)
        state.text = if (card.playing) "❚❚" else "▶"
        state.setTextColor(textColour); state.textSize = 14f
        val slp = FrameLayout.LayoutParams(-2, -2)
        slp.gravity = Gravity.TOP or Gravity.END
        slp.setMargins(0, Ui.dp(this, 5), Ui.dp(this, 8), 0)
        state.layoutParams = slp
        tile.addView(state)

        Ui.focusLift(tile, accent, 16)
        tile.setOnClickListener {
            try {
                val controls = item.controller.transportControls
                if (card.playing) controls.pause() else controls.play()
                handler.postDelayed({ rebuild() }, 450)
            } catch (e: Exception) {
                Prefs.noteError(this, "nowPlayingToggle", e)
            }
        }
        tile.setOnKeyListener { _, keyCode, event ->
            if (event.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            try {
                when (keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (!card.canPrev) return@setOnKeyListener false
                        item.controller.transportControls.skipToPrevious()
                        handler.postDelayed({ rebuild() }, 450)
                        true
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (!card.canNext) return@setOnKeyListener false
                        item.controller.transportControls.skipToNext()
                        handler.postDelayed({ rebuild() }, 450)
                        true
                    }
                    else -> false
                }
            } catch (e: Exception) {
                Prefs.noteError(this, "nowPlayingSkip", e)
                false
            }
        }
        return tile
    }

    private fun buildAppsSection(): View {
        val box = section("All Apps")
        // v1.3 folder dedupe: an app inside any folder lives ONLY in
        // its folder here (see FolderLogic); removing it from the
        // folder brings it back. Favorites are unaffected.
        val visiblePkgs = FolderLogic.allAppsVisible(
            apps.map { it.pkg }, layout.hidden.toSet(), layout.folders
        ).toSet()
        val visible = apps.filter { it.pkg in visiblePkgs }
        if (visible.isEmpty()) {
            val hint = TextView(this)
            hint.text = "No launchable apps found."
            hint.setTextColor(subColour); hint.textSize = 17f
            box.addView(hint)
            return box
        }
        val ordered = orderedApps(visible)
        val cols = prefs.columns
        var i = 0
        while (i < ordered.size) {
            val rowLayout = LinearLayout(this); rowLayout.orientation = LinearLayout.HORIZONTAL
            val chunk = ordered.subList(i, minOf(i + cols, ordered.size))
            for (entry in chunk) rowLayout.addView(appTile(entry, Source.AllApps))
            box.addView(rowLayout)
            i += cols
        }
        return box
    }

    /** Custom order first (as arranged with hold-and-move), then the rest alphabetically. */
    private fun orderedApps(visible: List<AppEntry>): List<AppEntry> {
        val byPkg = visible.associateBy { it.pkg }
        val head = layout.allAppsOrder.mapNotNull { byPkg[it] }
        val headPkgs = head.map { it.pkg }.toSet()
        return head + visible.filter { it.pkg !in headPkgs }
    }

    private fun buildFoldersSection(): View? {
        // v1.5: the home Folders row shows ROOT folders only — nested
        // folders live inside their parent and are reached by opening it.
        val roots = FolderTree.rootFolders(layout.folders)
        if (roots.isEmpty() && !prefs.safeMode) {
            // Still show the section so "New folder" is discoverable.
            val box = section("Folders")
            val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL
            row.addView(newFolderTile())
            box.addView(horizontalScroller(row))
            return box
        }
        val box = section("Folders")
        val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL
        for (folder in roots) {
            row.addView(folderTile(folder))
        }
        row.addView(newFolderTile())
        box.addView(horizontalScroller(row))
        return box
    }

    /** "3 apps · 1 folder" style contents summary for folder tiles/menus. */
    private fun folderContentsLabel(folder: Folder): String {
        val apps = folder.appPackages.size
        val subs = folder.subFolderIds.size
        return when {
            apps == 0 && subs == 0 -> "empty"
            subs == 0 -> if (apps == 1) "1 app" else "$apps apps"
            apps == 0 -> if (subs == 1) "1 folder" else "$subs folders"
            else -> "$apps apps · $subs ${if (subs == 1) "folder" else "folders"}"
        }
    }

    /** A folder's place in the tree, for pickers: "Media › Video". */
    private fun folderPathLabel(folder: Folder): String =
        FolderTree.breadcrumb(layout.folders, folder.id).joinToString(" › ") { it.name }

    private fun buildContinueSection(): View? {
        val items = TvData.watchNext(this)
        DiagCache.watchNextCount = items.size
        if (items.isEmpty()) return null
        val box = section("Continue Watching")
        val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL
        for (item in items) row.addView(watchTile(item))
        box.addView(horizontalScroller(row))
        return box
    }

    private fun buildInputsSection(): View? {
        val inputs = TvData.listInputs(this)
        DiagCache.inputsCount = inputs.size
        if (inputs.isEmpty()) return null
        val box = section("HDMI & Inputs")
        val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL
        for (info in inputs) row.addView(inputTile(info))
        box.addView(horizontalScroller(row))
        return box
    }

    /**
     * One HDMI/TV input tile — shared by the TV-style inputs row and
     * the iPad grid's inputs segment, so the v1.6 lock gate below
     * applies identically in both styles. Inputs are never move-mode
     * reorderable: their tile has no menu at all.
     */
    private fun inputTile(
        info: android.media.tv.TvInputInfo,
        ipad: Boolean = false,
        large: Boolean = false
    ): View {
        val tile = glyphTile("📺", TvData.inputLabel(this, info), ipad = ipad, large = large)
        tile.setOnClickListener {
            // v1.6 gate audit: switching to an input starts an
            // activity inside the input's own package (e.g. the
            // TV/tuner app) — if that package is arrow-locked,
            // the lock applies here too.
            val pkg = try { info.serviceInfo.packageName } catch (e: Exception) { null }
            val launch = {
                if (!TvData.launchInput(this, info)) {
                    Toast.makeText(this, "Couldn't open that input on this device", Toast.LENGTH_SHORT).show()
                }
            }
            if (!pkg.isNullOrEmpty() && pkg in prefs.lockedApps) {
                gateLockedApp(pkg, TvData.inputLabel(this, info), launch)
            } else {
                launch()
            }
        }
        return tile
    }

    private fun horizontalScroller(row: LinearLayout): HorizontalScrollView {
        val hs = HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        hs.clipToPadding = false
        val lp = LinearLayout.LayoutParams(-1, -2)
        hs.layoutParams = lp
        row.setPadding(Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10))
        hs.addView(row)
        return hs
    }

    // ---------------------------------------------------------------- tiles

    private enum class Source { Favorites, AllApps, Folder, Recents }

    /**
     * The ONE tile size for the whole home screen (v1.2: core/TileLayout).
     * [large] (v1.7) applies the iPad grid's boost on top of the
     * profile's app-size scale; every other tile leaves it off.
     */
    private fun currentTileMetrics(wide: Boolean = false, large: Boolean = false): TileLayout.Metrics {
        val dm = resources.displayMetrics
        val boost = if (large) com.mizan.launcher.core.Design.IPAD_TILE_BOOST else 1f
        return if (wide) {
            TileLayout.cardMetrics(dm.widthPixels, dm.density, prefs.appSize, prefs.columns, boost)
        } else {
            TileLayout.tileMetrics(dm.widthPixels, dm.density, prefs.appSize, prefs.columns, boost)
        }
    }

    /**
     * Base frame for every home tile: fixed uniform size + panel
     * background. The name label is added by [finishTile] as an overlay,
     * so it never affects the tile's size or the row's layout.
     */
    private fun baseTile(wide: Boolean = false, large: Boolean = false): FrameLayout {
        val m = currentTileMetrics(wide, large)
        val frame = FrameLayout(this)
        frame.isFocusable = true
        frame.isClickable = true
        val lp = LinearLayout.LayoutParams(m.tileWidthPx, m.tileHeightPx)
        val margin = Ui.dp(this, TileLayout.TILE_MARGIN_DP)
        lp.setMargins(margin, margin, margin, margin)
        frame.layoutParams = lp
        frame.background = Ui.tileBackground(accent, false, 16, this)
        if (firstTile == null) firstTile = frame
        return frame
    }

    /**
     * Adds the focus-only name overlay (bottom of the tile, translucent
     * pill) and the focus lift. The label is INVISIBLE unless the tile
     * has focus — the single decision point is TileLayout.labelVisible.
     */
    private fun finishTile(frame: FrameLayout, name: String) {
        val label = TextView(this)
        label.text = name
        label.setTextColor(textColour)
        label.textSize = 14f
        label.gravity = Gravity.CENTER
        label.maxLines = 2
        label.ellipsize = android.text.TextUtils.TruncateAt.END
        label.setBackgroundColor(0xB3000000.toInt())
        label.setPadding(Ui.dp(this, 6), Ui.dp(this, 3), Ui.dp(this, 6), Ui.dp(this, 3))
        val llp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
        )
        llp.gravity = Gravity.BOTTOM
        label.layoutParams = llp
        label.visibility = View.INVISIBLE
        frame.addView(label)
        Ui.focusLift(frame, accent, 16) { hasFocus ->
            label.visibility = if (TileLayout.labelVisible(hasFocus)) View.VISIBLE else View.INVISIBLE
        }
    }

    /**
     * One app tile. TV style: centered icon + focus-only name pill.
     * iPad style ([ipad]): squircle icon in the tile's upper area +
     * the name ALWAYS under it ([ipadLabel] off only in the dock,
     * whose favorites are icons-only). Click/long-click (launch +
     * the shared menu incl. Move) are identical in both styles.
     */
    private fun appTile(
        entry: AppEntry,
        source: Source,
        folderId: String? = null,
        ipad: Boolean = false,
        large: Boolean = false,
        ipadLabel: Boolean = true
    ): View {
        val m = currentTileMetrics(large = large)
        val tile = baseTile(large = large)
        // Move mode finds the grabbed tile by package after a rebuild.
        tile.tag = entry.pkg
        val iv = ImageView(this)
        iv.setImageBitmap(Ui.shapedIcon(entry.icon, m.iconPx, effectiveIconShape()))
        val ilp = FrameLayout.LayoutParams(m.iconPx, m.iconPx)
        if (ipad) {
            ilp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ilp.setMargins(0, Ui.dp(this, 8), 0, 0)
        } else {
            ilp.gravity = Gravity.CENTER
        }
        iv.layoutParams = ilp
        tile.addView(iv)
        if (prefs.lockedApps.contains(entry.pkg)) {
            // The lock badge stays visible even though the NAME is
            // focus-only, so a locked app is recognisable at a glance.
            val lock = TextView(this)
            lock.text = "🔒"
            lock.textSize = 13f
            val llp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
            )
            llp.gravity = Gravity.TOP or Gravity.END
            llp.setMargins(0, Ui.dp(this, 4), Ui.dp(this, 6), 0)
            lock.layoutParams = llp
            tile.addView(lock)
        }
        if (ipad) {
            if (ipadLabel) tile.addView(ipadLabelView(entry.label))
            Ui.focusLift(tile, accent, 16)
        } else {
            finishTile(tile, entry.label)
        }
        tile.setOnClickListener { launchWithPin(entry) }
        tile.setOnLongClickListener { openAppMenu(entry, source, folderId); true }
        return tile
    }

    /** A tile whose centre is a glyph/symbol and whose name shows on focus. */
    private fun glyphTile(
        glyph: String,
        name: String,
        wide: Boolean = false,
        ipad: Boolean = false,
        large: Boolean = false
    ): FrameLayout {
        val tile = baseTile(wide, large)
        val centre = TextView(this)
        centre.text = glyph
        centre.setTextColor(textColour)
        centre.textSize = if (ipad) 40f else 32f
        centre.gravity = Gravity.CENTER
        val clp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
        )
        if (ipad) {
            clp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            clp.setMargins(0, Ui.dp(this, 14), 0, 0)
        } else {
            clp.gravity = Gravity.CENTER
        }
        centre.layoutParams = clp
        tile.addView(centre)
        if (ipad) {
            tile.addView(ipadLabelView(name))
            Ui.focusLift(tile, accent, 16)
        } else {
            finishTile(tile, name)
        }
        return tile
    }

    private fun watchTile(item: WatchItem): View {
        // Continue Watching uses the row's one uniform wide card size
        // (TileLayout.cardMetrics) and the same focus-label rule.
        val tile = glyphTile("▶", item.title, wide = true)
        tile.setOnClickListener {
            // v1.6 gate audit: this used to launch directly. When the
            // item's intent names a package, that package's arrow lock
            // applies here exactly like a home launch; items without a
            // package can't be attributed to an app and just launch.
            val pkg = try {
                item.intentUri?.let {
                    Intent.parseUri(it, Intent.URI_INTENT_SCHEME).`package`
                }
            } catch (e: Exception) { null }
            val launch = {
                if (!TvData.launchWatchItem(this, item)) {
                    Toast.makeText(this, "Couldn't open that title", Toast.LENGTH_SHORT).show()
                } else if (!pkg.isNullOrEmpty()) {
                    // Recently Used records Continue Watching launches
                    // where applicable (package-named intents only).
                    prefs.recordLaunch(pkg)
                }
            }
            if (!pkg.isNullOrEmpty()) gateLockedApp(pkg, item.title, launch) else launch()
        }
        return tile
    }

    /**
     * v1.6: a folder tile's face is a tidy 2×2 mini-grid of its first
     * four apps' icons (the Apple/Roku look) instead of a lone glyph;
     * a folder with no direct apps keeps the glyph. The mini-grid is
     * presentation only — name, badge, menus and moves are unchanged.
     */
    /**
     * A folder tile's face: the 2×2 mini-grid of its first apps'
     * icons (v1.6), or the 🗂 glyph when it holds no apps. [top]
     * (v1.7 iPad style) parks the face in the tile's upper area so
     * the always-on name can sit under it; otherwise it's centered.
     * The mini icons use the effective shape, so in iPad style they
     * are squircles too.
     */
    private fun folderFace(folder: Folder, large: Boolean = false, top: Boolean = false): View {
        val contained = folder.appPackages.mapNotNull { appsByPkg[it] }
        if (contained.isEmpty()) {
            val centre = TextView(this)
            centre.text = "🗂"
            centre.setTextColor(textColour)
            centre.textSize = 32f
            centre.gravity = Gravity.CENTER
            val clp = FrameLayout.LayoutParams(-2, -2)
            if (top) {
                clp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                clp.setMargins(0, Ui.dp(this, 14), 0, 0)
            } else {
                clp.gravity = Gravity.CENTER
            }
            centre.layoutParams = clp
            return centre
        }
        val m = currentTileMetrics(large = large)
        val gap = Ui.dp(this, 4)
        val mini = ((m.iconPx - gap) / 2).coerceAtLeast(8)
        val grid = android.widget.GridLayout(this)
        grid.columnCount = 2
        grid.rowCount = 2
        val glp = FrameLayout.LayoutParams(-2, -2)
        if (top) {
            glp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            glp.setMargins(0, Ui.dp(this, 8), 0, 0)
        } else {
            glp.gravity = Gravity.CENTER
        }
        grid.layoutParams = glp
        for (i in 0 until 4) {
            val cell = FrameLayout(this)
            val clp = android.widget.GridLayout.LayoutParams()
            clp.width = mini
            clp.height = mini
            clp.setMargins(gap / 2, gap / 2, gap / 2, gap / 2)
            cell.layoutParams = clp
            val entry = contained.getOrNull(i)
            if (entry != null) {
                val iv = ImageView(this)
                iv.setImageBitmap(Ui.shapedIcon(entry.icon, mini, effectiveIconShape()))
                cell.addView(iv, FrameLayout.LayoutParams(mini, mini))
            } else {
                val dot = View(this)
                val bg = GradientDrawable()
                bg.cornerRadius = Ui.dp(this, 6).toFloat()
                bg.setColor(com.mizan.launcher.core.Design.TILE_FILL)
                dot.background = bg
                cell.addView(dot, FrameLayout.LayoutParams(mini, mini))
            }
            grid.addView(cell)
        }
        return grid
    }

    /**
     * One folder tile. iPad style ([ipad]): face on top + name always
     * under it. [movable] (the iPad grid's folders segment only) tags
     * the tile with the folder id and adds the reorder Move entry —
     * in TV style the Folders row deliberately has no reorder Move
     * (folders there are ordered by creation/nesting, v1.3 rule).
     */
    private fun folderTile(
        folder: Folder,
        ipad: Boolean = false,
        large: Boolean = false,
        movable: Boolean = false
    ): View {
        val tile = baseTile(large = large)
        if (movable) tile.tag = folder.id
        tile.addView(folderFace(folder, large = large, top = ipad))
        if (ipad) {
            tile.addView(ipadLabelView(folder.name))
            Ui.focusLift(tile, accent, 16)
        } else {
            finishTile(tile, "${folder.name} · ${folderContentsLabel(folder)}")
        }
        // v1.3: locked folders carry the same 🔒 badge as locked apps.
        if (folder.locked) {
            val lock = TextView(this)
            lock.text = "🔒"
            lock.textSize = 13f
            val llp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
            )
            llp.gravity = Gravity.TOP or Gravity.END
            llp.setMargins(0, Ui.dp(this, 4), Ui.dp(this, 6), 0)
            lock.layoutParams = llp
            tile.addView(lock)
        }
        tile.setOnClickListener { openFolderGated(folder) }
        tile.setOnLongClickListener {
            val labels = mutableListOf<String>()
            val actions = mutableListOf<() -> Unit>()
            if (movable) {
                labels.add("Move"); actions.add { startRootFolderMove(folder) }
            }
            labels.add("Rename folder"); actions.add { renameFolder(folder) }
            labels.add(if (folder.locked) "Unlock folder" else "Lock folder")
            actions.add { toggleFolderLock(folder) }
            labels.add("Move into folder…"); actions.add { chooseFolderParent(folder) }
            labels.add("Delete folder"); actions.add {
                // v1.5: deleting a folder never cascade-deletes — its
                // sub-folders return to the home Folders row and its
                // apps simply stop being folder members.
                FolderTree.deleteFolder(layout.folders, folder.id)
                saveLayout(); rebuild()
                Toast.makeText(
                    this,
                    "Folder deleted — sub-folders are back on the home screen; apps are still installed",
                    Toast.LENGTH_LONG
                ).show()
            }
            AlertDialog.Builder(this).setTitle(folder.name)
                .setItems(labels.toTypedArray()) { _, which -> actions[which].invoke() }
                .show()
            true
        }
        return tile
    }

    /**
     * "Move into folder…" for a folder (v1.5): the picker offers ONLY
     * legal targets (core/FolderTree.canNest — no self, no cycles, no
     * depth overflow), labelled by breadcrumb path, plus "Home (top
     * level)" when the folder is currently nested. Moving REPLACES the
     * folder's parent: it leaves wherever it was.
     */
    private fun chooseFolderParent(folder: Folder) {
        val targets = FolderTree.legalParents(layout.folders, folder.id)
        val nested = FolderTree.parentOf(layout.folders, folder.id) != null
        val labels = mutableListOf<String>()
        if (nested) labels.add("⌂ Home (top level)")
        labels.addAll(targets.map { "🗂 ${folderPathLabel(it)}" })
        if (labels.isEmpty()) {
            Toast.makeText(this, "No folder can hold this one (depth limit ${FolderTree.MAX_DEPTH})", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this).setTitle("Move “${folder.name}” into…")
            .setItems(labels.toTypedArray()) { _, which ->
                val parentId: String? = if (nested) {
                    if (which == 0) null else targets[which - 1].id
                } else {
                    targets[which].id
                }
                FolderTree.moveFolder(layout.folders, folder.id, parentId)
                saveLayout(); rebuild()
                Toast.makeText(
                    this,
                    if (parentId == null) "“${folder.name}” is back on the home screen"
                    else "“${folder.name}” moved into ${FolderTree.byId(layout.folders, parentId)?.name ?: "folder"}",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton("Cancel", null).show()
    }

    /**
     * Flip a folder's lock flag. Locking with no arrow pattern set runs
     * the set-pattern flow first (same fail-open rule as apps: nobody
     * gets locked out of their own launcher). If the user cancels the
     * setup, the folder simply stays unlocked.
     */
    private fun toggleFolderLock(folder: Folder) {
        if (folder.locked) {
            folder.locked = false
            saveLayout(); rebuild()
            Toast.makeText(this, "Folder unlocked", Toast.LENGTH_SHORT).show()
            return
        }
        if (prefs.arrowPin.isEmpty()) {
            ArrowPinDialog.showSetup(this, "Set a pattern to lock “${folder.name}”", accent, onDone = { seq ->
                prefs.arrowPin = seq
                folder.locked = true
                saveLayout(); rebuild()
                Toast.makeText(this, "Folder locked", Toast.LENGTH_SHORT).show()
            })
        } else {
            folder.locked = true
            saveLayout(); rebuild()
            Toast.makeText(this, "Folder locked", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Open a folder honouring its lock (v1.3): unlocked → open; locked
     * with a pattern → pattern first (wrong pattern keeps it closed);
     * locked without a pattern → set-pattern flow, then open
     * (fail-open, mirroring the app-lock rule).
     */
    private fun openFolderGated(folder: Folder) {
        when (FolderLogic.folderGate(folder, prefs.arrowPin.isNotEmpty())) {
            FolderGate.OPEN -> openFolder(folder)
            FolderGate.PROMPT_PATTERN -> {
                // Wrong entries keep the dialog open (retry) — the
                // folder opens only via onSuccess, so a wrong pattern
                // leaves it closed. Cancelling changes nothing.
                ArrowPinDialog.showUnlock(
                    this, "Unlock “${folder.name}”", accent, prefs.arrowPin,
                    onSuccess = { openFolder(folder) },
                    onWrongAttempt = { prefs.arrowAttempts = prefs.arrowAttempts + 1 }
                )
            }
            FolderGate.PROMPT_SET_PATTERN -> {
                ArrowPinDialog.showSetup(this, "Set a pattern to open “${folder.name}”", accent, onDone = { seq ->
                    prefs.arrowPin = seq
                    openFolder(folder)
                })
            }
        }
    }

    private fun newFolderTile(): View {
        val tile = glyphTile("＋", "New folder")
        tile.setOnClickListener { promptFolderName(null) }
        return tile
    }

    // -------------------------------------------------------------- folders

    private fun promptFolderName(after: ((String) -> Unit)?) {
        val input = EditText(this)
        input.hint = "Folder name"
        input.setTextColor(0xFF111111.toInt())
        AlertDialog.Builder(this).setTitle("New folder").setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    if (after != null) after(name) else {
                        // v1.5: every folder gets a stable id at birth.
                        layout.folders.add(Folder(FolderTree.freshId(layout.folders), name))
                        saveLayout(); rebuild()
                    }
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun renameFolder(folder: Folder) {
        val input = EditText(this)
        input.setText(folder.name)
        input.setTextColor(0xFF111111.toInt())
        AlertDialog.Builder(this).setTitle("Rename folder").setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    folder.name = name; saveLayout(); rebuild()
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    /**
     * Folder overlay: a d-pad navigable dialog showing one folder's
     * contents (v1.5: apps AND sub-folder tiles). Navigation is a
     * STACK of folder ids: opening a sub-folder pushes its id, BACK
     * pops one level, and BACK at the root level closes the overlay.
     * The dialog title shows the breadcrumb ("Media › Video"). The
     * folder on display is always resolved fresh from the CURRENT
     * layout by id — layout reloads replace Folder objects, so a
     * captured object would go stale (the v1.3/v1.4 lesson).
     */
    private fun openFolder(folder: Folder) {
        folderStack.clear()
        folderStack.add(folder.id)
        val scroll = ScrollView(this)
        val box = LinearLayout(this); box.orientation = LinearLayout.VERTICAL
        box.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 16))
        scroll.addView(box)
        val dlg = AlertDialog.Builder(this).setView(scroll)
            .setNegativeButton("Close", null).create()
        // Folder tiles close the overlay before launching, so Back is sane.
        box.tag = dlg
        dlg.setOnKeyListener { _, keyCode, event ->
            if (event.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            // Move mode inside the overlay is driven from the dialog
            // window (a separate window from the activity's key
            // dispatch). The session identifies its folder by ID
            // (v1.5): layout reloads replace Folder objects, so object
            // or index identity would silently stop matching.
            val session = moveSession
            if (session != null && session.container == MoveContainer.FOLDER &&
                session.folderId == folderStack.lastOrNull()
            ) {
                return@setOnKeyListener handleMoveKey(keyCode)
            }
            // BACK pops one level of the folder stack; at the root
            // level it falls through and closes the overlay.
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK && folderStack.size > 1) {
                folderStack.removeAt(folderStack.lastIndex)
                renderFolderOverlay()
                return@setOnKeyListener true
            }
            false
        }
        dlg.setOnDismissListener {
            if (moveSession?.container == MoveContainer.FOLDER) {
                endMoveMode(commit = false)
            }
            if (currentFolderDialog === dlg) {
                currentFolderDialog = null
                currentFolderBox = null
                folderStack.clear()
            }
        }
        currentFolderDialog = dlg
        currentFolderBox = box
        // v1.7: in iPad style the overlay gets roomier padding, so
        // the panel reads as a sheet rather than a cramped list.
        if (isIpadStyle()) {
            box.setPadding(
                Ui.dp(this, 24), Ui.dp(this, 16), Ui.dp(this, 24), Ui.dp(this, 24)
            )
        }
        renderFolderOverlay()
        dlg.show()
        if (isIpadStyle()) styleIpadFolderPanel(dlg)
    }

    /**
     * iPad-style folder panel dressing (v1.7): the dialog window
     * shrinks to ~70% of the screen width, gains the large-radius
     * rounded panel background, and its title (the breadcrumb)
     * centers. Purely cosmetic and fail-open — any failure leaves
     * the stock dialog, which still works.
     */
    private fun styleIpadFolderPanel(dlg: AlertDialog) {
        try {
            val dm = resources.displayMetrics
            val win = dlg.window ?: return
            win.setLayout(
                (dm.widthPixels * 0.70f).toInt(),
                android.view.WindowManager.LayoutParams.WRAP_CONTENT
            )
            val bg = GradientDrawable()
            bg.cornerRadius =
                Ui.dp(this, com.mizan.launcher.core.Design.IPAD_FOLDER_PANEL_RADIUS_DP).toFloat()
            bg.setColor(com.mizan.launcher.core.Design.IPAD_PANEL_FILL)
            win.setBackgroundDrawable(bg)
            val titleId = resources.getIdentifier("alertTitle", "id", "android")
            if (titleId != 0) {
                (dlg.findViewById(titleId) as? TextView)?.gravity = Gravity.CENTER
            }
        } catch (e: Exception) {
            Prefs.noteError(this, "ipad-folder-panel", e)
        }
    }

    private var currentFolderDialog: AlertDialog? = null
    private var currentFolderBox: LinearLayout? = null

    /** Folder ids from the overlay's root down to the folder on display. */
    private val folderStack = mutableListOf<String>()

    /** The folder the overlay is currently showing (top of the stack). */
    private fun currentOverlayFolder(): Folder? =
        folderStack.lastOrNull()?.let { FolderTree.byId(layout.folders, it) }

    /**
     * Re-render the open folder overlay for the folder on top of the
     * stack (after navigating, a move, or a contents change). The
     * folder is resolved from the CURRENT layout by id, never from a
     * captured object.
     */
    private fun renderFolderOverlay() {
        val box = currentFolderBox ?: return
        val folder = currentOverlayFolder()
        if (folder == null) {
            // The folder vanished (deleted elsewhere) — close quietly.
            currentFolderDialog?.dismiss()
            return
        }
        currentFolderDialog?.setTitle(
            FolderTree.breadcrumb(layout.folders, folder.id).joinToString(" › ") { it.name }
        )
        box.removeAllViews()
        // Tiles in entry order: apps resolve to app entries, folder
        // references to sub-folder tiles. Hidden apps stay hidden.
        // In iPad style the tiles take the style's treatment
        // (squircle + label under the icon) and rows center in the
        // panel; the contents/order/gates are unchanged.
        val ipad = isIpadStyle()
        val tiles = mutableListOf<View>()
        for (entry in folder.entries) {
            when (entry) {
                is FolderEntry.App -> {
                    val app = appsByPkg[entry.pkg]
                    if (app != null && app.pkg !in layout.hidden) {
                        tiles.add(appTile(app, Source.Folder, folder.id, ipad = ipad))
                    }
                }
                is FolderEntry.FolderRef -> {
                    FolderTree.byId(layout.folders, entry.folderId)?.let { sub ->
                        tiles.add(subFolderTile(sub, folder.id))
                    }
                }
            }
        }
        if (tiles.isEmpty()) {
            val t = TextView(this); t.text = "This folder is empty."; t.textSize = 18f
            box.addView(t)
            return
        }
        val cols = prefs.columns
        var i = 0
        while (i < tiles.size) {
            val rowLayout = LinearLayout(this); rowLayout.orientation = LinearLayout.HORIZONTAL
            if (ipad) rowLayout.gravity = Gravity.CENTER
            for (tile in tiles.subList(i, minOf(i + cols, tiles.size))) {
                rowLayout.addView(tile)
            }
            box.addView(rowLayout)
            i += cols
        }
    }

    /**
     * A sub-folder tile inside the overlay: the same uniform tile +
     * focus-name + 🔒 badge rules as home folder tiles. Clicking it
     * pushes the folder onto the overlay's stack — gated by the
     * folder lock at push time, exactly like opening from home.
     * Hold-OK: Move (reorder the entry inside its parent), Remove
     * from folder (the sub-folder returns to the home Folders row,
     * contents intact), Lock/Unlock.
     */
    private fun subFolderTile(folder: Folder, parentId: String): View {
        // v1.7: in iPad style a sub-folder tile matches the panel's
        // other tiles (folder face + name always under it); in TV
        // style it stays the glyph tile with the focus-only name.
        val tile: FrameLayout = if (isIpadStyle()) {
            val t = baseTile()
            t.addView(folderFace(folder, top = true))
            t.addView(ipadLabelView(folder.name))
            Ui.focusLift(t, accent, 16)
            t
        } else {
            glyphTile("🗂", "${folder.name} · ${folderContentsLabel(folder)}")
        }
        // The grabbed-tile search (move mode) finds tiles by entry key.
        tile.tag = FolderEntry.FolderRef(folder.id).key()
        if (folder.locked) {
            val lock = TextView(this)
            lock.text = "🔒"
            lock.textSize = 13f
            val llp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
            )
            llp.gravity = Gravity.TOP or Gravity.END
            llp.setMargins(0, Ui.dp(this, 4), Ui.dp(this, 6), 0)
            lock.layoutParams = llp
            tile.addView(lock)
        }
        tile.setOnClickListener { pushFolderGated(folder) }
        tile.setOnLongClickListener {
            val labels = mutableListOf<String>()
            val actions = mutableListOf<() -> Unit>()
            labels.add("Move")
            actions.add { startEntryMove(FolderEntry.FolderRef(folder.id).key(), parentId) }
            labels.add("Remove from folder")
            actions.add {
                FolderTree.byId(layout.folders, parentId)?.entries?.removeAll {
                    it is FolderEntry.FolderRef && it.folderId == folder.id
                }
                saveLayout(); renderFolderOverlay(); rebuild()
                Toast.makeText(
                    this, "“${folder.name}” is back on the home screen", Toast.LENGTH_SHORT
                ).show()
            }
            labels.add(if (folder.locked) "Unlock folder" else "Lock folder")
            actions.add {
                toggleFolderLock(folder)
                // toggleFolderLock rebuilds HOME; the overlay shows a
                // possibly-stale layout object afterwards, so re-render
                // it from the current layout too.
                renderFolderOverlay()
            }
            AlertDialog.Builder(this).setTitle(folder.name)
                .setItems(labels.toTypedArray()) { _, which -> actions[which].invoke() }
                .show()
            true
        }
        return tile
    }

    /**
     * Push a sub-folder onto the overlay stack, honouring its lock
     * (same gate as opening a folder from home): locked + pattern →
     * pattern first; locked without a pattern → set-pattern flow,
     * then push (fail-open). A move session in progress blocks
     * navigation — the grabbed tile must be dropped/cancelled first.
     */
    private fun pushFolderGated(folder: Folder) {
        if (moveSession != null) return
        when (FolderLogic.folderGate(folder, prefs.arrowPin.isNotEmpty())) {
            FolderGate.OPEN -> pushFolder(folder)
            FolderGate.PROMPT_PATTERN -> {
                ArrowPinDialog.showUnlock(
                    this, "Unlock “${folder.name}”", accent, prefs.arrowPin,
                    onSuccess = { pushFolder(folder) },
                    onWrongAttempt = { prefs.arrowAttempts = prefs.arrowAttempts + 1 }
                )
            }
            FolderGate.PROMPT_SET_PATTERN -> {
                ArrowPinDialog.showSetup(this, "Set a pattern to open “${folder.name}”", accent, onDone = { seq ->
                    prefs.arrowPin = seq
                    pushFolder(folder)
                })
            }
        }
    }

    private fun pushFolder(folder: Folder) {
        if (folderStack.lastOrNull() == folder.id) return
        folderStack.add(folder.id)
        renderFolderOverlay()
        // Focus the first tile of the pushed folder, like a fresh open.
        currentFolderBox?.post { currentFolderBox?.let { focusFirstTile(it) } }
    }

    /** Best-effort focus of the first focusable tile inside [view]. */
    private fun focusFirstTile(view: View): Boolean {
        if (view.isFocusable && view.requestFocus()) return true
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                if (focusFirstTile(view.getChildAt(i))) return true
            }
        }
        return false
    }

    // ---------------------------------------------------------------- menus

    private fun saveLayout() { prefs.layout = layout }

    private fun openAppMenu(entry: AppEntry, source: Source, folderId: String?) {
        val isFav = layout.favorites.contains(entry.pkg)
        val isLocked = prefs.lockedApps.contains(entry.pkg)
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        // v1.3: hold-and-move replaces the old step left/right entries.
        // v1.4: Recently Used tiles get the same menu MINUS Move — the
        // row's order is its recency, so there is nothing to rearrange.
        if (source != Source.Recents) {
            labels.add("Move")
            actions.add { startMoveMode(entry, source, folderId) }
        }
        labels.add(if (isFav) "Remove favorite" else "Add favorite")
        actions.add {
            if (isFav) layout.favorites.remove(entry.pkg) else layout.favorites.add(entry.pkg)
            saveLayout(); rebuild()
        }
        if (source == Source.Folder && folderId != null) {
            labels.add("Remove from folder")
            actions.add {
                // v1.5: removing the entry takes the app out of the
                // folder; it reappears in All Apps (recursive dedupe).
                FolderTree.byId(layout.folders, folderId)?.entries?.removeAll {
                    it is FolderEntry.App && it.pkg == entry.pkg
                }
                saveLayout(); currentFolderDialog?.dismiss(); rebuild()
            }
        } else {
            labels.add("Add to folder…")
            actions.add { chooseFolder(entry) }
        }
        labels.add(if (isLocked) "Remove arrow lock" else "Arrow-lock this app")
        actions.add {
            val set = prefs.lockedApps.toMutableSet()
            if (isLocked) set.remove(entry.pkg) else set.add(entry.pkg)
            prefs.lockedApps = set
            if (!isLocked && prefs.arrowPin.isEmpty()) {
                Toast.makeText(this, "Locked. You'll set an arrow pattern the first time you open it (or in Settings).", Toast.LENGTH_LONG).show()
            }
            rebuild()
        }
        labels.add("Hide app")
        actions.add {
            layout.hidden.add(entry.pkg)
            layout.favorites.remove(entry.pkg)
            saveLayout(); currentFolderDialog?.dismiss(); rebuild()
            Toast.makeText(this, "Hidden. Unhide it in Settings.", Toast.LENGTH_SHORT).show()
        }
        labels.add("App info")
        actions.add {
            try {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(android.net.Uri.parse("package:${entry.pkg}"))
                )
            } catch (e: Exception) { Prefs.noteError(this, "appInfo", e) }
        }
        if (!entry.isSystem) {
            labels.add("Uninstall")
            actions.add {
                try {
                    startActivity(Intent(Intent.ACTION_DELETE).setData(android.net.Uri.parse("package:${entry.pkg}")))
                } catch (e: Exception) { Prefs.noteError(this, "uninstall", e) }
            }
        }

        AlertDialog.Builder(this)
            .setTitle(entry.label)
            .setItems(labels.toTypedArray()) { _, which -> actions[which].invoke() }
            .show()
    }

    // ------------------------------------------------------------ move mode

    /**
     * One active hold-and-move session (v1.4 rework). The ordering
     * state lives ENTIRELY in the pure [MoveSessionMachine], which
     * owns a private working copy of the container's order. This
     * holder only remembers WHERE the order belongs (container +
     * folder ID) so each write-back resolves its target list from
     * the CURRENT layout at write time.
     *
     * v1.3 stored a direct reference to the layout's list here — and
     * rebuild() reloads the layout from prefs (a fresh object) on
     * every rebuild, orphaning that reference at grab time: swaps
     * landed in a list nobody rendered or persisted, and arrows
     * appeared to do nothing (confirmed on-device). Never hold a
     * layout list reference across a rebuild again.
     *
     * v1.5: folder sessions resolve their folder by its STABLE ID
     * (v1.4 used the list index; nesting made positions meaningless),
     * and the session orders ENTRY KEYS — inside a folder, apps and
     * sub-folder entries move through the same machine.
     */
    private class ActiveMove(
        val machine: MoveSessionMachine,
        val container: MoveContainer,
        /** Stable folder id for FOLDER sessions; null otherwise. */
        val folderId: String?
    )

    private var moveSession: ActiveMove? = null
    private var moveHintBar: TextView? = null
    private var movePulse: Runnable? = null
    private var movePulseOn: Boolean = false

    /**
     * Enter move mode for [entry] from the hold-OK menu. All Apps moves
     * edit the DISPLAYED order (folder-hidden apps are not in it; the
     * displayed order is materialized into allAppsOrder first — folder
     * membership itself is never touched by moving). Favorites and
     * folder contents edit their own lists, via the machine's copy.
     */
    private fun startMoveMode(entry: AppEntry, source: Source, folderId: String?) {
        // Recents tiles never enter move mode (their menu omits Move).
        if (source == Source.Recents) return
        val container: MoveContainer
        val initialOrder: List<String>
        when (source) {
            Source.Favorites -> {
                container = MoveContainer.FAVORITES
                initialOrder = layout.favorites.toList()
            }
            Source.AllApps -> {
                container = MoveContainer.ALL_APPS
                val visiblePkgs = FolderLogic.allAppsVisible(
                    apps.map { it.pkg }, layout.hidden.toSet(), layout.folders
                ).toSet()
                val current = orderedApps(apps.filter { it.pkg in visiblePkgs }).map { it.pkg }
                if (layout.allAppsOrder != current) {
                    layout.allAppsOrder.clear(); layout.allAppsOrder.addAll(current)
                    saveLayout()
                }
                initialOrder = layout.allAppsOrder.toList()
            }
            Source.Folder -> {
                // v1.5: a folder session orders the folder's ENTRY
                // KEYS (apps and sub-folders alike), resolved by id.
                val folder = folderId?.let { FolderTree.byId(layout.folders, it) } ?: return
                container = MoveContainer.FOLDER
                initialOrder = folder.entryKeys()
            }
            Source.Recents -> return // unreachable (guarded above); keeps the when exhaustive
        }
        beginMoveSession(entry.pkg, container, initialOrder, folderId)
    }

    /**
     * Enter move mode for a folder ENTRY (v1.5): a sub-folder tile
     * inside its parent's overlay. The grabbed "item" is the entry
     * key (`@f:<id>`); from here it moves through the parent's entry
     * list exactly like an app.
     */
    private fun startEntryMove(entryKey: String, parentFolderId: String) {
        val folder = FolderTree.byId(layout.folders, parentFolderId) ?: return
        beginMoveSession(entryKey, MoveContainer.FOLDER, folder.entryKeys(), parentFolderId)
    }

    /**
     * Enter move mode for a ROOT folder tile (v1.7, iPad grid only):
     * the grabbed "item" is the folder's stable id and the session
     * orders the root ids; write-back permutes the roots' slots in
     * the flat folder list via FolderTree.reorderRoots, leaving
     * nesting untouched. The grid tile carries the id as its tag, so
     * the shared grabbed-tile search finds it like any app tile.
     */
    private fun startRootFolderMove(folder: Folder) {
        val roots = FolderTree.rootFolders(layout.folders).map { it.id }
        beginMoveSession(folder.id, MoveContainer.ROOT_FOLDERS, roots, null)
    }

    /** Shared session start for app moves and folder-entry moves. */
    private fun beginMoveSession(
        grabbedKey: String,
        container: MoveContainer,
        initialOrder: List<String>,
        folderId: String?
    ) {
        val machine = MoveSessionMachine(grabbedKey, initialOrder, prefs.columns)
        if (!machine.isValid) return
        moveSession = ActiveMove(
            machine, container,
            if (container == MoveContainer.FOLDER) folderId else null
        )
        // Re-render (buildRoot attaches the hint bar while a home
        // session is active), re-find the grabbed tile, mark it.
        rebuildAfterMove()
        Toast.makeText(this, "Move mode: arrows move, OK drops, Back cancels", Toast.LENGTH_SHORT).show()
    }

    /**
     * Write a session order (current or snapshot) into the CURRENT
     * layout's list for the session's container and persist it. The
     * target is resolved HERE, at write time — never from a reference
     * captured earlier — so layout reloads (rebuild re-decodes prefs)
     * cannot orphan a move again. FOLDER write-back converts the
     * session's entry keys back into typed entries.
     */
    private fun applyMoveOrder(session: ActiveMove, order: List<String>) {
        when (session.container) {
            MoveContainer.FAVORITES -> {
                layout.favorites.clear()
                layout.favorites.addAll(order)
            }
            MoveContainer.ALL_APPS -> {
                layout.allAppsOrder.clear()
                layout.allAppsOrder.addAll(order)
            }
            MoveContainer.FOLDER -> {
                val folder = session.folderId?.let { FolderTree.byId(layout.folders, it) }
                if (folder != null) {
                    folder.entries.clear()
                    folder.entries.addAll(order.map { FolderEntry.fromKey(it) })
                }
            }
            MoveContainer.ROOT_FOLDERS -> {
                // v1.7: permute only the roots' own slots in the
                // flat list — nested folders keep their positions.
                FolderTree.reorderRoots(layout.folders, order)
            }
        }
        saveLayout()
    }

    /** Hint bar pinned at the top of the home root while move mode runs. */
    private fun attachMoveHint(root: FrameLayout) {
        hideMoveChrome()
        val bar = TextView(this)
        bar.text = "✥ Move with arrows · OK = drop · Back = cancel"
        bar.setTextColor(0xFFFFFFFF.toInt())
        bar.textSize = 16f
        bar.gravity = Gravity.CENTER
        bar.setBackgroundColor(0xE6222222.toInt())
        bar.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8))
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
        )
        lp.gravity = Gravity.TOP
        bar.layoutParams = lp
        root.addView(bar)
        moveHintBar = bar
    }

    private fun hideMoveChrome() {
        moveHintBar?.let { (it.parent as? ViewGroup)?.removeView(it) }
        moveHintBar = null
        stopMovePulse()
    }

    /** Gentle pulse on the grabbed tile so it reads as "held". */
    private fun startMovePulse(tile: View) {
        stopMovePulse()
        val pulse = object : Runnable {
            override fun run() {
                movePulseOn = !movePulseOn
                tile.scaleX = if (movePulseOn) 1.06f else 1.12f
                tile.scaleY = tile.scaleX
                tile.elevation = Ui.dp(this@MainActivity, 10).toFloat()
                tile.background = Ui.tileBackground(accent, movePulseOn, 16, this@MainActivity)
                handler.postDelayed(this, 450)
            }
        }
        movePulse = pulse
        movePulseOn = false
        handler.post(pulse)
    }

    private fun stopMovePulse() {
        movePulse?.let { handler.removeCallbacks(it) }
        movePulse = null
    }

    /** The grabbed tile, searched in the window that actually hosts it. */
    private fun findGrabbedTile(session: ActiveMove): View? {
        // Folder tiles live in the DIALOG's window, not the activity's —
        // v1.3 searched only the activity window, so folder sessions
        // never got their focus/pulse back after a re-render.
        val roots = listOfNotNull(
            if (session.container == MoveContainer.FOLDER) {
                currentFolderDialog?.window?.decorView
            } else null,
            window?.decorView
        )
        for (root in roots) {
            root.findViewWithTag<View>(session.machine.pkg)?.let { return it }
        }
        return null
    }

    /**
     * After any move-mode state change: re-render the container, put
     * focus back on the grabbed tile, and re-apply its grabbed look.
     */
    private fun rebuildAfterMove() {
        val session = moveSession ?: return
        if (session.container == MoveContainer.FOLDER) renderFolderOverlay() else rebuild()
        val tile = findGrabbedTile(session) ?: return
        tile.post { tile.requestFocus() }
        startMovePulse(tile)
    }

    /**
     * One key press inside move mode. Returns true when consumed
     * (everything is, while a session runs — the home screen must not
     * navigate away mid-move).
     */
    private fun handleMoveKey(keyCode: Int): Boolean {
        val session = moveSession ?: return false
        val dir = when (keyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> com.mizan.launcher.core.ArrowDir.LEFT
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> com.mizan.launcher.core.ArrowDir.RIGHT
            android.view.KeyEvent.KEYCODE_DPAD_UP -> com.mizan.launcher.core.ArrowDir.UP
            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> com.mizan.launcher.core.ArrowDir.DOWN
            else -> null
        }
        when {
            dir != null -> {
                // The machine swaps inside its own working copy; the
                // result is written into the CURRENT layout (resolved
                // at this moment) and persisted BEFORE the re-render,
                // which reloads the layout from prefs — so what renders
                // is exactly what was written.
                if (session.machine.move(dir)) {
                    applyMoveOrder(session, session.machine.currentOrder())
                    rebuildAfterMove()
                }
                // Edge no-ops are swallowed: focus stays on the tile.
            }
            keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
                keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                endMoveMode(commit = true)
                Toast.makeText(this, "Moved", Toast.LENGTH_SHORT).show()
            }
            keyCode == android.view.KeyEvent.KEYCODE_BACK -> endMoveMode(commit = false)
            else -> Unit // swallow other keys while moving
        }
        return true
    }

    /** Leave move mode: drop (keep order) or cancel (restore snapshot). */
    private fun endMoveMode(commit: Boolean) {
        val session = moveSession ?: return
        if (!commit) {
            // Restore the grab-time order into the CURRENT layout.
            applyMoveOrder(session, session.machine.snapshotOrder())
        }
        // On drop nothing is pending: every move was already written
        // into the layout (and prefs) as it happened.
        moveSession = null
        hideMoveChrome()
        if (session.container == MoveContainer.FOLDER) renderFolderOverlay() else rebuild()
        val tile = findGrabbedTile(session)
        tile?.post { tile.requestFocus() }
    }

    /**
     * "Add to folder…" picker for an APP (v1.5): every folder is a
     * legal target for an app (the depth/cycle rules constrain
     * folder-in-folder placement, not apps), so all folders are
     * offered — nested ones labelled by breadcrumb path so "Video"
     * under "Media" reads differently from a root "Video".
     */
    private fun chooseFolder(entry: AppEntry) {
        if (layout.folders.isEmpty()) {
            promptFolderName { name ->
                val f = Folder(
                    FolderTree.freshId(layout.folders), name,
                    mutableListOf(FolderEntry.App(entry.pkg))
                )
                layout.folders.add(f); saveLayout(); rebuild()
            }
            return
        }
        val names = layout.folders.map { folderPathLabel(it) } + "＋ New folder…"
        AlertDialog.Builder(this).setTitle("Add ${entry.label} to…")
            .setItems(names.toTypedArray()) { _, which ->
                if (which == layout.folders.size) {
                    promptFolderName { name ->
                        layout.folders.add(
                            Folder(
                                FolderTree.freshId(layout.folders), name,
                                mutableListOf(FolderEntry.App(entry.pkg))
                            )
                        )
                        saveLayout(); rebuild()
                    }
                } else {
                    val f = layout.folders[which]
                    if (!f.appPackages.contains(entry.pkg)) {
                        f.entries.add(FolderEntry.App(entry.pkg))
                    }
                    saveLayout(); rebuild()
                    Toast.makeText(this, "Added to ${f.name}", Toast.LENGTH_SHORT).show()
                }
            }.show()
    }

    // -------------------------------------------------------------- launching

    private fun launchWithPin(entry: AppEntry) {
        currentFolderDialog?.dismiss()
        currentFolderDialog = null
        gateLockedApp(entry.pkg, entry.label) { doLaunch(entry) }
    }

    /**
     * THE arrow-lock gate (v1.6: one shared funnel). Every launch path
     * that ends in some package goes through here — home/folder/
     * recents tiles (via launchWithPin), Continue Watching items whose
     * intent names a package, and HDMI input tiles (an input switch
     * starts an activity inside the input's own package). Until v1.6
     * the last two launched directly and bypassed the lock entirely.
     *
     * Not locked -> [onPass] runs immediately. Locked + no pattern
     * set -> FAIL-OPEN (v1.2 rule): prompt to SET a pattern first,
     * then run — the launcher never locks Haythem out of his own TV.
     * Locked + pattern -> the unlock prompt; success runs [onPass].
     */
    private fun gateLockedApp(pkg: String, label: String, onPass: () -> Unit) {
        if (!prefs.lockedApps.contains(pkg)) {
            onPass()
            return
        }
        val stored = prefs.arrowPin
        if (stored.isEmpty()) {
            ArrowPinDialog.showSetup(
                activity = this,
                title = "Set an arrow pattern to lock $label",
                accent = accent,
                onDone = { seq ->
                    prefs.arrowPin = seq
                    Toast.makeText(this, "Arrow lock set", Toast.LENGTH_SHORT).show()
                    onPass()
                }
            )
            return
        }
        // Arrow-sequence unlock — dots only, no digits anywhere.
        ArrowPinDialog.showUnlock(
            activity = this,
            title = "$label is locked",
            accent = accent,
            storedEncoded = stored,
            onSuccess = { onPass() },
            onWrongAttempt = { prefs.arrowAttempts = prefs.arrowAttempts + 1 }
        )
    }

    private fun doLaunch(entry: AppEntry) {
        try {
            val intent = AppLoader.launchIntent(packageManager, entry.pkg)
            if (intent == null) {
                Toast.makeText(this, "Couldn't launch ${entry.label}", Toast.LENGTH_SHORT).show()
                return
            }
            startActivity(intent)
            // v1.4: launches FROM Mizan feed the Recently Used row.
            prefs.recordLaunch(entry.pkg)
        } catch (e: Exception) {
            Prefs.noteError(this, "launch:${entry.pkg}", e)
            Toast.makeText(this, "Couldn't launch ${entry.label}", Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------ housekeeping (v1.4)

    private var lastHousekeepAt: Long = 0
    private var bingFetchInFlight: Boolean = false

    /**
     * Throttled tick work (called from the clock tick): Now Playing
     * change detection plus the rotation/Bing checks. Each piece is
     * fail-open; a changed Now Playing signature triggers one rebuild
     * so the row appears/disappears/updates without user action.
     */
    private fun housekeeping() {
        val now = System.currentTimeMillis()
        if (now - lastHousekeepAt < 15_000) return
        lastHousekeepAt = now
        try { checkRotationAndBing() } catch (e: Exception) {
            Prefs.noteError(this, "housekeeping:wallpaper", e)
        }
        try { checkNowPlayingChanged() } catch (e: Exception) {
            // Row refresh is cosmetic; never disturb the home screen.
        }
    }

    /**
     * The service-free wallpaper schedule: Reddit rotation due-check
     * and the Bing calendar-day check. Called on resume and on the
     * (throttled) clock tick. No timers, no services, no alarms.
     */
    private fun checkRotationAndBing() {
        if (!::prefs.isInitialized || prefs.safeMode) return
        val now = System.currentTimeMillis()
        // Reddit auto-rotation: due → advance to the next candidate.
        if (prefs.wallpaperKind == "reddit" &&
            RotationLogic.isDue(now, prefs.redditRotationLast, prefs.redditRotation)
        ) {
            WallpaperRotator.advanceReddit(this, prefs) { rebuild() }
        }
        // Bing daily: a new fetch only when the calendar day changed
        // (or today's file is missing). The cached file for today is
        // applied directly, with no network at all.
        if (prefs.wallpaperKind == "bing") {
            val today = com.mizan.launcher.core.BingWallpaper.dayKey(now)
            val cached = if (prefs.bingCacheDate == today && prefs.bingCachePath.isNotEmpty()) {
                java.io.File(prefs.bingCachePath)
            } else null
            if (cached != null && cached.exists()) {
                if (prefs.wallpaperValue != cached.absolutePath) {
                    prefs.wallpaperValue = cached.absolutePath
                    AccentUtil.recompute(this, prefs)
                    rebuild()
                }
            } else if (!bingFetchInFlight) {
                bingFetchInFlight = true
                BingFetcher.fetchToday(this, today) { file ->
                    runOnUiThread {
                        bingFetchInFlight = false
                        if (file != null) {
                            prefs.bingCacheDate = today
                            prefs.bingCachePath = file.absolutePath
                            prefs.wallpaperValue = file.absolutePath
                            AccentUtil.recompute(this, prefs)
                            rebuild()
                        }
                    }
                }
            }
        }
    }

    /** Rebuild home when the Now Playing card set changed under us. */
    private fun checkNowPlayingChanged() {
        if (prefs.safeMode || !layout.isRowVisible("nowplaying")) return
        if (!NowPlayingData.isGranted(this)) {
            lastNowPlayingSignature = ""
            return
        }
        val sig = nowPlayingSignature(NowPlayingData.activeCards(this).map { it.card })
        val last = lastNowPlayingSignature
        if (last == null) {
            lastNowPlayingSignature = sig
        } else if (sig != last) {
            rebuild() // buildNowPlayingSection refreshes the signature
        }
    }

    // -------------------------------------------------------------- top bar data

    private fun updateClock() {
        try {
            val now = Date()
            val cfg = if (prefs.safeMode) {
                com.mizan.launcher.core.StatusBarConfig()
            } else {
                layout.statusBar
            }
            // v1.7: the iPad strip's date is fixed iPadOS-style
            // ("Tuesday, October 21") — the profile's date-format
            // preference belongs to the TV hero. The clock keeps the
            // profile's format in both styles.
            val datePattern = if (isIpadStyle()) "EEEE, MMMM d" else cfg.datePattern()
            dateView?.text = SimpleDateFormat(datePattern, Locale.getDefault()).format(now)
            clockView?.text = SimpleDateFormat(cfg.clockPattern(), Locale.getDefault()).format(now)
            networkView?.text = networkLabel()
        } catch (e: Exception) {
            // Clock is cosmetic; never let it take the launcher down.
        }
    }

    /**
     * Status-bar network text. v1.3: on Wi-Fi, the connected network's
     * name rides next to the icon when Android will share it
     * (WifiLabel handles "<unknown ssid>" → generic label). Many Google
     * TV builds hide the SSID unless location permission is granted —
     * we never ask for it from the home screen; the label simply falls
     * back to "● Wi-Fi" (fail-open, same as before v1.3). Refreshed on
     * the existing clock tick + onResume (updateClock path).
     */
    private fun networkLabel(): String {
        return try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
                ?: return WifiLabel.label(WifiLabel.Kind.OFFLINE, null)
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                    val wm = applicationContext.getSystemService(WIFI_SERVICE) as? WifiManager
                    @Suppress("DEPRECATION")
                    val ssid = try { wm?.connectionInfo?.ssid } catch (e: Exception) { null }
                    WifiLabel.label(WifiLabel.Kind.WIFI, ssid)
                }
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ->
                    WifiLabel.label(WifiLabel.Kind.ETHERNET, null)
                else -> WifiLabel.label(WifiLabel.Kind.OTHER, null)
            }
        } catch (e: Exception) {
            ""
        }
    }

    private fun refreshWeather() {
        if (prefs.safeMode) return
        // The fetch feeds BOTH the status-bar widget (when present) and
        // the weather background (when that wallpaper kind is chosen).
        val weatherView = findWeatherView()
        if (weatherView == null && prefs.wallpaperKind != "weather") return
        val cached = prefs.cachedWeather
        if (weatherView != null && cached.isNotEmpty()) weatherView.text = cached
        val stale = System.currentTimeMillis() - prefs.lastWeatherFetch > 30 * 60 * 1000
        if (!stale) {
            weatherView?.visibility = if (cached.isEmpty()) View.GONE else View.VISIBLE
            return
        }
        Weather.fetch(prefs.weatherLat, prefs.weatherLon, prefs.weatherUnits) { snap ->
            runOnUiThread {
                try {
                    if (snap != null) {
                        prefs.cachedWeather = snap.text
                        prefs.weatherData = Weather.encodeSnapshot(snap)
                        prefs.lastWeatherFetch = System.currentTimeMillis()
                        val v = findWeatherView()
                        if (v != null) {
                            v.text = snap.text
                            v.visibility = View.VISIBLE
                        }
                        // Fresh conditions may change the background.
                        if (prefs.wallpaperKind == "weather") rebuild()
                    } else if (cached.isEmpty()) {
                        findWeatherView()?.visibility = View.GONE // hide quietly offline (fail-open)
                    }
                } catch (e: Exception) {
                    // Widget/background only — ignore.
                }
            }
        }
    }

    private fun findWeatherView(): TextView? {
        return try {
            val root = window?.decorView as? ViewGroup ?: return null
            findTagged(root, "weather") as? TextView
        } catch (e: Exception) { null }
    }

    private fun findTagged(group: ViewGroup, tag: String): View? {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child.tag == tag) return child
            if (child is ViewGroup) findTagged(child, tag)?.let { return it }
        }
        return null
    }

    /** App list cache so resume-rebuilds don't re-query PackageManager every time. */
    object AppCache {
        private var cached: List<AppEntry>? = null
        private var at: Long = 0
        fun get(activity: Activity): List<AppEntry> {
            val now = System.currentTimeMillis()
            val c = cached
            if (c != null && now - at < 30_000) return c
            val fresh = AppLoader.loadApps(activity.packageManager, activity.packageName)
            cached = fresh; at = now
            DiagCache.appsCount = fresh.size
            return fresh
        }
    }

    /** Values the diagnostics screen reads (apps/inputs/watchnext counts). */
    object DiagCache {
        var appsCount: Int = 0
        var inputsCount: Int = 0
        var watchNextCount: Int = 0
        /** v1.4: Now Playing diagnostics — grant state + sessions with a title. */
        var nowPlayingGranted: Boolean = false
        var nowPlayingCount: Int = 0
    }
}
