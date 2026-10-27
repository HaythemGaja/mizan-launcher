package com.mizan.launcher.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.LinearLayout
import com.mizan.launcher.domain.InstalledApp

/** Full-screen All Apps grid (MAP §1.2): every app incl. foldered (badged), A–Z, label ties by package, hidden excluded. */
class AllAppsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL
        col.addView(Ui.sectionTitle(this, "All Apps"))
        setContentView(Ui.rootScrollView(this, col))
        Ui.runBg {
            val apps = try { UiWiring.apps?.getAllApps() ?: emptyList() } catch (e: Throwable) { emptyList() }
            val sorted = apps.sortedWith(compareBy({ it.label.lowercase() }, { it.packageName }))
            Ui.runMain { rebuild(col, sorted) }
        }
    }
    private fun rebuild(col: LinearLayout, apps: List<InstalledApp>) {
        // Keep the title (first child); rebuild the rest.
        while (col.childCount > 1) col.removeViewAt(1)
        if (apps.isEmpty()) { col.addView(Ui.stateText(this, "No apps found")); return }
        val cols = UiWiring.settings?.getProfile(UiWiring.settings?.getGlobal()?.currentProfile ?: 1)?.gridColumns ?: 6
        var row: LinearLayout? = null; var count = 0
        for (app in apps) {
            if (count % cols == 0) { row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL; col.addView(row) }
            row?.addView(PosterCards.appTile(this, app,
                onClick = { launchGated(app) },
                onLong = { appMenu(app) }))
            count++
        }
    }
    private fun launchGated(app: InstalledApp) {
        if (app.locked) PatternGate.run(this) { Ui.launchApp(this, app.packageName) }
        else Ui.launchApp(this, app.packageName)
    }
    private fun appMenu(app: InstalledApp) {
        val items = mutableListOf<Pair<String, () -> Unit>>()
        items += "Add to folder…" to { FolderViewActivity.open(this, null) }
        items += "Hide app" to { Ui.runBg { UiWiring.apps?.setHidden(app.packageName, true); recreate() } }
        items += "App info" to { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}"))) }
        if (!app.isSystem) items += "Uninstall" to { startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.packageName}"))) }
        val lockLabel = if (app.locked) "Unlock app" else "Lock app"
        items += lockLabel to {
            Ui.runBg { UiWiring.lock?.setAppLocked(app.packageName, !app.locked) }
        }
        items += "Artwork…" to { ArtworkPicker.show(this, app.packageName, app.artworkPreset) { _, _ -> } }
        Ui.menu(this, app.label, items)
    }
}
