package com.mizan.launcher.data

import android.content.Context
import com.mizan.launcher.core.RemoteProtocol
import com.mizan.launcher.core.Themes
import com.mizan.launcher.core.asObj
import com.mizan.launcher.core.asStr
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom

/**
 * The live-home nudge for remote commands (v3.19). MainActivity
 * sets [onRefresh] in onCreate (posting to its own handler) and
 * clears it in onDestroy; the server calls it after any
 * successful mutating command. When no home is alive the
 * request still lands in prefs (refreshPending) and the next
 * home build consumes it — so a command is never lost just
 * because the home wasn't visible.
 */
object RemoteRefresh {
    @Volatile
    var onRefresh: (() -> Unit)? = null
}

/**
 * The phone-remote listener (v3.19) — a tiny HTTP server on a
 * daemon thread. NOT a Service and NOT a foreground service:
 * it lives only while this process lives (the launcher home
 * process is normally alive), and it dies with the process.
 * That is the honest trade for "no new service": if the
 * process is killed, the phone shows "TV unreachable" until
 * the home restarts and App.onCreate re-arms the listener
 * (when still enabled in prefs).
 *
 * LAN ONLY: the socket binds the device's site-local IPv4
 * address, never 0.0.0.0 — a loopback-only or VPN-only
 * device simply fails to start (lastError = "no_lan") rather
 * than listening somewhere surprising. No port is ever
 * forwarded by this app; reachability from outside the home
 * Wi-Fi would need the user's own router to do it.
 *
 * All command logic lives in core/RemoteProtocol (sim-tested);
 * this file only moves bytes: one POST /v1/command endpoint,
 * 8 KB body cap, 10 s socket timeout, every connection
 * failure contained so a hostile phone can never kill the
 * home process.
 */
object RemoteServer {

    @Volatile
    private var socket: ServerSocket? = null

    @Volatile
    private var thread: Thread? = null

    /** "192.168.x.y:47819" while running, else null. */
    @Volatile
    var boundAddress: String? = null
        private set

    /** Simple-name of the last start failure ("no_lan" when no LAN address exists). */
    @Volatile
    var lastError: String? = null
        private set

    fun isRunning(): Boolean = thread?.isAlive == true

    /** First up, non-loopback, site-local IPv4 address, or null. */
    fun lanAddress(): String? {
        return try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (iface in ifaces) {
                if (!iface.isUp || iface.isLoopback || iface.isVirtual) continue
                for (addr in iface.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress) {
                        return addr.hostAddress
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    /** A fresh 6-digit pairing code (100000–999999). */
    fun generateCode(): String {
        val rnd = SecureRandom()
        return (100000 + rnd.nextInt(900000)).toString()
    }

    /** Idempotent start. Returns false (with [lastError] set) when there is no LAN to bind. */
    @Synchronized
    fun start(ctx: Context): Boolean {
        if (isRunning()) return true
        lastError = null
        val lan = lanAddress()
        if (lan == null) {
            lastError = "no_lan"
            return false
        }
        return try {
            val prefs = Prefs(ctx.applicationContext)
            val srv = ServerSocket(RemoteProtocol.PORT, 50, InetAddress.getByName(lan))
            srv.soTimeout = 0
            socket = srv
            boundAddress = "$lan:${RemoteProtocol.PORT}"
            val t = Thread({
                while (!Thread.currentThread().isInterrupted) {
                    try {
                        val conn = srv.accept()
                        Thread({ handleConnection(conn, prefs) }, "phone-remote-conn").apply {
                            isDaemon = true
                            start()
                        }
                    } catch (e: Exception) {
                        break
                    }
                }
            }, "phone-remote")
            t.isDaemon = true
            t.start()
            thread = t
            true
        } catch (e: Exception) {
            lastError = e.javaClass.simpleName
            try { socket?.close() } catch (ignored: Exception) { }
            socket = null
            boundAddress = null
            false
        }
    }

    @Synchronized
    fun stop() {
        try { socket?.close() } catch (ignored: Exception) { }
        socket = null
        thread?.interrupt()
        thread = null
        boundAddress = null
    }

    private class PrefsStore(private val prefs: Prefs) : RemoteProtocol.Store {
        override var pairingCode: String
            get() = prefs.pairingCode
            set(v) { prefs.pairingCode = v }
        override var style: String
            get() = prefs.homeStyle
            set(v) { prefs.homeStyle = v }
        override var themeId: String
            get() = prefs.themeId
            set(v) { prefs.themeId = v }
        override val themeOptions: List<Pair<String, String>>
            get() = Themes.PRESETS.map { it.id to it.name }
        override var appLockEnabled: Boolean
            get() = prefs.remoteAppLockEnabled
            set(v) { prefs.remoteAppLockEnabled = v }
        override var folderLockEnabled: Boolean
            get() = prefs.remoteFolderLockEnabled
            set(v) { prefs.remoteFolderLockEnabled = v }
        override var arrowPin: String
            get() = prefs.arrowPin
            set(v) { prefs.arrowPin = v }
        override val libraryTitles: Int
            get() = try {
                TrackerClient.memoOrNull()?.tracked?.size ?: 0
            } catch (e: Exception) {
                0
            }
        override var refreshPending: Boolean
            get() = prefs.remoteRefreshPending
            set(v) { prefs.remoteRefreshPending = v }
        override var failureCount: Int
            get() = prefs.remoteFailureCount
            set(v) { prefs.remoteFailureCount = v }
        override var lockoutUntilMs: Long
            get() = prefs.remoteLockoutUntilMs
            set(v) { prefs.remoteLockoutUntilMs = v }
    }

    private fun handleConnection(conn: Socket, prefs: Prefs) {
        try {
            conn.soTimeout = 10_000
            val input = BufferedReader(InputStreamReader(conn.getInputStream(), Charsets.UTF_8))
            val requestLine = input.readLine() ?: return
            var contentLength = 0
            while (true) {
                val line = input.readLine() ?: return
                if (line.isEmpty()) break
                val parts = line.split(":", limit = 2)
                if (parts.size == 2 && parts[0].trim().equals("Content-Length", ignoreCase = true)) {
                    contentLength = parts[1].trim().toIntOrNull()?.coerceIn(0, 8192) ?: 0
                }
            }
            val bodyChars = CharArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = input.read(bodyChars, read, contentLength - read)
                if (n < 0) break
                read += n
            }
            val body = String(bodyChars, 0, read)
            val (status, payload) = if (requestLine.startsWith("POST /v1/command")) {
                val json = RemoteProtocol.handle(body, System.currentTimeMillis(), PrefsStore(prefs))
                val httpStatus = when {
                    json.contains("\"ok\":true") -> 200
                    json.contains("\"locked_out\"") -> 429
                    json.contains("\"bad_code\"") || json.contains("\"missing_code\"") -> 401
                    else -> 400
                }
                if (httpStatus == 200) {
                    val cmd = try {
                        com.mizan.launcher.core.JsonMini.parse(body).asObj()?.get("command").asStr()
                    } catch (e: Exception) {
                        null
                    }
                    if (cmd != null && cmd != "status") {
                        try { RemoteRefresh.onRefresh?.invoke() } catch (ignored: Exception) { }
                    }
                }
                httpStatus to json
            } else {
                404 to "{\"ok\":false,\"error\":\"not_found\"}"
            }
            val bytes = payload.toByteArray(Charsets.UTF_8)
            val out = conn.getOutputStream()
            val head = "HTTP/1.1 $status\r\nContent-Type: application/json\r\n" +
                "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
            out.write(head.toByteArray(Charsets.UTF_8))
            out.write(bytes)
            out.flush()
        } catch (e: Exception) {
            // A bad phone must never take down the home process.
        } finally {
            try { conn.close() } catch (ignored: Exception) { }
        }
    }
}
