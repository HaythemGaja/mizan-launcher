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
import com.mizan.launcher.core.FolderLogic
import com.mizan.launcher.core.Themes
import com.mizan.launcher.core.TileLayout
import com.mizan.launcher.data.AppEntry
import com.mizan.launcher.data.AppLoader
import com.mizan.launcher.data.Prefs

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

        setContentView(buildRoot(preset.bgTop, preset.bgBottom))
        keyboardRows.firstOrNull()?.firstOrNull()?.post {
            keyboardRows.firstOrNull()?.firstOrNull()?.requestFocus()
        }
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

        root.addView(buildKeyboard())

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
        // Bottom row: Space / Backspace / Clear.
        val special = LinearLayout(this)
        special.orientation = LinearLayout.HORIZONTAL
        val space = keyButton("␣ Space") { typeChar(' ') }
        val back = keyButton("⌫") {
            if (query.isNotEmpty()) { query = query.dropLast(1); onQueryChanged() }
        }
        val clear = keyButton("Clear") {
            if (query.isNotEmpty()) { query = ""; onQueryChanged() }
        }
        val specialKeys = listOf<View>(space, back, clear)
        for (k in specialKeys) special.addView(k)
        rows.add(specialKeys)
        kb.addView(special)
        keyboardRows = rows
        return kb
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

    private fun rebuildResults() {
        val box = resultsBox ?: return
        box.removeAllViews()
        val items = AppSearch.filter(
            visibleApps.map { AppSearch.Item(it.pkg, it.label) }, query
        )
        if (items.isEmpty()) {
            val t = TextView(this)
            t.text = if (visibleApps.isEmpty()) "No apps to search." else "No apps match “$query”."
            t.setTextColor(subColour); t.textSize = 18f
            box.addView(t)
            resultRows = emptyList()
            return
        }
        val cols = prefs.columns
        val rows = mutableListOf<List<View>>()
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
        iv.setImageBitmap(Ui.shapedIcon(entry.icon, m.iconPx, prefs.iconShape))
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
        return frame
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
