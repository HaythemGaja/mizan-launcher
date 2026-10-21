package com.mizan.launcher.core

/**
 * Home-screen STYLE (v1.7, extended v1.8) — pure decisions, no
 * Android imports.
 *
 * A per-profile presentation choice: the classic TV home (rows under
 * a hero bar — everything v1.0–v1.6 built), the iPad home (a big
 * icon grid with labels under the icons and a floating Favorites
 * dock), or — v1.8 — the Tracker home (a Netflix-style view of the
 * user's shows & movies from TVShowsMoviesTracker, over the same TV
 * status bar and the same Favorites / All Apps rows). The style
 * changes PRESENTATION + COMPOSITION ONLY: the underlying data
 * (favorites, folders, allAppsOrder, locks) and every menu, gate and
 * move session are shared between the styles, so switching styles
 * can never lose or reinterpret a setting. The TV and iPad renders
 * stay tracker-free; tracker data appears ONLY in the Tracker style
 * (and in Search, which is a shared screen).
 *
 * This object owns the testable decisions:
 * - the stored-key codec ([normalize] — only the exact "ipad" /
 *   "tracker" keys select those styles; anything else is TV, so old
 *   profiles and old backups default to TV),
 * - the label rule (TV + Tracker: focus-only overlay; iPad: always
 *   under the icon),
 * - the icon-shape override (iPad forces the squircle),
 * - the iPad grid composition (apps segment + root-folders segment
 *   + inputs segment),
 * - the iPad dock composition (capped favorites + fixed buttons).
 */
object HomeStyle {

    const val TV = "tv"
    const val IPAD = "ipad"
    const val TRACKER = "tracker"

    /** Closed list of stored values, for Settings pickers. */
    val VALUES: List<String> = listOf(TV, IPAD, TRACKER)

    /**
     * Shape key the iPad style forces for app icons: the existing
     * "rounded" mask with the Design radius scaled up (a squircle).
     * Ui.shapedIcon understands this key; TV style never produces it.
     */
    const val SHAPE_SQUIRCLE = "squircle"

    /** Max favorites shown in the iPad dock (it scrolls past this). */
    const val DOCK_FAVORITES_CAP = 10

    /** Codec: only the exact "ipad" / "tracker" keys select those
     * styles; anything else (missing, empty, garbage, an old backup)
     * is TV. */
    fun normalize(raw: String?): String = when (raw) {
        IPAD -> IPAD
        TRACKER -> TRACKER
        else -> TV
    }

    fun isIpad(style: String): Boolean = style == IPAD

    /** v1.8: the Tracker home style (Netflix-style tracker view). */
    fun isTracker(style: String): Boolean = style == TRACKER

    /**
     * The one label decision for home tiles: in iPad style a tile's
     * name is ALWAYS visible (drawn under the icon); in TV style the
     * v1.2 rule stands (name only while focused — see
     * [TileLayout.labelVisible], which this mirrors for TV).
     */
    fun labelVisible(style: String, hasFocus: Boolean): Boolean =
        isIpad(style) || hasFocus

    /**
     * The icon shape a tile renders with: iPad style always uses the
     * squircle, whatever the profile's icon-shape preference says;
     * TV style uses the preference untouched.
     */
    fun effectiveIconShape(style: String, prefShape: String): String =
        if (isIpad(style)) SHAPE_SQUIRCLE else prefShape

    // ------------------------------------------------------------ grid

    /**
     * The iPad main grid, in render order: (a) the visible All Apps
     * in the user's order, (b) the ROOT folders in folder-row order,
     * (c) the HDMI/TV inputs — only when the inputs row is enabled
     * AND the device reports inputs. Nested folders are NOT grid
     * items (they live inside their parent, as on TV).
     */
    data class GridComposition(
        val appPkgs: List<String>,
        val folderIds: List<String>,
        val inputIds: List<String>
    ) {
        val size: Int get() = appPkgs.size + folderIds.size + inputIds.size
    }

    /**
     * Compose the grid from the caller's display inputs.
     * [installedOrder] is the full catalogue in display order (the
     * caller's custom order first, then the rest); the apps segment
     * applies the standing exclusions through [FolderLogic] (hidden
     * and in-any-folder apps drop out, order preserved). The folders
     * segment is the tree's root folders in their stored order.
     */
    fun composeGrid(
        installedOrder: List<String>,
        hidden: Set<String>,
        folders: List<Folder>,
        inputsEnabled: Boolean,
        inputIds: List<String>
    ): GridComposition = GridComposition(
        appPkgs = FolderLogic.allAppsVisible(installedOrder, hidden, folders),
        folderIds = FolderTree.rootFolders(folders).map { it.id },
        inputIds = if (inputsEnabled) inputIds.toList() else emptyList()
    )

    // ------------------------------------------------------------ dock

    /** What one dock slot is. FAVORITE carries its package. */
    enum class DockKind { FAVORITE, DIVIDER, SEARCH, ALL_APPS, PROFILE, SETTINGS }

    data class DockItem(val kind: DockKind, val pkg: String? = null)

    /**
     * Compose the iPad dock, left to right: the profile's favorites
     * in order (capped at [DOCK_FAVORITES_CAP] — at most 10 are
     * composed; the dock VIEW still scrolls horizontally when the
     * composed items overflow the screen width), then ONE divider
     * (only when at least one favorite precedes it), then the fixed
     * buttons:
     * Search and All Apps only when their per-tile switches are on,
     * Profile and Settings ALWAYS, in that order, last.
     * [favorites] is the caller's displayable list (resolved and
     * unhidden), in profile order.
     */
    fun composeDock(
        favorites: List<String>,
        showSearch: Boolean,
        showAllApps: Boolean
    ): List<DockItem> {
        val out = ArrayList<DockItem>()
        val favs = favorites.take(DOCK_FAVORITES_CAP)
        for (pkg in favs) out.add(DockItem(DockKind.FAVORITE, pkg))
        if (favs.isNotEmpty()) out.add(DockItem(DockKind.DIVIDER))
        if (showSearch) out.add(DockItem(DockKind.SEARCH))
        if (showAllApps) out.add(DockItem(DockKind.ALL_APPS))
        out.add(DockItem(DockKind.PROFILE))
        out.add(DockItem(DockKind.SETTINGS))
        return out
    }
}
