package com.mizan.launcher.ui

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.mizan.launcher.core.Design
import com.mizan.launcher.core.LibraryLogic
import com.mizan.launcher.core.TrackerLogic
import com.mizan.launcher.data.Prefs
import com.mizan.launcher.data.TrackerActions
import com.mizan.launcher.data.TrackerClient

/**
 * Search TMDB (v2.1) — find a show or movie on TMDB and add it to
 * the library, without opening the tracker app. The query runs
 * through the tracker's `tmdb_get` proxy (search/multi; the
 * tracker's own key — it never leaves the tracker). One search per
 * Search-key press (deliberately NOT per keystroke: each query is a
 * proxied network call). Results are poster cards; OK queues an
 * add_title action and the title appears in My Library at once as
 * "Pending" (the generalized overlay), joining for real when the
 * tracker applies the queue at its next boot — the tracker adds
 * it as planned, per contract.
 *
 * The keyboard + keyboard↔results focus handoff mirror the app
 * Search screen. Errors use the same tmdb_get state lines as the
 * details screen (no_key / http_ / io / bad_path).
 */
class TmdbSearchActivity : Activity() {

    private lateinit var prefs: Prefs
    private val accent: Int = Design.NETFLIX_RED

    private var query: String = ""
    private var queryView: TextView? = null
    private var statusView: TextView? = null
    private var resultsBox: LinearLayout? = null

    private var hits: List<LibraryLogic.SearchHit> = emptyList()
    private var searching = false
    /** Keys already in the library (snapshot) or added this session. */
    private val addedKeys = HashSet<String>()

    private var keyboardRows: List<List<View>> = emptyList()
    private var resultRows: List<List<View>> = emptyList()
    private var lastKey: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        refreshAddedKeys()
        setContentView(buildRoot())
        keyboardRows.firstOrNull()?.firstOrNull()?.post {
            keyboardRows.firstOrNull()?.firstOrNull()?.requestFocus()
        }
        TrackerClient.refreshAsync(this) { refreshAddedKeys() }
    }

    override fun onResume() {
        super.onResume()
        refreshAddedKeys()
        if (hits.isNotEmpty()) rebuildResults()
    }

    /** Snapshot-tracked + overlay-added title keys ("type|id"). */
    private fun refreshAddedKeys() {
        val snap = TrackerClient.cached(this)
        for (show in snap.tracked) addedKeys.add("tv|${show.tmdbId}")
        for (movie in snap.movies) addedKeys.add("movie|${movie.tmdbId}")
        for (entry in LibraryLogic.decodePending(prefs.pendingOverlay)) {
            if (entry.addedTitle != null && !entry.removed) {
                addedKeys.add(entry.key())
            }
        }
        for (entry in TrackerLogic.decodeForYouOverlay(prefs.forYouOverlay)) {
            if (entry.added) addedKeys.add("${entry.type}|${entry.id}")
        }
    }

    // ------------------------------------------------------------ focus handoff

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val focus = currentFocus
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    val bottomRow = keyboardRows.lastOrNull().orEmpty()
                    if (focus != null && focus in bottomRow) {
                        val first = resultRows.firstOrNull()?.firstOrNull()
                        if (first != null) { first.requestFocus(); return true }
                    }
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    val firstRow = resultRows.firstOrNull().orEmpty()
                    if (focus != null && focus in firstRow) {
                        val key = lastKey ?: keyboardRows.lastOrNull()?.firstOrNull()
                        if (key != null) { key.requestFocus(); return true }
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ------------------------------------------------------------ layout

    private fun buildRoot(): View {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Design.NETFLIX_BG)
        root.setPadding(Ui.dp(this, 48), Ui.dp(this, 26), Ui.dp(this, 48), Ui.dp(this, 30))

        val title = TextView(this)
        title.text = "Search TMDB — add to your library"
        title.setTextColor(0xFFFFFFFF.toInt())
        title.textSize = 26f
        title.setTypeface(title.typeface, android.graphics.Typeface.BOLD)
        root.addView(title)

        queryView = TextView(this)
        queryView!!.setTextColor(0xFFFFFFFF.toInt())
        queryView!!.textSize = 23f
        queryView!!.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 10))
        root.addView(queryView)
        updateQueryLabel()

        root.addView(buildKeyboard())

        statusView = TextView(this)
        statusView!!.setTextColor(0xFFB3B3B3.toInt())
        statusView!!.textSize = 16f
        statusView!!.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 4))
        statusView!!.text = "Type a title, then press Search."
        root.addView(statusView)

        val scroll = ScrollView(this)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        scroll.addView(box)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        resultsBox = box
        return root
    }

    private fun updateQueryLabel() {
        queryView?.text = if (query.isEmpty()) "Type to search TMDB…" else "Search: $query"
        queryView?.setTextColor(if (query.isEmpty()) 0xFF8C8C8C.toInt() else 0xFFFFFFFF.toInt())
    }

    private fun buildKeyboard(): LinearLayout {
        val kb = LinearLayout(this)
        kb.orientation = LinearLayout.VERTICAL
        val rows = ArrayList<List<View>>()
        for (rowText in listOf("ABCDEFGHIJ", "KLMNOPQRST", "UVWXYZ", "0123456789")) {
            val rowView = LinearLayout(this)
            rowView.orientation = LinearLayout.HORIZONTAL
            val keys = ArrayList<View>()
            for (ch in rowText) {
                val key = keyButton(ch.toString()) {
                    query += ch
                    updateQueryLabel()
                }
                keys.add(key)
                rowView.addView(key)
            }
            rows.add(keys)
            kb.addView(rowView)
        }
        val special = LinearLayout(this)
        special.orientation = LinearLayout.HORIZONTAL
        val search = keyButton("🔎 Search") { runSearch() }
        val space = keyButton("␣ Space") { query += ' '; updateQueryLabel() }
        val back = keyButton("⌫") {
            if (query.isNotEmpty()) {
                query = query.dropLast(1)
                updateQueryLabel()
            }
        }
        val clear = keyButton("Clear") {
            if (query.isNotEmpty()) {
                query = ""
                updateQueryLabel()
            }
        }
        val specialKeys = listOf<View>(search, space, back, clear)
        for (k in specialKeys) special.addView(k)
        rows.add(specialKeys)
        kb.addView(special)
        keyboardRows = rows
        return kb
    }

    private fun keyButton(label: String, onPress: () -> Unit): TextView {
        val tv = TextView(this)
        tv.text = label
        tv.setTextColor(0xFFFFFFFF.toInt())
        tv.textSize = 19f
        tv.gravity = Gravity.CENTER
        tv.isFocusable = true
        tv.isClickable = true
        tv.setPadding(Ui.dp(this, 15), Ui.dp(this, 8), Ui.dp(this, 15), Ui.dp(this, 8))
        val lp = LinearLayout.LayoutParams(-2, -2)
        lp.setMargins(Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3))
        tv.layoutParams = lp
        tv.background = Ui.tileBackground(accent, false, 10, this)
        Ui.focusLift(tv, accent, 10) { hasFocus -> if (hasFocus) lastKey = tv }
        tv.setOnClickListener { onPress() }
        return tv
    }

    // ------------------------------------------------------------ search + results

    private fun runSearch() {
        val q = query.trim()
        if (q.isEmpty()) {
            statusView?.text = "Type a title first, then press Search."
            return
        }
        if (searching) return
        searching = true
        statusView?.text = "Searching TMDB…"
        TrackerClient.tmdbGet(this, LibraryLogic.searchPath(q)) { json, error ->
            if (isFinishing || isDestroyed) return@tmdbGet
            searching = false
            if (error != null) {
                hits = emptyList()
                statusView?.text = LibraryLogic.tmdbUserLine(error)
                rebuildResults()
                return@tmdbGet
            }
            hits = LibraryLogic.parseSearch(json)
            statusView?.text = when {
                hits.isEmpty() -> "No shows or movies matched “$q”."
                hits.size == 1 -> "1 result — OK adds it to your library."
                else -> "${hits.size} results — OK adds a title to your library."
            }
            rebuildResults()
        }
    }

    private fun rebuildResults() {
        val box = resultsBox ?: return
        box.removeAllViews()
        val rows = ArrayList<List<View>>()
        if (hits.isEmpty()) {
            resultRows = rows
            return
        }
        val cols = PosterCards.columns(this, prefs)
        var i = 0
        while (i < hits.size) {
            val rowView = LinearLayout(this)
            rowView.orientation = LinearLayout.HORIZONTAL
            val frames = ArrayList<View>()
            for (hit in hits.subList(i, minOf(i + cols, hits.size))) {
                val key = "${hit.type}|${hit.id}"
                val added = key in addedKeys
                val metaParts = ArrayList<String>()
                TrackerLogic.ratingText(hit.voteAverage)?.let { metaParts.add(it) }
                if (hit.year.isNotBlank()) metaParts.add(hit.year)
                metaParts.add(if (hit.type == "movie") "Movie" else "TV")
                val card = PosterCards.build(
                    activity = this,
                    prefs = prefs,
                    accent = accent,
                    posterPath = hit.poster,
                    title = hit.title,
                    meta = metaParts.joinToString(" · "),
                    line2 = if (added) "✓ In your library" else null,
                    glyph = if (hit.type == "movie") "🎬" else "📺",
                    tag = key,
                    onClick = { onHitClicked(hit) },
                    onLong = null
                )
                frames.add((card as LinearLayout).getChildAt(0))
                rowView.addView(card)
            }
            rows.add(frames)
            box.addView(rowView)
            i += cols
        }
        resultRows = rows
    }

    private fun onHitClicked(hit: LibraryLogic.SearchHit) {
        val key = "${hit.type}|${hit.id}"
        if (key in addedKeys) {
            Toast.makeText(this, "“${hit.title}” is already in your library", Toast.LENGTH_SHORT)
                .show()
            return
        }
        // TMDB search carries no full release date — "" per contract;
        // the tracker fills richer data from its own fetch at drain.
        TrackerActions.addTitle(this, prefs, hit.id, hit.type, hit.title, hit.poster, "")
        addedKeys.add(key)
        if (hit.poster.isNotBlank()) {
            TrackerClient.notePoster(this, hit.type, hit.id, hit.poster)
        }
        Toast.makeText(
            this,
            "Added “${hit.title}” — it joins your library when the tracker next opens",
            Toast.LENGTH_SHORT
        ).show()
        rebuildResults()
    }
}
