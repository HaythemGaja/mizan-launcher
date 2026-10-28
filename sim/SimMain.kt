import com.calm.launcher.core.AppEntry
import com.calm.launcher.core.ArrowPin
import com.calm.launcher.core.Folder
import com.calm.launcher.core.Folders
import com.calm.launcher.core.HomeModel
import com.calm.launcher.core.Profiles
import com.calm.launcher.core.Wallpapers

var passed = 0
var failed = 0

fun check(name: String, ok: Boolean) {
    if (ok) { passed++; println("PASS  $name") } else { failed++; println("FAIL  $name") }
}

fun app(pkg: String, label: String = pkg) = AppEntry(pkg, label, ".Main", "LEANBACK_LAUNCHER")

fun main() {
    val installed = listOf(app("a.one", "Zebra"), app("b.two", "apple"), app("c.three", "Mango"))

    // --- home rules ---
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
    check("toggleFavorite refuses a 13th favorite", HomeModel.toggleFavorite(full, "new.app") == null)
    check("toggleFavorite can still remove when full", HomeModel.toggleFavorite(full, "p1") == full.drop(1))
    check("recent is newest first and excludes favorites",
        HomeModel.recent(listOf("a.one", "c.three", "b.two"), installed, setOf("c.three")).map { it.pkg } == listOf("a.one", "b.two"))
    check("touchRecent moves to front without duplicates",
        HomeModel.touchRecent(listOf("a.one", "b.two"), "b.two") == listOf("b.two", "a.one"))
    check("touchRecent caps at MAX_RECENT", HomeModel.touchRecent((1..30).map { "p$it" }, "new").size == HomeModel.MAX_RECENT)
    check("allApps sorts by label ignoring case",
        HomeModel.allApps(installed).map { it.label } == listOf("apple", "Mango", "Zebra"))
    check("allApps shows each package once", HomeModel.allApps(installed + installed).size == 3)
    check("list encode/decode round-trips", HomeModel.decodeList(HomeModel.encodeList(listOf("a.one", "b.two"))) == listOf("a.one", "b.two"))
    check("decode of blank or null is empty", HomeModel.decodeList(null).isEmpty() && HomeModel.decodeList("  \n \n").isEmpty())
    check("decode trims and drops blank lines and duplicates",
        HomeModel.decodeList(" a.one \n\n a.one \nb.two ") == listOf("a.one", "b.two"))
    check("clock 24h pads", HomeModel.clockText(9, 5, true) == "09:05")
    check("clock 12h: midnight is 12:30 AM", HomeModel.clockText(0, 30, false) == "12:30 AM")
    check("clock 12h: noon is 12:00 PM", HomeModel.clockText(12, 0, false) == "12:00 PM")
    check("clock rejects out-of-range time", runCatching { HomeModel.clockText(24, 0, true) }.isFailure)

    // --- profiles ---
    check("profiles decode: blank or garbage falls back to the default profile",
        Profiles.decode(null) == listOf(1) && Profiles.decode("x,,9") == listOf(1))
    check("profiles decode: keeps valid ids sorted and distinct", Profiles.decode("3,1,3") == listOf(1, 3))
    check("profiles add picks the lowest free id", Profiles.add(listOf(1, 3)) == 2)
    check("profiles add refuses past MAX", Profiles.add((1..Profiles.MAX).toList()) == null)
    check("profiles remove refuses the last profile", Profiles.remove(listOf(1), 1) == null)
    check("profiles remove drops one profile", Profiles.remove(listOf(1, 2), 1) == listOf(2))
    check("profiles resolve falls back when the stored current is gone",
        Profiles.resolveCurrent(3, listOf(1, 2)) == 1 && Profiles.resolveCurrent(2, listOf(1, 2)) == 2)
    check("profile keys are scoped", Profiles.key(2, "favorites") == "p2.favorites")

    // --- folders ---
    val f1 = Folder("f1", "Games", listOf("a.one"))
    val f2 = Folder("f2", "Video", listOf("b.two"))
    check("folder codec round-trips",
        Folders.decode(Folders.encode(listOf(f1, f2))) == listOf(f1, f2))
    check("folder codec skips bad lines and keeps good ones",
        Folders.decode("garbage\nf9\tName\tx.app\nf1\t\tx\n").map { it.id } == listOf("f9"))
    check("folder names cannot break the codec", Folders.cleanName("a\tb\nc") == "a b c")
    check("folder names are capped", Folders.cleanName("x".repeat(60)).length == Folders.MAX_NAME)
    check("moveInto takes the app out of its old folder",
        Folders.moveInto(listOf(f1, f2), "a.one", "f2").map { it.pkgs } == listOf(emptyList(), listOf("b.two", "a.one")))
    check("removeFromAll clears an app everywhere",
        Folders.removeFromAll(listOf(f1, f2), "b.two").map { it.pkgs } == listOf(listOf("a.one"), emptyList()))
    check("new folder ids are unused", Folders.newId(listOf(f1, f2)) == "f3")
    check("delete removes one folder", Folders.delete(listOf(f1, f2), "f1") == listOf(f2))
    check("rename keeps the old name when given blank",
        Folders.rename(listOf(f1), "f1", "   ").first().name == "Games")
    check("visible hides empty folders and uninstalled apps",
        Folders.visible(listOf(f1, Folder("f3", "Gone", listOf("x")), f2), setOf("b.two")).map { it.id } == listOf("f2"))

    // --- arrow lock ---
    check("arrow pattern must be 3-8 directions", ArrowPin.isValid("UDL") && !ArrowPin.isValid("UD") && !ArrowPin.isValid("U".repeat(9)))
    check("arrow pattern rejects other letters", !ArrowPin.isValid("UDX"))
    check("arrow pattern matches exactly", ArrowPin.matches("UUDL", "UUDL") && !ArrowPin.matches("UUDL", "UUD"))
    check("no pattern set means every attempt passes", ArrowPin.matches("", "anything") && !ArrowPin.hasPattern(""))

    // --- wallpapers ---
    check("wallpaper index is clamped into range",
        Wallpapers.presetAt("99") == Wallpapers.PRESETS.last() && Wallpapers.presetAt("junk") == Wallpapers.PRESETS.first())

    println("RESULT: $passed passed, $failed failed, ${passed + failed} total")
    if (failed > 0) kotlin.system.exitProcess(1)
}
