package com.mizan.launcher.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.mizan.launcher.core.Design
import com.mizan.launcher.core.LibraryLogic
import com.mizan.launcher.data.Prefs
import com.mizan.launcher.data.TrackerActions
import com.mizan.launcher.data.TrackerClient
import kotlin.concurrent.thread

/**
 * My Library (v2.1) — every tracked title from TVShowsMoviesTracker
 * in one Netflix-style grid (#141414), fed by the sync bridge
 * snapshots (/tracked + /movies + /continue + /upnext) with the
 * generalized pending overlay applied (core/LibraryLogic). This is
 * the daily-use surface that replaces jumping to the tracker app:
 * OK opens in-launcher details; hold-OK manages the title (mark,
 * mute, remove — all QUEUED writes; the tracker stays the single
 * writer and applies them at its next boot).
 *
 * Posters: /tracked carries none, so a show outside /continue and
 * /upnext starts as a glyph card; on open, the screen quietly
 * fetches the missing posters through the tracker's tmdb_get
 * proxy (details calls, capped) and caches the paths — later opens
 * are instant. Fail-open throughout: no tracker / no key / no
 * network just means glyph cards and the snapshot text.
 */
class LibraryActivity : Activity() {

    private lateinit var prefs: Prefs
    private val accent: Int = Design.NETFLIX_RED

    private var contentBox: LinearLayout? = null
    private var items: List<LibraryLogic.LibItem> = emptyList()
    /** The library key of the card that last held focus (rebuild restore). */
    private var focusedKey: String? = null
    private var firstCard: View? = null
    /** tmdb ids whose poster fill-in is queued/running this session. */
    private val posterFillTried = HashSet<String>()
    @Volatile
    private var posterFillRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Design.NETFLIX_BG)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(Ui.dp(this, 48), Ui.dp(this, 26), Ui.dp(this, 48), Ui.dp(this, 30))
        scroll.addView(box)
        contentBox = box
        setContentView(scroll)
        rebuild()
        TrackerClient.refreshAsync(this) { rebuild() }
    }

    override fun onResume() {
        super.onResume()
        // Returning from Details (writes happened there) or from the
        // tracker: re-read snapshots + overlay and re-render.
        rebuild()
        TrackerClient.refreshAsync(this) { rebuild() }
    }

    // ------------------------------------------------------------ render

    /**
     * v3.0: every Library build runs behind this guard (the
     * IptvActivity rule): an escape is recorded and replaced by a
     * worded line, never a black screen or a dead process.
     */
    private fun rebuild() {
        try {
            rebuildInner()
        } catch (e: Throwable) {
            Prefs.noteError(this, "libraryRebuild", e)
            val box = contentBox ?: return
            try {
                box.removeAllViews()
                val line = TextView(this)
                line.text = "Your library hit a snag — press Back and try again. " +
                    "The error is noted in the launcher's diagnostics."
                line.setTextColor(0xFFB3B3B3.toInt())
                line.textSize = 17f
                box.addView(line)
            } catch (ignored: Throwable) {
                // Leave whatever is on screen rather than crash.
            }
        }
    }

    private fun rebuildInner() {
        val box = contentBox ?: return
        val focusedBefore = focusedKey
        box.removeAllViews()
        firstCard = null

        val snap = TrackerClient.cached(this)
        // Converge the overlay against the freshest snapshot first
        // (the prepareTrackerData discipline): prune, persist if the
        // prune changed anything, THEN render from the pruned set.
        var overlay = LibraryLogic.decodePending(prefs.pendingOverlay)
        val shows = LibraryLogic.parseLibShows(snap.trackedJson)
        val pruned = LibraryLogic.prunePending(overlay, shows, snap.movies, snap.updated)
        if (pruned != overlay) {
            prefs.pendingOverlay = LibraryLogic.encodePending(pruned)
            overlay = pruned
        }
        items = LibraryLogic.buildLibrary(
            shows, snap.movies, snap.cont, snap.upNext, overlay,
            TrackerClient.posterCache(this)
        )

        // Header: title + count, and the TMDB search entry.
        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        val title = TextView(this)
        title.text = "My Library"
        title.setTextColor(0xFFFFFFFF.toInt())
        title.textSize = 28f
        title.setTypeface(title.typeface, Typeface.BOLD)
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        val addBtn = headerButton("＋ Search TMDB to add") {
            startActivity(Intent(this, TmdbSearchActivity::class.java))
        }
        header.addView(addBtn)
        box.addView(header)

        val count = TextView(this)
        count.text = when {
            items.isEmpty() -> ""
            items.size == 1 -> "1 title — OK for details, hold OK to manage"
            else -> "${items.size} titles — OK for details, hold OK to manage"
        }
        count.setTextColor(0xFFB3B3B3.toInt())
        count.textSize = 15f
        val clp = LinearLayout.LayoutParams(-1, -2)
        clp.setMargins(0, Ui.dp(this, 4), 0, Ui.dp(this, 10))
        count.layoutParams = clp
        box.addView(count)

        if (items.isEmpty()) {
            val line = TextView(this)
            line.text = if (TrackerClient.isInstalled(this)) {
                "Nothing tracked yet. Add titles with “Search TMDB to add” " +
                    "above, or in the tracker app."
            } else {
                "TV Shows & Movies Tracker isn't installed — your library lives there. " +
                    "Sreeb reads it from the tracker and queues changes back to it."
            }
            line.setTextColor(0xFFB3B3B3.toInt())
            line.textSize = 17f
            box.addView(line)
            addBtn.post { addBtn.requestFocus() }
            return
        }

        val cols = PosterCards.columns(this, prefs)
        var i = 0
        while (i < items.size) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            for (item in items.subList(i, minOf(i + cols, items.size))) {
                val card = PosterCards.build(
                    activity = this,
                    prefs = prefs,
                    accent = accent,
                    posterPath = item.poster,
                    title = item.title,
                    meta = LibraryLogic.progressLine(item),
                    line2 = when {
                        item.pending -> "Pending"
                        item.muted -> "🔇 Notifications muted"
                        else -> null
                    },
                    glyph = if (item.type == "movie") "🎬" else "📺",
                    tag = item.key(),
                    progress = LibraryLogic.progressFraction(item),
                    onFocus = { focusedKey = item.key() },
                    onClick = { openDetails(item) },
                    onLong = { openItemMenu(item) }
                )
                if (firstCard == null) {
                    // The focusable frame is the column's first child.
                    firstCard = (card as LinearLayout).getChildAt(0)
                }
                row.addView(card)
            }
            box.addView(row)
            i += cols
        }

        // Focus: back to the card that had it, else the first card.
        val target = focusedBefore?.let { key ->
            findFrameByTag(box, key)
        } ?: firstCard
        target?.post { target.requestFocus() }

        fillMissingPosters()
    }

    private fun findFrameByTag(root: View, tag: String): View? {
        if (root.tag == tag) return root
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) {
                findFrameByTag(root.getChildAt(i), tag)?.let { return it }
            }
        }
        return null
    }

    private fun headerButton(text: String, onClick: () -> Unit): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.setTextColor(0xFFFFFFFF.toInt())
        tv.textSize = 16f
        tv.setTypeface(tv.typeface, Typeface.BOLD)
        tv.gravity = Gravity.CENTER
        tv.isFocusable = true
        tv.isClickable = true
        tv.setPadding(Ui.dp(this, 16), Ui.dp(this, 9), Ui.dp(this, 16), Ui.dp(this, 9))
        tv.background = Ui.tileBackground(accent, false, Design.RADIUS_CHIP, this)
        Ui.focusLift(tv, accent, Design.RADIUS_CHIP)
        tv.setOnClickListener { onClick() }
        return tv
    }

    // ------------------------------------------------------------ poster fill-in

    /**
     * Shows outside /continue + /upnext have no snapshot poster
     * (/tracked carries none). Fetch their TMDB details through the
     * tracker proxy — one background pass, capped — cache the poster
     * path, and repaint. Runs at most once per title per session;
     * every failure is silent (the glyph card stays).
     */
    private fun fillMissingPosters() {
        if (posterFillRunning) return
        val missing = items.filter {
            it.poster.isBlank() && it.key() !in posterFillTried
        }.take(40)
        if (missing.isEmpty()) return
        for (item in missing) posterFillTried.add(item.key())
        posterFillRunning = true
        thread(name = "mizan-library-posters") {
            try {
                var learned = false
                for (item in missing) {
                    if (isFinishing || isDestroyed) break
                    val (json, _) = TrackerClient.tmdbGetBlocking(
                        this, LibraryLogic.detailsPath(item.type, item.tmdbId)
                    )
                    val details = LibraryLogic.parseDetails(json, item.type)
                    if (details != null && details.poster.isNotBlank()) {
                        TrackerClient.notePoster(this, item.type, item.tmdbId, details.poster)
                        learned = true
                    }
                    if (details != null && details.backdrop.isNotBlank()) {
                        TrackerClient.noteBackdrop(this, item.type, item.tmdbId, details.backdrop)
                    }
                }
                if (learned && !isFinishing && !isDestroyed) {
                    runOnUiThread { rebuild() }
                }
            } catch (e: Exception) {
                Prefs.noteError(this, "libraryPosterFill", e)
            } finally {
                posterFillRunning = false
            }
        }
    }

    // ------------------------------------------------------------ actions

    private fun openDetails(item: LibraryLogic.LibItem) {
        startActivity(
            TrackerActions.detailsIntent(this, item.tmdbId, item.type, item.title, item.poster)
        )
    }

    /** Hold-OK management menu for one library card (queued writes). */
    private fun openItemMenu(item: LibraryLogic.LibItem) {
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        labels.add("Details")
        actions.add { openDetails(item) }
        if (item.type == "tv" && item.nextSeason > 0) {
            labels.add("Mark S${item.nextSeason} E${item.nextEpisode} watched")
            actions.add {
                TrackerActions.toggleEpisode(
                    this, prefs, item.tmdbId, item.nextSeason, item.nextEpisode,
                    watched = true
                )
                Toast.makeText(
                    this, "Marked watched — applies next time the tracker opens",
                    Toast.LENGTH_SHORT
                ).show()
                rebuild()
            }
        }
        if (item.type == "movie") {
            labels.add(if (item.movieWatched) "Mark unwatched" else "Mark watched")
            actions.add {
                TrackerActions.toggleMovie(this, prefs, item.tmdbId, !item.movieWatched)
                Toast.makeText(
                    this, "Saved — applies next time the tracker opens",
                    Toast.LENGTH_SHORT
                ).show()
                rebuild()
            }
        }
        labels.add(
            if (item.muted) "Unmute notifications" else "Mute notifications"
        )
        actions.add {
            TrackerActions.setMuted(this, prefs, item.tmdbId, item.type, !item.muted)
            Toast.makeText(
                this,
                if (item.muted) "Notifications unmuted" else "Notifications muted",
                Toast.LENGTH_SHORT
            ).show()
            rebuild()
        }
        labels.add("Remove from library…")
        actions.add {
            TrackerActions.confirmRemove(this, prefs, item.tmdbId, item.type, item.title) {
                rebuild()
            }
        }
        labels.add("Open in Tracker")
        actions.add { TrackerActions.openTrackerGated(this, prefs, accent) }
        AlertDialog.Builder(this)
            .setTitle(item.title)
            .setItems(labels.toTypedArray()) { _, which -> actions[which].invoke() }
            .show()
    }
}
