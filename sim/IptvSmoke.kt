import com.calm.launcher.core.IptvEpg
import com.calm.launcher.core.IptvLogic
import com.calm.launcher.core.JsonMini

var ok = 0
var bad = 0
fun check(name: String, pass: Boolean) {
    if (pass) { ok++; println("PASS  $name") } else { bad++; println("FAIL  $name") }
}

fun main() {
    val m3u = """
        #EXTM3U
        #EXTINF:-1 tvg-id="news.us" tvg-name="News" group-title="News",Newsroom
        http://example.com/live/1.ts
        #EXTINF:-1 group-title="Sports",Arena
        http://example.com/live/2.ts
    """.trimIndent()
    val (cats, channels) = IptvLogic.parseM3u(m3u)
    check("m3u: two channels parsed", channels.size == 2)
    check("m3u: names and direct urls kept", channels.first().name == "Newsroom" && channels.first().directUrl == "http://example.com/live/1.ts")
    check("m3u: categories derived from group-title", cats.map { it.name }.toSet().containsAll(setOf("News", "Sports")))
    check("m3u: an empty playlist yields nothing", IptvLogic.parseM3u("#EXTM3U\n").second.isEmpty())

    check("server: trailing slash and spaces stripped", IptvLogic.normalizeServer("  http://example.com:8080/  ") == "http://example.com:8080")
    val acc = IptvLogic.Account("http://example.com:8080", "u", "p")
    check("xtream: live url shape", IptvLogic.liveUrl(acc, 42).contains("/live/u/p/42"))

    check("json: parses an object", (JsonMini.parse("{\"a\":[1,2,\"x\"]}") as? Map<*, *>)?.containsKey("a") == true)
    check("json: trailing garbage rejected", JsonMini.parse("{\"a\":1} xx") == null)
    check("json: deep nesting is refused, not a stack overflow", JsonMini.parse("[".repeat(5000) + "]".repeat(5000)) == null)

    val xml = """<tv>
        <programme start="20260101120000 +0000" stop="20260101130000 +0000" channel="news.us">
          <title>Evening &amp; News &#128512;</title>
        </programme></tv>"""
    val epg = IptvEpg.parseXmltv(xml)
    val first = epg["news.us"]?.firstOrNull()
    check("xmltv: programme attached to its channel", first != null)
    check("xmltv: entities and numeric references decoded", first?.title == "Evening & News 😀")

    println("RESULT: $ok passed, $bad failed, ${ok + bad} total")
    if (bad > 0) kotlin.system.exitProcess(1)
}
