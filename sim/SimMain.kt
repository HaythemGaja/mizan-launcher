import com.mizan.launcher.core.AllAppsLogic
import com.mizan.launcher.core.AppSearch
import com.mizan.launcher.core.BingWallpaper
import com.mizan.launcher.core.DominantColor
import com.mizan.launcher.core.ForecastLogic
import com.mizan.launcher.core.GuestGate
import com.mizan.launcher.core.HourPoint
import com.mizan.launcher.core.DayPoint
import com.mizan.launcher.core.MoveContainer
import com.mizan.launcher.core.MoveSessionMachine
import com.mizan.launcher.core.NeverList
import com.mizan.launcher.core.NowPlayingLogic
import com.mizan.launcher.core.RecentsStore
import com.mizan.launcher.core.RotationLogic
import com.mizan.launcher.core.RowOrder
import com.mizan.launcher.core.SettingsGate
import com.mizan.launcher.core.SpeedUp
import com.mizan.launcher.core.Folder
import com.mizan.launcher.core.FolderEntry
import com.mizan.launcher.core.FolderTree
import com.mizan.launcher.core.LayoutCodec
import com.mizan.launcher.core.LayoutData
import com.mizan.launcher.core.ArrowDir
import com.mizan.launcher.core.ArrowPinCodec
import com.mizan.launcher.core.ArrowPinEntry
import com.mizan.launcher.core.ArrowPinEvent
import com.mizan.launcher.core.ArrowPinSetupFlow
import com.mizan.launcher.core.ArrowPinSetupResult
import com.mizan.launcher.core.FolderGate
import com.mizan.launcher.core.FolderLogic
import com.mizan.launcher.core.GridMove
import com.mizan.launcher.core.SettingsFit
import com.mizan.launcher.core.SettingsNavMachine
import com.mizan.launcher.core.SwitchLogic
import com.mizan.launcher.core.TileLayout
import com.mizan.launcher.core.WifiLabel
import com.mizan.launcher.core.WeatherCondition
import com.mizan.launcher.core.WeatherLogic
import com.mizan.launcher.core.WeatherUnits
import com.mizan.launcher.core.RedditCacheCodec
import com.mizan.launcher.core.RedditWallpapers
import com.mizan.launcher.core.HomeStyle
import com.mizan.launcher.core.IptvLogic
import com.mizan.launcher.core.IptvEpg
import com.mizan.launcher.core.IptvLocks
import com.mizan.launcher.core.IptvStatus
import com.mizan.launcher.core.WatchMatch
import com.mizan.launcher.core.TrackerLogic
import com.mizan.launcher.core.LibraryLogic
import com.mizan.launcher.core.AppCategories
import com.mizan.launcher.core.ArtworkStore
import com.mizan.launcher.core.Design
import com.mizan.launcher.core.HeroRotator
import com.mizan.launcher.core.LiveTvLogic
import com.mizan.launcher.core.MostUsedLogic
import com.mizan.launcher.core.QuickSettings
import com.mizan.launcher.core.TrackerSections
import com.mizan.launcher.core.StatusBarCodec
import com.mizan.launcher.core.StatusBarConfig
import com.mizan.launcher.core.StatusBarLogic
import com.mizan.launcher.core.Themes
import kotlin.math.abs

/**
 * Headless JVM sim for Mizan Launcher's pure logic.
 * Run: see run_sim.sh. Prints PASS/FAIL per check and a final count;
 * exits non-zero if anything failed.
 */
var pass = 0
var fail = 0

fun check(name: String, cond: Boolean, detail: String = "") {
    if (cond) { pass++; println("PASS  $name") }
    else { fail++; println("FAIL  $name  $detail") }
}

fun chan(c: Int, shift: Int) = (c shr shift) and 0xFF

fun closeTo(actual: Int, r: Int, g: Int, b: Int, tol: Int): Boolean =
    abs(chan(actual, 16) - r) <= tol && abs(chan(actual, 8) - g) <= tol && abs(chan(actual, 0) - b) <= tol

/**
 * Build a folder from entry KEYS (v1.5 typed-entry model): a plain
 * package name is an app entry, "@f:<id>" is a sub-folder reference —
 * the same keys the layout codec stores and move sessions order.
 */
fun testFolder(id: String, name: String, vararg keys: String, locked: Boolean = false): Folder {
    val f = Folder(id, name, mutableListOf(), locked)
    for (k in keys) f.entries.add(FolderEntry.fromKey(k))
    return f
}

fun main() {
    // ---------- (1) Never-list (v1.5: all that remains of the classifier) ----------
    // The Cleanup classifier and its SAFE/REVIEW categories were
    // REMOVED in v1.5 with the Cleanup feature. Its never-list data
    // survives as core/NeverList — Speed Up's exclusion list — and is
    // exercised here directly and again through the kill list (§20).
    check("neverlist: gms is never", NeverList.isNever("com.google.android.gms"))
    check("neverlist: launcherx is never", NeverList.isNever("com.google.android.apps.tv.launcherx"))
    check("neverlist: Play Store is never", NeverList.isNever("com.android.vending"))
    check("neverlist: Settings + SystemUI are never",
        NeverList.isNever("com.android.tv.settings") && NeverList.isNever("com.android.systemui"))
    check("neverlist: setup/keyboard/bluetooth/tvinput are never",
        NeverList.isNever("com.google.android.tungsten.setupwraith") &&
            NeverList.isNever("com.example.keyboard") &&
            NeverList.isNever("com.android.bluetooth") &&
            NeverList.isNever("com.vendor.tvinput"))
    check("neverlist: own package is never", NeverList.isNever("com.mizan.launcher"))
    check("neverlist: ordinary apps are not never",
        !NeverList.isNever("com.netflix.ninja") && !NeverList.isNever("com.example.whatever"))

    // ---------- (2) Layout store: round-trip + per-profile isolation ----------
    val a = LayoutData()
    a.favorites.addAll(listOf("com.a", "com.b", "com.c"))
    a.hidden.addAll(setOf("com.hidden1", "com.hidden2"))
    a.allAppsOrder.addAll(listOf("com.z", "com.a"))
    a.folders.add(testFolder("f1", "Kids, |Fun%", "com.a", "com.k"))
    a.folders.add(testFolder("f2", "News", "com.n"))
    a.rowOrder.clear(); a.rowOrder.addAll(listOf("apps", "favorites", "folders", "continue", "inputs"))
    a.rowVisible["continue"] = false

    val encA = LayoutCodec.encode(a)
    val back = LayoutCodec.decode(encA)
    check("layout: favorites round-trip", back.favorites == a.favorites, back.favorites.toString())
    check("layout: hidden round-trip", back.hidden == a.hidden, back.hidden.toString())
    check("layout: allAppsOrder round-trip", back.allAppsOrder == a.allAppsOrder)
    check("layout: folder names with separators round-trip",
        back.folders.map { it.name } == a.folders.map { it.name }, back.folders.map { it.name }.toString())
    check("layout: folder ids round-trip",
        back.folders.map { it.id } == a.folders.map { it.id })
    check("layout: folder apps round-trip",
        back.folders.map { it.appPackages } == a.folders.map { it.appPackages })
    check("layout: row order round-trip", back.rowOrder == a.rowOrder, back.rowOrder.toString())
    check("layout: row visibility round-trip", back.rowVisible["continue"] == false && back.isRowVisible("apps"))
    check("layout: re-encode is stable", LayoutCodec.encode(back) == encA)
    check("layout: empty/garbage decode fails open",
        LayoutCodec.decode("garbage\n===\n").favorites.isEmpty())

    // Per-profile isolation: the codec is what each profile stores under its
    // own key; two stores must not share state.
    val profileA = LayoutCodec.decode(encA)
    val profileB = LayoutCodec.decode(null)
    profileB.favorites.add("com.onlyb")
    profileB.hidden.add("com.onlyb")
    check("layout: profile isolation (favorites)", !profileA.favorites.contains("com.onlyb"))
    check("layout: profile isolation (hidden)", !profileA.hidden.contains("com.onlyb"))
    check("layout: move swaps neighbours", profileA.move(profileA.favorites, "com.b", +1) &&
        profileA.favorites == listOf("com.a", "com.c", "com.b"))
    check("layout: move at edge is no-op", !profileA.move(profileA.favorites, "com.b", +1))

    // ---------- (3) Dominant colour on synthetic bitmaps ----------
    val red = (0xFF shl 24) or (200 shl 16) or (30 shl 8) or 30
    val blue = (0xFF shl 24) or (30 shl 16) or (40 shl 8) or 210
    val mostlyRed = IntArray(1000) { if (it % 10 == 0) blue else red }
    val domRed = DominantColor.extract(mostlyRed, 0)
    check("colour: dominant red wins", closeTo(domRed, 200, 30, 30, 16), Integer.toHexString(domRed))
    val mostlyBlue = IntArray(500) { blue }
    val domBlue = DominantColor.extract(mostlyBlue, 0)
    check("colour: solid blue returned", closeTo(domBlue, 30, 40, 210, 16), Integer.toHexString(domBlue))
    val allBlack = IntArray(100) { 0xFF000000.toInt() }
    check("colour: near-black image falls back",
        DominantColor.extract(allBlack, 0xFF336699.toInt()) == 0xFF336699.toInt())
    check("colour: empty image falls back",
        DominantColor.extract(IntArray(0), 0xFF336699.toInt()) == 0xFF336699.toInt())
    check("colour: result is opaque", (DominantColor.extract(mostlyRed, 0) ushr 24) == 0xFF)

    // ---------- (4) Theme presets + gradients ----------
    check("themes: at least 8 presets + dynamic", Themes.PRESETS.size >= 9, Themes.PRESETS.size.toString())
    val ids = Themes.PRESETS.map { it.id }
    check("themes: preset ids unique", ids.distinct().size == ids.size)
    for (required in listOf("apple_black", "roku_purple", "midnight", "dusk", "plum", "forest", "ocean", "graphite", "dynamic")) {
        check("themes: preset '$required' exists", ids.contains(required))
    }
    check("themes: exactly one dynamic preset", Themes.PRESETS.count { it.dynamic } == 1)
    var presetsComplete = true
    for (p in Themes.PRESETS) {
        // Every colour fully opaque and text != background (readable).
        val colours = listOf(p.bgTop, p.bgBottom, p.accent, p.text, p.subText)
        if (colours.any { (it ushr 24) != 0xFF }) presetsComplete = false
        if (p.text == p.bgTop || p.text == p.bgBottom) presetsComplete = false
        if (p.name.isBlank()) presetsComplete = false
    }
    check("themes: every preset defines opaque bg/accent/text", presetsComplete)
    check("gradients: exactly 14 built-ins", Themes.GRADIENTS.size == 14, Themes.GRADIENTS.size.toString())
    var gradientsOk = true
    val gnames = HashSet<String>()
    for (g in Themes.GRADIENTS) {
        if ((g.top ushr 24) != 0xFF || (g.bottom ushr 24) != 0xFF) gradientsOk = false
        if (g.name.isBlank() || !gnames.add(g.name)) gradientsOk = false
    }
    check("gradients: all 14 define render params (unique names, opaque colours)", gradientsOk)
    check("themes: unknown preset id falls back to first preset",
        Themes.preset("does-not-exist") == Themes.PRESETS[0])
    check("themes: unknown gradient falls back to first gradient",
        Themes.gradient("does-not-exist") == Themes.GRADIENTS[0])

    // ---------- (5) Arrow PIN state machine (v1.2) ----------
    // REPLACES the v1.1 numeric PIN-pad tests entirely — the numeric
    // PIN is retired in v1.2 (spec section 10); the lock is now a
    // D-pad direction sequence. Old PIN tests did not carry over.
    run {
        val e1 = ArrowPinEntry()
        check("arrow append changes state", e1.append(ArrowDir.UP) == ArrowPinEvent.Changed && e1.entered == 1)
        e1.append(ArrowDir.DOWN); e1.append(ArrowDir.LEFT)
        check("arrow submit below min is too short", !e1.canSubmit && e1.submit() == ArrowPinEvent.TooShort && e1.entered == 3)
        e1.append(ArrowDir.RIGHT)
        check("arrow 4 steps can submit", e1.canSubmit)
        check("arrow submit of 4 accepted", (e1.submit() as? ArrowPinEvent.Submitted)?.sequence == "UDLR")
        // Upper bound: 8 accepted, 9th append rejected.
        val e2 = ArrowPinEntry()
        repeat(8) { e2.append(ArrowDir.UP) }
        check("arrow 8 steps still submittable", e2.canSubmit)
        check("arrow 9th append rejected as full", e2.append(ArrowDir.DOWN) == ArrowPinEvent.RejectedFull && e2.entered == 8)
        check("arrow submit of 8 accepted", (e2.submit() as? ArrowPinEvent.Submitted)?.sequence?.length == 8)
        // Backspace: removes last; empty means cancel.
        val e3 = ArrowPinEntry()
        e3.append(ArrowDir.LEFT); e3.append(ArrowDir.RIGHT)
        check("arrow backspace removes last", e3.pressBack() == ArrowPinEvent.Changed && e3.entered == 1)
        e3.pressBack()
        check("arrow backspace on empty cancels", e3.pressBack() == ArrowPinEvent.Cancelled)
        // Explicit cancel clears.
        val e4 = ArrowPinEntry()
        e4.append(ArrowDir.UP); e4.append(ArrowDir.UP)
        check("arrow cancel clears buffer", e4.cancel() == ArrowPinEvent.Cancelled && e4.entered == 0)
        // Set + confirm: match completes, mismatch restarts.
        val flow = ArrowPinSetupFlow()
        check("arrow set needs confirm", flow.submit("UDLR") == ArrowPinSetupResult.NeedConfirm && flow.awaitingConfirm)
        check("arrow set confirm match completes", flow.submit("UDLR") == ArrowPinSetupResult.Done("UDLR"))
        val flow2 = ArrowPinSetupFlow()
        flow2.submit("UDLR")
        check("arrow set confirm mismatch restarts", flow2.submit("UDRL") == ArrowPinSetupResult.Mismatch && !flow2.awaitingConfirm)
        check("arrow set after mismatch takes fresh build", flow2.submit("UDRL") == ArrowPinSetupResult.NeedConfirm)
        // Unlock check via codec.
        check("arrow unlock correct sequence matches",
            ArrowPinCodec.matches(listOf(ArrowDir.UP, ArrowDir.DOWN, ArrowDir.LEFT, ArrowDir.RIGHT), "UDLR"))
        check("arrow unlock wrong sequence rejected",
            !ArrowPinCodec.matches(listOf(ArrowDir.UP, ArrowDir.DOWN, ArrowDir.RIGHT, ArrowDir.LEFT), "UDLR"))
        check("arrow unlock empty never matches unset lock",
            !ArrowPinCodec.matches(emptyList(), ""))
        // Encoding: round-trip, validation, legacy-numeric migration discard.
        val seq = listOf(ArrowDir.UP, ArrowDir.DOWN, ArrowDir.LEFT, ArrowDir.RIGHT, ArrowDir.UP)
        check("arrow encode round-trips", ArrowPinCodec.decode(ArrowPinCodec.encode(seq)) == seq)
        check("arrow encoded form is UDLRU", ArrowPinCodec.encode(seq) == "UDLRU")
        check("arrow numeric value is legacy", ArrowPinCodec.isLegacyNumeric("2580"))
        check("arrow numeric value decodes empty (migration discard)",
            ArrowPinCodec.decode("2580").isEmpty() && !ArrowPinCodec.isValidEncoded("2580"))
        check("arrow glyphs are arrows not digits",
            ArrowDir.UP.glyph == "\u2191" && ArrowDir.DOWN.glyph == "\u2193" &&
                ArrowDir.LEFT.glyph == "\u2190" && ArrowDir.RIGHT.glyph == "\u2192")
    }

    // ---------- (6) Reddit wallpaper parser (v1.1) ----------
    val fixture = """
    {"kind":"Listing","data":{"children":[
      {"kind":"t3","data":{"title":"Mountain lake at dawn","subreddit":"EarthPorn","stickied":false,
        "is_video":false,"is_gallery":false,"over_18":false,
        "url":"https://i.redd.it/lake123.jpg","permalink":"/r/EarthPorn/comments/a1/mountain/",
        "preview":{"images":[{"source":{"url":"https://preview.redd.it/lake123.jpg?width=2560&amp;height=1440","width":2560,"height":1440}}]}}},
      {"kind":"t3","data":{"title":"Mod announcement","subreddit":"EarthPorn","stickied":true,
        "is_video":false,"is_gallery":false,"over_18":false,
        "url":"https://i.redd.it/rules.jpg",
        "preview":{"images":[{"source":{"url":"https://preview.redd.it/rules.jpg","width":1920,"height":1080}}]}}},
      {"kind":"t3","data":{"title":"Cool video","subreddit":"wallpaper","stickied":false,
        "is_video":true,"is_gallery":false,"over_18":false,
        "url":"https://v.redd.it/clip99","permalink":"/r/wallpaper/comments/a2/video/"}},
      {"kind":"t3","data":{"title":"My gallery","subreddit":"wallpaper","stickied":false,
        "is_video":false,"is_gallery":true,"over_18":false,
        "url":"https://www.reddit.com/gallery/xyz1","permalink":"/r/wallpaper/comments/a3/gallery/"}},
      {"kind":"t3","data":{"title":"Spicy art","subreddit":"wallpapers","stickied":false,
        "is_video":false,"is_gallery":false,"over_18":true,
        "url":"https://i.redd.it/spicy.jpg",
        "preview":{"images":[{"source":{"url":"https://preview.redd.it/spicy.jpg","width":1920,"height":1080}}]}}},
      {"kind":"t3","data":{"title":"Phone wallpaper portrait","subreddit":"wallpapers","stickied":false,
        "is_video":false,"is_gallery":false,"over_18":false,
        "url":"https://i.redd.it/portrait.jpg",
        "preview":{"images":[{"source":{"url":"https://preview.redd.it/portrait.jpg","width":1080,"height":1920}}]}}},
      {"kind":"t3","data":{"title":"External host desert","subreddit":"wallpaper","stickied":false,
        "is_video":false,"is_gallery":false,"over_18":false,
        "url":"https://imgur.example/desert-page","permalink":"/r/wallpaper/comments/a4/desert/",
        "preview":{"images":[{"source":{"url":"https://preview.redd.it/desert.jpg?width=3840&amp;height=2160","width":3840,"height":2160}}]}}},
      {"kind":"t3","data":{"title":"Just a question","subreddit":"wallpaper","stickied":false,
        "is_video":false,"is_gallery":false,"over_18":false,
        "url":"https://www.reddit.com/r/wallpaper/comments/a5/question/","permalink":"/r/wallpaper/comments/a5/question/"}}
    ]}}
    """.trimIndent()

    val parsed = RedditWallpapers.parseListing(fixture)
    check("reddit: only usable image posts survive", parsed.size == 2, "got ${parsed.size}")
    check("reddit: direct i.redd.it image accepted",
        parsed.any { it.url == "https://i.redd.it/lake123.jpg" && it.subreddit == "EarthPorn" })
    check("reddit: stickied rejected", parsed.none { it.url.contains("rules.jpg") })
    check("reddit: video rejected", parsed.none { it.url.contains("v.redd.it") })
    check("reddit: gallery rejected", parsed.none { it.url.contains("gallery") || it.title.contains("gallery", true) })
    check("reddit: NSFW rejected by default", parsed.none { it.url.contains("spicy.jpg") })
    check("reddit: portrait rejected", parsed.none { it.url.contains("portrait.jpg") })
    check("reddit: preview fallback accepted + URL unescaped",
        parsed.any { it.url == "https://preview.redd.it/desert.jpg?width=3840&height=2160" },
        parsed.map { it.url }.toString())
    check("reddit: landscape dimensions carried through",
        parsed.firstOrNull { it.url.contains("lake123") }?.let { it.width == 2560 && it.height == 1440 } == true)
    val parsedNsfw = RedditWallpapers.parseListing(fixture, allowNsfw = true)
    check("reddit: NSFW accepted only with the toggle on",
        parsedNsfw.size == 3 && parsedNsfw.any { it.url.contains("spicy.jpg") })
    check("reddit: malformed JSON fails open to empty",
        RedditWallpapers.parseListing("not json at all").isEmpty() &&
            RedditWallpapers.parseListing(null).isEmpty() &&
            RedditWallpapers.parseListing("{\"data\":{}}").isEmpty())

    check("reddit: valid subreddit names",
        RedditWallpapers.isValidSubreddit("EarthPorn") && RedditWallpapers.isValidSubreddit("wallpaper") &&
            RedditWallpapers.isValidSubreddit("a_b_1"))
    check("reddit: invalid subreddit names rejected",
        !RedditWallpapers.isValidSubreddit("r/wallpaper") && !RedditWallpapers.isValidSubreddit("has space") &&
            !RedditWallpapers.isValidSubreddit("x") && !RedditWallpapers.isValidSubreddit("") &&
            !RedditWallpapers.isValidSubreddit("bad-dash"))
    check("reddit: r/ prefix cleaned", RedditWallpapers.cleanSubreddit("r/EarthPorn") == "EarthPorn")
    check("reddit: cache round-trip",
        RedditCacheCodec.decode(RedditCacheCodec.encode(parsed)) == parsed)

    // ---------- (7) Status bar config (v1.1) ----------
    val defaults = StatusBarConfig()
    check("statusbar: defaults match v1.0 behaviour",
        defaults.showBar && defaults.showClock && defaults.showDate && defaults.showWeather &&
            defaults.showNetwork && defaults.showBluetooth && defaults.showQuickTiles &&
            !defaults.clock24h && !defaults.showSeconds &&
            defaults.dateFormat == StatusBarConfig.DATE_WEEKDAY_MONTH_DAY &&
            defaults.textSize == StatusBarConfig.SIZE_MEDIUM &&
            defaults.background == StatusBarConfig.BG_TRANSPARENT &&
            defaults.opacity == StatusBarConfig.OPACITY_SOLID && defaults.isDefault())
    val custom = StatusBarConfig(
        showBar = false, showWeather = false, showBluetooth = false,
        clock24h = true, showSeconds = true,
        dateFormat = StatusBarConfig.DATE_ISO, textSize = StatusBarConfig.SIZE_LARGE,
        background = StatusBarConfig.BG_BLACK, opacity = StatusBarConfig.OPACITY_TRANSLUCENT
    )
    check("statusbar: codec round-trip identity",
        StatusBarCodec.decode(StatusBarCodec.encode(custom)) == custom)
    check("statusbar: default codec round-trip identity",
        StatusBarCodec.decode(StatusBarCodec.encode(defaults)) == defaults)
    check("statusbar: garbage decode fails open to defaults",
        StatusBarCodec.decode("garbage||||") == defaults && StatusBarCodec.decode(null) == defaults)
    check("statusbar: unknown field values fall back individually",
        StatusBarCodec.decode("111111111|nope|nope|nope|nope") ==
            defaults.copy(clock24h = true, showSeconds = true))
    check("statusbar: clock patterns honour 12/24h + seconds",
        StatusBarConfig().clockPattern() == "h:mm a" &&
            StatusBarConfig(clock24h = true, showSeconds = true).clockPattern() == "H:mm:ss" &&
            StatusBarConfig(showSeconds = true).clockPattern() == "h:mm:ss a")
    check("statusbar: date patterns cover all formats",
        StatusBarConfig.DATE_FORMATS.all { StatusBarConfig(dateFormat = it).datePattern().isNotEmpty() } &&
            StatusBarConfig(dateFormat = StatusBarConfig.DATE_ISO).datePattern() == "yyyy-MM-dd")
    check("statusbar: text scales small/medium/large",
        StatusBarConfig(textSize = StatusBarConfig.SIZE_SMALL).textScale() < 1f &&
            StatusBarConfig(textSize = StatusBarConfig.SIZE_MEDIUM).textScale() == 1.0f &&
            StatusBarConfig(textSize = StatusBarConfig.SIZE_LARGE).textScale() > 1f)
    check("statusbar: opacity alpha solid vs translucent",
        StatusBarConfig.backgroundAlpha(StatusBarConfig.OPACITY_SOLID) == 0xFF &&
            StatusBarConfig.backgroundAlpha(StatusBarConfig.OPACITY_TRANSLUCENT) == 0x99)

    // Status bar travels inside the layout codec (per-profile).
    val layoutWithBar = LayoutData()
    layoutWithBar.statusBar = custom
    val layoutBack = LayoutCodec.decode(LayoutCodec.encode(layoutWithBar))
    check("statusbar: survives layout encode/decode",
        layoutBack.statusBar == custom, layoutBack.statusBar.toString())
    check("statusbar: v1.0 layout without status line decodes to defaults",
        LayoutCodec.decode("v=1\nfav=com.a\n").statusBar == defaults)

    println()
    // ---------- (8) Uniform tile layout (v1.2) ----------
    run {
        val a = TileLayout.tileMetrics(1920, 2.0f, "standard", 6)
        val b = TileLayout.tileMetrics(1920, 2.0f, "standard", 6)
        check("tiles deterministic: same inputs same size", a == b)
        val small = TileLayout.tileMetrics(1920, 2.0f, "compact", 6)
        val large = TileLayout.tileMetrics(1920, 2.0f, "large", 6)
        check("tiles scale uniformly S < M < L",
            small.tileWidthPx < a.tileWidthPx && a.tileWidthPx < large.tileWidthPx &&
                small.tileHeightPx < a.tileHeightPx && a.tileHeightPx < large.tileHeightPx)
        val more = TileLayout.tileMetrics(1920, 2.0f, "standard", 10)
        check("tiles more columns means smaller tiles", more.tileWidthPx < a.tileWidthPx)
        check("tile icon fits inside tile", a.iconPx < a.tileWidthPx && a.iconPx < a.tileHeightPx)
        val card = TileLayout.cardMetrics(1920, 2.0f, "standard", 6)
        check("watch card is uniform width > tile, same height",
            card.tileWidthPx > a.tileWidthPx && card.tileHeightPx == a.tileHeightPx)
        check("label visible only when focused", TileLayout.labelVisible(true) && !TileLayout.labelVisible(false))
    }

    // ---------- (9) Weather units + background logic (v1.2) ----------
    run {
        check("weather units fahrenheit params",
            WeatherUnits.FAHRENHEIT.apiTemperatureUnit == "fahrenheit" && WeatherUnits.FAHRENHEIT.apiWindSpeedUnit == "mph" &&
                WeatherUnits.FAHRENHEIT.tempSymbol == "\u00B0F" && WeatherUnits.FAHRENHEIT.windSymbol == "mph")
        check("weather units celsius params",
            WeatherUnits.CELSIUS.apiTemperatureUnit == "celsius" && WeatherUnits.CELSIUS.apiWindSpeedUnit == "kmh" &&
                WeatherUnits.CELSIUS.tempSymbol == "\u00B0C" && WeatherUnits.CELSIUS.windSymbol == "km/h")
        check("weather units fromKey falls back to fahrenheit",
            WeatherUnits.fromKey("celsius") == WeatherUnits.CELSIUS &&
                WeatherUnits.fromKey("junk") == WeatherUnits.FAHRENHEIT &&
                WeatherUnits.fromKey(null) == WeatherUnits.FAHRENHEIT)
        // Every WMO group, day + night.
        check("weather clear day/night", WeatherLogic.conditionFor(0, true) == WeatherCondition.CLEAR_DAY &&
            WeatherLogic.conditionFor(0, false) == WeatherCondition.CLEAR_NIGHT &&
            WeatherLogic.conditionFor(1, false) == WeatherCondition.CLEAR_NIGHT)
        check("weather partly day/night", WeatherLogic.conditionFor(2, true) == WeatherCondition.PARTLY &&
            WeatherLogic.conditionFor(2, false) == WeatherCondition.PARTLY)
        check("weather cloudy + fog", WeatherLogic.conditionFor(3, true) == WeatherCondition.CLOUDY &&
            WeatherLogic.conditionFor(45, false) == WeatherCondition.FOG &&
            WeatherLogic.conditionFor(48, true) == WeatherCondition.FOG)
        check("weather rain group all rain",
            listOf(51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82)
                .all { WeatherLogic.conditionFor(it, true) == WeatherCondition.RAIN })
        check("weather storm group", listOf(95, 96, 99)
            .all { WeatherLogic.conditionFor(it, false) == WeatherCondition.STORM })
        check("weather snow group", listOf(71, 73, 75, 77, 85, 86)
            .all { WeatherLogic.conditionFor(it, true) == WeatherCondition.SNOW })
        check("weather unknown code falls back to cloudy",
            WeatherLogic.conditionFor(999, true) == WeatherCondition.CLOUDY &&
                WeatherLogic.conditionFor(-1, false) == WeatherCondition.CLOUDY)
        // Choice: current first, forecast dominant as fallback.
        check("weather choice prefers current",
            WeatherLogic.chooseCondition(0, true, listOf(95)) == WeatherCondition.CLEAR_DAY)
        check("weather choice falls back to forecast dominant",
            WeatherLogic.chooseCondition(null, true, listOf(0, 61, 3)) == WeatherCondition.RAIN)
        check("weather choice fallback worst wins",
            WeatherLogic.chooseCondition(null, true, listOf(61, 71)) == WeatherCondition.SNOW &&
                WeatherLogic.chooseCondition(null, false, listOf(0, 95)) == WeatherCondition.STORM)
        check("weather choice with nothing is null",
            WeatherLogic.chooseCondition(null, true, emptyList()) == null)
        check("weather palettes complete + opaque for every condition",
            WeatherCondition.values().all { c ->
                val p = WeatherLogic.paletteFor(c)
                p.motif == c.motif && (p.top ushr 24) == 0xFF && (p.bottom ushr 24) == 0xFF && (p.accent ushr 24) == 0xFF
            })
    }

    // ---------- (10) Settings two-column nav machine (v1.2) ----------
    run {
        val cats = listOf("home", "statusbar", "look", "weather", "lock", "safe")
        val navm = SettingsNavMachine(cats)
        check("settings starts in list column, first category",
            navm.focusColumn == SettingsNavMachine.COLUMN_LEFT && navm.selectedId == "home" && !navm.inDetail)
        check("settings right enters detail", navm.enterDetail() && navm.inDetail)
        check("settings right again is a no-op", !navm.enterDetail())
        check("settings left/back returns to list", navm.backToList() && !navm.inDetail)
        check("settings back in list stays (caller exits)", !navm.backToList())
        navm.select(3)
        check("settings category change swaps detail model", navm.selectedId == "weather")
        navm.select(99)
        check("settings selection clamps at end", navm.selectedIndex == cats.size - 1)
        navm.select(-5)
        check("settings selection clamps at start", navm.selectedIndex == 0)
        check("settings move down works", navm.moveSelection(1) && navm.selectedIndex == 1)
        navm.select(cats.size - 1)
        check("settings move past end clamps (no wrap)", !navm.moveSelection(1) && navm.selectedIndex == cats.size - 1)
        navm.noteFocus(SettingsNavMachine.COLUMN_RIGHT)
        check("settings focus sync marks detail", navm.inDetail)
        navm.noteFocus(99)
        check("settings focus sync ignores nonsense", navm.inDetail)
    }

    // ---------- (11) Folder dedupe + folder lock (v1.3) ----------
    run {
        val folders = listOf(
            testFolder("f1", "Kids", "com.kid1", "com.shared"),
            testFolder("f2", "News", "com.news1", "com.shared")
        )
        val installed = listOf("com.a", "com.kid1", "com.b", "com.shared", "com.news1", "com.c")
        check("dedupe: apps in folders are excluded from All Apps",
            FolderLogic.allAppsVisible(installed, emptySet(), folders) == listOf("com.a", "com.b", "com.c"))
        check("dedupe: app in two folders excluded once",
            FolderLogic.folderPackages(folders).count { it == "com.shared" } == 1 &&
                !FolderLogic.allAppsVisible(installed, emptySet(), folders).contains("com.shared"))
        val afterRemoval = listOf(
            testFolder("f1", "Kids", "com.shared"),
            testFolder("f2", "News", "com.news1", "com.shared")
        )
        check("dedupe: removing an app from its folder restores it",
            FolderLogic.allAppsVisible(installed, emptySet(), afterRemoval).contains("com.kid1"))
        check("dedupe: hidden + in-folder still excluded",
            !FolderLogic.allAppsVisible(installed, setOf("com.b"), folders).contains("com.b") &&
                FolderLogic.allAppsVisible(installed, setOf("com.b"), folders) == listOf("com.a", "com.c"))
        check("dedupe: empty folder list keeps everything visible",
            FolderLogic.allAppsVisible(installed, emptySet(), emptyList()) == installed)
        check("dedupe: favorites unaffected (favorite may live in a folder)",
            FolderLogic.folderPackages(folders).contains("com.kid1")) // membership is data, not a block

        // Folder lock: codec round-trip + backward compat.
        val lf = LayoutData()
        lf.folders.add(testFolder("f1", "Kids", "com.a", locked = true))
        lf.folders.add(testFolder("f2", "Open", "com.b", locked = false))
        val lback = LayoutCodec.decode(LayoutCodec.encode(lf))
        check("folderlock: locked flag round-trips",
            lback.folders.size == 2 && lback.folders[0].locked && !lback.folders[1].locked &&
                lback.folders[0].appPackages == listOf("com.a"))
        check("folderlock: re-encode is stable",
            LayoutCodec.encode(lback) == LayoutCodec.encode(lf))
        // A v1.2-era folder line (no lock segment) decodes unlocked.
        val legacy = LayoutCodec.decode("v=1\nfolder=Legacy|com.a,com.b\nfolder=Empty|\n")
        check("folderlock: v1.2 folder strings decode unlocked",
            legacy.folders.size == 2 && legacy.folders.none { it.locked } &&
                legacy.folders[0].appPackages == listOf("com.a", "com.b") &&
                legacy.folders[1].appPackages.isEmpty())
        // v1.5 migration: legacy lines carry no id — decode assigns
        // stable ids in line order, contents unchanged.
        check("folderlock: legacy strings migrate with sequential ids",
            legacy.folders.map { it.id } == listOf("f1", "f2"))
        val vaultLayout = LayoutData()
        vaultLayout.folders.add(testFolder("f1", "Vault", locked = true))
        check("folderlock: locked empty folder round-trips",
            LayoutCodec.decode(LayoutCodec.encode(vaultLayout)).folders.single().locked)

        // Gate decision.
        val open = testFolder("f1", "Open")
        val vault = testFolder("f2", "Vault", locked = true)
        check("foldergate: unlocked folder opens",
            FolderLogic.folderGate(open, patternSet = false) == FolderGate.OPEN &&
                FolderLogic.folderGate(open, patternSet = true) == FolderGate.OPEN)
        check("foldergate: locked with pattern prompts for pattern",
            FolderLogic.folderGate(vault, patternSet = true) == FolderGate.PROMPT_PATTERN)
        check("foldergate: locked without pattern prompts to set one (never locked out)",
            FolderLogic.folderGate(vault, patternSet = false) == FolderGate.PROMPT_SET_PATTERN)
    }

    // ---------- (12) Grid hold-and-move (v1.3) ----------
    run {
        // 7 items in 3 columns: rows [a b c] [d e f] [g]
        val base = mutableListOf("a", "b", "c", "d", "e", "f", "g")
        val l1 = base.toMutableList()
        check("gridmove: left swaps with neighbour",
            GridMove.gridMove(l1, 1, ArrowDir.LEFT, 3) == 0 && l1 == listOf("b", "a", "c", "d", "e", "f", "g"))
        val l2 = base.toMutableList()
        check("gridmove: left at row start is no-op (no wrap)",
            GridMove.gridMove(l2, 3, ArrowDir.LEFT, 3) == -1 && l2 == base)
        val l3 = base.toMutableList()
        check("gridmove: right swaps with neighbour",
            GridMove.gridMove(l3, 0, ArrowDir.RIGHT, 3) == 1 && l3 == listOf("b", "a", "c", "d", "e", "f", "g"))
        val l4 = base.toMutableList()
        check("gridmove: right at row end is no-op (no wrap)",
            GridMove.gridMove(l4, 2, ArrowDir.RIGHT, 3) == -1 && l4 == base)
        check("gridmove: right at very last item is no-op",
            GridMove.gridMove(base.toMutableList(), 6, ArrowDir.RIGHT, 3) == -1)
        val l5 = base.toMutableList()
        check("gridmove: up swaps with item one row up",
            GridMove.gridMove(l5, 4, ArrowDir.UP, 3) == 1 && l5 == listOf("a", "e", "c", "d", "b", "f", "g"))
        check("gridmove: up from top row is no-op",
            GridMove.gridMove(base.toMutableList(), 1, ArrowDir.UP, 3) == -1)
        val l6 = base.toMutableList()
        check("gridmove: down swaps with item one row down",
            GridMove.gridMove(l6, 0, ArrowDir.DOWN, 3) == 3 && l6 == listOf("d", "b", "c", "a", "e", "f", "g"))
        check("gridmove: down into partial last row works where an item sits below",
            GridMove.gridMove(base.toMutableList(), 3, ArrowDir.DOWN, 3) == 6)
        check("gridmove: down is no-op where partial row has no item below",
            GridMove.gridMove(base.toMutableList(), 4, ArrowDir.DOWN, 3) == -1 &&
                GridMove.gridMove(base.toMutableList(), 5, ArrowDir.DOWN, 3) == -1)
        check("gridmove: single item list never moves",
            ArrowDir.values().all { GridMove.gridMove(mutableListOf("x"), 0, it, 3) == -1 })
        check("gridmove: invalid index/columns are no-ops",
            GridMove.gridMove(base.toMutableList(), 99, ArrowDir.LEFT, 3) == -1 &&
                GridMove.gridMove(base.toMutableList(), 0, ArrowDir.LEFT, 0) == -1)
        // Snapshot/restore: cancel must restore the exact original order.
        val working = base.toMutableList()
        val snapshot = working.toList()
        GridMove.gridMove(working, 0, ArrowDir.DOWN, 3)
        GridMove.gridMove(working, 3, ArrowDir.RIGHT, 3)
        working.clear(); working.addAll(snapshot)
        check("gridmove: cancel restores the exact snapshot", working == base)
        // Commit path: the moved list differs and keeps every element once.
        val committed = base.toMutableList()
        GridMove.gridMove(committed, 6, ArrowDir.UP, 3)
        check("gridmove: drop-commit keeps all items exactly once",
            committed.sorted() == base.sorted() && committed != base)
    }

    // ---------- (13) Per-tile status-bar switches (v1.3) ----------
    run {
        check("tileswitch: defaults all on except the Wi-Fi tile (v2.1: glyph + popover cover it)",
            StatusBarConfig().let {
                !it.showTileWifi && it.showTileBluetooth && it.showTileSound &&
                    it.showTilePicture && it.showTileSettings && it.showTileSearch &&
                    it.showTileAllApps
            })
        val tilesOff = StatusBarConfig(showTileSound = false, showTileWifi = false)
        check("tileswitch: codec round-trip with tiles off",
            StatusBarCodec.decode(StatusBarCodec.encode(tilesOff)) == tilesOff)
        check("tileswitch: sound independently off, others on",
            StatusBarCodec.decode(StatusBarCodec.encode(tilesOff)).let {
                !it.showTileSound && it.showTileBluetooth && it.showTilePicture && it.showTileSettings
            })
        // A v1.2 status line (5 fields, no tile segment) decodes to
        // the plain defaults — every tile on except Wi-Fi (v2.1).
        val v12 = StatusBarCodec.decode("111111111|iso|medium|black|solid")
        check("tileswitch: v1.2 status strings decode to defaults (Wi-Fi tile off since v2.1)",
            !v12.showTileWifi && v12.showTileBluetooth && v12.showTileSound &&
                v12.showTilePicture && v12.showTileSettings && v12.dateFormat == StatusBarConfig.DATE_ISO)
        check("tileswitch: tiles survive the layout codec",
            LayoutCodec.decode(LayoutCodec.encode(LayoutData().apply { statusBar = tilesOff })).statusBar == tilesOff)
        check("tileswitch: garbage tiles segment fails open to defaults (Wi-Fi off, others on — v2.1)",
            StatusBarCodec.decode("111111111|xx|iso|medium|black|solid").let {
                !it.showTileWifi && it.showTileSound
            })

        // v2.1: codec migration — a pre-v2.1 7-char tile segment whose
        // Wi-Fi char said ON must decode OFF (the reorganized bar
        // lands on saved profiles); the grown 8-char segment's last
        // char is authoritative, and round-trips a re-enabled tile.
        check("tilecodec: v2.0 7-char segment migrates the Wi-Fi tile to off",
            !StatusBarCodec.decode("111111111|1111111|iso|medium|black|solid").showTileWifi)
        check("tilecodec: 8-char segment honours the Wi-Fi flag",
            StatusBarCodec.decode("111111111|00000001|iso|medium|black|solid").showTileWifi &&
                !StatusBarCodec.decode("111111111|00000000|iso|medium|black|solid").showTileWifi)
        check("tilecodec: re-enabled Wi-Fi tile survives encode/decode",
            StatusBarCodec.decode(StatusBarCodec.encode(StatusBarConfig(showTileWifi = true))).showTileWifi)

        // v2.1: bar composition (core/StatusBarLogic) — the exact
        // cluster MainActivity renders.
        val bart = StatusBarLogic.BarTile.values()
        fun tiles(cfg: StatusBarConfig, safe: Boolean) = StatusBarLogic.barTiles(cfg, safe)
        check("bartiles: default bar = All Apps, Search, Profile, Settings",
            tiles(StatusBarConfig(), false) == listOf(
                bart[0], bart[1], bart[3], bart[4]))
        check("bartiles: re-enabled Wi-Fi tile sits between Search and Profile",
            tiles(StatusBarConfig(showTileWifi = true), false) == listOf(
                bart[0], bart[1], bart[2], bart[3], bart[4]))
        check("bartiles: master tiles off leaves Profile + Settings",
            tiles(StatusBarConfig(showQuickTiles = false), false) == listOf(bart[3], bart[4]))
        check("bartiles: safe mode shows the Settings tile only",
            tiles(StatusBarConfig(), true) == listOf(bart[4]))
        check("bartiles: Settings switch off drops the tile (corner gear covers it)",
            tiles(StatusBarConfig(showTileSettings = false), false) == listOf(
                bart[0], bart[1], bart[3]))

        // v2.1: status glyphs (core/WifiLabel) — symbol + dimming.
        check("statusglyph: Wi-Fi full alpha, Ethernet globe, offline dimmed",
            WifiLabel.glyph(WifiLabel.Kind.WIFI) == "📶" &&
                WifiLabel.glyphAlpha(WifiLabel.Kind.WIFI) == 1f &&
                WifiLabel.glyph(WifiLabel.Kind.ETHERNET) == "🌐" &&
                WifiLabel.glyphAlpha(WifiLabel.Kind.OFFLINE) == 0.35f)
    }

    // ---------- (14) Settings fit metrics (v1.3) ----------
    run {
        val full = SettingsFit.compute(1920, 1080, 1.0f)
        check("fit: 1920x1080 is normal (not compact)",
            !full.compact && !full.stacked && full.headerSp == 24f && full.rowTextSp == 19f)
        check("fit: left pane is 30% clamped to 260..360dp",
            full.leftPaneWidthDp == 360 &&
                SettingsFit.compute(1000, 900, 1.0f).leftPaneWidthDp == 300 &&
                SettingsFit.compute(800, 900, 1.0f).leftPaneWidthDp == 260)
        val hd = SettingsFit.compute(1280, 720, 1.0f)
        check("fit: 1280x720 is compact with smaller type/padding",
            hd.compact && hd.headerSp == 20f && hd.rowTextSp == 17f && hd.rowPadVDp < full.rowPadVDp)
        val small = SettingsFit.compute(960, 540, 1.0f)
        check("fit: 960x540 is compact", small.compact)
        check("fit: density converts px to dp (1920px @2x = 540dp tall = compact)",
            SettingsFit.compute(1920, 1080, 2.0f).compact)
        val portrait = SettingsFit.compute(1080, 1920, 2.0f)
        check("fit: portrait/narrow stacks panes",
            portrait.stacked && portrait.leftPaneWidthDp == 540)
        check("fit: bottom padding is at least one row height at every size",
            listOf(full, hd, small, portrait).all { it.detailBottomPadDp >= it.rowHeightDp })
    }

    // ---------- (15) iOS switch logic (v1.3) ----------
    run {
        check("switch: on = green track, knob right",
            SwitchLogic.trackColor(true) == 0xFF34C759.toInt() && SwitchLogic.knobFraction(true) == 1f)
        check("switch: off = grey track, knob left",
            SwitchLogic.trackColor(false) == 0xFF3A3A3C.toInt() && SwitchLogic.knobFraction(false) == 0f)
        check("switch: toggle flips both ways",
            SwitchLogic.toggled(true) == false && SwitchLogic.toggled(false) == true &&
                SwitchLogic.KNOB == 0xFFFFFFFF.toInt())
    }

    // ---------- (16) Wi-Fi SSID label (v1.3) ----------
    run {
        check("wifi: quoted SSID is cleaned", WifiLabel.cleanSsid("\"HomeWifi\"") == "HomeWifi")
        check("wifi: unquoted SSID passes through", WifiLabel.cleanSsid("HomeWifi") == "HomeWifi")
        check("wifi: unknown sentinel becomes no-name",
            WifiLabel.cleanSsid("<unknown ssid>") == null && WifiLabel.cleanSsid("<UNKNOWN SSID>") == null)
        check("wifi: null/empty/quotes-only become no-name",
            WifiLabel.cleanSsid(null) == null && WifiLabel.cleanSsid("") == null &&
                WifiLabel.cleanSsid("\"\"") == null && WifiLabel.cleanSsid("   ") == null)
        check("wifi: label shows the network name",
            WifiLabel.label(WifiLabel.Kind.WIFI, "\"HomeWifi\"") == "📶 HomeWifi")
        check("wifi: label falls back to generic Wi-Fi without a name",
            WifiLabel.label(WifiLabel.Kind.WIFI, "<unknown ssid>") == "● Wi-Fi" &&
                WifiLabel.label(WifiLabel.Kind.WIFI, null) == "● Wi-Fi")
        check("wifi: ethernet/other/offline labels unchanged",
            WifiLabel.label(WifiLabel.Kind.ETHERNET, "\"HomeWifi\"") == "● Ethernet" &&
                WifiLabel.label(WifiLabel.Kind.OTHER, null) == "● Online" &&
                WifiLabel.label(WifiLabel.Kind.OFFLINE, null) == "● Offline")
    }

    // ---------- (17) Forecast formatting (v1.4) ----------
    run {
        // 48 hourly entries starting 2026-10-20T00:00, temp = hour index.
        val hours = (0 until 48).map { i ->
            val day = 20 + i / 24
            val h = i % 24
            HourPoint("2026-10-%02dT%02d:00".format(day, h), i.toDouble(), 0)
        }
        check("forecast: current hour index found",
            ForecastLogic.currentHourIndex(hours.map { it.timeIso }, "2026-10-20T14") == 14)
        check("forecast: current hour index missing is -1",
            ForecastLogic.currentHourIndex(hours.map { it.timeIso }, "2026-11-01T09") == -1 &&
                ForecastLogic.currentHourIndex(hours.map { it.timeIso }, "") == -1)
        val window = ForecastLogic.hourWindow(hours, 14)
        check("forecast: window has 24 entries starting at current hour",
            window.size == 24 && window.first().timeIso == "2026-10-20T14:00" &&
                window.last().timeIso == "2026-10-21T13:00")
        // Midnight wrap: starting at 22:00, labels run 10 PM, 11 PM, 12 AM, 1 AM...
        val late = ForecastLogic.hourWindow(hours, 22)
        check("forecast: window wraps past midnight",
            late[0].timeIso.endsWith("T22:00") && late[2].timeIso == "2026-10-21T00:00")
        check("forecast: 12h labels wrap at midnight",
            ForecastLogic.hourLabel("2026-10-20T22:00", false) == "10 PM" &&
                ForecastLogic.hourLabel("2026-10-20T23:00", false) == "11 PM" &&
                ForecastLogic.hourLabel("2026-10-21T00:00", false) == "12 AM" &&
                ForecastLogic.hourLabel("2026-10-21T01:00", false) == "1 AM" &&
                ForecastLogic.hourLabel("2026-10-21T12:00", false) == "12 PM")
        check("forecast: 24h labels",
            ForecastLogic.hourLabel("2026-10-20T14:00", true) == "14:00" &&
                ForecastLogic.hourLabel("2026-10-20T00:00", true) == "00:00" &&
                ForecastLogic.hourLabel("garbage", true) == "")
        // Near the end of the array the window wraps modulo to stay full.
        val tail = ForecastLogic.hourWindow(hours, 40)
        check("forecast: window wraps modulo at array end",
            tail.size == 24 && tail.first().timeIso == "2026-10-21T16:00" &&
                tail.last().timeIso == "2026-10-20T15:00")
        check("forecast: short source yields whole source, empty yields empty",
            ForecastLogic.hourWindow(hours.take(5), 2).size == 5 &&
                ForecastLogic.hourWindow(emptyList(), 0).isEmpty())
        check("forecast: temp renders in Fahrenheit",
            ForecastLogic.tempText(72.4, WeatherUnits.FAHRENHEIT) == "72°F" &&
                ForecastLogic.tempText(72.5, WeatherUnits.FAHRENHEIT) == "73°F")
        check("forecast: temp renders in Celsius",
            ForecastLogic.tempText(22.2, WeatherUnits.CELSIUS) == "22°C")
        val day = DayPoint("2026-10-20", 61, 68.0, 51.0, 80)
        val dayRow = ForecastLogic.dayRow(day, WeatherUnits.FAHRENHEIT)
        check("forecast: day row label is weekday + date (2026-10-20 is a Tuesday)",
            dayRow.label == "Tue, Oct 20", dayRow.label)
        check("forecast: day row condition + hi/lo + precip",
            dayRow.condition == "Rain" && dayRow.hiLo == "68°F / 51°F" && dayRow.precip == "💧 80%")
        check("forecast: day row without precip omits it",
            ForecastLogic.dayRow(day.copy(precipProb = null), WeatherUnits.CELSIUS).precip == null &&
                ForecastLogic.dayRow(day.copy(precipProb = null), WeatherUnits.CELSIUS).hiLo == "68°C / 51°C")
        check("forecast: hour row formats all fields",
            ForecastLogic.hourRow(HourPoint("2026-10-20T14:00", 71.6, 2), WeatherUnits.FAHRENHEIT, false) ==
                com.mizan.launcher.core.HourRow("2 PM", "72°F", "Partly cloudy"))
        check("forecast: condition labels cover groups",
            ForecastLogic.conditionLabel(0) == "Clear" &&
                ForecastLogic.conditionLabel(95) == "Storm" &&
                ForecastLogic.conditionLabel(71) == "Snow" &&
                ForecastLogic.conditionLabel(45) == "Fog")
    }

    // ---------- (18) Rotation + Bing (v1.4) ----------
    run {
        check("rotation: off is never due",
            !RotationLogic.isDue(10_000_000L, 0L, RotationLogic.OFF) &&
                !RotationLogic.isDue(10_000_000L, 0L, "bogus"))
        check("rotation: never rotated counts as due",
            RotationLogic.isDue(1_000L, 0L, RotationLogic.EVERY_15_MIN))
        check("rotation: not due before interval, due at exact boundary",
            !RotationLogic.isDue(899_999L, 0L + 1L, RotationLogic.EVERY_15_MIN) &&
                RotationLogic.isDue(900_001L, 1L, RotationLogic.EVERY_15_MIN))
        check("rotation: 1h and 6h and daily intervals",
            RotationLogic.intervalMillis(RotationLogic.EVERY_1_HOUR) == 3_600_000L &&
                RotationLogic.intervalMillis(RotationLogic.EVERY_6_HOURS) == 21_600_000L &&
                RotationLogic.intervalMillis(RotationLogic.DAILY) == 86_400_000L &&
                !RotationLogic.isDue(3_599_999L, 0L + 1L, RotationLogic.EVERY_1_HOUR))
        check("rotation: next index advances and wraps",
            RotationLogic.nextIndex(0, 3) == 1 && RotationLogic.nextIndex(2, 3) == 0 &&
                RotationLogic.nextIndex(-1, 3) == 0)
        check("rotation: next index empty list is -1, stale index wraps",
            RotationLogic.nextIndex(0, 0) == -1 && RotationLogic.nextIndex(9, 3) == 0)
        val bingJson = """{"images":[{"url":"/th?id=OHR.Test_EN-US1_1920x1080.jpg","title":"x"}],"tooltips":{}}"""
        check("bing: relative url becomes absolute",
            BingWallpaper.parseImageUrl(bingJson) ==
                "https://www.bing.com/th?id=OHR.Test_EN-US1_1920x1080.jpg")
        check("bing: absolute url passes through",
            BingWallpaper.parseImageUrl("""{"images":[{"url":"https://cdn.example.com/a.jpg"}]}""") ==
                "https://cdn.example.com/a.jpg")
        check("bing: malformed json fails open to null",
            BingWallpaper.parseImageUrl("not json") == null &&
                BingWallpaper.parseImageUrl("""{"images":[]}""") == null &&
                BingWallpaper.parseImageUrl(null) == null &&
                BingWallpaper.parseImageUrl("""{"images":[{"url":""}]}""") == null)
        check("bing: day key is the calendar date",
            BingWallpaper.dayKey(java.util.GregorianCalendar(2026, 9, 20, 23, 59).timeInMillis)
                .matches(Regex("\\d{4}-\\d{2}-\\d{2}")) &&
                BingWallpaper.dayKey(0L).isNotEmpty())
        check("bing: day key changes across midnight",
            BingWallpaper.dayKey(java.util.GregorianCalendar(2026, 9, 20, 23, 59).timeInMillis) !=
                BingWallpaper.dayKey(java.util.GregorianCalendar(2026, 9, 21, 0, 1).timeInMillis))
    }

    // ---------- (19) App search filter (v1.4) ----------
    run {
        val apps = listOf(
            AppSearch.Item("com.netflix", "Netflix"),
            AppSearch.Item("com.youtube", "YouTube"),
            AppSearch.Item("com.myyou", "My YouTube Clips"),
            AppSearch.Item("com.youcount", "YouCount"),
            AppSearch.Item("com.hbo", "HBO Max")
        )
        check("search: empty query returns all in order",
            AppSearch.filter(apps, "") == apps && AppSearch.filter(apps, "   ") == apps)
        check("search: substring match is case-insensitive",
            AppSearch.filter(apps, "TUBE").map { it.pkg } ==
                listOf("com.youtube", "com.myyou"))
        check("search: prefix matches sort before substring matches",
            AppSearch.filter(apps, "you").map { it.pkg } ==
                listOf("com.youtube", "com.youcount", "com.myyou"))
        check("search: no match is empty",
            AppSearch.filter(apps, "zzz").isEmpty())
        check("search: order preserved inside groups",
            AppSearch.filter(apps, "o").map { it.pkg } ==
                listOf("com.youtube", "com.myyou", "com.youcount", "com.hbo"))
    }

    // ---------- (20) Speed up kill list (v1.4) ----------
    run {
        val candidates = listOf(
            SpeedUp.Candidate("com.google.android.gms", false),
            SpeedUp.Candidate("com.android.systemui", true),
            SpeedUp.Candidate("com.vendor.framework", true),
            SpeedUp.Candidate("com.mizan.launcher", false),
            SpeedUp.Candidate("com.netflix.ninja", false),
            SpeedUp.Candidate("com.spotify.tv", false),
            SpeedUp.Candidate("com.netflix.ninja", false)
        )
        check("speedup: excludes never-list, system, self, foreground; dedupes",
            SpeedUp.killList(candidates, "com.mizan.launcher", "com.spotify.tv") ==
                listOf("com.netflix.ninja"))
        check("speedup: null foreground excludes nothing extra",
            SpeedUp.killList(candidates, "com.mizan.launcher", null) ==
                listOf("com.netflix.ninja", "com.spotify.tv"))
        check("speedup: never-list wins even for non-system flag",
            SpeedUp.killList(
                listOf(SpeedUp.Candidate("com.google.android.apps.tv.launcherx", false)),
                "com.mizan.launcher", null
            ).isEmpty())
        check("speedup: empty in, empty out",
            SpeedUp.killList(emptyList(), "com.mizan.launcher", null).isEmpty())
    }

    // ---------- (21) Recents store (v1.4) ----------
    run {
        var recents = RecentsStore.decode("")
        check("recents: empty decodes empty", recents.isEmpty())
        recents = RecentsStore.record(recents, "com.a", 100L, "com.mizan.launcher")
        recents = RecentsStore.record(recents, "com.b", 200L, "com.mizan.launcher")
        recents = RecentsStore.record(recents, "com.c", 300L, "com.mizan.launcher")
        check("recents: MRU order is newest first",
            recents.map { it.pkg } == listOf("com.c", "com.b", "com.a"))
        recents = RecentsStore.record(recents, "com.a", 400L, "com.mizan.launcher")
        check("recents: relaunch dedupes to front with new timestamp",
            recents.map { it.pkg } == listOf("com.a", "com.c", "com.b") && recents[0].at == 400L)
        recents = RecentsStore.record(recents, "com.mizan.launcher", 500L, "com.mizan.launcher")
        check("recents: self is never recorded",
            recents.none { it.pkg == "com.mizan.launcher" } && recents.size == 3)
        var many = RecentsStore.decode("")
        for (i in 1..12) many = RecentsStore.record(many, "com.app$i", i.toLong(), "com.mizan.launcher")
        check("recents: capped at 8, oldest dropped",
            many.size == RecentsStore.CAP && many.first().pkg == "com.app12" &&
                many.last().pkg == "com.app5")
        val roundTrip = RecentsStore.decode(RecentsStore.encode(many))
        check("recents: codec round-trips", roundTrip == many)
        check("recents: garbage decodes fail-open",
            RecentsStore.decode("|||,,|,x|notanumber").isEmpty() &&
                RecentsStore.decode(null).isEmpty())
    }

    // ---------- (22) Now Playing mapping (v1.4) ----------
    run {
        val np = NowPlayingLogic
        val sessions = listOf(
            NowPlayingLogic.RawSession("com.spotify.tv", "Song One", "Artist A",
                np.STATE_PLAYING, np.ACTION_PLAY_PAUSE or np.ACTION_SKIP_TO_NEXT or np.ACTION_SKIP_TO_PREVIOUS),
            NowPlayingLogic.RawSession("com.spotify.tv", "Song One (dup)", "Artist A",
                np.STATE_PAUSED, np.ACTION_PLAY_PAUSE),
            NowPlayingLogic.RawSession("com.android.systemui", "System", "", np.STATE_PLAYING, np.ACTION_PLAY_PAUSE),
            NowPlayingLogic.RawSession("com.example.notitle", "  ", "Nobody", np.STATE_PLAYING, np.ACTION_PLAY_PAUSE),
            NowPlayingLogic.RawSession("com.youtube.tv", "A Video", null, np.STATE_PAUSED, np.ACTION_PLAY_PAUSE)
        )
        val cards = np.cards(sessions)
        check("nowplaying: dedupes by package, filters system-ui and blank titles",
            cards.map { it.pkg } == listOf("com.spotify.tv", "com.youtube.tv"))
        check("nowplaying: first session wins for a package",
            cards[0].title == "Song One" && cards[0].playing)
        check("nowplaying: controls follow the actions mask",
            cards[0].canToggle && cards[0].canNext && cards[0].canPrev &&
                !cards[1].canNext && !cards[1].canPrev && cards[1].canToggle)
        check("nowplaying: paused state is not playing, artist may be empty",
            !cards[1].playing && cards[1].artist == "")
        check("nowplaying: no actions means no toggle",
            !np.cards(listOf(NowPlayingLogic.RawSession("com.x", "T", "A", np.STATE_PAUSED, 0L)))[0].canToggle)
        check("nowplaying: buffering reads as playing",
            np.cards(listOf(NowPlayingLogic.RawSession("com.x", "T", "A", np.STATE_BUFFERING, np.ACTION_PAUSE)))[0].playing)
        check("nowplaying: empty in, empty out", np.cards(emptyList()).isEmpty())
    }

    // ---------- (23) Guest gate (v1.4, trimmed v1.5) ----------
    // v1.5: the OPEN_SETTINGS and OPEN_CLEANUP actions are GONE from
    // the enum — Cleanup no longer exists, and Settings entry is
    // decided by core/SettingsGate (section 27), which merges the
    // guest rule with the global Lock Settings switch into one
    // prompt. The two removed checks covered exactly those actions;
    // the profile-switch gate keeps its full coverage here.
    run {
        val g = GuestGate
        val away = GuestGate.Action.SWITCH_AWAY_FROM_GUEST
        val toGuest = GuestGate.Action.SWITCH_TO_GUEST
        val personal = GuestGate.Action.SWITCH_BETWEEN_PERSONAL
        check("guest: locked guest must enter pattern to switch away",
            g.decide(true, true, true, away) == GuestGate.Decision.REQUIRE_PATTERN)
        check("guest: switching TO guest is never gated",
            g.decide(false, true, true, toGuest) == GuestGate.Decision.ALLOW &&
                g.decide(true, true, true, toGuest) == GuestGate.Decision.ALLOW)
        check("guest: fail-open when no pattern set",
            g.decide(true, true, false, away) == GuestGate.Decision.ALLOW)
        check("guest: lock switch off means no gate",
            g.decide(true, false, true, away) == GuestGate.Decision.ALLOW)
        check("guest: not in guest means no gate",
            g.decide(false, true, true, away) == GuestGate.Decision.ALLOW)
        check("guest: personal-to-personal switch is free",
            g.decide(false, true, true, personal) == GuestGate.Decision.ALLOW)
        check("guest: active flag only for the flagged profile",
            g.isGuestActive(2, 2) && !g.isGuestActive(1, 2) && !g.isGuestActive(1, 0))
    }

    // ---------- (24) Row order normalize + status codec v1.4 (v1.4) ----------
    run {
        val old = listOf("favorites", "folders", "continue", "inputs", "apps")
        val normalized = RowOrder.normalize(old)
        // v2.0 (updated in place): the inserted-row set grew — mostused
        // follows recents, livetv follows inputs (spec §18).
        check("rows: stored order gains new rows after their anchors",
            normalized == listOf("favorites", "recents", "mostused", "folders", "nowplaying", "continue", "inputs", "livetv", "apps"),
            normalized.toString())
        check("rows: normalize is idempotent",
            RowOrder.normalize(normalized) == normalized)
        val custom = listOf("apps", "favorites", "folders", "continue", "inputs")
        check("rows: custom order preserved, insertions follow their anchors",
            RowOrder.normalize(custom) ==
                listOf("apps", "favorites", "recents", "mostused", "folders", "nowplaying", "continue", "inputs", "livetv"))
        check("rows: empty order gains the inserted rows",
            RowOrder.normalize(emptyList()) == listOf("recents", "mostused", "nowplaying", "livetv"))
        // Status codec: v1.4 writes 6 tile flags; v1.3's 5-flag strings
        // decode with Search ON; the flag itself round-trips.
        val searchOff = StatusBarConfig(showTileSearch = false)
        val decodedOff = StatusBarCodec.decode(StatusBarCodec.encode(searchOff))
        check("statusbar: search tile flag round-trips (Wi-Fi tile flag is the v2.1 default-off)",
            !decodedOff.showTileSearch && !decodedOff.showTileWifi)
        val v13 = "111111100|11111|weekday_month_day|medium|transparent|solid"
        check("statusbar: v1.3 string decodes with search tile on",
            StatusBarCodec.decode(v13).showTileSearch &&
                !StatusBarCodec.decode(v13).clock24h)
        val v14 = StatusBarCodec.encode(StatusBarConfig())
        check("statusbar: v1.4 default round-trips with search on",
            StatusBarCodec.decode(v14).showTileSearch && StatusBarCodec.decode(v14).isDefault())
        // v1.5: the tile segment grew a 7th flag (All Apps). Older
        // strings decode with it ON; the flag itself round-trips.
        check("statusbar: v1.3 string decodes with All Apps tile on",
            StatusBarCodec.decode(v13).showTileAllApps)
        val segs14 = v14.split("|").toMutableList()
        segs14[1] = "111110"
        check("statusbar: v1.4 tile string decodes with All Apps tile on",
            StatusBarCodec.decode(segs14.joinToString("|")).showTileAllApps)
        check("statusbar: All Apps tile flag round-trips",
            !StatusBarCodec.decode(
                StatusBarCodec.encode(StatusBarConfig(showTileAllApps = false))
            ).showTileAllApps)
    }

    // ---------- (25) Move session (v1.4 fix — session level) ----------
    // Regression test for the on-device v1.3 bug: the activity reloads
    // its layout from prefs on every rebuild, orphaning any captured
    // list reference, so swaps never rendered/persisted. These tests
    // replay the activity's FIXED loop exactly: the machine owns a
    // working copy; each successful move is written into the CURRENT
    // layout; a codec round-trip mid-session stands in for rebuild()'s
    // reload; the assertions read the FINAL PERSISTED state (a fresh
    // decode — what the next cold render would show).
    run {
        fun persisted(layout: LayoutData): LayoutData =
            LayoutCodec.decode(LayoutCodec.encode(layout))

        // Write-back exactly as MainActivity.applyMoveOrder does it:
        // resolve the target from the CURRENT layout, replace contents.
        // v1.5: FOLDER write-back resolves the folder by its STABLE ID
        // (v1.4 used the list index) and converts entry keys back into
        // typed entries.
        fun writeBack(layout: LayoutData, container: MoveContainer, folderId: String?, order: List<String>) {
            when (container) {
                MoveContainer.FAVORITES -> {
                    layout.favorites.clear(); layout.favorites.addAll(order)
                }
                MoveContainer.ALL_APPS -> {
                    layout.allAppsOrder.clear(); layout.allAppsOrder.addAll(order)
                }
                MoveContainer.FOLDER -> {
                    val folder = folderId?.let { FolderTree.byId(layout.folders, it) } ?: return
                    folder.entries.clear()
                    folder.entries.addAll(order.map { FolderEntry.fromKey(it) })
                }
                // v1.7: root-folder sessions write back as a root
                // permutation of the flat folder list.
                MoveContainer.ROOT_FOLDERS -> {
                    FolderTree.reorderRoots(layout.folders, order)
                }
            }
        }

        // -- Favorites: grab -> RIGHT -> DOWN -> drop, reload mid-session --
        var live = LayoutData()
        live.favorites.addAll(listOf("a", "b", "c", "d", "e", "f"))
        val favMachine = MoveSessionMachine("b", live.favorites, 3)
        check("movesession: grab finds the item, store untouched until write-back",
            favMachine.isValid && favMachine.index == 1 &&
                live.favorites == listOf("a", "b", "c", "d", "e", "f"))
        check("movesession: machine moves do not leak into the store by themselves",
            favMachine.move(ArrowDir.RIGHT) &&
                live.favorites == listOf("a", "b", "c", "d", "e", "f"))
        writeBack(live, MoveContainer.FAVORITES, null, favMachine.currentOrder())
        // THE regression step: rebuild() reloads the layout from prefs.
        live = persisted(live)
        check("movesession: first swap survives a layout reload",
            live.favorites == listOf("a", "c", "b", "d", "e", "f"), live.favorites.toString())
        check("movesession: moves still land after the reload (down)",
            favMachine.move(ArrowDir.DOWN))
        writeBack(live, MoveContainer.FAVORITES, null, favMachine.currentOrder())
        // Drop = nothing pending; final persisted order is the assertion.
        check("movesession: favorites final persisted order after RIGHT+DOWN+drop",
            persisted(live).favorites == listOf("a", "c", "f", "d", "e", "b"),
            persisted(live).favorites.toString())

        // -- All Apps: grab -> LEFT -> UP -> drop --
        var liveApps = LayoutData()
        liveApps.allAppsOrder.addAll(listOf("a", "b", "c", "d", "e", "f", "g", "h"))
        val appMachine = MoveSessionMachine("f", liveApps.allAppsOrder, 4)
        check("movesession: all-apps grab index", appMachine.isValid && appMachine.index == 5)
        check("movesession: all-apps LEFT swaps",
            appMachine.move(ArrowDir.LEFT))
        writeBack(liveApps, MoveContainer.ALL_APPS, null, appMachine.currentOrder())
        liveApps = persisted(liveApps)
        check("movesession: all-apps UP swaps after reload",
            appMachine.move(ArrowDir.UP))
        writeBack(liveApps, MoveContainer.ALL_APPS, null, appMachine.currentOrder())
        check("movesession: all-apps final persisted order after LEFT+UP+drop",
            persisted(liveApps).allAppsOrder == listOf("f", "b", "c", "d", "a", "e", "g", "h"),
            persisted(liveApps).allAppsOrder.toString())

        // -- Folder: grab -> RIGHT -> drop, folder resolved by ID after reload --
        var liveFolder = LayoutData()
        liveFolder.folders.add(testFolder("f1", "Kids", "x", "y", "z", "w", "v", "u"))
        val folderMachine = MoveSessionMachine("y", liveFolder.folders[0].entryKeys(), 3)
        check("movesession: folder RIGHT swaps",
            folderMachine.move(ArrowDir.RIGHT))
        writeBack(liveFolder, MoveContainer.FOLDER, "f1", folderMachine.currentOrder())
        liveFolder = persisted(liveFolder) // reload replaces the Folder object itself
        check("movesession: folder DOWN still lands after the folder object was replaced",
            folderMachine.move(ArrowDir.DOWN))
        writeBack(liveFolder, MoveContainer.FOLDER, "f1", folderMachine.currentOrder())
        check("movesession: folder final persisted order after RIGHT+DOWN+drop",
            persisted(liveFolder).folders[0].entryKeys() == listOf("x", "z", "u", "w", "v", "y"),
            persisted(liveFolder).folders[0].entryKeys().toString())

        // -- Sub-folder ENTRY moves inside its parent like an app (v1.5) --
        var liveNested = LayoutData()
        liveNested.folders.add(testFolder("f1", "Media", "com.a", "@f:f2", "com.b"))
        liveNested.folders.add(testFolder("f2", "Video", "com.v"))
        val entryMachine = MoveSessionMachine("@f:f2", liveNested.folders[0].entryKeys(), 3)
        check("movesession: sub-folder entry grabs by its entry key",
            entryMachine.isValid && entryMachine.index == 1)
        check("movesession: sub-folder entry swaps like an app",
            entryMachine.move(ArrowDir.RIGHT))
        writeBack(liveNested, MoveContainer.FOLDER, "f1", entryMachine.currentOrder())
        check("movesession: parent order persisted with the entry moved",
            persisted(liveNested).folders[0].entryKeys() == listOf("com.a", "com.b", "@f:f2"))
        check("movesession: the sub-folder itself is untouched by the move",
            persisted(liveNested).folders[1].appPackages == listOf("com.v"))

        // -- Cancel: moves happen, then the snapshot is written back --
        var liveCancel = LayoutData()
        liveCancel.favorites.addAll(listOf("a", "b", "c", "d"))
        val cancelMachine = MoveSessionMachine("a", liveCancel.favorites, 2)
        cancelMachine.move(ArrowDir.RIGHT)
        writeBack(liveCancel, MoveContainer.FAVORITES, null, cancelMachine.currentOrder())
        cancelMachine.move(ArrowDir.DOWN)
        writeBack(liveCancel, MoveContainer.FAVORITES, null, cancelMachine.currentOrder())
        liveCancel = persisted(liveCancel)
        writeBack(liveCancel, MoveContainer.FAVORITES, null, cancelMachine.snapshotOrder())
        check("movesession: cancel restores the exact grab-time order",
            persisted(liveCancel).favorites == listOf("a", "b", "c", "d"),
            persisted(liveCancel).favorites.toString())

        // -- Edge no-op at machine level: returns false, order untouched --
        val edgeMachine = MoveSessionMachine("a", listOf("a", "b", "c"), 3)
        check("movesession: edge move is a no-op and reports false",
            !edgeMachine.move(ArrowDir.LEFT) &&
                edgeMachine.currentOrder() == listOf("a", "b", "c") &&
                edgeMachine.index == 0)
        check("movesession: unknown package cannot start a session",
            !MoveSessionMachine("nope", listOf("a", "b"), 3).isValid)
    }

    // ---------- (26) Folder tree: nesting, legality, codec (v1.5) ----------
    run {
        // f1 Media (root) ⊃ f2 Video ⊃ f3 Clips; f4 Games (root).
        fun tree(): MutableList<Folder> = mutableListOf(
            testFolder("f1", "Media", "com.music", "@f:f2"),
            testFolder("f2", "Video", "com.player", "@f:f3"),
            testFolder("f3", "Clips", "com.deep"),
            testFolder("f4", "Games", "com.game")
        )
        val t = tree()
        check("tree: depths are 1 for roots, +1 per level",
            FolderTree.depth(t, "f1") == 1 && FolderTree.depth(t, "f2") == 2 &&
                FolderTree.depth(t, "f3") == 3 && FolderTree.depth(t, "gone") == 0)
        check("tree: subtree heights",
            FolderTree.subtreeHeight(t, "f1") == 3 && FolderTree.subtreeHeight(t, "f2") == 2 &&
                FolderTree.subtreeHeight(t, "f3") == 1)
        check("tree: descendants and ancestors",
            FolderTree.descendants(t, "f1") == setOf("f2", "f3") &&
                FolderTree.ancestors(t, "f3").map { it.id } == listOf("f1", "f2"))
        check("tree: breadcrumb is root-to-folder",
            FolderTree.breadcrumb(t, "f3").map { it.name } == listOf("Media", "Video", "Clips"))
        check("tree: roots are the parentless folders",
            FolderTree.rootFolders(t).map { it.id } == listOf("f1", "f4"))
        check("tree: fresh id skips taken ids",
            FolderTree.freshId(t) == "f5" &&
                FolderTree.freshId(listOf(testFolder("f2", "X"))) == "f1")
        // Legality matrix (canNest arguments are child, then parent).
        check("tree: a folder cannot contain itself",
            !FolderTree.canNest(t, "f3", "f3"))
        check("tree: cycle — an ancestor cannot move under its descendant",
            !FolderTree.canNest(t, "f1", "f2"))
        check("tree: max depth — a height-3 subtree fits nowhere deeper",
            !FolderTree.canNest(t, "f1", "f4") && !FolderTree.canNest(t, "f2", "f3"))
        check("tree: re-parenting a leaf up a level is legal",
            FolderTree.canNest(t, "f3", "f1"))
        check("tree: legal parents exclude self, descendants, too-deep targets",
            FolderTree.legalParents(t, "f1").isEmpty() &&
                FolderTree.legalParents(t, "f3").map { it.id } == listOf("f1", "f2", "f4"))
        // Move semantics: the child leaves its old parent.
        val moved = tree()
        FolderTree.moveFolder(moved, "f3", "f4")
        check("tree: move re-parents (old parent loses the entry)",
            FolderTree.parentOf(moved, "f3")?.id == "f4" &&
                FolderTree.byId(moved, "f2")!!.subFolderIds.isEmpty())
        FolderTree.moveFolder(moved, "f3", null)
        check("tree: move to null makes the folder a root",
            FolderTree.parentOf(moved, "f3") == null &&
                FolderTree.byId(moved, "f4")!!.subFolderIds.isEmpty())
        // Delete never cascades: sub-folders re-home to the root row.
        val del = tree()
        FolderTree.deleteFolder(del, "f2")
        check("tree: deleting a nested folder rehomes its sub-folders",
            FolderTree.byId(del, "f2") == null &&
                FolderTree.parentOf(del, "f3") == null &&
                FolderTree.byId(del, "f1")!!.subFolderIds.isEmpty() &&
                FolderTree.byId(del, "f3")!!.appPackages == listOf("com.deep"))
        // Recursive membership + home-row dedupe at depth 3.
        val deep = tree()
        check("tree: recursive membership spans all depths",
            FolderTree.allFolderPackages(deep) ==
                setOf("com.music", "com.player", "com.deep", "com.game"))
        check("tree: dedupe hides an app nested at depth 3 from All Apps row",
            FolderLogic.allAppsVisible(
                listOf("com.deep", "com.free"), emptySet(), deep
            ) == listOf("com.free"))
        // Prune: dangling refs, self refs, duplicate parents.
        val messy = mutableListOf(
            testFolder("f1", "A", "com.a", "@f:gone", "@f:f1"),
            testFolder("f2", "B", "@f:f3"),
            testFolder("f3", "C", "@f:f2", "com.c")
        )
        FolderTree.prune(messy)
        check("tree: prune drops dangling and self references",
            FolderTree.byId(messy, "f1")!!.entryKeys() == listOf("com.a"))
        check("tree: prune leaves a folder with exactly one parent",
            (FolderTree.parentOf(messy, "f3")?.id == "f2") !=
                (FolderTree.parentOf(messy, "f2")?.id == "f3"))
        // Codec: nested structure round-trips, and re-encode is stable.
        val nestedLayout = LayoutData()
        nestedLayout.folders.addAll(tree())
        val nestedBack = LayoutCodec.decode(LayoutCodec.encode(nestedLayout))
        check("tree: nested folders round-trip through the codec",
            nestedBack.folders.map { it.id } == listOf("f1", "f2", "f3", "f4") &&
                nestedBack.folders.map { it.entryKeys() } ==
                nestedLayout.folders.map { it.entryKeys() } &&
                LayoutCodec.encode(nestedBack) == LayoutCodec.encode(nestedLayout))
    }

    // ---------- (27) Settings gate (v1.5) ----------
    run {
        val REQ = SettingsGate.Decision.REQUIRE_PATTERN
        val ALW = SettingsGate.Decision.ALLOW
        check("settingsgate: lock on + pattern set -> prompt",
            SettingsGate.decide(true, false, false, true, false) == REQ)
        check("settingsgate: already unlocked this entry -> allow",
            SettingsGate.decide(true, false, false, true, true) == ALW)
        check("settingsgate: no pattern -> fail open even with lock on",
            SettingsGate.decide(true, false, false, false, false) == ALW)
        check("settingsgate: guest rule alone requires the pattern",
            SettingsGate.decide(false, true, true, true, false) == REQ)
        check("settingsgate: neither lock -> allow",
            SettingsGate.decide(false, false, false, true, false) == ALW)
        check("settingsgate: global lock wins when guest lock is off",
            SettingsGate.decide(true, true, false, true, false) == REQ)
        check("settingsgate: guest active but guest lock off and global off -> allow",
            SettingsGate.decide(false, true, false, true, false) == ALW)
    }

    // ---------- (28) All Apps tab list (v1.5) ----------
    run {
        val tabFolders = listOf(
            testFolder("f1", "Media", "app.zed", "@f:f2"),
            testFolder("f2", "Video", "app.deep"),
            testFolder("f3", "Games", "app.game")
        )
        val catalog = listOf(
            AppSearch.Item("app.zed", "Zed"),
            AppSearch.Item("app.deep", "Deep"),
            AppSearch.Item("app.game", "Game"),
            AppSearch.Item("app.free", "Free"),
            AppSearch.Item("app.hidden", "Aardvark")
        )
        val items = AllAppsLogic.build(catalog, setOf("app.hidden"), tabFolders)
        check("allapps: hidden excluded, everything else listed",
            items.map { it.pkg } == listOf("app.deep", "app.free", "app.game", "app.zed"),
            items.map { it.pkg }.toString())
        check("allapps: alphabetical by label",
            items.map { it.label } == listOf("Deep", "Free", "Game", "Zed"))
        check("allapps: foldered apps badged with the containing folder",
            items.first { it.pkg == "app.zed" }.let { it.inFolder && it.folderName == "Media" } &&
                items.first { it.pkg == "app.deep" }.let { it.inFolder && it.folderName == "Video" })
        check("allapps: unfoldered apps carry no badge",
            items.filter { !it.inFolder }.map { it.pkg } == listOf("app.free"))
        check("allapps: label ties break by package",
            AllAppsLogic.build(
                listOf(AppSearch.Item("b.pkg", "Same"), AppSearch.Item("a.pkg", "Same")),
                emptySet(), emptyList()
            ).map { it.pkg } == listOf("a.pkg", "b.pkg"))
    }

    // ------------------------------------------------ (29) design tokens (v1.6)
    // The redesign's point is one source of truth; these checks pin
    // the token invariants (not pixels): an increasing spacing
    // scale, a bottom-weighted scrim inside a sane band, positive
    // radii, and the quiet 8%-white resting tile.
    run {
        val d = com.mizan.launcher.core.Design
        check("design: spacing scale increases S<M<L<XL",
            d.SPACE_S < d.SPACE_M && d.SPACE_M < d.SPACE_L && d.SPACE_L < d.SPACE_XL)
        check("design: content margin is the TV margin (48dp)",
            d.CONTENT_MARGIN_DP == 48)
        check("design: scrim is bottom-weighted (bottom alpha > top)",
            d.SCRIM_BOTTOM_ALPHA > d.SCRIM_TOP_ALPHA)
        check("design: scrim alphas stay in the calm band (10%..45%)",
            d.SCRIM_TOP_ALPHA in 0x1A..0x73 && d.SCRIM_BOTTOM_ALPHA in 0x1A..0x73)
        check("design: scrim colors carry exactly the token alphas",
            d.scrimColors().size == 2 &&
                (d.scrimColors()[0] ushr 24) == d.SCRIM_TOP_ALPHA &&
                (d.scrimColors()[1] ushr 24) == d.SCRIM_BOTTOM_ALPHA)
        check("design: radii positive, panel rounder than tile, tile rounder than chip",
            d.RADIUS_PANEL > d.RADIUS_TILE && d.RADIUS_TILE > d.RADIUS_CHIP && d.RADIUS_CHIP > 0)
        check("design: resting tile fill is 8% white, panel fill is 25% black",
            (d.TILE_FILL ushr 24) == 0x14 && (d.TILE_FILL and 0xFFFFFF) == 0xFFFFFF &&
                (d.PANEL_FILL ushr 24) == 0x40 && (d.PANEL_FILL and 0xFFFFFF) == 0)
        check("design: focus scale is a slight lift (1.0..1.15)",
            d.FOCUS_SCALE > 1.0f && d.FOCUS_SCALE <= 1.15f)
    }

    // ------------------------------------------------ (30) app catalogue (v1.6)
    // The lock manager's coverage: merging the two intent-category
    // queries must dedupe by package (leanback wins), include system
    // apps, exclude Mizan itself, and sort by label.
    run {
        val rec = com.mizan.launcher.core.AppCatalogue.Record( "", "", false, false)
        fun r(pkg: String, label: String, system: Boolean = false, leanback: Boolean = false) =
            rec.copy(pkg = pkg, label = label, isSystem = system, leanback = leanback)
        val merged = com.mizan.launcher.core.AppCatalogue.merge(
            listOf(
                r("com.tv.settings", "TV Settings (phone label)", system = true),
                r("com.tv.settings", "TV Settings", system = true, leanback = true),
                r("com.example.hidden", "Hidden One"),
                r("com.example.foldered", "Foldered One", leanback = true),
                r("com.mizan.launcher", "Mizan Launcher", leanback = true)
            ),
            ownPkg = "com.mizan.launcher"
        )
        check("catalogue: dedupes across categories, leanback label wins",
            merged.count { it.pkg == "com.tv.settings" } == 1 &&
                merged.first { it.pkg == "com.tv.settings" }.label == "TV Settings")
        check("catalogue: system apps are included, flagged",
            merged.any { it.pkg == "com.tv.settings" && it.isSystem })
        check("catalogue: Mizan itself is excluded",
            merged.none { it.pkg == "com.mizan.launcher" })
        check("catalogue: hidden and foldered apps are in the catalogue",
            merged.map { it.pkg }.containsAll(listOf("com.example.hidden", "com.example.foldered")))
        check("catalogue: sorted by label (case-insensitive)",
            merged.map { it.label } == merged.map { it.label }.sortedBy { it.lowercase() })
        check("catalogue: label ties break by package",
            com.mizan.launcher.core.AppCatalogue.merge(
                listOf(r("b.pkg", "Same"), r("a.pkg", "Same")), "self"
            ).map { it.pkg } == listOf("a.pkg", "b.pkg"))
    }

    // ------------------------------------------------ (31) Reddit chain + RSS + status (v1.6)
    run {
        val chain = com.mizan.launcher.core.RedditEndpoints.chain("wallpaper")
        check("redditchain: four endpoints, JSON first, RSS last",
            chain.size == 4 &&
                chain.take(3).all { it.kind == com.mizan.launcher.core.RedditEndpoints.Kind.JSON } &&
                chain.last().kind == com.mizan.launcher.core.RedditEndpoints.Kind.RSS)
        check("redditchain: endpoints are www, api, old, then www .rss",
            chain.map { it.id } == listOf("www-json", "api-json", "old-json", "www-rss") &&
                chain[0].url.contains("www.reddit.com/r/wallpaper/top.json") &&
                chain[3].url.contains("www.reddit.com/r/wallpaper/top/.rss"))
        check("redditchain: subreddit names are cleaned into the URLs",
            com.mizan.launcher.core.RedditEndpoints.chain("r/EarthPorn").first().url
                .contains("/r/EarthPorn/"))
        check("redditchain: usable means at least one candidate",
            !com.mizan.launcher.core.RedditEndpoints.isUsable(0) &&
                com.mizan.launcher.core.RedditEndpoints.isUsable(1))

        // A trimmed real-shape Atom entry (2026-10-21 shape): escaped
        // HTML content with a [link] anchor to the full-size file, a
        // media:thumbnail preview, and the resolution in the title.
        val feed = """<?xml version="1.0" encoding="UTF-8"?>
<feed xmlns="http://www.w3.org/2005/Atom" xmlns:media="http://search.yahoo.com/mrss/">
<entry><author><name>/u/tester</name></author>
<title>Norway Sea [3840x2160]</title>
<link rel="alternate" href="https://www.reddit.com/r/wallpaper/comments/abc123/norway_sea/"/>
<content type="html">&lt;a href=&quot;https://www.reddit.com/r/wallpaper/comments/abc123/norway_sea/&quot;&gt;&lt;img src=&quot;https://preview.redd.it/abc123.png?width=640&amp;amp;crop=smart&quot;/&gt;&lt;/a&gt; &lt;span&gt;&lt;a href=&quot;https://i.redd.it/abc123.png&quot;&gt;[link]&lt;/a&gt;&lt;/span&gt;</content>
<media:thumbnail url="https://preview.redd.it/abc123.png?width=640&amp;crop=smart" />
</entry>
<entry><author><name>/u/tester2</name></author>
<title>Vertical Phone Shot [1440x2560]</title>
<link rel="alternate" href="https://www.reddit.com/r/wallpaper/comments/def456/vertical/"/>
<content type="html">&lt;span&gt;&lt;a href=&quot;https://i.redd.it/def456.jpg&quot;&gt;[link]&lt;/a&gt;&lt;/span&gt;</content>
<media:thumbnail url="https://preview.redd.it/def456.jpg?width=640" />
</entry>
<entry><author><name>/u/tester3</name></author>
<title>Thumb Only Peak</title>
<link rel="alternate" href="https://www.reddit.com/r/wallpaper/comments/ghi789/thumb/"/>
<content type="html">&lt;span&gt;no direct file here&lt;/span&gt;</content>
<media:thumbnail url="https://preview.redd.it/ghi789.jpg?width=640&amp;crop=smart" />
</entry>
</feed>"""
        val rss = com.mizan.launcher.core.RedditRss.parseFeed(feed, "wallpaper")
        check("redditrss: landscape full-size entry survives",
            rss.any { it.url == "https://i.redd.it/abc123.png" })
        check("redditrss: dimensions come from the title tag",
            rss.first { it.url.contains("abc123") }.let { it.width == 3840 && it.height == 2160 })
        check("redditrss: known-portrait entry is rejected (landscape rule)",
            rss.none { it.url.contains("def456") })
        check("redditrss: thumbnail is the fallback when no direct file exists",
            rss.any { it.url.startsWith("https://preview.redd.it/ghi789.jpg") })
        check("redditrss: thumbnail URL is entity-unescaped",
            rss.none { it.url.contains("&amp;") })
        check("redditrss: permalink is captured as the post URL",
            rss.first { it.url.contains("abc123") }.postUrl.contains("/comments/abc123/"))
        check("redditrss: subreddit is stamped from the caller",
            rss.all { it.subreddit == "wallpaper" })
        check("redditrss: malformed input fails open to empty",
            com.mizan.launcher.core.RedditRss.parseFeed("not xml at all", "wallpaper").isEmpty() &&
                com.mizan.launcher.core.RedditRss.parseFeed(null, "wallpaper").isEmpty())
        check("redditrss: nsfw-tagged titles are rejected unless allowed",
            com.mizan.launcher.core.RedditRss.parseFeed(
                feed.replace("Thumb Only Peak", "Thumb Only Peak [NSFW]"), "wallpaper"
            ).none { it.url.contains("ghi789") } &&
                com.mizan.launcher.core.RedditRss.parseFeed(
                    feed.replace("Thumb Only Peak", "Thumb Only Peak [NSFW]"), "wallpaper",
                    allowNsfw = true
                ).any { it.url.contains("ghi789") })

        val att = com.mizan.launcher.core.RedditStatus.Attempt("www-json", "wallpaper", "HTTP 403", 0, 0L)
        check("redditstatus: attempt line names endpoint, code, count",
            com.mizan.launcher.core.RedditStatus.attemptLine(att) ==
                "r/wallpaper · www-json · HTTP 403 · 0 photos")
        check("redditstatus: success summary shows the photo count",
            com.mizan.launcher.core.RedditStatus.runSummary(listOf(att), 18, 0L)
                .startsWith("✓ 18 photos"))
        check("redditstatus: failure summary names the last result",
            com.mizan.launcher.core.RedditStatus.runSummary(listOf(att), 0, 0L)
                .startsWith("✗ r/wallpaper HTTP 403 · 0 photos"))
    }

    // ---------- (32) Home style: codec + the v2.2 iPad migration (v1.7, reworked v2.2) ----------
    run {
        // Codec: only the exact "tracker"/"iptv" keys select those
        // styles — missing, empty or garbled values (and old
        // backups) resolve to TV.
        check("homestyle: missing/garbage decodes to tv",
            HomeStyle.normalize(null) == "tv" && HomeStyle.normalize("") == "tv" &&
                HomeStyle.normalize("TV") == "tv" && HomeStyle.normalize("bogus") == "tv")
        // v2.2: the iPad style is REMOVED (Haythem: "it is bad").
        // A profile saved as iPad migrates silently to TV through
        // this same codec — Prefs reads/writes pass through
        // normalize, so no other migration is needed and no profile
        // data is reinterpreted (iPad never owned layout data).
        check("homestyle: saved ipad migrates to tv (v2.2 removal)",
            HomeStyle.normalize("ipad") == "tv" && HomeStyle.normalize("IPAD") == "tv")
        check("homestyle: tracker and iptv survive the codec",
            HomeStyle.normalize("tracker") == "tracker" && HomeStyle.isTracker("tracker") &&
                HomeStyle.normalize("iptv") == "iptv" && HomeStyle.isIptv("iptv") &&
                !HomeStyle.isIptv("tv") && !HomeStyle.isTracker("iptv"))
        // v2.3 in-place update: the Watch style joins the picker,
        // so the value list legitimately grows a fourth entry.
        check("homestyle: value list is tv, tracker, iptv, watch (the Settings picker order)",
            HomeStyle.VALUES == listOf("tv", "tracker", "iptv", "watch"))
        check("homestyle: watch survives the codec (v2.3)",
            HomeStyle.normalize("watch") == "watch" && HomeStyle.isWatch("watch") &&
                !HomeStyle.isWatch("tracker") && !HomeStyle.isTracker("watch") &&
                !HomeStyle.isIptv("watch"))

        // Root reorder (core/FolderTree write-back machinery —
        // retained: the folder tree is shared by every style even
        // though the iPad grid's move entry point is gone).
        // flat [A root, B root, C nested in A, D root], roots -> D,B,A.
        val reorderFolders = mutableListOf(
            testFolder("fa", "A", "@f:fc"),
            testFolder("fb", "B"),
            testFolder("fc", "C", "com.deep"),
            testFolder("fd", "D")
        )
        FolderTree.reorderRoots(reorderFolders, listOf("fd", "fb", "fa"))
        check("homestyle: root reorder permutes only the root slots",
            reorderFolders.map { it.id } == listOf("fd", "fb", "fc", "fa"))
        check("homestyle: root reorder keeps nesting intact",
            FolderTree.parentOf(reorderFolders, "fc")?.id == "fa" &&
                FolderTree.rootFolders(reorderFolders).map { it.id } ==
                listOf("fd", "fb", "fa"))
        val noopFolders = mutableListOf(
            testFolder("fa", "A", "@f:fc"),
            testFolder("fb", "B"),
            testFolder("fc", "C")
        )
        FolderTree.reorderRoots(noopFolders, listOf("fa", "fb"))
        check("homestyle: root reorder with the current order is a no-op",
            noopFolders.map { it.id } == listOf("fa", "fb", "fc"))
    }

    // ---------- (33) Tracker view: codec v2, parsing, hero, dates, merge, movies, marked-set, search (v1.8) ----------
    run {
        // Codec: "tracker" is its own style (v2.2: "iptv" is too;
        // the removed "ipad" is not — section 32 checks its migration).
        check("tracker: codec — tracker survives, garbage still decodes to tv",
            HomeStyle.normalize("tracker") == "tracker" && HomeStyle.isTracker("tracker") &&
                !HomeStyle.isTracker("tv") && !HomeStyle.isTracker("iptv") &&
                HomeStyle.normalize("Tracker") == "tv" && HomeStyle.normalize("bogus") == "tv")

        // Snapshot parsing (JsonMini): full item, defaults, garbage.
        val upJson = """[{"title":"Silo","sub":"S2 E3 · Solo","date":"Nov 14 · in 3d","sort":"2026-11-14","poster":"/p1.jpg","backdrop":"/b1.jpg","overview":"In a silo…","season":2,"episode":3,"tmdbId":125988,"type":"tv"},{"title":"Bad","tmdbId":0}]"""
        val upParsed = TrackerLogic.parseUpNext(upJson)
        check("tracker: upnext parse — fields land, id-less item dropped",
            upParsed.size == 1 && upParsed[0].title == "Silo" && upParsed[0].season == 2 &&
                upParsed[0].episode == 3 && upParsed[0].dateIso == "2026-11-14" &&
                upParsed[0].backdrop == "/b1.jpg" && upParsed[0].tmdbId == 125988L)
        check("tracker: parse is fail-open — garbage and null give empty lists",
            TrackerLogic.parseUpNext("not json").isEmpty() &&
                TrackerLogic.parseUpNext(null).isEmpty() &&
                TrackerLogic.parseContinue("{}").isEmpty() &&
                TrackerLogic.parseMovies("[{]").isEmpty() &&
                TrackerLogic.parseTrackedShows("[]").isEmpty())
        val contJson = """[{"title":"Silo","sub":"Next S1 E4 · 3 of 20 watched","watched":3,"total":20,"poster":"/p1.jpg","backdrop":"/b1.jpg","overview":"…","tmdbId":125988,"type":"tv"}]"""
        val contParsed = TrackerLogic.parseContinue(contJson)
        check("tracker: continue parse — progress counts land",
            contParsed.size == 1 && contParsed[0].watched == 3 && contParsed[0].total == 20)
        val movJson = """[{"tmdbId":693134,"title":"Dune: Part Two","releaseDate":"2024-03-01","planned":true,"poster":"/dune.jpg","watched":false},{"tmdbId":11,"title":"Old","releaseDate":null,"planned":false}]"""
        val movParsed = TrackerLogic.parseMovies(movJson)
        check("tracker: movies parse — planned/poster/watched land, null date reads blank",
            movParsed.size == 2 && movParsed[0].planned && movParsed[0].poster == "/dune.jpg" &&
                !movParsed[0].watched && movParsed[1].releaseDate == "" && !movParsed[1].watched)
        val trkJson = """[{"tmdbId":125988,"name":"Silo","muted":false,"planned":false,"watched":["1x1","1x2","2x1"]},{"tmdbId":999,"name":"Parked","muted":false,"planned":true,"watched":[]}]"""
        val trkParsed = TrackerLogic.parseTrackedShows(trkJson)
        check("tracker: tracked parse — watched[] becomes a count",
            trkParsed.size == 2 && trkParsed[0].watchedCount == 3 && trkParsed[1].planned)

        // Hero selection: priority groups + cycle wrap.
        fun up(id: Long, bd: String, s: Int = 1, e: Int = 1) = TrackerLogic.UpNextItem(
            id, "Show$id", "S${s} E$e", "2026-11-01", "/p$id.jpg", bd, "ov", s, e, "tv"
        )
        fun cont(id: Long, bd: String) = TrackerLogic.ContinueItem(
            id, "Cont$id", "Next S1 E1", 1, 10, "/p$id.jpg", bd, "ov", "tv"
        )
        val heroCands = TrackerLogic.heroCandidates(
            listOf(up(1, ""), up(2, "/b2.jpg")), listOf(cont(3, "/b3.jpg"), cont(4, ""))
        )
        check("tracker: hero order — upnext+backdrop, continue+backdrop, rest",
            heroCands.map { it.tmdbId } == listOf(2L, 3L, 1L, 4L))
        check("tracker: hero featured pick is the first upnext with a backdrop",
            TrackerLogic.selectHero(heroCands, 0)?.tmdbId == 2L)
        check("tracker: hero cycle wraps forward and backward",
            TrackerLogic.selectHero(heroCands, 4)?.tmdbId == 2L &&
                TrackerLogic.selectHero(heroCands, -1)?.tmdbId == 4L &&
                TrackerLogic.selectHero(heroCands, 6)?.tmdbId == 1L)
        check("tracker: hero falls back to continue when upnext has no backdrop",
            TrackerLogic.selectHero(
                TrackerLogic.heroCandidates(listOf(up(1, "")), listOf(cont(3, "/b3.jpg"))), 0
            )?.tmdbId == 3L)
        check("tracker: hero falls back to first upnext when nothing has a backdrop",
            TrackerLogic.selectHero(
                TrackerLogic.heroCandidates(listOf(up(1, ""), up(2, "")), emptyList()), 0
            )?.tmdbId == 1L)
        check("tracker: hero of an empty cycle is null",
            TrackerLogic.selectHero(emptyList(), 0) == null)
        check("tracker: continue hero carries no episode coordinates",
            heroCands.first { it.tmdbId == 3L }.fromContinue &&
                heroCands.first { it.tmdbId == 3L }.season == 0)

        // Countdown labels against a fixed today (2026-10-21).
        val today = "2026-10-21"
        check("tracker: countdown — today, tomorrow, in N d",
            TrackerLogic.countdownLabel("2026-10-21", today) == "Today" &&
                TrackerLogic.countdownLabel("2026-10-22", today) == "Tomorrow" &&
                TrackerLogic.countdownLabel("2026-10-26", today) == "in 5 d" &&
                TrackerLogic.countdownLabel("2026-11-20", today) == "in 30 d")
        check("tracker: countdown — past is Aired, far future is the date itself",
            TrackerLogic.countdownLabel("2026-10-20", today) == "Aired" &&
                TrackerLogic.countdownLabel("2026-11-21", today) == "Nov 21" &&
                TrackerLogic.countdownLabel("2027-03-04", today) == "Mar 4")
        check("tracker: countdown — month/year boundaries use real day math",
            TrackerLogic.countdownLabel("2026-11-01", today) == "in 11 d" &&
                TrackerLogic.countdownLabel("2027-01-01", "2026-12-31") == "Tomorrow")
        check("tracker: countdown — empty or malformed dates give no label",
            TrackerLogic.countdownLabel("", today) == "" &&
                TrackerLogic.countdownLabel("Nov 14", today) == "" &&
                TrackerLogic.countdownLabel("2026-13-40", today) == "")

        // Airing-today + pill text.
        val pillUp = listOf(
            up(1, "").copy(dateIso = "2026-10-21"), up(2, "").copy(dateIso = "2026-10-21"),
            up(3, "").copy(dateIso = "2026-10-25")
        )
        check("tracker: airing-today counts only today's items",
            TrackerLogic.airingTodayCount(pillUp, today) == 2 &&
                TrackerLogic.airingTodayCount(pillUp, "2026-10-22") == 0)
        check("tracker: pill text composes the numbers the data supports",
            TrackerLogic.pillText(2, 3) == "2 airing today · 3 unwatched" &&
                TrackerLogic.pillText(1, 1) == "1 airing today · 1 unwatched" &&
                TrackerLogic.pillText(0, 5) == "5 unwatched" &&
                TrackerLogic.pillText(0, 0) == "")

        // Continue merge: tracker first, title-tie dedupe, system dedupe.
        val merged = TrackerLogic.mergeContinue(
            listOf(cont(1, "").copy(title = "Silo"), cont(2, "").copy(title = "The Bear")),
            listOf("Silo", "Dark", "the bear!", "Dark", "  ")
        )
        check("tracker: merge — tracker cards first, in snapshot order",
            merged.take(2).map { it.title } == listOf("Silo", "The Bear") &&
                merged.take(2).all { it.tracker != null && it.systemIndex == -1 })
        check("tracker: merge — system titles matching a tracker title drop out",
            merged.none { it.tracker == null && it.title == "Silo" } &&
                merged.none { it.tracker == null && it.title == "the bear!" })
        check("tracker: merge — unique system titles survive once, with their index",
            merged.count { it.tracker == null } == 1 &&
                merged.last().title == "Dark" && merged.last().systemIndex == 1)
        check("tracker: title normalization ignores case, spaces, punctuation",
            TrackerLogic.normalizeTitle("The Bear!") == "thebear" &&
                TrackerLogic.normalizeTitle("S.W.A.T.") == "swat")

        // Movie night ordering.
        fun mov(id: Long, date: String, planned: Boolean, watched: Boolean = false) =
            TrackerLogic.MovieItem(id, "M$id", date, planned, "/m$id.jpg", watched)
        val ordered = TrackerLogic.orderMovies(
            listOf(
                mov(1, "2027-05-01", planned = true), mov(2, "2026-12-01", planned = true),
                mov(3, "2026-11-01", planned = false), mov(4, "2026-10-01", planned = true, watched = true)
            ),
            emptyList()
        )
        check("tracker: movies — planned first by release date, then the rest, watched excluded",
            ordered.map { it.tmdbId } == listOf(2L, 1L, 3L))
        check("tracker: movies — unknown release dates sort last within their group",
            TrackerLogic.orderMovies(
                listOf(mov(1, "", planned = true), mov(2, "2027-01-01", planned = true)),
                emptyList()
            ).map { it.tmdbId } == listOf(2L, 1L))
        check("tracker: movies — all-watched falls back to the full sorted list",
            TrackerLogic.orderMovies(
                listOf(mov(1, "2027-01-01", planned = false, watched = true),
                    mov(2, "2026-01-01", planned = true, watched = true)),
                emptyList()
            ).map { it.tmdbId } == listOf(2L, 1L))
        check("tracker: movies — locally-marked movies stay out even in the fallback",
            TrackerLogic.orderMovies(
                listOf(mov(1, "2027-01-01", planned = true, watched = true),
                    mov(2, "2026-01-01", planned = false, watched = true)),
                listOf(TrackerLogic.MarkedEntry(1L, "movie", 0, 0, 1L))
            ).map { it.tmdbId } == listOf(2L))

        // Marked-set: codec, dedupe, filters, convergence prune.
        val m1 = TrackerLogic.MarkedEntry(125988L, "tv", 2, 3, 1_000L)
        val m2 = TrackerLogic.MarkedEntry(693134L, "movie", 0, 0, 2_000L)
        val roundTrip = TrackerLogic.decodeMarked(TrackerLogic.encodeMarked(listOf(m1, m2)))
        check("tracker: marked codec round-trips and skips garbage lines",
            roundTrip == listOf(m1, m2) &&
                TrackerLogic.decodeMarked("junk\n1|tv|x|2|3\n").isEmpty() &&
                TrackerLogic.decodeMarked(null).isEmpty())
        check("tracker: withMarked replaces the same item instead of duplicating it",
            TrackerLogic.withMarked(listOf(m1), m1.copy(markedAt = 5_000L)).size == 1 &&
                TrackerLogic.withMarked(listOf(m1), m2).size == 2)
        val filterUp = listOf(up(125988, "", 2, 3), up(125988, "", 2, 4), up(7, "", 1, 1))
        check("tracker: filterUpNext hides only the marked episode",
            TrackerLogic.filterUpNext(filterUp, listOf(m1)).map { it.episode } == listOf(4, 1))
        check("tracker: filterContinue hides a show with any marked episode",
            TrackerLogic.filterContinue(
                listOf(cont(125988, ""), cont(7, "")), listOf(m1)
            ).map { it.tmdbId } == listOf(7L))
        check("tracker: prune keeps entries the snapshot is not fresher than",
            TrackerLogic.pruneMarked(listOf(m1), emptyList(), emptyList(), 500L) == listOf(m1) &&
                TrackerLogic.pruneMarked(listOf(m1), emptyList(), emptyList(), 0L) == listOf(m1))
        check("tracker: prune drops a tv entry once a fresher snapshot lost the episode",
            TrackerLogic.pruneMarked(
                listOf(m1), listOf(up(125988, "", 2, 4)), emptyList(), 2_000L
            ).isEmpty())
        check("tracker: prune keeps a tv entry while the episode is still in the snapshot",
            TrackerLogic.pruneMarked(
                listOf(m1), listOf(up(125988, "", 2, 3)), emptyList(), 2_000L
            ) == listOf(m1))
        check("tracker: prune drops a movie entry once the snapshot flags it watched",
            TrackerLogic.pruneMarked(
                listOf(m2), emptyList(),
                listOf(mov(693134, "2024-03-01", planned = false, watched = true)), 3_000L
            ).isEmpty() &&
                TrackerLogic.pruneMarked(
                    listOf(m2), emptyList(),
                    listOf(mov(693134, "2024-03-01", planned = false, watched = false)), 3_000L
                ) == listOf(m2))

        // Tracker search: prefix-first, details, empty query.
        val searchShows = listOf(
            TrackerLogic.TrackedShow(1L, "Silo", 3, false),
            TrackerLogic.TrackedShow(2L, "Basil", 0, false)
        )
        val searchMovies = listOf(
            TrackerLogic.MovieItem(3L, "Silence", "2026-01-01", false, "", false)
        )
        val hits = TrackerLogic.searchTracked(searchShows, searchMovies, "sil")
        check("tracker: search — prefix hits (shows then movies) before substring hits",
            hits.map { it.title } == listOf("Silo", "Silence", "Basil"))
        check("tracker: search detail counts watched episodes",
            hits.first { it.title == "Silo" }.detail == "3 episodes watched" &&
                hits.first { it.title == "Silence" }.detail == "Movie · tracked")
        val hitsDune = TrackerLogic.searchTracked(trkParsed, movParsed, "dune")
        check("tracker: search finds movies with the tracked detail",
            hitsDune.size == 1 && hitsDune[0].detail == "Movie · tracked" &&
                hitsDune[0].type == "movie")
        check("tracker: search is case-insensitive substring, prefix first",
            TrackerLogic.searchTracked(trkParsed, movParsed, "ILO").map { it.title } ==
                listOf("Silo") &&
                TrackerLogic.searchTracked(trkParsed, movParsed, "  ").isEmpty() &&
                TrackerLogic.searchTracked(trkParsed, movParsed, "zzz").isEmpty())
    }

    // ---- 34. v1.9: profile names, unlock grace, tracker status ----
    run {
        // ProfileNames: normalize (trim, collapse, cap) + defaults.
        check("names: normalize trims and collapses whitespace runs",
            com.mizan.launcher.core.ProfileNames.normalize("  Haythem   Gaja ") == "Haythem Gaja" &&
                com.mizan.launcher.core.ProfileNames.normalize("a\t b\n c") == "a b c")
        check("names: blank or null normalizes to no-custom-name",
            com.mizan.launcher.core.ProfileNames.normalize("   ").isEmpty() &&
                com.mizan.launcher.core.ProfileNames.normalize(null).isEmpty() &&
                com.mizan.launcher.core.ProfileNames.normalize("").isEmpty())
        check("names: cap is 16 chars and never leaves a trailing space",
            com.mizan.launcher.core.ProfileNames.normalize("ABCDEFGHIJKLMNOPQRSTUV") ==
                "ABCDEFGHIJKLMNOP" &&
                com.mizan.launcher.core.ProfileNames.normalize("123456789012345 78") ==
                "123456789012345")
        check("names: default labels — Guest for the flagged profile, Profile N otherwise",
            com.mizan.launcher.core.ProfileNames.defaultLabel(1, false) == "Profile 1" &&
                com.mizan.launcher.core.ProfileNames.defaultLabel(2, true) == "Guest")
        check("names: display — custom name wins, blank falls back to the default",
            com.mizan.launcher.core.ProfileNames.display("Kids", 2, true) == "Kids" &&
                com.mizan.launcher.core.ProfileNames.display("", 2, true) == "Guest" &&
                com.mizan.launcher.core.ProfileNames.display(null, 1, false) == "Profile 1" &&
                com.mizan.launcher.core.ProfileNames.display("  ", 1, false) == "Profile 1")

        // UnlockGrace: windows, edges, disabled, switch semantics are
        // call-site rules; the pure window math is pinned here.
        val g = com.mizan.launcher.core.UnlockGrace
        check("grace: windows and labels line up; garbage coerces to Every time",
            g.WINDOWS_MIN == listOf(0, 5, 15, 30) &&
                g.WINDOW_LABELS.size == g.WINDOWS_MIN.size &&
                g.labelFor(5) == "After 5 minutes" &&
                g.labelFor(0) == "Every time" &&
                g.normalize(7) == 0 && g.normalize(30) == 30 && g.normalize(-5) == 0)
        check("grace: window 0 (Every time) never grants, even with a fresh stamp",
            !g.withinGrace(0, 1_000_000L, 1_000_001L))
        check("grace: no stamp never grants",
            !g.withinGrace(15, 0L, 1_000_000L))
        check("grace: inside the window grants (5 min, 1 ms before the edge)",
            g.withinGrace(5, 1_000_000L, 1_000_000L + 299_999L))
        check("grace: expiry is exact — at the boundary the gate prompts again",
            !g.withinGrace(5, 1_000_000L, 1_000_000L + 300_000L) &&
                !g.withinGrace(5, 1_000_000L, 1_000_000L + 300_001L))
        check("grace: a stamp in the future (clock moved back) never grants",
            !g.withinGrace(30, 2_000_000L, 1_000_000L))
        check("grace: longer windows hold proportionally (30 min at 29:59)",
            g.withinGrace(30, 1_000_000L, 1_000_000L + 29L * 60_000L + 59_000L))

        // TrackerStatus: classification precedence + wording maps.
        val ts = com.mizan.launcher.core.TrackerStatus
        check("tracker status: classify — not installed beats everything",
            ts.classify(installed = false, anyAnswered = true, securityError = true, hasData = true) ==
                com.mizan.launcher.core.TrackerStatus.Kind.NOT_INSTALLED)
        check("tracker status: classify — a permission refusal is reported even with cached data",
            ts.classify(installed = true, anyAnswered = true, securityError = true, hasData = true) ==
                com.mizan.launcher.core.TrackerStatus.Kind.PERMISSION &&
                ts.classify(installed = true, anyAnswered = false, securityError = true, hasData = false) ==
                com.mizan.launcher.core.TrackerStatus.Kind.PERMISSION)
        check("tracker status: classify — answered+data is NONE, answered-empty is EMPTY, silent is OTHER",
            ts.classify(installed = true, anyAnswered = true, securityError = false, hasData = true) ==
                com.mizan.launcher.core.TrackerStatus.Kind.NONE &&
                ts.classify(installed = true, anyAnswered = true, securityError = false, hasData = false) ==
                com.mizan.launcher.core.TrackerStatus.Kind.EMPTY &&
                ts.classify(installed = true, anyAnswered = false, securityError = false, hasData = false) ==
                com.mizan.launcher.core.TrackerStatus.Kind.OTHER)
        check("tracker status: diag line forms for every state",
            ts.diagText(com.mizan.launcher.core.TrackerStatus.Kind.NOT_INSTALLED, false, 0, 0, 0, 0, 0, "") == "not installed" &&
                ts.diagText(com.mizan.launcher.core.TrackerStatus.Kind.PERMISSION, false, 0, 0, 0, 0, 0, "")
                    .startsWith("permission blocked") &&
                ts.diagText(com.mizan.launcher.core.TrackerStatus.Kind.EMPTY, false, 0, 0, 0, 0, 0, "") ==
                "no data yet (open the tracker once)" &&
                ts.diagText(com.mizan.launcher.core.TrackerStatus.Kind.OTHER, false, 0, 0, 0, 0, 0, "")
                    .startsWith("not answering"))
        check("tracker status: diag OK form carries per-section counts and the snapshot time (v2.1)",
            ts.diagText(com.mizan.launcher.core.TrackerStatus.Kind.NONE, true, 3, 2, 5, 59, 8, "2:41 PM") ==
                "OK · up 3 · cont 2 · mov 5 · trk 59 · foryou 8 · 2:41 PM" &&
                ts.diagText(com.mizan.launcher.core.TrackerStatus.Kind.NONE, true, 0, 1, 0, 13, 0, "") ==
                "OK · up 0 · cont 1 · mov 0 · trk 13 · foryou 0")
        check("tracker status: diag NONE without data reads as the empty state",
            ts.diagText(com.mizan.launcher.core.TrackerStatus.Kind.NONE, false, 0, 0, 0, 0, 0, "") ==
                "no data yet (open the tracker once)")
        check("tracker status: fallback line — absent tracker shows NO line",
            ts.emptyLine(com.mizan.launcher.core.TrackerStatus.Kind.NOT_INSTALLED, installed = true) == null &&
                ts.emptyLine(com.mizan.launcher.core.TrackerStatus.Kind.EMPTY, installed = false) == null &&
                ts.emptyLine(com.mizan.launcher.core.TrackerStatus.Kind.PERMISSION, installed = false) == null)
        check("tracker status: fallback line — permission state is actionable",
            ts.emptyLine(com.mizan.launcher.core.TrackerStatus.Kind.PERMISSION, installed = true) ==
                "Tracker data is blocked — updating or reinstalling Sreeb fixes the permission")
        check("tracker status: fallback line — empty/other/none ask for one tracker open",
            ts.emptyLine(com.mizan.launcher.core.TrackerStatus.Kind.EMPTY, installed = true) ==
                "Open TV Shows & Movies Tracker once so it can refresh" &&
                ts.emptyLine(com.mizan.launcher.core.TrackerStatus.Kind.OTHER, installed = true) ==
                "Open TV Shows & Movies Tracker once so it can refresh" &&
                ts.emptyLine(com.mizan.launcher.core.TrackerStatus.Kind.NONE, installed = true) ==
                "Open TV Shows & Movies Tracker once so it can refresh")
    }

    // ---------- (35) v2.0: Netflix tokens, hero rotator, tracker sections, For You, categories, most used, artwork, quick settings, live TV ----------
    run {
        // --- Design tokens + poster metrics (spec §18) ---
        check("netflix: page + brand tokens",
            Design.NETFLIX_BG == 0xFF141414.toInt() && Design.NETFLIX_RED == 0xFFE50914.toInt())
        val pm1 = TileLayout.posterMetrics(1920, 2f, "standard", 6)
        val tm1 = TileLayout.tileMetrics(1920, 2f, "standard", 6)
        check("poster metrics: height is two tile heights, width is 2:3",
            pm1.tileHeightPx == (tm1.tileHeightPx * Design.POSTER_HEIGHT_TILES).toInt() &&
                pm1.tileWidthPx == pm1.tileHeightPx * 2 / 3,
            "poster=${pm1.tileWidthPx}x${pm1.tileHeightPx} tile=${tm1.tileWidthPx}x${tm1.tileHeightPx}")
        check("poster metrics: portrait, and follows the size scale",
            pm1.tileHeightPx > pm1.tileWidthPx &&
                TileLayout.posterMetrics(1920, 2f, "large", 6).tileHeightPx > pm1.tileHeightPx)

        // --- HeroRotator ---
        val r0 = HeroRotator.initial(0, 3, 1_000L)
        check("rotator: first advance is due one interval out",
            r0.dueAt == 1_000L + HeroRotator.INTERVAL_MS && HeroRotator.tick(r0, r0.dueAt - 1) == null)
        val r1 = HeroRotator.tick(r0, r0.dueAt)
        check("rotator: tick at the due time advances and re-times",
            r1 != null && r1.index == 1 && r1.dueAt == r0.dueAt + HeroRotator.INTERVAL_MS)
        val rWrap = HeroRotator.tick(HeroRotator.initial(2, 3, 0L), HeroRotator.INTERVAL_MS)
        check("rotator: advance wraps past the last candidate",
            rWrap != null && rWrap.index == 0)
        check("rotator: fewer than 2 candidates never rotates",
            HeroRotator.tick(HeroRotator.initial(0, 1, 0L), 999_999L) == null &&
                !HeroRotator.canRotate(HeroRotator.initial(0, 0, 0L)))
        val rHeld = HeroRotator.focusGained(r0)
        check("rotator: focus held blocks a late tick",
            HeroRotator.tick(rHeld, r0.dueAt + 60_000L) == null)
        val rRel = HeroRotator.focusReleased(rHeld, 50_000L)
        check("rotator: release resumes after the resume delay, not at once",
            !rRel.focusHeld && rRel.dueAt == 50_000L + HeroRotator.RESUME_MS &&
                HeroRotator.tick(rRel, rRel.dueAt - 1) == null &&
                HeroRotator.tick(rRel, rRel.dueAt)?.index == 1)
        val rNext = HeroRotator.next(r0, 7_000L)
        check("rotator: Next advances immediately and resets the timer",
            rNext.index == 1 && rNext.dueAt == 7_000L + HeroRotator.INTERVAL_MS)
        val rClamp = HeroRotator.withCount(HeroRotator.initial(2, 3, 0L), 2, 100L)
        check("rotator: a shrunk candidate list clamps the index",
            rClamp.index == 1 && rClamp.count == 2)
        check("rotator: initial index is clamped into range",
            HeroRotator.initial(9, 3, 0L).index == 2 && HeroRotator.initial(-1, 3, 0L).index == 0)

        // --- TrackerSections ---
        val ts = TrackerSections
        val defCfg = ts.decode(null)
        check("sections: default config composes the canonical order",
            ts.compose(defCfg) { true } == ts.CANONICAL)
        check("sections: codec round-trips a custom config",
            ts.decode(ts.encode(ts.normalize(setOf("foryou"), listOf("foryou", "upnext", "continue", "movienight", "favorites", "allapps")))) ==
                ts.normalize(setOf("foryou"), listOf("foryou", "upnext", "continue", "movienight", "favorites", "allapps")))
        check("sections: garbage decodes to defaults",
            ts.decode("nonsense||,,") == TrackerSections.Config() && ts.decode("") == TrackerSections.Config())
        val noHero = ts.setShown(defCfg, ts.HERO, false)
        check("sections: a hidden hero drops only the hero",
            ts.compose(noHero) { true } == ts.CANONICAL.drop(1))
        val moved = ts.move(defCfg, ts.FORYOU, -1)
        check("sections: move up swaps with the previous row",
            moved.order == listOf("upnext", "continue", "foryou", "movienight", "favorites", "allapps"))
        check("sections: moves at the edges and for the hero are no-ops",
            ts.move(defCfg, ts.UPNEXT, -1) == defCfg &&
                ts.move(defCfg, ts.ALLAPPS, 1) == defCfg &&
                ts.move(defCfg, ts.HERO, 1) == defCfg)
        val reordered = ts.normalize(emptySet(), listOf("allapps", "upnext"))
        check("sections: compose follows the configured order, hero first",
            ts.compose(reordered) { true } ==
                listOf("hero", "allapps", "upnext", "continue", "movienight", "foryou", "favorites"))
        check("sections: hidden + data-empty sections are both skipped",
            ts.compose(ts.setShown(defCfg, ts.UPNEXT, false)) { it != ts.MOVIENIGHT } ==
                listOf("hero", "continue", "foryou", "favorites", "allapps"))
        check("sections: unknown keys are dropped by normalize",
            ts.normalize(setOf("bogus"), listOf("bogus", "upnext")).hidden.isEmpty() &&
                ts.normalize(setOf("bogus"), listOf("bogus", "upnext")).order.first() == "upnext")

        // --- For You: parse, filter, overlay convergence ---
        val fyJson = """
            [{"id": 101, "type": "tv", "title": "Slow Horses", "poster_path": "/sh.jpg",
              "vote_average": 8.24, "reason": "Because you watch Severance",
              "whyGenres": ["Drama", "Thriller"], "score": 91.5},
             {"id": 202, "type": "movie", "title": "Dune: Part Two", "poster_path": "/dune.jpg",
              "vote_average": 8.0, "reason": "Popular with sci-fi fans",
              "whyGenres": ["Sci-Fi"], "score": 88.0},
             {"id": 303, "type": "tv", "title": "No Rating Show", "poster_path": "",
              "vote_average": 0, "reason": "", "whyGenres": [], "score": 10.0}]
        """.trimIndent()
        val picks = TrackerLogic.parseForYou(fyJson)
        check("foryou: picks parse with the contract shape (whyGenres are names)",
            picks.size == 3 && picks[0].title == "Slow Horses" &&
                picks[0].whyGenres == listOf("Drama", "Thriller") &&
                picks[0].poster == "/sh.jpg" && picks[1].type == "movie",
            picks.toString())
        check("foryou: malformed JSON yields no picks",
            TrackerLogic.parseForYou(null).isEmpty() && TrackerLogic.parseForYou("junk").isEmpty())
        check("foryou: rating text is one decimal, absent when zero",
            TrackerLogic.ratingText(8.24) == "★ 8.2" &&
                TrackerLogic.ratingText(8.0) == "★ 8.0" &&
                TrackerLogic.ratingText(0.0) == null)
        val trackedShows = listOf(TrackerLogic.TrackedShow(101L, "Slow Horses", 3, false))
        val trackedMovies = listOf(TrackerLogic.MovieItem(202L, "Dune: Part Two", "2024-03-01", false, "/dune.jpg", false))
        check("foryou: already-tracked picks (tv + movie) are dropped",
            TrackerLogic.filterForYou(picks, trackedShows, trackedMovies, emptyList()).map { it.id } ==
                listOf(303L))
        val overlay = listOf(TrackerLogic.ForYouMark(303L, "tv", added = false, at = 500L))
        check("foryou: overlay-dismissed picks are dropped",
            TrackerLogic.filterForYou(picks, emptyList(), emptyList(), overlay).map { it.id } ==
                listOf(101L, 202L))
        check("foryou: the row is capped",
            TrackerLogic.filterForYou(
                (1..30).map { TrackerLogic.ForYouPick(it.toLong(), "tv", "T$it", "", 7.0, "", emptyList(), 1.0) },
                emptyList(), emptyList(), emptyList()
            ).size == 20)
        check("foryou: overlay codec round-trips",
            TrackerLogic.decodeForYouOverlay(TrackerLogic.encodeForYouOverlay(overlay)) == overlay)
        val stalePrune = TrackerLogic.pruneForYouOverlay(
            listOf(TrackerLogic.ForYouMark(101L, "tv", added = true, at = 900L)),
            picks, emptyList(), emptyList(), snapshotUpdated = 800L
        )
        check("foryou: a snapshot older than the mark retires nothing",
            stalePrune.size == 1)
        val addedPrune = TrackerLogic.pruneForYouOverlay(
            listOf(TrackerLogic.ForYouMark(101L, "tv", added = true, at = 100L)),
            picks, trackedShows, emptyList(), snapshotUpdated = 1_000L
        )
        check("foryou: an add retires once the title is tracked",
            addedPrune.isEmpty())
        val addedGone = TrackerLogic.pruneForYouOverlay(
            listOf(TrackerLogic.ForYouMark(999L, "tv", added = true, at = 100L)),
            picks, emptyList(), emptyList(), snapshotUpdated = 1_000L
        )
        check("foryou: an add also retires when the pick leaves the snapshot",
            addedGone.isEmpty())
        val dismissKept = TrackerLogic.pruneForYouOverlay(overlay, picks, emptyList(), emptyList(), 1_000L)
        val dismissPrune = TrackerLogic.pruneForYouOverlay(
            overlay, picks.filter { it.id != 303L }, emptyList(), emptyList(), 1_000L
        )
        check("foryou: a dismiss retires only when the pick leaves the snapshot",
            dismissKept.size == 1 && dismissPrune.isEmpty())

        // --- AppCategories ---
        val ac = AppCategories
        check("categories: curated packages win over the manifest category",
            ac.categoryFor("com.netflix.ninja", -1) == "Video" &&
                ac.categoryFor("com.google.android.youtube.tv", 0) == "Video" &&
                ac.categoryFor("com.spotify.music", -1) == "Music" &&
                ac.categoryFor("com.peacocktv.peacockandroid", -1) == "Video")
        check("categories: manifest category table",
            ac.categoryFor("com.unknown.a", 2) == "Video" &&
                ac.categoryFor("com.unknown.b", 1) == "Music" &&
                ac.categoryFor("com.unknown.c", 0) == "Games" &&
                ac.categoryFor("com.unknown.d", 4) == "Social" &&
                ac.categoryFor("com.unknown.e", 7) == "Other" &&
                ac.categoryFor("com.unknown.f", 5) == "Other" &&
                ac.categoryFor("com.unknown.g", 3) == "Other" &&
                ac.categoryFor("com.unknown.h", -1) == "Other")
        val grouped = ac.sections(
            listOf(
                AppCategories.Item("com.zed.app", "Zed", -1),
                AppCategories.Item("com.netflix.ninja", "Netflix", -1),
                AppCategories.Item("com.alpha.app", "alpha", -1),
                AppCategories.Item("com.spotify.music", "Spotify", -1),
                AppCategories.Item("com.game.x", "Game X", 0)
            )
        )
        check("categories: sections render in order, non-empty only, alpha inside",
            grouped.map { it.first } == listOf("Video", "Music", "Games", "Other") &&
                grouped.last().second.map { it.label } == listOf("alpha", "Zed"),
            grouped.toString())

        // --- MostUsedLogic ---
        val stats = listOf(
            MostUsedLogic.Stat("com.a", 5, 100L, "Alpha"),
            MostUsedLogic.Stat("com.b", 5, 300L, "Beta"),
            MostUsedLogic.Stat("com.c", 5, 300L, "Charlie"),
            MostUsedLogic.Stat("com.d", 9, 50L, "Delta"),
            MostUsedLogic.Stat("com.hidden", 99, 999L, "Hidden"),
            MostUsedLogic.Stat("com.gone", 99, 999L, "Uninstalled")
        )
        val top = MostUsedLogic.topUsed(
            stats, setOf("com.a", "com.b", "com.c", "com.d", "com.hidden"), setOf("com.hidden"), 8
        )
        check("mostused: count desc, recency breaks count ties, name breaks recency ties",
            top.map { it.pkg } == listOf("com.d", "com.b", "com.c", "com.a"), top.toString())
        check("mostused: hidden + non-launchable excluded, limit respected, zero counts dropped",
            MostUsedLogic.topUsed(stats, setOf("com.a", "com.b"), emptySet(), 1).map { it.pkg } ==
                listOf("com.b") &&
                MostUsedLogic.topUsed(
                    listOf(MostUsedLogic.Stat("com.z", 0, 5L, "Zero")), setOf("com.z"), emptySet(), 8
                ).isEmpty())

        // --- ArtworkStore ---
        val aw = ArtworkStore
        check("artwork: 12 presets with names + colours, indices coerced",
            aw.PRESET_COUNT == 12 && aw.PRESET_NAMES.size == 12 &&
                aw.presetIndex("99") == 11 && aw.presetIndex("-3") == 0 &&
                aw.presetColors(0) != aw.presetColors(1))
        val awMap = mapOf(
            "com.netflix.ninja" to aw.preset(3),
            "com.spotify.music" to aw.url("https://example.com/a\tb.png")
        )
        check("artwork: codec round-trips presets and escaped URLs",
            aw.decode(aw.encode(awMap)) == awMap)
        check("artwork: garbage decodes empty; with() sets and removes",
            aw.decode("garbage\n\ncom.x\tQ\t1").isEmpty() &&
                aw.with(awMap, "com.new", aw.preset(0)).size == 3 &&
                aw.with(awMap, "com.netflix.ninja", null).size == 1)

        // --- QuickSettings table ---
        check("quicksettings: six system targets then Sreeb Settings, actions distinct",
            QuickSettings.TARGETS.size == 7 &&
                QuickSettings.TARGETS.last().label == "Sreeb Settings" &&
                QuickSettings.TARGETS.last().action.isEmpty() &&
                QuickSettings.TARGETS.dropLast(1).all { it.action.startsWith("android.settings.") } &&
                QuickSettings.TARGETS.dropLast(1).map { it.action }.toSet().size == 6)
        check("quicksettings: unresolvable targets are filtered, Sreeb Settings stays",
            QuickSettings.visible(QuickSettings.TARGETS) { it.contains("WIFI") }.map { it.label } ==
                listOf("Wi-Fi", "Sreeb Settings"))

        // --- LiveTvLogic state mapping ---
        val lt = LiveTvLogic
        check("livetv: state precedence (feature, security, failure, count)",
            lt.classify(hasLiveTv = false, securityBlocked = true, queryFailed = false, channelCount = 5) ==
                LiveTvLogic.State.NOT_SUPPORTED &&
                lt.classify(true, securityBlocked = true, queryFailed = false, channelCount = 5) ==
                LiveTvLogic.State.BLOCKED &&
                lt.classify(true, securityBlocked = false, queryFailed = true, channelCount = 0) ==
                LiveTvLogic.State.NOT_SUPPORTED &&
                lt.classify(true, securityBlocked = false, queryFailed = false, channelCount = 0) ==
                LiveTvLogic.State.NO_CHANNELS &&
                lt.classify(true, securityBlocked = false, queryFailed = false, channelCount = 7) ==
                LiveTvLogic.State.OK)
        check("livetv: diagnostics wording",
            lt.diagText(LiveTvLogic.State.OK, 7) == "7 channels" &&
                lt.diagText(LiveTvLogic.State.BLOCKED, 0) == "blocked (permission)" &&
                lt.diagText(LiveTvLogic.State.NO_CHANNELS, 0) == "no tuner channels" &&
                lt.diagText(LiveTvLogic.State.NOT_SUPPORTED, 0) == "not supported")
    }

    // ---------- (36) v2.1: library merge/sort/progress, TMDB parsing + errors, pending overlay ----------
    run {
        val ll = LibraryLogic

        // --- /tracked full parse ---
        val trackedJson = """
            [{"tmdbId": 125988, "name": "Silo", "muted": true, "planned": false,
              "watched": ["1x1", "1x2", " 1x3 "]},
             {"tmdbId": 94997, "name": "Plunder", "muted": false, "planned": true,
              "watched": []},
             {"tmdbId": 0, "name": "Bad id", "watched": []},
             {"tmdbId": 7, "name": "", "watched": []}]
        """.trimIndent()
        val libShows = ll.parseLibShows(trackedJson)
        check("library: tracked parse keeps watched set + muted + planned, drops bad rows",
            libShows.size == 2 &&
                libShows[0].watched == setOf("1x1", "1x2", "1x3") &&
                libShows[0].muted && !libShows[0].planned &&
                libShows[1].planned && libShows[1].watched.isEmpty())
        check("library: tracked parse fails open",
            ll.parseLibShows(null).isEmpty() && ll.parseLibShows("junk").isEmpty() &&
                ll.parseLibShows("{}").isEmpty())

        // --- next-coords from a continue sub ---
        check("library: next coords parse from continue sub",
            ll.parseNextFromSub("Next S1 E4 · 3 of 20 watched") == (1 to 4) &&
                ll.parseNextFromSub("Next S12 E103 · 5 watched") == (12 to 103))
        check("library: caught-up / garbage subs yield no next coords",
            ll.parseNextFromSub("✓ All caught up") == null &&
                ll.parseNextFromSub("") == null &&
                ll.parseNextFromSub("Next episode soon") == null &&
                ll.parseNextFromSub("Next S0 E0") == null)

        // --- library merge + sort + progress ---
        val contJson = """
            [{"title": "Silo", "sub": "Next S1 E4 · 3 of 20 watched", "watched": 3,
              "total": 20, "poster": "/silo.jpg", "backdrop": "", "overview": "o",
              "tmdbId": 125988, "type": "tv"},
             {"title": "Done Show", "sub": "✓ All caught up", "watched": 8, "total": 8,
              "poster": "/done.jpg", "backdrop": "", "overview": "",
              "tmdbId": 555, "type": "tv"}]
        """.trimIndent()
        val upJson = """
            [{"title": "Plunder", "sub": "S1 E2 · Ep", "sort": "2026-11-01",
              "poster": "/pl.jpg", "backdrop": "", "overview": "", "season": 1,
              "episode": 2, "tmdbId": 94997, "type": "tv"}]
        """.trimIndent()
        val moviesJson = """
            [{"tmdbId": 693134, "title": "Dune: Part Two", "releaseDate": "2024-03-01",
              "planned": false, "poster": "/dune.jpg", "watched": false},
             {"tmdbId": 100, "title": "Old Film", "releaseDate": "1999-01-01",
              "planned": false, "poster": "", "watched": true}]
        """.trimIndent()
        val shows2 = libShows + LibraryLogic.LibShow(555, "Done Show", false, false, (1..8).map { "1x$it" }.toSet())
        val movies2 = TrackerLogic.parseMovies(moviesJson)
        val cont2 = TrackerLogic.parseContinue(contJson)
        val up2 = TrackerLogic.parseUpNext(upJson)
        val lib = ll.buildLibrary(shows2, movies2, cont2, up2, emptyList(), emptyMap())
        check("library: shows + movies merge into one grid",
            lib.size == 5 && lib.map { it.key() }.toSet().size == 5, lib.toString())
        check("library: sort - in-progress first (continue order), then up-next, then A-Z, finished last",
            lib.map { it.title } == listOf("Silo", "Plunder", "Dune: Part Two", "Done Show", "Old Film"),
            lib.map { it.title }.toString())
        val silo = lib.first { it.tmdbId == 125988L }
        check("library: progress derives from continue (count/total/next) + poster",
            silo.watchedCount == 3 && silo.totalCount == 20 &&
                silo.nextSeason == 1 && silo.nextEpisode == 4 &&
                silo.poster == "/silo.jpg" && silo.muted,
            silo.toString())
        check("library: caught-up show detected from the continue sub",
            lib.first { it.tmdbId == 555L }.caughtUp &&
                !lib.first { it.tmdbId == 555L }.let { it.nextSeason > 0 })
        check("library: progress line + fraction",
            ll.progressLine(silo) == "Next S1 E4 · 3 of 20 watched" &&
                ll.progressFraction(silo) == 0.15f &&
                ll.progressLine(lib.first { it.tmdbId == 693134L }) == "2024" &&
                ll.progressLine(lib.first { it.tmdbId == 100L }) == "✓ Watched" &&
                ll.progressFraction(lib.first { it.tmdbId == 100L }) < 0f)
        check("library: poster cache fills a poster the snapshots lack",
            ll.buildLibrary(
                listOf(LibraryLogic.LibShow(42, "Ghost", false, false, emptySet())),
                emptyList(), emptyList(), emptyList(), emptyList(),
                mapOf("tv|42" to "/ghost.jpg")
            ).single().poster == "/ghost.jpg")

        // --- overlay application in the grid ---
        var ov = emptyList<LibraryLogic.PendingTitle>()
        ov = ll.withRemoved(ov, "movie", 693134, 1_000)
        ov = ll.withAdded(ov, "tv", 777, "New Show", "/new.jpg", "", 1_000)
        ov = ll.withMuted(ov, "tv", 94997, true, 1_000)
        ov = ll.withMovieToggle(ov, 100, false, 1_000)
        val libOv = ll.buildLibrary(shows2, movies2, cont2, up2, ov, emptyMap())
        check("library overlay: removed title vanishes from the grid",
            libOv.none { it.tmdbId == 693134L })
        check("library overlay: added title appears as pending (planned)",
            libOv.firstOrNull { it.tmdbId == 777L }?.let {
                it.pending && it.planned && it.poster == "/new.jpg" &&
                    ll.progressLine(it).startsWith("Pending")
            } == true)
        check("library overlay: mute + movie-unwatch override the snapshot",
            libOv.first { it.tmdbId == 94997L }.muted &&
                !libOv.first { it.tmdbId == 100L }.movieWatched)
        check("library overlay: marking the known next episode clears the next claim",
            ll.buildLibrary(
                shows2, movies2, cont2, up2,
                ll.withEpisodeToggle(emptyList(), 125988, 1, 4, true, 5L),
                emptyMap()
            ).first { it.tmdbId == 125988L }.let {
                it.nextSeason == 0 && it.watchedCount == 4
            })

        // --- overlay codec + toggle discipline ---
        val nasty = ll.withAdded(
            emptyList(), "tv", 5, "A | B % C\nD", "/p|q.jpg", "2024-01-01", 42L
        )
        val nastyRt = ll.decodePending(ll.encodePending(nasty))
        check("overlay: codec round-trips escaped titles/posters",
            nastyRt == nasty, nastyRt.toString())
        check("overlay: codec fails open on garbage",
            ll.decodePending("a|b|c").isEmpty() && ll.decodePending(null).isEmpty() &&
                ll.decodePending("").isEmpty())
        var tog = ll.withEpisodeToggle(emptyList(), 9, 1, 1, true, 10L)
        tog = ll.withEpisodeToggle(tog, 9, 1, 1, false, 20L)
        val togEntry = ll.overlayEntry(tog, "tv", 9)!!
        check("overlay: newest toggle wins (mark then unmark leaves only the remove)",
            togEntry.watchedAdd.isEmpty() && togEntry.watchedRemove == setOf("1x1") &&
                togEntry.at == 20L)
        check("overlay: effective watched = snapshot plus adds minus removes",
            ll.effectiveWatched(
                LibraryLogic.LibShow(9, "X", false, false, setOf("1x1", "1x2")),
                ll.withEpisodeToggle(
                    ll.withEpisodeToggle(emptyList(), 9, 1, 3, true, 1L),
                    9, 1, 1, false, 2L
                ).single()
            ) == setOf("1x2", "1x3"))
        check("overlay: season mark adds every listed episode (and un-marks none)",
            ll.overlayEntry(
                ll.withSeasonMark(emptyList(), 9, 2, listOf(3, 1, 2), 7L), "tv", 9
            )!!.watchedAdd == setOf("2x1", "2x2", "2x3"))

        // --- overlay convergence (prune) ---
        val snapShows = listOf(
            LibraryLogic.LibShow(125988, "Silo", muted = true, planned = false, watched = setOf("1x1", "1x4"))
        )
        val snapMovies = listOf(
            TrackerLogic.MovieItem(693134, "Dune: Part Two", "2024-03-01", false, "/d.jpg", watched = true)
        )
        var pend = emptyList<LibraryLogic.PendingTitle>()
        pend = ll.withEpisodeToggle(pend, 125988, 1, 4, true, 100L)   // in snapshot → retires
        pend = ll.withEpisodeToggle(pend, 125988, 1, 9, true, 100L)   // not in snapshot → stays
        pend = ll.withMuted(pend, "tv", 125988, true, 100L)           // snapshot muted=true → retires
        pend = ll.withMovieToggle(pend, 693134, true, 100L)           // snapshot watched → retires
        val prunedFresh = ll.prunePending(pend, snapShows, snapMovies, snapshotUpdated = 200L)
        val prunedEntry = ll.overlayEntry(prunedFresh, "tv", 125988)
        check("overlay prune: converged parts retire, unconverged stay",
            prunedEntry != null && prunedEntry.watchedAdd == setOf("1x9") &&
                prunedEntry.muted == null &&
                ll.overlayEntry(prunedFresh, "movie", 693134) == null,
            prunedFresh.toString())
        check("overlay prune: a stale snapshot retires nothing",
            ll.prunePending(pend, snapShows, snapMovies, snapshotUpdated = 100L) == pend &&
                ll.prunePending(pend, snapShows, snapMovies, snapshotUpdated = 0L) == pend)
        val addPend = ll.withAdded(emptyList(), "tv", 777, "New Show", "", "", 100L)
        check("overlay prune: an add retires when the title reaches the snapshot",
            ll.prunePending(
                addPend, listOf(LibraryLogic.LibShow(777, "New Show", false, true, emptySet())),
                emptyList(), 200L
            ).isEmpty() &&
                ll.prunePending(addPend, emptyList(), emptyList(), 200L) == addPend)
        val removePend = ll.withRemoved(emptyList(), "movie", 693134, 100L)
        check("overlay prune: a remove retires when the title leaves the snapshot",
            ll.prunePending(removePend, emptyList(), snapMovies, 200L) == removePend &&
                ll.prunePending(removePend, emptyList(), emptyList(), 200L).isEmpty())
        val addRemove = ll.withRemoved(
            ll.withAdded(emptyList(), "tv", 888, "Flip", "", "", 100L), "tv", 888, 110L
        )
        check("overlay prune: add-then-remove settles whole once snapshots are fresher",
            ll.prunePending(addRemove, emptyList(), emptyList(), 200L).isEmpty())
        check("overlay prune: an unwatch retires when the episode leaves the watched set",
            ll.overlayEntry(
                ll.prunePending(
                    ll.withEpisodeToggle(emptyList(), 125988, 1, 7, false, 100L),
                    snapShows, snapMovies, 200L
                ), "tv", 125988
            ) == null)

        // --- TMDB details parsing ---
        val tvDetailsJson = """
            {"id": 125988, "name": "Silo", "overview": "Underground…",
             "vote_average": 8.2, "poster_path": "/siloP.jpg",
             "backdrop_path": "/siloB.jpg", "first_air_date": "2023-05-05",
             "status": "Returning Series",
             "seasons": [{"season_number": 1, "episode_count": 10, "name": "Season 1"},
                          {"season_number": 0, "episode_count": 3, "name": "Specials"},
                          {"season_number": 2, "episode_count": 0, "name": "Season 2"}]}
        """.trimIndent()
        val tvDetails = ll.parseDetails(tvDetailsJson, "tv")!!
        check("tmdb: tv details parse (fields, year, seasons sorted, empty season dropped)",
            tvDetails.title == "Silo" && tvDetails.year == "2023" &&
                tvDetails.voteAverage == 8.2 && tvDetails.poster == "/siloP.jpg" &&
                tvDetails.backdrop == "/siloB.jpg" &&
                tvDetails.seasons.map { it.number } == listOf(0, 1),
            tvDetails.toString())
        val movieDetails = ll.parseDetails(
            """{"id": 693134, "title": "Dune: Part Two", "overview": "Sand…",
                "vote_average": 8.3, "poster_path": "/d.jpg", "backdrop_path": "",
                "release_date": "2024-03-01", "status": "Released"}""", "movie"
        )!!
        check("tmdb: movie details parse (title/release year, no seasons)",
            movieDetails.title == "Dune: Part Two" && movieDetails.year == "2024" &&
                movieDetails.seasons.isEmpty())
        check("tmdb: details parse fails open",
            ll.parseDetails(null, "tv") == null && ll.parseDetails("junk", "tv") == null &&
                ll.parseDetails("""{"name": "No id"}""", "tv") == null)

        // --- season episodes + the mark_season payload ---
        val seasonJson = """
            {"season_number": 1, "episodes": [
              {"episode_number": 2, "name": "Holston's Pick", "air_date": "2023-05-05", "overview": ""},
              {"episode_number": 1, "name": "Freedom Day", "air_date": "2023-05-05", "overview": ""},
              {"episode_number": 2, "name": "Dup", "air_date": "", "overview": ""}]}
        """.trimIndent()
        val episodes = ll.parseSeasonEpisodes(seasonJson)
        check("tmdb: season episodes parse ascending",
            episodes.map { it.number } == listOf(1, 2, 2) &&
                episodes.first().name == "Freedom Day")
        check("tmdb: mark_season CSV is sorted, deduped, positive-only",
            ll.seasonCsv(episodes) == "1,2" &&
                ll.seasonCsv(listOf(LibraryLogic.EpisodeInfo(0, "", "", ""), LibraryLogic.EpisodeInfo(3, "", "", ""))) == "3" &&
                ll.seasonCsv(emptyList()) == "")
        check("tmdb: season parse fails open",
            ll.parseSeasonEpisodes(null).isEmpty() && ll.parseSeasonEpisodes("{}").isEmpty())

        // --- search parsing ---
        val searchJson = """
            {"results": [
              {"media_type": "tv", "id": 125988, "name": "Silo", "poster_path": "/s.jpg",
               "vote_average": 8.2, "first_air_date": "2023-05-05", "overview": "o"},
              {"media_type": "person", "id": 31, "name": "Some Actor", "poster_path": ""},
              {"media_type": "movie", "id": 693134, "title": "Dune: Part Two",
               "poster_path": "/d.jpg", "vote_average": 8.3, "release_date": "2024-03-01",
               "overview": ""},
              {"media_type": "tv", "id": 0, "name": "Bad"}]}
        """.trimIndent()
        val hits = ll.parseSearch(searchJson)
        check("tmdb: search keeps tv+movie, drops persons and bad rows",
            hits.size == 2 && hits[0].type == "tv" && hits[0].title == "Silo" &&
                hits[0].year == "2023" && hits[1].type == "movie" &&
                hits[1].title == "Dune: Part Two" && hits[1].year == "2024",
            hits.toString())
        check("tmdb: search parse fails open",
            ll.parseSearch(null).isEmpty() && ll.parseSearch("{}").isEmpty())

        // --- tmdb_get paths + error mapping ---
        check("tmdb: proxy paths (details / season / search with encoding)",
            ll.detailsPath("tv", 125988) == "tv/125988" &&
                ll.detailsPath("movie", 693134) == "movie/693134" &&
                ll.seasonPath(125988, 2) == "tv/125988/season/2" &&
                ll.searchPath("the matrix") == "search/multi?query=the%20matrix" &&
                ll.searchPath("Lupin & Co") == "search/multi?query=Lupin%20%26%20Co")
        check("tmdb: error kinds + http code",
            ll.tmdbErrorKind(null) == LibraryLogic.TmdbError.NONE &&
                ll.tmdbErrorKind("no_key") == LibraryLogic.TmdbError.NO_KEY &&
                ll.tmdbErrorKind("bad_path") == LibraryLogic.TmdbError.BAD_PATH &&
                ll.tmdbErrorKind("io") == LibraryLogic.TmdbError.IO &&
                ll.tmdbErrorKind("http_503") == LibraryLogic.TmdbError.HTTP &&
                ll.tmdbHttpCode("http_503") == 503 && ll.tmdbHttpCode("io") == 0 &&
                ll.tmdbErrorKind("weird") == LibraryLogic.TmdbError.UNKNOWN)
        check("tmdb: retryable = http/io/unknown only",
            ll.tmdbRetryable("http_500") && ll.tmdbRetryable("io") &&
                ll.tmdbRetryable("weird") && !ll.tmdbRetryable("no_key") &&
                !ll.tmdbRetryable("bad_path") && !ll.tmdbRetryable(null))
        check("tmdb: user lines (no_key names the fix; http carries the code)",
            ll.tmdbUserLine("no_key").contains("TMDB key") &&
                ll.tmdbUserLine("no_key").contains("tracker app") &&
                ll.tmdbUserLine("http_404").contains("404") &&
                ll.tmdbUserLine("io").contains("reach TMDB") &&
                ll.tmdbUserLine(null) == "")
        check("tmdb: diagnostics wording",
            ll.tmdbDiagText(null) == "not used yet" && ll.tmdbDiagText("ok") == "ok" &&
                ll.tmdbDiagText("no_key") == "no key in tracker" &&
                ll.tmdbDiagText("bad_path") == "bad path (bug)" &&
                ll.tmdbDiagText("http_500") == "http 500" &&
                ll.tmdbDiagText("io") == "network error")

        // --- poster cache codec ---
        val cache = mapOf("tv|125988" to "/silo.jpg", "movie|693134" to "/dune.jpg")
        check("poster cache: codec round-trips, garbage dropped",
            ll.decodePosterCache(ll.encodePosterCache(cache)) == cache &&
                ll.decodePosterCache("tv|x|/a.jpg\nmovie|5").isEmpty() &&
                ll.decodePosterCache(null).isEmpty())

        // --- Movie Night hardening (v2.1): a numeric-STRING tmdbId
        // must still parse — a bridge serializing ids as strings
        // would otherwise silently empty the whole row. ---
        val stringIdMovies =
            """[{"tmdbId":"303","title":"Strung","releaseDate":"2024-03-03","planned":true,"poster":"/s.jpg","watched":false}]"""
        check("movie night: numeric-string tmdbId still parses",
            TrackerLogic.parseMovies(stringIdMovies).let {
                it.size == 1 && it[0].tmdbId == 303L && it[0].title == "Strung" && !it[0].watched
            })

        // --- hero pool widening + source tags (spec section 19) ---
        val hUp = listOf(
            TrackerLogic.UpNextItem(1, "U-NoArt", "S1 E1", "2026-11-01", "", "", "", 1, 1, "tv"),
            TrackerLogic.UpNextItem(2, "U-Art", "S1 E2", "2026-11-02", "", "/u2.jpg", "", 1, 2, "tv")
        )
        val hCont = listOf(
            TrackerLogic.ContinueItem(3, "C-Art", "Next S1 E1", 1, 9, "", "/c3.jpg", "", "tv")
        )
        val hMovies = listOf(
            TrackerLogic.MovieItem(4, "M-One", "2026-12-01", false, "/m4.jpg", false),
            TrackerLogic.MovieItem(5, "M-Two", "", true, "", false)
        )
        val hPicks = listOf(
            TrackerLogic.ForYouPick(6, "tv", "P-Art", "/p6.jpg", 7.5, "Because you watched X", listOf("Drama"), 9.0),
            TrackerLogic.ForYouPick(7, "movie", "P-NoArt", "/p7.jpg", 6.0, "Trending", emptyList(), 5.0)
        )
        val pool = TrackerLogic.heroCandidates(
            hUp, hCont, hMovies, hPicks, mapOf("tv|6" to "/p6-back.jpg")
        )
        check("hero pool: order is Up Next, Continue, Movie Night, For You (backdrop-first in section)",
            pool.map { it.title } == listOf("U-Art", "C-Art", "U-NoArt", "M-One", "M-Two", "P-Art"),
            pool.map { it.title }.toString())
        check("hero pool: every candidate carries its exact section name as source",
            pool.map { it.source } == listOf(
                "Up Next", "Continue Watching", "Up Next",
                "Movie Night", "Movie Night", "For You"
            ),
            pool.map { it.source }.toString())
        check("hero pool: poster-only For You pick stays out; a known backdrop lets it in",
            pool.none { it.title == "P-NoArt" } &&
                pool.first { it.title == "P-Art" }.backdrop == "/p6-back.jpg" &&
                pool.first { it.title == "P-Art" }.sub == "Because you watched X")
        check("hero pool: movie hero uses the movie path (type movie, release date, no fake backdrop)",
            pool.first { it.title == "M-One" }.let {
                it.type == "movie" && it.dateIso == "2026-12-01" && it.backdrop.isEmpty()
            } &&
                pool.first { it.title == "M-Two" }.sub == "Planned")
        check("hero pool: two-source overload keeps the v1.8 order and tags",
            TrackerLogic.heroCandidates(hUp, hCont).map { it.title to it.source } == listOf(
                "U-Art" to "Up Next", "C-Art" to "Continue Watching", "U-NoArt" to "Up Next"
            ))
    }

    // ---------- (37) v2.2 IPTV: account + URL shapes, Xtream/M3U parsing, favorites + resume stores, home composition, EPG, category locks, status ----------
    run {
        val L = IptvLogic
        val acct = L.account("panel.example:8080/", "user name", "p@ss")

        // Account + server normalization.
        check("iptv: bare host gains http:// and loses the trailing slash",
            acct.server == "http://panel.example:8080" &&
                L.normalizeServer("https://x.tv/") == "https://x.tv" &&
                L.normalizeServer("  ") == "" &&
                L.normalizeServer("EXAMPLE.tv") == "http://EXAMPLE.tv")
        check("iptv: account needs server + user + pass",
            acct.isConfigured &&
                !L.account("http://x.tv", "u", "").isConfigured &&
                !L.account("", "u", "p").isConfigured)

        // URL shapes (the Xtream protocol shapes, from pure core).
        check("iptv: api url is player_api.php with encoded credentials + action",
            L.apiUrl(acct, null) ==
                "http://panel.example:8080/player_api.php?username=user+name&password=p%40ss" &&
                L.apiUrl(acct, "get_live_streams").endsWith("&action=get_live_streams") &&
                L.apiUrl(acct, "get_series_info", mapOf("series_id" to "9"))
                    .endsWith("&action=get_series_info&series_id=9"))
        check("iptv: xmltv url shape",
            L.xmltvUrl(acct) ==
                "http://panel.example:8080/xmltv.php?username=user+name&password=p%40ss")
        check("iptv: live url is /live/user/pass/id.m3u8",
            L.liveUrl(acct, 42) == "http://panel.example:8080/live/user%20name/p@ss/42.m3u8")
        check("iptv: vod url uses container_extension, defaulting to mp4",
            L.vodUrl(acct, 7, "mkv") == "http://panel.example:8080/movie/user%20name/p@ss/7.mkv" &&
                L.vodUrl(acct, 7, "") == "http://panel.example:8080/movie/user%20name/p@ss/7.mp4" &&
                L.vodUrl(acct, 7, ".avi") == "http://panel.example:8080/movie/user%20name/p@ss/7.avi")
        check("iptv: episode url is /series/user/pass/episodeId.ext",
            L.episodeUrl(acct, "e 9", "") ==
                "http://panel.example:8080/series/user%20name/p@ss/e%209.mp4")
        val shiftStart = IptvEpg.parseXmltvTime("20261022173000 +0000")
        check("iptv: timeshift url is /timeshift/user/pass/durationMin/startYmdHis/id.ts",
            L.timeshiftUrl(acct, 42, shiftStart, 60) ==
                "http://panel.example:8080/timeshift/user%20name/p@ss/60/20261022173000/42.ts")

        // Auth probe parsing.
        check("iptv: auth probe — 1 true, 0 false, string ids accepted, junk null",
            L.parseAuth("""{"user_info":{"auth":1}}""") == true &&
                L.parseAuth("""{"user_info":{"auth":0}}""") == false &&
                L.parseAuth("""{"user_info":{"auth":"1"}}""") == true &&
                L.parseAuth("""{"user_info":{}}""") == null &&
                L.parseAuth("not json") == null)

        // Catalogue parsing (ids as strings AND numbers; malformed entries skipped).
        val liveCats = L.parseCategories(
            """[{"category_id":"5","category_name":"News"},{"category_id":6,"category_name":"Sport"},{"category_id":"7","category_name":"  "}]""",
            L.KIND_LIVE
        )
        check("iptv: categories parse (string+numeric ids, blank names skipped, key stamped)",
            liveCats.size == 2 && liveCats[0].key == "xtream:live:5" &&
                liveCats[1].id == "6" && liveCats[1].source == "xtream")
        val live = L.parseLive(
            """[{"stream_id":"101","name":"Chan A","stream_icon":"http://img/a.png","category_id":"5","epg_channel_id":"ch1","tv_archive":1,"tv_archive_duration":2},{"stream_id":102,"name":"Chan B","category_id":"5"},{"stream_id":0,"name":"Bad id"},{"stream_id":103,"name":"  "}]""",
            liveCats.associate { it.id to it.name }
        )
        check("iptv: live parse skips bad ids + blank names, resolves category names",
            live.size == 2 && live[0].categoryName == "News" && live[1].icon == "")
        check("iptv: tv_archive flag drives catch-up availability",
            live[0].hasArchive && live[0].archiveDays == 2 && !live[1].hasArchive)
        val vods = L.parseVods(
            """[{"stream_id":201,"name":"Film","stream_icon":"http://img/f.jpg","category_id":"7","container_extension":"mkv","rating":8.5},{"stream_id":"202","name":"Film 2","category_id":"7"}]""",
            mapOf("7" to "Films")
        )
        check("iptv: vod parse keeps container_extension + numeric rating as text",
            vods.size == 2 && vods[0].ext == "mkv" && vods[0].rating == "8.5" &&
                vods[1].ext == "" && vods[1].key == "xtream:vod:202")
        val seriesList = L.parseSeries(
            """[{"series_id":"301","name":"Show","cover":"http://img/s.jpg","category_id":"8","rating":"9.1"}]""",
            mapOf("8" to "Drama")
        )
        check("iptv: series list parse",
            seriesList.size == 1 && seriesList[0].catKey == "xtream:series:8" &&
                seriesList[0].categoryName == "Drama")

        // Series info: numeric season sort ("10" after "2"), episode
        // sort, blank episode ids skipped, duration from info.
        val info = L.parseSeriesInfo(
            """{"info":{"name":"Show","cover":"http://img/s.jpg","plot":"A plot."},"episodes":{"2":[{"id":"902","episode_num":1,"title":"Return","container_extension":"mp4","info":{"duration_secs":1800}}],"1":[{"id":"901","episode_num":2,"title":"Second","container_extension":"mkv","info":{"duration_secs":1500}},{"id":"900","episode_num":1,"title":"Pilot","container_extension":"mp4","info":{}},{"id":"","episode_num":3,"title":"Ghost"}],"10":[{"id":"910","episode_num":1,"title":"Finale","container_extension":"mp4","info":{}}]}}""",
            301
        )
        check("iptv: series info — seasons sort numerically, ghost episodes skipped",
            info != null && info.seasons.map { it.number } == listOf(1, 2, 10) &&
                info.seasons[0].episodes.map { it.num } == listOf(1, 2))
        check("iptv: series info — episode fields (ext, duration, plot)",
            info != null && info.plot == "A plot." &&
                info.seasons[0].episodes[1].ext == "mkv" &&
                info.seasons[0].episodes[1].durationSecs == 1500 &&
                info.seasons[1].episodes[0].durationSecs == 1800 &&
                L.parseSeriesInfo("garbage", 1) == null)

        // M3U parsing: attributes, quote-aware name split, group → category.
        val m3uText = listOf(
            "#EXTM3U",
            "#EXTINF:-1 tvg-id=\"ch1\" tvg-logo=\"http://img/a.png\" group-title=\"News, World\",Chan A",
            "http://streams.example/a.m3u8",
            "#EXTINF:-1 tvg-id=\"ch2\" group-title=\"Sport\",Chan B, Live",
            "http://streams.example/b.m3u8",
            "#EXTINF:-1,Plain Chan",
            "http://streams.example/c.m3u8"
        ).joinToString("\n")
        val (m3uCats, m3uLive) = L.parseM3u(m3uText)
        check("iptv: m3u channels + groups (quote-aware name, ungrouped → Playlist)",
            m3uLive.size == 3 && m3uLive[0].name == "Chan A" &&
                m3uLive[1].name == "Chan B, Live" &&
                m3uCats.map { it.name } == listOf("News, World", "Sport", "Playlist") &&
                m3uLive[2].categoryName == "Playlist")
        check("iptv: m3u channel identity — tvg-id/logo/direct url, stable ids",
            m3uLive[0].epgId == "ch1" && m3uLive[0].icon == "http://img/a.png" &&
                m3uLive[0].directUrl == "http://streams.example/a.m3u8" &&
                m3uLive[0].source == "m3u" &&
                L.parseM3u(m3uText).second.map { it.id } == m3uLive.map { it.id } &&
                L.parseM3u("").second.isEmpty())

        // Favorites codec: hostile names round-trip; toggle discipline.
        val favLive = L.favOf(live[0])
        val favWeird = IptvLogic.FavEntry(
            L.KIND_VOD, "xtream", "55", "A|B%C\nD", "http://i/x.png", "mkv",
            "xtream:vod:7", ""
        )
        val favRound = L.decodeFavorites(L.encodeFavorites(listOf(favLive, favWeird)))
        check("iptv: favorites codec round-trips hostile names",
            favRound == listOf(favLive, favWeird))
        check("iptv: favorites toggle adds then removes by identity key",
            L.toggleFavorite(emptyList(), favLive).size == 1 &&
                L.toggleFavorite(listOf(favLive), favLive).isEmpty() &&
                L.isFavorite(listOf(favLive), "xtream:live:101") &&
                !L.isFavorite(listOf(favLive), "xtream:live:102"))
        check("iptv: favorites decode is fail-open on garbage",
            L.decodeFavorites("|||\n\nkind|only").isEmpty() &&
                L.decodeFavorites(null).isEmpty())

        // Resume store: clear rules, replacement, ordering, continue filter.
        check("iptv: shouldClear — near the end or ≥95% is finished; unknown duration is not",
            L.shouldClear(95_000, 100_000) && L.shouldClear(86_000, 100_000) &&
                !L.shouldClear(50_000, 100_000) && !L.shouldClear(50_000, 0) &&
                !L.shouldClear(0, 100_000))
        fun resumeEntry(key: String, kind: String, pos: Long, at: Long) = IptvLogic.ResumeEntry(
            key, kind, key.substringAfterLast(':'), "T", "", "", pos, 100_000, at, "", "", ""
        )
        val r1 = resumeEntry("xtream:vod:1", L.KIND_VOD, 40_000, 100)
        val r2 = resumeEntry("xtream:episode:9", L.KIND_EPISODE, 30_000, 200)
        val rLive = resumeEntry("xtream:live:5", L.KIND_LIVE, 30_000, 300)
        var store = L.withResume(emptyList(), r1)
        store = L.withResume(store, r2)
        store = L.withResume(store, rLive)
        check("iptv: resume store replaces by key and drops finished entries",
            store.size == 3 &&
                L.withResume(store, r1.copy(posMs = 99_000)).size == 2 &&
                L.resumeFor(store, "xtream:vod:1")?.posMs == 40_000L)
        check("iptv: resumeFor hides sub-threshold positions",
            L.resumeFor(store, "xtream:vod:1") != null &&
                L.resumeFor(listOf(r1.copy(posMs = 2_000)), "xtream:vod:1") == null)
        check("iptv: continue list is vod/episode only, newest first",
            L.continueList(store).map { it.key } ==
                listOf("xtream:episode:9", "xtream:vod:1"))
        val resumeRound = L.decodeResume(L.encodeResume(store))
        check("iptv: resume codec round-trips",
            resumeRound == store && resumeRound.toSet() == store.toSet())

        // Search across catalogues with hidden categories excluded.
        val catsAll = IptvLogic.Catalogues(
            liveCats = liveCats, live = live, vodCats = emptyList(), vods = vods,
            seriesCats = emptyList(), series = seriesList
        )
        check("iptv: search is case-insensitive across all three catalogues",
            L.search(catsAll, "CHAN", emptySet()).live.size == 2 &&
                L.search(catsAll, "film", emptySet()).vods.size == 2 &&
                L.search(catsAll, "show", emptySet()).series.size == 1 &&
                L.search(catsAll, "  ", emptySet()).isEmpty)
        check("iptv: search excludes hidden categories",
            L.search(catsAll, "chan", setOf("xtream:live:5")).live.isEmpty())

        // Home composition: favorites first, empty categories skipped,
        // locked content dropped, EPG now/next on live cards.
        val progStart = IptvEpg.parseXmltvTime("20261022173000 +0000")
        val progStop = IptvEpg.parseXmltvTime("20261022183000 +0000")
        val epgMap = mapOf("ch1" to listOf(IptvEpg.Programme("ch1", progStart, progStop, "Evening News")))
        val nowMs = IptvEpg.parseXmltvTime("20261022180000 +0000")
        val chanSport = live[1].copy(categoryId = "6", categoryName = "Sport")
        val catsHome = IptvLogic.Catalogues(
            liveCats = liveCats,
            live = listOf(live[0], chanSport),
            vodCats = listOf(IptvLogic.Category("7", "Films", "xtream", L.KIND_VOD)), vods = vods,
            seriesCats = listOf(IptvLogic.Category("8", "Drama", "xtream", L.KIND_SERIES)), series = seriesList
        )
        val rowsAll = L.homeRows(
            catsHome, listOf(L.favOf(vods[0])), listOf(r2), emptySet(), epgMap, nowMs
        )
        check("iptv: home rows order — favorites, live categories, continue, movies, series",
            rowsAll.map { it.kind } == listOf(
                IptvLogic.RowKind.FAVORITES, IptvLogic.RowKind.LIVE, IptvLogic.RowKind.LIVE,
                IptvLogic.RowKind.CONTINUE, IptvLogic.RowKind.MOVIES, IptvLogic.RowKind.SERIES
            ) && rowsAll.map { it.title } ==
                listOf("Favorites", "News", "Sport", "Continue watching", "Movies", "Series"))
        check("iptv: home live card carries the EPG now title + progress",
            rowsAll[1].cards[0].nowTitle == "Evening News" &&
                rowsAll[1].cards[0].progress == 0.5f)
        val rowsLocked = L.homeRows(
            catsHome, emptyList(), emptyList(), setOf("xtream:live:6", "xtream:vod:7")
        )
        check("iptv: home rows drop locked categories (empty category rows never render)",
            rowsLocked.none { it.title == "Sport" } && rowsLocked.none { it.title == "Movies" } &&
                rowsLocked.any { it.title == "News" })
        check("iptv: home rows with no data compose to nothing",
            L.homeRows(IptvLogic.Catalogues(), emptyList(), emptyList(), emptySet()).isEmpty())

        // XMLTV: offsets, entities, CDATA, broken entries.
        val xmltv = listOf(
            "<tv>",
            "<programme start=\"20261022173000 +0000\" stop=\"20261022183000 +0000\" channel=\"ch1\"><title>News &amp; Weather</title></programme>",
            "<programme start=\"20261022183000 +0000\" stop=\"20261022193000 +0000\" channel=\"ch1\"><title><![CDATA[Movie Time]]></title></programme>",
            "<programme start=\"20261022193000 +0200\" stop=\"20261022203000 +0200\" channel=\"ch2\"><title>Late Show</title></programme>",
            "<programme start=\"20261022100000 +0000\" stop=\"20261022090000 +0000\" channel=\"ch1\"><title>Broken</title></programme>",
            "</tv>"
        ).joinToString("\n")
        val epgIndex = IptvEpg.parseXmltv(xmltv)
        check("iptv: xmltv parses channels, unescapes entities, unwraps CDATA, skips broken spans",
            epgIndex["ch1"]?.size == 2 && epgIndex["ch1"]?.get(0)?.title == "News & Weather" &&
                epgIndex["ch1"]?.get(1)?.title == "Movie Time")
        check("iptv: xmltv timezone offsets convert to the same absolute instant",
            epgIndex["ch2"]?.get(0)?.startMs == epgIndex["ch1"]?.get(0)?.startMs &&
                IptvEpg.parseXmltvTime("20261022193000 +0200") ==
                IptvEpg.parseXmltvTime("20261022173000 +0000") &&
                IptvEpg.formatYmdHis(epgIndex["ch1"]!![0].startMs) == "20261022173000")
        val (nowP, nextP) = IptvEpg.nowNext(epgIndex["ch1"], nowMs)
        check("iptv: now/next selection at a fixed clock + progress fraction",
            nowP?.title == "News & Weather" && nextP?.title == "Movie Time" &&
                IptvEpg.progress(nowP!!, nowMs) == 0.5f &&
                IptvEpg.nowNext(epgIndex["ch1"], progStart - 1).first == null &&
                IptvEpg.nowNext(epgIndex["ch1"], progStart - 1).second?.title == "News & Weather")
        check("iptv: short epg — base64 titles, listing times read as UTC",
            IptvEpg.parseShortEpg(
                """{"epg_listings":[{"title":"SGVsbG8gV29ybGQ=","start":"2026-10-22 17:30:00","end":"2026-10-22 18:30:00"}]}""",
                "ch9"
            ).let { it.size == 1 && it[0].title == "Hello World" && it[0].startMs == progStart })

        // Category locks: markers, codecs, stamp rules, the one decision.
        check("iptv: adult markers match case-insensitively",
            IptvLocks.adultLocked("XXX Movies") && IptvLocks.adultLocked("18+ Only") &&
                IptvLocks.adultLocked("Adults") && !IptvLocks.adultLocked("Kids") &&
                !IptvLocks.adultLocked("News"))
        check("iptv: lock store codecs round-trip",
            IptvLocks.decodeKeys(IptvLocks.encodeKeys(setOf("xtream:live:5", "m3u:live:Sport"))) ==
                setOf("xtream:live:5", "m3u:live:Sport") &&
                IptvLocks.decodeStamps(IptvLocks.encodeStamps(mapOf("xtream:live:5" to 123L))) ==
                mapOf("xtream:live:5" to 123L))
        check("iptv: stamp validity — grace window vs session rule",
            IptvLocks.stampValid(1_000L, 5, 1_000L + 4 * 60_000L, 0L) &&
                !IptvLocks.stampValid(1_000L, 5, 1_000L + 6 * 60_000L, 0L) &&
                IptvLocks.stampValid(5_000L, 0, 9_000L, 5_000L) &&
                !IptvLocks.stampValid(4_999L, 0, 9_000L, 5_000L) &&
                !IptvLocks.stampValid(0L, 30, 9_000L, 0L))
        val storedLocks = setOf("xtream:vod:7")
        check("iptv: isLocked — no pattern means nothing locks; stored + adult lock; a valid stamp opens",
            !IptvLocks.isLocked("xtream:vod:7", "Films", storedLocks, emptyMap(), 0, 10_000L, 0L, hasPattern = false) &&
                IptvLocks.isLocked("xtream:vod:7", "Films", storedLocks, emptyMap(), 0, 10_000L, 0L, hasPattern = true) &&
                IptvLocks.isLocked("xtream:vod:9", "XXX Zone", emptySet(), emptyMap(), 0, 10_000L, 0L, hasPattern = true) &&
                !IptvLocks.isLocked("xtream:vod:9", "XXX Zone", emptySet(), mapOf("xtream:vod:9" to 10_000L), 0, 10_500L, 0L, hasPattern = true) &&
                !IptvLocks.isLocked("xtream:vod:7", "Films", emptySet(), emptyMap(), 0, 10_000L, 0L, hasPattern = true))
        check("iptv: hiddenKeys composes the decision over a category list",
            IptvLocks.hiddenKeys(
                listOf(
                    IptvLogic.Category("7", "Films", "xtream", L.KIND_VOD),
                    IptvLogic.Category("8", "Drama", "xtream", L.KIND_SERIES)
                ),
                storedLocks, emptyMap(), 0, 10_000L, 0L, hasPattern = true
            ) == setOf("xtream:vod:7"))

        // Status classification + the diagnostics line (counts only).
        check("iptv: status classify precedence — unconfigured, data beats auth, auth, empty, error",
            IptvStatus.classify(false, false, false, false) == IptvStatus.Kind.NOT_CONFIGURED &&
                IptvStatus.classify(true, false, true, true) == IptvStatus.Kind.OK &&
                IptvStatus.classify(true, true, true, false) == IptvStatus.Kind.AUTH &&
                IptvStatus.classify(true, true, false, false) == IptvStatus.Kind.EMPTY &&
                IptvStatus.classify(true, false, false, false) == IptvStatus.Kind.ERROR)
        check("iptv: diag line carries counts + time, never credentials",
            IptvStatus.diagText(IptvStatus.Kind.OK, 3, 2, 1, 4, 9, "2:41 PM") ==
                "OK · live 3 · vod 2 · series 1 · fav 4 · epg 9 · 2:41 PM" &&
                IptvStatus.diagText(IptvStatus.Kind.NOT_CONFIGURED, 0, 0, 0, 0, 0, "") ==
                "not configured (Settings › IPTV)" &&
                !IptvStatus.diagText(IptvStatus.Kind.AUTH, 0, 0, 0, 0, 0, "")
                    .contains("password="))
        check("iptv: empty-state lines per kind",
            IptvStatus.emptyLine(IptvStatus.Kind.NOT_CONFIGURED) ==
                "Add your IPTV account in Settings › IPTV" &&
                IptvStatus.emptyLine(IptvStatus.Kind.OK) == null &&
                IptvStatus.emptyLine(IptvStatus.Kind.NONE) == null)
        // v2.3.1: the ERROR line names the recorded failure class.
        check("iptv: diag line names the failure class after 'not answering' when one is recorded",
            IptvStatus.diagText(
                IptvStatus.Kind.ERROR, 0, 0, 0, 0, 0, "8:48 PM", "UnknownHostException"
            ) == "not answering (UnknownHostException) — check the server address " +
                "and your network · live 0 · vod 0 · series 0 · fav 0 · 8:48 PM")
        check("iptv: diag line has no parenthetical when no error is recorded",
            IptvStatus.diagText(IptvStatus.Kind.ERROR, 0, 0, 0, 0, 0, "") ==
                "not answering — check the server address and your network" +
                " · live 0 · vod 0 · series 0 · fav 0")
        check("iptv: a recorded error class never leaks into non-error lines",
            !IptvStatus.diagText(IptvStatus.Kind.OK, 1, 2, 3, 4, 0, "", "UnknownHostException")
                .contains("UnknownHostException") &&
                !IptvStatus.diagText(IptvStatus.Kind.AUTH, 0, 0, 0, 0, 0, "", "UnknownHostException")
                    .contains("UnknownHostException"))
    }

    // ---------- (38) v2.3 Watch: title matching, episode resolution, match cache, auto-mark, UI model ----------
    run {
        
        // Normalization: punctuation, articles, case, whitespace.
        check("watch: normalization strips punctuation/articles/case and collapses space",
            WatchMatch.normalizeKey("The Office!") == "office" &&
                WatchMatch.normalizeKey("  BREAKING   BAD ") == "breaking bad" &&
                WatchMatch.normalizeKey("A  Few   Good Men") == "few good men" &&
                WatchMatch.normalizeKey("An American Tail") == "american tail" &&
                WatchMatch.normalizeKey("Grey's Anatomy") == "grey s anatomy")
        // Year extraction: bracketed, trailing, leading; mid-title numbers stay.
        check("watch: splitYear takes bracketed / trailing / leading years only",
            WatchMatch.splitYear("Reacher (2022)") == Pair("Reacher", 2022) &&
                WatchMatch.splitYear("Dune 2021").second == 2021 &&
                WatchMatch.splitYear("2021 Dune").second == 2021 &&
                WatchMatch.splitYear("Apollo 13").second == 0 &&
                WatchMatch.splitYear("The 100").second == 0)
        check("watch: keyOf prefers a known year over the title token",
            WatchMatch.keyOf("Dune (1984)", 2021) == Pair("dune", 2021) &&
                WatchMatch.keyOf("Dune (1984)", 0) == Pair("dune", 1984) &&
                WatchMatch.keyOf("Dune", 0) == Pair("dune", 0))

        val office = WatchMatch.TrackerTitle(2316, "tv", "The Office", 2005)
        val officeCands = listOf(WatchMatch.Candidate(10, "The Office", 2005), WatchMatch.Candidate(11, "Office Space", 1999))
        check("watch: exact normalized match wins, unique + year agree = exact",
            WatchMatch.matchOne(office, officeCands)?.let { it.iptvId == 10L && it.exact && !it.possible } == true)
        check("watch: punctuation/case variant still exact-matches",
            WatchMatch.matchOne(
                WatchMatch.TrackerTitle(1, "tv", "GREY'S ANATOMY!", 2005),
                listOf(WatchMatch.Candidate(5, "Grey's Anatomy", 2005))
            )?.let { it.iptvId == 5L && it.exact } == true)
        check("watch: year breaks a same-name tie (remakes)",
            WatchMatch.matchOne(
                WatchMatch.TrackerTitle(2, "movie", "Dune", 2021),
                listOf(WatchMatch.Candidate(20, "Dune", 1984), WatchMatch.Candidate(21, "Dune", 2021))
            )?.let { it.iptvId == 21L && it.exact } == true)
        check("watch: ambiguous same-name (no usable year) picks first, flagged possible",
            WatchMatch.matchOne(
                WatchMatch.TrackerTitle(3, "movie", "Dune", 0),
                listOf(WatchMatch.Candidate(20, "Dune", 1984), WatchMatch.Candidate(21, "Dune", 2021))
            )?.let { it.iptvId == 20L && !it.exact && it.possible } == true)
        check("watch: year conflict (both known, different) is possible, not exact",
            WatchMatch.matchOne(
                WatchMatch.TrackerTitle(4, "movie", "Dune", 2000),
                listOf(WatchMatch.Candidate(20, "Dune", 1984))
            )?.let { it.iptvId == 20L && !it.exact && it.possible } == true)
        check("watch: fuzzy tier (word-boundary prefix) matches but is only possible",
            WatchMatch.matchOne(
                WatchMatch.TrackerTitle(5, "tv", "Star Trek", 1966),
                listOf(WatchMatch.Candidate(30, "Star Trek: The Next Generation", 1987))
            )?.let { it.iptvId == 30L && !it.exact && it.possible } == true)
        check("watch: exact tier beats the fuzzy tier outright",
            WatchMatch.matchOne(
                WatchMatch.TrackerTitle(6, "tv", "Star Trek", 1966),
                listOf(
                    WatchMatch.Candidate(30, "Star Trek: The Next Generation", 1987),
                    WatchMatch.Candidate(31, "Star Trek", 1966)
                )
            )?.let { it.iptvId == 31L && it.exact } == true)
        check("watch: no close title -> no match (never a forced guess)",
            WatchMatch.matchOne(
                WatchMatch.TrackerTitle(7, "tv", "Reacher", 2022),
                listOf(WatchMatch.Candidate(40, "Preacher", 2016), WatchMatch.Candidate(41, "Teachers", 2016))
            ) == null &&
                WatchMatch.matchOne(office, emptyList()) == null)
        check("watch: matchAll keys by tmdbId and skips unmatched",
            WatchMatch.matchAll(listOf(office, WatchMatch.TrackerTitle(8, "tv", "Reacher", 2022)), officeCands)
                .let { it.size == 1 && it[2316]?.iptvId == 10L })

        // Episode resolution against a parsed series-info fixture.
        val infoJson = """{"info":{"name":"Reacher"},"seasons":[1,2],"episodes":{
            "1":[{"id":"9001","season":1,"episode_num":1,"title":"Welcome","container_extension":"mkv"}],
            "2":[{"id":"9002","season":2,"episode_num":3,"title":"Picture","container_extension":"mp4"}]}}"""
        val info = IptvLogic.parseSeriesInfo(infoJson, 77)
        check("watch: episode resolution finds id + extension",
            info != null &&
                WatchMatch.resolveEpisode(info, 2, 3)?.let { it.id == "9002" && it.ext == "mp4" } == true &&
                WatchMatch.resolveEpisode(info, 1, 1)?.id == "9001")
        check("watch: missing season / missing episode resolve to null (fail-open)",
            info != null && WatchMatch.resolveEpisode(info, 3, 1) == null &&
                WatchMatch.resolveEpisode(info, 2, 9) == null)

        // Match cache: fingerprint invalidation + codec.
        check("watch: fingerprint is stable for same inputs, moves on catalogue or tracker change",
            WatchMatch.fingerprint(10, 20, 111L, 222L, 7) == WatchMatch.fingerprint(10, 20, 111L, 222L, 7) &&
                WatchMatch.fingerprint(11, 20, 111L, 222L, 7) != WatchMatch.fingerprint(10, 20, 111L, 222L, 7) &&
                WatchMatch.fingerprint(10, 20, 112L, 222L, 7) != WatchMatch.fingerprint(10, 20, 111L, 222L, 7) &&
                WatchMatch.fingerprint(10, 20, 111L, 223L, 7) != WatchMatch.fingerprint(10, 20, 111L, 222L, 7) &&
                WatchMatch.fingerprint(10, 20, 111L, 222L, 8) != WatchMatch.fingerprint(10, 20, 111L, 222L, 7))
        check("watch: match codec round-trips ids + flags, skips garbage, stores no titles",
            WatchMatch.decodeMatches(
                WatchMatch.encodeMatches(
                    listOf(
                        WatchMatch.Match(2316, "tv", 10, exact = true, possible = false),
                        WatchMatch.Match(99, "movie", 21, exact = false, possible = true)
                    )
                )
            ).let {
                it.size == 2 && it[0].tmdbId == 2316L && it[0].exact && !it[0].possible &&
                    it[1].iptvId == 21L && it[1].possible
            } &&
                WatchMatch.decodeMatches("garbage\n1|tv|x|1|0\n5|tv|6|1").isEmpty() &&
                !WatchMatch.encodeMatches(listOf(WatchMatch.Match(1, "tv", 2, true, false))).contains("Office"))

        // Auto-mark decision.
        check("watch: auto-mark fires on ended (even at low position)",
            WatchMatch.shouldAutoMark(true, false, ended = true, posMs = 1_000, durMs = 100_000))
        check("watch: auto-mark fires at exactly 90%, not at 89%",
            WatchMatch.shouldAutoMark(true, false, ended = false, posMs = 90_000, durMs = 100_000) &&
                !WatchMatch.shouldAutoMark(true, false, ended = false, posMs = 89_000, durMs = 100_000))
        check("watch: auto-mark never fires twice, never tokenless, never on a partial watch",
            !WatchMatch.shouldAutoMark(true, true, ended = true, posMs = 100_000, durMs = 100_000) &&
                !WatchMatch.shouldAutoMark(false, false, ended = true, posMs = 100_000, durMs = 100_000) &&
                !WatchMatch.shouldAutoMark(false, false, ended = false, posMs = 95_000, durMs = 100_000) &&
                !WatchMatch.shouldAutoMark(true, false, ended = false, posMs = 40_000, durMs = 100_000) &&
                !WatchMatch.shouldAutoMark(true, false, ended = false, posMs = 0, durMs = 0))

        // UI model: badge + hero labels + diagnostics.
        check("watch: badge only when matched; hero labels name the episode, movies plain",
            WatchMatch.cardBadge(true) == "▶ IPTV" && WatchMatch.cardBadge(false) == null &&
                WatchMatch.heroPlayLabel("tv", 2, 3) == "▶ Play S2 E3" &&
                WatchMatch.heroPlayLabel("movie", 0, 0) == "▶ Play" &&
                WatchMatch.heroPlayLabel("tv", 0, 0) == "▶ Play")
        check("watch: diag line is counts-only",
            WatchMatch.diagText(WatchMatch.Stats(3, 5, 2, 4, 7)) ==
                "shows 3/5 · movies 2/4 · episodes resolved 7")
    }

    // ---------- (39) v2.3.1 hotfix: source checks (manifest cleartext + the one true version label) ----------
    run {
        // These read the app sources relative to the project root —
        // run_sim.sh always runs the sim from there.
        val manifest = try {
            java.io.File("app/src/main/AndroidManifest.xml").readText()
        } catch (e: Exception) {
            ""
        }
        check("hotfix: manifest permits cleartext HTTP (Xtream panels / M3U / XMLTV are http://)",
            manifest.contains("android:usesCleartextTraffic=\"true\""))
        val settingsSrc = try {
            java.io.File(
                "app/src/main/java/com/mizan/launcher/ui/SettingsActivity.kt"
            ).readText()
        } catch (e: Exception) {
            ""
        }
        check("hotfix: no hardcoded 'Sreeb v<digit>' label remains in SettingsActivity",
            settingsSrc.isNotEmpty() &&
                !Regex("Sreeb v[0-9]").containsMatchIn(settingsSrc))
        check("hotfix: the version label is read from PackageManager at runtime",
            settingsSrc.contains("getPackageInfo(packageName, 0).versionName") &&
                settingsSrc.contains("appVersionLabel()"))
    }

    println("RESULT: $pass passed, $fail failed, ${pass + fail} total")
    if (fail > 0) kotlin.system.exitProcess(1)
}
