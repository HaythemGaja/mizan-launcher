package com.mizan.launcher.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.mizan.launcher.MainActivity
import com.mizan.launcher.core.Folder
import com.mizan.launcher.core.LayoutData
import com.mizan.launcher.core.SettingsFit
import com.mizan.launcher.core.SettingsNavMachine
import com.mizan.launcher.core.Themes
import com.mizan.launcher.core.WeatherUnits
import com.mizan.launcher.data.AccentUtil
import com.mizan.launcher.data.AppLoader
import com.mizan.launcher.data.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Settings — two columns, Roku style (v1.2).
 *
 * LEFT column: the category list. The selected category is highlighted
 * persistently (not just a focus ring) and its controls fill the RIGHT
 * column, which scrolls independently. Navigation is coordinated by the
 * pure core/SettingsNavMachine:
 * - Focus in the list selects categories live as it moves (Roku-style);
 *   RIGHT or OK moves focus into the detail pane.
 * - LEFT from the detail pane, or BACK from the detail pane, returns
 *   focus to the category list; BACK in the list exits Settings.
 * - Focus memory: each category remembers its last focused control;
 *   returning to the category restores it (falling back to its first
 *   control when nothing was focused there yet).
 * - On narrow/portrait windows (width < 600dp, e.g. a sideloaded phone)
 *   the columns STACK: the category strip sits on top, details below.
 *
 * v1.3: sizes come from core/SettingsFit (window-derived; compact mode
 * on short screens), BOTH panes scroll independently with the focused
 * control auto-scrolled into view, and every boolean is an iOS-style
 * switch row (ui/SwitchRow.kt). Nav-machine behaviour is unchanged,
 * except LEFT/RIGHT on a focused switch toggles it (BACK returns).
 *
 * v1.5 ENTRY GATE: onCreate asks core/SettingsGate BEFORE any content
 * is built. The global "Lock Settings" switch and the v1.4 Guest rule
 * merge into ONE arrow-pattern prompt; the correct pattern builds the
 * UI, Back/cancel finishes back to home. The pass is remembered for
 * THIS entry only ([entered], reset in onDestroy): rebuilds and
 * resumes within the entry never re-prompt, but the next entry does.
 * Because the gate lives here — not at the home screen's entry
 * points — the gear tile, the corner gear, and watcher deep-links
 * are all covered by the same single prompt.
 *
 * v1.5 REORGANIZATION: eight categories (Home & launcher, Look &
 * wallpaper, Home screen, Status bar, Weather, Lock & profiles, Now
 * playing, Data & about). Every v1.4 control survives somewhere — the
 * old → new mapping table is spec §13.4. The Cleanup category is gone
 * (feature removed); its one surviving action, Speed up, lives in
 * Home & launcher.
 *
 * The version label sits at the top of the left column and in Data &
 * about, so it is always reachable for pasting back.
 *
 * Request codes: 41 export backup, 42 import backup, 43 pick wallpaper image.
 */
class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var layout: LayoutData
    private var accent: Int = 0
    private var textColour: Int = 0
    private var subColour: Int = 0

    /** True while the "Make Mizan your home screen" checklist replaces the home category's detail. */
    private var showingMakeHome: Boolean = false

    // ------------------------------------------------ two-column state

    /** Category ids + titles, in left-column order (v1.5 reorganization). */
    private val categories: List<Pair<String, String>> = listOf(
        "home" to "Home & launcher",
        "look" to "Look & wallpaper",
        "homescreen" to "Home screen",
        "statusbar" to "Status bar",
        "weather" to "Weather",
        "lockprofiles" to "Lock & profiles",
        "nowplaying" to "Now playing",
        "data" to "Data & about"
    )

    /** v1.6: one glyph per category in the nav list (visual anchors). */
    private val categoryGlyphs: Map<String, String> = mapOf(
        "home" to "🏠", "look" to "🎨", "homescreen" to "▦", "statusbar" to "🕐",
        "weather" to "🌦", "lockprofiles" to "🔒", "nowplaying" to "♪", "data" to "💾"
    )

    /** v1.6: one-line description under the category name in the detail header. */
    private val categoryDescriptions: Map<String, String> = mapOf(
        "home" to "Default home screen, the watcher, and Speed up",
        "look" to "Theme, wallpaper, icons — and the Reddit source",
        "homescreen" to "Home rows, hidden apps, and folders",
        "statusbar" to "The top bar: clock, date, weather, quick tiles",
        "weather" to "Location and units for the home weather",
        "lockprofiles" to "Arrow pattern, app locks, profiles, Guest",
        "nowplaying" to "What's playing in other apps, shown at home",
        "data" to "Backup, safe mode, and diagnostics"
    )

    /** Tag marking a detail view as a card title (see groupDetailIntoCards). */
    private companion object { const val CARD_HEADER_TAG = "cardHeader" }

    /**
     * True once the v1.5 entry gate has passed for THIS entry. Reset
     * in onDestroy so the next entry prompts again; rebuilds/resumes
     * within the entry see it true and never re-prompt.
     */
    private var entered: Boolean = false

    private lateinit var nav: SettingsNavMachine

    /** Which column currently holds focus (SettingsNavMachine.COLUMN_*). */
    private var focusedPane: Int = SettingsNavMachine.COLUMN_LEFT

    /** Last focused control title per category id (focus memory). */
    private val detailFocusMemory = mutableMapOf<String, String>()

    /** Views of the current build, for focus restore after rebuild(). */
    private var navRows: List<TextView> = emptyList()
    private var detailFocusable: List<View> = emptyList()

    /** True while detail-pane rows are being built (row() tags them). */
    private var buildingDetail: Boolean = false

    /**
     * v1.3 fit metrics for the CURRENT window (core/SettingsFit):
     * recomputed on every rebuild so paddings/text/left-pane width
     * always derive from the real screen size — nothing is clipped on
     * short TVs, and both panes scroll independently.
     */
    private var metrics: SettingsFit.Metrics = SettingsFit.compute(1920, 1080, 1f)

    /** The two pane scrollers (kept for auto-scroll-into-view). */
    private var navScroll: ScrollView? = null
    private var detailScrollView: ScrollView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        // v1.5 entry gate — BEFORE any settings content is built, so
        // every entry point (gear tile, corner gear, watcher
        // deep-links) is covered by this one prompt. The global Lock
        // Settings switch and the Guest rule merge here
        // (core/SettingsGate): never two prompts for one entry.
        val decision = com.mizan.launcher.core.SettingsGate.decide(
            lockSettings = prefs.lockSettings,
            guestActive = com.mizan.launcher.core.GuestGate.isGuestActive(
                prefs.currentProfile, prefs.guestProfile
            ),
            guestLockSettings = prefs.guestLockSettings,
            patternSet = prefs.arrowPin.isNotEmpty(),
            unlockedThisEntry = false
        )
        if (decision == com.mizan.launcher.core.SettingsGate.Decision.REQUIRE_PATTERN &&
            prefs.withinUnlockGrace()
        ) {
            // v1.9 unlock grace: a successful pattern verification
            // at ANY gate (locked app/folder, guest exit, an earlier
            // Settings entry) inside this profile's window covers
            // this entry too — no second prompt. Profile switches
            // clear the stamp, so this can never be a previous
            // profile's grace leaking in.
            enterSettings()
        } else if (decision == com.mizan.launcher.core.SettingsGate.Decision.REQUIRE_PATTERN) {
            // Blank content behind the prompt: nothing of Settings
            // exists or is reachable until the pattern is entered.
            // Wrong attempts feed the diagnostics counter, exactly
            // like app/folder unlocks. Back/cancel → back to home.
            setContentView(View(this))
            val preset = Themes.preset(prefs.themeId)
            val gateAccent = if (preset.dynamic && prefs.accentOverride != 0) {
                prefs.accentOverride
            } else preset.accent
            ArrowPinDialog.showUnlock(
                this, "Enter pattern to open Settings", gateAccent, prefs.arrowPin,
                onSuccess = {
                    // v1.9: a successful verification stamps the
                    // device-level grace clock (Prefs.lastUnlockAt).
                    prefs.stampUnlock()
                    enterSettings()
                },
                onWrongAttempt = { prefs.arrowAttempts = prefs.arrowAttempts + 1 },
                onCancel = { finish() }
            )
        } else {
            enterSettings()
        }
    }

    /** Build the settings UI — called once the entry gate has passed. */
    private fun enterSettings() {
        entered = true
        nav = SettingsNavMachine(categories.map { it.first })
        rebuild()
    }

    override fun onDestroy() {
        // Re-arm the entry gate: "unlocked this entry" dies with the
        // activity instance, so re-entering Settings after finishing
        // prompts again (configuration rebuilds within the entry do
        // not pass through here without a finish in between).
        entered = false
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        // Before the gate passes there is no UI to rebuild (and the
        // blank gate view must stay untouched behind the prompt).
        if (entered && ::prefs.isInitialized) rebuild()
    }

    // ---------------------------------------------------------------- layout

    private fun rebuild() {
        layout = prefs.layout
        val preset = Themes.preset(prefs.themeId)
        textColour = preset.text; subColour = preset.subText
        accent = if (preset.dynamic && prefs.accentOverride != 0) prefs.accentOverride else preset.accent

        // v1.3: every size derives from the actual window.
        val dm = resources.displayMetrics
        metrics = SettingsFit.compute(dm.widthPixels, dm.heightPixels, dm.density)
        val stacked = metrics.stacked

        val root = LinearLayout(this)
        root.orientation = if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        root.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(preset.bgTop, preset.bgBottom)
        )

        // Left pane in its OWN ScrollView (v1.3): on short screens the
        // category list scrolls instead of running off the bottom.
        val navPane = buildNavPane(stacked)
        if (stacked) {
            navScroll = null
            root.addView(navPane)
        } else {
            val scroller = ScrollView(this)
            scroller.isFillViewport = true
            scroller.addView(navPane)
            scroller.layoutParams = LinearLayout.LayoutParams(Ui.dp(this, metrics.leftPaneWidthDp), -1)
            navScroll = scroller
            root.addView(scroller)
        }

        val detailScroll = ScrollView(this)
        detailScroll.isFillViewport = true
        val detail = LinearLayout(this)
        detail.orientation = LinearLayout.VERTICAL
        // Bottom padding >= one row height: the last control can
        // always scroll fully into view (never clipped by the edge).
        detail.setPadding(
            Ui.dp(this, 40), Ui.dp(this, metrics.panePadDp), Ui.dp(this, 48),
            Ui.dp(this, metrics.detailBottomPadDp)
        )
        detailScroll.addView(detail)
        if (stacked) {
            detailScroll.layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        } else {
            detailScroll.layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
        }
        detailScrollView = detailScroll
        root.addView(detailScroll)

        // v1.6: the detail pane opens with the category name + a
        // one-line description, then the category's controls grouped
        // into cards (groupDetailIntoCards, keyed off header() tags).
        detail.addView(detailHeader(nav.selectedId))
        buildingDetail = true
        detailFocusable = emptyList()
        buildDetail(detail, nav.selectedId)
        buildingDetail = false
        groupDetailIntoCards(detail, firstContentIndex = 1)

        setContentView(root)
        restoreFocus(root)
    }

    /** After a rebuild, put focus back where the user was (see class doc). */
    private fun restoreFocus(root: View) {
        root.post {
            try {
                var placed = false
                if (focusedPane == SettingsNavMachine.COLUMN_RIGHT) {
                    val remembered = detailFocusMemory[nav.selectedId]
                    val target = detailFocusable.firstOrNull { it.tag == remembered }
                        ?: detailFocusable.firstOrNull()
                    if (target != null) {
                        target.requestFocus()
                        scrollIntoView(target)
                        placed = true
                    } else {
                        // No detail controls (shouldn't happen) — fall back to the list.
                        focusedPane = SettingsNavMachine.COLUMN_LEFT
                    }
                }
                if (!placed) {
                    val row = navRows.getOrNull(nav.selectedIndex)
                    row?.requestFocus()
                    if (row != null) scrollIntoView(row)
                }
            } catch (e: Exception) {
                // Focus placement must never crash Settings.
            }
        }
    }

    /**
     * Scroll [view] fully into view inside whichever pane scroller
     * contains it (v1.3). requestRectangleOnScreen walks up to the
     * nearest scrollable ancestor — the standard way to guarantee a
     * focused control is never left below the fold.
     */
    private fun scrollIntoView(view: View) {
        view.post {
            try {
                view.requestRectangleOnScreen(
                    android.graphics.Rect(0, 0, view.width, view.height), false
                )
            } catch (e: Exception) {
                // Cosmetic only — scrolling must never crash Settings.
            }
        }
    }

    /** Left column: title + version + the category list. */
    private fun buildNavPane(stacked: Boolean): View {
        val pane = LinearLayout(this)
        pane.orientation = LinearLayout.VERTICAL
        pane.setPadding(
            Ui.dp(this, metrics.panePadDp), Ui.dp(this, metrics.panePadDp),
            Ui.dp(this, 20), Ui.dp(this, metrics.panePadDp)
        )
        if (stacked) {
            pane.layoutParams = LinearLayout.LayoutParams(-1, -2)
        } else {
            // Width comes from the wrapping ScrollView (fit metrics).
            pane.layoutParams = LinearLayout.LayoutParams(-1, -2)
            pane.setBackgroundColor(0x33000000)
        }

        val title = TextView(this)
        title.text = "Settings"
        title.setTextColor(accent)
        title.textSize = metrics.headerSp + 2f
        title.setTypeface(title.typeface, android.graphics.Typeface.BOLD)
        pane.addView(title)
        val version = TextView(this)
        version.text = "Mizan Launcher v1.9"
        version.setTextColor(subColour)
        version.textSize = 15f
        version.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, metrics.rowPadVDp + 2))
        pane.addView(version)

        val list = LinearLayout(this)
        list.orientation = if (stacked) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        pane.addView(list)

        val rows = mutableListOf<TextView>()
        categories.forEachIndexed { index, (_, titleText) ->
            val tv = navRow(index, titleText)
            rows.add(tv)
            list.addView(tv)
        }
        navRows = rows
        if (stacked) {
            val scroller = android.widget.HorizontalScrollView(this)
            // Re-wrap: horizontal strip scrolls sideways on narrow windows.
            (list.parent as? LinearLayout)?.removeView(list)
            scroller.addView(list)
            pane.addView(scroller)
        }
        return pane
    }

    /**
     * One category row. Selected = persistently highlighted (accent
     * stroke, whether or not it has focus). Gaining focus selects the
     * category live (Roku-style) and rebuilds the detail; losing the
     * selection keeps the highlight rule in sync via the focus hook.
     */
    private fun navRow(index: Int, titleText: String): TextView {
        val tv = TextView(this)
        // v1.6: every category row carries its glyph; heights are one
        // consistent value from the fit metrics (taller when roomy).
        val glyph = categoryGlyphs[categories[index].first]
        tv.text = if (glyph != null) "$glyph  $titleText" else titleText
        tv.setTextColor(textColour)
        tv.textSize = metrics.navTextSp
        tv.isFocusable = true
        tv.isClickable = true
        tv.minHeight = Ui.dp(this, if (metrics.compact) 48 else 56)
        tv.setPadding(Ui.dp(this, 16), Ui.dp(this, metrics.rowPadVDp), Ui.dp(this, 16), Ui.dp(this, metrics.rowPadVDp))
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.setMargins(0, Ui.dp(this, 3), 0, Ui.dp(this, 3))
        tv.layoutParams = lp
        applyNavRowStyle(tv, index, focused = false)
        Ui.focusLift(tv, accent, 12) { hasFocus ->
            applyNavRowStyle(tv, index, hasFocus)
            if (hasFocus) {
                scrollIntoView(tv)
                focusedPane = SettingsNavMachine.COLUMN_LEFT
                nav.noteFocus(SettingsNavMachine.COLUMN_LEFT)
                if (nav.selectedIndex != index) {
                    // Posted, not synchronous: swapping the whole view
                    // hierarchy from inside a focus-change dispatch is
                    // fragile; a frame later is indistinguishable.
                    tv.post {
                        if (nav.selectedIndex != index) {
                            nav.select(index)
                            if (nav.selectedId != "home") showingMakeHome = false
                            rebuild()
                        }
                    }
                }
            }
        }
        tv.setOnClickListener {
            // OK / tap on a category: select it AND enter its detail.
            nav.select(index)
            if (nav.selectedId != "home") showingMakeHome = false
            nav.enterDetail()
            focusedPane = SettingsNavMachine.COLUMN_RIGHT
            rebuild()
        }
        return tv
    }

    /** Persistent selection highlight for nav rows (focus adds the lift on top). */
    private fun applyNavRowStyle(tv: TextView, index: Int, focused: Boolean) {
        val selected = nav.selectedIndex == index
        tv.background = when {
            selected -> {
                // Accent fill tint + stroke: visible even unfocused.
                val d = android.graphics.drawable.GradientDrawable()
                d.cornerRadius = Ui.dp(this, 12).toFloat()
                d.setColor((accent and 0x00FFFFFF) or 0x66000000)
                d.setStroke(Ui.dp(this, 3), accent)
                d
            }
            focused -> Ui.tileBackground(accent, true, 12, this)
            else -> Ui.tileBackground(accent, false, 12, this)
        }
        tv.setTypeface(tv.typeface, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
    }

    /** Deterministic LEFT/RIGHT pane transitions + BACK handled in onBackPressed. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Behind the entry-gate prompt there is no UI to navigate.
        if (!entered) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (focusedPane == SettingsNavMachine.COLUMN_LEFT) {
                        nav.enterDetail()
                        focusedPane = SettingsNavMachine.COLUMN_RIGHT
                        val remembered = detailFocusMemory[nav.selectedId]
                        val target = detailFocusable.firstOrNull { it.tag == remembered }
                            ?: detailFocusable.firstOrNull()
                        target?.requestFocus()
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    // v1.3 exception: LEFT/RIGHT on a focused switch
                    // TOGGLES it (iOS-style) instead of jumping back to
                    // the category list — the switch row consumes the
                    // key itself. BACK still returns to the list.
                    if (focusedPane == SettingsNavMachine.COLUMN_RIGHT &&
                        currentFocus !is SwitchRowView
                    ) {
                        focusNavList()
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** Move focus back to the selected category row in the left column. */
    private fun focusNavList() {
        nav.backToList()
        focusedPane = SettingsNavMachine.COLUMN_LEFT
        val row = navRows.getOrNull(nav.selectedIndex)
        row?.requestFocus()
        if (row != null) scrollIntoView(row)
    }

    override fun onBackPressed() {
        if (!entered) {
            // The gate prompt handles its own Back (cancel → finish);
            // if a Back reaches the activity anyway, just leave.
            @Suppress("DEPRECATION")
            super.onBackPressed()
            return
        }
        if (showingMakeHome) {
            showingMakeHome = false
            rebuild()
            return
        }
        if (focusedPane == SettingsNavMachine.COLUMN_RIGHT || nav.inDetail) {
            focusNavList()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    // ------------------------------------------------------------ detail panes

    private fun buildDetail(content: LinearLayout, id: String) {
        when (id) {
            "home" -> buildHomeDetail(content)
            "look" -> {
                buildLookDetail(content)
                buildRedditDetail(content)
            }
            "homescreen" -> buildHomeScreenDetail(content)
            "statusbar" -> buildStatusBarDetail(content)
            "weather" -> buildWeatherDetail(content)
            "lockprofiles" -> buildLockProfilesDetail(content)
            "nowplaying" -> buildNowPlayingDetail(content)
            "data" -> buildDataDetail(content)
            else -> {
                content.addView(header("Settings"))
                content.addView(info("Nothing here."))
            }
        }
    }

    /** "Home & launcher": the takeover checklist, the watcher, Speed up. */
    private fun buildHomeDetail(content: LinearLayout) {
        if (showingMakeHome) {
            buildMakeHome(content)
            return
        }
        content.addView(header("Home & launcher"))
        content.addView(row("Home screen setup", homeSetupSubtitle()) {
            showingMakeHome = true
            rebuild()
        })
        content.addView(row("Home watcher (accessibility)", "Optional, OFF unless you enable it. See explanation.") { watcherInfo() })

        // Speed up (v1.4's Cleanup action, kept in v1.5 at Haythem's
        // direction and relocated here — Cleanup itself is gone). The
        // last result line shows under the button, as it did there.
        content.addView(header("Speed up"))
        content.addView(row(
            "⚡ Speed up now",
            prefs.speedupLastResult.ifEmpty { "Ask background apps to close and report the free RAM before/after" }
        ) { runSpeedUp() })
        content.addView(info("Speed up signals OTHER apps' background processes to exit (it disables and uninstalls nothing, and Android may restart what it wants — the numbers are informational). The never-kill list, system apps, the foreground app, and Mizan itself are never signalled."))
    }

    /**
     * The Speed up action (spec §12.4 behaviour, new home): read free
     * RAM, signal the pure kill list (core/SpeedUp) with
     * killBackgroundProcesses, wait ~1.2 s for processes to die, read
     * free RAM again, and persist + show the result line.
     */
    private fun runSpeedUp() {
        try {
            val am = getSystemService(android.app.ActivityManager::class.java)
            val candidates = packageManager.getInstalledPackages(0).map { pi ->
                com.mizan.launcher.core.SpeedUp.Candidate(
                    pi.packageName,
                    (pi.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                )
            }
            // Best-effort foreground detection: the first visible
            // foreground process that isn't us. Modern Android mostly
            // only reports our own processes here — null is fine
            // (killList then excludes nothing extra).
            val foreground = try {
                am.runningAppProcesses?.firstOrNull {
                    it.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND &&
                        it.processName != packageName
                }?.processName
            } catch (e: Exception) { null }
            val kill = com.mizan.launcher.core.SpeedUp.killList(candidates, packageName, foreground)
            val before = freeRamMb(am)
            for (pkg in kill) {
                try { am.killBackgroundProcesses(pkg) } catch (e: Exception) {
                    // Individual failures don't stop the sweep.
                }
            }
            toast("Speeding up…")
            android.os.Handler(mainLooper).postDelayed({
                try {
                    val after = freeRamMb(am)
                    val line = "Before $before MB free → After $after MB free (${kill.size} apps signalled)"
                    prefs.speedupLastResult = line
                    toast(line)
                    if (entered) rebuild()
                } catch (e: Exception) {
                    failOpen("speedUpResult", e)
                }
            }, 1200)
        } catch (e: Exception) {
            failOpen("speedUp", e)
        }
    }

    /** Available RAM in whole MB, for the Speed up result line. */
    private fun freeRamMb(am: android.app.ActivityManager): Long {
        val mi = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return mi.availMem / (1024L * 1024L)
    }

    /** The v1.4 Reddit category, folded into Look & wallpaper (v1.5). */
    private fun buildRedditDetail(content: LinearLayout) {
        content.addView(header("Wallpaper source — Reddit"))
                content.addView(row("Reddit wallpapers", "Top of the week from your subreddits") { redditPicker() })
                // v1.6: fetch status is VISIBLE — v1.1–v1.5 failed
                // silently and the feature just looked broken.
                content.addView(info(
                    "Last try: " + prefs.redditLastStatus.ifEmpty {
                        "no fetch yet — tap “Reddit wallpapers” to fetch"
                    }
                ))
                content.addView(row("Subreddits", prefs.redditSubs) { editSubreddits() })
                content.addView(toggleRow("Allow NSFW results", prefs.redditAllowNsfw, "Off by default — over_18 posts are skipped") {
                    prefs.redditAllowNsfw = it
                    rebuild()
                })
                content.addView(header("Auto-rotation"))
                content.addView(row(
                    "Wallpaper rotation",
                    com.mizan.launcher.core.RotationLogic.labelFor(prefs.redditRotation) +
                        " — while the wallpaper source is Reddit"
                ) {
                    val keys = com.mizan.launcher.core.RotationLogic.KEYS
                    val labels = keys.map { com.mizan.launcher.core.RotationLogic.labelFor(it) }
                    pickFrom(
                        labels,
                        com.mizan.launcher.core.RotationLogic.labelFor(prefs.redditRotation)
                    ) { chosen ->
                        prefs.redditRotation =
                            keys[labels.indexOf(chosen).coerceAtLeast(0)]
                        // A new cadence starts from now, not from the last rotation.
                        prefs.redditRotationLast = System.currentTimeMillis()
                        rebuild()
                    }
                })
                content.addView(row("Next wallpaper", "Jump to the next Reddit wallpaper now") {
                    toast("Finding the next wallpaper…")
                    com.mizan.launcher.data.WallpaperRotator.advanceReddit(this, prefs) {
                        toast("Wallpaper updated")
                        rebuild()
                    }
                })
                content.addView(info("Rotation is checked when the home screen wakes or its clock ticks — there is no background service or timer. The Bing daily picture (Wallpaper → Bing daily picture) changes with the calendar day instead."))
    }

    /** Weather: location + units, everything weather in one place (v1.5). */
    private fun buildWeatherDetail(content: LinearLayout) {
        content.addView(header("Weather"))
                val units = prefs.weatherUnits
                content.addView(row(
                    "Units",
                    if (units == WeatherUnits.FAHRENHEIT) "Fahrenheit — °F, wind in mph" else "Celsius — °C, wind in km/h"
                ) {
                    pickFrom(
                        listOf("Fahrenheit (°F)", "Celsius (°C)"),
                        if (units == WeatherUnits.FAHRENHEIT) "Fahrenheit (°F)" else "Celsius (°C)"
                    ) { v ->
                        prefs.weatherUnitsKey =
                            if (v.startsWith("Celsius")) WeatherUnits.CELSIUS.key else WeatherUnits.FAHRENHEIT.key
                        // Cached readings are in the old units — drop them.
                        prefs.cachedWeather = ""
                        prefs.weatherData = ""
                        prefs.lastWeatherFetch = 0
                        toast("Units set — weather will refresh")
                        rebuild()
                    }
                })
                content.addView(row("Weather location", "${prefs.weatherLat}, ${prefs.weatherLon} (Bloomington, IN default)") { weatherLocation() })
                content.addView(info("Weather comes from Open-Meteo (no account or key). The weather background (Wallpaper → Weather) follows the same data."))
                content.addView(info("Forecast: press OK on the weather widget on the home screen to open the full forecast — the next 24 hours and the 7-day outlook, in these units."))
    }

    /**
     * Lock & profiles (v1.5): the v1.4 "Arrow lock" and "Profiles"
     * categories merged — pattern set/clear, the Lock Settings switch,
     * locked-apps manager, profile switching, and the Guest controls.
     */
    private fun buildLockProfilesDetail(content: LinearLayout) {
        content.addView(header("Arrow lock"))
        content.addView(info("Locked apps ask for a sequence of remote arrows (↑↓←→, 4–8 steps) before opening. No numbers, no keyboard."))
        content.addView(row(
            "Set / change arrow pattern",
            if (prefs.arrowPin.isEmpty()) "No pattern set" else "Pattern is set — enter it first to change it"
        ) { setArrowLock() })
        content.addView(row(
            "Clear arrow pattern",
            if (prefs.arrowPin.isEmpty()) "No pattern to clear" else "Remove the pattern (asks for it first)"
        ) { clearArrowLock() })
        content.addView(row("Locked apps", "${prefs.lockedApps.size} locked") { lockManager() })

        content.addView(header("Settings lock"))
        content.addView(toggleRow(
            "Lock Settings", prefs.lockSettings,
            "Settings asks for the arrow pattern every time it opens"
        ) { on ->
            if (on && prefs.arrowPin.isEmpty()) {
                // Enabling with no pattern runs the set-pattern flow
                // first (the standing fail-open rule); until a pattern
                // exists the switch stays off.
                ArrowPinDialog.showSetup(this, "Set a pattern to lock Settings", accent, onDone = { seq ->
                    prefs.arrowPin = seq
                    prefs.lockSettings = true
                    toast("Settings lock on")
                    rebuild()
                })
                rebuild()
            } else {
                prefs.lockSettings = on
                rebuild()
            }
        })
        content.addView(info("Forgot the pattern? Recovery is Android system Settings → Apps → Mizan Launcher → Clear data — that resets the layout too. This is a casual lock, by design."))
        content.addView(info("Folder locks live in the Folders manager (Home screen category) or any folder's hold-OK menu; app locks in the Locked apps list above or any app's hold-OK menu."))
        content.addView(header("Unlock grace"))
        content.addView(row(
            "Re-ask for pattern",
            com.mizan.launcher.core.UnlockGrace.labelFor(prefs.unlockGraceMinutes)
        ) {
            pickFrom(
                com.mizan.launcher.core.UnlockGrace.WINDOW_LABELS,
                com.mizan.launcher.core.UnlockGrace.labelFor(prefs.unlockGraceMinutes)
            ) { v ->
                val idx = com.mizan.launcher.core.UnlockGrace.WINDOW_LABELS.indexOf(v)
                if (idx >= 0) {
                    prefs.unlockGraceMinutes =
                        com.mizan.launcher.core.UnlockGrace.WINDOWS_MIN[idx]
                }
                rebuild()
            }
        })
        content.addView(info("After you enter the pattern once — for a locked app, a folder, Settings, or leaving Guest — the other gates open without asking until this time is up. Switching profiles always asks again. “Every time” is how it worked before v1.9."))
        content.addView(header("Profiles"))
                content.addView(row("Profile", "${prefs.profileDisplayName(prefs.currentProfile)} — tap to switch") {
                    // v1.9: switchProfile clears the grace stamp.
                    prefs.switchProfile(if (prefs.currentProfile == 1) 2 else 1)
                    toast("Switched to ${prefs.profileDisplayName(prefs.currentProfile)}")
                    rebuild()
                })
                content.addView(info("Each profile keeps its own theme, wallpaper, layout, status bar, weather units and hidden apps. The arrow lock is shared."))
                content.addView(row("Rename Profile 1", prefs.profileDisplayName(1)) { renameProfile(1) })
                content.addView(row("Rename Profile 2", prefs.profileDisplayName(2)) { renameProfile(2) })
                content.addView(info("Names show on the profile tile, in the switch menu, and on the Guest pill. Saving an empty name brings back the default — “Guest” for the guest profile, “Profile N” otherwise. Renaming never changes the Guest flag or any lock."))
                content.addView(header("Guest profile"))
                content.addView(row(
                    "Guest profile",
                    when (prefs.guestProfile) {
                        1 -> "${prefs.profileDisplayName(1)} is Guest"
                        2 -> "${prefs.profileDisplayName(2)} is Guest"
                        else -> "None — no guest profile"
                    }
                ) {
                    // v1.9: the picker speaks in display names (a
                    // renamed profile is picked by its name); the
                    // choice maps back to a profile NUMBER — the
                    // flag itself never keys off names.
                    val labels = listOf(
                        "None", prefs.profileDisplayName(1), prefs.profileDisplayName(2)
                    )
                    pickFrom(labels, labels[prefs.guestProfile.coerceIn(0, 2)]) { v ->
                        prefs.guestProfile = labels.indexOf(v).coerceIn(0, 2)
                        toast(
                            if (prefs.guestProfile == 0) "Guest profile cleared"
                            else "${prefs.profileDisplayName(prefs.guestProfile)} is now Guest"
                        )
                        rebuild()
                    }
                })
                content.addView(toggleRow(
                    "Lock settings in Guest", prefs.guestLockSettings,
                    "While Guest is active, opening Settings or switching back asks for the arrow pattern"
                ) {
                    prefs.guestLockSettings = it
                    rebuild()
                })
                content.addView(info("Switching TO Guest is always instant (profile tile in the status bar, or the row above). While Guest is active, a “Guest” pill shows in the status bar. If no arrow pattern is set, the guest lock fails open — nothing is gated — so set a pattern above for the gate to bite."))
    }

    /** Data & about (v1.5): the v1.4 Backup and Safe mode categories merged. */
    private fun buildDataDetail(content: LinearLayout) {
        content.addView(header("Backup & restore"))
                content.addView(row("Export settings", "Save all settings as a JSON file") {
                    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/json")
                        .putExtra(Intent.EXTRA_TITLE, "mizan-launcher-settings.json")
                    try { startActivityForResult(intent, 41) } catch (e: Exception) { failOpen("export", e) }
                })
                content.addView(row("Import settings", "Restore from a previously exported JSON file") {
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/json")
                    try { startActivityForResult(intent, 42) } catch (e: Exception) { failOpen("import", e) }
                })
        content.addView(header("Mizan Launcher v1.9"))
        content.addView(info(diagnosticsText()))
        content.addView(header("Safe mode"))
        content.addView(toggleRow("Safe mode: plain launcher", prefs.safeMode, "Plain launcher — themes/widgets/extras off") {
            prefs.safeMode = it
            toast("Safe mode " + if (it) "ON" else "off")
            rebuild()
        })
    }

    private fun buildStatusBarDetail(content: LinearLayout) {
        content.addView(header("Status bar"))
        val sb = layout.statusBar
        content.addView(toggleRow("Show status bar", sb.showBar, "Off = clean Apple-TV style, no top bar at all") { updateStatusBar { c -> c.copy(showBar = it) } })
        content.addView(toggleRow("Sticky status bar", prefs.stickyStatusBar, "The bar stays pinned at the top while the home scrolls — in the TV, iPad and Tracker views") {
            prefs.stickyStatusBar = it
            rebuild()
        })
        content.addView(toggleRow("Clock", sb.showClock) { updateStatusBar { c -> c.copy(showClock = it) } })
        content.addView(toggleRow("Date", sb.showDate) { updateStatusBar { c -> c.copy(showDate = it) } })
        content.addView(toggleRow("Weather", sb.showWeather) { updateStatusBar { c -> c.copy(showWeather = it) } })
        content.addView(toggleRow("Network icon", sb.showNetwork) { updateStatusBar { c -> c.copy(showNetwork = it) } })
        content.addView(toggleRow("Bluetooth icon", sb.showBluetooth) { updateStatusBar { c -> c.copy(showBluetooth = it) } })
        content.addView(toggleRow("Quick tiles row", sb.showQuickTiles, "Master switch for the tiles below") { updateStatusBar { c -> c.copy(showQuickTiles = it) } })
        content.addView(header("Quick tiles — individual"))
        content.addView(info("Each tile can be hidden on its own. The bar's Settings gear has its own switch; even with it off, a corner ⚙ appears so you can always get back into Settings."))
        content.addView(toggleRow("Tile: Wi-Fi", sb.showTileWifi) { updateStatusBar { c -> c.copy(showTileWifi = it) } })
        content.addView(toggleRow("Tile: Bluetooth", sb.showTileBluetooth) { updateStatusBar { c -> c.copy(showTileBluetooth = it) } })
        content.addView(toggleRow("Tile: Sound", sb.showTileSound) { updateStatusBar { c -> c.copy(showTileSound = it) } })
        content.addView(toggleRow("Tile: Picture", sb.showTilePicture) { updateStatusBar { c -> c.copy(showTilePicture = it) } })
        content.addView(toggleRow("Tile: Settings gear", sb.showTileSettings, "The ⚙ Settings button inside the bar (corner gear is separate)") { updateStatusBar { c -> c.copy(showTileSettings = it) } })
        content.addView(toggleRow("Tile: Search", sb.showTileSearch, "Opens Mizan's app Search screen (the remote's Search key works too)") { updateStatusBar { c -> c.copy(showTileSearch = it) } })
        content.addView(toggleRow("Tile: All Apps", sb.showTileAllApps, "Opens the full-screen All Apps tab — every app, foldered ones included") { updateStatusBar { c -> c.copy(showTileAllApps = it) } })
        content.addView(row("Clock format", if (sb.clock24h) "24-hour" else "12-hour") {
            pickFrom(listOf("12-hour", "24-hour"), if (sb.clock24h) "24-hour" else "12-hour") { v ->
                updateStatusBar { c -> c.copy(clock24h = v == "24-hour") }
            }
        })
        content.addView(toggleRow("Show seconds", sb.showSeconds) { updateStatusBar { c -> c.copy(showSeconds = it) } })
        content.addView(row("Date format", com.mizan.launcher.core.StatusBarConfig.DATE_LABELS[sb.dateFormat] ?: sb.dateFormat) {
            val keys = com.mizan.launcher.core.StatusBarConfig.DATE_FORMATS
            val labels = keys.map { com.mizan.launcher.core.StatusBarConfig.DATE_LABELS[it] ?: it }
            val idx = keys.indexOf(sb.dateFormat).coerceAtLeast(0)
            AlertDialog.Builder(this).setTitle("Date format")
                .setSingleChoiceItems(labels.toTypedArray(), idx) { dlg, which ->
                    updateStatusBar { c -> c.copy(dateFormat = keys[which]) }
                    dlg.dismiss()
                }
                .setNegativeButton("Cancel", null).show()
        })
        content.addView(row("Text size", sb.textSize) {
            pickFrom(com.mizan.launcher.core.StatusBarConfig.TEXT_SIZES, sb.textSize) { v ->
                updateStatusBar { c -> c.copy(textSize = v) }
            }
        })
        content.addView(row("Bar background", sb.background) {
            pickFrom(com.mizan.launcher.core.StatusBarConfig.BACKGROUNDS, sb.background) { v ->
                updateStatusBar { c -> c.copy(background = v) }
            }
        })
        content.addView(row("Background opacity", sb.opacity) {
            pickFrom(com.mizan.launcher.core.StatusBarConfig.OPACITIES, sb.opacity) { v ->
                updateStatusBar { c -> c.copy(opacity = v) }
            }
        })
    }

    private fun buildLookDetail(content: LinearLayout) {
        content.addView(header("Theme & wallpaper"))
        content.addView(row("Theme", Themes.preset(prefs.themeId).name) { pickTheme() })
        content.addView(row("Wallpaper", wallpaperLabel()) { pickWallpaper() })
        content.addView(row("Home style", homeStyleLabel()) { pickHomeStyle() })
        content.addView(row("Icon shape", prefs.iconShape) { pickFrom(listOf("rounded", "circle", "square"), prefs.iconShape) { prefs.iconShape = it; rebuild() } })
        content.addView(row("App size", prefs.appSize + " — scales every tile equally") { pickFrom(listOf("compact", "standard", "large"), prefs.appSize) { prefs.appSize = it; rebuild() } })
        content.addView(row("Grid columns", prefs.columns.toString()) {
            pickFrom(listOf("4", "5", "6", "7", "8"), prefs.columns.toString()) { prefs.columns = it.toInt(); rebuild() }
        })

    }

    /** The current home style with its one-line description (v1.7, v1.8). */
    private fun homeStyleLabel(): String =
        when {
            com.mizan.launcher.core.HomeStyle.isIpad(prefs.homeStyle) ->
                "iPad — big icon grid with a Favorites dock"
            com.mizan.launcher.core.HomeStyle.isTracker(prefs.homeStyle) ->
                "Tracker — Netflix-style view of your shows & movies"
            else ->
                "TV — rows of apps under the hero bar"
        }

    /**
     * Home style picker (v1.7, extended v1.8): three described
     * choices, per profile. Switching is presentation-only —
     * favorites, folders, order and locks are shared, so nothing is
     * lost either way.
     */
    private fun pickHomeStyle() {
        val keys = listOf(
            com.mizan.launcher.core.HomeStyle.TV,
            com.mizan.launcher.core.HomeStyle.IPAD,
            com.mizan.launcher.core.HomeStyle.TRACKER
        )
        val labels = arrayOf(
            "TV — rows of apps under the hero bar",
            "iPad — big icon grid with a Favorites dock",
            "Tracker — Netflix-style view of your shows & movies (needs TV Shows & Movies Tracker)"
        )
        val current = keys.indexOf(prefs.homeStyle).coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("Home style")
            .setSingleChoiceItems(labels, current) { dlg, which ->
                prefs.homeStyle = keys[which]
                dlg.dismiss()
                rebuild()
            }
            .setNegativeButton("Cancel", null).show()
    }

    /**
     * Home screen (v1.5): row visibility/order (incl. the Recently
     * Used toggle — it is the "recents" entry in the rows list),
     * Hidden apps, and the Folders manager. These lived inside
     * Theme & wallpaper until v1.4.
     */
    private fun buildHomeScreenDetail(content: LinearLayout) {
        content.addView(header("Home screen rows"))
        // Show the EFFECTIVE order — stored orders from older
        // versions gain the new rows at their canonical positions, and
        // the normalized order is what row options below edit.
        val effectiveOrder = com.mizan.launcher.core.RowOrder.normalize(layout.rowOrder)
        if (effectiveOrder != layout.rowOrder) {
            layout.rowOrder.clear()
            layout.rowOrder.addAll(effectiveOrder)
        }
        for (key in layout.rowOrder) {
            content.addView(row(rowLabel(key), if (layout.isRowVisible(key)) "Shown — tap for options" else "Hidden — tap for options") {
                rowOptions(key)
            })
        }
        content.addView(info("The Recently Used row is the “Row: Recently Used” entry above — Shown/Hidden is its toggle."))

        content.addView(header("Apps & folders"))
        content.addView(row("Hidden apps", "${layout.hidden.size} hidden") { hiddenApps() })
        content.addView(row("Folders", "${layout.folders.size} folders") { folderManager() })
    }

    /** Now playing (v1.5): its own category; lived in Theme & wallpaper in v1.4. */
    private fun buildNowPlayingDetail(content: LinearLayout) {
        content.addView(header("Now playing"))
        content.addView(toggleRow(
            "Now playing row", layout.isRowVisible("nowplaying"),
            "Shows what's playing in other apps (needs notification access, below)"
        ) { v ->
            layout.rowVisible["nowplaying"] = v
            saveLayout()
            rebuild()
        })
        val npGranted = com.mizan.launcher.data.NowPlayingData.isGranted(this)
        content.addView(row(
            "Notification access",
            if (npGranted) "Granted ✓" else "Not granted — tap to open system settings"
        ) {
            try { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            catch (e: Exception) { failOpen("notificationListenerSettings", e) }
        })
        content.addView(info("State: ${if (npGranted) "Granted" else "Not granted"}. Mizan's listener reads no notification content — Android simply requires this grant before an app may see media sessions (what's playing, and its play/pause controls). Without it the row stays hidden."))
    }

    // ------------------------------------------------------------ row helpers

    private fun header(text: String): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.setTextColor(accent)
        tv.textSize = metrics.headerSp
        tv.setTypeface(tv.typeface, android.graphics.Typeface.BOLD)
        tv.setPadding(0, Ui.dp(this, metrics.rowPadVDp + 6), 0, Ui.dp(this, 6))
        // v1.6: headers are card titles — groupDetailIntoCards keys
        // the Settings card grouping off this tag.
        tv.tag = CARD_HEADER_TAG
        return tv
    }

    private fun info(text: String): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.setTextColor(subColour)
        tv.textSize = metrics.infoTextSp
        // Info text wraps (no single-line truncation) — v1.3 fit rule.
        // v1.6: horizontal padding matches row text, so info lines up
        // with the controls inside its card.
        tv.setPadding(Ui.dp(this, 16), Ui.dp(this, 4), Ui.dp(this, 16), Ui.dp(this, 4))
        return tv
    }

    /** v1.6: the detail pane's opening block — category name + description. */
    private fun detailHeader(id: String): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        val title = TextView(this)
        title.text = categories.firstOrNull { it.first == id }?.second ?: "Settings"
        title.setTextColor(textColour)
        title.textSize = metrics.headerSp + 4f
        title.setTypeface(title.typeface, android.graphics.Typeface.BOLD)
        box.addView(title)
        val desc = TextView(this)
        desc.text = categoryDescriptions[id] ?: ""
        desc.setTextColor(subColour)
        desc.textSize = metrics.infoTextSp
        desc.setPadding(0, Ui.dp(this, 4), 0, 0)
        box.addView(desc)
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.setMargins(0, 0, 0, Ui.dp(this, com.mizan.launcher.core.Design.SPACE_M))
        box.layoutParams = lp
        return box
    }

    /**
     * v1.6 card grouping: the detail builders still emit a flat
     * stream (header, rows, infos…); this pass re-parents everything
     * from [firstContentIndex] on into rounded cards — each header()
     * starts a new card and becomes its title, the controls that
     * follow it live inside that card. Pure re-arrangement: tags,
     * focus hooks and listeners on the children are untouched, so
     * the nav machine and focus memory behave exactly as before.
     * Fail-open: any surprise leaves the flat list as built.
     */
    private fun groupDetailIntoCards(detail: LinearLayout, firstContentIndex: Int) {
        try {
            val children = (0 until detail.childCount).map { detail.getChildAt(it) }
            val content = children.drop(firstContentIndex)
            if (content.isEmpty()) return
            content.forEach { detail.removeView(it) }
            var card: LinearLayout? = null
            fun newCard(): LinearLayout {
                val c = LinearLayout(this)
                c.orientation = LinearLayout.VERTICAL
                val bg = android.graphics.drawable.GradientDrawable()
                bg.cornerRadius = Ui.dp(this, com.mizan.launcher.core.Design.RADIUS_PANEL).toFloat()
                bg.setColor(com.mizan.launcher.core.Design.PANEL_FILL)
                c.background = bg
                c.setPadding(
                    Ui.dp(this, 18), Ui.dp(this, 10),
                    Ui.dp(this, 18), Ui.dp(this, 14)
                )
                val lp = LinearLayout.LayoutParams(-1, -2)
                lp.setMargins(0, 0, 0, Ui.dp(this, com.mizan.launcher.core.Design.SPACE_M))
                c.layoutParams = lp
                detail.addView(c)
                card = c
                return c
            }
            for (child in content) {
                if (child.tag == CARD_HEADER_TAG) {
                    val host = newCard()
                    // The header is the card's title now: no big top gap.
                    child.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 8))
                    host.addView(child)
                } else {
                    (card ?: newCard()).addView(child)
                }
            }
        } catch (e: Exception) {
            failOpen("settingsCards", e)
        }
    }

    /**
     * A detail-pane control row. While detail rows are being built the
     * row is tagged with its title (focus memory), collected into
     * [detailFocusable], and its focus hook reports the detail pane as
     * focused to the nav machine — that's what makes LEFT/BACK and the
     * memory restore deterministic instead of relying on focus search.
     */
    private fun row(title: String, subtitle: String, onClick: () -> Unit): TextView {
        val tv = TextView(this)
        tv.text = if (subtitle.isEmpty()) title else "$title\n$subtitle"
        tv.setTextColor(textColour)
        tv.textSize = metrics.rowTextSp
        tv.isFocusable = true
        tv.isClickable = true
        tv.setPadding(Ui.dp(this, 16), Ui.dp(this, metrics.rowPadVDp), Ui.dp(this, 16), Ui.dp(this, metrics.rowPadVDp))
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.setMargins(0, Ui.dp(this, 4), 0, Ui.dp(this, 4))
        tv.layoutParams = lp
        tv.tag = title
        if (buildingDetail) {
            detailFocusable = detailFocusable + tv
        }
        tv.background = Ui.tileBackground(accent, false, 12, this)
        Ui.focusLift(tv, accent, 12) { hasFocus ->
            if (hasFocus) {
                scrollIntoView(tv)
                focusedPane = SettingsNavMachine.COLUMN_RIGHT
                nav.noteFocus(SettingsNavMachine.COLUMN_RIGHT)
                detailFocusMemory[nav.selectedId] = title
            }
        }
        tv.setOnClickListener {
            detailFocusMemory[nav.selectedId] = title
            onClick()
        }
        return tv
    }

    private fun rowLabel(key: String): String = when (key) {
        "favorites" -> "Row: Favorites"
        "recents" -> "Row: Recently Used"
        "folders" -> "Row: Folders"
        "nowplaying" -> "Row: Now Playing"
        "continue" -> "Row: Continue Watching"
        "inputs" -> "Row: HDMI & Inputs"
        "apps" -> "Row: All Apps"
        else -> "Row: $key"
    }

    private fun diagnosticsText(): String {
        val diag = MainActivity.DiagCache
        val lastErr = Prefs.lastError(this)
        return buildString {
            append("Diagnostics — profile: ${prefs.currentProfile}, theme: ${Themes.preset(prefs.themeId).name}")
            append(", apps: ${diag.appsCount}, inputs: ${diag.inputsCount}, watch next: ${diag.watchNextCount}")
            append(", safe mode: ${if (prefs.safeMode) "on" else "off"}")
            val isHome = com.mizan.launcher.data.HomeStatus.isDefaultHome(this@SettingsActivity)
            val watcher = com.mizan.launcher.data.HomeStatus.isWatcherEnabled(this@SettingsActivity)
            append("\nHome: ${if (isHome) "Mizan is default" else "not default (current: " +
                com.mizan.launcher.data.HomeStatus.currentHomePackage(this@SettingsActivity).ifEmpty { "unknown" } + ")"}")
            append(", watcher: ${if (watcher) "enabled" else "disabled"}" +
                if (watcher) "" else " (listed in Accessibility as “Mizan Launcher watcher”)")
            val relaunches = Prefs.watcherRelaunches(this@SettingsActivity)
            if (relaunches > 0) {
                val lastAt = Prefs.watcherLastAt(this@SettingsActivity)
                val whenText = if (lastAt > 0) {
                    java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.getDefault())
                        .format(java.util.Date(lastAt))
                } else "never"
                append(", watcher relaunches: $relaunches (last: $whenText)")
            }
            append("\nStatus bar: ${if (layout.statusBar.showBar) "shown" else "hidden"}" +
                ", wallpaper: ${wallpaperLabel()}")
            // v1.7: the profile's home style (safe mode renders TV
            // regardless — noted so a report isn't misread).
            append("\nhome style: ${prefs.homeStyle}" +
                if (prefs.safeMode) " (safe mode: tv)" else "")
            // v1.9: the tracker: line — ALWAYS present, worded by
            // core/TrackerStatus from the last classified fetch, so
            // an empty Tracker view explains itself instead of
            // failing silently (the v1.8 behaviour on Haythem's TV).
            val tSnap = com.mizan.launcher.data.TrackerClient.cached(this@SettingsActivity)
            val tState = com.mizan.launcher.data.TrackerClient.status(this@SettingsActivity)
            val tTime = if (tSnap.updated > 0) {
                java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
                    .format(java.util.Date(tSnap.updated))
            } else ""
            append(
                "\ntracker: " + com.mizan.launcher.core.TrackerStatus.diagText(
                    tState.kind, tSnap.hasData, tSnap.upNext.size,
                    tSnap.cont.size, tSnap.movies.size, tTime
                )
            )
            val sbCfg = layout.statusBar
            append("\nQuick tiles: master ${if (sbCfg.showQuickTiles) "on" else "off"}" +
                ", wifi ${onOff(sbCfg.showTileWifi)}, bt ${onOff(sbCfg.showTileBluetooth)}" +
                ", sound ${onOff(sbCfg.showTileSound)}, picture ${onOff(sbCfg.showTilePicture)}" +
                ", gear ${onOff(sbCfg.showTileSettings)}")
            append("\nLocked folders: ${layout.folders.count { it.locked }} of ${layout.folders.size}")
            // v1.2 lines: arrow lock, weather units + background state.
            append("\nArrow lock: ${if (prefs.arrowPin.isEmpty()) "not set" else "set"}" +
                ", wrong unlock attempts: ${prefs.arrowAttempts}")
            append("\nSettings lock: ${onOff(prefs.lockSettings)}")
            append("\nWeather: units ${prefs.weatherUnits.tempSymbol}" +
                ", weather bg: ${prefs.weatherBgStatus}")
            // v1.6: the reddit: line always appears — last run's
            // summary plus the recorded per-endpoint attempts.
            append("\nreddit: ${prefs.redditLastStatus.ifEmpty { "no fetch yet" }}")
            val redditErr = com.mizan.launcher.data.RedditFetcher.lastError
            if (redditErr.isNotEmpty()) append(" · $redditErr")
            for (line in Prefs.redditAttemptLog(this@SettingsActivity)) {
                append("\n  $line")
            }
            // v1.4 lines: Now Playing state, guest gate, rotation, speed up, Bing.
            append("\nnow playing: ${if (diag.nowPlayingGranted) "granted" else "not-granted"}" +
                ", sessions: ${diag.nowPlayingCount}")
            val guestActiveNow = com.mizan.launcher.core.GuestGate.isGuestActive(
                prefs.currentProfile, prefs.guestProfile
            )
            append("\nGuest: ${if (prefs.guestProfile == 0) "none set" else "profile ${prefs.guestProfile}"}" +
                ", active: ${if (guestActiveNow) "yes" else "no"}" +
                ", lock settings: ${onOff(prefs.guestLockSettings)}")
            if (guestActiveNow && prefs.guestLockSettings && prefs.arrowPin.isEmpty()) {
                append(" (gate fail-open: no pattern set)")
            }
            append("\nReddit rotation: ${com.mizan.launcher.core.RotationLogic.labelFor(prefs.redditRotation)}" +
                ", Bing day: ${prefs.bingCacheDate.ifEmpty { "—" }}")
            val bingErr = com.mizan.launcher.data.BingFetcher.lastError
            if (bingErr.isNotEmpty()) append("\nBing: $bingErr")
            if (prefs.speedupLastResult.isNotEmpty()) {
                append("\nSpeed up: ${prefs.speedupLastResult}")
            }
            if (lastErr.isNotEmpty()) append("\nLast error: $lastErr")
        }
    }

    /**
     * A boolean settings row rendered as an iPhone-style switch (v1.3):
     * title/subtitle left, pill switch right (grey OFF / green ON).
     * OK/CENTER and LEFT/RIGHT toggle; the row registers with the same
     * focus-memory + nav hooks as text rows.
     */
    private fun toggleRow(title: String, value: Boolean, subtitle: String = "", apply: (Boolean) -> Unit): View {
        val switch = SwitchRowView(
            this, title, subtitle, textColour, subColour,
            metrics.rowTextSp, metrics.infoTextSp, metrics.rowPadVDp, value
        ) { newValue -> apply(newValue) }
        switch.tag = title
        if (buildingDetail) {
            detailFocusable = detailFocusable + switch
        }
        switch.background = Ui.tileBackground(accent, false, 12, this)
        Ui.focusLift(switch, accent, 12) { hasFocus ->
            if (hasFocus) {
                scrollIntoView(switch)
                focusedPane = SettingsNavMachine.COLUMN_RIGHT
                nav.noteFocus(SettingsNavMachine.COLUMN_RIGHT)
                detailFocusMemory[nav.selectedId] = title
            }
        }
        return switch
    }

    /** Apply a status-bar change, persist via the layout store, rebuild. */
    private fun updateStatusBar(transform: (com.mizan.launcher.core.StatusBarConfig) -> com.mizan.launcher.core.StatusBarConfig) {
        layout.statusBar = transform(layout.statusBar)
        saveLayout()
        rebuild()
    }

    // ------------------------------------------------- home screen takeover

    private fun homeSetupSubtitle(): String {
        val isHome = com.mizan.launcher.data.HomeStatus.isDefaultHome(this)
        val watcher = com.mizan.launcher.data.HomeStatus.isWatcherEnabled(this)
        return when {
            isHome -> "✓ Mizan is your home screen" + if (watcher) " · watcher on" else ""
            watcher -> "Watcher is on, but Mizan isn't the default home yet"
            else -> "Mizan isn't your home screen yet — 3 guided steps"
        }
    }

    /**
     * The takeover checklist (fills the Home override detail pane).
     * Status lines are LIVE (rebuilt on resume, so returning from a
     * system settings screen updates the ticks). Mizan never disables
     * the stock launcher or any other package — taking over Home is
     * the system's default-home choice + the optional watcher.
     */
    private fun buildMakeHome(content: LinearLayout) {
        val isHome = com.mizan.launcher.data.HomeStatus.isDefaultHome(this)
        val watcher = com.mizan.launcher.data.HomeStatus.isWatcherEnabled(this)

        content.addView(header("Make Mizan your home screen"))
        content.addView(info(
            "Three steps. Nothing here turns off the TV's own launcher or any other app — " +
                "you can switch back in the same system screens at any time."
        ))

        content.addView(header("Step 1 — Default home"))
        content.addView(info(
            if (isHome) "✓ Done — Mizan is the default home screen."
            else "✗ Not yet — current home: " +
                com.mizan.launcher.data.HomeStatus.currentHomePackage(this).ifEmpty { "unknown" }
        ))
        content.addView(row("Set Mizan as default home", "Opens the system's home-role request / Home settings") {
            requestDefaultHome()
        })

        content.addView(header("Step 2 — Home watcher"))
        content.addView(info(
            if (watcher) "✓ Done — “Mizan Launcher watcher” is enabled in Accessibility."
            else "Optional but recommended: the watcher notices when the TV's own home screen " +
                "appears and reopens Mizan. It only watches which window is showing — " +
                "it cannot read screen contents."
        ))
        if (!watcher) {
            content.addView(info(
                "Where to find it: TV Settings → Device Preferences → Accessibility → " +
                    "Installed services → “Mizan Launcher watcher”. If it isn't listed, " +
                    "allow restricted settings first: Android system Settings → Apps → " +
                    "Mizan Launcher → ⋮ (top-right) → “Allow restricted settings”, then look again."
            ))
        }
        content.addView(row("Enable Home watcher", "Opens Accessibility settings — turn on “Mizan Launcher watcher”") {
            try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            catch (e: Exception) { failOpen("a11ySettings", e) }
        })
        val relaunches = Prefs.watcherRelaunches(this)
        content.addView(info("Watcher relaunches so far: $relaunches"))

        content.addView(header("Step 3 — Optional: set via ADB"))
        content.addView(info(
            "If the system screens don't offer Mizan on this TV, the same result from a " +
                "computer on the same Wi-Fi (this changes only the default home — it disables nothing):"
        ))
        val adbView = info(com.mizan.launcher.data.HomeStatus.adbCommands())
        adbView.setTextIsSelectable(true)
        content.addView(adbView)

        content.addView(row("← Back to Home & launcher", "") {
            showingMakeHome = false
            rebuild()
        })
    }

    /** Ask Android to make Mizan the default home; fail-open across variants. */
    private fun requestDefaultHome() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                val rm = getSystemService(android.app.role.RoleManager::class.java)
                if (rm != null && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME) &&
                    !rm.isRoleHeld(android.app.role.RoleManager.ROLE_HOME)
                ) {
                    startActivityForResult(
                        rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_HOME), 44
                    )
                    return
                }
                if (rm != null && rm.isRoleHeld(android.app.role.RoleManager.ROLE_HOME)) {
                    toast("Mizan is already the default home")
                    return
                }
            }
        } catch (e: Exception) {
            // Fall through to the settings screen variant.
        }
        try {
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
            return
        } catch (e: Exception) {
            // Some TVs don't ship that screen either.
        }
        try {
            startActivity(Intent(Settings.ACTION_SETTINGS))
            toast("Find “Default home app” in Settings and choose Mizan Launcher")
        } catch (e: Exception) {
            failOpen("homeSettings", e)
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    /** Short on/off word for diagnostics lines. */
    private fun onOff(b: Boolean): String = if (b) "on" else "off"

    private fun failOpen(where: String, e: Exception) {
        Prefs.noteError(this, "settings:$where", e)
        toast("That didn't work on this device — nothing was changed.")
    }

    private fun saveLayout() { prefs.layout = layout }

    // ------------------------------------------------------------ pickers

    private fun pickFrom(options: List<String>, current: String, apply: (String) -> Unit) {
        val idx = options.indexOf(current).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setSingleChoiceItems(options.toTypedArray(), idx) { dlg, which ->
                apply(options[which]); dlg.dismiss()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun pickTheme() {
        val names = Themes.PRESETS.map { it.name }
        val idx = Themes.PRESETS.indexOfFirst { it.id == prefs.themeId }.coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("Theme")
            .setSingleChoiceItems(names.toTypedArray(), idx) { dlg, which ->
                prefs.themeId = Themes.PRESETS[which].id
                if (Themes.PRESETS[which].dynamic) AccentUtil.recompute(this, prefs)
                dlg.dismiss(); rebuild()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun wallpaperLabel(): String = when (prefs.wallpaperKind) {
        "gradient" -> "Gradient: ${prefs.wallpaperValue}"
        "solid" -> "Solid colour"
        "image" -> "My picture"
        "reddit" -> "Reddit picture"
        "bing" -> "Bing daily picture"
        "weather" -> "Weather (live conditions)"
        else -> "Theme background"
    }

    private fun pickWallpaper() {
        val options = listOf(
            "Theme background", "Built-in gradients…", "Solid colours…",
            "Weather background", "Reddit wallpapers…", "My picture from storage…",
            "Bing daily picture"
        )
        AlertDialog.Builder(this).setTitle("Wallpaper")
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> { prefs.wallpaperKind = "theme"; AccentUtil.recompute(this, prefs); rebuild() }
                    3 -> {
                        // Weather background (v1.2): drawn from the
                        // current conditions / today's forecast; falls
                        // back to the theme gradient until weather data
                        // arrives (and the home screen fetches it).
                        prefs.wallpaperKind = "weather"
                        AccentUtil.recompute(this, prefs)
                        toast("Weather background on — it follows the forecast on the home screen")
                        rebuild()
                    }
                    4 -> redditPicker()
                    6 -> chooseBingWallpaper()
                    1 -> {
                        val names = Themes.GRADIENTS.map { it.name }
                        AlertDialog.Builder(this).setTitle("Gradient")
                            .setItems(names.toTypedArray()) { _, w2 ->
                                prefs.wallpaperKind = "gradient"
                                prefs.wallpaperValue = names[w2]
                                AccentUtil.recompute(this, prefs); rebuild()
                            }.show()
                    }
                    2 -> {
                        val names = Themes.SOLIDS.map { it.first }
                        AlertDialog.Builder(this).setTitle("Solid colour")
                            .setItems(names.toTypedArray()) { _, w2 ->
                                prefs.wallpaperKind = "solid"
                                prefs.wallpaperValue = Themes.SOLIDS[w2].second.toString()
                                AccentUtil.recompute(this, prefs); rebuild()
                            }.show()
                    }
                    5 -> {
                        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                            .addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("image/*")
                        try { startActivityForResult(intent, 43) }
                        catch (e: Exception) { failOpen("wallpaperPicker", e) }
                    }
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    /**
     * Bing daily picture (v1.4): use today's cached file when we have
     * it (no network), otherwise fetch + download it. Fail-open: a
     * failed fetch keeps the current wallpaper and says so.
     */
    private fun chooseBingWallpaper() {
        val today = com.mizan.launcher.core.BingWallpaper.dayKey(System.currentTimeMillis())
        val cachedPath = prefs.bingCachePath
        if (prefs.bingCacheDate == today && cachedPath.isNotEmpty() &&
            java.io.File(cachedPath).exists()
        ) {
            prefs.wallpaperKind = "bing"
            prefs.wallpaperValue = cachedPath
            AccentUtil.recompute(this, prefs)
            toast("Bing daily picture set")
            rebuild()
            return
        }
        toast("Fetching today's Bing picture…")
        com.mizan.launcher.data.BingFetcher.fetchToday(this, today) { file ->
            runOnUiThread {
                if (file != null) {
                    prefs.bingCacheDate = today
                    prefs.bingCachePath = file.absolutePath
                    prefs.wallpaperKind = "bing"
                    prefs.wallpaperValue = file.absolutePath
                    AccentUtil.recompute(this, prefs)
                    toast("Bing daily picture set")
                    rebuild()
                } else {
                    Prefs.noteError(
                        this, "bingPicker",
                        Exception(com.mizan.launcher.data.BingFetcher.lastError
                            .ifEmpty { "fetch failed" })
                    )
                    toast("Couldn't fetch the Bing picture — current wallpaper kept")
                    rebuild()
                }
            }
        }
    }

    private fun rowOptions(key: String) {
        val options = mutableListOf<String>()
        options.add(if (layout.isRowVisible(key)) "Hide row" else "Show row")
        options.add("Move row up")
        options.add("Move row down")
        AlertDialog.Builder(this).setTitle(rowLabel(key))
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> { layout.rowVisible[key] = !layout.isRowVisible(key) }
                    1 -> moveRow(key, -1)
                    2 -> moveRow(key, +1)
                }
                saveLayout(); rebuild()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun moveRow(key: String, dir: Int) {
        val i = layout.rowOrder.indexOf(key)
        if (i < 0) return
        val j = i + dir
        if (j < 0 || j >= layout.rowOrder.size) return
        val tmp = layout.rowOrder[i]; layout.rowOrder[i] = layout.rowOrder[j]; layout.rowOrder[j] = tmp
    }

    private fun hiddenApps() {
        if (layout.hidden.isEmpty()) { toast("No hidden apps"); return }
        val apps = AppLoader.loadApps(packageManager, packageName)
        val byPkg = apps.associateBy { it.pkg }
        val pkgs = layout.hidden.toList()
        val labels = pkgs.map { byPkg[it]?.label ?: it }
        AlertDialog.Builder(this).setTitle("Hidden apps — tap to unhide")
            .setItems(labels.toTypedArray()) { _, which ->
                layout.hidden.remove(pkgs[which]); saveLayout(); rebuild()
            }
            .setNegativeButton("Close", null).show()
    }

    /**
     * The Folders manager (v1.5): the folder TREE, listed depth-first
     * with indentation (roots first, each folder's sub-folders under
     * it). Per folder: Rename, Lock/Unlock, "Move into…" (only legal
     * targets — core/FolderTree.canNest), Delete (never cascades:
     * sub-folders return to the home Folders row).
     */
    private fun folderManager() {
        val flat = mutableListOf<Pair<Folder, Int>>()
        fun walk(folder: Folder, depth: Int) {
            flat.add(folder to depth)
            for (subId in folder.subFolderIds) {
                com.mizan.launcher.core.FolderTree.byId(layout.folders, subId)?.let { walk(it, depth + 1) }
            }
        }
        for (root in com.mizan.launcher.core.FolderTree.rootFolders(layout.folders)) walk(root, 0)
        val options = flat.map { (folder, depth) ->
            "  ".repeat(depth) +
                (if (folder.locked) "🔒 " else "") +
                "${folder.name} (${folder.appPackages.size} apps" +
                (if (folder.subFolderIds.isNotEmpty()) ", ${folder.subFolderIds.size} folders" else "") + ")"
        } + "＋ New folder…"
        AlertDialog.Builder(this).setTitle("Folders")
            .setItems(options.toTypedArray()) { _, which ->
                if (which == flat.size) {
                    promptName("New folder") { name ->
                        layout.folders.add(
                            Folder(com.mizan.launcher.core.FolderTree.freshId(layout.folders), name)
                        )
                        saveLayout(); rebuild()
                    }
                } else {
                    folderManagerActions(flat[which].first)
                }
            }
            .setNegativeButton("Close", null).show()
    }

    /** Per-folder actions inside the Folders manager. */
    private fun folderManagerActions(folder: Folder) {
        AlertDialog.Builder(this).setTitle(folder.name)
            .setItems(
                arrayOf(
                    "Rename",
                    if (folder.locked) "Unlock folder" else "Lock folder",
                    "Move into…",
                    "Delete folder"
                )
            ) { _, which ->
                when (which) {
                    0 -> promptName("Rename folder", folder.name) { name ->
                        folder.name = name; saveLayout(); rebuild()
                    }
                    1 -> toggleFolderLock(folder)
                    2 -> folderManagerMoveInto(folder)
                    else -> {
                        com.mizan.launcher.core.FolderTree.deleteFolder(layout.folders, folder.id)
                        saveLayout(); rebuild()
                        toast("Folder deleted — its sub-folders are back on the home screen")
                    }
                }
            }.show()
    }

    /**
     * "Move into…" in the manager: legal targets only (no self, no
     * cycles, max depth 3 — core/FolderTree), labelled by breadcrumb
     * path, plus "Home (top level)" for a nested folder. Moving
     * replaces the folder's parent.
     */
    private fun folderManagerMoveInto(folder: Folder) {
        val tree = com.mizan.launcher.core.FolderTree
        val targets = tree.legalParents(layout.folders, folder.id)
        val nested = tree.parentOf(layout.folders, folder.id) != null
        val labels = mutableListOf<String>()
        if (nested) labels.add("⌂ Home (top level)")
        labels.addAll(targets.map { target ->
            "🗂 " + tree.breadcrumb(layout.folders, target.id).joinToString(" › ") { it.name }
        })
        if (labels.isEmpty()) {
            toast("No folder can hold this one (depth limit ${tree.MAX_DEPTH})")
            return
        }
        AlertDialog.Builder(this).setTitle("Move “${folder.name}” into…")
            .setItems(labels.toTypedArray()) { _, which ->
                val parentId: String? = if (nested) {
                    if (which == 0) null else targets[which - 1].id
                } else {
                    targets[which].id
                }
                tree.moveFolder(layout.folders, folder.id, parentId)
                saveLayout(); rebuild()
            }
            .setNegativeButton("Cancel", null).show()
    }

    /**
     * Lock/unlock a folder from Settings (v1.3). Locking with no arrow
     * pattern set runs the set-pattern flow first and only locks once
     * a pattern exists — the same fail-open rule as the home screen,
     * so the user can never lock themselves out of a folder.
     */
    private fun toggleFolderLock(folder: Folder) {
        if (folder.locked) {
            folder.locked = false
            saveLayout(); rebuild()
            toast("Folder unlocked")
            return
        }
        if (prefs.arrowPin.isEmpty()) {
            ArrowPinDialog.showSetup(this, "Set a pattern to lock “${folder.name}”", accent, onDone = { seq ->
                prefs.arrowPin = seq
                folder.locked = true
                saveLayout(); rebuild()
                toast("Folder locked")
            })
        } else {
            folder.locked = true
            saveLayout(); rebuild()
            toast("Folder locked")
        }
    }

    /**
     * Rename a profile (v1.9) — the D-pad keyboard dialog (the
     * Search keyboard as a dialog; no EditText, no soft keyboard).
     * The codec (core/ProfileNames) owns the rules: the stored value
     * is normalized, and an empty result restores the default label.
     * Only the label changes — guest flag, gates and per-profile
     * settings are keyed by profile number.
     */
    private fun renameProfile(profile: Int) {
        val isGuest = prefs.guestProfile != 0 && prefs.guestProfile == profile
        KeyboardDialog.show(
            this,
            "Rename ${com.mizan.launcher.core.ProfileNames.defaultLabel(profile, isGuest)}",
            accent,
            prefs.profileName(profile),
            com.mizan.launcher.core.ProfileNames.MAX_LEN
        ) { text ->
            prefs.setProfileName(profile, text)
            toast("Now called “${prefs.profileDisplayName(profile)}”")
            rebuild()
        }
    }

    private fun promptName(title: String, initial: String = "", onOk: (String) -> Unit) {
        val input = EditText(this)
        input.setText(initial)
        input.hint = "Name"
        AlertDialog.Builder(this).setTitle(title).setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) onOk(name)
            }
            .setNegativeButton("Cancel", null).show()
    }

    // ------------------------------------------------------------ arrow lock

    /**
     * Set/change the arrow lock (v1.2): if a pattern exists, it must be
     * entered first (unlock-style dialog, dots only); the new pattern is
     * built with arrows and confirmed with arrows (ArrowPinDialog
     * handles enter → confirm, mismatch restarts inside the dialog).
     * No digits, no text field, no soft keyboard anywhere in the flow.
     */
    private fun setArrowLock() {
        val stored = prefs.arrowPin
        if (stored.isNotEmpty()) {
            ArrowPinDialog.showUnlock(this, "Enter current arrow pattern", accent, stored,
                onSuccess = { startSetArrowFlow() },
                onWrongAttempt = { prefs.arrowAttempts = prefs.arrowAttempts + 1 })
        } else {
            startSetArrowFlow()
        }
    }

    private fun startSetArrowFlow() {
        ArrowPinDialog.showSetup(this, "Build your arrow pattern", accent, onDone = { seq ->
            prefs.arrowPin = seq
            toast("Arrow lock set")
            rebuild()
        })
    }

    /** Clear the arrow lock — verified with the pattern first when one is set. */
    private fun clearArrowLock() {
        val stored = prefs.arrowPin
        if (stored.isEmpty()) { toast("No arrow pattern set"); return }
        ArrowPinDialog.showUnlock(this, "Enter pattern to clear the lock", accent, stored,
            onSuccess = {
                prefs.arrowPin = ""
                toast("Arrow lock cleared")
                rebuild()
            },
            onWrongAttempt = { prefs.arrowAttempts = prefs.arrowAttempts + 1 })
    }

    private fun lockManager() {
        // v1.6: AppLoader enumerates the FULL launchable catalogue
        // (core/AppCatalogue merge) — hidden apps, foldered apps and
        // system apps are all here, not just the home-visible set.
        val apps = AppLoader.loadApps(packageManager, packageName)
        val labels = apps.map { app ->
            (if (prefs.lockedApps.contains(app.pkg)) "🔒 " else "") + app.label
        }
        AlertDialog.Builder(this).setTitle("Tap an app to lock / unlock it")
            .setMessage(
                "Every launchable app on this TV is listed — hidden apps, apps inside " +
                    "folders, and system apps (like the TV's own Settings) included. " +
                    "Mizan Launcher itself is the only app not listed. Locking gates " +
                    "launches made through Mizan; the remote's hardware app buttons bypass it."
            )
            .setItems(labels.toTypedArray()) { _, which ->
                val set = prefs.lockedApps.toMutableSet()
                val pkg = apps[which].pkg
                if (!set.remove(pkg)) set.add(pkg)
                prefs.lockedApps = set
                rebuild()
            }
            .setNegativeButton("Close", null).show()
    }

    // ------------------------------------------------------------ Reddit

    /** Edit the subreddit list (comma separated). Invalid names are dropped with a toast. */
    private fun editSubreddits() {
        val input = EditText(this)
        input.setText(prefs.redditSubs)
        input.hint = "wallpaper, wallpapers, EarthPorn"
        AlertDialog.Builder(this)
            .setTitle("Subreddits")
            .setMessage("Top-of-the-week posts from these subreddits become the wallpaper choices. Names only — no “r/” needed.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val raw = input.text.toString().split(",").map { it.trim() }.filter { it.isNotEmpty() }
                val cleaned = raw.map { com.mizan.launcher.core.RedditWallpapers.cleanSubreddit(it) }
                val valid = cleaned.filter { com.mizan.launcher.core.RedditWallpapers.isValidSubreddit(it) }
                val dropped = cleaned - valid.toSet()
                if (valid.isEmpty()) {
                    toast("No valid subreddit names — list not changed")
                } else {
                    prefs.redditSubs = valid.joinToString(",")
                    prefs.redditCache = ""
                    toast(
                        if (dropped.isEmpty()) "Subreddits saved"
                        else "Saved ${valid.size}; dropped invalid: ${dropped.joinToString(", ")}"
                    )
                }
                rebuild()
            }
            .setNegativeButton("Cancel", null).show()
    }

    /**
     * Reddit wallpaper picker: fetch fresh listings (background), cache
     * them, then show the choices. Offline / blocked / empty → fall back
     * to the cached list; if that's empty too, a toast and the current
     * wallpaper stays untouched (fail-open).
     */
    private fun redditPicker() {
        toast("Fetching Reddit wallpapers…")
        val subs = prefs.redditSubs.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        com.mizan.launcher.data.RedditFetcher.fetch(subs, prefs.redditAllowNsfw, this) { images ->
            runOnUiThread {
                if (images.isNotEmpty()) {
                    prefs.redditCache = com.mizan.launcher.core.RedditCacheCodec.encode(images)
                }
                showRedditList(images)
            }
        }
    }

    private fun showRedditList(fresh: List<com.mizan.launcher.core.RedditImage>) {
        val fromCache = fresh.isEmpty()
        val list = if (fromCache) {
            com.mizan.launcher.core.RedditCacheCodec.decode(prefs.redditCache)
        } else {
            fresh
        }
        if (list.isEmpty()) {
            // v1.6: an empty list explains itself — the recorded run
            // status plus a Try again that re-fetches immediately,
            // instead of a bare toast that hid two versions of bugs.
            val err = com.mizan.launcher.data.RedditFetcher.lastError
            Prefs.noteError(this, "redditPicker", Exception(err.ifEmpty { "no images" }))
            AlertDialog.Builder(this)
                .setTitle("No Reddit wallpapers yet")
                .setMessage(
                    "Last try: " + prefs.redditLastStatus.ifEmpty { "—" } +
                        "\n\nReddit isn't providing a photo list right now (or the TV is " +
                        "offline). Your current wallpaper is untouched."
                )
                .setPositiveButton("Try again") { _, _ -> redditPicker() }
                .setNegativeButton("Close", null)
                .show()
            rebuild()
            return
        }
        val labels = ArrayList<String>()
        labels.add("🎲 Surprise me — random wallpaper")
        for (img in list) {
            val dims = if (img.width > 0 && img.height > 0) " · ${img.width}×${img.height}" else ""
            val dl = if (com.mizan.launcher.data.RedditFetcher.isDownloaded(this, img)) " · ✓ on TV" else ""
            val title = img.title.ifEmpty { "(untitled)" }.take(60)
            labels.add("r/${img.subreddit} — $title$dims$dl")
        }
        AlertDialog.Builder(this)
            .setTitle(
                if (fromCache) "Reddit wallpapers (cached — fetch failed, showing last list)"
                else "Reddit wallpapers — top of the week"
            )
            .setItems(labels.toTypedArray()) { _, which ->
                val chosen = if (which == 0) list.random() else list[which - 1]
                selectRedditImage(chosen)
            }
            .setNegativeButton("Cancel", null).show()
    }

    /** Download (if needed) and apply one Reddit wallpaper. Fail-open. */
    private fun selectRedditImage(item: com.mizan.launcher.core.RedditImage) {
        val fetcher = com.mizan.launcher.data.RedditFetcher
        if (fetcher.isDownloaded(this, item)) {
            applyRedditWallpaper(item, fetcher.localFile(this, item))
            return
        }
        toast("Downloading wallpaper…")
        kotlin.concurrent.thread(name = "mizan-reddit-dl") {
            val file = fetcher.download(this, item)
            runOnUiThread {
                if (file != null) {
                    applyRedditWallpaper(item, file)
                } else {
                    Prefs.noteError(this, "redditDownload",
                        Exception(fetcher.lastError.ifEmpty { "download failed" }))
                    toast("Download failed — current wallpaper kept")
                    rebuild()
                }
            }
        }
    }

    private fun applyRedditWallpaper(item: com.mizan.launcher.core.RedditImage, file: java.io.File) {
        prefs.wallpaperKind = "reddit"
        prefs.wallpaperValue = file.absolutePath
        AccentUtil.recompute(this, prefs)
        toast("Wallpaper set: ${item.title.take(40)}")
        rebuild()
    }

    // ------------------------------------------------------------ weather / watcher

    private fun weatherLocation() {
        val input = EditText(this)
        input.setText("${prefs.weatherLat}, ${prefs.weatherLon}")
        input.hint = "latitude, longitude"
        AlertDialog.Builder(this)
            .setTitle("Weather location")
            .setMessage("Default is Bloomington, IN (39.1653, -86.5264). Weather comes from Open-Meteo, no account or key needed.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val parts = input.text.toString().split(",").map { it.trim() }
                val lat = parts.getOrNull(0)?.toDoubleOrNull()
                val lon = parts.getOrNull(1)?.toDoubleOrNull()
                if (lat != null && lon != null) {
                    prefs.weatherLat = parts[0]; prefs.weatherLon = parts[1]
                    prefs.cachedWeather = ""; prefs.weatherData = ""; prefs.lastWeatherFetch = 0
                    toast("Weather location saved")
                } else toast("That didn't parse as latitude, longitude — not changed")
                rebuild()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun watcherInfo() {
        AlertDialog.Builder(this)
            .setTitle("Home watcher (optional)")
            .setMessage(
                "Android sometimes brings back the TV's own home screen when you press Home. " +
                    "The watcher is a small accessibility service that notices when the stock home " +
                    "screen appears and reopens Mizan Launcher instead.\n\n" +
                    "It only watches which window is on screen — it cannot read screen contents, " +
                    "and it sends nothing anywhere.\n\n" +
                    "It is OFF by default. To turn it on, open the TV's Settings → " +
                    "Device Preferences → Accessibility → Installed services → " +
                    "“Mizan Launcher watcher” — that exact name (older builds listed it as " +
                    "just “Mizan Launcher”, which is why it was hard to find) — and turn it on " +
                    "yourself. Turn it off there any time.\n\n" +
                    "Still not listed? Sideloaded apps are hidden from Accessibility on some " +
                    "Google TV builds until you allow them: Android system Settings → Apps → " +
                    "Mizan Launcher → ⋮ (top-right) → “Allow restricted settings”, then check " +
                    "Accessibility again."
            )
            .setPositiveButton("Open Accessibility settings") { _, _ ->
                try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                catch (e: Exception) { failOpen("a11ySettings", e) }
            }
            .setNegativeButton("Cancel", null).show()
    }

    // ------------------------------------------------------------ backup

    /** Serialize one profile's settings; backup includes both profiles + global values. */
    private fun exportJson(): String {
        val root = JSONObject()
        root.put("app", "mizan-launcher")
        root.put("version", 1)
        // v1.2: the lock is an arrow pattern now; like the old PIN, the
        // pattern itself is deliberately NOT exported (flag only).
        root.put("arrow_lock_set", prefs.arrowPin.isNotEmpty())
        root.put("safe_mode", prefs.safeMode)
        root.put("weather_lat", prefs.weatherLat)
        root.put("weather_lon", prefs.weatherLon)
        root.put("current_profile", prefs.currentProfile)
        // v1.4 globals: guest designation. (Recently Used is NOT
        // backed up — it is a usage trace, not a setting.)
        root.put("guest_profile", prefs.guestProfile)
        root.put("guest_lock_settings", prefs.guestLockSettings)
        // v1.5 global: the Settings entry lock.
        root.put("lock_settings", prefs.lockSettings)
        val profiles = JSONObject()
        val current = prefs.currentProfile
        for (p in listOf(1, 2)) {
            prefs.currentProfile = p
            val o = JSONObject()
            o.put("theme", prefs.themeId)
            o.put("wall_kind", prefs.wallpaperKind)
            o.put("wall_value", prefs.wallpaperValue)
            o.put("icon_shape", prefs.iconShape)
            o.put("app_size", prefs.appSize)
            o.put("columns", prefs.columns)
            o.put("layout", com.mizan.launcher.core.LayoutCodec.encode(prefs.layout))
            o.put("locked", JSONArray(prefs.lockedApps.toList()))
            o.put("reddit_subs", prefs.redditSubs)
            o.put("reddit_nsfw", prefs.redditAllowNsfw)
            // v1.4 per-profile extra: the rotation interval setting
            // (not the rotation clock/index — those are runtime state).
            o.put("reddit_rotation", prefs.redditRotation)
            o.put("home_hint_dismissed", prefs.homeHintDismissed)
            // v1.2 per-profile extra.
            o.put("weather_units", prefs.weatherUnitsKey)
            // v1.7 per-profile extra: the home style.
            o.put("home_style", prefs.homeStyle)
            // v1.9 per-profile extras: custom name, the unlock-grace
            // window, the sticky status bar.
            o.put("profile_name", prefs.profileName(p))
            o.put("unlock_grace_min", prefs.unlockGraceMinutes)
            o.put("sticky_status_bar", prefs.stickyStatusBar)
            profiles.put(p.toString(), o)
        }
        prefs.currentProfile = current
        root.put("profiles", profiles)
        return root.toString(2)
    }

    private fun importJson(text: String): Boolean {
        return try {
            val root = JSONObject(text)
            if (root.optString("app") != "mizan-launcher") return false
            prefs.safeMode = root.optBoolean("safe_mode", prefs.safeMode)
            prefs.weatherLat = root.optString("weather_lat", prefs.weatherLat)
            prefs.weatherLon = root.optString("weather_lon", prefs.weatherLon)
            if (root.has("guest_profile")) {
                prefs.guestProfile = root.optInt("guest_profile", prefs.guestProfile)
            }
            if (root.has("guest_lock_settings")) {
                prefs.guestLockSettings =
                    root.optBoolean("guest_lock_settings", prefs.guestLockSettings)
            }
            if (root.has("lock_settings")) {
                prefs.lockSettings = root.optBoolean("lock_settings", prefs.lockSettings)
            }
            val profiles = root.optJSONObject("profiles") ?: return false
            val current = prefs.currentProfile
            for (p in listOf(1, 2)) {
                val o = profiles.optJSONObject(p.toString()) ?: continue
                prefs.currentProfile = p
                prefs.themeId = o.optString("theme", prefs.themeId)
                prefs.wallpaperKind = o.optString("wall_kind", prefs.wallpaperKind)
                prefs.wallpaperValue = o.optString("wall_value", prefs.wallpaperValue)
                prefs.iconShape = o.optString("icon_shape", prefs.iconShape)
                prefs.appSize = o.optString("app_size", prefs.appSize)
                prefs.columns = o.optInt("columns", prefs.columns)
                prefs.layout = com.mizan.launcher.core.LayoutCodec.decode(o.optString("layout", ""))
                val locked = o.optJSONArray("locked")
                if (locked != null) {
                    val set = mutableSetOf<String>()
                    for (i in 0 until locked.length()) set.add(locked.optString(i))
                    prefs.lockedApps = set
                }
                if (o.has("reddit_subs")) prefs.redditSubs = o.optString("reddit_subs", prefs.redditSubs)
                if (o.has("reddit_nsfw")) prefs.redditAllowNsfw = o.optBoolean("reddit_nsfw", false)
                if (o.has("reddit_rotation")) prefs.redditRotation = o.optString("reddit_rotation", prefs.redditRotation)
                if (o.has("home_hint_dismissed")) prefs.homeHintDismissed = o.optBoolean("home_hint_dismissed", false)
                if (o.has("weather_units")) prefs.weatherUnitsKey = o.optString("weather_units", prefs.weatherUnitsKey)
                // v1.7: absent in pre-v1.7 backups → keep the
                // profile's current style (the Prefs setter also
                // normalizes any garbled value to "tv").
                if (o.has("home_style")) prefs.homeStyle = o.optString("home_style", prefs.homeStyle)
                // v1.9: absent in pre-v1.9 backups → keep current.
                // The name setter normalizes (the guarded-import
                // pattern); the grace setter coerces to a legal
                // window.
                if (o.has("profile_name")) prefs.setProfileName(p, o.optString("profile_name", ""))
                if (o.has("unlock_grace_min")) prefs.unlockGraceMinutes = o.optInt("unlock_grace_min", prefs.unlockGraceMinutes)
                if (o.has("sticky_status_bar")) prefs.stickyStatusBar = o.optBoolean("sticky_status_bar", false)
            }
            prefs.currentProfile = root.optInt("current_profile", current).coerceIn(1, 2)
            true
        } catch (e: Exception) {
            Prefs.noteError(this, "importBackup", e)
            false
        }
    }

    // ------------------------------------------------------------ activity results

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            when (requestCode) {
                41 -> {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(exportJson().toByteArray(Charsets.UTF_8))
                    }
                    toast("Settings exported")
                }
                42 -> {
                    val text = contentResolver.openInputStream(uri)?.use { input ->
                        BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText()
                    } ?: ""
                    if (importJson(text)) { toast("Settings imported"); rebuild() }
                    else toast("That file isn't a Mizan Launcher backup — nothing imported")
                }
                43 -> {
                    try {
                        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } catch (e: Exception) {
                        // Some providers don't grant persistable access; the
                        // wallpaper then lasts until permission is revoked.
                    }
                    prefs.wallpaperKind = "image"
                    prefs.wallpaperValue = uri.toString()
                    AccentUtil.recompute(this, prefs)
                    toast("Wallpaper set")
                    rebuild()
                }
            }
        } catch (e: Exception) {
            failOpen("activityResult:$requestCode", e)
        }
    }
}
