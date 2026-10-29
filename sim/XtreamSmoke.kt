import com.calm.launcher.core.IptvLogic
import com.calm.launcher.core.JsonMini
import com.calm.launcher.core.XtreamParse

var ok = 0
var bad = 0
fun check(name: String, pass: Boolean) {
    if (pass) { ok++; println("PASS  $name") } else { bad++; println("FAIL  $name") }
}

fun main() {
    val cats = XtreamParse.liveCategories(JsonMini.parse("""[{"category_id":"5","category_name":"News"},{"category_id":6,"category_name":"Sport"},{"category_name":"no id"}]"""))
    check("categories: numeric and string ids both read as text", cats.map { it.id } == listOf("5", "6"))
    check("categories: a row without an id is skipped", cats.size == 2)

    val names = cats.associate { it.id to it.name }
    val streams = XtreamParse.liveStreams(
        JsonMini.parse("""[
            {"stream_id":101,"name":"BBC One","stream_icon":"http://x/i.png","epg_channel_id":"bbc1","category_id":"5","tv_archive":1,"tv_archive_duration":7},
            {"stream_id":"102","name":"  Arena  ","category_id":6},
            {"stream_id":103,"name":""},
            {"name":"no id"}
        ]"""),
        names
    ) { id -> "http://example.com/live/u/p/$id.m3u8" }
    check("streams: rows without a name or id are skipped", streams.map { it.id } == listOf(101L, 102L))
    check("streams: string stream ids work", streams[1].id == 102L && streams[1].name == "Arena")
    check("streams: category name is looked up", streams[0].categoryName == "News" && streams[1].categoryName == "Sport")
    check("streams: archive flags read", streams[0].tvArchive == 1 && streams[0].archiveDays == 7 && streams[0].hasArchive)
    check("streams: playback url built from the id", streams[0].directUrl.endsWith("/101.m3u8"))
    check("streams: unknown category falls back to Other", XtreamParse.liveStreams(JsonMini.parse("""[{"stream_id":1,"name":"X","category_id":"99"}]"""), names) { "u" }[0].categoryName == "Other")
    check("streams: a non-list response is empty, not a crash", XtreamParse.liveStreams(JsonMini.parse("{\"error\":1}"), names) { "u" }.isEmpty())

    println("RESULT: $ok passed, $bad failed, ${ok + bad} total")
    if (bad > 0) kotlin.system.exitProcess(1)
}
