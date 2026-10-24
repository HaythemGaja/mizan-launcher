package com.mizan.launcher.ui

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.mizan.launcher.core.AppSearch
import com.mizan.launcher.core.ArtworkStore
import com.mizan.launcher.core.FolderLogic
import com.mizan.launcher.core.Themes
import com.mizan.launcher.core.TileLayout
import com.mizan.launcher.core.TrackerLogic
import com.mizan.launcher.data.AppEntry
import com.mizan.launcher.data.AppLoader
import com.mizan.launcher.data.ArtworkImages
import com.mizan.launcher.data.Prefs
import com.mizan.launcher.data.TrackerClient

/**
 * App Search screen (v1.4) — opened from the status-bar Search tile or
 * the remote's Search key (KEYCODE_SEARCH on the home screen).
 *
 * Query on top, an on-screen D-pad keyboard under it (letter/number
 * rows, then Space / ⌫ / Clear), and the results grid below, updating
 * live. The result set is the same visible set as All Apps (installed
 * − hidden − in-any-folder) filtered by core/AppSearch (substring,
 * prefix matches first). Tiles are the home screen's uniform tiles
 * with the focus-name overlay.
 *
 * Navigation: arrows move inside the keyboard by normal focus search;
 * DOWN from the keyboard's bottom row drops into the results; UP from
 * the results' first row returns to the keyboard; BACK closes. OK on
 * a result launches it through the same arrow-lock gate as a home
 * launch, and the launch is recorded in Recently Used.
 */
class SearchActivity : Activity() {

    private companion object {
        const val REQ_VOICE = 9417
    }

    private lateinit var prefs: Prefs
    private var accent: Int = 0
    private var textColour: Int = 0
    private var subColour: Int = 0

    private var query: String = ""
    /** All visible apps (the All Apps set), label-sorted by AppLoader. */
    private var visibleApps: List<AppEntry> = emptyList()
    private var byPkg: Map<String, AppEntry> = emptyMap()

    private var queryView: TextView? = null
    private var resultsBox: LinearLayout? = null
    private var keyboardHost: LinearLayout? = null

    /** v2.0 voice search: null = not yet probed this session. */
    private var voiceAvailable: Boolean? = null
    /** v2.0: this profile's custom artwork, parsed per results rebuild. */
    private var artworkByPkg: Map<String, ArtworkStore.Entry> = emptyMap()

    /** Keyboard rows (for the DOWN-into-results handoff). */
    private var keyboardRows: List<List<View>> = emptyList()
    /** Results grid rows (for the UP-back-to-keyboard handoff). */
    private var resultRows: List<List<View>> = emptyList()
    /** Last keyboard key focused — UP from results returns here. */
    private var lastKey: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val preset = Themes.preset(prefs.themeId)
        textColour = preset.text; subColour = preset.subText
        accent = if (preset.dynamic && prefs.accentOverride != 0) prefs.accentOverride else preset.accent

        val layout = prefs.layout
        val all = AppLoader.loadApps(packageManager, packageName)
        byPkg = all.associateBy { it.pkg }
        val visiblePkgs = FolderLogic.allAppsVisible(
            all.map { it.pkg }, layout.hidden.toSet(), layout.folders
        ).toSet()
        visibleApps = all.filter { it.pkg in visiblePkgs }

        // v3.0: a root build failure renders the worded fallback,
        // never a black screen (the guarded-rebuilds rule).
        try {
            setContentView(buildRoot(preset.bgTop, preset.bgBottom))
        } catch (e: Throwable) {
            Prefs.noteError(this, "searchRoot", e)
            val line = TextView(this)
            line.text = "Search hit a snag — press Back and try again."
            line.setTextColor(0xFFFFFFFF.toInt())
            line.textSize = 17f
            line.setPadding(Ui.dp(this, 48), Ui.dp(this, 48), Ui.dp(this, 48), Ui.dp(this, 48))
            setContentView(line)
        }
        keyboardRows.firstOrNull()?.firstOrNull()?.post {
            keyboardRows.firstOrNull()?.firstOrNull()?.requestFocus()
        }
        // v1.8: warm the tracker snapshot cache (read-only, fail-open);
        // the "In your tracker" section reads the cache per keystroke.
        TrackerClient.refreshAsync(this) {}
    }

    /**
     * The keyboard ↔ results focus handoff (see class doc). Only the
     * two boundary crossings are intercepted; everything else is the
     * framework's geometric focus search.
     */
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

    // ------------------------------------------------------------------ UI

    private fun buildRoot(bgTop: Int, bgBottom: Int): View {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(bgTop, bgBottom)
        )
        root.setPadding(Ui.dp(this, 48), Ui.dp(this, 26), Ui.dp(this, 48), Ui.dp(this, 30))

        val title = TextView(this)
        title.text = "Search apps"
        title.setTextColor(accent); title.textSize = 28f
        title.setTypeface(title.typeface, android.graphics.Typeface.BOLD)
        root.addView(title)

        queryView = TextView(this)
        queryView!!.setTextColor(textColour); queryView!!.textSize = 24f
        queryView!!.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 12))
        root.addView(queryView)
        updateQueryLabel()

        val kbHost = LinearLayout(this)
        kbHost.orientation = LinearLayout.VERTICAL
        kbHost.addView(buildKeyboard())
        keyboardHost = kbHost
        root.addView(kbHost)

        val scroll = ScrollView(this)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(0, Ui.dp(this, 14), 0, 0)
        scroll.addView(box)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        resultsBox = box
        rebuildResults()
        return root
    }

    private fun updateQueryLabel() {
        queryView?.text = if (query.isEmpty()) "Type to search…" else "Search: $query"
        queryView?.setTextColor(if (query.isEmpty()) subColour else textColour)
    }

    private fun buildKeyboard(): LinearLayout {
        val kb = LinearLayout(this)
        kb.orientation = LinearLayout.VERTICAL
        val rows = mutableListOf<List<View>>()
        for (rowText in listOf("ABCDEFGHIJ", "KLMNOPQRST", "UVWXYZ", "0123456789")) {
            val rowView = LinearLayout(this)
            rowView.orientation = LinearLayout.HORIZONTAL
            val keys = mutableListOf<View>()
            for (ch in rowText) {
                val key = keyButton(ch.toString()) { typeChar(ch) }
                keys.add(key)
                rowView.addView(key)
            }
            rows.add(keys)
            kb.addView(rowView)
        }
        // Bottom row: [Voice] / Space / Backspace / Clear.
        val special = LinearLayout(this)
        special.orientation = LinearLayout.HORIZONTAL
        val specialKeys = mutableListOf<View>()
        // v2.0 voice search: the mic key exists only while a speech
        // recognizer resolves on this device (probed once per build;
        // a failed launch hides it for the rest of the session).
        if (voiceAvailable == null) {
            voiceAvailable = try {
                android.content.Intent(
                    android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH
                ).resolveActivity(packageManager) != null
            } catch (e: Exception) {
                false
            }
        }
        if (voiceAvailable == true) {
            val mic = keyButton("🎤 Voice") { startVoiceSearch() }
            specialKeys.add(mic)
            special.addView(mic)
        }
        val space = keyButton("␣ Space") { typeChar(' ') }
        val back = keyButton("⌫") {
            if (query.isNotEmpty()) { query = query.dropLast(1); onQueryChanged() }
        }
        val clear = keyButton("Clear") {
            if (query.isNotEmpty()) { query = ""; onQueryChanged() }
        }
        for (k in listOf(space, back, clear)) {
            specialKeys.add(k)
            special.addView(k)
        }
        rows.add(specialKeys)
        kb.addView(special)
        keyboardRows = rows
        return kb
    }

    /**
     * v2.0 voice search: hand recognition to the system's recognizer
     * activity (no permission of ours — it is another app's screen).
     * The top result becomes the query and the existing search runs
     * (apps + "In your tracker"). No recognizer / a refused launch →
     * toast + the mic key hides for the session.
     */
    private fun startVoiceSearch() {
        val intent = android.content.Intent(
            android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH
        )
            .putExtra(
                android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            .putExtra(
                android.speech.RecognizerIntent.EXTRA_PROMPT,
                "Search apps and your tracker"
            )
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_VOICE)
        } catch (e: Exception) {
            Prefs.noteError(this, "voiceSearch", e)
            voiceAvailable = false
            Toast.makeText(
                this, "Voice search isn't available on this TV", Toast.LENGTH_SHORT
            ).show()
            keyboardHost?.let { host ->
                host.removeAllViews()
                host.addView(buildKeyboard())
            }
        }
    }

    @Deprecated("Activity result routing for the recognizer")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_VOICE || resultCode != RESULT_OK) return
        val spoken = try {
            data?.getStringArrayListExtra(
                android.speech.RecognizerIntent.EXTRA_RESULTS
            )?.firstOrNull()
        } catch (e: Exception) {
            null
        }
        if (!spoken.isNullOrBlank()) {
            query = spoken.trim()
            onQueryChanged()
        }
    }

    private fun keyButton(label: String, onPress: () -> Unit): TextView {
        val tv = TextView(this)
        tv.text = label
        tv.setTextColor(textColour); tv.textSize = 20f
        tv.gravity = Gravity.CENTER
        tv.isFocusable = true; tv.isClickable = true
        tv.setPadding(Ui.dp(this, 16), Ui.dp(this, 9), Ui.dp(this, 16), Ui.dp(this, 9))
        val lp = LinearLayout.LayoutParams(-2, -2)
        lp.setMargins(Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3))
        tv.layoutParams = lp
        tv.background = Ui.tileBackground(accent, false, 10, this)
        Ui.focusLift(tv, accent, 10) { hasFocus -> if (hasFocus) lastKey = tv }
        tv.setOnClickListener { onPress() }
        return tv
    }

    private fun typeChar(ch: Char) {
        query += ch
        onQueryChanged()
    }

    private fun onQueryChanged() {
        updateQueryLabel()
        rebuildResults()
    }

    // -------------------------------------------------------------- results

    /** v3.0: results rebuilds run behind this guard (never a black screen). */
    private fun rebuildResults() {
        try {
            rebuildResultsInner()
        } catch (e: Throwable) {
            Prefs.noteError(this, "searchResults", e)
            val box = resultsBox ?: return
            try {
                box.removeAllViews()
                val line = TextView(this)
                line.text = "Results hit a snag — the error is noted in the launcher's diagnostics."
                line.setTextColor(subColour)
                line.textSize = 16f
                box.addView(line)
            } catch (ignored: Throwable) {
                // Leave whatever is on screen rather than crash.
            }
        }
    }

    private fun rebuildResultsInner() {
        val box = resultsBox ?: return
        box.removeAllViews()
        artworkByPkg = prefs.artworkMap()
        val rows = mutableListOf<List<View>>()

        // v1.8: "In your tracker" — above the app results, present
        // only when tracker snapshots exist and titles match. The
        // cache is read per keystroke; no data → no section.
        val snap = TrackerClient.cached(this)
        val hits = TrackerLogic.searchTracked(snap.tracked, snap.movies, query)
        if (hits.isNotEmpty()) {
            val header = TextView(this)
            header.text = "In your tracker"
            header.setTextColor(accent); header.textSize = 20f
            header.setTypeface(header.typeface, android.graphics.Typeface.BOLD)
            header.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 6))
            box.addView(header)
            for (hit in hits) {
                val rowView = trackerHitRow(hit)
                rows.add(listOf(rowView))
                box.addView(rowView)
            }
        }

        val items = AppSearch.filter(
            visibleApps.map { AppSearch.Item(it.pkg, it.label) }, query
        )
        if (items.isEmpty()) {
            if (hits.isEmpty()) {
                val t = TextView(this)
                t.text = if (visibleApps.isEmpty()) "No apps to search." else "No apps match “$query”."
                t.setTextColor(subColour); t.textSize = 18f
                box.addView(t)
            }
            resultRows = rows
            return
        }
        if (hits.isNotEmpty()) {
            val header = TextView(this)
            header.text = "Apps"
            header.setTextColor(accent); header.textSize = 20f
            header.setTypeface(header.typeface, android.graphics.Typeface.BOLD)
            header.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 6))
            box.addView(header)
        }
        val cols = prefs.columns
        var i = 0
        while (i < items.size) {
            val rowView = LinearLayout(this)
            rowView.orientation = LinearLayout.HORIZONTAL
            val tiles = mutableListOf<View>()
            for (item in items.subList(i, minOf(i + cols, items.size))) {
                val entry = byPkg[item.pkg] ?: continue
                val tile = resultTile(entry)
                tiles.add(tile)
                rowView.addView(tile)
            }
            rows.add(tiles)
            box.addView(rowView)
            i += cols
        }
        resultRows = rows
    }

    /**
     * One "In your tracker" result row: title + tracker detail.
     * v2.1: OK opens the title's in-launcher DETAILS (which carries
     * its own gated "Open in Tracker") instead of jumping straight
     * to the tracker app — the §16 behaviour, upgraded.
     */
    private fun trackerHitRow(hit: TrackerLogic.TrackerHit): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.isFocusable = true
        row.isClickable = true
        row.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10))
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.setMargins(0, Ui.dp(this, 3), 0, Ui.dp(this, 3))
        row.layoutParams = lp
        row.background = Ui.tileBackground(accent, false, 10, this)
        Ui.focusLift(row, accent, 10)

        val title = TextView(this)
        title.text = hit.title
        title.setTextColor(textColour); title.textSize = 19f
        title.maxLines = 1
        title.ellipsize = android.text.TextUtils.TruncateAt.END
        row.addView(title, LinearLayout.LayoutParams(0, -2, 1f))

        val detail = TextView(this)
        detail.text = hit.detail
        detail.setTextColor(subColour); detail.textSize = 16f
        row.addView(detail)

        row.setOnClickListener {
            startActivity(
                com.mizan.launcher.data.TrackerActions.detailsIntent(
                    this, hit.tmdbId, hit.type, hit.title, ""
                )
            )
        }
        return row
    }

    /**
     * Open the tracker from a search hit, through the same arrow-lock
     * gate as an app launch (the tracker is a launchable app; if it
     * is locked, its lock applies). Absent tracker → toast.
     */
    private fun openTrackerGated() {
        val doOpen: () -> Unit = {
            try {
                val intent = AppLoader.launchIntent(packageManager, TrackerClient.TRACKER_PKG)
                if (intent == null) {
                    Toast.makeText(
                        this, "TV Shows & Movies Tracker isn't installed",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    prefs.recordLaunch(TrackerClient.TRACKER_PKG)
                    startActivity(intent)
                    finish()
                }
            } catch (e: Exception) {
                Prefs.noteError(this, "searchOpenTracker", e)
                Toast.makeText(this, "Couldn't open the tracker", Toast.LENGTH_SHORT).show()
            }
        }
        if (!prefs.lockedApps.contains(TrackerClient.TRACKER_PKG)) {
            doOpen()
            return
        }
        val stored = prefs.arrowPin
        if (stored.isEmpty()) {
            ArrowPinDialog.showSetup(
                activity = this,
                title = "Set an arrow pattern to lock TV Shows & Movies Tracker",
                accent = accent,
                onDone = { seq ->
                    prefs.arrowPin = seq
                    Toast.makeText(this, "Arrow lock set", Toast.LENGTH_SHORT).show()
                    doOpen()
                }
            )
            return
        }
        ArrowPinDialog.showUnlock(
            activity = this,
            title = "TV Shows & Movies Tracker is locked",
            accent = accent,
            storedEncoded = stored,
            onSuccess = { doOpen() },
            onWrongAttempt = { prefs.arrowAttempts = prefs.arrowAttempts + 1 }
        )
    }

    /** A result tile: the home screen's uniform tile + focus-name overlay. */
    private fun resultTile(entry: AppEntry): View {
        val dm = resources.displayMetrics
        val m = TileLayout.tileMetrics(dm.widthPixels, dm.density, prefs.appSize, prefs.columns)
        val frame = FrameLayout(this)
        frame.isFocusable = true
        frame.isClickable = true
        val lp = LinearLayout.LayoutParams(m.tileWidthPx, m.tileHeightPx)
        val margin = Ui.dp(this, TileLayout.TILE_MARGIN_DP)
        lp.setMargins(margin, margin, margin, margin)
        frame.layoutParams = lp
        frame.background = Ui.tileBackground(accent, false, 16, this)

        val iv = ImageView(this)
        // v2.0 custom artwork, same consultation as the home tiles.
        val fallbackIcon = Ui.shapedIcon(entry.icon, m.iconPx, prefs.iconShape)
        when (val art = artworkByPkg[entry.pkg]) {
            null -> iv.setImageBitmap(fallbackIcon)
            else -> when (art.kind) {
                ArtworkStore.Kind.PRESET -> iv.setImageBitmap(
                    Ui.presetArtwork(
                        ArtworkStore.presetColors(ArtworkStore.presetIndex(art.value)),
                        entry.label, m.iconPx, prefs.iconShape
                    )
                )
                ArtworkStore.Kind.URL -> ArtworkImages.load(
                    this, entry.pkg, art.value, iv, m.iconPx, prefs.iconShape, fallbackIcon
                )
            }
        }
        val ilp = FrameLayout.LayoutParams(m.iconPx, m.iconPx)
        ilp.gravity = Gravity.CENTER
        iv.layoutParams = ilp
        frame.addView(iv)

        if (prefs.lockedApps.contains(entry.pkg)) {
            val lock = TextView(this)
            lock.text = "🔒"; lock.textSize = 13f
            val llp = FrameLayout.LayoutParams(-2, -2)
            llp.gravity = Gravity.TOP or Gravity.END
            llp.setMargins(0, Ui.dp(this, 4), Ui.dp(this, 6), 0)
            lock.layoutParams = llp
            frame.addView(lock)
        }

        val label = TextView(this)
        label.text = entry.label
        label.setTextColor(textColour); label.textSize = 14f
        label.gravity = Gravity.CENTER
        label.maxLines = 2
        label.ellipsize = android.text.TextUtils.TruncateAt.END
        label.setBackgroundColor(0xB3000000.toInt())
        label.setPadding(Ui.dp(this, 6), Ui.dp(this, 3), Ui.dp(this, 6), Ui.dp(this, 3))
        val labLp = FrameLayout.LayoutParams(-1, -2)
        labLp.gravity = Gravity.BOTTOM
        label.layoutParams = labLp
        label.visibility = View.INVISIBLE
        frame.addView(label)
        Ui.focusLift(frame, accent, 16) { hasFocus ->
            label.visibility = if (TileLayout.labelVisible(hasFocus)) View.VISIBLE else View.INVISIBLE
        }
        frame.setOnClickListener { launchWithGate(entry) }
        // v1.6: Search results had NO hold-OK menu at all — every
        // other app surface offers the arrow-lock toggle. This is the
        // same core menu as the All Apps tab (favorite / lock / info).
        frame.setOnLongClickListener { openResultMenu(entry); true }
        return frame
    }

    /** Hold-OK menu for a search result (favorite / arrow lock / app info). */
    private fun openResultMenu(entry: AppEntry) {
        val layout = prefs.layout
        val isFav = layout.favorites.contains(entry.pkg)
        val isLocked = prefs.lockedApps.contains(entry.pkg)
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        labels.add(if (isFav) "Remove favorite" else "Add favorite")
        actions.add {
            if (isFav) layout.favorites.remove(entry.pkg) else layout.favorites.add(entry.pkg)
            prefs.layout = layout
            rebuildResults()
        }
        labels.add(if (isLocked) "Remove arrow lock" else "Arrow-lock this app")
        actions.add {
            val set = prefs.lockedApps.toMutableSet()
            if (isLocked) set.remove(entry.pkg) else set.add(entry.pkg)
            prefs.lockedApps = set
            if (!isLocked && prefs.arrowPin.isEmpty()) {
                Toast.makeText(
                    this,
                    "Locked. You'll set an arrow pattern the first time you open it (or in Settings).",
                    Toast.LENGTH_LONG
                ).show()
            }
            rebuildResults()
        }
        // v2.0: custom artwork (presets / image URL / reset).
        labels.add("Custom artwork…")
        actions.add {
            ArtworkPicker.show(this, prefs, accent, entry.pkg, entry.label) { rebuildResults() }
        }
        labels.add("App info")
        actions.add {
            try {
                startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(android.net.Uri.parse("package:${entry.pkg}"))
                )
            } catch (e: Exception) { Prefs.noteError(this, "searchAppInfo", e) }
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(entry.label)
            .setItems(labels.toTypedArray()) { _, which -> actions[which].invoke() }
            .show()
    }

    // -------------------------------------------------------------- launching

    /**
     * Launch through the same arrow-lock gate as a home-screen launch
     * (mirrors MainActivity.launchWithPin, including the fail-open
     * set-pattern-first rule), recording the launch in Recently Used.
     */
    private fun launchWithGate(entry: AppEntry) {
        if (!prefs.lockedApps.contains(entry.pkg)) {
            doLaunch(entry)
            return
        }
        val stored = prefs.arrowPin
        if (stored.isEmpty()) {
            ArrowPinDialog.showSetup(
                activity = this,
                title = "Set an arrow pattern to lock ${entry.label}",
                accent = accent,
                onDone = { seq ->
                    prefs.arrowPin = seq
                    Toast.makeText(this, "Arrow lock set", Toast.LENGTH_SHORT).show()
                    doLaunch(entry)
                }
            )
            return
        }
        ArrowPinDialog.showUnlock(
            activity = this,
            title = "${entry.label} is locked",
            accent = accent,
            storedEncoded = stored,
            onSuccess = { doLaunch(entry) },
            onWrongAttempt = { prefs.arrowAttempts = prefs.arrowAttempts + 1 }
        )
    }

    private fun doLaunch(entry: AppEntry) {
        try {
            val intent = AppLoader.launchIntent(packageManager, entry.pkg)
            if (intent == null) {
                Toast.makeText(this, "Couldn't launch ${entry.label}", Toast.LENGTH_SHORT).show()
                return
            }
            prefs.recordLaunch(entry.pkg)
            startActivity(intent)
            finish()
        } catch (e: Exception) {
            Prefs.noteError(this, "searchLaunch:${entry.pkg}", e)
            Toast.makeText(this, "Couldn't launch ${entry.label}", Toast.LENGTH_SHORT).show()
        }
    }
}
