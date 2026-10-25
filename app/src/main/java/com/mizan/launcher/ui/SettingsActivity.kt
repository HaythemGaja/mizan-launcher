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
import com.mizan.launcher.core.SortGrab
import com.mizan.launcher.core.SettingsNavMachine
import com.mizan.launcher.core.Themes
import com.mizan.launcher.core.WeatherUnits
import com.mizan.launcher.data.AccentUtil
import com.mizan.launcher.data.AppEntry
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

    /**
     * The ONE true version label (v2.3.1): read from PackageManager
     * at runtime, so it can never go stale at a version bump — the
     * previous build shipped with one stale hardcoded label in the
     * nav pane and a different one in Data & about, both wrong in
     * the same diagnostics photo. The constant below is ONLY the
     * fallback for a lookup that throws; keep it in step at bumps,
     * but a stale fallback now requires the lookup to fail first.
     */
    private fun appVersionLabel(): String {
        val name = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            null
        }
        return "Sreeb v" + (name ?: VERSION_FALLBACK)
    }

    private lateinit var prefs: Prefs
    private lateinit var layout: LayoutData
    private var accent: Int = 0
    private var textColour: Int = 0
    private var subColour: Int = 0

    /** True while the "Make Sreeb your home screen" checklist replaces the home category's detail. */
    private var showingMakeHome: Boolean = false

    // ------------------------------------------------ two-column state

    /** Category ids + titles, in left-column order (v1.5 reorganization). */
    private val categories: List<Pair<String, String>> = listOf(
        "home" to "Home & launcher",
        "look" to "Look & wallpaper",
        "homescreen" to "Home screen",
        "sortsections" to "Sort sections",
        "statusbar" to "Status bar",
        "weather" to "Weather",
        "lockprofiles" to "Lock & profiles",
        "nowplaying" to "Now playing",
        "iptv" to "IPTV",
        "data" to "Data & about"
    )

    /** v1.6: one glyph per category in the nav list (visual anchors). */
    private val categoryGlyphs: Map<String, String> = mapOf(
        "home" to "🏠", "look" to "🎨", "homescreen" to "▦", "sortsections" to "↕",
        "statusbar" to "🕐",
        "weather" to "🌦", "lockprofiles" to "🔒", "nowplaying" to "♪",
        "iptv" to "📺", "data" to "💾"
    )

    /** v1.6: one-line description under the category name in the detail header. */
    private val categoryDescriptions: Map<String, String> = mapOf(
        "home" to "Default home screen, the watcher, and Speed up",
        "look" to "Theme, wallpaper, icons — and the Reddit source",
        "homescreen" to "Home rows, hidden apps, and folders",
        "sortsections" to "Reorder and hide home sections for this style",
        "statusbar" to "The top bar: clock, date, weather, quick tiles",
        "weather" to "Location and units for the home weather",
        "lockprofiles" to "Arrow pattern, app locks, profiles, Guest",
        "nowplaying" to "What's playing in other apps, shown at home",
        "iptv" to "Your IPTV account, playlist, and catalogues",
        "data" to "Backup, safe mode, and diagnostics"
    )

    /** Tag marking a detail view as a card title (see groupDetailIntoCards). */
    private companion object {
        const val CARD_HEADER_TAG = "cardHeader"

        /** Fail-open fallback for [appVersionLabel] — see its doc. */
        const val VERSION_FALLBACK = "2.3.1"
    }

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

    /**
     * Hold-and-drop sort state (v3.4, ask 50): the grab machine
     * for the "Sort sections" page, or null when no row is held.
     * [sortGrabKey]/[sortGrabBranch] name the held row
     * ("tracker" = Tracker/Watch via TrackerSections, "tv" = TV
     * via LayoutStore rowOrder). IPTV catalogue rows are not
     * reorderable (v3.1) and never grab. Moves persist at once
     * (like the Up/Down buttons); Back cancels by restoring the
     * grab-time snapshot, so a cancelled grab changes nothing.
     */
    private var sortGrab: SortGrab.State? = null
    private var sortGrabKey: String? = null
    private var sortGrabBranch: String = ""

    /**
     * The focused control's INDEX in its page, per category id
     * (v2.8): the restore fallback when the remembered title no
     * longer exists after a rebuild (e.g. a row that removes
     * itself, like "Clear IPTV sources…") — focus lands on the
     * NEAREST row, never bounced to the page top.
     */
    private val detailFocusIndexMemory = mutableMapOf<String, Int>()

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

    /**
     * The IPTV page's progress/updated line (v2.5): ingest
     * progress ticks update THIS view's text only — see
     * [onIptvProgressTick]. Cleared on every rebuild; the IPTV
     * page builder re-registers it when it builds the line.
     */
    private var iptvProgressView: TextView? = null

    /**
     * Ingest progress callback (v2.5): while the ingest runs, a
     * tick updates only the progress line's text. The v2.4 shape
     * rebuilt the WHOLE settings page on every tick (throttled to
     * one per 2 s at the source, but a minutes-long ingest still
     * meant a full page composition — potentially including the
     * diagnostics page — every couple of seconds). Completion —
     * isIngesting() is false by the time the final callback runs —
     * does the one full rebuild.
     */
    private fun onIptvProgressTick() {
        if (isFinishing || isDestroyed) return
        val client = com.mizan.launcher.data.IptvClient
        if (client.isIngesting()) {
            iptvProgressView?.text = client.ingestProgressText()
        } else {
            rebuild()
        }
    }

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
        com.mizan.launcher.data.DiagLog.note("settings opened")
        nav = SettingsNavMachine(categories.map { it.first })
        // v3.1 (ask 41): deep-link from the quick-settings
        // "Sort sections" entry (or any future internal target) —
        // an unknown value keeps the default first category.
        val deep = intent.getStringExtra("category")
        val deepIdx = categories.indexOfFirst { it.first == deep }
        if (deepIdx >= 0) nav.select(deepIdx)
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

    /**
     * v2.8 nav-audit safety net: when focus returns to this window
     * (a dialog dismissed, a sub-activity finished) and NOTHING in
     * the hierarchy holds focus, re-run the standard restore —
     * focus must never be stranded on nothing after a dialog,
     * whatever order its dismiss/rebuild happened in.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && entered && ::prefs.isInitialized) {
            val root = window?.decorView ?: return
            if (root.findFocus() == null) {
                try {
                    restoreFocus(root)
                } catch (e: Exception) {
                    // The net itself must never crash Settings.
                }
            }
        }
    }

    // ---------------------------------------------------------------- layout

    /**
     * v3.0: every Settings build runs behind this guard (the
     * IptvActivity rule, generalized): a throw anywhere in the
     * composition — including an inflation OOM — is recorded and
     * replaced by a worded screen, never a black one. The detail
     * page dispatch carries its own finer guard inside
     * [rebuildInner], so one broken page degrades alone.
     */
    private fun rebuild() {
        try {
            rebuildInner()
        } catch (e: Throwable) {
            Prefs.noteError(this, "settingsRebuild", e)
            try {
                val line = TextView(this)
                line.text = "Settings hit a snag — press Back and try again. " +
                    "The error is noted in the launcher's diagnostics."
                line.setTextColor(0xFFFFFFFF.toInt())
                line.textSize = 17f
                line.setPadding(
                    Ui.dp(this, 48), Ui.dp(this, 48), Ui.dp(this, 48), Ui.dp(this, 48)
                )
                setContentView(line)
            } catch (ignored: Throwable) {
                // Leave whatever is on screen rather than crash.
            }
        }
    }

    private fun rebuildInner() {
        iptvProgressView = null
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
        // v3.0: the page dispatch is contained — one broken
        // settings page renders an error line in the detail pane
        // while the nav and every other page keep working.
        try {
            buildDetail(detail, nav.selectedId)
        } catch (e: Throwable) {
            Prefs.noteError(this, "settingsPage:${nav.selectedId}", e)
            val line = TextView(this)
            line.text = "This settings page hit a snag — the error is noted in Diagnostics."
            line.setTextColor(subColour)
            line.textSize = 16f
            detail.addView(line)
        }
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
                    // v2.8: title hit → that row; title GONE (the
                    // row removed itself) → the row now sitting at
                    // the remembered index (the nearest row), and
                    // only then the page top.
                    val rememberedIndex = detailFocusIndexMemory[nav.selectedId]
                    val target = detailFocusable.firstOrNull { it.tag == remembered }
                        ?: rememberedIndex?.let {
                            detailFocusable.getOrNull(it.coerceIn(0, detailFocusable.size - 1))
                        }
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
        version.text = appVersionLabel()
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
                        // v2.8 nav audit: only act if this row
                        // STILL holds focus — fast D-pad travel
                        // queues several of these posts, and a
                        // stale one re-selected an older category
                        // and yanked focus back mid-navigation.
                        if (tv.hasFocus() && nav.selectedIndex != index) {
                            nav.select(index)
                            com.mizan.launcher.data.DiagLog.note("settings page: ${nav.selectedId}")
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
            com.mizan.launcher.data.DiagLog.note("settings page: ${nav.selectedId}")
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
            // v3.4 (ask 50): while a sort grab is active, the
            // D-pad drives the grabbed row instead of focus:
            // up/down moves it live, OK drops it. LEFT leaves
            // the page AND cancels the grab (snapshot restored)
            // before the normal LEFT handling runs below. Back
            // is handled in onBackPressed (same cancel).
            if (sortGrab != null) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        sortGrabMove(-1)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        sortGrabMove(+1)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        sortGrabDrop()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> sortGrabCancel()
                }
            }
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
        // v3.4 (ask 50): Back while a sort grab is active
        // CANCELS the grab (snapshot restored) and stays on the
        // page — it takes precedence over leaving the page.
        if (sortGrab != null) {
            sortGrabCancel()
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
            "sortsections" -> buildSortSectionsDetail(content)
            "statusbar" -> buildStatusBarDetail(content)
            "weather" -> buildWeatherDetail(content)
            "lockprofiles" -> buildLockProfilesDetail(content)
            "nowplaying" -> buildNowPlayingDetail(content)
            "iptv" -> buildIptvDetail(content)
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
        content.addView(info("Speed up signals OTHER apps' background processes to exit (it disables and uninstalls nothing, and Android may restart what it wants — the numbers are informational). The never-kill list, system apps, the foreground app, and Sreeb itself are never signalled."))
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
                content.addView(info("Forecast: press OK on the clock / date / weather in the home bar to open the full forecast — the next 24 hours and the 7-day outlook, in these units."))
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
        content.addView(info("Forgot the pattern? Recovery is Android system Settings → Apps → Sreeb → Clear data — that resets the layout too. This is a casual lock, by design."))
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

    /**
     * IPTV (v2.2): the sources behind the IPTV home style, browser
     * and guide. HARD RULE (spec §20.2): the values edited here are
     * credentials — they live only in prefs; this screen shows the
     * server + username so the account can be checked (like his web
     * player does), the password is never displayed, and NOTHING
     * here reaches backups, diagnostics or logs.
     */
    private fun buildIptvDetail(content: LinearLayout) {
        val acct = prefs.iptvAccount()
        content.addView(header("IPTV account (Xtream)"))
        content.addView(info("Use the same server, username and password as in your IPTV web player. They're stored only on this TV — never in backups, diagnostics or logs."))
        content.addView(row("Server", acct.server.ifEmpty { "Not set" }) {
            KeyboardDialog.show(this, "IPTV server", accent, prefs.iptvServer, 120, symbols = true) { text ->
                prefs.iptvServer = text.trim()
                com.mizan.launcher.data.IptvClient.invalidateEpg()
                // v2.4: saving the account IS the refresh request.
                com.mizan.launcher.data.IptvClient.refreshAsync(this) { onIptvProgressTick() }
                rebuild()
            }
        })
        content.addView(row("Username", prefs.iptvUser.ifEmpty { "Not set" }) {
            KeyboardDialog.show(this, "IPTV username", accent, prefs.iptvUser, 60, symbols = true) { text ->
                prefs.iptvUser = text.trim()
                com.mizan.launcher.data.IptvClient.refreshAsync(this) { onIptvProgressTick() }
                rebuild()
            }
        })
        content.addView(row("Password", if (prefs.iptvPass.isEmpty()) "Not set" else "•••••••• (set)") {
            KeyboardDialog.show(this, "IPTV password", accent, prefs.iptvPass, 60, symbols = true, masked = true) { text ->
                prefs.iptvPass = text
                com.mizan.launcher.data.IptvClient.refreshAsync(this) { onIptvProgressTick() }
                rebuild()
            }
        })
        content.addView(header("Playlist (M3U)"))
        content.addView(info("A second, independent source: paste a playlist URL and its channels join the Live tab and the IPTV home's Live rows (both sources can be on at once; channels are not merged or de-duplicated). The optional guide (XMLTV) URL feeds the programme guide for playlist channels, matched by tvg-id. Playlist and guide URLs can contain credentials of their own — same rule: stored only here, never exported or logged."))
        content.addView(row("Playlist (M3U) URL", if (prefs.iptvM3uUrl.isEmpty()) "Not set" else "Set") {
            KeyboardDialog.show(this, "M3U playlist URL", accent, prefs.iptvM3uUrl, 300, symbols = true) { text ->
                prefs.iptvM3uUrl = text.trim()
                com.mizan.launcher.data.IptvClient.refreshAsync(this) { onIptvProgressTick() }
                rebuild()
            }
        })
        content.addView(row("Guide (XMLTV) URL", if (prefs.iptvEpgUrl.isEmpty()) "Not set" else "Set") {
            KeyboardDialog.show(this, "XMLTV guide URL", accent, prefs.iptvEpgUrl, 300, symbols = true) { text ->
                prefs.iptvEpgUrl = text.trim()
                com.mizan.launcher.data.IptvClient.invalidateEpg()
                com.mizan.launcher.data.IptvClient.refreshAsync(this) { onIptvProgressTick() }
                rebuild()
            }
        })
        content.addView(header("Catalogue"))
        // v2.4: counts come from the disk store's INDEX (no
        // catalogue is materialized to count it), the line shows
        // the store size, and the section says when the data was
        // updated + live ingest progress while a refresh runs.
        // Data loads ONLY when the account is saved or Refresh is
        // tapped — opening screens never fetches.
        val idx = com.mizan.launcher.data.IptvClient.storeIndex(this)
        val kind = com.mizan.launcher.data.IptvClient.status(this)
        content.addView(info(
            "Status: " + com.mizan.launcher.core.IptvStatus.diagText(
                kind, idx?.liveCount ?: 0, idx?.vodCount ?: 0, idx?.seriesCount ?: 0,
                prefs.iptvFavoriteList().size,
                com.mizan.launcher.data.IptvClient.epgChannelCount(this), "",
                com.mizan.launcher.data.IptvClient.lastErrorClass(this),
                com.mizan.launcher.core.IptvStore.sizeText(
                    com.mizan.launcher.data.IptvClient.storeBytes(this)
                )
            )
        ))
        val progress = com.mizan.launcher.data.IptvClient.ingestProgressText()
        if (progress.isNotEmpty()) {
            // v2.5: registered as the tick target — progress
            // updates rewrite this line's text, never the page.
            val pv = info(progress)
            iptvProgressView = pv
            content.addView(pv)
        } else if (idx != null && idx.fetchedAt > 0L) {
            val timeText = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
                .format(java.util.Date(idx.fetchedAt))
            val uv = info("Updated $timeText — data refreshes only when you save the account or tap Refresh.")
            iptvProgressView = uv
            content.addView(uv)
        }
        content.addView(row("Refresh now", "Fetch the catalogues + programme guide again") {
            com.mizan.launcher.data.DiagLog.note("iptv refresh tapped")
            com.mizan.launcher.data.IptvClient.refreshAsync(this, force = true) { onIptvProgressTick() }
            toast("Refreshing IPTV…")
        })
        if (prefs.iptvAnyConfigured()) {
            content.addView(row("Clear IPTV sources…", "Removes the account and playlist URLs from this TV") {
                AlertDialog.Builder(this).setTitle("Clear IPTV sources?")
                    .setMessage("The Xtream account, playlist and guide URLs will be removed from this TV. Favorites and resume positions stay.")
                    .setPositiveButton("Clear") { _, _ ->
                        prefs.clearIptvAccount()
                        com.mizan.launcher.data.IptvClient.invalidateEpg()
                        // v2.4: the on-disk store goes with the sources.
                        com.mizan.launcher.data.IptvClient.clearStore(this@SettingsActivity)
                        rebuild()
                        toast("IPTV sources cleared")
                    }
                    .setNegativeButton("Cancel", null).show()
            })
        }
        content.addView(info("Category locks: hide any category behind your arrow pattern from the IPTV browser — hold OK on a category chip. Categories with adult names (adult, xxx, +18, 18+) start locked when a pattern is set."))
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
        // v2.6: the launcher's OWN TMDB key (optional). TMDB reads
        // normally go through the tracker app (its key never leaves
        // the tracker); when the proxy fails, a key saved here lets
        // Sreeb call TMDB directly as a fallback. Same hygiene as
        // the IPTV credentials: stored only in prefs — never in
        // backups, diagnostics or logs — and shown as set/not-set.
        content.addView(header("TMDB"))
        content.addView(info("Optional. Sreeb reads TMDB through the tracker app first; if that fails, a key saved here lets Sreeb reach TMDB directly instead. It's stored only on this TV — never in backups, diagnostics or logs. Leave it empty to clear it."))
        content.addView(row("TMDB API key", if (prefs.tmdbKey.isEmpty()) "Not set" else "Set") {
            KeyboardDialog.show(this, "TMDB API key", accent, prefs.tmdbKey, 80, symbols = true, masked = true) { text ->
                prefs.tmdbKey = text.trim()
                rebuild()
            }
        })
        content.addView(header(appVersionLabel()))
        content.addView(info(diagnosticsText()))
        content.addView(row("Copy diagnostics", "Copy all of the above — including the freeze/crash logs — to report a problem") {
            try {
                val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                cm.setPrimaryClip(
                    android.content.ClipData.newPlainText("Sreeb diagnostics", diagnosticsText())
                )
                toast("Diagnostics copied")
            } catch (e: Exception) {
                failOpen("copyDiagnostics", e)
            }
        })
        // v3.3 (ask 46): safe-mode EXIT — shown ONLY while the
        // startup shell is engaged. This is NOT the "Safe mode:
        // plain launcher" toggle below (prefs.safeMode): this
        // resets the crash counter and returns to the normal
        // home style immediately (MainActivity re-reads the
        // armed flag on rebuild). If the normal home then dies
        // twice again, safe mode re-engages on its own — the
        // safety net is not disabled by exiting.
        if (com.mizan.launcher.data.StartupHealthStore.safeShellArmed) {
            content.addView(row("Exit safe mode and retry normal home", "Safe mode is on after repeated start crashes — this resets the counter and returns to your normal home style now") {
                try {
                    com.mizan.launcher.data.StartupHealthStore.exitSafeShell(this)
                    toast("Safe mode off — returning to your normal home")
                } catch (e: Exception) {
                    failOpen("exitSafeMode", e)
                }
                finish()
            })
        }
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
        content.addView(toggleRow("Sticky status bar", prefs.stickyStatusBar, "The bar stays pinned at the top while the home scrolls — in the TV, Tracker and IPTV views") {
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
        content.addView(toggleRow("Tile: Search", sb.showTileSearch, "Opens Sreeb's app Search screen (the remote's Search key works too)") { updateStatusBar { c -> c.copy(showTileSearch = it) } })
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
                    // v2.8: dismiss before the rebuilding apply
                    // (see pickFrom).
                    dlg.dismiss()
                    updateStatusBar { c -> c.copy(dateFormat = keys[which]) }
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

        // v2.0: Tracker-view section controls (per profile; the codec
        // is core/TrackerSections). Placed in this category because it
        // sits next to the Home style picker it belongs to. The hero
        // is always first when shown; the other sections reorder.
        content.addView(header("Tracker sections"))
        content.addView(info("Only applies when Home style is Tracker or Watch. A section with nothing in it never appears, even when switched on. For You also needs TV Shows & Movies Tracker 1.14.4 or newer."))
        val sectionsCfg = prefs.trackerSectionsConfig()
        val ts = com.mizan.launcher.core.TrackerSections
        content.addView(toggleRow(
            "Section: Hero", ts.isShown(sectionsCfg, ts.HERO),
            "The big featured title at the top — always first when shown"
        ) { shown ->
            prefs.setTrackerSectionsConfig(ts.setShown(prefs.trackerSectionsConfig(), ts.HERO, shown))
            rebuild()
        })
        for (key in sectionsCfg.order) {
            content.addView(toggleRow(
                "Section: ${ts.label(key)}", ts.isShown(sectionsCfg, key), ""
            ) { shown ->
                prefs.setTrackerSectionsConfig(
                    ts.setShown(prefs.trackerSectionsConfig(), key, shown)
                )
                rebuild()
            })
        }
        content.addView(row(
            "Reorder tracker sections…",
            sectionsCfg.order.joinToString(" › ") { ts.label(it) }
        ) { trackerSectionOrder() })
    }

    /**
     * v3.1 (ask 41): "Sort home sections" — one page for the
     * ACTIVE home style, reusing the existing order stores only
     * (no second store is invented): Tracker/Watch via
     * TrackerSections (order + hidden, per profile), TV via
     * LayoutStore rowOrder + rowVisible, IPTV via LayoutStore for
     * its favorites/apps rows only — catalogue rows follow the
     * IPTV store order and have no user sort store, so the page
     * says so instead of offering fake controls. Each row is a
     * D-pad row (the row() helper: focusable, scroll-into-view,
     * focus memory per the v2.8 rules); tapping it opens the
     * Move up / Move down / Hide-or-Show dialog (the rowOptions
     * idiom). Every action persists at once and rebuilds the
     * page, so the home picks it up on return. The existing
     * hold-OK hide controls keep working — they edit the same
     * stores.
     */
    private fun buildSortSectionsDetail(content: LinearLayout) {
        val ts = com.mizan.launcher.core.TrackerSections
        val hs = com.mizan.launcher.core.HomeStyle
        content.addView(header("Sort home sections"))
        when {
            hs.isTracker(prefs.homeStyle) || hs.isWatch(prefs.homeStyle) -> {
                val cfg = prefs.trackerSectionsConfig()
                content.addView(info(
                    "Home style: ${homeStyleLabel()} — tap a section, then Move up, " +
                        "Move down, or Hide/Show. Changes apply at once. Hold OK on a " +
                        "row to grab it, then D-pad up/down to move it, OK to drop, " +
                        "Back to cancel."
                ))
                if (sortGrabKey != null && sortGrabBranch == "tracker") {
                    content.addView(info(
                        "Holding “${ts.label(sortGrabKey!!)}” — D-pad up/down moves it, " +
                            "OK drops it, Back cancels."
                    ))
                }
                content.addView(row(
                    "Hero — ${if (ts.isShown(cfg, ts.HERO)) "Shown" else "Hidden"}",
                    "Always first when shown — Hide/Show only"
                ) {
                    sortSectionDialog(ts.label(ts.HERO), canMove = false,
                        shown = ts.isShown(cfg, ts.HERO)) { action ->
                        applyTrackerSortAction(ts.HERO, action)
                    }
                })
                for ((i, key) in cfg.order.withIndex()) {
                    val held = sortGrabKey == key && sortGrabBranch == "tracker"
                    val tv = row(
                        "${i + 1}. ${ts.label(key)} — " +
                            if (ts.isShown(cfg, key)) "Shown" else "Hidden",
                        if (held) "◉ Held — D-pad up/down to move, OK to drop, Back to cancel"
                        else "Tap for options — hold OK to grab"
                    ) {
                        sortSectionDialog(ts.label(key), canMove = true,
                            shown = ts.isShown(cfg, key)) { action ->
                            applyTrackerSortAction(key, action)
                        }
                    }
                    tv.setOnLongClickListener {
                        sortGrabStart(key, "tracker")
                        true
                    }
                    if (held) tv.background = Ui.tileBackground(accent, true, 12, this)
                    content.addView(tv)
                }
            }
            hs.isIptv(prefs.homeStyle) -> {
                content.addView(info(
                    "Home style: IPTV — catalogue rows follow your IPTV store order " +
                        "and can't be reordered here. Folders, Favorites and Apps " +
                        "below can be hidden or shown."
                ))
                for (key in listOf("folders", "favorites", "apps")) {
                    content.addView(row(
                        "${rowLabel(key)} — " +
                            if (layout.isRowVisible(key)) "Shown" else "Hidden",
                        "Tap for options"
                    ) {
                        sortSectionDialog(rowLabel(key), canMove = false,
                            shown = layout.isRowVisible(key)) { action ->
                            if (action == SortAction.TOGGLE) {
                                layout.rowVisible[key] = !layout.isRowVisible(key)
                                saveLayout()
                                rebuild()
                            }
                        }
                    })
                }
            }
            else -> {
                content.addView(info(
                    "Home style: TV — tap a row, then Move up, Move down, or " +
                        "Hide/Show. Changes apply at once. Hold OK on a row to " +
                        "grab it, then D-pad up/down to move it, OK to drop, " +
                        "Back to cancel."
                ))
                val effectiveOrder = com.mizan.launcher.core.RowOrder.normalize(layout.rowOrder)
                if (effectiveOrder != layout.rowOrder) {
                    layout.rowOrder.clear()
                    layout.rowOrder.addAll(effectiveOrder)
                }
                if (sortGrabKey != null && sortGrabBranch == "tv") {
                    content.addView(info(
                        "Holding “${rowLabel(sortGrabKey!!)}” — D-pad up/down moves it, " +
                            "OK drops it, Back cancels."
                    ))
                }
                for ((i, key) in layout.rowOrder.withIndex()) {
                    val held = sortGrabKey == key && sortGrabBranch == "tv"
                    val tv = row(
                        "${i + 1}. ${rowLabel(key)} — " +
                            if (layout.isRowVisible(key)) "Shown" else "Hidden",
                        if (held) "◉ Held — D-pad up/down to move, OK to drop, Back to cancel"
                        else "Tap for options — hold OK to grab"
                    ) {
                        sortSectionDialog(rowLabel(key), canMove = true,
                            shown = layout.isRowVisible(key)) { action ->
                            when (action) {
                                SortAction.TOGGLE ->
                                    layout.rowVisible[key] = !layout.isRowVisible(key)
                                SortAction.UP -> moveRow(key, -1)
                                SortAction.DOWN -> moveRow(key, +1)
                            }
                            saveLayout()
                            rebuild()
                        }
                    }
                    tv.setOnLongClickListener {
                        sortGrabStart(key, "tv")
                        true
                    }
                    if (held) tv.background = Ui.tileBackground(accent, true, 12, this)
                    content.addView(tv)
                }
            }
        }
    }

    /** One sort dialog's outcome (ask 41). */
    private enum class SortAction { UP, DOWN, TOGGLE }

    /** The Move up / Move down / Hide-or-Show dialog for one sort-page row. */
    private fun sortSectionDialog(
        label: String,
        canMove: Boolean,
        shown: Boolean,
        apply: (SortAction) -> Unit
    ) {
        val options = mutableListOf<String>()
        if (canMove) {
            options.add("Move up")
            options.add("Move down")
        }
        options.add(if (shown) "Hide" else "Show")
        AlertDialog.Builder(this).setTitle(label)
            .setItems(options.toTypedArray()) { _, which ->
                val action = if (canMove) {
                    when (which) {
                        0 -> SortAction.UP
                        1 -> SortAction.DOWN
                        else -> SortAction.TOGGLE
                    }
                } else {
                    SortAction.TOGGLE
                }
                apply(action)
            }
            .setNegativeButton("Cancel", null).show()
    }

    /**
     * Hold-and-drop sort (v3.4, ask 50) — the wiring around the
     * pure core/SortGrab machine. A long-press on a sort-page row
     * grabs it; D-pad up/down moves it live (persisted at once,
     * like the Up/Down buttons); OK drops it; Back cancels it by
     * restoring the grab-time snapshot. The existing Up/Down/Hide
     * dialog stays untouched alongside. Focus is re-aimed at the
     * grabbed row after every rebuild (its title carries its
     * position number, so the plain title-memory restore would
     * miss after a move).
     */
    private fun sortGrabStart(key: String, branch: String) {
        if (sortGrab != null) return
        val order = when (branch) {
            "tracker" -> prefs.trackerSectionsConfig().order
            "tv" -> layout.rowOrder.toList()
            else -> return
        }
        val state = SortGrab.grab(SortGrab.State.idle(order), key)
        if (state.grabbed == null) return
        sortGrab = state
        sortGrabKey = key
        sortGrabBranch = branch
        rebuild()
        sortGrabFocusKey(key, branch)
    }

    private fun sortGrabMove(dir: Int) {
        val state = sortGrab ?: return
        val moved = SortGrab.move(state, dir)
        if (moved == state) return
        sortGrab = moved
        when (sortGrabBranch) {
            "tracker" -> prefs.setTrackerSectionsConfig(
                prefs.trackerSectionsConfig().copy(order = moved.order)
            )
            "tv" -> {
                layout.rowOrder.clear()
                layout.rowOrder.addAll(moved.order)
                saveLayout()
            }
        }
        rebuild()
        sortGrabFocusKey(sortGrabKey ?: return, sortGrabBranch)
    }

    private fun sortGrabDrop() {
        val key = sortGrabKey ?: return
        val branch = sortGrabBranch
        sortGrab = null
        sortGrabKey = null
        sortGrabBranch = ""
        rebuild()
        sortGrabFocusKey(key, branch)
    }

    private fun sortGrabCancel() {
        val state = sortGrab ?: return
        val key = sortGrabKey ?: return
        val branch = sortGrabBranch
        when (branch) {
            "tracker" -> prefs.setTrackerSectionsConfig(
                prefs.trackerSectionsConfig().copy(order = state.snapshot)
            )
            "tv" -> {
                layout.rowOrder.clear()
                layout.rowOrder.addAll(state.snapshot)
                saveLayout()
            }
        }
        sortGrab = null
        sortGrabKey = null
        sortGrabBranch = ""
        rebuild()
        sortGrabFocusKey(key, branch)
    }

    /** The sort-page row title for [key] under the CURRENT store (position + label + shown). */
    private fun sortGrabRowTitle(key: String, branch: String): String? {
        val ts = com.mizan.launcher.core.TrackerSections
        return when (branch) {
            "tracker" -> {
                val cfg = prefs.trackerSectionsConfig()
                val i = cfg.order.indexOf(key)
                if (i < 0) null
                else "${i + 1}. ${ts.label(key)} — " +
                    if (ts.isShown(cfg, key)) "Shown" else "Hidden"
            }
            "tv" -> {
                val i = layout.rowOrder.indexOf(key)
                if (i < 0) null
                else "${i + 1}. ${rowLabel(key)} — " +
                    if (layout.isRowVisible(key)) "Shown" else "Hidden"
            }
            else -> null
        }
    }

    private fun sortGrabFocusKey(key: String, branch: String) {
        val title = sortGrabRowTitle(key, branch) ?: return
        detailFocusMemory[nav.selectedId] = title
        detailFocusable.firstOrNull { it.tag == title }?.requestFocus()
    }

    /** Apply one sort action to a Tracker/Watch section (existing store only). */
    private fun applyTrackerSortAction(key: String, action: SortAction) {
        val ts = com.mizan.launcher.core.TrackerSections
        when (action) {
            SortAction.UP -> prefs.setTrackerSectionsConfig(
                ts.move(prefs.trackerSectionsConfig(), key, -1)
            )
            SortAction.DOWN -> prefs.setTrackerSectionsConfig(
                ts.move(prefs.trackerSectionsConfig(), key, +1)
            )
            SortAction.TOGGLE -> prefs.setTrackerSectionsConfig(
                ts.setShown(
                    prefs.trackerSectionsConfig(), key,
                    !ts.isShown(prefs.trackerSectionsConfig(), key)
                )
            )
        }
        rebuild()
    }

    /** Move up/down dialog for one Tracker section (v2.0). */
    private fun trackerSectionOrder() {
        val ts = com.mizan.launcher.core.TrackerSections
        val cfg = prefs.trackerSectionsConfig()
        val labels = cfg.order.map { ts.label(it) }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Reorder tracker sections")
            .setItems(labels) { _, which ->
                val key = cfg.order[which]
                AlertDialog.Builder(this).setTitle(ts.label(key))
                    .setItems(arrayOf("Move up", "Move down")) { _, dir ->
                        prefs.setTrackerSectionsConfig(
                            ts.move(prefs.trackerSectionsConfig(), key, if (dir == 0) -1 else 1)
                        )
                        rebuild()
                    }
                    .setNegativeButton("Cancel", null).show()
            }
            .setNegativeButton("Cancel", null).show()
    }

    /** The current home style with its one-line description (v1.7, v1.8, v2.2, v2.3). */
    private fun homeStyleLabel(): String =
        if (com.mizan.launcher.data.StartupHealthStore.safeShellArmed) {
            // v3.3 (ask 46): disclose, never silently accept — the
            // home is locked to TV while safe mode is on, whatever
            // style is picked; the exit lives in Data & about.
            "TV — safe mode is ON: home locked to TV until safe mode exits (Data & about → Exit safe mode)"
        } else when {
            com.mizan.launcher.core.HomeStyle.isWatch(prefs.homeStyle) ->
                "Watch — Tracker view + IPTV playback of your titles"
            com.mizan.launcher.core.HomeStyle.isIptv(prefs.homeStyle) ->
                "IPTV — live TV, movies & series from your IPTV account"
            com.mizan.launcher.core.HomeStyle.isTracker(prefs.homeStyle) ->
                "Tracker — Netflix-style view of your shows & movies"
            else ->
                "TV — rows of apps under the hero bar"
        }

    /**
     * Home style picker (v1.7, extended v1.8, reworked v2.2): three
     * described choices, per profile. Switching is presentation-
     * only — favorites, folders, order and locks are shared, so
     * nothing is lost either way. (v2.2 removed the iPad style; a
     * profile still set to it reads as TV — see core/HomeStyle.)
     */
    private fun pickHomeStyle() {
        val keys = listOf(
            com.mizan.launcher.core.HomeStyle.TV,
            com.mizan.launcher.core.HomeStyle.TRACKER,
            com.mizan.launcher.core.HomeStyle.IPTV,
            com.mizan.launcher.core.HomeStyle.WATCH
        )
        val labels = arrayOf(
            "TV — rows of apps under the hero bar",
            "Tracker — Netflix-style view of your shows & movies (needs TV Shows & Movies Tracker)",
            "IPTV — live TV, movies & series (set up your account in Settings › IPTV)",
            "Watch — Tracker view + IPTV playback of your titles (needs the tracker AND an IPTV account)"
        )
        val current = keys.indexOf(prefs.homeStyle).coerceAtLeast(0)
        val builder = AlertDialog.Builder(this).setTitle("Home style")
        // v3.3 (ask 46): while safe mode is on, say so in the
        // picker itself — a picked style is stored and applies
        // once safe mode exits, but the home stays TV until then.
        if (com.mizan.launcher.data.StartupHealthStore.safeShellArmed) {
            builder.setMessage("Safe mode is ON: the home is locked to TV until safe mode exits. " +
                "Your pick below is still saved and applies once you leave safe mode " +
                "(Data & about → Exit safe mode and retry normal home).")
        }
        builder
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
        // v2.0: the Most Used row needs Usage Access; while the grant
        // is missing, its settings entry point lives here.
        if (!com.mizan.launcher.data.MostUsed.isGranted(this)) {
            content.addView(row(
                "Most Used — needs Usage Access",
                "Android hides app-usage data until you allow it — tap to open Usage Access settings"
            ) {
                try { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
                catch (e: Exception) { failOpen("usageAccessSettings", e) }
            })
        }
        content.addView(info("Live TV reads the TV's tuner channel list when the system allows it — on many TVs that permission is reserved, the row stays hidden, and Diagnostics shows why (“live tv:”)."))

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
        content.addView(info("State: ${if (npGranted) "Granted" else "Not granted"}. Sreeb's listener reads no notification content — Android simply requires this grant before an app may see media sessions (what's playing, and its play/pause controls). Without it the row stays hidden."))
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
                // v2.8: the row's index too — restoreFocus's
                // nearest-row fallback when the title is gone.
                val idx = detailFocusable.indexOf(tv)
                if (idx >= 0) detailFocusIndexMemory[nav.selectedId] = idx
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
        "mostused" -> "Row: Most Used"
        "folders" -> "Row: Folders"
        "nowplaying" -> "Row: Now Playing"
        "continue" -> "Row: Continue Watching"
        "inputs" -> "Row: HDMI & Inputs"
        "livetv" -> "Row: Live TV"
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
            append("\nHome: ${if (isHome) "Sreeb is default" else "not default (current: " +
                com.mizan.launcher.data.HomeStatus.currentHomePackage(this@SettingsActivity).ifEmpty { "unknown" } + ")"}")
            append(", watcher: ${if (watcher) "enabled" else "disabled"}" +
                if (watcher) "" else " (listed in Accessibility as “Sreeb watcher”)")
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
            // v2.8: the startup: line — the cold-start timings
            // (first frame of the staged shell · the warmed home),
            // shaped by core/DiagStartup from data/DiagLog's
            // recorded milestones (persisted across processes).
            append(
                "\n" + com.mizan.launcher.core.DiagStartup.line(
                    com.mizan.launcher.data.DiagLog.startupFirstMs(this@SettingsActivity),
                    com.mizan.launcher.data.DiagLog.startupFullMs(this@SettingsActivity)
                )
            )
            // v3.0: the startup-health line — the uncleared-start
            // counter + safe-shell state (core/StartupHealth via
            // data/StartupHealthStore). Two uncleared starts in a
            // row arm the shell; a shell that's ON here is the
            // named signal for a repeating startup crash.
            append(
                "\n" + com.mizan.launcher.data.StartupHealthStore
                    .diagnosticsLine(this@SettingsActivity)
            )
            // v3.5 (spec §35): the death proof — what killed the
            // last process that died mid-startup (trim level +
            // last stage + before/after first frame), or
            // "unknown" when it died with no trim warning, or
            // "none recorded" when the last process survived.
            // Never fabricated: unknown stays unknown.
            append(
                "\n" + com.mizan.launcher.data.DeathStore
                    .diagLine(this@SettingsActivity)
            )
            // v3.5: the cold chain's state — waiting survival /
            // running: <step> / done / cancelled — so a photo
            // shows whether the post-survival work finished.
            append(
                "\ncold chain: " + com.mizan.launcher.data.ColdChainRunner.diagState
            )
            // v1.7: the profile's home style (safe mode renders TV
            // regardless — noted so a report isn't misread).
            append("\nhome style: ${prefs.homeStyle}" +
                if (prefs.safeMode) " (safe mode: tv)" else "")
            // v3.7: the library: line replaces the v1.9
            // tracker: line — the launcher IS the tracker
            // now, so there is no fetch classification to
            // word it from. Counts only (titles, episode
            // files, missed items, last local change),
            // plus whether the one-time import is still
            // pending. A missing tracker app is never
            // reported here at all — it is a normal state.
            // (The memo parse the old line needed is gone
            // too: libraryDiag reads only the small index.)
            append(
                "\nlibrary: " + com.mizan.launcher.data.TrackerClient.libraryDiag(this@SettingsActivity)
            )
            // v2.1: the tmdb: line — the last TMDB outcome, so a
            // details/search failure is diagnosable, incl. bad_path.
            // v3.7: direct TMDB with the launcher's own key is
            // the ONLY path now (the tracker proxy is gone) —
            // the line names the direct outcome and, on
            // failure, the direct token / that no launcher
            // key is set.
            append(
                "\ntmdb: " + com.mizan.launcher.core.LibraryLogic.tmdbDiagText(
                    com.mizan.launcher.data.TrackerClient.lastTmdbOutcome(this@SettingsActivity),
                    com.mizan.launcher.data.TrackerClient.lastTmdbDirect(this@SettingsActivity)
                )
            )
            // v2.0: the live tv: line — the Live TV probe's verdict
            // (channel count, or exactly why there is no row).
            append("\nlive tv: " + com.mizan.launcher.data.LiveTv.diagLine(this@SettingsActivity))
            // v2.2: the iptv: line — classified state + counts only
            // (core/IptvStatus). The server, credentials and playlist
            // URLs NEVER appear here (or in any backup/log/file).
            val iIdx = com.mizan.launcher.data.IptvClient.storeIndex(this@SettingsActivity)
            val iKind = com.mizan.launcher.data.IptvClient.status(this@SettingsActivity)
            val iFetchedAt = com.mizan.launcher.data.IptvClient.fetchedAt(this@SettingsActivity)
            val iTime = if (iFetchedAt > 0) {
                java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
                    .format(java.util.Date(iFetchedAt))
            } else ""
            append(
                "\niptv: " + com.mizan.launcher.core.IptvStatus.diagText(
                    iKind, iIdx?.liveCount ?: 0, iIdx?.vodCount ?: 0, iIdx?.seriesCount ?: 0,
                    prefs.iptvFavoriteList().size,
                    com.mizan.launcher.data.IptvClient.epgChannelCount(this@SettingsActivity),
                    iTime,
                    com.mizan.launcher.data.IptvClient.lastErrorClass(this@SettingsActivity),
                    com.mizan.launcher.core.IptvStore.sizeText(
                        com.mizan.launcher.data.IptvClient.storeBytes(this@SettingsActivity)
                    )
                )
            )
            // v2.3: the watch: line — match coverage + resolved
            // episodes, counts only (ids/titles never print).
            append(
                "\nwatch: " + com.mizan.launcher.core.WatchMatch.diagText(
                    com.mizan.launcher.data.WatchPlay.stats(this@SettingsActivity)
                )
            )
            // v2.7: the discovery: line — which engine supplies
            // Coming Soon / Trending / For You and how much each
            // holds. The counts are persisted by
            // data/DiscoveryData's worker; composing diagnostics
            // never triggers a compute, and no key material or
            // title ever appears here.
            val dDiag = com.mizan.launcher.data.DiscoveryData.diagState(this@SettingsActivity)
            append(
                "\ndiscovery: " + com.mizan.launcher.core.Discovery.diagText(
                    dDiag.first, dDiag.second.first, dDiag.second.second, dDiag.third
                )
            )
            // v3.1 (ask 38): the quiet home-cache staleness note —
            // which tracker sections have a last-known disk copy and
            // how old the oldest is. Rendered from a worker-updated
            // volatile: composing diagnostics does no IO, and a
            // stale home never shows an on-screen error for it.
            append(
                "\nhome cache: " +
                    com.mizan.launcher.data.HomeCacheDisk.lastDiag
            )
            // v3.4 (ask 49): the slim-cache proof — whether the
            // last cold start painted from the fast slim file
            // ("hit"), found none ("absent", first run after
            // install), or fell through to the full parse
            // ("miss"). A miss is not an error; it only means
            // the next cold start parses the full snapshots.
            append(
                "\nslim: " +
                    com.mizan.launcher.data.TrackerClient.slimDiag
            )
            // v3.6 (ask 53): the tracker-store proof — title
            // count, episode-file count, index stamp, and
            // which source is actually serving the home
            // (store vs prefs fallback). Read from the
            // worker-updated volatile: composing diagnostics
            // does no IO. The slim: line above stays until
            // the prefs eviction retires the slim file;
            // after eviction the store is the only fast
            // path and slim: reads "absent" harmlessly.
            append(
                "\ntracker store: " +
                    com.mizan.launcher.data.TrackerClient.storeDiag
            )
            // v3.2 (ask 43): the proof line — when the home data
            // was last refreshed, and by what (manual Refresh vs
            // a section Retry). "never" + auto-refresh off is the
            // expected reading on a home that only renders cache.
            val lastRefresh = if (prefs.lastRefreshAt > 0) {
                java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.getDefault())
                    .format(java.util.Date(prefs.lastRefreshAt)) +
                    " (" + prefs.lastRefreshKind.ifEmpty { "manual" } + ")"
            } else {
                "never"
            }
            append("\nlast refresh: $lastRefresh · auto-refresh: off")
            // v3.2: the fine-grained startup stages — which stage
            // ate the first-frame time, if it is still slow.
            val stages = com.mizan.launcher.data.DiagLog.stagesText()
            if (stages.isNotEmpty()) append("\nstartup stages: $stages")
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
            // v2.5: the self-diagnostics (data/DiagLog) — the
            // breadcrumb trail and the tails of the app's own
            // freeze/crash logs. Everything below is composed
            // from bounded, already-recorded text: no live
            // provider, store or network call happens while this
            // page builds (the watch: counts above likewise read
            // the cached match table only — WatchPlay.stats).
            val crumbs = com.mizan.launcher.data.DiagLog.crumbs()
            if (crumbs.isNotEmpty()) {
                append("\nrecent actions: " + crumbs.takeLast(5).joinToString(" · "))
            }
            val anrTail = com.mizan.launcher.data.DiagLog.anrTail(this@SettingsActivity, 14)
            if (anrTail.isNotEmpty()) {
                append("\nlast freeze (watchdog):\n").append(anrTail)
            }
            val crashTail = com.mizan.launcher.data.DiagLog.crashTail(this@SettingsActivity, 10)
            if (crashTail.isNotEmpty()) {
                append("\nlast crash:\n").append(crashTail)
            }
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
                val idx = detailFocusable.indexOf(switch)
                if (idx >= 0) detailFocusIndexMemory[nav.selectedId] = idx
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
            isHome -> "✓ Sreeb is your home screen" + if (watcher) " · watcher on" else ""
            watcher -> "Watcher is on, but Sreeb isn't the default home yet"
            else -> "Sreeb isn't your home screen yet — 3 guided steps"
        }
    }

    /**
     * The takeover checklist (fills the Home override detail pane).
     * Status lines are LIVE (rebuilt on resume, so returning from a
     * system settings screen updates the ticks). Sreeb never disables
     * the stock launcher or any other package — taking over Home is
     * the system's default-home choice + the optional watcher.
     */
    private fun buildMakeHome(content: LinearLayout) {
        val isHome = com.mizan.launcher.data.HomeStatus.isDefaultHome(this)
        val watcher = com.mizan.launcher.data.HomeStatus.isWatcherEnabled(this)

        content.addView(header("Make Sreeb your home screen"))
        content.addView(info(
            "Three steps. Nothing here turns off the TV's own launcher or any other app — " +
                "you can switch back in the same system screens at any time."
        ))

        content.addView(header("Step 1 — Default home"))
        content.addView(info(
            if (isHome) "✓ Done — Sreeb is the default home screen."
            else "✗ Not yet — current home: " +
                com.mizan.launcher.data.HomeStatus.currentHomePackage(this).ifEmpty { "unknown" }
        ))
        content.addView(row("Set Sreeb as default home", "Opens the system's home-role request / Home settings") {
            requestDefaultHome()
        })

        content.addView(header("Step 2 — Home watcher"))
        content.addView(info(
            if (watcher) "✓ Done — “Sreeb watcher” is enabled in Accessibility."
            else "Optional but recommended: the watcher notices when the TV's own home screen " +
                "appears and reopens Sreeb. It only watches which window is showing — " +
                "it cannot read screen contents."
        ))
        if (!watcher) {
            content.addView(info(
                "Where to find it: TV Settings → Device Preferences → Accessibility → " +
                    "Installed services → “Sreeb watcher”. If it isn't listed, " +
                    "allow restricted settings first: Android system Settings → Apps → " +
                    "Sreeb → ⋮ (top-right) → “Allow restricted settings”, then look again."
            ))
        }
        content.addView(row("Enable Home watcher", "Opens Accessibility settings — turn on “Sreeb watcher”") {
            try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            catch (e: Exception) { failOpen("a11ySettings", e) }
        })
        val relaunches = Prefs.watcherRelaunches(this)
        content.addView(info("Watcher relaunches so far: $relaunches"))

        content.addView(header("Step 3 — Optional: set via ADB"))
        content.addView(info(
            "If the system screens don't offer Sreeb on this TV, the same result from a " +
                "computer on the same Wi-Fi (this changes only the default home — it disables nothing):"
        ))
        // v2.8 nav audit: this info text must NOT be made
        // text-selectable — a selectable TextView is FOCUSABLE,
        // which made this read-only ADB text a dead D-pad stop
        // (no highlight, no action) mid-page.
        content.addView(info(com.mizan.launcher.data.HomeStatus.adbCommands()))

        content.addView(row("← Back to Home & launcher", "") {
            showingMakeHome = false
            rebuild()
        })
    }

    /** Ask Android to make Sreeb the default home; fail-open across variants. */
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
                    toast("Sreeb is already the default home")
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
            toast("Find “Default home app” in Settings and choose Sreeb")
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
                // v2.8 nav audit: dismiss BEFORE apply — apply
                // rebuilds the hierarchy, and rebuilding under a
                // still-shown dialog let the window-focus return
                // drop the pending restoreFocus, stranding focus.
                dlg.dismiss()
                apply(options[which])
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun pickTheme() {
        val names = Themes.PRESETS.map { it.name }
        val idx = Themes.PRESETS.indexOfFirst { it.id == prefs.themeId }.coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("Theme")
            .setSingleChoiceItems(names.toTypedArray(), idx) { dlg, which ->
                prefs.themeId = Themes.PRESETS[which].id
                if (Themes.PRESETS[which].dynamic) AccentUtil.recomputeAsync(this, prefs) { rebuild() }
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
                    0 -> { prefs.wallpaperKind = "theme"; AccentUtil.recomputeAsync(this, prefs) { rebuild() }; rebuild() }
                    3 -> {
                        // Weather background (v1.2): drawn from the
                        // current conditions / today's forecast; falls
                        // back to the theme gradient until weather data
                        // arrives (and the home screen fetches it).
                        prefs.wallpaperKind = "weather"
                        AccentUtil.recomputeAsync(this, prefs) { rebuild() }
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
                                AccentUtil.recomputeAsync(this, prefs) { rebuild() }; rebuild()
                            }.show()
                    }
                    2 -> {
                        val names = Themes.SOLIDS.map { it.first }
                        AlertDialog.Builder(this).setTitle("Solid colour")
                            .setItems(names.toTypedArray()) { _, w2 ->
                                prefs.wallpaperKind = "solid"
                                prefs.wallpaperValue = Themes.SOLIDS[w2].second.toString()
                                AccentUtil.recomputeAsync(this, prefs) { rebuild() }; rebuild()
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
            AccentUtil.recomputeAsync(this, prefs) { rebuild() }
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
                    AccentUtil.recomputeAsync(this, prefs) { rebuild() }
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

    /**
     * v3.2 (ask 44): row options are visibility ONLY now. The
     * old Move row up/down lived here AND in the v3.1 "Sort
     * sections" page (same LayoutStore rowOrder) — sorting in
     * two places. Sorting lives ONLY in "Sort sections" now;
     * this dialog keeps Hide/Show, which ask 44 does not move.
     * moveRow() survives for the Sort page's use.
     */
    private fun rowOptions(key: String) {
        val options = mutableListOf<String>()
        options.add(if (layout.isRowVisible(key)) "Hide row" else "Show row")
        AlertDialog.Builder(this).setTitle(rowLabel(key))
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> { layout.rowVisible[key] = !layout.isRowVisible(key) }
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

    /**
     * v2.9 (asks A.2 / I.33): the app pickers used to enumerate
     * the full catalogue with AppLoader.loadApps ON THE MAIN
     * THREAD — a full PackageManager scan, the jank the §28 audit
     * flagged. Now they seed from MainActivity.AppCache
     * (stale-while-revalidate: returns instantly, refreshes in
     * the background); only a truly cold cache pays ONE
     * background scan, and the dialog shows on the main thread.
     */
    private fun withApps(onReady: (List<AppEntry>) -> Unit) {
        val cached = MainActivity.AppCache.get(this)
        if (cached.isNotEmpty()) {
            onReady(cached)
            return
        }
        toast("Loading apps…")
        Thread {
            val apps = try {
                AppLoader.loadApps(packageManager, packageName)
            } catch (e: Exception) {
                emptyList()
            }
            runOnUiThread {
                if (!isFinishing && !isDestroyed) onReady(apps)
            }
        }.apply {
            name = "mizan-settings-apps"
            isDaemon = true
        }.start()
    }

    private fun hiddenApps() {
        if (layout.hidden.isEmpty()) { toast("No hidden apps"); return }
        withApps { apps ->
            val byPkg = apps.associateBy { it.pkg }
            val pkgs = layout.hidden.toList()
            val labels = pkgs.map { byPkg[it]?.label ?: it }
            AlertDialog.Builder(this).setTitle("Hidden apps — tap to unhide")
                .setItems(labels.toTypedArray()) { _, which ->
                    layout.hidden.remove(pkgs[which]); saveLayout(); rebuild()
                }
                .setNegativeButton("Close", null).show()
        }
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
        // v2.9: the enumeration runs via withApps (off the main
        // thread / AppCache) — see its doc.
        withApps { apps ->
            val labels = apps.map { app ->
                (if (prefs.lockedApps.contains(app.pkg)) "🔒 " else "") + app.label
            }
            AlertDialog.Builder(this).setTitle("Tap an app to lock / unlock it")
                .setMessage(
                    "Every launchable app on this TV is listed — hidden apps, apps inside " +
                        "folders, and system apps (like the TV's own Settings) included. " +
                        "Sreeb itself is the only app not listed. Locking gates " +
                        "launches made through Sreeb; the remote's hardware app buttons bypass it."
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
        AccentUtil.recomputeAsync(this, prefs) { rebuild() }
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
                    "screen appears and reopens Sreeb instead.\n\n" +
                    "It only watches which window is on screen — it cannot read screen contents, " +
                    "and it sends nothing anywhere.\n\n" +
                    "It is OFF by default. To turn it on, open the TV's Settings → " +
                    "Device Preferences → Accessibility → Installed services → " +
                    "“Sreeb watcher” — that exact name (builds before v2.0 listed it as " +
                    "“Mizan Launcher watcher”) — and turn it on " +
                    "yourself. Turn it off there any time.\n\n" +
                    "Still not listed? Sideloaded apps are hidden from Accessibility on some " +
                    "Google TV builds until you allow them: Android system Settings → Apps → " +
                    "Sreeb → ⋮ (top-right) → “Allow restricted settings”, then check " +
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
            // v2.0 per-profile extras: tracker section controls +
            // custom app artwork (both codec strings).
            o.put("tracker_sections", prefs.trackerSections)
            o.put("artwork", prefs.artwork)
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
                // v2.0: absent in pre-v2.0 backups → keep current.
                // Both setters run the values through their codecs'
                // normalization on read, so garbled data fails open.
                if (o.has("tracker_sections")) prefs.trackerSections = o.optString("tracker_sections", "")
                if (o.has("artwork")) prefs.artwork = o.optString("artwork", "")
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
                    else toast("That file isn't a Sreeb backup — nothing imported")
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
                    AccentUtil.recomputeAsync(this, prefs) { rebuild() }
                    toast("Wallpaper set")
                    rebuild()
                }
            }
        } catch (e: Exception) {
            failOpen("activityResult:$requestCode", e)
        }
    }
}
