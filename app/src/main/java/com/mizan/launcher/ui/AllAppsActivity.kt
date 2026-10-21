package com.mizan.launcher.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.mizan.launcher.core.AllAppsLogic
import com.mizan.launcher.core.AppSearch
import com.mizan.launcher.core.Folder
import com.mizan.launcher.core.FolderEntry
import com.mizan.launcher.core.FolderTree
import com.mizan.launcher.core.LayoutData
import com.mizan.launcher.core.Themes
import com.mizan.launcher.core.TileLayout
import com.mizan.launcher.data.AppEntry
import com.mizan.launcher.data.AppLoader
import com.mizan.launcher.data.Prefs

/**
 * The All Apps TAB (v1.5) — a full-screen catalogue opened from the
 * status bar's "▦ All Apps" tile.
 *
 * Unlike the home screen's All Apps ROW, this page lists EVERY
 * installed launchable app except hidden ones — including apps that
 * live inside folders (the row dedupes those away; finding them again
 * is the point of the tab). Foldered apps carry a small "▸ Folder"
 * badge (core/AllAppsLogic decides membership recursively). The grid
 * is alphabetical by label — there is no custom order here, so tiles
 * have no Move action.
 *
 * Tiles behave like home tiles otherwise: OK launches through the
 * same arrow-lock gate (and records in Recently Used); hold-OK opens
 * the familiar menu — favorite, add to folder, arrow-lock, hide,
 * app info, uninstall. BACK returns home.
 */
class AllAppsActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var layout: LayoutData
    private var accent: Int = 0
    private var textColour: Int = 0
    private var subColour: Int = 0

    private var allApps: List<AppEntry> = emptyList()
    private var byPkg: Map<String, AppEntry> = emptyMap()
    private var items: List<AllAppsLogic.Item> = emptyList()
    private var gridBox: LinearLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val preset = Themes.preset(prefs.themeId)
        textColour = preset.text; subColour = preset.subText
        accent = if (preset.dynamic && prefs.accentOverride != 0) prefs.accentOverride else preset.accent

        allApps = AppLoader.loadApps(packageManager, packageName)
        byPkg = allApps.associateBy { it.pkg }

        setContentView(buildRoot(preset.bgTop, preset.bgBottom))
        gridBox?.post { focusFirstTile() }
    }

    override fun onResume() {
        super.onResume()
        if (::prefs.isInitialized) {
            // Layout may have changed (hide/favorite/folder edits made
            // from this screen go through dialogs; a resume rebuild is
            // the simple, always-correct refresh).
            reloadItems()
            rebuildGrid()
        }
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
        title.text = "All Apps"
        title.setTextColor(accent); title.textSize = 28f
        title.setTypeface(title.typeface, android.graphics.Typeface.BOLD)
        root.addView(title)

        val sub = TextView(this)
        sub.text = "Every app on this TV — apps inside folders are badged ▸"
        sub.setTextColor(subColour); sub.textSize = 16f
        sub.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 10))
        root.addView(sub)

        val scroll = ScrollView(this)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        scroll.addView(box)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        gridBox = box
        reloadItems()
        rebuildGrid()
        return root
    }

    private fun reloadItems() {
        layout = prefs.layout
        items = AllAppsLogic.build(
            allApps.map { AppSearch.Item(it.pkg, it.label) },
            layout.hidden.toSet(),
            layout.folders
        )
    }

    private fun rebuildGrid() {
        val box = gridBox ?: return
        box.removeAllViews()
        if (items.isEmpty()) {
            val t = TextView(this)
            t.text = "No launchable apps found."
            t.setTextColor(subColour); t.textSize = 18f
            box.addView(t)
            return
        }
        val cols = prefs.columns
        var i = 0
        while (i < items.size) {
            val rowView = LinearLayout(this)
            rowView.orientation = LinearLayout.HORIZONTAL
            for (item in items.subList(i, minOf(i + cols, items.size))) {
                val entry = byPkg[item.pkg] ?: continue
                rowView.addView(appTile(entry, item))
            }
            box.addView(rowView)
            i += cols
        }
    }

    private fun focusFirstTile() {
        val box = gridBox ?: return
        fun walk(v: View): Boolean {
            if (v.isFocusable && v.requestFocus()) return true
            if (v is android.view.ViewGroup) {
                for (i in 0 until v.childCount) if (walk(v.getChildAt(i))) return true
            }
            return false
        }
        walk(box)
    }

    /** One catalogue tile: uniform tile + focus-name overlay + badges. */
    private fun appTile(entry: AppEntry, item: AllAppsLogic.Item): View {
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

        if (item.inFolder) {
            // The folder badge: which folder holds this app (the tab's
            // reason to exist). Top-left — the lock badge owns top-right.
            val badge = TextView(this)
            badge.text = "▸ ${item.folderName}".take(16)
            badge.setTextColor(textColour); badge.textSize = 11f
            badge.maxLines = 1
            badge.ellipsize = android.text.TextUtils.TruncateAt.END
            badge.setBackgroundColor(0xB3000000.toInt())
            badge.setPadding(Ui.dp(this, 5), Ui.dp(this, 2), Ui.dp(this, 5), Ui.dp(this, 2))
            val blp = FrameLayout.LayoutParams(-2, -2)
            blp.gravity = Gravity.TOP or Gravity.START
            blp.setMargins(Ui.dp(this, 4), Ui.dp(this, 4), 0, 0)
            badge.layoutParams = blp
            frame.addView(badge)
        }
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
        frame.setOnLongClickListener { openAppMenu(entry); true }
        return frame
    }

    // -------------------------------------------------------------- app menu

    /**
     * The home hold-OK menu, minus Move (the tab is alphabetical —
     * there is no order to rearrange). Layout edits save through the
     * same per-profile store the home screen renders from.
     */
    private fun openAppMenu(entry: AppEntry) {
        layout = prefs.layout
        val isFav = layout.favorites.contains(entry.pkg)
        val isLocked = prefs.lockedApps.contains(entry.pkg)
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        labels.add(if (isFav) "Remove favorite" else "Add favorite")
        actions.add {
            if (isFav) layout.favorites.remove(entry.pkg) else layout.favorites.add(entry.pkg)
            saveLayoutAndRefresh()
        }
        labels.add("Add to folder…")
        actions.add { chooseFolder(entry) }
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
            reloadItems(); rebuildGrid()
        }
        labels.add("Hide app")
        actions.add {
            layout.hidden.add(entry.pkg)
            layout.favorites.remove(entry.pkg)
            saveLayoutAndRefresh()
            Toast.makeText(this, "Hidden. Unhide it in Settings.", Toast.LENGTH_SHORT).show()
        }
        labels.add("App info")
        actions.add {
            try {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(android.net.Uri.parse("package:${entry.pkg}"))
                )
            } catch (e: Exception) { Prefs.noteError(this, "allAppsAppInfo", e) }
        }
        if (!entry.isSystem) {
            labels.add("Uninstall")
            actions.add {
                try {
                    startActivity(Intent(Intent.ACTION_DELETE).setData(android.net.Uri.parse("package:${entry.pkg}")))
                } catch (e: Exception) { Prefs.noteError(this, "allAppsUninstall", e) }
            }
        }

        AlertDialog.Builder(this)
            .setTitle(entry.label)
            .setItems(labels.toTypedArray()) { _, which -> actions[which].invoke() }
            .show()
    }

    private fun saveLayoutAndRefresh() {
        prefs.layout = layout
        reloadItems()
        rebuildGrid()
    }

    /** "Add to folder…" — same breadcrumb-labelled picker as home. */
    private fun chooseFolder(entry: AppEntry) {
        layout = prefs.layout
        if (layout.folders.isEmpty()) {
            promptFolderName { name ->
                layout.folders.add(
                    Folder(
                        FolderTree.freshId(layout.folders), name,
                        mutableListOf(FolderEntry.App(entry.pkg))
                    )
                )
                saveLayoutAndRefresh()
            }
            return
        }
        val names = layout.folders.map { folder ->
            FolderTree.breadcrumb(layout.folders, folder.id).joinToString(" › ") { it.name }
        } + "＋ New folder…"
        AlertDialog.Builder(this).setTitle("Add ${entry.label} to…")
            .setItems(names.toTypedArray()) { _, which ->
                if (which == layout.folders.size) {
                    promptFolderName { name ->
                        layout.folders.add(
                            Folder(
                                FolderTree.freshId(layout.folders), name,
                                mutableListOf(FolderEntry.App(entry.pkg))
                            )
                        )
                        saveLayoutAndRefresh()
                    }
                } else {
                    val f = layout.folders[which]
                    if (!f.appPackages.contains(entry.pkg)) {
                        f.entries.add(FolderEntry.App(entry.pkg))
                    }
                    saveLayoutAndRefresh()
                    Toast.makeText(this, "Added to ${f.name}", Toast.LENGTH_SHORT).show()
                }
            }.show()
    }

    private fun promptFolderName(after: (String) -> Unit) {
        val input = EditText(this)
        input.hint = "Folder name"
        input.setTextColor(0xFF111111.toInt())
        AlertDialog.Builder(this).setTitle("New folder").setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) after(name)
            }
            .setNegativeButton("Cancel", null).show()
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
            Prefs.noteError(this, "allAppsLaunch:${entry.pkg}", e)
            Toast.makeText(this, "Couldn't launch ${entry.label}", Toast.LENGTH_SHORT).show()
        }
    }
}
