package com.calm.launcher.data

import android.os.Handler
import android.os.Looper
import com.calm.launcher.core.IptvEpg
import com.calm.launcher.core.IptvLogic
import java.net.HttpURLConnection
import java.net.URL

/**
 * Process-wide guide cache shared by Live TV and the player. The XMLTV guide is
 * fetched at most once per [TTL_MS]; callers get whatever is cached immediately
 * and are told when a fresh copy lands.
 */
object EpgCache {
    private const val TTL_MS = 30 * 60 * 1000L
    private const val MAX_BYTES = 48 * 1024 * 1024
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var guide: Map<String, List<IptvEpg.Programme>> = emptyMap()
    @Volatile private var loadedAt = 0L
    @Volatile private var loading = false

    /** Current guide (maybe empty), refreshing in the background when stale. */
    fun get(account: IptvLogic.Account, onUpdated: (Map<String, List<IptvEpg.Programme>>) -> Unit): Map<String, List<IptvEpg.Programme>> {
        val stale = System.currentTimeMillis() - loadedAt > TTL_MS
        if ((stale || guide.isEmpty()) && !loading && account.isConfigured) {
            loading = true
            Thread {
                val fresh = fetch(account)
                if (fresh != null) {
                    guide = IptvEpg.parseXmltv(fresh)
                    loadedAt = System.currentTimeMillis()
                }
                loading = false
                main.post { onUpdated(guide) }
            }.start()
        }
        return guide
    }

    /** Forget the guide, e.g. after the account changes. */
    fun clear() {
        guide = emptyMap()
        loadedAt = 0L
    }

    private fun fetch(account: IptvLogic.Account): String? = try {
        val conn = URL(IptvLogic.xmltvUrl(account)).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 30_000
        if (conn.responseCode !in 200..299) null else conn.inputStream.use { s ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_BYTES) return@use null
                out.write(buf, 0, n)
            }
            out.toString(Charsets.UTF_8.name())
        }
    } catch (e: Exception) {
        null
    }
}
