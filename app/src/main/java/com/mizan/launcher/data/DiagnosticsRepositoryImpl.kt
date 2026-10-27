package com.mizan.launcher.data

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.mizan.launcher.core.DiagLogs
import com.mizan.launcher.core.DiagScrub
import com.mizan.launcher.core.DiagStartup
import com.mizan.launcher.core.DiagWatchdog
import com.mizan.launcher.domain.DiagnosticsRepository
import com.mizan.launcher.domain.DiagnosticsSnapshot
import com.mizan.launcher.domain.GlobalAction
import com.mizan.launcher.domain.GlobalActionsGateway
import com.mizan.launcher.domain.HomeWatcherProbe
import java.io.File

/**
 * Diagnostics engine (MAP §2): crash log (filesDir/crash.log),
 * ANR log (filesDir/anr.log, once per stall episode), breadcrumb
 * ring (persisted, oldest-first, capped), bounded event log,
 * startup milestones, death evidence, main-thread watchdog.
 * Lines carry counts/state ONLY — never titles, ids, or key
 * material. The app layer installs the crash handler / watchdog
 * and calls DiagLog-equivalent hooks here; this class owns the
 * files and the sanitized snapshot.
 */
class DiagnosticsRepositoryImpl(ctx: Context) : DiagnosticsRepository {
    private val context = ctx.applicationContext
    private val prefs = PrefsStore(ctx)
    private val trackerDisk = TrackerStoreDisk(ctx)
    private val iptvDisk = IptvStoreDisk(ctx)
    private val ring = com.mizan.launcher.core.BreadcrumbRing()

    @Volatile var startupFirstFrameMs: Long? = null
    @Volatile var startupFullMs: Long? = null
    @Volatile var coldStart: Boolean = false
    @Volatile var lastDeathStage: String? = null

    init {
        try {
            ring.restore(File(context.filesDir, "breadcrumbs.txt").takeIf { it.isFile }?.readText())
        } catch (ignored: Throwable) { }
    }

    private fun crashTail(): List<String> {
        val t = DiagLogs.tailLines(
            try {
                File(context.filesDir, "crash.log").takeIf { it.isFile }?.readText() ?: ""
            } catch (e: Throwable) { "" }, 5)
        return if (t.isBlank()) emptyList() else t.split('\n')
    }
    private fun anrTail(): List<String> {
        val t = DiagLogs.tailLines(
            try {
                File(context.filesDir, "anr.log").takeIf { it.isFile }?.readText() ?: ""
            } catch (e: Throwable) { "" }, 5)
        return if (t.isBlank()) emptyList() else t.split('\n')
    }

    override fun snapshot(): DiagnosticsSnapshot {
        val lines = ArrayList<String>()
        val ff = startupFirstFrameMs
        val full = startupFullMs
        if (ff != null && full != null) lines.add(DiagStartup.line(ff, full))
        lines.add("coldStart: $coldStart")
        lines.add(trackerDisk.diagLine())
        val (lc, vc, sc) = iptvDisk.counts()
        lines.add("iptv: live=$lc vod=$vc series=$sc updated=${iptvDisk.lastUpdateMs()}")
        lines.add("home: profile=${prefs.profile()} style=${prefs.homeStyleRaw()} safeMode=${prefs.safeMode()}")
        lines.add("watcher: relaunches=${prefs.watcherRelaunches()} lastAt=${prefs.watcherLastAt()} " +
            "screenOnRecoveries=${prefs.screenOnRecoveries()}")
        val death = lastDeathStage
        if (death != null) lines.add("lastDeath: $death")
        val err = prefs.lastError()
        if (err.isNotBlank()) lines.add("lastError: $err")
        lines.addAll(ring.lines().map { "crumb: $it" })
        lines.addAll(crashTail().map { "crash: $it" })
        lines.addAll(anrTail().map { "anr: $it" })
        return DiagnosticsSnapshot(lines, startupFullMs, coldStart,
            prefs.watcherRelaunches(), prefs.watcherLastAt(),
            prefs.screenOnRecoveries(), lastDeathStage)
    }

    override fun recordBreadcrumb(label: String) {
        val clean = DiagScrub.message(label)
        ring.add(System.currentTimeMillis(), clean)
        try {
            File(context.filesDir, "breadcrumbs.txt").writeText(ring.encode())
        } catch (ignored: Throwable) { }
    }

    override fun noteError(message: String) {
        // Scrubbed before storing; exception messages can embed keys.
        prefs.noteErrorScrubbed(DiagScrub.message(message))
    }

    override fun copyToClipboard(): String {
        val block = snapshot().lines.joinToString("\n")
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("Sreeb diagnostics", block))
        } catch (ignored: Throwable) { }
        return block
    }

    /** Append a crash entry (called by the app layer's handler). */
    fun noteCrash(stage: String, message: String?) {
        try {
            val f = File(context.filesDir, "crash.log")
            val existing = if (f.isFile) f.readText() else ""
            val entry = "${System.currentTimeMillis()} $stage ${DiagScrub.message(message)}"
            f.writeText(DiagLogs.appendEvent(existing, entry, 50))
        } catch (ignored: Throwable) { }
    }
    /** Append an ANR entry, once per stall episode (caller dedupes). */
    fun noteAnr(stackSummary: String) {
        try {
            val f = File(context.filesDir, "anr.log")
            val existing = if (f.isFile) f.readText() else ""
            f.writeText(DiagLogs.appendEvent(existing,
                "${System.currentTimeMillis()} stall ${DiagScrub.message(stackSummary)}", 50))
        } catch (ignored: Throwable) { }
    }
    fun isWatchdogStale(lastBeatMs: Long, nowMs: Long): Boolean =
        DiagWatchdog.isStale(lastBeatMs, nowMs)
}

/**
 * Accessibility gateways (MAP §8.2, domain contracts only).
 * Both services are off by default; NO in-app toggle exists —
 * opt-in lives in system Accessibility settings. The service
 * classes themselves land in app/; these gateways are the
 * data-layer probes the UI/integration layers use, failing open
 * when disconnected. The app layer registers the live
 * GlobalActionsGateway implementation; until then this stub
 * reports disconnected (fail open), which is the safe default.
 */
object GlobalActionsGatewayStub : GlobalActionsGateway {
    @Volatile var delegate: GlobalActionsGateway? = null
    override fun isConnected(): Boolean = delegate?.isConnected() ?: false
    override fun perform(action: GlobalAction): Boolean = delegate?.perform(action) ?: false
}

class HomeWatcherProbeImpl(ctx: Context) : HomeWatcherProbe {
    private val context = ctx.applicationContext
    private val prefs = PrefsStore(ctx)
    override fun isWatcherEnabled(): Boolean {
        return try {
            val flat = android.provider.Settings.Secure.getString(context.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            flat.split(':').any {
                it.contains("WatcherService", ignoreCase = true) ||
                    it.contains("watcher", ignoreCase = true)
            }
        } catch (e: Throwable) { false }
    }
    override fun isDefaultHome(): Boolean {
        return try {
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_HOME)
            val ri = context.packageManager.resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
            ri?.activityInfo?.packageName == context.packageName
        } catch (e: Throwable) { false }
    }
    override fun currentHomePackage(): String? {
        return try {
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_HOME)
            context.packageManager.resolveActivity(intent,
                android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        } catch (e: Throwable) { null }
    }
    override fun relaunchCount(): Int = prefs.watcherRelaunches()
}
