package com.calm.launcher.data

import com.calm.launcher.core.IptvLogic
import com.calm.launcher.core.JsonMini
import com.calm.launcher.core.XtreamParse
import java.net.HttpURLConnection
import java.net.URL

/**
 * Network side of Xtream Codes. Blocking calls: run them off the main thread.
 * Every failure returns an empty result, so a bad account never crashes the home screen.
 */
object XtreamClient {

    private const val MAX_BYTES = 64 * 1024 * 1024

    fun account(server: String, user: String, pass: String) =
        IptvLogic.Account(IptvLogic.normalizeServer(server), user.trim(), pass.trim())

    /** Live categories and channels, or empty lists when the server does not answer. */
    fun loadLive(account: IptvLogic.Account): Pair<List<IptvLogic.Category>, List<IptvLogic.LiveChannel>> {
        if (!account.isConfigured) return emptyList<IptvLogic.Category>() to emptyList()
        val cats = XtreamParse.liveCategories(JsonMini.parse(get(IptvLogic.apiUrl(account, "get_live_categories"))))
        val names = cats.associate { it.id to it.name }
        val streams = XtreamParse.liveStreams(
            JsonMini.parse(get(IptvLogic.apiUrl(account, "get_live_streams"))),
            names
        ) { id -> IptvLogic.liveUrl(account, id) }
        return cats to streams
    }

    /** True when the server accepts the login; used by Settings before saving. */
    fun checkLogin(account: IptvLogic.Account): Boolean {
        val json = JsonMini.parse(get(IptvLogic.apiUrl(account, null))) as? Map<*, *> ?: return false
        val info = json["user_info"] as? Map<*, *> ?: return false
        return info["auth"]?.toString()?.startsWith("1") == true
    }

    private fun get(url: String): String? {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("User-Agent", "CalmLauncher/0.3")
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.use { stream ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(32 * 1024)
                var total = 0
                while (true) {
                    val n = stream.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_BYTES) return null
                    out.write(buf, 0, n)
                }
                out.toString(Charsets.UTF_8.name())
            }
        } catch (e: Exception) {
            null
        }
    }
}
