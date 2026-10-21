package com.mizan.launcher.core

/**
 * The All Apps TAB (v1.5) — pure list-building, no Android imports.
 *
 * Unlike the home screen's All Apps ROW, the tab is a catalogue of
 * EVERYTHING: every installed launchable app except hidden ones —
 * INCLUDING apps that live inside folders (the home row hides those;
 * finding them again is the whole point of the tab). Foldered apps are
 * flagged with the name of the folder holding them so the tile can
 * badge them. The list is alphabetical by label (case-insensitive,
 * package name as the tie-break) — the tab has no custom order and
 * therefore no Move action.
 */
object AllAppsLogic {

    /** One row of the tab: an app, whether it lives in a folder, and which. */
    data class Item(
        val pkg: String,
        val label: String,
        val inFolder: Boolean,
        /** Name of the (first) folder containing the app; "" when not foldered. */
        val folderName: String
    )

    /**
     * Build the tab's list from the installed apps ([apps] carries
     * package + label), the profile's hidden set, and its folders.
     * Membership is recursive (see [FolderTree.allFolderPackages]): an
     * app three folders deep is still "in a folder" here.
     */
    fun build(apps: List<AppSearch.Item>, hidden: Set<String>, folders: List<Folder>): List<Item> {
        // First folder (list order) holding each package names the badge.
        val memberOf = HashMap<String, String>()
        for (f in folders) {
            for (pkg in f.appPackages) memberOf.putIfAbsent(pkg, f.name)
        }
        return apps
            .filter { it.pkg !in hidden }
            .map { app ->
                val folderName = memberOf[app.pkg]
                Item(app.pkg, app.label, folderName != null, folderName ?: "")
            }
            .sortedWith(compareBy<Item> { it.label.lowercase() }.thenBy { it.pkg })
    }
}
