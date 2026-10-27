package com.mizan.launcher.data

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Small fail-open HTTP helper. Never logs URLs (they may embed credentials). */
object HttpUtil {
    const val CONNECT_TIMEOUT_MS = 15_000
    const val READ_TIMEOUT_MS = 30_000

    data class Response(val code: Int, val body: String?)

    fun get(url: String, accept: String? = null): Response {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.setRequestProperty("User-Agent", "Sreeb/3.9")
            if (accept != null) conn.setRequestProperty("Accept", accept)
            conn.instanceFollowRedirects = true
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            var body: String? = null
            if (stream != null) {
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { r ->
                    val sb = StringBuilder()
                    val buf = CharArray(8192)
                    var total = 0
                    while (true) {
                        val n = r.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > 4_000_000) break
                        sb.append(buf, 0, n)
                    }
                    body = sb.toString()
                }
            }
            try { conn.disconnect() } catch (ignored: Throwable) { }
            Response(code, body)
        } catch (e: Throwable) {
            Response(-1, null)
        }
    }

    fun enc(s: String): String = try {
        URLEncoder.encode(s, "UTF-8")
    } catch (e: Throwable) { s }

    /** Stream a URL line-by-line without holding the whole body. Returns lines read. */
    fun forEachLine(url: String, onLine: (String) -> Unit): Int {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.setRequestProperty("User-Agent", "Sreeb/3.9")
            val code = conn.responseCode
            if (code !in 200..299) {
                try { conn.disconnect() } catch (ignored: Throwable) { }
                return -1
            }
            var count = 0
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { r ->
                var line: String?
                while (r.readLine().also { line = it } != null) {
                    onLine(line!!)
                    count++
                }
            }
            try { conn.disconnect() } catch (ignored: Throwable) { }
            count
        } catch (e: Throwable) {
            -1
        }
    }
}
