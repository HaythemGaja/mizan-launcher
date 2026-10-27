package com.mizan.launcher.domain

/**
 * Installed apps, folders, layout rows, recents / most-used.
 * Icons are deliberately NOT in this layer: no Bitmap/Drawable
 * (Android UI classes are forbidden in domain). UI resolves icons
 * from packageName; per-app custom artwork is referenced by
 * preset index or image path only.
 */
data class InstalledApp(
    val packageName: String,
    val label: String,
    val category: String = "",
    val isSystem: Boolean = false,
    /** Containing folder id when foldered (any depth); null at root. */
    val folderId: String? = null,
    val folderBreadcrumb: String = "",
    val hidden: Boolean = false,
    val locked: Boolean = false,
    /** Custom artwork preset index, or null for the app icon. */
    val artworkPreset: Int? = null,
    val artworkImagePath: String? = null,
)

data class FolderEntry(
    val key: String,
    /** "app" or "folder". */
    val kind: String,
    /** Package name for apps, folder id for sub-folders. */
    val ref: String,
    val label: String,
)

data class Folder(
    val id: String,
    val name: String,
    val entries: List<FolderEntry> = emptyList(),
    val locked: Boolean = false,
    val depth: Int = 1,
)

data class RecentApp(val packageName: String, val label: String, val lastUsedMs: Long)

data class MostUsedApp(val packageName: String, val label: String, val launchCount: Long)

interface AppsRepository {
    /** All launchable apps minus hidden; foldered apps included, badged. */
    fun getAllApps(): List<InstalledApp>

    /** Apps row: all apps minus hidden AND foldered, in stored order. */
    fun getAppsRow(): List<InstalledApp>

    fun getFavorites(): List<InstalledApp>
    fun setFavorite(packageName: String, favorite: Boolean)

    /** Recently used, newest first, capped at 8; launcher never recorded. */
    fun getRecents(): List<RecentApp>
    fun recordLaunch(packageName: String)

    /** Most used: count desc, recency breaks ties, name breaks recency ties. */
    fun getMostUsed(): List<MostUsedApp>

    fun getHiddenApps(): List<InstalledApp>
    fun setHidden(packageName: String, hidden: Boolean)

    fun getFolders(): List<Folder>
    fun getRootFolders(): List<Folder>

    /** Folder rules enforced by implementations (see core/LayoutStore): */
    fun createFolder(name: String): Folder
    fun renameFolder(folderId: String, name: String)
    fun deleteFolder(folderId: String) // sub-folders rehomed to root, apps kept
    fun moveEntry(entryKey: String, targetFolderId: String?)
    fun setFolderLocked(folderId: String, locked: Boolean)
}
