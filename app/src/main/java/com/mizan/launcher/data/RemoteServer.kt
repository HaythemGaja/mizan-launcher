package com.mizan.launcher.data

import android.content.Context
import com.mizan.launcher.core.RemoteProtocol
import com.mizan.launcher.core.Themes
import com.mizan.launcher.core.asObj
import com.mizan.launcher.core.asStr
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import com.mizan.launcher.core.RemoteHttp
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.RejectedExecutionException

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

    private val commandLock = Any()
    private val connections = java.util.Collections.synchronizedSet(HashSet<Socket>())
    private var workers: ThreadPoolExecutor? = null

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
            val executor = ThreadPoolExecutor(2, 2, 30L, TimeUnit.SECONDS,
                ArrayBlockingQueue<Runnable>(4),
                { task -> Thread(task, "phone-remote-conn").apply { isDaemon = true } })
            workers = executor
            val t = Thread({
                while (!Thread.currentThread().isInterrupted) {
                    try {
                        val conn = srv.accept()
                        connections.add(conn)
                        try {
                            executor.execute {
                                try { handleConnection(conn, prefs) }
                                finally { connections.remove(conn) }
                            }
                        } catch (e: RejectedExecutionException) {
                            connections.remove(conn)
                            conn.close()
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
            workers?.shutdownNow()
            workers = null
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
        workers?.shutdownNow()
        workers = null
        synchronized(connections) {
            connections.forEach { try { it.close() } catch (ignored: Exception) { } }
            connections.clear()
        }
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
            val (status, payload) = try {
                val body = RemoteHttp.readBody(conn.getInputStream())
                // Serialize the read/modify/write lockout counters across phones.
                val json = synchronized(commandLock) {
                    if (!prefs.remoteEnabled) "{\"ok\":false,\"error\":\"disabled\"}"
                    else RemoteProtocol.handle(body, System.currentTimeMillis(), PrefsStore(prefs))
                }
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
            } catch (e: RemoteHttp.Invalid) {
                e.status to "{\"ok\":false,\"error\":\"${e.error}\"}"
            }
            val bytes = payload.toByteArray(Charsets.UTF_8)
            val out = conn.getOutputStream()
            val reason = when (status) {
                200 -> "OK"
                400 -> "Bad Request"
                401 -> "Unauthorized"
                404 -> "Not Found"
                411 -> "Length Required"
                413 -> "Content Too Large"
                429 -> "Too Many Requests"
                431 -> "Request Header Fields Too Large"
                else -> "Error"
            }
            val head = "HTTP/1.1 $status $reason\r\nContent-Type: application/json; charset=utf-8\r\nCache-Control: no-store\r\n" +
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
