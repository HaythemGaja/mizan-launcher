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
import com.mizan.launcher.core.ArrowPinAutoSubmit
import com.mizan.launcher.core.ArrowPinSetupFlow
import com.mizan.launcher.core.ArrowPinSetupResult
import com.mizan.launcher.core.FolderGate
import com.mizan.launcher.core.FolderLogic
import com.mizan.launcher.core.GridMove
import com.mizan.launcher.core.SettingsFit
import com.mizan.launcher.core.SettingsNavMachine
import com.mizan.launcher.core.SwitchLogic
import com.mizan.launcher.core.TileLayout
import com.mizan.launcher.core.TrackerSlim
import com.mizan.launcher.core.TrackerStore
import com.mizan.launcher.core.VisibleWindow
import com.mizan.launcher.core.SortGrab
import com.mizan.launcher.core.ColdChain
import com.mizan.launcher.core.DeathCapture
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
import com.mizan.launcher.core.IptvStore
import com.mizan.launcher.core.IptvStream
import com.mizan.launcher.core.IptvM3uStream
import com.mizan.launcher.core.WatchMatch
import com.mizan.launcher.core.Discovery
import com.mizan.launcher.core.BreadcrumbRing
import com.mizan.launcher.core.DiagLogs
import com.mizan.launcher.core.DiagStartup
import com.mizan.launcher.core.DiagWatchdog
import com.mizan.launcher.core.HomeTabs
import com.mizan.launcher.core.Artwork
import com.mizan.launcher.core.DiagScrub
import com.mizan.launcher.core.UnifiedBar
import com.mizan.launcher.core.PlayerLogic
import com.mizan.launcher.core.JsonMini
import com.mizan.launcher.core.StartupHealth
import com.mizan.launcher.core.SectionGuard
import com.mizan.launcher.core.HomeCache
import com.mizan.launcher.core.SectionModule
import com.mizan.launcher.core.PlayInLogic
import com.mizan.launcher.core.RefreshPolicy
import com.mizan.launcher.core.StartupStages
import com.mizan.launcher.core.HomeFallback
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
        // v3.1 RE-PINNED (ask 37): the input still mentions movienight —
        // normalize drops it on BOTH sides now, so the round-trip holds.
        check("sections: codec round-trips a custom config",
            ts.decode(ts.encode(ts.normalize(setOf("foryou"), listOf("foryou", "upnext", "continue", "movienight", "favorites", "allapps")))) ==
                ts.normalize(setOf("foryou"), listOf("foryou", "upnext", "continue", "movienight", "favorites", "allapps")))
        check("sections: garbage decodes to defaults",
            ts.decode("nonsense||,,") == TrackerSections.Config() && ts.decode("") == TrackerSections.Config())
        val noHero = ts.setShown(defCfg, ts.HERO, false)
        check("sections: a hidden hero drops only the hero",
            ts.compose(noHero) { true } == ts.CANONICAL.drop(1))
        val moved = ts.move(defCfg, ts.FORYOU, -1)
        // RE-PINNED in v2.7: the default order intentionally
        // changed (directive: comingsoon after upnext, trending
        // after continue) — the swap happens over the longer list.
        // v3.3 RE-PINNED (ask 47): foryou's predecessor is now
        // trendingmovies (movienight gone in v3.1, comingsoon
        // merged into upnext in v3.3), so the swap lands there.
        // v3.4 RE-PINNED (ask 51): folders joins the canonical
        // tail (after favorites, before allapps).
        // v3.7 RE-PINNED (Missed row): Missed joins ROWS right
        // after Up Next, so the foryou swap happens over the
        // longer list.
        check("sections: move up swaps with the previous row",
            moved.order == listOf("upnext", "missed", "continue", "trendingtv", "foryou", "trendingmovies", "favorites", "folders", "allapps"))
        check("sections: moves at the edges and for the hero are no-ops",
            ts.move(defCfg, ts.UPNEXT, -1) == defCfg &&
                ts.move(defCfg, ts.ALLAPPS, 1) == defCfg &&
                ts.move(defCfg, ts.HERO, 1) == defCfg)
        val reordered = ts.normalize(emptySet(), listOf("allapps", "upnext"))
        // RE-PINNED in v2.7 (new sections in the canonical tail).
        check("sections: compose follows the configured order, hero first",
            ts.compose(reordered) { true } ==
                // v3.3 RE-PINNED (ask 47): comingsoon merged into upnext.
                // v3.4 RE-PINNED (ask 51): folders appends in its canonical slot.
                // v3.7 RE-PINNED (Missed row): Missed composes after Up Next.
                listOf("hero", "allapps", "upnext", "missed", "continue", "trendingtv", "trendingmovies", "foryou", "favorites", "folders"))
        // RE-PINNED in v2.7 (new sections compose when available).
        // v3.1 RE-PINNED (ask 37): MOVIENIGHT is gone, so the data-empty
        // gate uses foryou; hidden upnext + gated foryou both drop.
        check("sections: hidden + data-empty sections are both skipped",
            ts.compose(ts.setShown(defCfg, ts.UPNEXT, false)) { it != ts.FORYOU } ==
                // v3.4 RE-PINNED (ask 51): folders composes in its canonical slot.
                // v3.7 RE-PINNED (Missed row): Missed composes after the hero.
                listOf("hero", "missed", "continue", "trendingtv", "trendingmovies", "favorites", "folders", "allapps"))
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
        // v3.1 RE-PINNED (ask 41): an internal Sort sections target
        // joins the table (second-to-last); system targets unchanged.
        // v3.2 RE-PINNED (ask 43): a second internal target,
        // "Refresh home data", joins between Sort sections and
        // Sreeb Settings; system targets unchanged.
        check("quicksettings: six system targets, then Sort sections, Refresh home data, Sreeb Settings",
            QuickSettings.TARGETS.size == 9 &&
                QuickSettings.TARGETS.last().label == "Sreeb Settings" &&
                QuickSettings.TARGETS.last().action.isEmpty() &&
                QuickSettings.TARGETS[6].label == "Sort sections" &&
                QuickSettings.TARGETS[6].action == "sreeb:sortsections" &&
                QuickSettings.TARGETS[7].label == "Refresh home data" &&
                QuickSettings.TARGETS[7].action == "sreeb:refreshhome" &&
                QuickSettings.TARGETS.take(6).all { it.action.startsWith("android.settings.") } &&
                QuickSettings.TARGETS.take(6).map { it.action }.toSet().size == 6)
        check("quicksettings: unresolvable targets are filtered, internal + Sreeb Settings stay",
            QuickSettings.visible(QuickSettings.TARGETS) { it.contains("WIFI") }.map { it.label } ==
                listOf("Wi-Fi", "Sort sections", "Refresh home data", "Sreeb Settings"))

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
                ll.tmdbErrorKind("io:UnknownHostException") == LibraryLogic.TmdbError.IO &&
                ll.tmdbErrorKind("http_503") == LibraryLogic.TmdbError.HTTP &&
                ll.tmdbHttpCode("http_503") == 503 && ll.tmdbHttpCode("io") == 0 &&
                ll.tmdbErrorKind("weird") == LibraryLogic.TmdbError.UNKNOWN)
        check("tmdb: retryable = http/io/unknown only",
            ll.tmdbRetryable("http_500") && ll.tmdbRetryable("io") &&
                ll.tmdbRetryable("weird") && !ll.tmdbRetryable("no_key") &&
                !ll.tmdbRetryable("bad_path") && !ll.tmdbRetryable(null))
        // v3.7 RE-PINNED (launcher IS the tracker): the no_key
        // fix names Sreeb Settings, not the tracker app.
        check("tmdb: user lines (no_key names the fix; http carries the code)",
            ll.tmdbUserLine("no_key").contains("TMDB key") &&
                ll.tmdbUserLine("no_key").contains("Sreeb Settings") &&
                ll.tmdbUserLine("http_404").contains("404") &&
                ll.tmdbUserLine("io").contains("reach TMDB") &&
                ll.tmdbUserLine(null) == "")
        check("tmdb: diagnostics wording (v2.6: path + human text + raw token in parens)",
            ll.tmdbDiagText(null, null) == "not used yet" &&
                ll.tmdbDiagText("ok", null) == "OK via tracker" &&
                // v3.7 RE-PINNED: the key lives in the launcher now.
                ll.tmdbDiagText("no_key", null) == "no launcher key set (no_key)" &&
                ll.tmdbDiagText("bad_path", null) == "bad path (bad_path)" &&
                ll.tmdbDiagText("http_500", null) == "http 500 (http_500)" &&
                ll.tmdbDiagText("io", null) == "network error (io)" &&
                ll.tmdbDiagText("io:UnknownHostException", null) ==
                    "network error (io:UnknownHostException)")

        // --- poster cache codec ---
        val cache = mapOf("tv|125988" to "/silo.jpg", "movie|693134" to "/dune.jpg")
        check("poster cache: codec round-trips, garbage dropped",
            ll.decodePosterCache(ll.encodePosterCache(cache)) == cache &&
                ll.decodePosterCache("tv|x|/a.jpg\nmovie|5").isEmpty() &&
                ll.decodePosterCache(null).isEmpty())

        // --- Movie parsing hardening (v2.1; v3.1: the section is gone, the parse stays) ---
        // must still parse — a bridge serializing ids as strings
        // would otherwise silently empty the whole row. ---
        val stringIdMovies =
            """[{"tmdbId":"303","title":"Strung","releaseDate":"2024-03-03","planned":true,"poster":"/s.jpg","watched":false}]"""
        check("movies: numeric-string tmdbId still parses",
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
        val hPicks = listOf(
            TrackerLogic.ForYouPick(6, "tv", "P-Art", "/p6.jpg", 7.5, "Because you watched X", listOf("Drama"), 9.0),
            TrackerLogic.ForYouPick(7, "movie", "P-NoArt", "/p7.jpg", 6.0, "Trending", emptyList(), 5.0)
        )
        // v3.1 RE-PINNED (hero schedule): the pool is the schedule —
        // both Up Next dates precede today, so both are missed,
        // most overdue first; Continue/For You only join an EMPTY
        // schedule (pinned in section 51).
        val pool = TrackerLogic.heroCandidates(
            hUp, hCont, hPicks, mapOf("tv|6" to "/p6-back.jpg"), "2026-11-03"
        )
        check("hero pool: missed first, most overdue first (backdrop never reorders)",
            pool.map { it.title } == listOf("U-NoArt", "U-Art"),
            pool.map { it.title }.toString())
        check("hero pool: missed heroes carry the Missed source + label with date chip",
            pool.map { it.source } == listOf("Missed", "Missed") &&
                pool[0].sub == "Missed · S1 E1 · Nov 1" &&
                pool[1].sub == "Missed · S1 E2 · Nov 2",
            pool.map { it.sub }.toString())
        val fallbackPool = TrackerLogic.heroCandidates(
            emptyList(), hCont, hPicks, mapOf("tv|6" to "/p6-back.jpg"), "2026-11-03"
        )
        check("hero pool: empty schedule falls back to Continue (backdrop-first) + For You (backdrop-gated)",
            fallbackPool.map { it.title } == listOf("C-Art", "P-Art") &&
                fallbackPool.map { it.source } == listOf("Continue Watching", "For You") &&
                fallbackPool.first { it.title == "P-Art" }.backdrop == "/p6-back.jpg" &&
                fallbackPool.first { it.title == "P-Art" }.sub == "Because you watched X" &&
                fallbackPool.none { it.title == "P-NoArt" })
        // v3.1 DELETED (ask 37): movie heroes no longer exist — the
        // pool is Up Next → Continue → For You, and movies data
        // itself still parses (see the numeric-string check above).
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
        
        // Normalization — RE-PINNED in v2.7: the repo method wins
        // (user directive). The key is the SQUASH: lowercase, every
        // non-[a-z0-9] character stripped — articles, spaces and
        // punctuation all vanish into one token.
        check("watch: normalization is the repo squash (articles/spaces/punctuation vanish)",
            WatchMatch.normalizeKey("The Office!") == "theoffice" &&
                WatchMatch.normalizeKey("  BREAKING   BAD ") == "breakingbad" &&
                WatchMatch.normalizeKey("A  Few   Good Men") == "afewgoodmen" &&
                WatchMatch.normalizeKey("An American Tail") == "anamericantail" &&
                WatchMatch.normalizeKey("Grey's Anatomy") == "greysanatomy" &&
                WatchMatch.squash("Dune 2021 1080p") == "dune20211080p")
        // Year extraction: bracketed, trailing, leading; mid-title numbers stay.
        check("watch: splitYear takes bracketed / trailing / leading years only",
            WatchMatch.splitYear("Reacher (2022)") == Pair("Reacher", 2022) &&
                WatchMatch.splitYear("Dune 2021").second == 2021 &&
                WatchMatch.splitYear("2021 Dune").second == 2021 &&
                WatchMatch.splitYear("Apollo 13").second == 0 &&
                WatchMatch.splitYear("The 100").second == 0)
        // RE-PINNED in v2.7 (repo method wins): the key no longer
        // strips the year token — "Dune (1984)" squashes whole;
        // the year rides alongside for in-tier ordering only.
        check("watch: keyOf prefers a known year over the title token (key is the whole squash)",
            WatchMatch.keyOf("Dune (1984)", 2021) == Pair("dune1984", 2021) &&
                WatchMatch.keyOf("Dune (1984)", 0) == Pair("dune1984", 1984) &&
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
        // RE-PINNED in v2.7 (repo method wins): the old fuzzy tier
        // is now the CONTAINS tier. This fixture still flags
        // possible — not for the tier, but for the YEAR CONFLICT
        // (1966 vs 1987). A single-distinct contains hit with no
        // year conflict is a plain match (§45 pins that).
        check("watch: contains tier matches a decorated name; a year conflict flags possible",
            WatchMatch.matchOne(
                WatchMatch.TrackerTitle(5, "tv", "Star Trek", 1966),
                listOf(WatchMatch.Candidate(30, "Star Trek: The Next Generation", 1987))
            )?.let { it.iptvId == 30L && !it.exact && it.possible } == true)
        check("watch: exact tier beats the contains tier outright",
            WatchMatch.matchOne(
                WatchMatch.TrackerTitle(6, "tv", "Star Trek", 1966),
                listOf(
                    WatchMatch.Candidate(30, "Star Trek: The Next Generation", 1987),
                    WatchMatch.Candidate(31, "Star Trek", 1966)
                )
            )?.let { it.iptvId == 31L && it.exact } == true)
        // RE-PINNED in v2.7 (repo method wins, user directive):
        // the repo's contains tier is crude by design — "preacher"
        // DOES contain "reacher", so this now MATCHES where the
        // old word-boundary fuzzy tier refused. The empty-catalogue
        // case still yields no match.
        check("watch: repo contains tier matches 'Preacher' for 'Reacher' (crude by design); empty catalogue -> no match",
            WatchMatch.matchOne(
                WatchMatch.TrackerTitle(7, "tv", "Reacher", 2022),
                listOf(WatchMatch.Candidate(40, "Preacher", 2016), WatchMatch.Candidate(41, "Teachers", 2016))
            )?.let { it.iptvId == 40L && !it.exact && it.possible } == true &&
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

    // ---------- (40) v2.3.2 hotfix: IPTV views bounded + fail-open on real panel data ----------
    run {
        val L = IptvLogic
        // An oversized real-panel-shaped fixture: 5,000 live in ONE
        // category, 5,000 movies, 60 favorites. (Sim fixtures were
        // all toy-sized until the real panel arrived on-device and
        // the unbounded views blacked out.)
        val bigLive = (1..5_000).map { i ->
            IptvLogic.LiveChannel(
                i.toLong(), "Channel $i", "", "1", "General",
                "epg$i", "xtream", "", 0, 0
            )
        }
        val bigVods = (1..5_000).map { i ->
            IptvLogic.VodItem(i.toLong(), "Movie $i", "", "1", "Films", "mkv", "")
        }
        val bigCats = IptvLogic.Catalogues(
            liveCats = listOf(IptvLogic.Category("1", "General", "xtream", L.KIND_LIVE)),
            live = bigLive,
            vodCats = listOf(IptvLogic.Category("1", "Films", "xtream", L.KIND_VOD)),
            vods = bigVods
        )
        val bigFavs = (1..60).map { L.favOf(bigVods[it - 1]) }

        // (a) The home composition fails OPEN: a hostile EPG index
        // (its get() throws) yields no rows via the fail-open seam —
        // the home view then renders its safe shell — never a throw.
        val hostileEpg = object : HashMap<String, List<IptvEpg.Programme>>() {
            override fun get(key: String): List<IptvEpg.Programme>? =
                throw IllegalStateException("hostile index")
        }
        hostileEpg["decoy"] = emptyList() // non-empty, so the EPG path is really entered
        check("iptv232: hostile EPG index — homeRowsOrEmpty fails open to no rows",
            L.homeRowsOrEmpty(bigCats, emptyList(), emptyList(), emptySet(), hostileEpg, 1_000L)
                .isEmpty())
        check("iptv232: the same composition with a sane EPG still composes rows",
            L.homeRowsOrEmpty(bigCats, emptyList(), emptyList(), emptySet(), emptyMap(), 1_000L)
                .isNotEmpty())

        // (b) Caps hold on the oversized catalogue.
        val bigRows = L.homeRows(bigCats, bigFavs, emptyList(), emptySet())
        check("iptv232: home rows stay capped on a 5,000-entry catalogue (live + movies ≤ ROW_CARD_CAP)",
            bigRows.first { it.kind == IptvLogic.RowKind.LIVE }.cards.size == L.ROW_CARD_CAP &&
                bigRows.first { it.kind == IptvLogic.RowKind.MOVIES }.cards.size == L.ROW_CARD_CAP)
        check("iptv232: the favorites row is capped too (60 stored → ROW_CARD_CAP)",
            bigRows.first { it.kind == IptvLogic.RowKind.FAVORITES }.cards.size == L.ROW_CARD_CAP)
        val fatRow = IptvLogic.HomeRow(
            IptvLogic.RowKind.LIVE, "Fat",
            (1..50).map { i ->
                IptvLogic.Card(L.KIND_LIVE, "k$i", "$i", "C$i", "", "", "", -1f, "", "xtream", "", "")
            }
        )
        check("iptv232: the home VIEW cap trims any row to HOME_ROW_VIEW_CAP (20)",
            L.homeRowCards(fatRow).size == 20 && L.HOME_ROW_VIEW_CAP == 20)

        // The browser page: filter (the catalogue tab's own filter)
        // then page — 5,000 in the category, 200 rendered, total kept.
        val catItems = bigCats.live.filter { it.catKey == "xtream:live:1" }
        val catPage = L.gridPage(catItems)
        check("iptv232: browser grid page caps a 5,000-channel category at GRID_CAP (200), total preserved",
            catPage.items.size == 200 && catPage.total == 5_000 && catPage.truncated &&
                L.GRID_CAP == 200)
        val searchPage = L.gridPage(L.search(bigCats, "channel", emptySet()).live)
        check("iptv232: search results page the same way (5,000 matches → 200 shown)",
            searchPage.items.size == 200 && searchPage.total == 5_000 && searchPage.truncated)
        val smallPage = L.gridPage(listOf(1, 2, 3))
        check("iptv232: a list under the cap pages whole and untruncated",
            smallPage.items.size == 3 && smallPage.total == 3 && !smallPage.truncated)

        // Source checks: the guards + caps are wired into the views.
        fun src(path: String): String = try {
            java.io.File(path).readText()
        } catch (e: Exception) {
            ""
        }
        val browserSrc = src("app/src/main/java/com/mizan/launcher/ui/IptvActivity.kt")
        check("iptv232: the browser rebuild is guarded and records as iptvbrowser",
            browserSrc.contains("noteError(this, \"iptvbrowser\"") &&
                browserSrc.contains("renderBrowserError"))
        check("iptv232: the browser pages its grids through gridPage",
            browserSrc.contains("IptvLogic.gridPage"))
        val guideSrc = src("app/src/main/java/com/mizan/launcher/ui/IptvGuideActivity.kt")
        check("iptv232: the guide rebuild is guarded, records as iptvguide, and pages its rows",
            guideSrc.contains("noteError(this, \"iptvguide\"") &&
                guideSrc.contains("IptvLogic.gridPage"))
        val mainSrc = src("app/src/main/java/com/mizan/launcher/MainActivity.kt")
        check("iptv232: the home build records iptvhome and renders rows through homeRowCards",
            mainSrc.contains("noteError(this, \"iptvhome\"") &&
                mainSrc.contains("IptvLogic.homeRowCards(row)"))
    }

    // ---------- (41) v2.4: disk store, streaming ingest parsers, slices, swap rule ----------
    run {
        val L = IptvLogic
        val S = IptvStore

        // --- streaming JSON: element-for-element equivalence with
        // the whole-document parsers (same row mappers).
        val liveJson = """[
          {"stream_id": 101, "name": "News One", "stream_icon": "http://x/n.png", "category_id": "1", "epg_channel_id": "news.tv", "tv_archive": 1, "tv_archive_duration": 3},
          {"stream_id": "102", "name": "Sport | Two %", "stream_icon": "", "category_id": 1, "epg_channel_id": "", "tv_archive": 0},
          {"stream_id": 0, "name": "Dead", "category_id": "1"},
          {"stream_id": 103, "name": "", "category_id": "1"},
          "a stray scalar",
          {"stream_id": 104, "name": "Films\nTonight", "category_id": "9", "epg_channel_id": "films.tv"}
        ]"""
        val catNames = mapOf("1" to "General", "9" to "Movies")
        val streamed = ArrayList<IptvLogic.LiveChannel>()
        val delivered = IptvStream.streamArray(java.io.StringReader(liveJson)) { row ->
            L.liveFromRow(row, catNames)?.let { streamed.add(it) }
        }
        check("iptvstore: streamArray delivers every object element (5 objects; the stray scalar is consumed + skipped)",
            delivered == 5, "delivered=$delivered")
        check("iptvstore: streamed rows equal the whole-document parse",
            streamed == L.parseLive(liveJson, catNames),
            "streamed=$streamed whole=${L.parseLive(liveJson, catNames)}")
        val streamThrows = try {
            IptvStream.streamArray(java.io.StringReader("{\"not\":\"an array\"}")) { }
            false
        } catch (e: Exception) {
            true
        }
        check("iptvstore: streamArray rejects a non-array document (ingest keeps the old store)",
            streamThrows)

        // --- M3U: the line-fed stream parser == the document parser.
        val m3u = "#EXTM3U\n" +
            "#EXTINF:-1 tvg-id=\"a.tv\" tvg-logo=\"http://x/a.png\" group-title=\"News\",Alpha\n" +
            "http://s/a.m3u8\n" +
            "#EXTINF:-1 group-title=\"News\",Beta\n" +
            "http://s/b.m3u8\n" +
            "#EXTINF:-1,No Group\n" +
            "http://s/c.m3u8\n"
        val m3uStream = IptvM3uStream()
        val m3uChans = ArrayList<IptvLogic.LiveChannel>()
        for (line in m3u.lineSequence()) m3uStream.feed(line)?.let { m3uChans.add(it) }
        val m3uWhole = L.parseM3u(m3u)
        check("iptvstore: M3U stream parse equals the document parse (cats + channels)",
            m3uStream.categories == m3uWhole.first && m3uChans == m3uWhole.second,
            "stream=${m3uStream.categories}/$m3uChans whole=$m3uWhole")

        // --- line codecs round-trip (incl. codec-hostile text).
        val live1 = IptvLogic.LiveChannel(
            42L, "A | B % C\nD", "http://x/i.png", "7", "Cat | One",
            "epg.tv", L.SRC_XTREAM, "", 1, 2
        )
        check("iptvstore: live line round-trips codec-hostile fields",
            S.decodeLive(S.encodeLive(live1)) == live1)
        val liveM = IptvLogic.LiveChannel(
            7L, "M3U Chan", "", "News", "News", "m.tv",
            L.SRC_M3U, "http://s/x.m3u8?u=1&p=2", 0, 0
        )
        check("iptvstore: M3U live line round-trips (direct URL survives)",
            S.decodeLive(S.encodeLive(liveM)) == liveM)
        val vod1 = IptvLogic.VodItem(9L, "Movie | One", "http://x/p.jpg", "3", "Films", "mkv", "8.5", 2021)
        check("iptvstore: vod line round-trips incl. year",
            S.decodeVod(S.encodeVod(vod1)) == vod1)
        val series1 = IptvLogic.SeriesItem(11L, "Show", "http://x/c.jpg", "4", "Drama", "9.1", "Plot | with % signs", 1999)
        check("iptvstore: series line round-trips",
            S.decodeSeries(S.encodeSeries(series1)) == series1)
        val longPlot = IptvLogic.SeriesItem(12L, "Long", "", "4", "Drama", "", "x".repeat(5000), 2020)
        check("iptvstore: series plots are capped at PLOT_CAP in the store line",
            (S.decodeSeries(S.encodeSeries(longPlot))?.plot?.length ?: -1) == S.PLOT_CAP)
        val prog1 = IptvEpg.Programme("ch.tv", 1_700_000_000_000L, 1_700_000_900_000L, "Title | %")
        check("iptvstore: epg line round-trips",
            S.decodeEpg(S.encodeEpg(prog1)) == prog1)
        check("iptvstore: garbage lines decode to null, never throw",
            S.decodeLive("not a line") == null && S.decodeVod("||") == null &&
                S.decodeSeries("") == null && S.decodeEpg("a|b|c") == null)

        // --- index codec round-trip + counts + size text.
        val idx = IptvStore.Index(
            fetchedAt = 1_700_000_000_000L, fingerprint = -12345,
            liveCount = 5000, vodCount = 3000, seriesCount = 2000,
            liveBytes = 2_000_000L, vodBytes = 3_000_000L, seriesBytes = 1_500_000L,
            epgChannels = 42, epgAt = 1_700_000_100_000L,
            cats = listOf(
                IptvStore.CatCount("1", "General | %", "xtream", L.KIND_LIVE, 5000),
                IptvStore.CatCount("2", "Films", "xtream", L.KIND_VOD, 3000),
                IptvStore.CatCount("News", "News", "m3u", L.KIND_LIVE, 25)
            )
        )
        val idxBack = S.decodeIndex(S.encodeIndex(idx))
        check("iptvstore: index round-trips (stamps, counts, byte sizes, hostile cat names)",
            idxBack == idx, "back=$idxBack")
        check("iptvstore: index serves categories + per-category counts by kind",
            idxBack?.categories(L.KIND_LIVE)?.size == 2 &&
                idxBack?.countForCat("xtream:live:1") == 5000 &&
                idxBack?.countOf(L.KIND_VOD) == 3000 &&
                idxBack?.hasData == true)
        check("iptvstore: sizeText renders B / KB / MB honestly",
            S.sizeText(0L) == "store 0 B" && S.sizeText(512L) == "store 512 B" &&
                S.sizeText(2048L) == "store 2 KB" &&
                S.sizeText(6_500_000L).startsWith("store 6.2 MB"),
            S.sizeText(6_500_000L))

        // --- slices over a 5,000-entry single-category file.
        val bigLines = (1..5_000).map { i ->
            S.encodeLive(
                IptvLogic.LiveChannel(i.toLong(), "Channel $i", "", "1", "General", "", L.SRC_XTREAM, "", 0, 0)
            )
        }
        val page1 = S.sliceLive(bigLines.asSequence(), "xtream:live:1", null, emptySet(), 0, 200, 5_000)
        check("iptvstore: slice window [0,200) of 5,000 is exact, total from the index",
            page1.items.size == 200 && page1.items.first().id == 1L &&
                page1.items.last().id == 200L && page1.total == 5_000)
        val pageTail = S.sliceLive(bigLines.asSequence(), "xtream:live:1", null, emptySet(), 4_800, 200, 5_000)
        check("iptvstore: slice window at the tail is exact (ids 4801..5000)",
            pageTail.items.size == 200 && pageTail.items.first().id == 4801L &&
                pageTail.items.last().id == 5000L && pageTail.total == 5_000)
        val mixedLines = (1..4_000).map { i ->
            val cat = if (i % 2 == 0) "2" else "1"
            S.encodeLive(
                IptvLogic.LiveChannel(i.toLong(), "Channel $i", "", cat, "C$cat", "", L.SRC_XTREAM, "", 0, 0)
            )
        }
        val catSlice = S.sliceLive(mixedLines.asSequence(), "xtream:live:2", null, emptySet(), 0, 10, -1)
        check("iptvstore: category slice counts its own total (2,000 of 4,000)",
            catSlice.total == 2_000 && catSlice.items.size == 10 &&
                catSlice.items.all { it.categoryId == "2" })
        val exclSlice = S.sliceLive(
            mixedLines.asSequence(), null, null, setOf("xtream:live:1"), 0, 10, 2_000
        )
        check("iptvstore: excluded-category slice skips hidden cats, index-adjusted total",
            exclSlice.total == 2_000 && exclSlice.items.all { it.categoryId == "2" })
        val querySlice = S.sliceLive(bigLines.asSequence(), null, "channel 499", emptySet(), 0, 200, -1)
        check("iptvstore: query slice counts matches across the whole file (499 + 4990..4999 = 11)",
            querySlice.total == 11 && querySlice.items.size == 11,
            "total=${querySlice.total}")
        val vodLines = (1..3_000).map { i ->
            S.encodeVod(IptvLogic.VodItem(i.toLong(), "Movie $i", "", "1", "Films", "mkv", "", 2000))
        }
        val found = S.findVod(vodLines.asSequence(), 2_999L)
        check("iptvstore: find locates one item in a 3,000-line file; a miss is null",
            found?.name == "Movie 2999" && S.findVod(vodLines.asSequence(), 9_999L) == null)
        val seriesLines = (1..120).map { i ->
            S.encodeSeries(IptvLogic.SeriesItem(i.toLong(), "Show $i", "", "1", "Drama", "", "", 1950 + i))
        }
        val tuples = S.seriesTuples(seriesLines.asSequence())
        check("iptvstore: tuples carry only (id, title, year) for matching",
            tuples.size == 120 && tuples[41] == IptvStore.Tuple(42L, "Show 42", 1992))

        // --- swap decision (the ingest's all-or-nothing rule).
        check("iptvstore: swap only when every attempted catalogue endpoint succeeded",
            S.shouldSwapCatalogue(
                listOf(IptvStore.EndpointResult(true, true), IptvStore.EndpointResult(true, true))
            ) &&
                !S.shouldSwapCatalogue(
                    listOf(IptvStore.EndpointResult(true, true), IptvStore.EndpointResult(true, false))
                ) &&
                !S.shouldSwapCatalogue(listOf(IptvStore.EndpointResult(false, false))) &&
                S.shouldSwapCatalogue(
                    listOf(IptvStore.EndpointResult(true, true), IptvStore.EndpointResult(false, false))
                ))

        // --- XMLTV streaming: equivalence + window + oversized segment resync.
        val nowMs = IptvEpg.parseXmltvTime("20261022120000 +0000")
        val xmltv = "<?xml version=\"1.0\"?><tv>" +
            "<programme start=\"20261022110000 +0000\" stop=\"20261022120000 +0000\" channel=\"a.tv\"><title>Before</title></programme>" +
            "<programme start=\"20261022120000 +0000\" stop=\"20261022130000 +0000\" channel=\"a.tv\"><title>Now &amp; Next</title></programme>" +
            "<programme start=\"20261022130000 +0000\" stop=\"20261022140000 +0000\" channel=\"b.tv\"><title><![CDATA[Other]]></title></programme>" +
            "</tv>"
        val streamedProgs = ArrayList<IptvEpg.Programme>()
        val deliveredCount = IptvEpg.streamXmltv(java.io.StringReader(xmltv)) { streamedProgs.add(it) }
        val wholeProgs = IptvEpg.parseXmltv(xmltv).values.flatten().sortedBy { it.startMs }
        check("iptvstore: XMLTV stream delivers the same programmes as the document parse",
            deliveredCount == 3 && streamedProgs.sortedBy { it.startMs } == wholeProgs,
            "streamed=$streamedProgs whole=$wholeProgs")
        check("iptvstore: guide window keeps now/next, drops the long past and the far future",
            IptvEpg.inGuideWindow(IptvEpg.Programme("a", nowMs - 1_800_000L, nowMs + 1_800_000L, "n"), nowMs) &&
                !IptvEpg.inGuideWindow(IptvEpg.Programme("a", nowMs - 7_200_000L, nowMs - 3_600_000L, "old"), nowMs) &&
                !IptvEpg.inGuideWindow(
                    IptvEpg.Programme("a", nowMs + 24L * 3_600_000L, nowMs + 25L * 3_600_000L, "far"), nowMs
                ))
        val giantTitle = "x".repeat(300_000)
        val xmltvGiant = "<tv>" +
            "<programme start=\"20261022120000 +0000\" stop=\"20261022130000 +0000\" channel=\"a.tv\"><title>$giantTitle</title></programme>" +
            "<programme start=\"20261022130000 +0000\" stop=\"20261022140000 +0000\" channel=\"a.tv\"><title>After</title></programme>" +
            "</tv>"
        val afterGiant = ArrayList<IptvEpg.Programme>()
        IptvEpg.streamXmltv(java.io.StringReader(xmltvGiant)) { afterGiant.add(it) }
        check("iptvstore: an oversized programme segment is dropped, the stream resyncs",
            afterGiant.size == 1 && afterGiant[0].title == "After",
            "got=${afterGiant.size}")

        // --- sliced home composition.
        val favs = listOf(L.favOf(vod1))
        val catA = IptvLogic.Category("1", "General", "xtream", L.KIND_LIVE)
        val chanA = IptvLogic.LiveChannel(1L, "Chan A", "", "1", "General", "", L.SRC_XTREAM, "", 0, 0)
        val slicedRows = L.homeRowsSliced(
            favs, emptyList(), emptySet(),
            listOf(catA to listOf(chanA)), emptyList(),
            listOf(vod1), listOf(series1)
        )
        check("iptvstore: sliced home composes favorites → live → movies → series",
            slicedRows.map { it.kind } == listOf(
                IptvLogic.RowKind.FAVORITES, IptvLogic.RowKind.LIVE,
                IptvLogic.RowKind.MOVIES, IptvLogic.RowKind.SERIES
            ), "rows=${slicedRows.map { it.kind }}")
        val flatRows = L.homeRowsSliced(
            emptyList(), emptyList(), emptySet(),
            emptyList(), listOf(chanA), emptyList(), emptyList()
        )
        check("iptvstore: sliced home falls back to a flat Live row without categories",
            flatRows.size == 1 && flatRows[0].kind == IptvLogic.RowKind.LIVE &&
                flatRows[0].title == "Live TV")

        // --- source checks: the data layer really is disk-first now.
        fun src(path: String): String = try {
            java.io.File(path).readText()
        } catch (e: Exception) {
            ""
        }
        val clientSrc = src("app/src/main/java/com/mizan/launcher/data/IptvClient.kt")
        check("iptvstore: the client purges the legacy prefs documents on first v2.4 run",
            clientSrc.contains("iptv_store_v2") && clientSrc.contains("\"iptv_cache_live\"") &&
                clientSrc.contains("LEGACY_KEYS"))
        check("iptvstore: no whole-catalogue snapshot survives in the client (no cached(), no raw snapshot)",
            clientSrc.isNotEmpty() && !clientSrc.contains("fun cached(") &&
                !clientSrc.contains("snapshotRaw"))
        check("iptvstore: the client streams ingest endpoints and slices reads",
            clientSrc.contains("IptvStream.streamArray") &&
                clientSrc.contains("fun sliceBlocking") &&
                clientSrc.contains("IptvStore.shouldSwapCatalogue"))
        val mainSrc2 = src("app/src/main/java/com/mizan/launcher/MainActivity.kt")
        check("iptvstore: no auto-fetch remains on home builds (refresh is on request only)",
            mainSrc2.isNotEmpty() && !mainSrc2.contains("IptvClient.refreshAsync"))
        check("iptvstore: the IPTV home composes from slices (homeRowsSliced + sliceBlocking)",
            mainSrc2.contains("homeRowsSliced") && mainSrc2.contains("sliceBlocking"))
        val browserSrc2 = src("app/src/main/java/com/mizan/launcher/ui/IptvActivity.kt")
        check("iptvstore: the browser reads slices and never fetches on open/resume",
            browserSrc2.contains("IptvClient.sliceAsync") &&
                !browserSrc2.contains("IptvClient.cached"))
        val imagesSrc = src("app/src/main/java/com/mizan/launcher/data/TmdbImages.kt")
        check("iptvstore: image decode is downsampled and the bitmap cache is byte-bounded",
            imagesSrc.contains("inSampleSize") && imagesSrc.contains("MAX_CACHE_BYTES"))
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

    section42()  // extracted in v2.7: main() hit the JVM 64 KB method limit

    // ---------- (43) v2.6: player control bar logic, TMDB error tokens, IPTV episode shaping ----------
    section43()  // extracted: main() hit the JVM 64 KB method limit

    // ---------- (44) v2.6 amendment: launcher TMDB key + direct path ----------
    section44()

    // ---------- (45) v2.7: repo-method matching, focus rows, launcher-computed discovery ----------
    section45()

    // ---------- (46) v2.8: Google-TV tabs, section-order migration, hero primary, startup line ----------
    section46()

    // ---------- (47) v2.9: audit fixes — play routing, artwork fit, unified bar, scrub, discovery source ----------
    section47()

    // ---------- (48) v3.0: crash-hardening redo — startup health, section guards, parser depth caps, prefs funnel ----------
    section48()

    // ---------- (49) v3.0: corrupt/partial startup state — the app still opens on his real data shapes ----------
    section49()

    // ---------- (50) v3.1: Movie Night removal, local-first home cache, section modules, Play in…, sort page ----------
    section50()

    // ---------- (51) v3.1 hero amendment: the hero comes from the schedule (missed + up next) ----------
    section51()

    // ---------- (52) v3.2: no refresh on open, sort in one place, first-frame stages ----------
    section52()

    // ---------- (53) v3.3: lock auto-submit, safe mode escapable + visible ----------
    section53()

    // ---------- (54) v3.4: slim tracker cache, folders on media homes, hold-and-drop sort, smaller row posters ----------
    section54()

    // ---------- (55) v3.5: sequential cold chain, key-only streaming match, last-death capture ----------
    section55()

    // ---------- (56) v3.6: indexed tracker store + paged/visible-first (ask 53) ----------
    section56()

    // ---------- (57) v3.7: launcher IS the tracker + Missed row + poster fix ----------
    section57()

    println("RESULT: $pass passed, $fail failed, ${pass + fail} total")
    if (fail > 0) kotlin.system.exitProcess(1)
}

/** v2.6 checks — top-level because main() reached the JVM method-size limit. */
fun section43() {
    run {
        val pl = PlayerLogic

        // --- aspect cycle: order + wrap + garbage normalization.
        check("aspect: cycle runs Fit -> Stretch -> Zoom -> Fit",
            pl.nextAspect(pl.ASPECT_FIT) == pl.ASPECT_STRETCH &&
                pl.nextAspect(pl.ASPECT_STRETCH) == pl.ASPECT_ZOOM &&
                pl.nextAspect(pl.ASPECT_ZOOM) == pl.ASPECT_FIT)
        check("aspect: garbled/null stored values normalize to Fit",
            pl.normalizeAspect(null) == pl.ASPECT_FIT &&
                pl.normalizeAspect("banana") == pl.ASPECT_FIT &&
                pl.nextAspect("banana") == pl.ASPECT_STRETCH &&
                pl.aspectLabel(null) == "Fit" && pl.aspectLabel("zoom") == "Zoom")

        // --- aspect measure maths (1920x1080 screen).
        check("aspect: Fit letterboxes a 4:3 video inside the screen",
            pl.measuredSize(pl.ASPECT_FIT, 1920, 1080, 640, 480) == (1440 to 1080))
        check("aspect: Stretch takes the container exactly",
            pl.measuredSize(pl.ASPECT_STRETCH, 1920, 1080, 640, 480) == (1920 to 1080))
        check("aspect: Zoom covers the screen (overflow crops)",
            pl.measuredSize(pl.ASPECT_ZOOM, 1920, 1080, 640, 480) == (1920 to 1440))
        check("aspect: a portrait video Fits by height and Zooms by width",
            pl.measuredSize(pl.ASPECT_FIT, 1920, 1080, 480, 640) == (810 to 1080) &&
                pl.measuredSize(pl.ASPECT_ZOOM, 1920, 1080, 480, 640) == (1920 to 2560))
        check("aspect: unknown video size falls back to the container; empty container stays empty",
            pl.measuredSize(pl.ASPECT_FIT, 1920, 1080, 0, 0) == (1920 to 1080) &&
                pl.measuredSize(pl.ASPECT_ZOOM, 0, 0, 640, 480) == (0 to 0))

        // --- speed ladder: cycle + wrap + labels.
        check("speed: the ladder cycles and wraps 2x -> 0.5x",
            pl.nextSpeed(0.5f) == 0.75f && pl.nextSpeed(1.0f) == 1.25f &&
                pl.nextSpeed(1.5f) == 2.0f && pl.nextSpeed(2.0f) == 0.5f)
        check("speed: an off-ladder value snaps to the nearest rung before advancing",
            pl.nextSpeed(1.1f) == 1.25f && pl.nextSpeed(0.9f) == 1.25f)
        check("speed: labels trim trailing zeros",
            pl.speedLabel(1.0f) == "1×" && pl.speedLabel(2.0f) == "2×" &&
                pl.speedLabel(0.5f) == "0.5×" && pl.speedLabel(1.25f) == "1.25×" &&
                pl.speedLabel(0.75f) == "0.75×")

        // --- track filtering (MediaPlayer.TrackInfo type numbering).
        val allTracks = listOf(
            PlayerLogic.TrackDesc(0, PlayerLogic.TRACK_TYPE_VIDEO, ""),
            PlayerLogic.TrackDesc(1, PlayerLogic.TRACK_TYPE_AUDIO, "en"),
            PlayerLogic.TrackDesc(2, PlayerLogic.TRACK_TYPE_AUDIO, ""),
            PlayerLogic.TrackDesc(3, PlayerLogic.TRACK_TYPE_TIMEDTEXT, "en"),
            PlayerLogic.TrackDesc(4, PlayerLogic.TRACK_TYPE_SUBTITLE, "fr"),
            PlayerLogic.TrackDesc(5, 0, "")
        )
        check("tracks: audio filter keeps audio only, in index order",
            pl.audioTracks(allTracks).map { it.index } == listOf(1, 2))
        check("tracks: text filter keeps timed-text + subtitle only",
            pl.textTracks(allTracks).map { it.index } == listOf(3, 4))
        check("tracks: Audio button needs >1 audio track; Subs button needs >=1 text track",
            pl.showAudioButton(allTracks) && pl.showSubsButton(allTracks) &&
                !pl.showAudioButton(allTracks.filter { it.index != 2 }) &&
                !pl.showSubsButton(allTracks.filter { !pl.isTextTrack(it) }) &&
                !pl.showAudioButton(emptyList()) && !pl.showSubsButton(emptyList()))
        check("tracks: labels prefer the language, else a 1-based ordinal",
            pl.trackLabel(allTracks[1], 0) == "en" &&
                pl.trackLabel(allTracks[2], 1) == "Track 2")
        check("tracks: audio cycle wraps and tolerates an unknown current",
            pl.cycleIndex(-1, 3) == 0 && pl.cycleIndex(0, 3) == 1 &&
                pl.cycleIndex(2, 3) == 0 && pl.cycleIndex(9, 3) == 0 &&
                pl.cycleIndex(0, 0) == -1)
        check("tracks: subtitle cycle runs Off -> tracks -> Off",
            pl.cycleSubtitle(-1, 2) == 0 && pl.cycleSubtitle(0, 2) == 1 &&
                pl.cycleSubtitle(1, 2) == -1 && pl.cycleSubtitle(-1, 0) == -1)

        // --- bar text + progress.
        check("bar: the title line composes title + episode-name meta once",
            pl.barTitle("Show · S1 E2", "Pilot") == "Show · S1 E2 · Pilot" &&
                pl.barTitle("Movie", "") == "Movie" &&
                pl.barTitle("Title", "Title") == "Title")
        check("bar: clocks format m:ss under an hour, h:mm:ss above, negatives clamp",
            pl.formatClock(0L) == "0:00" && pl.formatClock(59_000L) == "0:59" &&
                pl.formatClock(61_000L) == "1:01" &&
                pl.formatClock(3_661_000L) == "1:01:01" &&
                pl.formatClock(-5L) == "0:00")
        check("bar: time label shows elapsed/total, or Live when duration is unknown",
            pl.timeLabel(61_000L, 3_600_000L) == "1:01 / 1:00:00" &&
                pl.timeLabel(61_000L, -1L) == "Live")
        check("bar: progress permille clamps and unknowns read 0",
            pl.progressPermille(500L, 1000L) == 500 &&
                pl.progressPermille(2000L, 1000L) == 1000 &&
                pl.progressPermille(0L, 1000L) == 0 &&
                pl.progressPermille(100L, 0L) == 0)
        check("bar: Prev/Next episode shows only for episode plays with queue neighbours",
            pl.hasEpisodeNeighbours(IptvLogic.KIND_EPISODE, 3) &&
                !pl.hasEpisodeNeighbours(IptvLogic.KIND_EPISODE, 1) &&
                !pl.hasEpisodeNeighbours(IptvLogic.KIND_LIVE, 5) &&
                !pl.hasEpisodeNeighbours(IptvLogic.KIND_VOD, 2))

        // --- TMDB error tokens (tracker 1.14.5's io:<ClassName> form).
        val ll = LibraryLogic
        check("tmdb token: io:<ClassName> parses as the raw token; bare io stays io",
            ll.tmdbErrorToken("io:UnknownHostException") == "io:UnknownHostException" &&
                ll.tmdbErrorToken("io:SSLHandshakeException") == "io:SSLHandshakeException" &&
                ll.tmdbErrorToken("io") == "io" &&
                ll.tmdbErrorToken(null) == "" && ll.tmdbErrorToken("") == "")
        check("tmdb token: http codes still parse through the colon-safe path",
            ll.tmdbHttpCode("http_401") == 401 &&
                ll.tmdbErrorKind("io:SocketTimeoutException") == LibraryLogic.TmdbError.IO &&
                ll.tmdbRetryable("io:SocketTimeoutException"))
        check("tmdb token: the user line keeps its human text and gains the token",
            ll.tmdbUserLine("io:UnknownHostException").contains("reach TMDB") &&
                ll.tmdbUserLine("io:UnknownHostException")
                    .endsWith("(io:UnknownHostException)") &&
                ll.tmdbUserLine("no_key").endsWith("(no_key)") &&
                ll.tmdbUserLine("io").endsWith("(io)"))

        // --- IPTV episode shaping (the Details fallback source).
        fun ep(num: Int, title: String) =
            IptvLogic.Episode("id$num", title, 0, num, "mkv", 0)
        val info = IptvLogic.SeriesInfo(
            seriesId = 42L, name = "Some Show", cover = "", plot = "",
            seasons = listOf(
                IptvLogic.Season(2, listOf(ep(3, ""), ep(1, "Finale"), ep(2, ""), ep(2, "Dupe"))),
                IptvLogic.Season(1, listOf(ep(2, ""), ep(1, "Pilot"))),
                IptvLogic.Season(3, emptyList()),
                IptvLogic.Season(0, listOf(ep(1, "Special")))
            )
        )
        val shapedSeasons = ll.iptvSeasonInfos(info)
        check("iptv shape: seasons ascend, empty seasons drop, counts are deduped",
            shapedSeasons.map { it.number } == listOf(0, 1, 2) &&
                shapedSeasons.map { it.episodeCount } == listOf(1, 2, 3))
        val s2 = ll.iptvEpisodes(info, 2)
        check("iptv shape: episodes ascend + dedupe by number, first occurrence wins",
            s2.map { it.number } == listOf(1, 2, 3))
        check("iptv shape: titles fall back to Episode N when the panel has none",
            s2[0].name == "Finale" && s2[1].name == "Episode 2" && s2[2].name == "Episode 3")
        check("iptv shape: a missing season shapes empty; seasonCsv matches the shaped list",
            ll.iptvEpisodes(info, 99).isEmpty() && ll.seasonCsv(s2) == "1,2,3")
        check("iptv shape: shaped episodes key into the same SxEy watched set as TMDB's",
            s2.map { ll.epKey(2, it.number) } == listOf("2x1", "2x2", "2x3"))

        // --- source checks: the v2.6 wiring is really in the app.
        fun src(path: String): String = try {
            java.io.File(path).readText()
        } catch (e: Exception) {
            ""
        }
        val playerSrc = src("app/src/main/java/com/mizan/launcher/ui/PlayerActivity.kt")
        check("v26src: the player has the aspect-measuring VideoView + bar machinery",
            playerSrc.contains("AspectVideoView") &&
                playerSrc.contains("PlayerLogic.measuredSize") &&
                playerSrc.contains("enumerateTracks") &&
                playerSrc.contains("hideBarRunnable"))
        check("v26src: no foreground service anywhere in the player (standing rule)",
            playerSrc.isNotEmpty() && !playerSrc.contains("startForegroundService"))
        val prefsSrc = src("app/src/main/java/com/mizan/launcher/data/Prefs.kt")
        check("v26src: the aspect mode persists per profile (player_aspect)",
            prefsSrc.contains("player_aspect") && prefsSrc.contains("playerAspect"))
        val detailsSrc = src("app/src/main/java/com/mizan/launcher/ui/DetailsActivity.kt")
        check("v26src: Details carries the IPTV episode fallback + its header note",
            detailsSrc.contains("maybeStartIptvFallback") &&
                detailsSrc.contains("Episode list from IPTV — TMDB unreachable") &&
                detailsSrc.contains("iptvSeasonInfos"))
        val librarySrc = src("app/src/main/java/com/mizan/launcher/core/LibraryLogic.kt")
        check("v26src: LibraryLogic accepts the io:<ClassName> token form",
            librarySrc.contains("startsWith(\"io:\")") &&
                librarySrc.contains("tmdbErrorToken"))
        val trackerSrc = src("../tvshows-movies-tracker/app/java/com/haythem/tvtracker/LauncherDataProvider.java")
        check("v26src: tracker 1.14.5 returns io:<simple exception class name>",
            trackerSrc.contains("\"io:\" + e.getClass().getSimpleName()"))
    }
}

/** v2.6 amendment checks — the launcher-held TMDB key + direct path. */
fun section44() {
    run {
        val ll = LibraryLogic

        // --- resolution order (core/LibraryLogic.resolveTmdb).
        var directCalled = false
        val proxyOk = ll.resolveTmdb("{\"ok\":1}", null, directKeySet = true) {
            directCalled = true
            "{\"direct\":1}" to null
        }
        check("resolve: a proxy success stands and the direct path never runs",
            proxyOk.json == "{\"ok\":1}" && !proxyOk.viaDirect &&
                proxyOk.error == null && !directCalled)
        directCalled = false
        val noKey = ll.resolveTmdb(null, "io:UnknownHostException", directKeySet = false) {
            directCalled = true
            "{}" to null
        }
        check("resolve: proxy failure + no launcher key surfaces the proxy error, direct uncalled",
            noKey.json == null && noKey.error == "io:UnknownHostException" &&
                noKey.directError == null && !directCalled)
        val directOk = ll.resolveTmdb(null, "io", directKeySet = true) {
            "{\"direct\":1}" to null
        }
        check("resolve: proxy failure + launcher key + direct success answers via direct",
            directOk.json == "{\"direct\":1}" && directOk.viaDirect && directOk.error == null)
        val bothFail = ll.resolveTmdb(null, "http_401", directKeySet = true) {
            null to "io:SocketTimeoutException"
        }
        check("resolve: both paths failing surfaces the PROXY error; the direct token is kept for diagnostics",
            bothFail.json == null && bothFail.error == "http_401" &&
                bothFail.directError == "io:SocketTimeoutException" && !bothFail.viaDirect)
        check("resolve: a missing proxy error degrades to io",
            ll.resolveTmdb(null, null, directKeySet = false) { "{}" to null }.error == "io")

        // --- direct error mapping + key redaction at the mapping layer.
        val secret = "SECRETKEY123"
        val thrown = ll.resolveTmdb(null, "io", directKeySet = true) {
            throw java.net.UnknownHostException("api_key=$secret")
        }
        check("direct: a throwing direct path degrades to its class-name token",
            thrown.directError == "io:UnknownHostException")
        check("direct: error tokens never carry the exception message (which can embed the key)",
            ll.tmdbDirectError(java.net.UnknownHostException("api_key=$secret")) ==
                "io:UnknownHostException" &&
                !thrown.directError.orEmpty().contains(secret) &&
                !thrown.error.orEmpty().contains(secret))
        check("direct: http token matches the provider contract shape",
            ll.tmdbDirectHttpError(401) == "http_401" && ll.tmdbDirectHttpError(404) == "http_404")

        // --- diagnostics line shaping: the four cases.
        check("tmdb line: OK via tracker / OK via launcher key",
            ll.tmdbDiagText("ok", "") == "OK via tracker" &&
                ll.tmdbDiagText("ok_direct", "") == "OK via launcher key")
        check("tmdb line: failure names the proxy token and the direct token",
            ll.tmdbDiagText("io:UnknownHostException", "io:SocketTimeoutException") ==
                "network error (io:UnknownHostException) · direct: io:SocketTimeoutException")
        check("tmdb line: failure with no launcher key says so",
            ll.tmdbDiagText("io:UnknownHostException", "not_set") ==
                "network error (io:UnknownHostException) · launcher key: not set")
        check("tmdb line: composed lines never contain key material",
            !ll.tmdbDiagText("io:UnknownHostException", "io:UnknownHostException")
                .contains(secret) &&
                !ll.tmdbDiagText("ok_direct", null).contains(secret))

        // --- source checks: the wiring + hygiene are really in the app.
        fun src(path: String): String = try {
            java.io.File(path).readText()
        } catch (e: Exception) {
            ""
        }
        val prefsSrc = src("app/src/main/java/com/mizan/launcher/data/Prefs.kt")
        check("v26src: the launcher key lives in Prefs under its own name",
            prefsSrc.contains("launcher_tmdb_key") && prefsSrc.contains("var tmdbKey"))
        val settingsSrc = src("app/src/main/java/com/mizan/launcher/ui/SettingsActivity.kt")
        check("v26src: Settings has the masked TMDB API key row (set/not-set only)",
            settingsSrc.contains("\"TMDB API key\"") && settingsSrc.contains("prefs.tmdbKey"))
        val exportStart = settingsSrc.indexOf("fun exportJson")
        val exportBody = if (exportStart >= 0) {
            settingsSrc.substring(exportStart, minOf(exportStart + 6000, settingsSrc.length))
        } else {
            ""
        }
        check("v26src: the backup export never touches the TMDB key (redaction)",
            exportBody.isNotEmpty() && !exportBody.contains("tmdbKey") &&
                !exportBody.contains("launcher_tmdb_key"))
        val directSrc = src("app/src/main/java/com/mizan/launcher/data/TmdbDirect.kt")
        check("v26src: the direct client targets TMDB with the contract allowlist + class-name tokens",
            directSrc.contains("api.themoviedb.org/3/") &&
                directSrc.contains("tmdbDirectError") &&
                directSrc.contains("trending/"))
        check("v26src: the direct client never logs (no Log import, no key echo)",
            directSrc.isNotEmpty() && !directSrc.contains("android.util.Log") &&
                !directSrc.contains("println"))
        val clientSrc = src("app/src/main/java/com/mizan/launcher/data/TrackerClient.kt")
        // v3.7 RE-PINNED (launcher IS the tracker): the proxy
        // is gone — the funnel is direct-only, same notes.
        check("v26src: the funnel is direct-only via TmdbDirect and records both notes",
            !clientSrc.contains("resolveTmdb") && !clientSrc.contains("tmdbGetViaProxy") &&
                clientSrc.contains("ok_direct") &&
                clientSrc.contains("lastTmdbDirect") && clientSrc.contains("TmdbDirect.getBlocking"))
    }
}

/** v2.7 checks — top-level like §43/§44 (main() is at the JVM method-size limit). */
fun section45() {
    run {
        val wm = WatchMatch
        val d = Discovery

        // --- directive 1: the repo method, fixture-pinned ---
        check("repo: exact-after-squash ('Grey's Anatomy' vs 'Greys Anatomy')",
            wm.matchOne(
                WatchMatch.TrackerTitle(1, "tv", "Grey's Anatomy", 2005),
                listOf(WatchMatch.Candidate(5, "Greys Anatomy", 2005))
            )?.let { it.iptvId == 5L && it.exact } == true)
        check("repo: catalogue suffix noise matches via contains ('The Office' vs 'The Office (US)')",
            wm.matchOne(
                WatchMatch.TrackerTitle(2, "tv", "The Office", 2005),
                listOf(WatchMatch.Candidate(6, "The Office (US)", 2005))
            )?.let { it.iptvId == 6L && !it.exact && !it.possible } == true)
        check("repo: quality-tag noise matches via contains ('Show Name 2023 1080p')",
            wm.matchOne(
                WatchMatch.TrackerTitle(3, "tv", "Show Name", 0),
                listOf(WatchMatch.Candidate(7, "Show Name 2023 1080p", 0))
            )?.let { it.iptvId == 7L && !it.exact && !it.possible } == true)
        check("repo: the contains tier picks the SHORTEST candidate; multiple distinct titles flag possible",
            wm.matchOne(
                WatchMatch.TrackerTitle(4, "tv", "Star Trek", 0),
                listOf(
                    WatchMatch.Candidate(8, "Star Trek: The Next Generation", 0),
                    WatchMatch.Candidate(9, "Star Trek Discovery", 0)
                )
            )?.let { it.iptvId == 9L && it.possible && !it.exact } == true)
        check("repo: a year disagreement deprioritizes inside the contains tier (beats shorter)",
            wm.matchOne(
                WatchMatch.TrackerTitle(5, "movie", "Dune", 2021),
                listOf(
                    WatchMatch.Candidate(10, "Dune 1984 Cut", 1984),
                    WatchMatch.Candidate(11, "Dune Part Two 2021", 2021)
                )
            )?.iptvId == 11L)
        check("repo: catalogue order breaks equal-length contains ties",
            wm.matchOne(
                WatchMatch.TrackerTitle(6, "tv", "Dark", 0),
                listOf(WatchMatch.Candidate(12, "Dark X1", 0), WatchMatch.Candidate(13, "Dark Y2", 0))
            )?.iptvId == 12L)
        val repoPool = listOf(
            WatchMatch.Candidate(20, "Dark", 0), WatchMatch.Candidate(21, "Dark Matter", 0),
            WatchMatch.Candidate(22, "Dark Matter Extended", 0), WatchMatch.Candidate(23, "The Dark", 0),
            WatchMatch.Candidate(24, "Dune", 1984), WatchMatch.Candidate(25, "Dune", 2021),
            WatchMatch.Candidate(26, "Dune 2021 1080p", 0)
        )
        val repoTitles = listOf(
            WatchMatch.TrackerTitle(30, "tv", "Dark", 0), WatchMatch.TrackerTitle(31, "tv", "Dark Matter", 0),
            WatchMatch.TrackerTitle(32, "movie", "Dune", 2021), WatchMatch.TrackerTitle(33, "movie", "Dune", 0),
            WatchMatch.TrackerTitle(34, "tv", "Missing Show", 0)
        )
        check("repo: keyed matchAll == per-title matchOne under the repo core",
            wm.matchAll(repoTitles, repoPool) ==
                repoTitles.mapNotNull { t -> wm.matchOne(t, repoPool)?.let { t.tmdbId to it } }.toMap())
        check("repo: 'Dune 2021 1080p' contains-matches the yearless 'Dune'; 'Missing Show' stays unmatched",
            wm.matchOne(
                WatchMatch.TrackerTitle(35, "movie", "Dune", 0),
                listOf(WatchMatch.Candidate(26, "Dune 2021 1080p", 0))
            )?.iptvId == 26L &&
                !wm.matchAll(repoTitles, repoPool).containsKey(34))

        // --- directive 2/3: trending parse + badge flagging ---
        val trendJson = """{"results":[
          {"id":11,"media_type":"tv","name":"Severance","poster_path":"/s.jpg","backdrop_path":"/sb.jpg","vote_average":8.4,"first_air_date":"2022-02-18"},
          {"id":22,"media_type":"movie","title":"Dune: Part Two","poster_path":"/d.jpg","vote_average":8.1,"release_date":"2024-03-01"},
          {"id":33,"media_type":"person","name":"Some Actor"},
          {"id":44,"media_type":"tv","name":"","poster_path":"/x.jpg"}]}"""
        val all = d.parseTrending(trendJson, null)
        check("discovery: trending/all parses tv+movie, drops people + blank titles, keeps TMDB order",
            all.size == 2 && all[0].type == "tv" && all[0].title == "Severance" &&
                all[0].year == "2022" && all[1].type == "movie" && all[1].year == "2024")
        check("discovery: per-type trending forces the type when media_type is absent",
            d.parseTrending(
                """{"results":[{"id":55,"name":"Andor","poster_path":"/a.jpg","first_air_date":"2022-09-21"}]}""",
                "tv"
            ).let { it.size == 1 && it[0].type == "tv" && it[0].id == 55L })
        val trendMatches = wm.matchAll(
            listOf(WatchMatch.TrackerTitle(1399, "tv", "Shogun", 2024)),
            listOf(WatchMatch.Candidate(50, "Shogun 2024", 2024), WatchMatch.Candidate(51, "Other", 0))
        )
        check("discovery: a trending title flags the ▶ IPTV badge only when the catalogue matches",
            wm.cardBadge(trendMatches.containsKey(1399)) == "▶ IPTV" &&
                wm.cardBadge(trendMatches.containsKey(2400)) == null)

        // --- details facts ---
        val tvFactsJson = """{"id":1396,"name":"Breaking Bad","poster_path":"/bb.jpg","vote_average":8.9,
           "genres":[{"id":18,"name":"Drama"},{"id":80,"name":"Crime"}],
           "next_episode_to_air":{"air_date":"2026-11-05","season_number":6,"episode_number":1}}"""
        check("discovery: tv facts parse (next episode + genres + rating)",
            d.parseTvFacts(tvFactsJson)?.let {
                it.nextDateIso == "2026-11-05" && it.nextSeason == 6 && it.nextEpisode == 1 &&
                    it.genreIds == listOf(18, 80) && it.voteAverage == 8.9
            } == true)
        check("discovery: tv facts — a null next_episode_to_air reads blank (no guessing from seasons)",
            d.parseTvFacts("""{"id":1,"name":"X","next_episode_to_air":null}""")?.nextDateIso == "")
        check("discovery: movie facts parse (release date + genres)",
            d.parseMovieFacts(
                """{"id":9,"title":"Future Film","release_date":"2027-01-15","genres":[{"id":878}]}"""
            )?.let { it.releaseDate == "2027-01-15" && it.genreIds == listOf(878) } == true)

        // --- coming soon shaping ---
        val today = "2026-10-23"
        val soon = d.shapeComingSoon(
            listOf(
                Discovery.ShowSoon(1, "Future Show", "/f.jpg", "2026-11-05", 6, 1),
                Discovery.ShowSoon(2, "Today Show", "/t.jpg", "2026-10-23", 1, 4),
                Discovery.ShowSoon(3, "Past Show", "/p.jpg", "2026-10-01", 2, 2),
                Discovery.ShowSoon(4, "No Date Show", "/n.jpg", "", 0, 0),
                Discovery.ShowSoon(5, "Bad Date Show", "/b.jpg", "2026-02-30", 1, 1)
            ),
            listOf(
                Discovery.MovieSoon(6, "Future Film", "/ff.jpg", "2027-01-15"),
                Discovery.MovieSoon(7, "Past Film", "/pf.jpg", "2020-05-05"),
                Discovery.MovieSoon(8, "Garbage Film", "/gf.jpg", "not-a-date")
            ),
            today
        )
        check("soon: future-only, strictly parsed (Feb 30 rejected), sorted ascending",
            soon.map { it.title } == listOf("Today Show", "Future Show", "Future Film"))
        check("soon: shows carry episode coordinates, movies none",
            soon[0].sub == "S1 E4" && soon[2].sub == "")
        check("soon: chips — today / tomorrow / in N days / the bare date; past + unparseable read blank",
            d.soonChip("2026-10-23", today) == "today" &&
                d.soonChip("2026-10-24", today) == "tomorrow" &&
                d.soonChip("2026-11-04", today) == "in 12 days" &&
                d.soonChip("2027-01-15", today) == "Jan 15" &&
                d.soonChip("2026-10-22", today) == "" &&
                d.soonChip("2026-02-30", today) == "")
        check("soon: the strict parser round-trips real dates and rejects invented ones",
            d.strictEpochDay("2026-02-28") != null && d.strictEpochDay("2026-02-30") == null &&
                d.civilIso(d.strictEpochDay("2026-10-23")!!) == "2026-10-23")
        val manySoon = (1..30).map { Discovery.ShowSoon(it.toLong(), "Show $it", "", "2026-12-01", 1, 1) }
        check("soon: the row caps at 20",
            d.shapeComingSoon(manySoon, emptyList(), today).size == d.SOON_CAP && d.SOON_CAP == 20)

        // --- for you engine (the tracker computeForYou, ported) ---
        check("foryou: seed weights follow the tracker (tv completion curve, movie watched flag)",
            d.tvSeedWeight(0) == 0.5 && d.tvSeedWeight(10) == 1.0 &&
                d.tvSeedWeight(20) == 1.5 && d.movieSeedWeight(true) == 1.0 &&
                d.movieSeedWeight(false) == 0.4)
        val seedInputs = listOf(
            Discovery.SeedInput("tv", 1, "Heavy Show", 1.5, 9.0, listOf(18)),
            Discovery.SeedInput("tv", 2, "Light Show", 0.5, 8.0, listOf(35)),
            Discovery.SeedInput("movie", 3, "Unwatched Film", 0.4, 9.5, listOf(28)),
            Discovery.SeedInput("movie", 4, "Watched Film", 1.0, 7.0, listOf(18, 80))
        )
        check("foryou: seeds are the top titles by weight x rating (weight gate > 0.4)",
            d.pickSeeds(seedInputs).map { it.id } == listOf(1L, 4L, 2L))
        val aff = d.affinity(seedInputs)
        check("foryou: affinity spreads each title's weight over its genres; top genres ranked",
            aff[18] == 2.5 && aff[35] == 0.5 && d.topGenres(aff, 2) == listOf(18, 80))
        val recs = d.recPicks(
            Discovery.SeedInput("tv", 1, "Heavy Show", 1.5, 9.0, listOf(18)),
            listOf(
                Discovery.FyCandidate(101, "Kindred", "/k.jpg", 8.0, listOf(18, 53)),
                Discovery.FyCandidate(102, "Posterless", "", 9.9, listOf(18)),
                Discovery.FyCandidate(103, "Third", "/t.jpg", 7.0, listOf(9999))
            )
        )
        check("foryou: recommendation picks — poster required, reason names the seed, score = weight*10 + rating",
            recs.size == 2 && recs[0].id == 101L &&
                recs[0].reason == "Because you watched Heavy Show" &&
                recs[0].whyGenres == listOf("Drama", "Thriller") &&
                recs[0].score == 23.0 &&
                recs[1].whyGenres == listOf("Similar to Heavy Show"))
        val disc = d.discoverPicks(
            "tv",
            listOf(
                Discovery.FyCandidate(201, "Rep Pick", "/r.jpg", 7.5, listOf(18)),
                Discovery.FyCandidate(202, "Genre Pick", "/g.jpg", 7.0, listOf(35))
            ),
            listOf(18, 80, 35), aff
        ) { gid -> if (gid == 18) "Heavy Show" else null }
        check("foryou: discover picks — representative-title reason, affinity x2 scoring",
            disc.size == 2 && disc[0].reason == "Because you watched Heavy Show" &&
                disc[0].score == 12.5 &&
                disc[1].reason == "Matches your taste: Comedy")
        val merged = d.mergePicks(listOf(recs, disc), excludeKeys = setOf("tv|101"), cap = 20)
        check("foryou: merge dedupes, excludes tracked, sorts by score",
            merged.none { it.id == 101L } && merged.map { it.id } == listOf(103L, 201L, 202L))
        check("foryou: merge cap applies",
            d.mergePicks(listOf(recs, disc), emptySet(), cap = 2).size == 2)
        check("foryou: the discover path mirrors the tracker engine's params",
            d.discoverPath("tv", listOf(18, 80)) ==
                "discover/tv?with_genres=18,80&sort_by=vote_average.desc&vote_average.gte=6.5&vote_count.gte=30&include_adult=false&page=1" &&
                d.recommendationsPath(Discovery.SeedInput("movie", 42, "X", 1.0, 7.0, emptyList())) ==
                "movie/42/recommendations")
        val roundTrip = TrackerLogic.parseForYou(d.encodePicks(recs))
        check("foryou: computed picks encode in the /foryou contract shape (parseForYou reads them back)",
            roundTrip.size == recs.size && roundTrip[0].title == "Kindred" &&
                roundTrip[0].whyGenres == listOf("Drama", "Thriller") &&
                roundTrip[0].reason == "Because you watched Heavy Show")

        // --- iptv trending: first-episode resolution ---
        val seriesInfo = IptvLogic.parseSeriesInfo(
            """{"info":{"name":"X"},"seasons":[0,1,2],"episodes":{
            "0":[{"id":"s0","season":0,"episode_num":1,"title":"Special","container_extension":"mp4"}],
            "1":[{"id":"s1","season":1,"episode_num":2,"title":"B","container_extension":"mp4"},{"id":"s2","season":1,"episode_num":1,"title":"A","container_extension":"mp4"}],
            "2":[{"id":"s3","season":2,"episode_num":1,"title":"C","container_extension":"mp4"}]}}""",
            5
        )
        check("discovery: first episode skips Specials and takes season 1's first episode",
            seriesInfo != null && d.firstEpisode(seriesInfo) == (1 to 1))

        // --- cache TTL + diagnostics ---
        check("discovery: cache TTL decisions on a fake clock",
            d.isFresh(1_000L, 1_000L + d.TRENDING_TTL_MS - 1, d.TRENDING_TTL_MS) &&
                !d.isFresh(1_000L, 1_000L + d.TRENDING_TTL_MS, d.TRENDING_TTL_MS) &&
                !d.isFresh(0L, 1_000L, d.TRENDING_TTL_MS) &&
                d.TRENDING_TTL_MS == 12L * 3600 * 1000 &&
                d.DETAILS_TTL_MS == 24L * 3600 * 1000 &&
                d.FORYOU_TTL_MS == 24L * 3600 * 1000)
        check("discovery: diagnostics line is source + counts only",
            d.diagText("launcher", 3, 24, 12) == "source launcher · soon 3 · trend 24 · foryou 12" &&
                d.diagText("tracker", 0, 0, 5) == "source tracker · soon 0 · trend 0 · foryou 5")

        // --- sections: the v2.7 set ---
        val tsx = TrackerSections
        // v3.3 RE-PINNED (ask 47): comingsoon merged into upnext
        // (v3.1 had already removed movienight from this order).
        // v3.4 RE-PINNED (ask 51): folders joins after favorites.
        // v3.7 RE-PINNED (Missed row): Missed joins after Up Next.
        check("sections: v2.7 canonical order is the directive's focus order",
            tsx.CANONICAL == listOf(
                "hero", "upnext", "missed", "continue", "trendingtv",
                "trendingmovies", "foryou", "favorites", "folders", "allapps"
            ))
        check("sections: v2.7 labels",
            tsx.label(tsx.TRENDINGTV) == "Trending TV" &&
                tsx.label(tsx.TRENDINGMOVIES) == "Trending Movies")
        // v3.3 RE-PINNED (ask 47): a stored movienight drops on decode;
        // the remaining order + appended focus rows are unchanged
        // (comingsoon no longer appends — it is merged into upnext).
        check("sections: a pre-v2.7 stored order keeps its order; the new rows append",
            tsx.decode("upnext,continue,movienight,foryou,favorites,allapps|").order ==
                // v3.4 RE-PINNED (ask 51): folders appends after the trending rows.
                // v3.7 RE-PINNED (Missed row): Missed splices after Up Next.
                listOf(
                    "upnext", "missed", "continue", "foryou", "favorites",
                    "allapps", "trendingtv", "trendingmovies", "folders"
                ))

        // --- source checks: the v2.7 wiring is really in the app ---
        fun src(path: String): String = try {
            java.io.File(path).readText()
        } catch (e: Exception) {
            ""
        }
        val mainSrc = src("app/src/main/java/com/mizan/launcher/MainActivity.kt")
        // v3.3 RE-PINNED (ask 47): Coming Soon is merged into Up
        // Next — the home composes the merged section + Trending.
        check("v27src: the Tracker/Watch home composes the merged Up Next + Trending rows",
            mainSrc.contains("buildMergedUpNextSection") &&
                mainSrc.contains("buildTrendingSection") &&
                mainSrc.contains("TrackerSections.TRENDINGTV"))
        check("v27src: the IPTV home carries Trending on your IPTV + its play path",
            mainSrc.contains("buildIptvTrendingSection") &&
                mainSrc.contains("Trending on your IPTV") &&
                mainSrc.contains("playTrendPlay"))
        val ddSrc = src("app/src/main/java/com/mizan/launcher/data/DiscoveryData.kt")
        check("v27src: discovery fetches run on the mizan-discovery worker through the funnel",
            ddSrc.contains("mizan-discovery") && ddSrc.contains("tmdbGetBlocking") &&
                ddSrc.contains("filesDir"))
        val settingsSrc = src("app/src/main/java/com/mizan/launcher/ui/SettingsActivity.kt")
        check("v27src: diagnostics carries the discovery line",
            settingsSrc.contains("discovery: ") && settingsSrc.contains("Discovery.diagText"))
        val directSrc = src("app/src/main/java/com/mizan/launcher/data/TmdbDirect.kt")
        check("v27src: the direct client accepts discover/ (the proxy allowlist has none)",
            directSrc.contains("discover/"))
    }
}


/** v2.5 checks — extracted in v2.7: main() hit the JVM method-size limit. */
fun section42() {
    // ---------- (42) v2.5: ANR hunt — watchdog, breadcrumbs, log codec, keyed matching ----------
    run {
        // --- watchdog staleness on a fake clock (core/DiagWatchdog).
        check("diag: a heartbeat 3.9 s old is fresh",
            !DiagWatchdog.isStale(1_000L, 4_900L))
        check("diag: a heartbeat exactly at the threshold is not yet stale (strictly past it is)",
            !DiagWatchdog.isStale(1_000L, 5_000L) &&
                DiagWatchdog.isStale(1_000L, 5_001L))
        check("diag: a heartbeat a minute old is stale",
            DiagWatchdog.isStale(1_000L, 61_000L))
        check("diag: a custom threshold is honoured",
            !DiagWatchdog.isStale(0L, 1_000L, 1_000L) &&
                DiagWatchdog.isStale(0L, 1_001L, 1_000L))
        check("diag: no heartbeat yet reads stale once past the threshold",
            !DiagWatchdog.isStale(0L, DiagWatchdog.STALE_MS) &&
                DiagWatchdog.isStale(0L, DiagWatchdog.STALE_MS + 1))

        // --- breadcrumb ring (core/BreadcrumbRing).
        val ring = BreadcrumbRing()
        check("crumbs: an empty ring snapshots empty", ring.snapshot().isEmpty())
        ring.add(0L, "home opened")
        ring.add(61_000L, "settings opened")
        check("crumbs: snapshot is oldest-first",
            ring.snapshot().map { it.second } == listOf("home opened", "settings opened"))
        val capped = BreadcrumbRing(3)
        capped.add(0L, "1"); capped.add(0L, "2"); capped.add(0L, "3"); capped.add(0L, "4")
        check("crumbs: the ring keeps only the newest N",
            capped.snapshot().map { it.second } == listOf("2", "3", "4"))
        val fmt = BreadcrumbRing()
        fmt.add(3_661_000L, "opened")
        check("crumbs: lines render an HH:mm:ss clock + the label",
            fmt.lines().size == 1 &&
                Regex("^\\d{2}:\\d{2}:\\d{2} opened$").matches(fmt.lines()[0]))
        val restored = BreadcrumbRing(3)
        restored.restore(capped.encode())
        check("crumbs: the persist/restore round-trip is exact",
            restored.snapshot() == capped.snapshot())
        val small = BreadcrumbRing(2)
        small.restore("0|x\n0|y\n0|z")
        check("crumbs: restore over capacity keeps the newest",
            small.snapshot().map { it.second } == listOf("y", "z"))
        val junk = BreadcrumbRing()
        junk.restore("garbage\n5|\n7|ok")
        check("crumbs: restore skips malformed lines",
            junk.snapshot().map { it.second } == listOf("ok"))

        // --- bounded event-log codec + tail rendering (core/DiagLogs).
        var logText = ""
        logText = DiagLogs.appendEvent(logText, "event one\nsecond line", 3)
        logText = DiagLogs.appendEvent(logText, "event two", 3)
        check("diaglog: events accumulate oldest-first",
            DiagLogs.eventCount(logText) == 2 &&
                logText.indexOf("event one") < logText.indexOf("event two"))
        logText = DiagLogs.appendEvent(logText, "event three", 3)
        logText = DiagLogs.appendEvent(logText, "event four", 3)
        check("diaglog: the event cap keeps the newest events",
            DiagLogs.eventCount(logText) == 3 &&
                !logText.contains("event one") && logText.contains("event four"))
        check("diaglog: tailLines returns the last lines (what diagnostics shows)",
            DiagLogs.tailLines(logText, 2) ==
                DiagLogs.EVENT_SEPARATOR + "\nevent four")
        check("diaglog: a full tail returns every line",
            DiagLogs.tailLines(logText, 500).split("\n").size ==
                logText.trimEnd('\n').split("\n").size)
        check("diaglog: an empty log tails empty",
            DiagLogs.tailLines("", 10).isEmpty())
        val huge = DiagLogs.appendEvent("", "x".repeat(500), 3, maxChars = 100)
        check("diaglog: the char cap never truncates the newest event",
            huge.contains("x".repeat(500)) && DiagLogs.eventCount(huge) == 1)
        val squeezed = DiagLogs.appendEvent(huge, "small", 3, maxChars = 100)
        check("diaglog: an oversized old event is dropped to fit the cap",
            !squeezed.contains("xxx") && squeezed.contains("small"))

        // --- keyed matching equivalence (the v2.5 WatchMatch rewrite).
        // matchAll (once-built exact-key index) must return exactly
        // what the per-title path returns — the index is an
        // optimization, never a semantics change (§38 pins the
        // semantics themselves).
        val kinds = listOf(
            "The Matrix", "Dark", "Dark Matter", "Severance", "The Office",
            "Breaking Bad", "Amélie", "1917", "Dune", "Dune Part Two",
            "Alien", "Aliens", "The Bear", "Shogun", "True Detective",
            "Fargo", "Chernobyl", "Andor", "The Last of Us", "Fallout"
        )
        val candPool = ArrayList<WatchMatch.Candidate>()
        var cid = 1L
        for (i in 0 until 410) {
            val base = kinds[i % kinds.size]
            val year = if (i % 3 == 0) 1990 + (i % 30) else 0
            candPool.add(
                WatchMatch.Candidate(
                    cid++, (base + if (i % 5 == 0) " Extended" else "").trim(), year
                )
            )
        }
        val titlePool = kinds.mapIndexed { i, n ->
            WatchMatch.TrackerTitle(
                (1000 + i).toLong(), if (i % 2 == 0) "tv" else "movie", n,
                if (i % 4 == 0) 1990 + (i % 30) else 0
            )
        }
        val viaAll = WatchMatch.matchAll(titlePool, candPool)
        val viaOne = titlePool.mapNotNull { t ->
            WatchMatch.matchOne(t, candPool)?.let { t.tmdbId to it }
        }.toMap()
        check("watchkey: keyed matchAll == per-title matchOne over ${candPool.size} candidates",
            viaAll == viaOne)
        check("watchkey: the fixture actually matches (the equivalence is not vacuous)",
            viaAll.size >= 10)
        val yearPick = WatchMatch.matchAll(
            listOf(WatchMatch.TrackerTitle(7L, "movie", "Dune", 2021)),
            listOf(
                WatchMatch.Candidate(1L, "Dune", 1984),
                WatchMatch.Candidate(2L, "Dune", 2021)
            )
        )
        check("watchkey: the index path keeps the year tie-break",
            yearPick[7L]?.iptvId == 2L)
        val ambiguous = WatchMatch.matchAll(
            listOf(WatchMatch.TrackerTitle(8L, "movie", "Dune", 0)),
            listOf(
                WatchMatch.Candidate(3L, "Dune", 1984),
                WatchMatch.Candidate(4L, "Dune", 2021)
            )
        )
        check("watchkey: the index path keeps ambiguity -> first candidate + possible",
            ambiguous[8L]?.iptvId == 3L &&
                ambiguous[8L]?.possible == true)

        // --- source checks: the v2.5 wiring is really in the app.
        fun src(path: String): String = try {
            java.io.File(path).readText()
        } catch (e: Exception) {
            ""
        }
        val watchPlaySrc = src("app/src/main/java/com/mizan/launcher/data/WatchPlay.kt")
        check("v25src: Watch matching recomputes on the mizan-watch-match worker",
            watchPlaySrc.contains("mizan-watch-match"))
        check("v25src: WatchPlay never calls the deleted main-thread tuplesAsync",
            watchPlaySrc.isNotEmpty() && !watchPlaySrc.contains("tuplesAsync("))
        val clientSrc = src("app/src/main/java/com/mizan/launcher/data/IptvClient.kt")
        // Re-pinned in v3.5 (intentional change, spec §35): the
        // memoized tuples read is gone — the key-only stream
        // replaces it (same matches, nothing retained).
        check("v25src: tuples are a worker-only blocking read now (tuplesBlocking)",
            clientSrc.contains("fun keyedCatalogue"))
        check("v25src: the legacy purge lands via a single commit()",
            clientSrc.contains("commit()"))
        val manifest = src("app/src/main/AndroidManifest.xml")
        check("v25src: the manifest bootstraps the App class (DiagLog install)",
            manifest.contains("android:name=\".App\""))
        val settingsSrc = src("app/src/main/java/com/mizan/launcher/ui/SettingsActivity.kt")
        check("v25src: Settings offers Copy diagnostics with the freeze/crash tails",
            settingsSrc.contains("Copy diagnostics") &&
                settingsSrc.contains("anrTail") && settingsSrc.contains("crashTail"))
        val gradleSrc = src("app/build.gradle.kts")
        // Re-pinned in v3.4 (intentional version bump 3.3/26 → 3.4/27 —
        // slim cache + media-home folders + hold-and-drop + row posters, spec §34).
        // Re-pinned in v3.5 (intentional version bump 3.4/27 → 3.5/28 —
        // sequential cold chain + key-only match + last-death capture, spec §35).
        // Re-pinned in v3.6 (intentional version bump 3.5/28 → 3.6/29 —
        // indexed tracker store + paged/visible-first, spec §36).
        // Re-pinned in v3.7 (intentional version bump 3.6/29 → 3.7/30 —
        // launcher IS the tracker + Missed row + poster fix, spec §37).
        check("v25src: the app version is 3.7 / code 30 (bumped by v3.7)",
            gradleSrc.contains("versionName = \"3.7\"") &&
                gradleSrc.contains("versionCode = 30"))
    }
}

/** v2.8 checks — Google-TV design language, cold start, settings nav, Open-in-Tracker demotion. */
fun section46() {
    run {
        val tsx = TrackerSections

        // --- the section-order migration (why Haythem "didn't see
        // any change" in v2.7: the focus rows appended at the end).
        val preV27 = tsx.decode("upnext,continue,movienight,foryou,favorites,allapps|")
        // v3.3 RE-PINNED (ask 47): comingsoon is gone (merged
        // into upnext); only the trending rows still append.
        // v3.4 RE-PINNED (ask 51): folders appends too (a new
        // row, not a splice target — the migration leaves it
        // at the end).
        check("migrate: a pre-v2.7 stored order has the focus rows appended at the end",
            preV27.order.takeLast(3) == listOf("trendingtv", "trendingmovies", "folders"))
        val migrated = tsx.migrateOrder(preV27)
        // v3.7 RE-PINNED (Missed row): Missed rides after Up Next.
        check("migrate: the appended rows splice into their canonical slots",
            migrated.order == listOf(
                "upnext", "missed", "continue", "trendingtv", "trendingmovies",
                "foryou", "favorites", "allapps", "folders"
            ))
        check("migrate: idempotent — a second pass changes nothing",
            tsx.migrateOrder(migrated) == migrated)
        check("migrate: an untouched canonical order is a no-op",
            tsx.migrateOrder(tsx.decode("")) == tsx.decode(""))
        val partial = tsx.decode(
            "upnext,comingsoon,continue,movienight,foryou,favorites,allapps,trendingtv,trendingmovies|"
        )
        // v3.3 RE-PINNED (ask 47): comingsoon drops on decode, so
        // only the still-appended trending rows splice.
        check("migrate: only the still-appended rows move (comingsoon drops on decode)",
            // v3.4 RE-PINNED (ask 51): folders stays appended at the end.
            // v3.7 RE-PINNED (Missed row): Missed rides after Up Next.
            tsx.migrateOrder(partial).order == listOf(
                "upnext", "missed", "continue", "trendingtv", "trendingmovies",
                "foryou", "favorites", "allapps", "folders"
            ))
        val parked = tsx.decode(
            "upnext,continue,trendingtv,foryou,favorites,allapps,trendingmovies|"
        )
        check("migrate: a focus row parked mid-list (before foryou) is never itself moved",
            // v3.4 RE-PINNED (ask 51): folders stays appended at the end.
            // v3.7 RE-PINNED (Missed row): Missed rides after Up Next.
            tsx.migrateOrder(parked).order == listOf(
                "upnext", "missed", "continue", "trendingtv", "trendingmovies",
                "foryou", "favorites", "allapps", "folders"
            ))
        val custom = tsx.decode(
            "movienight,upnext,continue,foryou,favorites,allapps,comingsoon,trendingtv,trendingmovies|"
        )
        check("migrate: the user's other rows keep their relative order exactly",
            // v3.4 RE-PINNED (ask 51): folders stays appended at the end.
            // v3.7 RE-PINNED (Missed row): Missed rides after Up Next.
            tsx.migrateOrder(custom).order == listOf(
                "upnext", "missed", "continue", "trendingtv",
                "trendingmovies", "foryou", "favorites", "allapps", "folders"
            ))
        val hiddenCfg = tsx.decode(
            "upnext,continue,movienight,foryou,favorites,allapps|comingsoon"
        )
        // v3.3 RE-PINNED (ask 47): a removed key drops from the
        // hidden set on decode; the splice still applies.
        check("migrate: the hidden set drops removed keys; the splice still applies",
            tsx.migrateOrder(hiddenCfg).hidden.isEmpty() &&
                // v3.4 RE-PINNED (ask 51): folders stays appended at the end.
                // v3.7 RE-PINNED (Missed row): Missed rides after Up Next.
                tsx.migrateOrder(hiddenCfg).order == listOf(
                    "upnext", "missed", "continue", "trendingtv", "trendingmovies",
                    "foryou", "favorites", "allapps", "folders"
                ))

        // --- the pill tab model (core/HomeTabs) ---
        val tTabs = HomeTabs.trackerTabs()
        check("tabs: Tracker/Watch = Home · Library · Apps",
            tTabs.map { it.id } == listOf("home", "library", "apps") &&
                tTabs.map { it.label } == listOf("Home", "Library", "Apps"))
        check("tabs: only Home is selected, and it is STAY",
            tTabs.count { it.selected } == 1 &&
                tTabs.first().action == HomeTabs.Action.STAY)
        check("tabs: Library opens the library; Apps jumps focus to All Apps",
            tTabs[1].action == HomeTabs.Action.OPEN_LIBRARY &&
                tTabs[2].action == HomeTabs.Action.FOCUS_ALL_APPS)
        val iTabs = HomeTabs.iptvTabs()
        check("tabs: IPTV = Home · Live · Movies · Series · Guide · Apps",
            iTabs.map { it.id } == listOf("home", "live", "movies", "series", "guide", "apps"))
        check("tabs: IPTV actions map Live/Movies/Series/Guide/Apps",
            iTabs[1].action == HomeTabs.Action.OPEN_IPTV_LIVE &&
                iTabs[2].action == HomeTabs.Action.OPEN_IPTV_MOVIES &&
                iTabs[3].action == HomeTabs.Action.OPEN_IPTV_SERIES &&
                iTabs[4].action == HomeTabs.Action.OPEN_GUIDE &&
                iTabs[5].action == HomeTabs.Action.FOCUS_ALL_APPS &&
                iTabs.count { it.selected } == 1)
        check("tabs: the TV style has no tab bar; tracker+watch share one; iptv gets its own",
            HomeTabs.forStyle("tv").isEmpty() &&
                HomeTabs.forStyle("tracker") == tTabs &&
                HomeTabs.forStyle("watch") == tTabs &&
                HomeTabs.forStyle("iptv") == iTabs)

        // --- the hero primary decision (Open in Tracker demotion) ---
        // Re-pinned in v2.9: the decision moved to
        // core/WatchMatch.heroPrimary and keys on the MATCH, not
        // on "play resolvable" — the v2.8 rule (TrackerLogic
        // .heroPrimaryAction, retired) is what left a matched but
        // unwarmed hero with no Play button (ask F.25). The full
        // routing machine is pinned in §47.
        check("hero: a matched title's primary is Play (v2.9 rule)",
            WatchMatch.heroPrimary(true, "movie", 0, 0).action ==
                WatchMatch.PressAction.PLAY)
        check("hero: an unmatched title's primary is Details — never the tracker",
            WatchMatch.heroPrimary(false, "movie", 0, 0).action ==
                WatchMatch.PressAction.DETAILS)

        // --- the startup line shaping (fake clock values) ---
        check("startup: both timings recorded",
            DiagStartup.line(412, 2380) == "startup: first frame 412 ms · full 2380 ms")
        check("startup: nothing recorded renders dashes, never a fake 0",
            DiagStartup.line(-1, -1) == "startup: first frame — · full —")
        check("startup: a zero first frame is honest; a missing full stays a dash",
            DiagStartup.line(0, -1) == "startup: first frame 0 ms · full —")

        // --- source checks: the v2.8 wiring is really in the app ---
        fun src(path: String): String = try {
            java.io.File(path).readText()
        } catch (e: Exception) {
            ""
        }
        val mainSrc = src("app/src/main/java/com/mizan/launcher/MainActivity.kt")
        // Re-pinned in v2.9: buildTabBar() is gone — the tab bar
        // merged into the unified bar (ask C.13); the model call
        // is UnifiedBar.items over the same HomeTabs model.
        check("v28src: both media homes build the pill bar from the pure model",
            mainSrc.contains("buildUnifiedBar(overHero") &&
                mainSrc.contains("UnifiedBar.items(homeStyleKey()"))
        check("v28src: the landscape rail + circular All Apps builders exist and are used",
            mainSrc.contains("fun landscapeCard(") &&
                mainSrc.contains("fun buildAppsSectionCircular()") &&
                mainSrc.contains("fun appCircleTile("))
        // Re-pinned in v2.9: the hero primary routes through
        // WatchMatch.heroPrimary + playHeroPrimary, and there is
        // NO Open Tracker hero button at all anymore (ask F.25 —
        // v2.8 kept it as a secondary; the audit removed it).
        check("v28src: the hero primary routes through the pinned decision; Open Tracker is never a hero button",
            mainSrc.contains("WatchMatch.heroPrimary(") &&
                mainSrc.contains("playHeroPrimary(hero, heroCoords") &&
                !mainSrc.contains("heroButton(\"Open Tracker\""))
        // Re-pinned in v3.0 (intentional change): the deferred prepare
        // call is unchanged semantically (tracker style, warm builds
        // only) but now sits inside the v3.0 Throwable guard, so the
        // pinned line is the guard's try form (spec §30).
        // Re-pinned AGAIN in v3.5 (intentional change, spec §35):
        // the colddwarm threads are deleted — the cold chain
        // replaces them (see section 55); the staged shell +
        // deferred prepares + shell lines below still hold.
        check("v28src: cold start is staged (warm worker + deferred prepares + shell lines)",
            mainSrc.contains("ColdChainRunner.kickAfterSurvival(appCtx)") &&
                mainSrc.contains("if (trackerStyle && !coldStart) {") &&
                mainSrc.contains("try { prepareTrackerData() } catch (e: Throwable)") &&
                mainSrc.contains("Loading your library…") &&
                mainSrc.contains("Loading your channels…"))
        val detailsSrc = src("app/src/main/java/com/mizan/launcher/ui/DetailsActivity.kt")
        // v3.7 RE-PINNED (launcher IS the tracker): the More
        // overflow held ONLY Open in Tracker, so the whole
        // menu block is deleted — no More button, no tracker
        // entry anywhere in Details.
        check("v28src: Details has no Open in Tracker anywhere — the More overflow is gone with it",
            !detailsSrc.contains("Open in Tracker") &&
            !detailsSrc.contains("⋯ More"))
        val iptvCardsSrc = src("app/src/main/java/com/mizan/launcher/ui/IptvCards.kt")
        check("v28src: IPTV cards carry the LIVE pill + in-frame progress + focus border",
            iptvCardsSrc.contains("liveBadge") &&
                iptvCardsSrc.contains("barInFrame") &&
                iptvCardsSrc.contains("focusBorder"))
        val settingsSrc = src("app/src/main/java/com/mizan/launcher/ui/SettingsActivity.kt")
        check("v28src: settings nav fixes are in (no selectable dead stop, index memory, focus net, startup line)",
            !settingsSrc.contains("setTextIsSelectable") &&
                settingsSrc.contains("detailFocusIndexMemory") &&
                settingsSrc.contains("onWindowFocusChanged") &&
                settingsSrc.contains("DiagStartup.line"))
        val themesSrc = src("app/src/main/res/values/themes.xml")
        check("v28src: the window background is the brand splash, not a black void",
            themesSrc.contains("@drawable/splash_bg"))
        val diagLogSrc = src("app/src/main/java/com/mizan/launcher/data/DiagLog.kt")
        check("v28src: DiagLog records the startup milestones",
            diagLogSrc.contains("recordFirstFrame") &&
                diagLogSrc.contains("recordHomeComplete") &&
                diagLogSrc.contains("markStartupStart"))
        val prefsSrc = src("app/src/main/java/com/mizan/launcher/data/Prefs.kt")
        check("v28src: the one-time order migration is wired into the sections read",
            prefsSrc.contains("tracker_sections_migrated_28") &&
                prefsSrc.contains("TrackerSections.migrateOrder"))
    }
}

/** v2.9 checks — the full-audit fixes: play routing, artwork fit, unified bar, error scrub, discovery source. */
fun section47() {
    println("== (47) v2.9: audit fixes — play routing, artwork fit, unified bar, scrub, discovery source ==")

    // --- play routing (asks F.25 / D.15): the MATCH decides ----
    check("route: a matched card's OK press plays",
        WatchMatch.pressAction(true) == WatchMatch.PressAction.PLAY)
    check("route: an unmatched card's OK press opens Details (never the tracker)",
        WatchMatch.pressAction(false) == WatchMatch.PressAction.DETAILS)
    val heroMovie = WatchMatch.heroPrimary(true, "movie", 0, 0)
    check("route: a matched movie hero leads with ▶ Play",
        heroMovie.action == WatchMatch.PressAction.PLAY && heroMovie.label == "▶ Play")
    val heroShow = WatchMatch.heroPrimary(true, "tv", 3, 7)
    check("route: a matched show hero leads with ▶ Play S3 E7",
        heroShow.action == WatchMatch.PressAction.PLAY && heroShow.label == "▶ Play S3 E7")
    val heroUnmatched = WatchMatch.heroPrimary(false, "tv", 3, 7)
    check("route: an unmatched hero leads with Details — Play is never offered",
        heroUnmatched.action == WatchMatch.PressAction.DETAILS &&
            heroUnmatched.label == "ⓘ Details")

    // --- the resolve-then-play state machine -------------------
    check("resolve: idle + press starts finding",
        WatchMatch.resolvePhase(WatchMatch.ResolvePhase.IDLE, WatchMatch.ResolveEvent.PRESS) ==
            WatchMatch.ResolvePhase.FINDING)
    check("resolve: a second press while finding is ignored (no double resolve)",
        WatchMatch.resolvePhase(WatchMatch.ResolvePhase.FINDING, WatchMatch.ResolveEvent.PRESS) ==
            WatchMatch.ResolvePhase.FINDING)
    check("resolve: finding + found hands off to the player",
        WatchMatch.resolvePhase(WatchMatch.ResolvePhase.FINDING, WatchMatch.ResolveEvent.FOUND) ==
            WatchMatch.ResolvePhase.PLAYING)
    check("resolve: finding + not found fails (the caller falls back to Details)",
        WatchMatch.resolvePhase(WatchMatch.ResolvePhase.FINDING, WatchMatch.ResolveEvent.NOT_FOUND) ==
            WatchMatch.ResolvePhase.FAILED)
    check("resolve: failed + press retries the find",
        WatchMatch.resolvePhase(WatchMatch.ResolvePhase.FAILED, WatchMatch.ResolveEvent.PRESS) ==
            WatchMatch.ResolvePhase.FINDING)
    check("resolve: the button reads Finding stream… only while finding",
        WatchMatch.resolveLabel(WatchMatch.ResolvePhase.FINDING, "▶ Play S3 E7") == "Finding stream…" &&
            WatchMatch.resolveLabel(WatchMatch.ResolvePhase.IDLE, "▶ Play S3 E7") == "▶ Play S3 E7" &&
            WatchMatch.resolveLabel(WatchMatch.ResolvePhase.FAILED, "▶ Play S3 E7") == "▶ Play S3 E7")

    // --- artwork fit (ask C.14) --------------------------------
    check("art: landscape art in a landscape frame crops to fill",
        Artwork.landscapeFit(true) == Artwork.Fit.CROP_FILL)
    check("art: a portrait poster in a landscape frame renders COMPLETE, never cropped",
        Artwork.landscapeFit(false) == Artwork.Fit.FIT_COMPLETE)
    check("art: a poster in its own 2:3 frame fills it",
        Artwork.posterFit() == Artwork.Fit.CROP_FILL)
    check("art: the details header crops a real backdrop but fits a poster complete",
        Artwork.headerFit(true) == Artwork.Fit.CROP_FILL &&
            Artwork.headerFit(false) == Artwork.Fit.FIT_COMPLETE)

    // --- the unified bar model (ask C.13) ----------------------
    val defCfg = StatusBarConfig()
    check("bar: the TV style has no unified bar (it keeps its own top bar)",
        UnifiedBar.items("tv", defCfg, guest = false, safeMode = false).isEmpty())
    check("bar: tracker = 3 tabs, then info + glyphs + the config's tiles as pills",
        UnifiedBar.items("tracker", defCfg, guest = false, safeMode = false) == listOf(
            UnifiedBar.Item.TAB, UnifiedBar.Item.TAB, UnifiedBar.Item.TAB,
            UnifiedBar.Item.INFO, UnifiedBar.Item.GLYPHS,
            UnifiedBar.Item.TILE_ALL_APPS, UnifiedBar.Item.TILE_SEARCH,
            UnifiedBar.Item.TILE_PROFILE, UnifiedBar.Item.TILE_SETTINGS
        ))
    check("bar: the watch style shares the tracker's bar shape (6 IPTV tabs on iptv)",
        UnifiedBar.items("watch", defCfg, guest = false, safeMode = false).count {
            it == UnifiedBar.Item.TAB
        } == 3 &&
            UnifiedBar.items("iptv", defCfg, guest = false, safeMode = false).count {
                it == UnifiedBar.Item.TAB
            } == 6)
    check("bar: showBar off hides the whole status group but never the tabs",
        UnifiedBar.items("tracker", defCfg.copy(showBar = false), guest = false, safeMode = false) ==
            listOf(UnifiedBar.Item.TAB, UnifiedBar.Item.TAB, UnifiedBar.Item.TAB))
    check("bar: clock+date+weather all off drops the info pill; glyphs follow their toggles",
        UnifiedBar.items(
            "tracker",
            defCfg.copy(showClock = false, showDate = false, showWeather = false),
            guest = false, safeMode = false
        ).let { !it.contains(UnifiedBar.Item.INFO) && it.contains(UnifiedBar.Item.GLYPHS) } &&
            UnifiedBar.items(
                "tracker",
                defCfg.copy(showNetwork = false, showBluetooth = false),
                guest = false, safeMode = false
            ).let { !it.contains(UnifiedBar.Item.GLYPHS) && it.contains(UnifiedBar.Item.INFO) })
    check("bar: the guest pill appears only for a guest profile outside safe mode",
        UnifiedBar.items("tracker", defCfg, guest = true, safeMode = false)
            .contains(UnifiedBar.Item.GUEST) &&
            !UnifiedBar.items("tracker", defCfg, guest = true, safeMode = true)
                .contains(UnifiedBar.Item.GUEST))
    check("bar: safe mode drops weather/guest but keeps clock+date info",
        UnifiedBar.items("tracker", defCfg, guest = false, safeMode = true)
            .contains(UnifiedBar.Item.INFO))

    // --- the diagnostics scrub (asks E.19 / H.29) --------------
    check("scrub: a credential URL in an error becomes ‹url›",
        DiagScrub.message("failed http://user:pass@panel.tv:8080/get.php?username=u&password=p") ==
            "failed ‹url›")
    check("scrub: an api_key parameter value is redacted",
        DiagScrub.message("GET failed api_key=abcdef123456&x=1") ==
            "GET failed api_key=‹redacted›&x=1")
    check("scrub: a plain message passes through untouched",
        DiagScrub.message("timeout after 10000ms") == "timeout after 10000ms")
    check("scrub: null and blank become empty; long text is capped",
        DiagScrub.message(null) == "" && DiagScrub.message("   ") == "" &&
            DiagScrub.message("x".repeat(500)).length == 160)

    // --- the discovery source word (ask H.30) ------------------
    check("discovery: launcher-computed For You reads launcher",
        Discovery.diagSource(
            forYouComputed = true, forYouFromTracker = false, launcherSetsLive = true
        ) == "launcher")
    // v3.7 RE-PINNED (launcher IS the tracker): the /foryou
    // section is the library's imported section now.
    check("discovery: library For You + live launcher sets reads launcher+library",
        Discovery.diagSource(
            forYouComputed = false, forYouFromTracker = true, launcherSetsLive = true
        ) == "launcher+library")
    check("discovery: library-only reads library",
        Discovery.diagSource(
            forYouComputed = false, forYouFromTracker = true, launcherSetsLive = false
        ) == "library")
    check("discovery: nothing supplied still reads library (the fallback word)",
        Discovery.diagSource(
            forYouComputed = false, forYouFromTracker = false, launcherSetsLive = false
        ) == "library")

    // --- source checks: the v2.9 wiring is really in the app ---
    fun src(path: String): String = try {
        java.io.File(path).readText()
    } catch (e: Exception) {
        ""
    }
    val mainSrc = src("app/src/main/java/com/mizan/launcher/MainActivity.kt")
    check("v29src: the warm-up is display-driven (no session cap survives)",
        mainSrc.contains("WatchPlay.warmUpDisplayed") &&
            !mainSrc.contains("WARM_CAP"))
    val watchPlaySrc = src("app/src/main/java/com/mizan/launcher/data/WatchPlay.kt")
    check("v29src: WatchPlay resolves on demand and warms what is displayed",
        watchPlaySrc.contains("fun warmUpDisplayed") &&
            watchPlaySrc.contains("fun resolveSeries"))
    // v3.7 RE-PINNED (launcher IS the tracker): openTracker
    // is deleted entirely — card menus carry Details /
    // Mark / Add only, via cardMenu.
    check("v29src: openTracker is gone; cardMenu replaces the old menu fn",
        !mainSrc.contains("private fun openTracker(") &&
            !mainSrc.contains("openTrackerCardMenu(") &&
            mainSrc.contains("private fun cardMenu("))
    check("v29src: the unified bar replaced the stacked bars in both media homes",
        mainSrc.contains("barHost.addView(buildUnifiedBar(overHero = false))") &&
            mainSrc.contains("barHost.addView(buildUnifiedBar(overHero = hero != null))") &&
            !mainSrc.contains("buildTabBar()"))
    check("v29src: the forecast opens from the bar's info cluster in every style",
        mainSrc.contains("fun openForecast()") &&
            mainSrc.contains("left.setOnClickListener { openForecast() }") &&
            mainSrc.contains("shell.setOnClickListener { openForecast() }"))
    // v3.7 RE-PINNED (poster fix): the fit decision still
    // comes from core/Artwork, but Up Next cards are
    // poster-first now — landscape is true only when the
    // poster is blank and a backdrop exists.
    check("v29src: landscape cards take the fit decision from core/Artwork",
        mainSrc.contains("Artwork.landscapeFit(imageLandscape)") &&
            mainSrc.contains("imageLandscape = item.poster.isBlank() && item.backdrop.isNotBlank()") &&
            mainSrc.contains("imageLandscape = false"))
    val settingsSrc = src("app/src/main/java/com/mizan/launcher/ui/SettingsActivity.kt")
    check("v29src: the settings app pickers no longer scan on the main thread",
        settingsSrc.contains("fun withApps(") &&
            settingsSrc.contains("MainActivity.AppCache.get(this)") &&
            !settingsSrc.contains("val apps = AppLoader.loadApps(packageManager, packageName)"))
    val prefsSrc = src("app/src/main/java/com/mizan/launcher/data/Prefs.kt")
    check("v29src: persisted error lines pass through the scrub",
        prefsSrc.contains("DiagScrub.message(e.message)"))
    val detailsSrc = src("app/src/main/java/com/mizan/launcher/ui/DetailsActivity.kt")
    check("v29src: the details header fits a poster complete (no banner crop)",
        detailsSrc.contains("Artwork.headerFit(hasBackdrop)"))
}

/** v3.0 checks — the crash-hardening redo (spec §30): startup health, section guards, parser depth caps, the prefs funnel. */
fun section48() {
    run {
        println("-- section 48: v3.0 crash hardening (startup health, section guards, depth caps, prefs funnel)")

        // ---- startup-health state machine (core/StartupHealth) ----
        val sh = StartupHealth
        check("health: threshold is two consecutive uncleared starts",
            sh.SAFE_SHELL_THRESHOLD == 2 && sh.SURVIVAL_MS == 10_000L)
        check("health: modeFor — 0/1 uncleared is NORMAL, 2+ is SAFE_SHELL, negatives coerce to NORMAL",
            sh.modeFor(0) == StartupHealth.Mode.NORMAL &&
                sh.modeFor(1) == StartupHealth.Mode.NORMAL &&
                sh.modeFor(2) == StartupHealth.Mode.SAFE_SHELL &&
                sh.modeFor(9) == StartupHealth.Mode.SAFE_SHELL &&
                sh.modeFor(-3) == StartupHealth.Mode.NORMAL)
        // The store protocol, composed exactly as data/StartupHealthStore
        // runs it: mode read from the counter, then onLaunch persists +1;
        // a completed render + survival applies onCleared.
        var counter = 0
        fun launch(dies: Boolean): StartupHealth.Mode {
            val mode = sh.modeFor(counter)
            counter = sh.onLaunch(counter)
            if (!dies) counter = sh.onCleared()
            return mode
        }
        val seq1 = listOf(launch(dies = true), launch(dies = true), launch(dies = true))
        check("health: [die, die] -> the THIRD launch is the safe shell",
            seq1 == listOf(
                StartupHealth.Mode.NORMAL,
                StartupHealth.Mode.NORMAL,
                StartupHealth.Mode.SAFE_SHELL
            ))
        counter = 0
        val seq2 = listOf(launch(dies = true), launch(dies = false), launch(dies = true), launch(dies = true))
        check("health: one clean render breaks the run — [die, clear, die, die] stays NORMAL throughout",
            seq2.all { it == StartupHealth.Mode.NORMAL } && counter == 2)
        val seq3mode = launch(dies = false)
        check("health: the launch after two consecutive deaths is SAFE_SHELL, and its clean render clears",
            seq3mode == StartupHealth.Mode.SAFE_SHELL && counter == 0)
        val seq4mode = launch(dies = false)
        check("health: after a cleared safe-shell launch the next launch is NORMAL again",
            seq4mode == StartupHealth.Mode.NORMAL && counter == 0)
        check("health: the notice names safe mode and points at Diagnostics",
            sh.NOTICE_TEXT.contains("safe mode") && sh.NOTICE_TEXT.contains("Diagnostics"))
        check("health: the diagnostics line carries the count and the shell state",
            sh.diagnosticsLine(2, true) == "startup health: 2 uncleared start(s) · safe shell ON" &&
                sh.diagnosticsLine(0, false) == "startup health: 0 uncleared start(s) · safe shell off")

        // ---- section-guard policy (core/SectionGuard + core/HomeFallback) ----
        check("guard: the error tile names the section and points at Diagnostics",
            SectionGuard.tileText("Up Next") ==
                "⚠ Up Next couldn't load — details in Settings › Diagnostics" &&
                SectionGuard.tileText("Apps").contains("Apps"))
        check("guard: reporting is throttled to one per section per 60s window",
            SectionGuard.shouldReport(0L, 100_000L) &&
                !SectionGuard.shouldReport(100_000L, 130_000L) &&
                SectionGuard.shouldReport(100_000L, 160_000L) &&
                !SectionGuard.shouldReport(100_000L, 159_999L))
        check("fallback: tracker/watch/iptv homes fall back to the TV render; tv/unknown to the emergency root",
            HomeFallback.fallbackFor("tracker") == HomeFallback.Fallback.TV_RENDER &&
                HomeFallback.fallbackFor("watch") == HomeFallback.Fallback.TV_RENDER &&
                HomeFallback.fallbackFor("iptv") == HomeFallback.Fallback.TV_RENDER &&
                HomeFallback.fallbackFor("tv") == HomeFallback.Fallback.EMERGENCY_ROOT &&
                HomeFallback.fallbackFor("banana") == HomeFallback.Fallback.EMERGENCY_ROOT)
        // The containment contract, modelled as data (the Android side
        // renders views, which the sim cannot run — the source checks
        // below pin the wiring): a section builder that throws ANY
        // Throwable — an Error included — yields its tile in place and
        // the sections around it still compose.
        val composed = ArrayList<String>()
        val builders: List<Pair<String, () -> String>> = listOf(
            "one" to { "row-one" },
            "two" to { throw OutOfMemoryError("simulated inflation OOM") },
            "three" to { "row-three" }
        )
        for ((label, build) in builders) {
            composed.add(
                try { build() } catch (e: Throwable) { SectionGuard.tileText(label) }
            )
        }
        check("guard: a throwing section (Error included) yields its tile; the others still render",
            composed == listOf("row-one", SectionGuard.tileText("two"), "row-three"))

        // ---- JSON depth caps (core/JsonMini + core/IptvStream) ----
        check("json: the depth cap is 64",
            JsonMini.MAX_DEPTH == 64)
        check("json: a 5000-deep document parses to null instead of overflowing the stack",
            JsonMini.parse("[".repeat(5000) + "]".repeat(5000)) == null)
        check("json: nesting inside the cap parses (60 levels), past it reads as malformed (100 levels)",
            JsonMini.parse("[".repeat(60) + "1" + "]".repeat(60)) != null &&
                JsonMini.parse("[".repeat(100) + "1" + "]".repeat(100)) == null)
        val wide = "[" + (1..200).joinToString(",") { "{\"a\":[$it]}" } + "]"
        check("json: a wide shallow document (200 siblings at depth 3) parses — depth is per-branch, not cumulative",
            (JsonMini.parse(wide) as? List<*>)?.size == 200)
        check("json: malformed documents read as null (truncated, bad token, trailing garbage, empty)",
            JsonMini.parse("{") == null &&
                JsonMini.parse("{\"a\":}") == null &&
                JsonMini.parse("[1,2") == null &&
                JsonMini.parse("{\"a\":1} trailing") == null &&
                JsonMini.parse("") == null &&
                JsonMini.parse(null) == null)
        val streamOk = try {
            var n = 0
            IptvStream.streamArray(java.io.StringReader("[{\"id\":1},{\"id\":2}]")) { n++ }
            n
        } catch (e: Throwable) {
            -1
        }
        check("stream: a small array still streams element by element", streamOk == 2)
        val deepElement = "[{\"x\":" + "[".repeat(5000) + "]".repeat(5000) + "}]"
        val deepOutcome = try {
            IptvStream.streamArray(java.io.StringReader(deepElement)) { }
            "no-throw"
        } catch (e: IllegalArgumentException) {
            "iae"
        } catch (e: Throwable) {
            "other:" + e.javaClass.simpleName
        }
        check("stream: a hostile deeply-nested element fails as IllegalArgumentException, not StackOverflowError",
            deepOutcome == "iae", deepOutcome)
        val badStream = try {
            IptvStream.streamArray(java.io.StringReader("[{\"id\":}]")) { }
            "no-throw"
        } catch (e: IllegalArgumentException) {
            "iae"
        } catch (e: Throwable) {
            "other"
        }
        check("stream: malformed input throws (the ingest keeps the previous store)", badStream == "iae")

        // ---- malformed fixtures for the network-fed parsers ----
        check("fixtures: discovery parsers fail open on garbage (trending empty, facts null)",
            Discovery.parseTrending("not json", "tv").isEmpty() &&
                Discovery.parseTrending(null, "movie").isEmpty() &&
                Discovery.parseTrending("{\"results\":{}}", "tv").isEmpty() &&
                Discovery.parseTvFacts("garbage") == null &&
                Discovery.parseTvFacts("{") == null &&
                Discovery.parseMovieFacts(null) == null)
        check("fixtures: series-episodes parser fails open on garbage",
            LibraryLogic.parseSeasonEpisodes("garbage").isEmpty() &&
                LibraryLogic.parseSeasonEpisodes(null).isEmpty() &&
                LibraryLogic.parseSeasonEpisodes("{\"episodes\":\"nope\"}").isEmpty())
        check("fixtures: the IPTV store index decoder rejects garbage",
            IptvStore.decodeIndex("garbage") == null &&
                IptvStore.decodeIndex(null) == null &&
                IptvStore.decodeIndex("[]") == null)
        check("fixtures: the short-EPG parser fails open on garbage",
            IptvEpg.parseShortEpg("garbage", "ch1").isEmpty() &&
                IptvEpg.parseShortEpg(null, "ch1").isEmpty())

        // ---- source checks: the v3.0 wiring is really in the app ----
        fun src(path: String): String = try {
            java.io.File(path).readText()
        } catch (e: Exception) {
            ""
        }
        // The prefs funnel: no raw typed reads survive in Prefs.kt,
        // and every read routes through data/PrefsSafe.
        val prefsSrc = src("app/src/main/java/com/mizan/launcher/data/Prefs.kt")
        check("v30src: Prefs reads carry no raw typed getters any more",
            prefsSrc.isNotEmpty() &&
                !prefsSrc.contains(".getString(") && !prefsSrc.contains(".getInt(") &&
                !prefsSrc.contains(".getBoolean(") && !prefsSrc.contains(".getLong(") &&
                !prefsSrc.contains(".getStringSet("))
        check("v30src: Prefs routes its reads through PrefsSafe",
            prefsSrc.contains("PrefsSafe.str(") && prefsSrc.contains("PrefsSafe.int(") &&
                prefsSrc.contains("PrefsSafe.bool(") && prefsSrc.contains("PrefsSafe.long(") &&
                prefsSrc.contains("PrefsSafe.stringSet("))
        val safeSrc = src("app/src/main/java/com/mizan/launcher/data/PrefsSafe.kt")
        check("v30src: PrefsSafe is the ClassCastException funnel (catch + evict + default)",
            safeSrc.contains("catch (e: ClassCastException)") && safeSrc.contains("remove(key)"))
        val trackerSrc = src("app/src/main/java/com/mizan/launcher/data/TrackerClient.kt")
        val iptvSrc = src("app/src/main/java/com/mizan/launcher/data/IptvClient.kt")
        val discoverySrc = src("app/src/main/java/com/mizan/launcher/data/DiscoveryData.kt")
        val watchPlaySrc = src("app/src/main/java/com/mizan/launcher/data/WatchPlay.kt")
        check("v30src: the data clients read prefs through PrefsSafe too (no raw p.getString survives)",
            trackerSrc.contains("PrefsSafe.") && iptvSrc.contains("PrefsSafe.") &&
                discoverySrc.contains("PrefsSafe.") && watchPlaySrc.contains("PrefsSafe.") &&
                !trackerSrc.contains("p.getString(") && !iptvSrc.contains("p.getString(") &&
                !discoverySrc.contains("p.getString(") && !watchPlaySrc.contains("p.getString("))
        // Startup health: store, bootstrap, shell gates, diagnostics line.
        val storeSrc = src("app/src/main/java/com/mizan/launcher/data/StartupHealthStore.kt")
        check("v30src: the health counter lives in its own tiny prefs file, committed synchronously",
            storeSrc.contains("\"mizan_health\"") && storeSrc.contains("commit()") &&
                storeSrc.contains("fun beginLaunch") && storeSrc.contains("fun markCleared"))
        val appSrc = src("app/src/main/java/com/mizan/launcher/App.kt")
        check("v30src: App.onCreate begins the startup AFTER the diagnostics install",
            appSrc.contains("StartupHealthStore.beginLaunch(this)") &&
                appSrc.indexOf("DiagLog.install(this)") <
                appSrc.indexOf("StartupHealthStore.beginLaunch(this)"))
        val mainSrc = src("app/src/main/java/com/mizan/launcher/MainActivity.kt")
        check("v30src: MainActivity reads the shell decision and forces TV in the shell",
            mainSrc.contains("StartupHealthStore.safeShellArmed") &&
                mainSrc.contains("prefs.safeMode || safeShell"))
        check("v30src: the shell renders its notice and the TV-style row gate uses the shell gate",
            mainSrc.contains("StartupHealth.NOTICE_TEXT") &&
                mainSrc.contains("val rowKeys = if (shellSafe)"))
        check("v30src: a completed render arms the survival clear (10s, main looper)",
            mainSrc.contains("scheduleStartupClear") &&
                mainSrc.contains("StartupHealth.SURVIVAL_MS") &&
                mainSrc.contains("StartupHealthStore.markCleared"))
        val settingsSrc = src("app/src/main/java/com/mizan/launcher/ui/SettingsActivity.kt")
        check("v30src: diagnostics carries the startup-health line",
            settingsSrc.contains("StartupHealthStore") && settingsSrc.contains("diagnosticsLine"))
        // Crash containment: whole-home guards, emergency root, tiles.
        check("v30src: the tracker home carries the whole-home Throwable guard (was unguarded)",
            mainSrc.contains("Prefs.noteError(this, \"trackerhome\", e)") &&
                mainSrc.contains("catch (e: Throwable)"))
        check("v30src: rebuild() itself is Throwable-guarded and renders the emergency root",
            mainSrc.contains("private fun buildEmergencyRoot()") &&
                mainSrc.contains("Prefs.noteError(this, \"rebuild\", e)"))
        check("v30src: failed home sections render the SectionGuard error tile in all three homes",
            mainSrc.contains("sectionErrorTile(rowTileLabel(key))") &&
                mainSrc.contains("sectionErrorTile(trackerTileLabel(key))") &&
                mainSrc.contains("sectionErrorTile(\"IPTV row\")"))
        check("v30src: every secondary activity's build is guarded (its noteError tag is present)",
            settingsSrc.contains("\"settingsRebuild\"") &&
                src("app/src/main/java/com/mizan/launcher/ui/DetailsActivity.kt")
                    .contains("\"detailsRebuild\"") &&
                src("app/src/main/java/com/mizan/launcher/ui/LibraryActivity.kt")
                    .contains("\"libraryRebuild\"") &&
                src("app/src/main/java/com/mizan/launcher/ui/ForecastActivity.kt")
                    .contains("\"forecastRender\"") &&
                src("app/src/main/java/com/mizan/launcher/ui/SearchActivity.kt")
                    .contains("\"searchRoot\"") &&
                src("app/src/main/java/com/mizan/launcher/ui/TmdbSearchActivity.kt")
                    .contains("\"tmdbSearchRoot\"") &&
                src("app/src/main/java/com/mizan/launcher/ui/AllAppsActivity.kt")
                    .contains("\"allAppsRoot\"") &&
                src("app/src/main/java/com/mizan/launcher/ui/PlayerActivity.kt")
                    .contains("\"playerCreate\""))
        check("v30src: a single settings page is contained (page tag) without killing Settings",
            settingsSrc.contains("\"settingsPage:"))
        // Death semantics: worker uncaughts log + degrade; only main chains.
        val diagSrc = src("app/src/main/java/com/mizan/launcher/data/DiagLog.kt")
        check("v30src: the crash handler chains to the system handler ONLY for the main thread",
            diagSrc.contains("t === Looper.getMainLooper().thread") && diagSrc.contains("if (isMain)"))
        check("v30src: the watchdog's judge block is Throwable-guarded (it cannot die silently)",
            diagSrc.contains("Keep watching"))
        // Decode bounds: one funnel, every former raw path routed through it.
        val artworkSrc = src("app/src/main/java/com/mizan/launcher/data/ArtworkImages.kt")
        val accentSrc = src("app/src/main/java/com/mizan/launcher/data/AccentUtil.kt")
        val weatherArtSrc = src("app/src/main/java/com/mizan/launcher/data/WeatherArt.kt")
        val boundsSrc = src("app/src/main/java/com/mizan/launcher/data/DecodeBounds.kt")
        check("v30src: artwork decodes are bounded through DecodeBounds (no raw decode left)",
            artworkSrc.contains("DecodeBounds.decodeBytesBounded") &&
                artworkSrc.contains("DecodeBounds.decodeFileBounded") &&
                !artworkSrc.contains("BitmapFactory.decode"))
        check("v30src: wallpaper decodes sample from measured bounds, not a fixed factor",
            accentSrc.contains("DecodeBounds.sampleSize") &&
                accentSrc.contains("DecodeBounds.decodeFileBounded") &&
                !accentSrc.contains("inSampleSize = 4"))
        check("v30src: the weather-art render is size-capped and DecodeBounds does the two-pass decode",
            weatherArtSrc.contains("MAX_DIM = 1920") &&
                boundsSrc.contains("inJustDecodeBounds") && boundsSrc.contains("fun sampleSize"))
        // Store swap: rename-first (no delete-then-rename window).
        val swapBody = iptvSrc.substringAfter("private fun swapFile").substringBefore("private fun ingestOnce")
        check("v30src: swapFile renames over the destination first and never deletes it first",
            swapBody.contains("tmp.renameTo(dest)") && !swapBody.contains("dest.delete()"))
        check("v30src: the ingest loop's per-endpoint catch is Throwable (callbacks still drain in finally)",
            iptvSrc.contains("} catch (e: Throwable) {") && iptvSrc.contains("iptvIngest"))
    }
}

/**
 * v3.0 §49 — corrupt/partial STARTUP state (the crash-on-open
 * fixtures): the exact shapes a cold open consumes on his TV —
 * profile-2 prefs values, a migrated section order, tracker
 * snapshots, the match cache, discovery cache files — each fed
 * truncated, wrong-typed or half-migrated, proving every startup
 * decoder fails open instead of killing the open. (Prefs values
 * whose TYPE is wrong are covered by the PrefsSafe funnel —
 * Android-layer, source-pinned in §48; what the sim proves here
 * is the string-content layer every one of those values feeds.)
 */
fun section49() {
    run {
        println("-- section 49: v3.0 corrupt/partial startup state (the app still opens)")

        // ---- layout document (prefs "layout"): truncated + garbled lines ----
        val layoutTrunc = LayoutCodec.decode(
            "rows=favorites,apps\nfav=com.example.tv,truncat"
        )
        check("startup-state: a truncated layout document still decodes (good lines survive)",
            layoutTrunc.rowOrder == listOf("favorites", "apps"))
        val layoutGarbage = LayoutCodec.decode(
            "rows=\nstatus=%%%\nfolder2=\u0000broken\n=orphan\nrows=favorites"
        )
        check("startup-state: a garbled layout document decodes to a usable layout, never throws",
            layoutGarbage.rowOrder.isNotEmpty())
        check("startup-state: empty/absent layout documents decode to defaults",
            LayoutCodec.decode(null).rowOrder.isNotEmpty() &&
                LayoutCodec.decode("").rowOrder.isNotEmpty())

        // ---- row order (TV home): unknown + partial stored orders ----
        // (normalize splices the four insertion rows around their
        // anchors; base rows come from the stored order itself —
        // unknown keys ride along and the render loop skips them.)
        val rowsNorm = RowOrder.normalize(listOf("apps", "banana"))
        check("startup-state: a partial row order with an unknown key normalizes without throwing",
            rowsNorm.contains("apps") && rowsNorm.contains("recents") &&
                rowsNorm.contains("livetv"))
        val rowsAnchored = RowOrder.normalize(listOf("favorites", "apps"))
        check("startup-state: insertion rows splice after their anchors in a partial order",
            rowsAnchored.indexOf("recents") == rowsAnchored.indexOf("favorites") + 1)

        // ---- tracker section order: half-migrated + garbled ----
        val halfMigrated = TrackerSections.decode("upnext,continue,movienight,foryou,favorites,allapps|")
        val migrated = TrackerSections.migrateOrder(halfMigrated)
        // v3.3 RE-PINNED (ask 47): comingsoon is gone with the merge.
        check("startup-state: the half-migrated section order migrates without losing sections",
            migrated.order.containsAll(
                listOf("upnext", "continue", "trendingtv", "trendingmovies")
            ) && !migrated.order.contains("comingsoon"))
        val garbledSections = TrackerSections.decode("xyz,hero,hero,|bogus,,")
        val composedGarbled = TrackerSections.compose(garbledSections) { true }
        check("startup-state: a garbled section order composes canonically (no dupes, no unknowns)",
            composedGarbled == composedGarbled.distinct() &&
                composedGarbled.all { it in TrackerSections.CANONICAL })
        check("startup-state: a truncated section order (cut mid-token) still composes",
            TrackerSections.compose(TrackerSections.decode("upn|")) { true }.isNotEmpty())

        // ---- home style: stored values from other versions ----
        check("startup-state: stored home styles normalize (retired ipad -> tv, garbage -> tv)",
            HomeStyle.normalize("watch") == "watch" &&
                HomeStyle.normalize("ipad") == "tv" &&
                HomeStyle.normalize("banana") == "tv" &&
                HomeStyle.normalize(null) == "tv")

        // ---- tracker snapshots (the warm-open parse): truncated documents ----
        check("startup-state: truncated tracker snapshot documents parse to empty, never throw",
            TrackerLogic.parseUpNext("{\"items\":[{\"tmdbId\":1,").isEmpty() &&
                TrackerLogic.parseContinue("{\"items\":[").isEmpty() &&
                TrackerLogic.parseMovies("garbage").isEmpty() &&
                TrackerLogic.parseTrackedShows("[{\"id\":").isEmpty() &&
                TrackerLogic.parseForYou("").isEmpty())

        // ---- watch match cache (line codec): truncated/garbled lines ----
        val matches = WatchMatch.decodeMatches(
            "123|tv|456|1|1\n789|tv|bad|1|0\npartial|\n\n42|movie|7|0|0"
        )
        check("startup-state: a half-corrupt match cache keeps its good lines and skips the rest",
            matches.size == 2 && matches[0].tmdbId == 123L && matches[1].tmdbId == 42L)
        check("startup-state: an absent match cache decodes empty",
            WatchMatch.decodeMatches(null).isEmpty() && WatchMatch.decodeMatches("").isEmpty())

        // ---- discovery cache files (v2.7/v2.9-written): empty + truncated ----
        check("startup-state: empty/truncated discovery cache documents read as no data",
            Discovery.parseTrending("", "tv").isEmpty() &&
                Discovery.parseTrending("{\"results\":[{\"id\":1,\"name\":\"X\"", "tv").isEmpty() &&
                Discovery.parseTrending("{\"results\":[", null).isEmpty())

        // ---- IPTV store index: truncated mid-document, wrong-typed counts ----
        check("startup-state: a truncated store index decodes to null (the store re-derives, the open survives)",
            IptvStore.decodeIndex("{\"live\":11380,\"vod\":319") == null)
        check("startup-state: a wrong-typed store index count degrades to 0 in a valid index, never throws",
            IptvStore.decodeIndex("{\"live\":\"eleven\"}")?.liveCount == 0)

        // ---- source checks: the degraded-build gate + worker widenings ----
        fun src(path: String): String = try {
            java.io.File(path).readText()
        } catch (e: Exception) {
            ""
        }
        val mainSrc = src("app/src/main/java/com/mizan/launcher/MainActivity.kt")
        check("v30src: a whole-home fallback holds the startup clear (degraded gate wired)",
            mainSrc.contains("buildDegraded = true") &&
                mainSrc.contains("onStartupRenderCompleted") &&
                mainSrc.contains("startup health: degraded build — clear held"))
        val watchPlaySrc = src("app/src/main/java/com/mizan/launcher/data/WatchPlay.kt")
        val discoverySrc = src("app/src/main/java/com/mizan/launcher/data/DiscoveryData.kt")
        check("v30src: the open-path workers record Errors too (Throwable catches, finally-reset flags)",
            watchPlaySrc.contains("catch (e: Throwable)") &&
                discoverySrc.contains("catch (e: Throwable)"))
    }
}

/**
 * v3.1 (spec §31): the five 2026-10-24 directives.
 * (37) Movie Night removed — registry, defaults, composition, hero
 * pool, stored orders. (38) Local-first home cache — TTLs,
 * staleness wording, size prune, credential tripwire. (39) One
 * module per section — LOADING → READY / EMPTY / ERROR + retry
 * routing. (40) Play in… — candidate filtering (self excluded,
 * no hardcoded packages). (41) Sort page — the pure stores it
 * edits (TrackerSections move/hide, QuickSettings internal target).
 */
fun section50() {
    run {
        val ts = TrackerSections
        val hour = 60L * 60L * 1000L

        // ---- (37) Movie Night is gone everywhere ----
        check("v31: movienight is not in the canonical order or the row set",
            "movienight" !in ts.CANONICAL && "movienight" !in ts.ROWS)
        check("v31: no section claims the Movie Night label anymore",
            ts.label("movienight") != "Movie Night")
        val stored = ts.decode("upnext,continue,movienight,foryou,favorites,allapps|movienight,comingsoon")
        check("v31: a stored order mentioning movienight loses it (order AND hidden) without breaking",
            "movienight" !in stored.order && "movienight" !in stored.hidden &&
                stored.order.containsAll(listOf("upnext", "continue", "foryou", "favorites", "allapps")))
        check("v31: compose never emits movienight, even for a stored config that mentions it",
            "movienight" !in ts.compose(stored) { true })
        check("v31: move/hide on the removed key are no-ops (the sort page can't resurrect it)",
            ts.move(ts.decode(null), "movienight", -1) == ts.decode(null) &&
                ts.setShown(ts.decode(null), "movienight", false) == ts.decode(null))
        val pool = TrackerLogic.heroCandidates(
            listOf(TrackerLogic.UpNextItem(1, "U", "S1 E1", "2026-11-01", "", "/u.jpg", "", 1, 1, "tv")),
            emptyList(),
            listOf(TrackerLogic.ForYouPick(2, "tv", "P", "/p.jpg", 7.0, "R", emptyList(), 5.0)),
            mapOf("tv|2" to "/p-back.jpg"),
            "2026-11-01"
        )
        check("v31: the hero pool carries no Movie Night source (today item lands in the up-next tier)",
            pool.none { it.source == "Movie Night" } &&
                pool.map { it.source } == listOf("Up Next") &&
                pool[0].sub == "Up next · S1 E1")

        // ---- (38) HomeCache: TTLs, staleness, prune, credential tripwire ----
        check("v31: cache TTLs are trending 12h, discovery + snapshots 24h",
            HomeCache.TRENDING_TTL_MS == 12L * hour &&
                HomeCache.DISCOVERY_TTL_MS == 24L * hour &&
                HomeCache.SNAPSHOT_TTL_MS == 24L * hour)
        val now = 1_000_000_000_000L
        check("v31: freshness is strict inside the TTL and false at/after it, for 0, and for the future",
            HomeCache.isFresh(now - hour, 12L * hour, now) &&
                !HomeCache.isFresh(now - 12L * hour, 12L * hour, now) &&
                !HomeCache.isFresh(0L, 12L * hour, now) &&
                !HomeCache.isFresh(now + hour, 12L * hour, now))
        check("v31: age wording is no data / minutes / hours / days",
            HomeCache.ageLabel(0L, now) == "no data" &&
                HomeCache.ageLabel(now - 30L * 60L * 1000L, now) == "30m old" &&
                HomeCache.ageLabel(now - 3L * hour, now) == "3h old" &&
                HomeCache.ageLabel(now - 50L * hour, now) == "2d old")
        val manifest = listOf(
            HomeCache.Entry("upnext", now - 5L * hour, 1000L),
            HomeCache.Entry("continue", now - 3L * hour, 1000L),
            HomeCache.Entry("foryou", now - 1L * hour, 1000L)
        )
        check("v31: prune under the cap deletes nothing",
            HomeCache.prune(manifest, keepSection = "foryou", capBytes = 10_000L).isEmpty())
        check("v31: prune over the cap deletes oldest first and never the section being written",
            HomeCache.prune(manifest, keepSection = "foryou", capBytes = 2_500L) == listOf("upnext"))
        check("v31: prune keeps the written section even when it alone busts the cap",
            HomeCache.prune(
                listOf(HomeCache.Entry("foryou", now, 9_999L)),
                keepSection = "foryou", capBytes = 100L
            ).isEmpty())
        check("v31: the credential tripwire fires on Xtream/TMDB shapes and passes clean snapshot JSON",
            HomeCache.containsCredentialPattern("http://panel:8080/live/u/p/1.ts?username=hay&password=x") &&
                HomeCache.containsCredentialPattern("{\"tmdb_key\":\"abc\"}") &&
                HomeCache.containsCredentialPattern("movie/list?api_key=abc") &&
                !HomeCache.containsCredentialPattern("[{\"tmdbId\":1,\"title\":\"Slow Horses\"}]"))
        check("v31: the diagnostics line names coverage, oldest age, and size — never content",
            HomeCache.diagLine(setOf("upnext", "continue"), now - 3L * hour, 2048L, now) ==
                "2/5 sections · 3h old · 2KB" &&
                HomeCache.diagLine(emptySet(), 0L, 0L, now).startsWith("0/5 sections · empty"))

        // ---- (39) SectionModule: state machine + retry routing ----
        check("v31: decide precedence — loading beats failure, failure beats data, data beats empty",
            SectionModule.decide(hasData = false, loading = true, failed = true) == SectionModule.State.LOADING &&
                SectionModule.decide(hasData = true, loading = false, failed = true) == SectionModule.State.ERROR &&
                SectionModule.decide(hasData = true, loading = false, failed = false) == SectionModule.State.READY &&
                SectionModule.decide(hasData = false, loading = false, failed = false) == SectionModule.State.EMPTY)
        check("v31: only ERROR is retryable",
            SectionModule.isRetryable(SectionModule.State.ERROR) &&
                !SectionModule.isRetryable(SectionModule.State.LOADING) &&
                !SectionModule.isRetryable(SectionModule.State.READY) &&
                !SectionModule.isRetryable(SectionModule.State.EMPTY))
        // v3.3 RE-PINNED (ask 47): comingsoon is an unknown key now
        // (NONE); the merged Up Next retry refreshes both sources
        // via MainActivity's own branch (merge53src pins it).
        check("v31: retry routing — tracker, discovery, iptv, apps, unknown",
            SectionModule.retrySource("upnext") == SectionModule.Source.TRACKER &&
                SectionModule.retrySource("foryou") == SectionModule.Source.TRACKER &&
                SectionModule.retrySource("comingsoon") == SectionModule.Source.NONE &&
                SectionModule.retrySource("trendingmovies") == SectionModule.Source.DISCOVERY &&
                SectionModule.retrySource("iptv-trending") == SectionModule.Source.DISCOVERY &&
                SectionModule.retrySource("iptv-row") == SectionModule.Source.IPTV &&
                SectionModule.retrySource("favorites") == SectionModule.Source.APPS &&
                SectionModule.retrySource("bogus") == SectionModule.Source.NONE)
        check("v31: a section with data never reports loading, even mid-refresh",
            !SectionModule.isLoading("upnext", hasData = true, trackerFetching = true, discoveryLoaded = false))
        check("v31: loading is tracker-fetching for tracker rows, discovery-unloaded for discovery rows",
            SectionModule.isLoading("upnext", hasData = false, trackerFetching = true, discoveryLoaded = true) &&
                !SectionModule.isLoading("upnext", hasData = false, trackerFetching = false, discoveryLoaded = true) &&
                SectionModule.isLoading("trendingtv", hasData = false, trackerFetching = false, discoveryLoaded = false) &&
                !SectionModule.isLoading("trendingtv", hasData = false, trackerFetching = false, discoveryLoaded = true) &&
                !SectionModule.isLoading("favorites", hasData = false, trackerFetching = true, discoveryLoaded = false))

        // ---- (40) PlayInLogic: self excluded, sorted, no hardcoded names ----
        val cands = PlayInLogic.filter(
            listOf(
                PlayInLogic.Candidate("com.other.player", "Z Player"),
                PlayInLogic.Candidate("com.mizan.launcher", "Sreeb"),
                PlayInLogic.Candidate("com.another.app", "a Player"),
                PlayInLogic.Candidate("", "Blank")
            ),
            "com.mizan.launcher"
        )
        check("v31: Play-in drops Sreeb itself and blank packages, sorts the rest by label",
            cands.map { it.pkg } == listOf("com.another.app", "com.other.player") &&
                PlayInLogic.hasHandler(cands) &&
                !PlayInLogic.hasHandler(
                    PlayInLogic.filter(
                        listOf(PlayInLogic.Candidate("com.mizan.launcher", "Sreeb")),
                        "com.mizan.launcher"
                    )
                ))

        // ---- (41) Sort page: the stores it edits + the internal popover target ----
        check("v31: sort-page moves reuse TrackerSections move (edges are no-ops, middle swaps)",
            TrackerSections.move(TrackerSections.decode(null), "upnext", -1) ==
                TrackerSections.decode(null) &&
                TrackerSections.move(TrackerSections.decode(null), "allapps", 1) ==
                    TrackerSections.decode(null))
        val qsTargets = QuickSettings.visible(QuickSettings.TARGETS) { false }
        check("v31: the Sort sections popover entry survives a resolves-nothing filter (internal target)",
            qsTargets.any { it.action == "sreeb:sortsections" } &&
                qsTargets.none { it.action == "android.settings.WIFI_SETTINGS" } &&
                qsTargets.any { it.action.isEmpty() } &&
                QuickSettings.isInternal(QuickSettings.Target("Sort sections", "sreeb:sortsections")) &&
                !QuickSettings.isInternal(QuickSettings.Target("Wi-Fi", "android.settings.WIFI_SETTINGS")))

        // ---- source checks: the wiring is really in the app ----
        fun src(path: String): String = try {
            java.io.File(path).readText()
        } catch (e: Exception) {
            ""
        }
        val mainSrc = src("app/src/main/java/com/mizan/launcher/MainActivity.kt")
        check("v31src: tracker home renders loading placeholders at configured positions + retry tiles",
            mainSrc.contains("renderKeys") && mainSrc.contains("sectionLoadingTile(") &&
                mainSrc.contains("retrySection(key)"))
        check("v31src: Play-in entries exist in card menus and the hero/menu paths use the shared resolvers",
            mainSrc.contains("onPlayIn") && mainSrc.contains("playInEpisode(") &&
                mainSrc.contains("playInMovie(") && mainSrc.contains("Play in…"))
        check("v31src: quick-settings routes the internal Sort sections target to Settings by category",
            mainSrc.contains("sreeb:sortsections") && mainSrc.contains("openSettings(\"sortsections\")"))
        // v3.7 RE-PINNED (launcher IS the tracker): there is
        // no fetch worker any more, so nothing mirrors to the
        // home cache — the indexed store IS the mirror, and
        // the old mirror survives only as a legacy fallback
        // read for the prefs-parse path.
        check("v31src: the indexed store replaces the home-cache mirror (legacy read only)",
            src("app/src/main/java/com/mizan/launcher/data/TrackerClient.kt").contains("storeSnapshots") &&
                !src("app/src/main/java/com/mizan/launcher/data/TrackerClient.kt").contains("HomeCacheDisk.putSnapshot") &&
                src("app/src/main/java/com/mizan/launcher/data/TrackerClient.kt").contains("HomeCacheDisk.mirrored"))
        val playInSrc = src("app/src/main/java/com/mizan/launcher/ui/PlayIn.kt")
        check("v31src: Play-in discovers handlers via the intent query — no hardcoded app packages",
            playInSrc.contains("queryIntentActivities") &&
                !playInSrc.contains("Smarters") && !playInSrc.contains("Reezn") &&
                playInSrc.contains("No other video app can open this stream"))
        check("v31src: the manifest declares the video/* queries intent (else the picker is always empty)",
            src("app/src/main/AndroidManifest.xml").contains("android:mimeType=\"video/*\""))
        val settingsSrc = src("app/src/main/java/com/mizan/launcher/ui/SettingsActivity.kt")
        check("v31src: the Sort sections page + its dialog + the category deep-link exist",
            settingsSrc.contains("buildSortSectionsDetail") &&
                settingsSrc.contains("sortSectionDialog") &&
                settingsSrc.contains("\"sortsections\"") &&
                settingsSrc.contains("getStringExtra(\"category\")"))
        check("v31src: discovery Retry forces one TTL-ignoring worker run",
            src("app/src/main/java/com/mizan/launcher/data/DiscoveryData.kt").contains("forceNext") &&
                src("app/src/main/java/com/mizan/launcher/data/DiscoveryData.kt").contains("fun refreshNow"))
        // Re-pinned in v3.4 (intentional version bump 3.3/26 → 3.4/27, spec §34).
        // Re-pinned in v3.5 (intentional version bump 3.4/27 → 3.5/28, spec §35).
        // Re-pinned in v3.6 (intentional version bump 3.5/28 → 3.6/29, spec §36).
        // Re-pinned in v3.7 (intentional version bump 3.6/29 → 3.7/30, spec §37).
        check("v31src: the version bump is 3.7 / code 30",
            src("app/build.gradle.kts").contains("versionCode = 30") &&
                src("app/build.gradle.kts").contains("versionName = \"3.7\""))
    }
}

/**
 * v3.1 hero amendment (spec §31): the hero pool is the schedule.
 * Missed (aired strictly before today, most overdue first) then
 * up-next (today or unknown, dated first, unknowns last); future
 * episodes are never hero material; unknown dates sort into
 * up-next, never emptying the hero; an empty schedule falls back
 * to Continue → For You; a Coming Soon entry evicts a hero item
 * only for the SAME episode (ties go to Coming Soon).
 */
fun section51() {
    run {
        val today = "2026-11-05"
        fun up(id: Long, date: String, season: Int, episode: Int, bd: String = "/b.jpg") =
            TrackerLogic.UpNextItem(id, "S$id", "S$season E$episode", date, "/p.jpg", bd, "", season, episode, "tv")
        fun cont(id: Long, bd: String) =
            TrackerLogic.ContinueItem(id, "C$id", "Next S1 E1", 1, 9, "/p.jpg", bd, "", "tv")
        val picks = listOf(
            TrackerLogic.ForYouPick(6, "tv", "P-Art", "/p6.jpg", 7.5, "Because", listOf("Drama"), 9.0),
            TrackerLogic.ForYouPick(7, "movie", "P-NoArt", "/p7.jpg", 6.0, "Trending", emptyList(), 5.0)
        )
        val backdrops = mapOf("tv|6" to "/p6-back.jpg")

        // ---- tiers + ordering (input deliberately shuffled) ----
        val pool = TrackerLogic.heroCandidates(
            listOf(
                up(16, "2026-11-20", 1, 1), // future — never hero
                up(15, "", 1, 3), // unknown — up-next tier, last
                up(13, "2026-11-05", 1, 1), // today — up-next tier
                up(11, "2026-10-25", 1, 2), // missed
                up(14, "2026-11-05", 1, 2), // today — up-next tier
                up(10, "2026-10-20", 1, 1), // missed, most overdue
                up(12, "2026-11-01", 2, 3) // missed
            ),
            listOf(cont(3, "/b3.jpg")), picks, backdrops, today
        )
        check("hero51: missed first (most overdue first), then up-next (today, then unknown)",
            pool.map { it.tmdbId } == listOf(10L, 11L, 12L, 13L, 14L, 15L),
            pool.map { it.tmdbId }.toString())
        check("hero51: future episodes are never hero material",
            pool.none { it.tmdbId == 16L })
        check("hero51: missed labels carry Missed + coords + date chip; up-next labels carry Up next + coords",
            pool[0].sub == "Missed · S1 E1 · Oct 20" &&
                pool[2].sub == "Missed · S2 E3 · Nov 1" &&
                pool[3].sub == "Up next · S1 E1" &&
                pool[5].sub == "Up next · S1 E3" &&
                pool[0].source == "Missed" && pool[3].source == "Up Next")
        check("hero51: a schedule pool never falls back to Continue/For You",
            pool.none { it.source == "Continue Watching" || it.source == "For You" })

        // ---- empty schedule → previous behaviour ----
        val fallback = TrackerLogic.heroCandidates(
            listOf(up(16, "2026-11-20", 1, 1)),
            listOf(cont(3, "/b3.jpg"), cont(4, "")), picks, backdrops, today
        )
        check("hero51: an all-future schedule falls back to Continue (backdrop-first) + For You (backdrop-gated)",
            fallback.map { it.tmdbId } == listOf(3L, 4L, 6L) &&
                fallback.map { it.source } == listOf("Continue Watching", "Continue Watching", "For You"))
        check("hero51: an empty library yields an empty pool (never a fabricated hero)",
            TrackerLogic.heroCandidates(emptyList(), emptyList(), emptyList(), emptyMap(), today).isEmpty())
        check("hero51: a blank today disables schedule shaping (fallback, never a crash)",
            TrackerLogic.heroCandidates(
                listOf(up(10, "2026-10-20", 1, 1)), listOf(cont(3, "/b3.jpg")),
                emptyList(), emptyMap(), ""
            ).map { it.tmdbId } == listOf(3L))

        // ---- Coming Soon exclusion is episode-precise ----
        val soon = listOf(
            Discovery.SoonItem(20, "tv", "H", "", "2026-11-20", "S1 E1"), // same episode → evicts
            Discovery.SoonItem(21, "tv", "I", "", "2026-11-20", "S1 E8"), // different episode → keeps
            Discovery.SoonItem(22, "tv", "J", "", "2026-11-20", "") // no coords → cannot prove → keeps
        )
        val excluded = TrackerLogic.heroCandidates(
            listOf(up(20, "2026-10-20", 1, 1), up(21, "2026-10-21", 1, 2), up(22, "2026-10-22", 1, 1)),
            emptyList(), emptyList(), emptyMap(), today, soon
        )
        check("hero51: no title shares an episode with Coming Soon (ties go to Coming Soon; other episodes keep their hero)",
            excluded.map { it.tmdbId } == listOf(21L, 22L) &&
                excluded.none { it.tmdbId == 20L })

        // ---- TMDB date fill: snapshot wins; fill needs matching coords ----
        val facts = mapOf(
            30L to Discovery.TvFacts("2026-10-28", 2, 4, "", 0.0, emptyList()),
            31L to Discovery.TvFacts("2026-10-28", 9, 9, "", 0.0, emptyList()),
            32L to Discovery.TvFacts("2026-10-29", 3, 1, "", 0.0, emptyList())
        )
        val filled = TrackerLogic.fillHeroDates(
            listOf(
                up(30, "", 2, 4), // blank + matching coords → filled
                up(31, "", 2, 4), // blank + mismatched coords → unfilled
                up(32, "", 0, 0), // blank + no coords → filled with facts coords
                up(33, "2026-10-01", 2, 4), // snapshot date wins → untouched
                up(34, "", 2, 4) // no facts → untouched
            ),
            facts
        )
        check("hero51: date fill fills matching-coord blanks, fills coords when absent, never overwrites",
            filled[0].dateIso == "2026-10-28" && filled[0].season == 2 && filled[0].episode == 4 &&
                filled[1].dateIso.isBlank() &&
                filled[2].dateIso == "2026-10-29" && filled[2].season == 3 && filled[2].episode == 1 &&
                filled[3].dateIso == "2026-10-01" &&
                filled[4].dateIso.isBlank())

        // ---- source checks: the schedule wiring is really in the app ----
        fun src(path: String): String = try {
            java.io.File(path).readText()
        } catch (e: Exception) {
            ""
        }
        val mainSrc = src("app/src/main/java/com/mizan/launcher/MainActivity.kt")
        check("hero51src: the home builds its hero from filled schedule dates + Coming Soon exclusion",
            mainSrc.contains("fillHeroDates") && mainSrc.contains("todayIso(), dSoon"))
        check("hero51src: the discovery worker publishes last-known TMDB facts for the hero fill",
            src("app/src/main/java/com/mizan/launcher/data/DiscoveryData.kt").contains("lastTvFacts") &&
                src("app/src/main/java/com/mizan/launcher/data/DiscoveryData.kt").contains("fun tvFacts()"))
    }
}

fun section52() {
    // ---- pure policy: no auto-refresh on open/resume/switch ----
    check("refresh52: open/resume/profile-switch never auto-refresh",
        !RefreshPolicy.autoRefreshAllowed(RefreshPolicy.Trigger.OPEN) &&
            !RefreshPolicy.autoRefreshAllowed(RefreshPolicy.Trigger.RESUME) &&
            !RefreshPolicy.autoRefreshAllowed(RefreshPolicy.Trigger.PROFILE_SWITCH))
    check("refresh52: manual/retry/first-run-seed are the only allowed triggers",
        RefreshPolicy.autoRefreshAllowed(RefreshPolicy.Trigger.MANUAL) &&
            RefreshPolicy.autoRefreshAllowed(RefreshPolicy.Trigger.SECTION_RETRY) &&
            RefreshPolicy.autoRefreshAllowed(RefreshPolicy.Trigger.FIRST_RUN_SEED))
    check("refresh52: tracker seeds only when nothing is stored",
        RefreshPolicy.trackerSeedNeeded(false) && !RefreshPolicy.trackerSeedNeeded(true))
    check("refresh52: discovery fetches only on force or a missing cache file (TTL never fetches)",
        RefreshPolicy.discoveryFetchAllowed(true, true) &&
            RefreshPolicy.discoveryFetchAllowed(false, false) &&
            !RefreshPolicy.discoveryFetchAllowed(false, true))
    check("refresh52: weather fetches only from the visible-home tick while stale",
        RefreshPolicy.weatherFetchAllowed(true, true) &&
            !RefreshPolicy.weatherFetchAllowed(true, false) &&
            !RefreshPolicy.weatherFetchAllowed(false, true))
    check("refresh52: accent recomputes only when the wallpaper changed",
        !RefreshPolicy.accentRecomputeNeeded("wp://a", "wp://a") &&
            RefreshPolicy.accentRecomputeNeeded("wp://a", "wp://b") &&
            RefreshPolicy.accentRecomputeNeeded("", "wp://b"))

    // ---- pure stages list: cap + format + same-name replace ----
    val stages = StartupStages()
    stages.record("prefs ready", 330)
    stages.record("apps ready", 410)
    stages.record("composition done", 900)
    check("stages52: compact name=ms rendering in record order",
        stages.text() == "prefs ready=330ms, apps ready=410ms, composition done=900ms")
    stages.record("apps ready", 950)
    check("stages52: re-record replaces the earlier same-name stage",
        stages.text() == "prefs ready=330ms, composition done=900ms, apps ready=950ms")
    val capped = StartupStages()
    for (i in 1..40) capped.record("s$i", i.toLong())
    check("stages52: list caps at 32, oldest dropped",
        capped.size() == 32 && capped.names().first() == "s9" && capped.names().last() == "s40")

    // ---- source checks: the wiring is really in the app ----
    fun src(path: String): String = try {
        java.io.File(path).readText()
    } catch (e: Exception) {
        ""
    }
    val mainSrc = src("app/src/main/java/com/mizan/launcher/MainActivity.kt")
    val settingsSrc = src("app/src/main/java/com/mizan/launcher/ui/SettingsActivity.kt")
    val discSrc = src("app/src/main/java/com/mizan/launcher/data/DiscoveryData.kt")
    check("refresh52src: onResume carries no refresh kicks any more",
        mainSrc.contains("NOTHING refreshes on resume"))
    check("refresh52src: tracker home refreshes only as first-run seeding",
        mainSrc.contains("if (!TrackerClient.hasStoredData(this))"))
    check("refresh52src: explicit Refresh home data exists (popover target + handler)",
        mainSrc.contains("sreeb:refreshhome") && mainSrc.contains("private fun refreshHomeData()"))
    check("refresh52src: discovery worker fetches only on force-or-empty-cache",
        discSrc.contains("val allowFetch = force || !hasCoreCache") &&
            discSrc.contains("if (!loaded) kick(ctx)"))
    // Re-pinned in v3.5 (intentional change, spec §35): the home
    // build's guarded kick is slim-only now — warmMemoAsync (a
    // full parse on the first home build) is deleted.
    check("refresh52src: home build never parses snapshots (memo-only read + worker kick)",
        mainSrc.contains("TrackerClient.memoOrNull()") && mainSrc.contains("warmSlimAsync"))
    check("refresh52src: Home screen row options lost Move up/down (sort only in Sort sections)",
        !settingsSrc.contains("\"Move row up\"") && !settingsSrc.contains("\"Move row down\"") &&
            settingsSrc.contains("Sorting lives ONLY in \"Sort sections\""))
    check("refresh52src: diagnostics gains last-refresh + startup-stages lines",
        settingsSrc.contains("last refresh:") && settingsSrc.contains("startup stages:"))
    check("refresh52src: hero schedule rule untouched (missed first, today stays in Coming Soon)",
        mainSrc.contains("fillHeroDates") && mainSrc.contains("todayIso(), dSoon"))
}

/** v3.3 checks — lock auto-submit (ask 45) + safe mode escapable/visible (ask 46, spec §33). */
fun section53() {
    run {
        println("-- section 53: v3.3 lock auto-submit + safe-mode exit")

        // ---- auto-submit rule (core/ArrowPinAutoSubmit) ----
        for (stored in 4..8) {
            check("auto53: stored length $stored submits exactly at $stored, never before or after",
                !ArrowPinAutoSubmit.shouldSubmit(stored - 1, stored) &&
                    ArrowPinAutoSubmit.shouldSubmit(stored, stored) &&
                    !ArrowPinAutoSubmit.shouldSubmit(stored + 1, stored))
        }
        for (bad in listOf(0, 1, 3, 9, 12)) {
            check("auto53: invalid stored length $bad never auto-submits",
                (0..9).none { ArrowPinAutoSubmit.shouldSubmit(it, bad) })
        }
        // Machine integration: an unlock entry of the stored length
        // submits with the exact encoding; a partial entry does not.
        val unlock = ArrowPinEntry()
        unlock.append(ArrowDir.UP); unlock.append(ArrowDir.DOWN); unlock.append(ArrowDir.LEFT)
        check("auto53: partial unlock entry (3 of 4) does not auto-submit",
            !ArrowPinAutoSubmit.shouldSubmit(unlock.entered, 4))
        unlock.append(ArrowDir.RIGHT)
        check("auto53: complete unlock entry (4 of 4) auto-submits with its encoding",
            ArrowPinAutoSubmit.shouldSubmit(unlock.entered, 4) &&
                (unlock.submit() as? ArrowPinEvent.Submitted)?.sequence == "UDLR")
        // The set flow has no stored length: it still needs OK per
        // phase (auto-submit must not fire without a stored length).
        check("auto53: set flow keeps OK-driven phases (no stored length, no auto-submit)",
            !ArrowPinAutoSubmit.shouldSubmit(4, 0))

        // ---- clear-delay rule (core/StartupHealth.clearDelayMs) ----
        val sh = StartupHealth
        check("clear53: normal completed render waits the full 10s survival",
            sh.clearDelayMs(false, false) == 10_000L)
        check("clear53: safe-shell completed render clears immediately (0ms)",
            sh.clearDelayMs(true, false) == 0L)
        check("clear53: degraded build holds the clear in both modes",
            sh.clearDelayMs(false, true) == null && sh.clearDelayMs(true, true) == null)
        // Anti-brick, composed with the new rule: two normal deaths
        // still arm the safe shell; a safe render clears at once;
        // the next launch is NORMAL; two more deaths re-arm.
        var counter = 0
        fun launch(dies: Boolean, degraded: Boolean = false): StartupHealth.Mode {
            val mode = sh.modeFor(counter)
            counter = sh.onLaunch(counter)
            if (!dies) {
                if (sh.clearDelayMs(mode == StartupHealth.Mode.SAFE_SHELL, degraded) != null) {
                    counter = sh.onCleared()
                }
            }
            return mode
        }
        launch(dies = true); launch(dies = true)
        val safeMode = launch(dies = false)
        check("clear53: [die, die] -> safe shell, and its clean render clears immediately",
            safeMode == StartupHealth.Mode.SAFE_SHELL && counter == 0)
        check("clear53: after the cleared safe launch the next launch is NORMAL",
            launch(dies = false) == StartupHealth.Mode.NORMAL)
        launch(dies = true); launch(dies = true)
        check("clear53: user-exit or immediate clear never disables re-arming — two more deaths re-arm safe mode",
            launch(dies = false) == StartupHealth.Mode.SAFE_SHELL || sh.modeFor(counter) == StartupHealth.Mode.SAFE_SHELL)
        // A degraded safe render must NOT clear (TV build itself broken).
        counter = 2
        val heldMode = sh.modeFor(counter)
        counter = sh.onLaunch(counter)
        check("clear53: degraded safe render holds the clear (counter stays)",
            heldMode == StartupHealth.Mode.SAFE_SHELL &&
                sh.clearDelayMs(true, true) == null && counter == 3)
        check("clear53: notice names safe mode, Diagnostics, and the exit action",
            sh.NOTICE_TEXT.contains("safe mode") && sh.NOTICE_TEXT.contains("Diagnostics") &&
                sh.NOTICE_TEXT.contains("Exit safe mode"))

        // ---- source checks: the wiring is really in the app ----
        fun src(path: String): String = try {
            java.io.File(path).readText()
        } catch (e: Exception) {
            ""
        }
        val dialogSrc = src("app/src/main/java/com/mizan/launcher/ui/ArrowPinDialog.kt")
        val settingsSrc = src("app/src/main/java/com/mizan/launcher/ui/SettingsActivity.kt")
        val mainSrc = src("app/src/main/java/com/mizan/launcher/MainActivity.kt")
        val storeSrc = src("app/src/main/java/com/mizan/launcher/data/StartupHealthStore.kt")
        check("auto53src: unlock dialog auto-submits at the stored length",
            dialogSrc.contains("autoSubmitLength") &&
                dialogSrc.contains("ArrowPinAutoSubmit.shouldSubmit") &&
                dialogSrc.contains("Press your arrow pattern · Back deletes"))
        check("auto53src: set flow passes no auto-submit length (OK-driven phases kept)",
            dialogSrc.contains("hint = \"Press arrows to build a pattern of 4–8 · OK when done · Back deletes\""))
        check("safe53src: Diagnostics gains the exit action, gated on the armed flag",
            settingsSrc.contains("Exit safe mode and retry normal home") &&
                settingsSrc.contains("exitSafeShell") &&
                settingsSrc.contains("safeShellArmed"))
        check("safe53src: style label + picker disclose the safe-mode TV lock",
            settingsSrc.contains("safe mode is ON: home locked to TV") &&
                settingsSrc.contains("locked to TV until safe mode exits"))
        check("safe53src: MainActivity re-reads the armed flag on rebuild and uses the clear-delay rule",
            mainSrc.contains("safeShell = StartupHealthStore.safeShellArmed") &&
                mainSrc.contains("clearDelayMs(safeShell, buildDegraded)"))
        check("safe53src: store offers a user-initiated exit that resets counter + flag",
            storeSrc.contains("fun exitSafeShell") &&
                storeSrc.contains("safeShellArmed = false"))
    }
    run {
        println("-- section 53b: v3.3 merged Up Next (ask 47)")

        // Fixtures: today 2026-10-24. Up Next carries the
        // snapshot dates; Soon carries TMDB next-air dates.
        val today = "2026-10-24"
        fun up(
            id: Long, title: String, date: String, s: Int, e: Int
        ) = TrackerLogic.UpNextItem(
            id, title, "S$s E$e", date, "p$id", "b$id", "", s, e, "tv"
        )
        fun soon(
            id: Long, title: String, date: String, sub: String, type: String = "tv"
        ) = Discovery.SoonItem(id, type, title, "p$id", date, sub)
        val upItems = listOf(
            up(1, "Missed Old", "2026-10-20", 1, 5),
            up(2, "Missed New", "2026-10-23", 2, 3),
            up(3, "Today Show", "2026-10-24", 1, 1),
            up(4, "Dateless Show", "", 3, 2),
            up(5, "Caught Up", "2026-10-30", 2, 6),
            up(6, "Behind Show", "2026-10-01", 1, 5),
            up(7, "Future No Twin", "2026-11-02", 1, 9)
        )
        val soonItems = listOf(
            soon(5, "Caught Up", "2026-10-30", "S2 E6"),
            soon(6, "Behind Show", "2026-11-05", "S1 E8"),
            soon(7, "Future Only", "2026-10-26", "S1 E1"),
            soon(8, "Today Only", "2026-10-24", "S4 E2"),
            soon(9, "Future Movie", "2026-11-14", "", "movie")
        )
        val merged = TrackerLogic.mergeUpNext(upItems, soonItems, today)
        val titles = merged.map { it.title }
        check("merge53: same episode appears once (Caught Up deduped to its Up Next card)",
            titles.count { it == "Caught Up" } == 1 &&
                merged.first { it.title == "Caught Up" }.upNext != null)
        check("merge53: different episodes of one show both stay (Behind S1E5 + Soon S1E8)",
            titles.count { it == "Behind Show" } == 2)
        check("merge53: tier order is missed (oldest first) -> watchable (today, then dateless) -> future (date asc)",
            titles == listOf(
                "Behind Show", "Missed Old", "Missed New",
                "Today Show", "Dateless Show",
                "Today Only", "Future Only", "Caught Up", "Future No Twin", "Behind Show", "Future Movie"
            ))
        check("merge53: missed tier is most-overdue first (Oct 01 before Oct 20 before Oct 23)",
            merged.take(3).map { it.title } == listOf("Behind Show", "Missed Old", "Missed New"))
        check("merge53: Soon-only today item leads the future tier (date asc)",
            merged[5].title == "Today Only" && merged[5].upNext == null)
        check("merge53: future Up Next item with no Soon twin is kept, in the future tier",
            merged.first { it.title == "Future No Twin" }.upNext != null &&
                merged.indexOfFirst { it.title == "Future No Twin" } >
                    merged.indexOfFirst { it.title == "Dateless Show" })
        check("merge53: Soon movies never dedupe against shows and land in the future tier",
            merged.last().title == "Future Movie" && merged.last().type == "movie")
        // Coord-less pairs never dedupe (sameness unproven — the
        // hero rule): same show, no coords on either side, twice.
        val coordless = TrackerLogic.mergeUpNext(
            listOf(
                TrackerLogic.UpNextItem(11, "NoCoords", "", "", "p", "b", "", 0, 0, "tv")
            ),
            listOf(Discovery.SoonItem(11, "tv", "NoCoords", "p", "2026-10-28", "")),
            today
        )
        check("merge53: coord-less pair keeps both cards (sameness unproven)",
            coordless.size == 2)
        // Registry: Coming Soon is gone; stored orders drop it.
        check("merge53: registry has no Coming Soon key anywhere",
            !TrackerSections.CANONICAL.contains("comingsoon") &&
                !TrackerSections.ROWS.contains("comingsoon"))
        val decoded = TrackerSections.decode("upnext,comingsoon,continue|comingsoon")
        check("merge53: stored order + hidden set mentioning comingsoon drop it on read",
            // v3.4 RE-PINNED (ask 51): folders appends in its canonical slot.
            // v3.7 RE-PINNED (Missed row): Missed splices after Up Next.
            decoded.order == listOf("upnext", "missed", "continue", "trendingtv", "trendingmovies", "foryou", "favorites", "folders", "allapps") &&
                "comingsoon" !in decoded.hidden)
        check("merge53: compose never emits comingsoon",
            !TrackerSections.compose(decoded) { true }.contains("comingsoon"))
        // Hero rule untouched: future items stay out of the hero,
        // and the same-episode Soon exclusion still applies.
        val heroes = TrackerLogic.heroCandidates(
            upItems, emptyList(), emptyList(), emptyMap(), today, soonItems
        )
        check("merge53: hero still excludes future items and same-episode Soon twins",
            heroes.none { it.title == "Caught Up" } &&
                heroes.none { it.title == "Future Only" } &&
                heroes.map { it.title }.contains("Missed Old"))
        // Source checks: the merged wiring is really in the app.
        fun src(path: String): String = try {
            java.io.File(path).readText()
        } catch (e: Exception) {
            ""
        }
        val mainSrc = src("app/src/main/java/com/mizan/launcher/MainActivity.kt")
        val sectionsSrc = src("app/src/main/java/com/mizan/launcher/core/TrackerSections.kt")
        check("merge53src: home composes the merged section, not two sections",
            mainSrc.contains("mergeUpNext(") &&
                mainSrc.contains("buildMergedUpNextSection(mergedUpNext)") &&
                !mainSrc.contains("buildComingSoonSection(") &&
                !mainSrc.contains("TrackerSections.COMINGSOON"))
        check("merge53src: Coming Soon const is gone from the registry",
            !sectionsSrc.contains("const val COMINGSOON"))
        check("merge53src: merged retry refreshes both sources",
            mainSrc.contains("the merged Up Next retries BOTH sources"))
    }
}

/** v3.4 checks — slim tracker cache (ask 49), folders on media homes (ask 51), hold-and-drop sort (ask 50), smaller row posters (ask 52). */
fun section54() {
    run {
        val ts = com.mizan.launcher.core.TrackerSections
        
        // ---- TrackerSlim: round-trip over every list, incl. unicode/quotes/empty lists ----
        val up = listOf(
            TrackerLogic.UpNextItem(1L, "Café \"Show\" ☕", "S2 E6", "2026-10-25", "/p1.jpg", "/b1.jpg", "Ov\nerview", 2, 6, "tv"),
            TrackerLogic.UpNextItem(2L, "Plain", "", "", "", "", "", 0, 0, "tv")
        )
        val cont = listOf(
            TrackerLogic.ContinueItem(3L, "Cont’d", "3/10", 3, 10, "/p2.jpg", "", "", "tv")
        )
        val mov = listOf(
            TrackerLogic.MovieItem(4L, "Movie", "2026-01-01", true, "/p3.jpg", false)
        )
        val trk = listOf(
            TrackerLogic.TrackedShow(5L, "Tracked", 42, false)
        )
        val fy = listOf(
            TrackerLogic.ForYouPick(6L, "movie", "Pick", "/p4.jpg", 8.5, "Because you watched X", listOf("Drama", "Sci-Fi"), 91.5)
        )
        val fp = TrackerSlim.fingerprint(1000L, 2000L, 11, 22, 33, 44, 55)
        val encoded = TrackerSlim.encode(up, cont, mov, trk, fy, 1000L, 900L, 2000L, fp)
        check("slim54: encode succeeds and stays under the cap",
            encoded != null && encoded.length < TrackerSlim.MAX_BYTES)
        val decoded = TrackerSlim.decode(encoded, fp)
        check("slim54: round-trip preserves every list (incl. unicode, quotes, newlines)",
            decoded != null && decoded.upNext == up && decoded.cont == cont &&
                decoded.movies == mov && decoded.tracked == trk && decoded.forYou == fy &&
                decoded.updated == 1000L && decoded.forYouUpdated == 900L &&
                decoded.fetchedAt == 2000L)
        val emptyEncoded = TrackerSlim.encode(
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
            0L, 0L, 0L, TrackerSlim.fingerprint(0L, 0L, 0, 0, 0, 0, 0)
        )
        val emptyDecoded = TrackerSlim.decode(
            emptyEncoded, TrackerSlim.fingerprint(0L, 0L, 0, 0, 0, 0, 0)
        )
        check("slim54: empty lists round-trip to empty lists",
            emptyDecoded != null && emptyDecoded.upNext.isEmpty() &&
                emptyDecoded.cont.isEmpty() && emptyDecoded.movies.isEmpty() &&
                emptyDecoded.tracked.isEmpty() && emptyDecoded.forYou.isEmpty())
        check("slim54: fingerprint mismatch reads as absent",
            TrackerSlim.decode(encoded, "different|fp") == null)
        check("slim54: garbage reads as absent",
            TrackerSlim.decode("not json at all {{{", fp) == null &&
                TrackerSlim.decode("", fp) == null &&
                TrackerSlim.decode(null, fp) == null)
        check("slim54: version mismatch reads as absent",
            TrackerSlim.decode(encoded.orEmpty().replace("\"v\":1", "\"v\":99"), fp) == null)
        check("slim54: oversize reads as absent",
            TrackerSlim.decode("x".repeat(TrackerSlim.MAX_BYTES + 1), fp) == null)
        val credentialUp = listOf(
            TrackerLogic.UpNextItem(9L, "tmdb_key=abc123", "", "", "", "", "", 0, 0, "tv")
        )
        check("slim54: credential-shaped payload refuses to encode",
            TrackerSlim.encode(credentialUp, emptyList(), emptyList(), emptyList(), emptyList(),
                0L, 0L, 0L, "fp") == null)

        // ---- SortGrab: the grab/move/drop/cancel machine ----
        val order = listOf("upnext", "continue", "trendingtv", "foryou")
        val idle = SortGrab.State.idle(order)
        check("grab54: idle state grabs nothing, order intact",
            idle.grabbed == null && idle.order == order)
        val grabbed = SortGrab.grab(idle, "continue")
        check("grab54: grab holds the row and snapshots the order",
            grabbed.grabbed == "continue" && grabbed.snapshot == order)
        check("grab54: unknown key and re-grab are no-ops",
            SortGrab.grab(idle, "nope").grabbed == null &&
                SortGrab.grab(grabbed, "foryou").grabbed == "continue")
        val movedDown = SortGrab.move(grabbed, +1)
        check("grab54: move down swaps with the neighbour",
            movedDown.order == listOf("upnext", "trendingtv", "continue", "foryou") &&
                movedDown.grabbed == "continue")
        val movedUp = SortGrab.move(movedDown, -1)
        check("grab54: move up restores the order",
            movedUp.order == order)
        val atTop = SortGrab.grab(idle, "upnext")
        val atBottom = SortGrab.grab(idle, "foryou")
        check("grab54: edge moves are no-ops",
            SortGrab.move(atTop, -1).order == order &&
                SortGrab.move(atBottom, +1).order == order)
        check("grab54: no-grab moves are no-ops",
            SortGrab.move(idle, +1) == idle && SortGrab.move(idle, -1) == idle)
        val dropped = SortGrab.drop(movedDown)
        check("grab54: drop commits the moved order and ends the grab",
            dropped.order == listOf("upnext", "trendingtv", "continue", "foryou") &&
                dropped.grabbed == null)
        val cancelled = SortGrab.cancel(movedDown)
        check("grab54: cancel restores the snapshot and ends the grab",
            cancelled.order == order && cancelled.grabbed == null)

        // ---- Folders registry: joins CANONICAL/ROWS, old orders gain it, compose includes it ----
        check("folders54: FOLDERS is in CANONICAL after FAVORITES, before ALLAPPS",
            ts.CANONICAL.indexOf(ts.FOLDERS) == ts.CANONICAL.indexOf(ts.FAVORITES) + 1 &&
                ts.CANONICAL.indexOf(ts.FOLDERS) == ts.CANONICAL.indexOf(ts.ALLAPPS) - 1)
        check("folders54: label is Folders",
            ts.label(ts.FOLDERS) == "Folders")
        val oldDecoded = ts.decode("upnext,continue|")
        check("folders54: a stored order predating folders gains it (canonical slot, before All Apps)",
            ts.FOLDERS in oldDecoded.order &&
                oldDecoded.order.indexOf(ts.FOLDERS) == oldDecoded.order.indexOf(ts.ALLAPPS) - 1)
        check("folders54: compose includes folders when shown and available",
            ts.compose(oldDecoded) { true }.contains(ts.FOLDERS))
        check("folders54: compose excludes folders when hidden",
            ts.compose(ts.setShown(oldDecoded, ts.FOLDERS, false)) { true }
                .contains(ts.FOLDERS).not())
        check("folders54: move works on the folders row (up one swaps with favorites)",
            ts.move(oldDecoded, ts.FOLDERS, -1).order[oldDecoded.order.size - 3] == ts.FOLDERS &&
                ts.move(oldDecoded, ts.FOLDERS, -1).order[oldDecoded.order.size - 2] == ts.FAVORITES)

        // ---- Row poster scale: 0.75x, aspect preserved, more cards per reference row ----
        check("scale54: home row scale is 0.75",
            TileLayout.HOME_ROW_CARD_SCALE == 0.75f)
        val ref = TileLayout.cardMetrics(1920, 2.0f, "standard", 6)
        val scaledW = TileLayout.homeRowCardWidthPx(ref.tileWidthPx)
        val scaledH = scaledW * 9 / 16
        check("scale54: scaled card keeps the 16:9 frame ratio",
            scaledW * 9 == scaledH * 16 || (scaledW * 9 / 16) == scaledH)
        val marginPx = (TileLayout.TILE_MARGIN_DP * 2 * 2.0f).toInt()
        val before = 1920 / (ref.tileWidthPx + marginPx)
        val after = 1920 / (scaledW + marginPx)
        check("scale54: reference row fits more cards scaled than standard (3 -> 4)",
            before == 3 && after == 4)
        check("scale54: scaled width is 0.75x standard (floor)",
            scaledW == (ref.tileWidthPx * 0.75f).toInt())

        // ---- Source checks: every wiring is really in the app ----
        fun src(path: String): String = try {
            java.io.File(path).readText()
        } catch (e: Exception) {
            ""
        }
        val mainSrc = src("app/src/main/java/com/mizan/launcher/MainActivity.kt")
        val settingsSrc = src("app/src/main/java/com/mizan/launcher/ui/SettingsActivity.kt")
        val clientSrc = src("app/src/main/java/com/mizan/launcher/data/TrackerClient.kt")
        val appSrc = src("app/src/main/java/com/mizan/launcher/App.kt")
        val discSrc = src("app/src/main/java/com/mizan/launcher/data/DiscoveryData.kt")
        val cardsSrc = src("app/src/main/java/com/mizan/launcher/ui/IptvCards.kt")
        check("src54: tracker home renders a Folders section",
            mainSrc.contains("TrackerSections.FOLDERS ->") &&
                mainSrc.contains("buildFoldersSection()?.let { content.addView(it) }"))
        check("src54: IPTV home renders a folders section before favorites",
            mainSrc.contains("row:iptv:folders"))
        check("src54: safe shell bypasses folder dedupe (no vanishing apps)",
            mainSrc.contains("shellSafe") && mainSrc.contains("safeMode || safeShell"))
        check("src54: tracker home availability gates folders on its visibility row",
            mainSrc.contains("TrackerSections.FOLDERS to layout.isRowVisible(\"folders\")"))
        check("src54: landscapeCard scales home row cards",
            mainSrc.contains("homeRowCardWidthPx(cm.tileWidthPx)"))
        check("src54: IPTV home rows pass the 0.75 scale, browser keeps default",
            mainSrc.contains("scale = TileLayout.HOME_ROW_CARD_SCALE") &&
                cardsSrc.contains("scale: Float = 1f"))
        // Re-pinned in v3.5 (intentional refactor, same semantics):
        // the slim attempt moved into trySlimSnap with the shared
        // fingerprint helper — slim still runs before the full
        // parse, and the full parse still rewrites the slim.
        check("src54: TrackerClient tries the slim before the full parse and writes it after",
            clientSrc.contains("trySlimSnap(ctx, inputs)") &&
                clientSrc.contains("writeSlimAsync(ctx, snap, slimFpFor(inputs))"))
        // Re-pinned in v3.5 (intentional change, spec §35):
        // App.onCreate warms slim ONLY — the full memo parse +
        // discovery preload at process start stacked the
        // cold-start peak; both moved to the post-survival chain.
        check("src54: App.onCreate kicks the memo warm and the discovery preload",
            appSrc.contains("warmSlimAsync(this)") && !appSrc.contains("DiscoveryData.preload(this)"))
        check("src54: discovery preload exists as a single-flight kick",
            discSrc.contains("fun preload(ctx: Context)"))
        // Re-pinned in v3.5 (intentional change, spec §35): the
        // parallel colddwarm-store thread is deleted with the
        // rest of the parallel warms — one sequential chain
        // thread replaces them (see section 55).
        check("src54: cold-warm runs tracker and store warms in parallel",
            !mainSrc.contains("mizan-coldwarm-store"))
        check("src54: sort page holds, moves, drops and cancels a grab",
            settingsSrc.contains("sortGrabStart(key, \"tracker\")") &&
                settingsSrc.contains("sortGrabStart(key, \"tv\")") &&
                settingsSrc.contains("sortGrabMove(-1)") &&
                settingsSrc.contains("sortGrabDrop()") &&
                settingsSrc.contains("sortGrabCancel()"))
        check("src54: sort page offers hold-OK on tracker and TV rows, IPTV lists folders hide/show",
            settingsSrc.contains("hold OK to grab") &&
                settingsSrc.contains("listOf(\"folders\", \"favorites\", \"apps\")"))
        check("src54: diagnostics gains the slim proof line",
            settingsSrc.contains("slim: \" +"))
        check("src54: hero rule untouched (missed first, today stays out)",
            mainSrc.contains("fillHeroDates") && mainSrc.contains("todayIso(), dSoon"))
    }
}

/** v3.5 checks — the sequential cold chain, the key-only streaming match, the last-death line (spec §35). */
fun section55() {
    run {
        // ---- ColdChain: ordering, gates, cancel, diagnostics ----
        val chain = ColdChain()
        check("chain55: fresh chain waits for survival, first step is tracker",
            chain.next() == ColdChain.Step.TRACKER_FULL && chain.diag() == "waiting survival")
        check("chain55: no heavy step before survival unless on-demand",
            !chain.canStart(ColdChain.Step.TRACKER_FULL, false, false) &&
                chain.canStart(ColdChain.Step.TRACKER_FULL, false, true) &&
                !ColdChain.heavyStepAllowedBeforeSurvival(false) &&
                ColdChain.heavyStepAllowedBeforeSurvival(true))
        check("chain55: out-of-order starts refused",
            !chain.canStart(ColdChain.Step.MATCH, true, false) &&
                !chain.canStart(ColdChain.Step.DISCOVERY, true, false))
        check("chain55: start tracker, in-flight blocks the next step",
            chain.start(ColdChain.Step.TRACKER_FULL, true, false) &&
                chain.next() == null &&
                !chain.canStart(ColdChain.Step.MATCH, true, false) &&
                chain.diag() == "running: tracker_full")
        check("chain55: completing the wrong step is a no-op",
            run {
                chain.complete(ColdChain.Step.MATCH)
                chain.inFlight() == ColdChain.Step.TRACKER_FULL && chain.next() == null
            })
        check("chain55: completing tracker advances to match",
            run {
                chain.complete(ColdChain.Step.TRACKER_FULL)
                chain.next() == ColdChain.Step.MATCH && chain.diag() == "waiting: match"
            })
        check("chain55: full run ends done",
            run {
                chain.start(ColdChain.Step.MATCH, true, false)
                chain.complete(ColdChain.Step.MATCH)
                chain.start(ColdChain.Step.DISCOVERY, true, false)
                chain.complete(ColdChain.Step.DISCOVERY)
                chain.isDone() && chain.next() == null && chain.diag() == "done" &&
                    !chain.canStart(ColdChain.Step.TRACKER_FULL, true, false)
            })
        check("chain55: arm resets for a fresh run (fetch reason)",
            run {
                chain.arm(ColdChain.Reason.FETCH)
                chain.next() == ColdChain.Step.TRACKER_FULL && chain.diag() == "waiting survival" &&
                    chain.reason == ColdChain.Reason.FETCH
            })
        check("chain55: cancel blocks starts and ends the run",
            run {
                val c = ColdChain()
                c.cancel()
                !c.canStart(ColdChain.Step.TRACKER_FULL, true, false) &&
                    c.next() == null && c.diag() == "cancelled" && c.isCancelled()
            })
        check("chain55: cancel mid-run finishes the in-flight step only",
            run {
                val c = ColdChain()
                c.start(ColdChain.Step.TRACKER_FULL, true, false)
                c.cancel()
                c.complete(ColdChain.Step.TRACKER_FULL)
                c.next() == null && c.diag() == "cancelled" && !c.isDone()
            })

        // ---- DeathCapture: trim names, line states, codec ----
        check("death55: running trim levels named, others numeric",
            DeathCapture.trimName(5) == "RUNNING_MODERATE" &&
                DeathCapture.trimName(10) == "RUNNING_LOW" &&
                DeathCapture.trimName(15) == "RUNNING_CRITICAL" &&
                DeathCapture.trimName(99) == "trim 99")
        val trimRec = DeathCapture.DeathRecord(15, "chain: match", true, 1729800000000L)
        check("death55: survived previous process ignores a stale record",
            DeathCapture.lastDeathLine(0, trimRec, "chain: match") == "last death: none recorded")
        check("death55: trim line names level, stage, after-first-frame",
            DeathCapture.lastDeathLine(1, trimRec, null) ==
                "last death: RUNNING_CRITICAL at chain: match (after first frame)")
        check("death55: trim line before first frame",
            DeathCapture.lastDeathLine(2, DeathCapture.DeathRecord(10, "composition done", false, 1L), null) ==
                "last death: RUNNING_LOW at composition done (before first frame)")
        check("death55: unknown line names the last persisted stage",
            DeathCapture.lastDeathLine(2, null, "composition done") ==
                "last death: unknown (last stage: composition done)")
        check("death55: unknown line with no stage stays unknown, never invented",
            DeathCapture.lastDeathLine(1, null, null) ==
                "last death: unknown (last stage: unknown stage)" &&
                DeathCapture.lastDeathLine(1, null, "") ==
                "last death: unknown (last stage: unknown stage)")
        check("death55: empty trim stage reads as unknown stage",
            DeathCapture.lastDeathLine(1, DeathCapture.DeathRecord(5, "", true, 1L), null) ==
                "last death: RUNNING_MODERATE at unknown stage (after first frame)")
        check("death55: record codec round-trips and strips pipes",
            run {
                val rec = DeathCapture.DeathRecord(15, "a|b", false, 42L)
                DeathCapture.decode(DeathCapture.encode(rec)) ==
                    DeathCapture.DeathRecord(15, "a b", false, 42L)
            })
        check("death55: malformed records decode to null (fail-open)",
            DeathCapture.decode(null) == null &&
                DeathCapture.decode("") == null &&
                DeathCapture.decode("1|2") == null &&
                DeathCapture.decode("x|a|1|2") == null &&
                DeathCapture.decode("5|a|2|100") == null &&
                DeathCapture.decode("-1|a|1|2") == null &&
                DeathCapture.decode("5|a|1|-3") == null)

        // ---- KeyOnly streaming match == classic matchAll ----
        val seriesLines = listOf(
            IptvStore.encodeSeries(IptvLogic.SeriesItem(101, "Reacher", "", "c1", "Drama", "", "", 0)),
            IptvStore.encodeSeries(IptvLogic.SeriesItem(102, "Preacher", "", "c1", "Drama", "", "", 0)),
            IptvStore.encodeSeries(IptvLogic.SeriesItem(103, "Dune", "", "c1", "SciFi", "", "", 2021)),
            IptvStore.encodeSeries(IptvLogic.SeriesItem(104, "Dune", "", "c1", "SciFi", "", "", 1984))
        )
        val vodLines = listOf(
            IptvStore.encodeVod(IptvLogic.VodItem(201, "Dune 2021 1080p", "", "c2", "Movies", "", "", 2021)),
            IptvStore.encodeVod(IptvLogic.VodItem(202, "Dune 1984", "", "c2", "Movies", "", "", 1984))
        )
        // The classic candidates (names retained) vs the key-only stream (names dropped at key time).
        val classicSeries = seriesLines.mapNotNull { IptvStore.decodeSeries(it) }
            .map { WatchMatch.Candidate(it.id, it.name, it.year) }
        val classicVod = vodLines.mapNotNull { IptvStore.decodeVod(it) }
            .map { WatchMatch.Candidate(it.id, it.name, it.year) }
        // The streaming shape: decode each line, key it, drop the name — no Tuple list is ever built.
        val keyedSeries = seriesLines.mapNotNull { line ->
            IptvStore.decodeSeries(line)?.let { WatchMatch.keyOnlyOf(it.id, it.name, it.year) }
        }
        val keyedVod = vodLines.mapNotNull { line ->
            IptvStore.decodeVod(line)?.let { WatchMatch.keyOnlyOf(it.id, it.name, it.year) }
        }
        check("key55: keyOnlyOf drops the name, keeps id + squash + year",
            // A known year wins over the title token; with no known
            // year, "2021" inside "Dune 2021 1080p" is NOT standalone
            // (neither wrapped nor last/first token), so no year is
            // extracted — the key is the same either way.
            WatchMatch.keyOnlyOf(7, "Dune 2021 1080p", 2021) ==
                WatchMatch.KeyOnly(7, "dune20211080p", 2021) &&
                WatchMatch.keyOnlyOf(7, "Dune 2021 1080p", 0) ==
                WatchMatch.KeyOnly(7, "dune20211080p", 0))
        val titles = listOf(
            WatchMatch.TrackerTitle(1, "tv", "Reacher", 0),
            WatchMatch.TrackerTitle(2, "tv", "Dune", 2021),
            WatchMatch.TrackerTitle(3, "tv", "Dune", 0),
            WatchMatch.TrackerTitle(4, "movie", "Dune", 2021),
            WatchMatch.TrackerTitle(5, "tv", "Zzzzz No Such Show", 0)
        )
        val classicTv = WatchMatch.matchAll(titles.filter { it.type == "tv" }, classicSeries)
        val keyedTv = WatchMatch.matchAllKeyed(titles.filter { it.type == "tv" }, keyedSeries)
        val classicMovies = WatchMatch.matchAll(titles.filter { it.type == "movie" }, classicVod)
        val keyedMovies = WatchMatch.matchAllKeyed(titles.filter { it.type == "movie" }, keyedVod)
        check("key55: key-only match == classic match (tv + movies)",
            classicTv == keyedTv && classicMovies == keyedMovies)
        check("key55: exact tier beats contains — Reacher finds exact Reacher, not Preacher",
            keyedTv[1]?.iptvId == 101L && keyedTv[1]?.exact == true)
        check("key55: repo contains case — Reacher finds catalogue Preacher when no exact exists",
            WatchMatch.matchOneKeyOnly(
                WatchMatch.TrackerTitle(1, "tv", "Reacher", 0),
                listOf(WatchMatch.keyOnlyOf(102, "Preacher", 0)),
                WatchMatch.exactIndexKeys(listOf(WatchMatch.keyOnlyOf(102, "Preacher", 0)))
            )?.let { it.iptvId == 102L && it.exact == false } == true)
        check("key55: exact tier wins and year narrows the pool",
            keyedTv[2]?.iptvId == 103L && keyedTv[2]?.exact == true && keyedTv[2]?.possible == false)
        check("key55: undated Dune hits the exact tier on both years, flagged possible",
            // Both series squash to "dune", so the exact tier (not
            // contains) fires with a 2-entry pool: first in
            // catalogue order, possible because unresolved.
            keyedTv[3]?.iptvId == 103L && keyedTv[3]?.exact == false && keyedTv[3]?.possible == true)
        check("key55: contains tier orders shortest first when years tie",
            // Undated movie Dune vs the two VODs: neither key equals
            // "dune", so contains fires; both year penalties tie at
            // 0, so the shortest squash ("dune1984") wins.
            WatchMatch.matchAllKeyed(
                listOf(WatchMatch.TrackerTitle(6, "movie", "Dune", 0)), keyedVod
            )[6]?.let { it.iptvId == 202L && it.exact == false && it.possible == true } == true)
        check("key55: movie Dune finds the 2021 VOD exactly enough (contains, year-agreeing)",
            keyedMovies[4]?.iptvId == 201L)
        check("key55: no match stays no match",
            keyedTv[5] == null)
        check("key55: empty title never matches, empty catalogue never matches",
            WatchMatch.matchAllKeyed(listOf(WatchMatch.TrackerTitle(9, "tv", "", 0)), keyedSeries).isEmpty() &&
                WatchMatch.matchAllKeyed(titles, emptyList()).isEmpty())

        // ---- Source checks: every wiring is really in the app ----
        fun src(path: String): String = try {
            java.io.File(path).readText()
        } catch (e: Exception) {
            ""
        }
        val appSrc = src("app/src/main/java/com/mizan/launcher/App.kt")
        val mainSrc = src("app/src/main/java/com/mizan/launcher/MainActivity.kt")
        val clientSrc = src("app/src/main/java/com/mizan/launcher/data/TrackerClient.kt")
        val iptvSrc = src("app/src/main/java/com/mizan/launcher/data/IptvClient.kt")
        val watchSrc = src("app/src/main/java/com/mizan/launcher/data/WatchPlay.kt")
        val discSrc = src("app/src/main/java/com/mizan/launcher/data/DiscoveryData.kt")
        val cacheSrc = src("app/src/main/java/com/mizan/launcher/data/HomeCacheDisk.kt")
        val chainSrc = src("app/src/main/java/com/mizan/launcher/data/ColdChainRunner.kt")
        val deathSrc = src("app/src/main/java/com/mizan/launcher/data/DeathStore.kt")
        val settingsSrc = src("app/src/main/java/com/mizan/launcher/ui/SettingsActivity.kt")
        check("src55: App.onCreate warms slim only — no full parse, no discovery preload",
            appSrc.contains("warmSlimAsync(this)") &&
                !appSrc.contains("warmMemoAsync(this)") &&
                !appSrc.contains("DiscoveryData.preload(this)"))
        check("src55: App captures trim warnings for the death record",
            appSrc.contains("override fun onTrimMemory") &&
                appSrc.contains("DeathStore.record(this, level, stage, afterFirst)"))
        check("src55: parallel colddwarm threads are gone; the chain kick replaces them",
            !mainSrc.contains("mizan-coldwarm") &&
                !mainSrc.contains("scheduleColdWarm") &&
                mainSrc.contains("ColdChainRunner.kickAfterSurvival(appCtx)"))
        check("src55: home build reads the cached match table, never kicks a recompute",
            mainSrc.contains("wMatches = WatchPlay.cachedMatches(this)") &&
                !mainSrc.contains("WatchPlay.matches(appCtx)"))
        check("src55: home tracker kick is slim-only",
            mainSrc.contains("TrackerClient.warmSlimAsync(this)"))
        check("src55: chain landing + destroy wiring in MainActivity",
            mainSrc.contains("ColdChainRunner.onLanded") &&
                mainSrc.contains("ColdChainRunner.cancel()"))
        // v3.7 RE-PINNED (launcher IS the tracker): no fetch
        // landings any more — refresh installs the store memo
        // directly and posts onChanged; the chain is never
        // kicked from a fetch path.
        check("src55: TrackerClient slim warm exists, full-parse warm is gone, local re-read replaces the chain kick",
            clientSrc.contains("fun warmSlimAsync") &&
                !clientSrc.contains("fun warmMemoAsync") &&
                clientSrc.contains("cachedMemo = snap") &&
                !clientSrc.contains("kickAfterFetch"))
        check("src55: tracker mirror fallback reads disk on demand with a context",
            clientSrc.contains("HomeCacheDisk.mirrored(ctx,"))
        check("src55: tuples memo + blocking read are gone; key-only stream + counts replace them",
            iptvSrc.contains("fun keyedCatalogue") &&
                iptvSrc.contains("fun catalogueCounts") &&
                !iptvSrc.contains("fun tuplesBlocking") &&
                !iptvSrc.contains("private var tuplesMemo"))
        check("src55: match recompute is fingerprint-first, then key-only",
            watchSrc.contains("fun recomputeSyncNow") &&
                watchSrc.contains("matchAllKeyed") &&
                run {
                    // Order inside the recompute body only (the doc
                    // comment above it mentions the stream first).
                    val body = watchSrc.substringAfter("private fun recomputeOnWorker")
                    body.indexOf("catalogueCounts") < body.indexOf("keyedCatalogue")
                } &&
                !watchSrc.contains("tuplesBlocking"))
        check("src55: discovery snapshot no longer kicks; runNow is the chain entry",
            discSrc.substringAfter("fun snapshot").substringBefore("fun peek").contains("kick(ctx)") == false &&
                discSrc.contains("fun runNow"))
        check("src55: discovery worker never parses on an automatic run (memo-only unless forced)",
            discSrc.contains("TrackerClient.memoOrNull()") &&
                discSrc.contains("if (force) TrackerClient.cached(appCtx) else return false"))
        check("src55: home-cache mirror is metadata-only, contents read on demand",
            cacheSrc.contains("mirrorMeta") &&
                cacheSrc.contains("fun hasMirror") &&
                cacheSrc.contains("fun mirrored(ctx: Context") &&
                !cacheSrc.contains("mirror = loaded"))
        check("src55: chain runner is one sequential thread with survival + fetch kicks",
            chainSrc.contains("mizan-coldchain") &&
                chainSrc.contains("fun kickAfterSurvival") &&
                chainSrc.contains("fun kickAfterFetch") &&
                chainSrc.contains("fun cancel()"))
        check("src55: death store commits trim records to its own tiny prefs file",
            deathSrc.contains("\"mizan_death\"") &&
                deathSrc.contains(".commit()") &&
                deathSrc.contains("fun diagLine"))
        check("src55: diagnostics gains last-death + cold-chain lines",
            settingsSrc.contains("DeathStore") &&
                settingsSrc.contains("cold chain: \" +") &&
                settingsSrc.contains("ColdChainRunner.diagState"))
        check("src55: hero rule untouched (missed first, today stays out)",
            mainSrc.contains("fillHeroDates") && mainSrc.contains("todayIso(), dSoon"))
    }
}

/** v3.6 checks — indexed tracker store + paged/visible-first (ask 53, spec §36). Each test here FAILS on the v3.5 code (no TrackerStore, no VisibleWindow, no streamed match, whole-doc parses in Details/Library/Discovery) and PASSES on v3.6. */
fun section56() {
    run {
        // ---- TrackerStore codecs: round-trips, separators, malformed fail-open ----
        val title = TrackerStore.Title("tv", 42L, "Show | 100% \n Real", "/p.jpg", "/b.jpg", 500, true, false, 2, 3)
        check("store56: title line round-trips separators (pipe, percent, newline)",
            TrackerStore.decodeTitle(TrackerStore.encodeTitle(title) ?: "") == title)
        check("store56: malformed title lines fail open (blank, bad id, blank title)",
            TrackerStore.decodeTitle("") == null &&
                TrackerStore.decodeTitle("tv|0|Nope| | |0|0|0|0|0") == null &&
                TrackerStore.decodeTitle("tv|7|| | |0|0|0|0|0") == null)
        val up = TrackerLogic.UpNextItem(7L, "Up | Show", "Next S1 E2", "2026-10-24", "/p.jpg", "/b.jpg", "ov", 1, 2, "tv")
        check("store56: upnext/continue/movie/foryou lines round-trip",
            TrackerStore.decodeUpNext(TrackerStore.encodeUpNext(up) ?: "") == up &&
                TrackerStore.decodeContinue(
                    TrackerStore.encodeContinue(
                        TrackerLogic.ContinueItem(8L, "Cont", "sub", 3, 10, "/p.jpg", "/b.jpg", "ov", "tv")
                    ) ?: ""
                )?.watched == 3 &&
                TrackerStore.decodeMovie(
                    TrackerStore.encodeMovie(
                        TrackerLogic.MovieItem(9L, "Mov", "2024-01-01", false, "/m.jpg", true)
                    ) ?: ""
                )?.watched == true &&
                TrackerStore.decodeForYou(
                    TrackerStore.encodeForYou(
                        TrackerLogic.ForYouPick(10L, "tv", "Pick", "/f.jpg", 7.4, "Because", listOf("Drama", "Sci-Fi"), 1.5)
                    ) ?: ""
                )?.whyGenres == listOf("Drama", "Sci-Fi"))
        val ep = TrackerStore.EpisodeFile(true, (1..500).map { "1x$it" }.toSet())
        val epJson = TrackerStore.encodeEpisode(ep) ?: ""
        check("store56: episode file round-trips a 500-entry set, muted flag kept",
            TrackerStore.decodeEpisode(epJson)?.watched?.size == 500 &&
                TrackerStore.decodeEpisode(epJson)?.muted == true &&
                TrackerStore.decodeEpisode("garbage") == null &&
                TrackerStore.decodeEpisode("") == null)
        val idx = TrackerStore.Index(46, 11, 46, 13, 15, 1729800000000L, 1729800001000L, "abcdef0123456789", 46)
        check("store56: index round-trips; corrupt index decodes null (fallback, never empty home)",
            TrackerStore.decodeIndex(TrackerStore.encodeIndex(idx)) == idx &&
                TrackerStore.decodeIndex("{\"titles\":") == null &&
                TrackerStore.decodeIndex("") == null)
        check("store56: store diag line names counts + fallback state, never titles",
            TrackerStore.diagLine(idx, "store") == "46 titles · 46 episode files · index 1729800000000 · fallback: store" &&
                TrackerStore.diagLine(null, "prefs") == "absent · fallback: prefs")

        // ---- Fingerprint: content, not lengths; fetchedAt is not an input ----
        val titlesA = (1..5).map {
            TrackerStore.Title("tv", it.toLong(), "Show $it", "", "", it * 10, false, false, 0, 0)
        }
        val titlesB = titlesA.map { if (it.tmdbId == 3L) it.copy(watchedCount = 20) else it }
        val fpA = TrackerStore.fingerprint(1000L, titlesA, emptyList(), emptyList(), emptyList(), emptyList())
        val fpB = TrackerStore.fingerprint(1000L, titlesB, emptyList(), emptyList(), emptyList(), emptyList())
        check("store56: fingerprint distinguishes same-length changes (count 30 -> 20, same digits)",
            fpA != fpB && fpA.length == 16)
        check("store56: fingerprint is stable for identical content (a fetch that changes only fetchedAt must not invalidate)",
            TrackerStore.fingerprint(1000L, titlesA, emptyList(), emptyList(), emptyList(), emptyList()) == fpA)

        // ---- Paged line reading: first window only, no extra pull ----
        val fiftyLines = (1..50).map { id ->
            TrackerStore.encodeTitle(
                TrackerStore.Title("tv", id.toLong(), "Show $id", "", "", id, false, false, 0, 0)
            ) ?: ""
        }
        var pulls = 0
        val counting = sequence {
            for (line in fiftyLines) {
                pulls++
                yield(line)
            }
        }
        val (page, consumed) = TrackerStore.readPage(counting, 0, VisibleWindow.FIRST_WINDOW, TrackerStore::decodeTitle)
        check("store56: open reads only the visible window (20 lines consumed of 50, no more pulled)",
            page.size == 20 && consumed == 20 && pulls == 20 &&
                page.first().tmdbId == 1L && page.last().tmdbId == 20L)
        val (page2, consumed2) = TrackerStore.readPage(fiftyLines.asSequence(), 20, VisibleWindow.FIRST_WINDOW, TrackerStore::decodeTitle)
        check("store56: second page starts at 21, stops at the window",
            page2.size == 20 && page2.first().tmdbId == 21L && consumed2 == 40)

        // ---- Episode sets are per-title; counts without the set ----
        val bigTitle = TrackerStore.Title("tv", 99L, "Big Show", "", "", 500, false, false, 0, 0)
        val bigLine = TrackerStore.encodeTitle(bigTitle) ?: ""
        check("store56: watchedCount without the set (title line carries 500, no episode keys ride it)",
            TrackerStore.decodeTitle(bigLine)?.watchedCount == 500 &&
                !bigLine.contains("1x4") && !bigLine.contains("1x"))
        check("store56: fetch re-indexes incrementally (changed show's episode file differs, others identical)",
            run {
                val oldEp = TrackerStore.encodeEpisode(TrackerStore.EpisodeFile(false, setOf("1x1", "1x2"))) ?: ""
                val newEp = TrackerStore.encodeEpisode(TrackerStore.EpisodeFile(false, setOf("1x1", "1x2", "1x3"))) ?: ""
                val otherEp = TrackerStore.encodeEpisode(TrackerStore.EpisodeFile(false, setOf("2x1"))) ?: ""
                oldEp != newEp && TrackerStore.encodeEpisode(TrackerStore.EpisodeFile(false, setOf("2x1"))) == otherEp
            })

        // ---- Eviction gate ----
        check("store56: prefs eviction is gated (all three legs required)",
            !TrackerStore.evictionAllowed(false, true, true) &&
                !TrackerStore.evictionAllowed(true, false, true) &&
                !TrackerStore.evictionAllowed(true, true, false) &&
                TrackerStore.evictionAllowed(true, true, true))

        // ---- VisibleWindow ----
        check("store56: visible window math (46 items = 3 pages; first window 0..19; page-in near end only)",
            VisibleWindow.FIRST_WINDOW == 20 &&
                VisibleWindow.pageCount(46) == 3 &&
                VisibleWindow.windowFor(46, 0) == (0 until 20) &&
                VisibleWindow.windowFor(46, 2) == (40 until 46) &&
                VisibleWindow.windowFor(46, 3) == null &&
                VisibleWindow.nextPageIndex(20, 46) == 1 &&
                VisibleWindow.nextPageIndex(46, 46) == null &&
                VisibleWindow.shouldPageIn(18, 20, 46) &&
                !VisibleWindow.shouldPageIn(5, 20, 46) &&
                !VisibleWindow.shouldPageIn(19, 20, 20))

        // ---- Streamed match == key-only match == classic match ----
        val catKeys = (1..5000).map { id ->
            WatchMatch.KeyOnly(id.toLong(), "show$id", 0)
        } + listOf(
            WatchMatch.keyOnlyOf(101, "Reacher", 0),
            WatchMatch.keyOnlyOf(102, "Preacher", 0),
            WatchMatch.keyOnlyOf(103, "Dune", 2021),
            WatchMatch.keyOnlyOf(104, "Dune", 1984)
        )
        val keyedClassic = catKeys.filter { it.iptvId in listOf(101L, 102L, 103L, 104L) }
        val visTitles = listOf(
            WatchMatch.TrackerTitle(1, "tv", "Reacher", 0),
            WatchMatch.TrackerTitle(2, "tv", "Dune", 2021),
            WatchMatch.TrackerTitle(3, "tv", "Dune", 0)
        )
        val streamed = WatchMatch.matchStreamed(visTitles) { catKeys.asSequence() }
        val keyed = WatchMatch.matchAllKeyed(visTitles, keyedClassic)
        check("store56: match-streamed == matchAllKeyed (exact, year-narrowed, possible, Preacher contains)",
            streamed[1]?.iptvId == 101L && streamed[1]?.exact == true &&
                streamed[2]?.iptvId == 103L && streamed[2]?.exact == true &&
                streamed[3]?.iptvId == 103L && streamed[3]?.possible == true &&
                streamed == keyed)
        check("store56: match-visible-titles-only-on-open (below-fold titles never enter the result)",
            !streamed.containsKey(999L) &&
                WatchMatch.matchStreamed(
                    listOf(WatchMatch.TrackerTitle(9, "tv", "Reacher", 0))
                ) { listOf(WatchMatch.keyOnlyOf(102, "Preacher", 0)).asSequence() }[9]?.iptvId == 102L)
        var streamPulls = 0
        val countingKeys = sequence {
            for (k in catKeys) {
                streamPulls++
                yield(k)
            }
        }
        // One visible title with an exact hit: exactly one full exact pass, no contains pass, no list built.
        val oneHit = WatchMatch.matchOneStreamed(visTitles[0]) { countingKeys }
        check("store56: match-streamed-scan pulls one exact pass only for an exact hit (no contains pass, no retained list)",
            oneHit?.iptvId == 101L && streamPulls == catKeys.size)

        // ---- Library count-only ----
        val countOnly = LibraryLogic.LibShow(42L, "Count Show", false, false, emptySet(), watchedCount = 500)
        check("store56: library count-only show reports its count without a set, and is flagged count-only",
            countOnly.count() == 500 && countOnly.isCountOnly &&
                !LibraryLogic.LibShow(42L, "Full Show", false, false, setOf("1x1")).isCountOnly)
        check("store56: grid count is the effective count (count-only base + pending adds - removes)",
            LibraryLogic.buildLibrary(
                listOf(LibraryLogic.LibShow(42L, "Count Show", false, false, emptySet(), watchedCount = 500)),
                emptyList(), emptyList(), emptyList(),
                LibraryLogic.withEpisodeToggle(emptyList(), 42, 1, 1, true, 5L),
                emptyMap()
            ).single().watchedCount == 501)

        // ---- Source checks: every wiring is really in the app ----
        fun src(path: String): String = try {
            java.io.File(path).readText()
        } catch (e: Exception) {
            ""
        }
        val clientSrc = src("app/src/main/java/com/mizan/launcher/data/TrackerClient.kt")
        val storeSrc = src("app/src/main/java/com/mizan/launcher/data/TrackerStoreDisk.kt")
        val discSrc = src("app/src/main/java/com/mizan/launcher/data/DiscoveryData.kt")
        val watchSrc = src("app/src/main/java/com/mizan/launcher/data/WatchPlay.kt")
        val iptvSrc = src("app/src/main/java/com/mizan/launcher/data/IptvClient.kt")
        val mainSrc = src("app/src/main/java/com/mizan/launcher/MainActivity.kt")
        val detailsSrc = src("app/src/main/java/com/mizan/launcher/ui/DetailsActivity.kt")
        val libSrc = src("app/src/main/java/com/mizan/launcher/ui/LibraryActivity.kt")
        val chainSrc = src("app/src/main/java/com/mizan/launcher/data/ColdChainRunner.kt")
        val settingsSrc = src("app/src/main/java/com/mizan/launcher/ui/SettingsActivity.kt")
        val matchSrc = src("app/src/main/java/com/mizan/launcher/core/WatchMatch.kt")
        check("src56: cold chain TRACKER step reads the store first, full parse only as fallback",
            chainSrc.contains("TrackerClient.storeOrCached(appCtx)") &&
                clientSrc.contains("fun storeOrCached") &&
                clientSrc.contains("fun storeSnapshots"))
        check("src56: fetch worker writes the store from its one parse and arms the eviction gate",
            clientSrc.contains("TrackerStoreDisk.writeFromSnapshots(appCtx, snap, lib)") &&
                clientSrc.contains("storeFetchLanded = true") &&
                clientSrc.contains("fun maybeEvictPrefs") &&
                clientSrc.contains("evictionAllowed"))
        check("src56: store writer swaps index last and aborts on a credential trip",
            storeSrc.contains("TrackerStore.encodeTitle(t) ?: return null") &&
                storeSrc.contains("writeAtomic(fileFor(ctx, F_INDEX)"))
        check("src56: Details + Library never whole-parse (no parseLibShows call in either screen)",
            !detailsSrc.contains("parseLibShows(") &&
                !libSrc.contains("parseLibShows(") &&
                detailsSrc.contains("TrackerStoreDisk.libShowFor") &&
                libSrc.contains("TrackerStoreDisk.libraryShows"))
        check("src56: Discovery never whole-parses and gates shaping on the tracker stamp",
            !discSrc.contains("parseLibShows(") &&
                discSrc.contains("lastShapedKey") &&
                discSrc.contains("shapingNeeded") &&
                discSrc.contains("iptvRowVisible"))
        check("src56: visible-first matching is wired (streamed scan, visible kick, first-window warm-up)",
            watchSrc.contains("fun kickVisibleRecompute") &&
                watchSrc.contains("matchStreamed") &&
                mainSrc.contains("WatchPlay.visibleNeedsMatch") &&
                mainSrc.contains("tUpNext.take(window)") &&
                mainSrc.contains("tCont.take(window)"))
        check("src56: streamed matcher never materialises a key list (no toList on the factory sequence)",
            run {
                val body = matchSrc.substringAfter("fun matchOneStreamed").substringBefore("fun matchStreamed")
                body.contains("for (k in keyFactory())") && !body.contains("toList()")
            } &&
                !watchSrc.contains("tuplesBlocking") &&
                !mainSrc.contains("tuplesBlocking"))
        check("src56: home rows page, not inflate-all (pagedRow on Up Next, Continue, Trending)",
            mainSrc.contains("fun <T> pagedRow") &&
                mainSrc.contains("pagedRow(items) { item ->") &&
                mainSrc.contains("pagedRow(cards) { card ->"))
        check("src56: series-info bodies live in per-series files, fetches never write prefs",
            iptvSrc.contains("seriesInfoFile(ctx, seriesId)") &&
                iptvSrc.contains("writeSeriesInfoFile(appCtx, seriesId, json)") &&
                run {
                    val body = iptvSrc.substringAfter("fun fetchSeriesInfoAsync")
                    !body.contains("putString(seriesInfoKey")
                })
        check("src56: diagnostics gains the tracker store line; slim line kept until eviction",
            settingsSrc.contains("tracker store: \" +") &&
                settingsSrc.contains("TrackerClient.storeDiag") &&
                settingsSrc.contains("slim: \" +"))
        check("src56: hero rule untouched by the store migration (missed first, today stays out)",
            mainSrc.contains("fillHeroDates") && mainSrc.contains("todayIso(), dSoon"))
        // v3.7 RE-PINNED (launcher IS the tracker): the RULE
        // is untouched (home build never fetches — no
        // provider fetch exists any more); the mechanism is
        // a local store re-read, not a chain kick.
        check("src56: no-refresh rule untouched (local re-read only; home build never fetches)",
            clientSrc.contains("fun refreshAsync") &&
                !clientSrc.contains("kickAfterFetch") &&
                !mainSrc.substringAfter("private fun prepareTrackerData").substringBefore("private fun prepareWatchData").contains("refreshAsync"))
    }
}

/** v3.7 checks — top-level because main() reached the JVM method-size limit. */
fun section57() {
    run {
        // ---- Missed row: the pure filter (core/TrackerLogic.missedRow) ----
        val today = "2026-10-25"
        fun item(id: Long, title: String, date: String, s: Int, e: Int, type: String = "tv") =
            TrackerLogic.UpNextItem(id, title, "S$s E$e", date, "/poster.jpg", "/backdrop.jpg", "", s, e, type)
        val past = item(1, "Old Show", "2026-10-20", 1, 5)
        val older = item(2, "Older Show", "2026-10-10", 2, 3)
        val todayItem = item(3, "Today Show", "2026-10-25", 1, 1)
        val future = item(4, "Future Show", "2026-10-28", 1, 1)
        val watchedPast = item(5, "Watched Show", "2026-10-15", 1, 2)
        val moviePast = item(6, "Movie", "2026-10-12", 0, 0, "movie")
        val coordless = item(7, "No Coords", "2026-10-11", 0, 0)
        val dateless = item(8, "No Date", "", 1, 1)
        val dupPast = item(1, "Old Show", "2026-10-20", 1, 5)
        val missed = TrackerLogic.missedRow(
            listOf(past, older, todayItem, future, watchedPast, moviePast, coordless, dateless, dupPast),
            { it.tmdbId == 5L },
            today
        )
        check("missed57: row contains ONLY past-unwatched, most-overdue first, episode-precise",
            missed.map { it.tmdbId } == listOf(2L, 1L))
        check("missed57: today, future, watched, movie, coordless, dateless all excluded",
            missed.none { it.tmdbId in setOf(3L, 4L, 5L, 6L, 7L, 8L) })

        // ---- Missed computation: first unwatched past episode, one at a time ----
        val seasons = listOf(
            TrackerLogic.SeasonAirDates(1, listOf(1 to "2026-10-01", 2 to "2026-10-08", 3 to "2026-10-15")),
            TrackerLogic.SeasonAirDates(2, listOf(1 to "2026-10-22"))
        )
        check("missed57: first missed is the earliest unwatched past episode",
            TrackerLogic.firstMissedEpisode(setOf("1x1"), seasons, today) == Triple(1, 2, "2026-10-08"))
        check("missed57: marking advances one at a time (next passed episode surfaces)",
            TrackerLogic.firstMissedEpisode(setOf("1x1", "1x2"), seasons, today) == Triple(1, 3, "2026-10-15") &&
                TrackerLogic.firstMissedEpisode(setOf("1x1", "1x2", "1x3"), seasons, today) == Triple(2, 1, "2026-10-22"))
        check("missed57: caught-up show has no missed episode",
            TrackerLogic.firstMissedEpisode(setOf("1x1", "1x2", "1x3", "2x1"), seasons, today) == null)
        check("missed57: future-only unwatched show has no missed episode",
            TrackerLogic.firstMissedEpisode(
                emptySet(),
                listOf(TrackerLogic.SeasonAirDates(1, listOf(1 to "2026-10-28"))),
                today
            ) == null)
        check("missed57: blank date skipped, specials skipped, no date invented",
            TrackerLogic.firstMissedEpisode(
                emptySet(),
                listOf(
                    TrackerLogic.SeasonAirDates(0, listOf(1 to "2026-10-01")),
                    TrackerLogic.SeasonAirDates(1, listOf(1 to "", 2 to "2026-10-05"))
                ),
                today
            ) == Triple(1, 2, "2026-10-05"))

        // ---- Section registry: Missed joins after Up Next, migrates cleanly ----
        check("missed57: MISSED sits right after UPNEXT in CANONICAL",
            TrackerSections.CANONICAL.indexOf(TrackerSections.MISSED) ==
                TrackerSections.CANONICAL.indexOf(TrackerSections.UPNEXT) + 1)
        check("missed57: pre-v3.7 order gains Missed after Up Next (not appended at the end)",
            TrackerSections.decode("upnext,continue,foryou,favorites,allapps|").order ==
                listOf("upnext", "missed", "continue", "foryou", "favorites", "allapps", "trendingtv", "trendingmovies", "folders"))
        check("missed57: Missed label + compose gating (shown, available, hideable)",
            TrackerSections.label(TrackerSections.MISSED) == "Missed" &&
                TrackerSections.compose(TrackerSections.decode("upnext,missed,continue|")) { it == "upnext" || it == "missed" } ==
                    listOf("upnext", "missed") &&
                TrackerSections.compose(TrackerSections.decode("upnext,missed,continue|missed")) { it == "upnext" || it == "continue" || it == "missed" } ==
                    listOf("upnext", "continue"))
        check("missed57: deliberate Missed position is preserved (normalize never re-splices a stored Missed)",
            TrackerSections.decode("continue,upnext,missed,foryou,favorites,allapps|").order ==
                listOf("continue", "upnext", "missed", "foryou", "favorites", "allapps", "trendingtv", "trendingmovies", "folders"))

        // ---- Missed fingerprint: content, not lengths ----
        val titlesA = listOf(
            TrackerStore.Title("tv", 1, "A", "/p", "/b", 500, false, false, 1, 2),
            TrackerStore.Title("tv", 2, "B", "/p", "/b", 3, false, false, 0, 0)
        )
        val titlesB = listOf(
            TrackerStore.Title("tv", 1, "A", "/p", "/b", 501, false, false, 1, 2),
            TrackerStore.Title("tv", 2, "B", "/p", "/b", 3, false, false, 0, 0)
        )
        check("missed57: missed fingerprint distinguishes same-shape different-count libraries",
            TrackerStore.missedFingerprint(titlesA) != TrackerStore.missedFingerprint(titlesB) &&
                TrackerStore.missedFingerprint(titlesA) == TrackerStore.missedFingerprint(titlesA))
        check("missed57: missed fingerprint ignores movies (TV-only input)",
            TrackerStore.missedFingerprint(titlesA) == TrackerStore.missedFingerprint(
                titlesA + TrackerStore.Title("movie", 9, "M", "/p", "/b", 1, false, false, 0, 0)
            ))

        // ---- Missed codec: UpNext line round-trips every card field ----
        val missedItem = TrackerLogic.UpNextItem(
            42, "Show", "S2 E3", "2026-10-19", "/poster.jpg", "/backdrop.jpg", "Overview", 2, 3, "tv"
        )
        check("missed57: missed item survives the UpNext line codec (poster, backdrop, coords, date)",
            TrackerStore.decodeUpNext(TrackerStore.encodeUpNext(missedItem) ?: "") == missedItem)

        // ---- Store-aware prune: local writes converge without any provider stamp ----
        val storeShow = LibraryLogic.LibShow(42L, "Show", false, false, setOf("1x1", "1x2"))
        val overlayWithAdd = LibraryLogic.withEpisodeToggle(emptyList(), 42, 1, 1, true, 5L)
        check("missed57: prunePendingLocal retires a store-proven episode add (no stamp needed)",
            LibraryLogic.prunePendingLocal(overlayWithAdd, listOf(storeShow), emptyList()).isEmpty())
        val overlayUnproven = LibraryLogic.withEpisodeToggle(emptyList(), 42, 1, 9, true, 5L)
        check("missed57: prunePendingLocal keeps an unproven episode add (fail-open)",
            LibraryLogic.prunePendingLocal(overlayUnproven, listOf(storeShow), emptyList()).size == 1)

        // ---- Source checks: every wiring is really in the app ----
        fun src(path: String): String = try {
            java.io.File(path).readText()
        } catch (e: Exception) {
            ""
        }
        val clientSrc = src("app/src/main/java/com/mizan/launcher/data/TrackerClient.kt")
        val localSrc = src("app/src/main/java/com/mizan/launcher/data/LibraryLocal.kt")
        val actionsSrc = src("app/src/main/java/com/mizan/launcher/data/TrackerActions.kt")
        val storeDiskSrc = src("app/src/main/java/com/mizan/launcher/data/TrackerStoreDisk.kt")
        val chainSrc = src("app/src/main/java/com/mizan/launcher/data/ColdChainRunner.kt")
        val mainSrc = src("app/src/main/java/com/mizan/launcher/MainActivity.kt")
        val detailsSrc = src("app/src/main/java/com/mizan/launcher/ui/DetailsActivity.kt")
        val libSrc = src("app/src/main/java/com/mizan/launcher/ui/LibraryActivity.kt")
        val settingsSrc = src("app/src/main/java/com/mizan/launcher/ui/SettingsActivity.kt")
        val posterSrc = src("app/src/main/java/com/mizan/launcher/ui/PosterCards.kt")
        val sectionsSrc = src("app/src/main/java/com/mizan/launcher/core/TrackerSections.kt")
        val manifestSrc = src("app/src/main/AndroidManifest.xml")

        check("src57: provider action queue is gone; writes go through LibraryLocal",
            !clientSrc.contains("fun callAction") &&
                !clientSrc.contains("fun markWatched(") &&
                !clientSrc.contains("fun addTitle(") &&
                !clientSrc.contains("fun dismissForYou(") &&
                actionsSrc.contains("LibraryLocal.queueEpisodeToggle") &&
                actionsSrc.contains("LibraryLocal.queueMovieToggle") &&
                actionsSrc.contains("LibraryLocal.queueAddTitle") &&
                actionsSrc.contains("LibraryLocal.queueRemoveTitle") &&
                actionsSrc.contains("LibraryLocal.queueMuted") &&
                mainSrc.contains("TrackerActions.markWatchedHome"))
        check("src57: one-time import is the sole provider contact (flag-gated, marked as the exception)",
            clientSrc.contains("fun runOneTimeImport") &&
                clientSrc.contains("libraryImported") &&
                clientSrc.contains("enum class ImportResult") &&
                clientSrc.contains("sole provider contact") &&
                manifestSrc.contains("LAUNCHER_READ") &&
                manifestSrc.contains("com.haythem.tvtracker"))
        check("src57: no live provider read outside TrackerClient (no launcherdata string in any screen/action/local file)",
            !mainSrc.contains("launcherdata") &&
                !detailsSrc.contains("launcherdata") &&
                !libSrc.contains("launcherdata") &&
                !actionsSrc.contains("launcherdata") &&
                !localSrc.contains("launcherdata"))
        check("src57: TMDB is direct-only (no proxy, no proxy-first resolution)",
            clientSrc.contains("TmdbDirect.getBlocking") &&
                !clientSrc.contains("tmdbGetViaProxy") &&
                !clientSrc.contains("resolveTmdb"))
        check("src57: Details unlock — Add to my list for untracked titles, old gate toast gone",
            detailsSrc.contains("Add to my list") &&
                detailsSrc.contains("add_to_list") &&
                detailsSrc.contains("Add this title to your list to use episode tools") &&
                run {
                    val body = detailsSrc.substringAfter("private fun episodeRow")
                    !body.substringBefore("private fun playEpisodeFromDetails")
                        .contains("Episode tools unlock once this title is in the tracker")
                } &&
                !detailsSrc.contains("Open in Tracker"))
        check("src57: no Open Tracker entry in any card menu (menus only, bodies scoped)",
            run {
                val menu = mainSrc.substringAfter("private fun cardMenu")
                    .substringBefore("private fun openTitleDetails")
                !menu.contains("Open Tracker")
            } &&
                run {
                    val menu = mainSrc.substringAfter("private fun openTrendingMenu")
                        .substringBefore("private fun addToMyList")
                    !menu.contains("Open Tracker")
                } &&
                run {
                    val menu = mainSrc.substringAfter("private fun openForYouMenu")
                        .substringBefore("private fun buildInputsSection")
                    !menu.contains("Open Tracker")
                } &&
                !libSrc.contains("Open in Tracker") &&
                !actionsSrc.contains("openTrackerGated"))
        check("src57: hero buttons no longer gated on the tracker app being installed",
            !mainSrc.contains("TrackerClient.isInstalled") &&
                mainSrc.contains("libraryHere") &&
                mainSrc.contains("tSnap.hasData"))
        check("src57: missed row wired end to end (section, filter, worker, chain tail, retry)",
            sectionsSrc.contains("MISSED") &&
                mainSrc.contains("tMissed") &&
                mainSrc.contains("buildMissedSection") &&
                mainSrc.contains("TrackerLogic.missedRow") &&
                mainSrc.contains("LibraryLocal.kickMissedAll") &&
                chainSrc.contains("LibraryLocal.kickMissedAll") &&
                localSrc.contains("fun kickMissedAll") &&
                localSrc.contains("fun recomputeMissedForShow") &&
                localSrc.contains("fun refreshNextEpisode") &&
                storeDiskSrc.contains("fun writeMissed") &&
                storeDiskSrc.contains("fun readMissed") &&
                storeDiskSrc.contains("fun writeSeasonDates"))
        check("src57: local writes are serialized + atomic (one lock, index rewrite, memo install)",
            localSrc.contains("synchronized(lock)") &&
                storeDiskSrc.contains("fun rewriteIndex") &&
                clientSrc.contains("fun installStoreMemo") &&
                localSrc.contains("finishWriteLocked"))
        check("src57: poster fix — portrait cards FIT_CENTER, landscape cards poster-first, hero stays backdrop",
            run {
                val body = mainSrc.substringAfter("private fun portraitCard")
                    .substringBefore("private fun landscapeCard")
                body.contains("FIT_CENTER") && !body.contains("CENTER_CROP")
            } &&
                mainSrc.contains("item.poster.ifBlank { item.backdrop }") &&
                run {
                    val body = mainSrc.substringAfter("private fun upNextCard")
                        .substringBefore("private fun buildMergedUpNextSection")
                    body.contains("playUpNextItem") && body.contains("openTitleDetails")
                } &&
                run {
                    val body = mainSrc.substringAfter("private fun addTrackerBackground")
                        .substringBefore("private fun buildHeroZone")
                    body.contains("hero.backdrop") && body.contains("w1280") &&
                        !body.contains("hero.poster")
                } &&
                posterSrc.contains("FIT_CENTER") && !posterSrc.contains("CENTER_CROP"))
        check("src57: diagnostics library line replaces the tracker line",
            settingsSrc.contains("library: \" +") &&
                settingsSrc.contains("TrackerClient.libraryDiag") &&
                !settingsSrc.contains("TrackerClient.status"))
        check("src57: hero rule untouched (missed first, today stays out) + no-refresh untouched (local re-read only)",
            mainSrc.contains("fillHeroDates") && mainSrc.contains("todayIso(), dSoon") &&
                clientSrc.contains("fun refreshAsync") &&
                run {
                    val body = clientSrc.substringAfter("fun refreshAsync")
                        .substringBefore("private data class PathResult")
                    body.contains("storeSnapshots") && body.contains("runOneTimeImport") &&
                        !body.contains("queryPath(appCtx")
                })
    }
}
