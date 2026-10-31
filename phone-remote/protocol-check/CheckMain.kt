import com.mizan.launcherremote.RemoteClient

/**
 * Headless protocol check for the phone client (NOT part of
 * the shipped app — this file lives in protocol-check/ and is
 * compiled standalone with kotlinc against RemoteClient.kt).
 *
 * It pins the exact wire agreement with the TV handler
 * (core/RemoteProtocol, Sreeb v3.19): the port, the request
 * shapes, and the reply shapes — including a golden status
 * reply written in the TV handler's exact output format. If
 * either side drifts, this check fails before any APK ships.
 */
fun check(name: String, cond: Boolean) {
    if (cond) println("PASS  $name") else {
        println("FAIL  $name")
        throw AssertionError(name)
    }
}

fun main() {
    check("port is the documented 47819", RemoteClient.PORT == 47819)
    check("url is built from the typed IP only",
        RemoteClient.url("192.168.1.50") == "http://192.168.1.50:47819/v1/command")

    // ---- request shapes ----
    check("status request shape",
        RemoteClient.requestJson("123456", "status") ==
            "{\"pairing\":\"123456\",\"command\":\"status\"}")
    check("setStyle request shape",
        RemoteClient.requestJson("123456", "setStyle", listOf("style" to "clean")) ==
            "{\"pairing\":\"123456\",\"command\":\"setStyle\",\"style\":\"clean\"}")
    check("setAppLock request encodes a bare boolean",
        RemoteClient.requestJson("123456", "setAppLock", listOf("enabled" to false)) ==
            "{\"pairing\":\"123456\",\"command\":\"setAppLock\",\"enabled\":false}")
    check("setArrowCode request shape",
        RemoteClient.requestJson("123456", "setArrowCode", listOf("arrowCode" to "UDLR")) ==
            "{\"pairing\":\"123456\",\"command\":\"setArrowCode\",\"arrowCode\":\"UDLR\"}")
    check("quotes in values are escaped",
        RemoteClient.requestJson("12\"456", "status").contains("\\\""))

    // ---- golden status reply (TV handler's exact format) ----
    val golden = "{\"ok\":true,\"style\":\"clean\",\"themeId\":\"midnight\"," +
        "\"themeOptions\":[{\"id\":\"apple_black\",\"name\":\"Apple Black\"}," +
        "{\"id\":\"midnight\",\"name\":\"Midnight\"}]," +
        "\"appLock\":true,\"folderLock\":false,\"lockSet\":true,\"libraryTitles\":45}"
    when (val r = RemoteClient.parseResponse(golden)) {
        is RemoteClient.Result.Ok -> {
            check("golden status parses: style/theme/locks/count",
                r.status.style == "clean" && r.status.themeId == "midnight" &&
                    r.status.appLock && !r.status.folderLock &&
                    r.status.lockSet && r.status.libraryTitles == 45)
            check("golden status parses: theme options in order",
                r.status.themeOptions.map { it.id } == listOf("apple_black", "midnight") &&
                    r.status.themeOptions[1].name == "Midnight")
        }
        is RemoteClient.Result.Err -> throw AssertionError("golden status did not parse: ${r.error}")
    }

    // ---- error replies ----
    check("error reply parses to Err with the TV's code",
        RemoteClient.parseResponse("{\"ok\":false,\"error\":\"bad_code\"}") ==
            RemoteClient.Result.Err("bad_code"))
    check("locked_out reply parses",
        RemoteClient.parseResponse("{\"ok\":false,\"error\":\"locked_out\"}") ==
            RemoteClient.Result.Err("locked_out"))
    check("garbage reply is bad_reply, never a throw",
        RemoteClient.parseResponse("{nope") == RemoteClient.Result.Err("bad_reply"))
    check("missing ok flag is bad_reply",
        RemoteClient.parseResponse("{\"style\":\"clean\"}") == RemoteClient.Result.Err("bad_reply"))

    println("RESULT: phone protocol check passed")
}
