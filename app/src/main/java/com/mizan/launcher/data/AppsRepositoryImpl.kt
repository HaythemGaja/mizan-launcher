package com.mizan.launcher.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import com.mizan.launcher.core.AppCatalogue
import com.mizan.launcher.core.FolderEntry
import com.mizan.launcher.core.FolderTree
import com.mizan.launcher.core.LayoutData
import com.mizan.launcher.core.RecentsStore
import com.mizan.launcher.domain.AppsRepository
import com.mizan.launcher.domain.Folder
import com.mizan.launcher.domain.FolderEntry as DomainFolderEntry
import com.mizan.launcher.domain.InstalledApp
import com.mizan.launcher.domain.MostUsedApp
import com.mizan.launcher.domain.RecentApp

/**
 * Installed apps via PackageManager leanback + launcher intents
 * (MAP §5): catalogue dedupes across categories (leanback label
 * wins), system apps included and flagged, Sreeb itself excluded,
 * sorted case-insensitively by label (ties by package). Folders
 * follow core/LayoutStore depth-3 rules; recents cap 8; favorites
 * may also live in folders.
 */
class AppsRepositoryImpl(ctx: Context) : AppsRepository {
    private val context = ctx.applicationContext
    private val prefs = PrefsStore(ctx)
    private val selfPkg: String get() = context.packageName

    private fun layout(): LayoutData = prefs.layout()
    private fun saveLayout(d: LayoutData) = prefs.saveLayout(d)

    private fun loadApps(): List<InstalledApp> {
        val pm = context.packageManager
        val seen = LinkedHashMap<String, InstalledApp>()
        fun collect(intent: Intent) {
            val list: List<ResolveInfo> = try {
                pm.queryIntentActivities(intent, 0)
            } catch (e: Throwable) { emptyList() }
            for (ri in list) {
                val pkg = ri.activityInfo?.packageName ?: continue
                if (pkg == selfPkg) continue
                val label = try {
                    ri.loadLabel(pm)?.toString() ?: pkg
                } catch (e: Throwable) { pkg }
                val ai = try { pm.getApplicationInfo(pkg, 0) } catch (e: Throwable) { null }
                val isSystem = ai != null && (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val existing = seen[pkg]
                if (existing == null) {
                    seen[pkg] = InstalledApp(pkg, label, "", isSystem)
                }
            }
        }
        collect(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER))
        collect(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER))
        val d = layout()
        val folderOf = HashMap<String, String>()
        val breadcrumbOf = HashMap<String, String>()
        // Resolve containing folder + breadcrumb per package (any depth).
        fun walk(folderId: String, chain: List<String>) {
            val f = FolderTree.byId(d.folders, folderId) ?: return
            for (e in f.entries) {
                when (e) {
                    is FolderEntry.App -> {
                        folderOf[e.pkg] = folderId
                        breadcrumbOf[e.pkg] = (chain + f.name).joinToString(" › ")
                    }
                    is FolderEntry.FolderRef -> walk(e.folderId, chain + f.name)
                }
            }
        }
        for (root in FolderTree.rootFolders(d.folders)) walk(root.id, emptyList())
        val artwork = prefs.artworkMap()
        val lockedApps = lockedApps()
        return seen.values.map { app ->
            val art = artwork[app.packageName]
            app.copy(
                folderId = folderOf[app.packageName],
                folderBreadcrumb = breadcrumbOf[app.packageName] ?: "",
                hidden = app.packageName in d.hidden,
                locked = app.packageName in lockedApps,
                artworkPreset = if (art?.kind == com.mizan.launcher.core.ArtworkStore.Kind.PRESET)
                    art.value.toIntOrNull() else null,
                artworkImagePath = if (art?.kind == com.mizan.launcher.core.ArtworkStore.Kind.URL)
                    art.value else null,
            )
        }.sortedWith(compareBy<InstalledApp> { it.label.lowercase() }.thenBy { it.packageName })
    }

    private fun lockedApps(): Set<String> =
        // Old format, preserved exactly: per-profile string-set key `locked`.
        PrefsSafe.stringSet(prefs.sp, "p${prefs.profile()}.locked")
    private fun saveLockedApps(s: Set<String>) {
        PrefsSafe.setStringSet(prefs.sp, "p${prefs.profile()}.locked", s)
    }

    override fun getAllApps(): List<InstalledApp> =
        loadApps().filter { !it.hidden }

    override fun getAppsRow(): List<InstalledApp> {
        val d = layout()
        val foldered = FolderTree.allFolderPackages(d.folders)
        val order = d.allAppsOrder
        val apps = loadApps().filter { !it.hidden && it.packageName !in foldered }
        if (order.isEmpty()) return apps
        val rank = order.withIndex().associate { it.value to it.index }
        return apps.sortedWith(compareBy<InstalledApp> { rank[it.packageName] ?: Int.MAX_VALUE }
            .thenBy { it.label.lowercase() }.thenBy { it.packageName })
    }

    override fun getFavorites(): List<InstalledApp> {
        val d = layout()
        val byPkg = loadApps().associateBy { it.packageName }
        return d.favorites.mapNotNull { byPkg[it] }.filter { !it.hidden }
    }

    override fun setFavorite(packageName: String, favorite: Boolean) {
        val d = layout()
        if (favorite) {
            if (packageName !in d.favorites) d.favorites.add(packageName)
        } else d.favorites.remove(packageName)
        saveLayout(d)
    }

    override fun getRecents(): List<RecentApp> {
        val byPkg = loadApps().associateBy { it.packageName }
        return prefs.recents().take(8).mapNotNull { e ->
            val app = byPkg[e.pkg]
            if (app == null) null else RecentApp(e.pkg, app.label, e.at)
        }
    }

    override fun recordLaunch(packageName: String) {
        if (packageName == selfPkg) return // launcher itself never recorded
        val updated = RecentsStore.record(prefs.recents(), packageName, System.currentTimeMillis(), selfPkg)
        prefs.setRecents(updated.take(8))
    }

    override fun getMostUsed(): List<MostUsedApp> {
        // Usage-stats path needs the PACKAGE_USAGE_STATS grant; the
        // grant check lives with the integration worker (app layer
        // supplies UsageStatsManager). Without it, fail open to the
        // launch-count record kept from recordLaunch ordering: we
        // report recents-derived counts only when usage stats are
        // unavailable, and never fabricate a grant.
        return UsageStatsCollector.collect(context).ifEmpty {
            // Fallback: no usage data -> empty (hidden by UI), not invented counts.
            emptyList()
        }
    }

    override fun getHiddenApps(): List<InstalledApp> =
        loadApps().filter { it.hidden }

    override fun setHidden(packageName: String, hidden: Boolean) {
        val d = layout()
        if (hidden) d.hidden.add(packageName) else d.hidden.remove(packageName)
        saveLayout(d)
    }

    override fun getFolders(): List<Folder> {
        val byPkg = loadApps().associateBy { it.packageName }
        val d = layout()
        return d.folders.map { f ->
            Folder(f.id, f.name, f.entries.map { e ->
                when (e) {
                    is FolderEntry.App -> DomainFolderEntry(e.key(), "app", e.pkg,
                        byPkg[e.pkg]?.label ?: e.pkg)
                    is FolderEntry.FolderRef -> DomainFolderEntry(e.key(), "folder", e.folderId,
                        FolderTree.byId(d.folders, e.folderId)?.name ?: e.folderId)
                }
            }, f.locked, FolderTree.depth(d.folders, f.id))
        }
    }

    override fun getRootFolders(): List<Folder> {
        val roots = FolderTree.rootFolders(layout().folders).map { it.id }.toSet()
        return getFolders().filter { it.id in roots }
    }

    override fun createFolder(name: String): Folder {
        val d = layout()
        val id = FolderTree.freshId(d.folders)
        val core = com.mizan.launcher.core.Folder(id, name)
        d.folders.add(core)
        saveLayout(d)
        return Folder(id, name, emptyList(), false, 1)
    }

    override fun renameFolder(folderId: String, name: String) {
        val d = layout()
        FolderTree.byId(d.folders, folderId)?.name = name
        saveLayout(d)
    }

    override fun deleteFolder(folderId: String) {
        val d = layout()
        FolderTree.deleteFolder(d.folders, folderId) // sub-folders rehomed to root, apps kept
        saveLayout(d)
    }

    override fun moveEntry(entryKey: String, targetFolderId: String?) {
        val d = layout()
        if (entryKey.startsWith(FolderEntry.REF_PREFIX)) {
            val childId = entryKey.removePrefix(FolderEntry.REF_PREFIX)
            // Depth-3 rule: a height-3 subtree fits nowhere deeper.
            if (targetFolderId != null &&
                !FolderTree.canNest(d.folders, childId, targetFolderId)) return
            FolderTree.moveFolder(d.folders, childId, targetFolderId)
        } else {
            for (f in d.folders) f.entries.removeAll { it is FolderEntry.App && it.pkg == entryKey }
            if (targetFolderId != null) {
                FolderTree.byId(d.folders, targetFolderId)?.entries?.add(FolderEntry.App(entryKey))
            }
        }
        saveLayout(d)
    }

    override fun setFolderLocked(folderId: String, locked: Boolean) {
        val d = layout()
        FolderTree.byId(d.folders, folderId)?.locked = locked
        saveLayout(d)
    }

    /** Lock state helpers used by LockRepositoryImpl (same store). */
    fun setAppLocked(packageName: String, locked: Boolean) {
        val cur = lockedApps().toMutableSet()
        if (locked) cur.add(packageName) else cur.remove(packageName)
        saveLockedApps(cur)
    }
    fun isAppLocked(packageName: String): Boolean = packageName in lockedApps()
}
