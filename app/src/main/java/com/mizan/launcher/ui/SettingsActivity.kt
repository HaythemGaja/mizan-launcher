package com.mizan.launcher.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.LinearLayout
import com.mizan.launcher.core.SettingsNavMachine
import com.mizan.launcher.core.Themes
import com.mizan.launcher.domain.HomeStyle
import com.mizan.launcher.domain.ProfileSettings
import com.mizan.launcher.domain.StatusBarSettings
import com.mizan.launcher.domain.WallpaperKind
import com.mizan.launcher.domain.WeatherUnits

/**
 * Settings (MAP §1.12 + §3, ~70 rows): two-column Roku-style — left category
 * list, right detail pane; Right enters detail, Right again is a no-op,
 * Left/Back returns to list, Back in list exits; category change swaps the
 * detail model; selection clamps (no wrap); focus memory per category is
 * approximated by remembering the last category. Detail panes are
 * crash-contained: a failing page shows "This settings page hit a snag —
 * the error is noted in Diagnostics" instead of crashing Settings.
 * Categories in order: Home & launcher · Look & wallpaper · Home screen ·
 * Sort sections · Status bar · Weather · Lock & profiles · Now playing ·
 * IPTV · Data & about.
 * Defaults (MAP §7): theme Apple Black, home style TV, columns 6,
 * weather Bloomington IN °F, unlock grace "Every time", Guest lock on,
 * safe mode off — all read from SettingsRepository, whose domain defaults
 * already encode them; this screen presents them, never hardcodes a
 * different default. Version label is read from PackageManager at runtime.
 * Gate: opening Settings asks for the arrow pattern when Settings lock is
 * on, or when Guest is active and Guest-lock is on; already-unlocked this
 * entry allows; no pattern set fails open.
 */
class SettingsActivity : Activity() {
    private val categories = listOf(
        "Home & launcher", "Look & wallpaper", "Home screen", "Sort sections",
        "Status bar", "Weather", "Lock & profiles", "Now playing", "IPTV", "Data & about"
    )
    private val nav = SettingsNavMachine(categories)
    private lateinit var detail: LinearLayout
    private var currentCat = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = UiWiring.settings
        val needsGate = settings?.let {
            val lock = UiWiring.lock
            val patternSet = lock?.isPatternSet() == true
            val settingsLocked = lock?.isSettingsLocked() == true
            val guestActive = it.getGlobal().guestProfile != 0 && it.getGlobal().guestProfile == it.getGlobal().currentProfile
            val guestLock = it.getGlobal().guestLockSettings
            patternSet && (settingsLocked || (guestActive && guestLock))
        } == true
        if (needsGate) {
            PatternGate.run(this) { buildUi() }
        } else buildUi()
    }

    private fun profile(): ProfileSettings? {
        val s = UiWiring.settings ?: return null
        return try { s.getProfile(s.getGlobal().currentProfile) } catch (e: Throwable) { null }
    }
    private fun save(p: ProfileSettings) { Ui.runBg { UiWiring.settings?.setProfile(p) } }

    private fun buildUi() {
        val root = LinearLayout(this); root.orientation = LinearLayout.HORIZONTAL
        val left = LinearLayout(this); left.orientation = LinearLayout.VERTICAL
        left.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.3f)
        detail = LinearLayout(this); detail.orientation = LinearLayout.VERTICAL
        detail.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.7f)
        categories.forEachIndexed { i, name ->
            left.addView(Ui.button(this, name) { currentCat = i; nav.select(i); renderDetail() })
        }
        root.addView(left); root.addView(detail)
        setContentView(root)
        renderDetail()
    }

    private fun renderDetail() {
        detail.removeAllViews()
        try {
            when (currentCat) {
                0 -> homeLauncher()
                1 -> lookWallpaper()
                2 -> homeScreen()
                3 -> sortSections()
                4 -> statusBar()
                5 -> weather()
                6 -> lockProfiles()
                7 -> nowPlaying()
                8 -> iptv()
                else -> dataAbout()
            }
        } catch (e: Throwable) {
            UiWiring.diagnostics?.noteError("settings page: " + e.javaClass.simpleName)
            detail.addView(Ui.text(this, "This settings page hit a snag — the error is noted in Diagnostics", 18f))
        }
    }

    private fun addRow(label: String, note: String = "", onClick: (() -> Unit)? = null) {
        val v = LinearLayout(this); v.orientation = LinearLayout.VERTICAL
        v.setPadding(Ui.dp(this, 16), Ui.dp(this, 10), Ui.dp(this, 16), Ui.dp(this, 10))
        v.addView(Ui.text(this, label, 18f, 0xFFFFFFFF.toInt(), true))
        if (note.isNotEmpty()) v.addView(Ui.text(this, note, 14f, 0xFFAAAAAA.toInt()))
        if (onClick != null) { Ui.focusable(v); v.setOnClickListener { onClick() } }
        detail.addView(v)
    }

    private fun homeLauncher() {
        val s = UiWiring.settings
        addRow("Make Sreeb your home screen", "Set Sreeb as the default home in system settings, then enable the watcher if you want it to stay front.") {
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        }
        addRow("Home watcher (accessibility)", "Optional, OFF unless enabled in system settings. Shows watcher + default-home + current-home-package state.") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        addRow("Watcher state", UiWiring.homeWatcher?.let {
            "Watcher enabled: ${it.isWatcherEnabled()} · Default home: ${it.isDefaultHome()} · Current home: ${it.currentHomePackage() ?: "?"} · Relaunches: ${it.relaunchCount()}"
        } ?: "Default home: see system settings")
        addRow("Speed Up", UiWiring.settings?.getGlobal()?.speedUpLastResult ?: "") {
            val result = UiWiring.onSpeedUp?.invoke() ?: "Speed Up is wired by the app layer (KILL_BACKGROUND_PROCESSES, never-list respected)"
            Ui.toast(this, result)
        }
        addRow("Launcher access service", "No in-app toggle — enable “Sreeb launcher” only in system Accessibility settings (Back/Home/Recents/notifications/quick settings, no content, no gestures).")
    }

    private fun lookWallpaper() {
        val p = profile()
        addRow("Theme", "Current: ${Themes.preset(p?.themeId ?: "apple_black").name} (default Apple Black)") {
            val items = Themes.PRESETS.map { t -> t.name to { val pp = p; if (pp != null) { save(pp.copy(themeId = t.id)); renderDetail() } } }
            Ui.menu(this, "Theme", items)
        }
        addRow("Wallpaper", "Current: ${p?.wallpaperKind ?: WallpaperKind.THEME} (default Theme background)") {
            val items = WallpaperKind.values().map { k -> k.name to { val pp = p; if (pp != null) { save(pp.copy(wallpaperKind = k)); renderDetail() } } }
            Ui.menu(this, "Wallpaper", items)
        }
        addRow("Home style", "TV / Watch only (v3.9; default TV). TV = rows of apps under the hero bar; Watch = your library + IPTV playback of your titles. Switching is presentation-only — nothing is lost.") {
            Ui.menu(this, "Home style", listOf(
                "TV — rows of apps under the hero bar" to { UiWiring.settings?.setHomeStyle(HomeStyle.TV); renderDetail() },
                "Watch — your library + IPTV playback of your titles" to { UiWiring.settings?.setHomeStyle(HomeStyle.WATCH); renderDetail() }
            ))
        }
        addRow("Icon shape", "Current: ${p?.iconShape ?: "rounded"} (default rounded)") {
            Ui.menu(this, "Icon shape", listOf("rounded", "circle", "square").map { v -> v to { p?.let { save(it.copy(iconShape = v)); renderDetail() } } })
        }
        addRow("App size", "Current: ${p?.appSize ?: "standard"} (default standard)") {
            Ui.menu(this, "App size", listOf("compact", "standard", "large").map { v -> v to { p?.let { save(it.copy(appSize = v)); renderDetail() } } })
        }
        addRow("Grid columns", "Current: ${p?.gridColumns ?: 6} (3–10, default 6)") {
            Ui.menu(this, "Grid columns", (3..10).map { v -> "$v" to { p?.let { save(it.copy(gridColumns = v)); renderDetail() } } })
        }
        addRow("Library sections (Watch only)", "Hero is always first when shown; a section with nothing in it never appears even when switched on.") {
            currentCat = 3; renderDetail()
        }
        addRow("Reddit wallpapers", "Top of the week from your subreddits (when wallpaper = Reddit)")
        addRow("Subreddits", p?.redditSubs ?: "wallpaper,wallpapers,EarthPorn") {
            KeyboardDialog.show(this, "Subreddits (comma-separated)", p?.redditSubs ?: "", maxLen = 200) { v -> p?.let { save(it.copy(redditSubs = v)) }; renderDetail() }
        }
        detail.addView(SwitchRow.row(this, "Allow NSFW results", "OFF by default (over_18 posts skipped)", p?.redditAllowNsfw == true) { v -> p?.let { save(it.copy(redditAllowNsfw = v)) } })
        addRow("Wallpaper rotation", "Current: ${p?.wallpaperRotation ?: "off"} (off / 15m / 1h / 6h / daily; checked on home wake or clock tick)") {
            Ui.menu(this, "Wallpaper rotation", listOf("off", "15m", "1h", "6h", "daily").map { v -> v to { p?.let { save(it.copy(wallpaperRotation = v)); renderDetail() } } })
        }
        addRow("Next wallpaper", "Jumps immediately") {
            Ui.runBg {
                val url = UiWiring.nextWallpaper?.invoke()
                Ui.runMain { Ui.toast(this, if (url != null) "Next wallpaper queued" else "No next wallpaper available") }
            }
        }
        addRow("Bing daily picture", "Changes with the calendar day; cached per day-key")
        addRow("Gradient", "14 built-ins; unknown falls back to first") {
            Ui.menu(this, "Gradient", Themes.GRADIENTS.map { g -> g.name to { p?.let { save(it.copy(wallpaperValue = g.name)); renderDetail() } } })
        }
        addRow("Solid colour", "") {
            Ui.menu(this, "Solid colour", Themes.SOLIDS.map { (n, _) -> n to { p?.let { save(it.copy(wallpaperValue = n)); renderDetail() } } })
        }
    }

    private fun homeScreen() {
        val s = UiWiring.settings
        addRow("Per-row Show / Hide + Move", "TV rows: favorites, recents, mostused, folders, nowplaying, continue, inputs, livetv, apps. Use Sort sections to reorder.") {
            currentCat = 3; renderDetail()
        }
        addRow("Recently Used row note", "Newest first, capped at 8, launcher itself never recorded")
        addRow("Live TV row note", "Tuner list only when the system allows; otherwise hidden with a diagnostics explanation")
        addRow("Hidden apps manager", "Count: ${try { UiWiring.apps?.getHiddenApps()?.size ?: 0 } catch (e: Throwable) { 0 }}") {
            Ui.runBg {
                val hidden = try { UiWiring.apps?.getHiddenApps() ?: emptyList() } catch (e: Throwable) { emptyList() }
                Ui.runMain {
                    Ui.menu(this, "Hidden apps", hidden.map { a -> a.label to { Ui.runBg { UiWiring.apps?.setHidden(a.packageName, false) } } })
                }
            }
        }
        addRow("Folders manager", "Count: ${try { UiWiring.apps?.getFolders()?.size ?: 0 } catch (e: Throwable) { 0 }}") {
            FolderViewActivity.open(this, null)
        }
    }

    private fun sortSections() {
        val s = UiWiring.settings ?: return
        val style = profile()?.homeStyle ?: HomeStyle.TV
        val order = if (style == HomeStyle.TV) s.getTvRowOrder() else s.getWatchSectionOrder()
        addRow("Sort sections (${if (style == HomeStyle.TV) "TV rows" else "Watch sections"})", "Tap a section, then Move up / Move down / Hide-Show. Hero is Hide/Show-only, never reorderable.")
        order.forEachIndexed { idx, id ->
            val visible = s.getSectionVisibility(id)
            addRow("$id ${if (visible) "" else "(hidden)"}", "") {
                val items = mutableListOf<Pair<String, () -> Unit>>()
                if (id != "hero") {
                    items += "Move up" to { moveSection(order, idx, -1) }
                    items += "Move down" to { moveSection(order, idx, 1) }
                }
                items += (if (visible) "Hide" else "Show") to { s.setSectionVisibility(id, !visible); renderDetail() }
                Ui.menu(this, id, items)
            }
        }
    }
    private fun moveSection(order: List<String>, idx: Int, delta: Int) {
        val s = UiWiring.settings ?: return
        val mutable = order.toMutableList()
        val to = idx + delta
        if (to !in mutable.indices) return
        val tmp = mutable[idx]; mutable[idx] = mutable[to]; mutable[to] = tmp
        if (profile()?.homeStyle == HomeStyle.TV) s.setTvRowOrder(mutable) else s.setWatchSectionOrder(mutable)
        renderDetail()
    }

    private fun statusBar() {
        val p = profile(); val sb = p?.statusBar ?: StatusBarSettings()
        detail.addView(SwitchRow.row(this, "Show status bar", "OFF = clean Apple-TV style, no top bar at all (default ON)", sb.showBar) { v -> p?.let { save(it.copy(statusBar = sb.copy(showBar = v))) } })
        detail.addView(SwitchRow.row(this, "Sticky status bar", "Pins at top while home scrolls (default OFF)", sb.sticky) { v -> p?.let { save(it.copy(statusBar = sb.copy(sticky = v))) } })
        detail.addView(SwitchRow.row(this, "Clock", "", sb.showClock) { v -> p?.let { save(it.copy(statusBar = sb.copy(showClock = v))) } })
        detail.addView(SwitchRow.row(this, "Date", "", sb.showDate) { v -> p?.let { save(it.copy(statusBar = sb.copy(showDate = v))) } })
        detail.addView(SwitchRow.row(this, "Weather", "", sb.showWeather) { v -> p?.let { save(it.copy(statusBar = sb.copy(showWeather = v))) } })
        detail.addView(SwitchRow.row(this, "Network icon", "", sb.showNetworkIcon) { v -> p?.let { save(it.copy(statusBar = sb.copy(showNetworkIcon = v))) } })
        detail.addView(SwitchRow.row(this, "Bluetooth icon", "", sb.showBluetoothIcon) { v -> p?.let { save(it.copy(statusBar = sb.copy(showBluetoothIcon = v))) } })
        detail.addView(SwitchRow.row(this, "Quick tiles row (master)", "Default ON", sb.quickTiles) { v -> p?.let { save(it.copy(statusBar = sb.copy(quickTiles = v))) } })
        detail.addView(SwitchRow.row(this, "Tile: Wi-Fi", "Default OFF", sb.wifiTile) { v -> p?.let { save(it.copy(statusBar = sb.copy(wifiTile = v))) } })
        detail.addView(SwitchRow.row(this, "Tile: Bluetooth", "Default ON", sb.bluetoothTile) { v -> p?.let { save(it.copy(statusBar = sb.copy(bluetoothTile = v))) } })
        detail.addView(SwitchRow.row(this, "Tile: Sound", "Default ON", sb.soundTile) { v -> p?.let { save(it.copy(statusBar = sb.copy(soundTile = v))) } })
        detail.addView(SwitchRow.row(this, "Tile: Picture", "Default ON", sb.pictureTile) { v -> p?.let { save(it.copy(statusBar = sb.copy(pictureTile = v))) } })
        detail.addView(SwitchRow.row(this, "Tile: Settings gear", "Corner gear appears even when off (default ON)", sb.settingsTile) { v -> p?.let { save(it.copy(statusBar = sb.copy(settingsTile = v))) } })
        detail.addView(SwitchRow.row(this, "Tile: Search", "Default ON", sb.searchTile) { v -> p?.let { save(it.copy(statusBar = sb.copy(searchTile = v))) } })
        detail.addView(SwitchRow.row(this, "Tile: All Apps", "Default ON", sb.allAppsTile) { v -> p?.let { save(it.copy(statusBar = sb.copy(allAppsTile = v))) } })
        addRow("Clock format", "Current: ${if (sb.clock24h) "24-hour" else "12-hour (default)"}") {
            Ui.menu(this, "Clock format", listOf("12-hour" to { p?.let { save(it.copy(statusBar = sb.copy(clock24h = false))); renderDetail() } }, "24-hour" to { p?.let { save(it.copy(statusBar = sb.copy(clock24h = true))); renderDetail() } }))
        }
        detail.addView(SwitchRow.row(this, "Show seconds", "Default OFF", sb.showSeconds) { v -> p?.let { save(it.copy(statusBar = sb.copy(showSeconds = v))) } })
        addRow("Date format", "Current: ${sb.dateFormat} (default weekday_month_day: “Tue, Oct 20”)") {
            Ui.menu(this, "Date format", listOf("weekday_month_day", "iso", "month_day_year").map { v -> v to { p?.let { save(it.copy(statusBar = sb.copy(dateFormat = v))); renderDetail() } } })
        }
        addRow("Text size", "Current: ${sb.textSize} (default medium)") {
            Ui.menu(this, "Text size", listOf("small", "medium", "large").map { v -> v to { p?.let { save(it.copy(statusBar = sb.copy(textSize = v))); renderDetail() } } })
        }
        addRow("Bar background", "Current: ${sb.background} (default transparent)") {
            Ui.menu(this, "Bar background", listOf("transparent", "theme", "black").map { v -> v to { p?.let { save(it.copy(statusBar = sb.copy(background = v))); renderDetail() } } })
        }
        addRow("Background opacity", "Current: ${sb.backgroundOpacity} (default solid)") {
            Ui.menu(this, "Background opacity", listOf("solid", "translucent").map { v -> v to { p?.let { save(it.copy(statusBar = sb.copy(backgroundOpacity = v))); renderDetail() } } })
        }
        addRow("Safe mode bar", "Settings tile only (all other tiles suppressed)")
    }

    private fun weather() {
        val p = profile(); val g = UiWiring.settings?.getGlobal()
        addRow("Units", "Current: ${p?.weatherUnits ?: WeatherUnits.FAHRENHEIT} (default Fahrenheit — °F, wind mph)") {
            Ui.menu(this, "Units", listOf(
                "Fahrenheit — °F, wind mph" to { p?.let { save(it.copy(weatherUnits = WeatherUnits.FAHRENHEIT)); renderDetail() } },
                "Celsius — °C, wind km/h" to { p?.let { save(it.copy(weatherUnits = WeatherUnits.CELSIUS)); renderDetail() } }
            ))
        }
        addRow("Weather location", "Current: ${g?.weatherLat ?: "39.1653"}, ${g?.weatherLon ?: "-86.5264"} (default Bloomington, IN)") {
            KeyboardDialog.show(this, "Latitude,longitude", "${g?.weatherLat}, ${g?.weatherLon}", maxLen = 60) { v ->
                val parts = v.split(",").map { it.trim() }
                if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                    Ui.runBg { UiWiring.settings?.setWeatherLocation(parts[0], parts[1]); Ui.runMain { renderDetail() } }
                } else Ui.toast(this, "Enter latitude,longitude (e.g. 39.1653, -86.5264)")
            }
        }
        addRow("Forecast entry note", "OK on clock / date / weather in the home bar opens the full forecast") {
            startActivity(Intent(this, ForecastActivity::class.java))
        }
        addRow("Weather background status", "Default “off” (internal; “Weather” wallpaper kind drives it)")
    }

    private fun lockProfiles() {
        val lock = UiWiring.lock; val s = UiWiring.settings
        addRow("Set / change arrow pattern", "Enter existing pattern first to change; default: no pattern set") {
            if (lock?.isPatternSet() == true) {
                PatternGate.run(this) { ArrowPinDialog.showSet(this, { encoded -> lock.setPattern(encoded) }) }
            } else ArrowPinDialog.showSet(this, { encoded -> lock?.setPattern(encoded) })
        }
        addRow("Clear arrow pattern", "Asks for it first") {
            PatternGate.run(this) { lock?.clearPattern(); Ui.toast(this, "Pattern cleared") }
        }
        addRow("Locked apps manager", "Count: ${try { UiWiring.apps?.getAllApps()?.count { it.locked } ?: 0 } catch (e: Throwable) { 0 }}")
        detail.addView(SwitchRow.row(this, "Lock Settings", "ON when a pattern is set and no explicit value stored (legacy rule); explicit toggle overrides", lock?.isSettingsLocked() == true) { v -> lock?.setSettingsLocked(v) })
        addRow("Forgot-pattern recovery", "System Settings → Apps → Sreeb → Clear data (resets layout too); “casual lock, by design”")
        addRow("Unlock grace (Re-ask for pattern)", "Current: ${com.mizan.launcher.core.UnlockGrace.labelFor(profile()?.unlockGraceMinutes ?: 0)} (default Every time, 0 min). Covers locked apps, folders, Settings, leaving Guest; switching profiles always asks again.") {
            Ui.menu(this, "Re-ask for pattern", listOf(0, 5, 15, 30).map { m ->
                com.mizan.launcher.core.UnlockGrace.labelFor(m) to { profile()?.let { save(it.copy(unlockGraceMinutes = m)); renderDetail() } }
            })
        }
        addRow("Profile switch", "Profile 1 ⇄ Profile 2 (tap to switch; clears the grace stamp)") {
            val cur = s?.getGlobal()?.currentProfile ?: 1
            Ui.runBg { s?.switchProfile(if (cur == 1) 2 else 1); Ui.runMain { renderDetail() } }
        }
        addRow("Rename Profile 1", "Custom name ≤ 16 chars, never leaves a trailing space; blank restores default") {
            KeyboardDialog.show(this, "Profile 1 name", maxLen = 16) { v ->
                val p = UiWiring.settings?.getProfile(1); if (p != null) save(p.copy(name = v.trim().take(16).ifBlank { "Profile 1" })); renderDetail()
            }
        }
        addRow("Rename Profile 2", "") {
            KeyboardDialog.show(this, "Profile 2 name", maxLen = 16) { v ->
                val p = UiWiring.settings?.getProfile(2); if (p != null) save(p.copy(name = v.trim().take(16).ifBlank { "Profile 2" })); renderDetail()
            }
        }
        addRow("Guest profile", "Current: ${s?.getGlobal()?.guestProfile ?: 0} (0 = none, default). Switching TO Guest is always instant; while Guest is active a Guest pill shows and opening Settings or switching back asks for the pattern (if set).") {
            Ui.menu(this, "Guest profile", listOf(
                "None" to { Ui.runBg { s?.setGuestProfile(0); Ui.runMain { renderDetail() } } },
                "Profile 1" to { Ui.runBg { s?.setGuestProfile(1); Ui.runMain { renderDetail() } } },
                "Profile 2" to { Ui.runBg { s?.setGuestProfile(2); Ui.runMain { renderDetail() } } },
            ))
        }
        detail.addView(SwitchRow.row(this, "Lock settings in Guest", "Default ON", s?.getGlobal()?.guestLockSettings == true) { v -> Ui.runBg { s?.setGuestLockSettings(v); Ui.runMain { renderDetail() } } })
    }

    private fun nowPlaying() {
        val granted = UiWiring.nowPlaying?.isListenerGranted() == true
        addRow("Now playing row toggle", "Row visibility flag; needs notification access below, otherwise the row stays hidden (default row visible)")
        addRow("Notification access", if (granted) "Granted ✓" else "Not granted — tap to open system settings") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
    }

    private fun iptv() {
        val s = UiWiring.settings; val iptv = UiWiring.iptv
        // The domain API has no password read-back (by design — credentials
        // are stored only, never exported). So the account is edited as one
        // combined form (server + username + password together) via
        // setIptvAccount, never as separate fields that would wipe the password.
        addRow("Xtream account", "Server: ${s?.getIptvServer()?.ifBlank { "(not set)" } ?: "(not set)"} · User: ${s?.getIptvUser()?.ifBlank { "(not set)" } ?: "(not set)"} · Password: •••••••• when set. Tap to edit all three together.") {
            KeyboardDialog.show(this, "IPTV server", s?.getIptvServer() ?: "", maxLen = KeyboardDialog.Caps.IPTV_SERVER) { server ->
                KeyboardDialog.show(this, "IPTV username", s?.getIptvUser() ?: "", maxLen = KeyboardDialog.Caps.IPTV_USER) { user ->
                    KeyboardDialog.show(this, "IPTV password", "", masked = true, maxLen = KeyboardDialog.Caps.IPTV_PASS) { pass ->
                        val cleanServer = server.trim().removeSuffix("/")
                        val withScheme = if (cleanServer.contains("://")) cleanServer else "http://$cleanServer"
                        s?.setIptvAccount(withScheme, user, pass); renderDetail()
                    }
                }
            }
        }
        addRow("Playlist (M3U) URL", s?.getM3uUrl()?.ifBlank { "(not set)" } ?: "(not set)") {
            KeyboardDialog.show(this, "M3U URL", s?.getM3uUrl() ?: "", maxLen = KeyboardDialog.Caps.M3U_EPG) { v -> s?.setPlaylistUrls(v, s.getEpgUrl()); renderDetail() }
        }
        addRow("Guide (XMLTV) URL", s?.getEpgUrl()?.ifBlank { "(not set)" } ?: "(not set)") {
            KeyboardDialog.show(this, "XMLTV URL", s?.getEpgUrl() ?: "", maxLen = KeyboardDialog.Caps.M3U_EPG) { v -> s?.setPlaylistUrls(s.getM3uUrl(), v); renderDetail() }
        }
        val st = iptv?.getStatus()
        addRow("Catalogue status", "Live ${st?.liveCount ?: 0} · VOD ${st?.vodCount ?: 0} · Series ${st?.seriesCount ?: 0}" + (st?.failureClass?.let { " ($it)" } ?: "") + " — data refreshes only when you save the account or tap Refresh")
        addRow("Refresh now", "Fetch catalogues + guide again") { Ui.runBg { iptv?.refresh(); Ui.runMain { renderDetail() } } }
        addRow("Clear IPTV sources…", "Removes account + playlist URLs from the TV; favorites and resume positions STAY") {
            Ui.confirm(this, "Clear IPTV sources", "Remove the IPTV account and playlist URLs? Favorites and resume positions stay.") {
                Ui.runBg { s?.clearIptvSources(); Ui.runMain { renderDetail() } }
            }
        }
        addRow("Category locks note", "Hold OK on a category chip to pattern-lock it; adult categories start locked when a pattern is set")
    }

    private fun dataAbout() {
        val s = UiWiring.settings
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (e: Throwable) { "?" }
        addRow("Version", "Sreeb v$version (read from PackageManager at runtime, never hardcoded)")
        addRow("Export settings", "Save all settings as mizan-launcher-settings.json (EXCLUDES tracker marked-set, For You overlay, pending overlay, IPTV favorites/resume, TMDB key, IPTV credentials)") {
            val json = org.json.JSONObject()
            val p = profile()
            json.put("theme", p?.themeId ?: "apple_black")
            json.put("home_style", (p?.homeStyle ?: HomeStyle.TV).name.lowercase())
            json.put("columns", p?.gridColumns ?: 6)
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                type = "application/json"; putExtra(Intent.EXTRA_TITLE, "mizan-launcher-settings.json")
            }
            try { startActivityForResult(intent, 2) } catch (e: Throwable) { Ui.toast(this, "Export: ${json}") }
        }
        addRow("Import settings", "Restore from a previously exported JSON file; rejects non-Sreeb files with “That file isn't a Sreeb backup — nothing imported”") {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "application/json" }
            try { startActivityForResult(intent, 3) } catch (e: Throwable) { Ui.toast(this, "That file isn't a Sreeb backup — nothing imported") }
        }
        addRow("TMDB API key", if ((s?.getTmdbKey()?.isNotEmpty() == true)) "Set" else "Not set" + " (masked entry, 80 chars; stored only on the TV, never in backups/diagnostics/logs; empty clears it; powers direct-TMDB fallback only)") {
            KeyboardDialog.show(this, "TMDB API key", "", masked = true, maxLen = KeyboardDialog.Caps.TMDB_KEY) { v -> s?.setTmdbKey(v); renderDetail() }
        }
        addRow("Diagnostics", (UiWiring.diagnostics?.snapshot()?.lines?.joinToString("\n") ?: "(no diagnostics)").take(2000))
        addRow("Copy diagnostics", "Copies the whole block to the clipboard") {
            val block = UiWiring.diagnostics?.copyToClipboard() ?: ""
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("diagnostics", block))
            Ui.toast(this, "Diagnostics copied")
        }
        detail.addView(SwitchRow.row(this, "Safe mode: plain launcher", "Themes/widgets/extras off (default OFF)", s?.getGlobal()?.safeMode == true) { v -> UiWiring.settings?.setSafeMode(v) })
        if (s?.getGlobal()?.safeMode == true) {
            addRow("Exit safe mode and retry normal home", "Resets the counter and returns to the normal home style now") {
                UiWiring.settings?.setSafeMode(false); renderDetail()
            }
        }
        addRow("Player aspect mode", "Per profile, not a Settings row: fit (default) / stretch / zoom — set in the Player")
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        val uri: Uri = data.data!!
        if (requestCode == 2) {
            // Phase 2D: full MAP §3.10 shape via the app-layer hook
            // (PrefsStore.exportSettingsJson); the partial inline JSON
            // below is only a fallback when the hook is unset.
            try {
                val json = UiWiring.exportSettings?.invoke()
                contentResolver.openOutputStream(uri)?.use { out ->
                    if (json != null) {
                        out.write(json.toByteArray())
                    } else {
                        val p = profile()
                        val fallback = org.json.JSONObject()
                        fallback.put("theme", p?.themeId ?: "apple_black")
                        fallback.put("home_style", (p?.homeStyle ?: HomeStyle.TV).name.lowercase())
                        fallback.put("columns", p?.gridColumns ?: 6)
                        out.write(fallback.toString().toByteArray())
                    }
                }
            } catch (e: Throwable) { Ui.toast(this, "Export failed") }
        } else if (requestCode == 3) {
            try {
                val text = contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: ""
                val imported = UiWiring.importSettings?.invoke(text)
                if (imported == true) {
                    Ui.toast(this, "Settings imported"); renderDetail(); return
                }
                if (imported == false) {
                    Ui.toast(this, "That file isn't a Sreeb backup — nothing imported"); return
                }
                val json = org.json.JSONObject(text)
                if (!json.has("theme") && !json.has("home_style")) {
                    Ui.toast(this, "That file isn't a Sreeb backup — nothing imported"); return
                }
                val p = profile()
                p?.let { save(it.copy(themeId = json.optString("theme", it.themeId), gridColumns = json.optInt("columns", it.gridColumns))) }
                Ui.toast(this, "Settings imported")
            } catch (e: Throwable) {
                Ui.toast(this, "That file isn't a Sreeb backup — nothing imported")
            }
        }
    }
}
