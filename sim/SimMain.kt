import com.calm.launcher.core.AppEntry
import com.calm.launcher.core.HomeModel

var passed = 0
var failed = 0

fun check(name: String, ok: Boolean) {
    if (ok) { passed++; println("PASS  $name") } else { failed++; println("FAIL  $name") }
}

fun app(pkg: String, label: String = pkg) = AppEntry(pkg, label, ".Main", "LEANBACK_LAUNCHER")

fun main() {
    val installed = listOf(app("a.one", "Zebra"), app("b.two", "apple"), app("c.three", "Mango"))

    check("favorites keep saved order and drop uninstalled packages",
        HomeModel.favorites(listOf("c.three", "gone.app", "a.one"), installed).map { it.pkg } == listOf("c.three", "a.one"))
    check("favorites drop duplicates",
        HomeModel.favorites(listOf("a.one", "a.one"), installed).size == 1)
    check("favorites cap at MAX_FAVORITES",
        HomeModel.favorites(installed.map { it.pkg } + (1..20).map { "x$it" }, installed + (1..20).map { app("x$it") }).size == HomeModel.MAX_FAVORITES)

    check("toggleFavorite adds to the end",
        HomeModel.toggleFavorite(listOf("a.one"), "b.two") == listOf("a.one", "b.two"))
    check("toggleFavorite removes an existing favorite",
        HomeModel.toggleFavorite(listOf("a.one", "b.two"), "a.one") == listOf("b.two"))
    val full = (1..HomeModel.MAX_FAVORITES).map { "p$it" }
    check("toggleFavorite refuses a 13th favorite",
        HomeModel.toggleFavorite(full, "new.app") == null)
    check("toggleFavorite can still remove when full",
        HomeModel.toggleFavorite(full, "p1") == full.drop(1))

    check("recent is newest first and excludes favorites",
        HomeModel.recent(listOf("a.one", "c.three", "b.two"), installed, setOf("c.three")).map { it.pkg } == listOf("a.one", "b.two"))
    check("touchRecent moves to front without duplicates",
        HomeModel.touchRecent(listOf("a.one", "b.two"), "b.two") == listOf("b.two", "a.one"))
    check("touchRecent caps at MAX_RECENT",
        HomeModel.touchRecent((1..30).map { "p$it" }, "new").size == HomeModel.MAX_RECENT)

    check("allApps sorts by label ignoring case",
        HomeModel.allApps(installed).map { it.label } == listOf("apple", "Mango", "Zebra"))
    check("allApps shows each package once",
        HomeModel.allApps(installed + installed).size == 3)

    val list = listOf("a.one", "b.two")
    check("encode/decode round-trips a list",
        HomeModel.decodeList(HomeModel.encodeList(list)) == list)
    check("decode of blank or null is empty",
        HomeModel.decodeList(null).isEmpty() && HomeModel.decodeList("  \n \n").isEmpty())
    check("decode trims and drops blank lines and duplicates",
        HomeModel.decodeList(" a.one \n\n a.one \nb.two ") == listOf("a.one", "b.two"))

    check("clock 24h pads hours and minutes", HomeModel.clockText(9, 5, true) == "09:05")
    check("clock 12h: midnight is 12:30 AM", HomeModel.clockText(0, 30, false) == "12:30 AM")
    check("clock 12h: noon is 12:00 PM", HomeModel.clockText(12, 0, false) == "12:00 PM")
    check("clock 12h: 23:59 is 11:59 PM", HomeModel.clockText(23, 59, false) == "11:59 PM")
    check("clock rejects out-of-range time", runCatching { HomeModel.clockText(24, 0, true) }.isFailure)

    println("RESULT: $passed passed, $failed failed, ${passed + failed} total")
    if (failed > 0) kotlin.system.exitProcess(1)
}
