package com.mizan.launcher.core

/**
 * Per-profile home-screen layout: favorites order, hidden apps, folders,
 * All-Apps custom order, and row order/visibility.
 *
 * Pure data + a pure codec (no Android imports) so persistence is verified
 * in the headless sim: encode -> decode must be an identity, and two
 * profiles' data must never bleed into each other. Android side stores the
 * encoded string in a profile-namespaced SharedPreferences key.
 *
 * v1.5: folders gained STABLE IDS and typed entries — a folder can hold
 * apps AND other folders (nesting, max depth [FolderTree.MAX_DEPTH]).
 * The codec writes `folder2=` lines carrying the id; v1.0–v1.4 `folder=`
 * lines (which addressed folders positionally) still decode, with ids
 * assigned in line order and contents unchanged (migration).
 */

/**
 * One item inside a folder: either an app (by package) or a reference to
 * another folder (by its stable id). The [key] form is what the layout
 * codec stores and what move-mode sessions order: a package name for an
 * app, `@f:<id>` for a folder reference. Package names can never start
 * with `@f:` (they contain only letters, digits, dots and underscores),
 * so the two key spaces never collide.
 */
sealed class FolderEntry {

    abstract fun key(): String

    data class App(val pkg: String) : FolderEntry() {
        override fun key(): String = pkg
    }

    data class FolderRef(val folderId: String) : FolderEntry() {
        override fun key(): String = REF_PREFIX + folderId
    }

    companion object {
        const val REF_PREFIX = "@f:"

        /** Parse a stored/session key back into a typed entry. */
        fun fromKey(key: String): FolderEntry =
            if (key.startsWith(REF_PREFIX)) FolderRef(key.substring(REF_PREFIX.length))
            else App(key)
    }
}

/**
 * One folder: a STABLE [id] (assigned once, never reused positionally),
 * a display name, an ordered list of typed [entries] (apps and
 * sub-folders), and a lock flag (v1.3). A locked folder asks for the
 * arrow-lock pattern before it opens (see [FolderLogic.folderGate]).
 */
data class Folder(
    var id: String,
    var name: String,
    val entries: MutableList<FolderEntry> = mutableListOf(),
    var locked: Boolean = false
) {
    /** App packages directly inside this folder (not recursive). */
    val appPackages: List<String>
        get() = entries.filterIsInstance<FolderEntry.App>().map { it.pkg }

    /** Ids of the folders directly inside this folder. */
    val subFolderIds: List<String>
        get() = entries.filterIsInstance<FolderEntry.FolderRef>().map { it.folderId }

    /** Entry keys in order — the list move-mode sessions reorder. */
    fun entryKeys(): List<String> = entries.map { it.key() }
}

/** What tapping a folder tile should do, decided purely (sim-tested). */
enum class FolderGate {
    /** Folder isn't locked: open it straight away. */
    OPEN,
    /** Folder is locked and a pattern exists: ask for the pattern first. */
    PROMPT_PATTERN,
    /**
     * Folder is locked but NO pattern exists (e.g. the pattern was
     * cleared in Settings after locking). Never lock the user out:
     * run the set-pattern flow first, then open.
     */
    PROMPT_SET_PATTERN
}

/**
 * Folder membership + open-gate rules, kept pure so the sim can pin them:
 * - An app inside ANY folder (at ANY depth) does not also appear in
 *   All Apps. Removing it from its folder makes it reappear.
 * - The Favorites row is UNAFFECTED: a favorite may also live in a
 *   folder (Favorites is an explicit shortcut list, not a catalogue).
 */
object FolderLogic {

    /**
     * Every package that sits inside at least one folder. Because each
     * folder stores its own direct app entries, the union over all
     * folders IS the recursive membership set (an app three levels deep
     * is a direct entry of the depth-3 folder holding it).
     */
    fun folderPackages(folders: List<Folder>): Set<String> =
        FolderTree.allFolderPackages(folders)

    /**
     * The All Apps catalogue: the caller's ordered list minus hidden
     * apps minus anything inside a folder. Order is preserved; an app
     * in two folders is still excluded exactly once.
     */
    fun allAppsVisible(
        orderedPkgs: List<String>,
        hidden: Set<String>,
        folders: List<Folder>
    ): List<String> {
        val inFolders = folderPackages(folders)
        return orderedPkgs.filter { it !in hidden && it !in inFolders }
    }

    /** Gate decision for opening [folder]. */
    fun folderGate(folder: Folder, patternSet: Boolean): FolderGate = when {
        !folder.locked -> FolderGate.OPEN
        patternSet -> FolderGate.PROMPT_PATTERN
        else -> FolderGate.PROMPT_SET_PATTERN
    }
}

/**
 * The folder TREE (v1.5) — pure structural rules over the flat folder
 * list. Parentage is derived, never stored: folder X is the parent of
 * folder Y iff X's entries contain a FolderRef to Y. Invariants the
 * tree relies on (enforced by [prune] at load and by [canNest] at edit
 * time): every folder has AT MOST one parent, there are no cycles, and
 * nesting never exceeds [MAX_DEPTH] levels below the home root.
 */
object FolderTree {

    /** Max folder depth below home: root folder = 1, its child = 2, grandchild = 3. */
    const val MAX_DEPTH = 3

    fun byId(folders: List<Folder>, id: String): Folder? = folders.firstOrNull { it.id == id }

    /** Smallest unused `f<N>` id — deterministic, so codec migration is testable. */
    fun freshId(folders: List<Folder>): String {
        val used = folders.mapTo(HashSet()) { it.id }
        var n = 1
        while ("f$n" in used) n++
        return "f$n"
    }

    /** The folder directly containing a reference to [id]; null = home root. */
    fun parentOf(folders: List<Folder>, id: String): Folder? =
        folders.firstOrNull { f -> f.subFolderIds.contains(id) }

    /** Folders nobody references: the ones the home Folders row shows. */
    fun rootFolders(folders: List<Folder>): List<Folder> {
        val claimed = HashSet<String>()
        for (f in folders) claimed.addAll(f.subFolderIds)
        return folders.filter { it.id !in claimed }
    }

    /** Parent chain of [id], root-first (empty for a root folder). Cycle-safe. */
    fun ancestors(folders: List<Folder>, id: String): List<Folder> {
        val chain = ArrayList<Folder>()
        val seen = HashSet<String>()
        var cur = parentOf(folders, id)
        while (cur != null && seen.add(cur.id)) {
            chain.add(0, cur)
            cur = parentOf(folders, cur.id)
        }
        return chain
    }

    /** Depth below home: a root folder is 1, its child 2, grandchild 3. */
    fun depth(folders: List<Folder>, id: String): Int =
        if (byId(folders, id) == null) 0 else ancestors(folders, id).size + 1

    /** All folder ids nested inside [id], transitively. Cycle-safe. */
    fun descendants(folders: List<Folder>, id: String): Set<String> {
        val out = LinkedHashSet<String>()
        val start = byId(folders, id) ?: return out
        val stack = ArrayDeque<String>()
        stack.addAll(start.subFolderIds)
        while (stack.isNotEmpty()) {
            val cur = stack.removeLast()
            if (!out.add(cur)) continue
            byId(folders, cur)?.let { stack.addAll(it.subFolderIds) }
        }
        return out
    }

    /**
     * Height of the subtree rooted at [id]: a leaf folder is 1, a
     * folder holding only leaf folders is 2, and so on. Cycle-safe
     * (a folder already on the current path contributes nothing).
     */
    fun subtreeHeight(folders: List<Folder>, id: String): Int {
        fun height(fid: String, onPath: Set<String>): Int {
            if (fid in onPath) return 0
            val f = byId(folders, fid) ?: return 0
            var best = 0
            for (sub in f.subFolderIds) best = maxOf(best, height(sub, onPath + fid))
            return 1 + best
        }
        return height(id, emptySet())
    }

    /**
     * May folder [childId] be placed inside folder [parentId]?
     * Legal iff: both exist, child != parent, child is NOT an ancestor
     * of parent (that would close a cycle), and the move keeps every
     * level within [MAX_DEPTH]: depth(parent) + height(child's
     * subtree) <= MAX_DEPTH. Moving a folder REPLACES its parent (it
     * leaves wherever it was) — see [moveFolder].
     */
    fun canNest(folders: List<Folder>, childId: String, parentId: String): Boolean {
        if (childId == parentId) return false
        if (byId(folders, childId) == null) return false
        if (byId(folders, parentId) == null) return false
        if (ancestors(folders, parentId).any { it.id == childId }) return false
        return depth(folders, parentId) + subtreeHeight(folders, childId) <= MAX_DEPTH
    }

    /** Every folder [childId] may legally be moved into. */
    fun legalParents(folders: List<Folder>, childId: String): List<Folder> =
        folders.filter { canNest(folders, childId, it.id) }

    /**
     * Move folder [childId] into [newParentId] (null = home root): the
     * reference is removed from its current parent (if any) and added
     * to the new parent's entries. The caller checks [canNest] first
     * for a non-null parent; moving to the root is always legal.
     */
    fun moveFolder(folders: MutableList<Folder>, childId: String, newParentId: String?) {
        for (f in folders) {
            f.entries.removeAll { it is FolderEntry.FolderRef && it.folderId == childId }
        }
        if (newParentId != null) {
            byId(folders, newParentId)?.entries?.add(FolderEntry.FolderRef(childId))
        }
    }

    /**
     * Reorder the ROOT folders to [rootOrder] (v1.7: the iPad grid's
     * folders segment is move-mode reorderable). Only the slots the
     * roots currently occupy in the flat list are permuted — nested
     * folders keep their exact positions and parentage, so a root
     * reorder can never disturb the tree. Ids in [rootOrder] that
     * aren't roots are ignored; roots missing from [rootOrder] keep
     * their relative order after the named ones (defensive — a
     * session order always names every root).
     */
    fun reorderRoots(folders: MutableList<Folder>, rootOrder: List<String>) {
        val roots = rootFolders(folders)
        if (roots.size < 2) return
        val byId = roots.associateBy { it.id }
        val seen = HashSet<String>()
        val ordered = ArrayList<Folder>()
        for (id in rootOrder) {
            val f = byId[id]
            if (f != null && seen.add(id)) ordered.add(f)
        }
        for (f in roots) if (seen.add(f.id)) ordered.add(f)
        val rootIds = roots.mapTo(HashSet()) { it.id }
        var next = 0
        for (i in folders.indices) {
            if (folders[i].id in rootIds) folders[i] = ordered[next++]
        }
    }

    /**
     * Delete folder [id] ONLY: references to it are removed from its
     * parent, and its own sub-folders are NOT deleted — their only
     * parent reference lived in the deleted folder's entries, so they
     * become root folders (back on the home Folders row). Its apps
     * simply stop being folder members (they reappear in All Apps).
     * Contents are never cascade-deleted.
     */
    fun deleteFolder(folders: MutableList<Folder>, id: String) {
        for (f in folders) {
            f.entries.removeAll { it is FolderEntry.FolderRef && it.folderId == id }
        }
        folders.removeAll { it.id == id }
    }

    /** Root-first chain of [id]'s parents, then the folder itself. */
    fun breadcrumb(folders: List<Folder>, id: String): List<Folder> {
        val self = byId(folders, id) ?: return emptyList()
        return ancestors(folders, id) + self
    }

    /** Every app package inside any folder, at any depth (see [FolderLogic.folderPackages]). */
    fun allFolderPackages(folders: List<Folder>): Set<String> {
        val out = HashSet<String>()
        for (f in folders) out.addAll(f.appPackages)
        return out
    }

    /**
     * Structural clean-up applied when a layout loads (the codec calls
     * this): duplicate entry keys within one folder are dropped,
     * references to folders that don't exist are dropped, self
     * references are dropped, a folder referenced from two parents
     * keeps only the FIRST parent (list order), and any remaining
     * detached cycle is broken by detaching its first folder (list
     * order) to the home root. Deterministic; never throws.
     */
    fun prune(folders: MutableList<Folder>) {
        val ids = folders.mapTo(HashSet()) { it.id }
        // (a) Per-folder entries: drop duplicate keys, refs to unknown
        // folders, and self-references.
        for (f in folders) {
            val seenKeys = HashSet<String>()
            f.entries.removeAll { e ->
                when (e) {
                    is FolderEntry.App -> !seenKeys.add(e.key())
                    is FolderEntry.FolderRef ->
                        e.folderId !in ids || e.folderId == f.id || !seenKeys.add(e.key())
                }
            }
        }
        // (b) Single parent: the first folder (list order) referencing
        // a child keeps it; later references are dropped.
        val claimedBy = HashMap<String, String>()
        for (f in folders) {
            f.entries.removeAll { e ->
                if (e is FolderEntry.FolderRef) {
                    claimedBy.putIfAbsent(e.folderId, f.id) != null
                } else false
            }
        }
        // (c) Detached cycles: after (b) every folder has at most one
        // parent, so a folder unreachable from any root must sit on a
        // cycle. Detach the first stuck folder (list order) to root
        // and repeat — each pass frees at least one folder.
        while (true) {
            val reachable = HashSet<String>()
            val stack = ArrayDeque<String>()
            for (root in rootFolders(folders)) if (reachable.add(root.id)) stack.add(root.id)
            while (stack.isNotEmpty()) {
                val cur = byId(folders, stack.removeLast()) ?: continue
                for (sub in cur.subFolderIds) if (reachable.add(sub)) stack.add(sub)
            }
            val stuck = folders.firstOrNull { it.id !in reachable } ?: break
            parentOf(folders, stuck.id)?.entries?.removeAll {
                it is FolderEntry.FolderRef && it.folderId == stuck.id
            }
        }
    }
}

/**
 * Row-order maintenance (v1.4). Stored layouts from older versions do
 * not contain the rows added later ("recents", "nowplaying"), and the
 * home screen renders exactly the stored order — so a missing key
 * would mean a missing row forever. [normalize] inserts any missing
 * known row at its canonical position (after its anchor row when the
 * anchor is present, else at the end), preserves the user's custom
 * order otherwise, and is idempotent.
 */
object RowOrder {

    /** Canonical (row, anchor-it-follows) pairs, in insertion order. */
    private val INSERTIONS: List<Pair<String, String>> = listOf(
        "recents" to "favorites",
        "nowplaying" to "folders",
        // v2.0: Most Used sits under Recently Used; Live TV under inputs.
        "mostused" to "recents",
        "livetv" to "inputs"
    )

    fun normalize(order: List<String>): List<String> {
        val out = order.toMutableList()
        for ((key, anchor) in INSERTIONS) {
            if (key in out) continue
            val anchorIdx = out.indexOf(anchor)
            if (anchorIdx >= 0) out.add(anchorIdx + 1, key) else out.add(key)
        }
        return out
    }
}

/** Everything that defines how one profile's home screen looks. */
data class LayoutData(
    val favorites: MutableList<String> = mutableListOf(),
    val hidden: MutableSet<String> = mutableSetOf(),
    val folders: MutableList<Folder> = mutableListOf(),
    /** Custom All-Apps order; packages not in the list sort after, alphabetically by caller. */
    val allAppsOrder: MutableList<String> = mutableListOf(),
    /** Row keys in display order. Known keys: favorites, recents, mostused,
     * folders, nowplaying, continue, inputs, livetv, apps. */
    val rowOrder: MutableList<String> = mutableListOf(
        "favorites", "recents", "mostused", "folders", "nowplaying", "continue", "inputs", "livetv", "apps"
    ),
    val rowVisible: MutableMap<String, Boolean> = mutableMapOf(
        "favorites" to true, "recents" to true, "mostused" to true, "folders" to true,
        "nowplaying" to true, "continue" to true, "inputs" to true, "livetv" to true, "apps" to true
    ),
    /** Status bar configuration (v1.1). Defaults = v1.0 behaviour. */
    var statusBar: StatusBarConfig = StatusBarConfig()
) {
    fun isRowVisible(key: String): Boolean = rowVisible[key] ?: true

    /** Move a package one step within a list; returns false at the edges. */
    fun move(list: MutableList<String>, pkg: String, dir: Int): Boolean {
        val i = list.indexOf(pkg)
        if (i < 0) return false
        val j = i + dir
        if (j < 0 || j >= list.size) return false
        val tmp = list[i]; list[i] = list[j]; list[j] = tmp
        return true
    }
}

/**
 * Line-based codec. Field separator '|', list separator ',', both escaped
 * in values (% first, then | , and newline) so any package name or folder
 * name round-trips exactly.
 *
 * Folder lines: v1.5 writes `folder2=<id>|<name>|<entry keys>|<lock 0/1>`
 * where entry keys are package names and `@f:<id>` folder references.
 * Legacy `folder=<name>|<apps>|<lock 0/1>` lines (v1.0–v1.4) decode with
 * ids assigned in line order (smallest unused `f<N>`, skipping ids taken
 * by folder2 lines) and their apps as plain app entries — contents
 * unchanged. Every decode ends with [FolderTree.prune], so dangling or
 * cyclic references from older/hand-edited data can't survive a load.
 */
object LayoutCodec {

    private fun esc(s: String): String = s
        .replace("%", "%25")
        .replace("|", "%7C")
        .replace(",", "%2C")
        .replace("\n", "%0A")

    private fun unesc(s: String): String = s
        .replace("%0A", "\n")
        .replace("%2C", ",")
        .replace("%7C", "|")
        .replace("%25", "%")

    private fun list(v: List<String>): String = v.joinToString(",") { esc(it) }
    private fun parseList(v: String): MutableList<String> =
        if (v.isEmpty()) mutableListOf() else v.split(",").map { unesc(it) }.toMutableList()

    fun encode(d: LayoutData): String {
        val sb = StringBuilder()
        sb.append("v=2\n")
        sb.append("fav=").append(list(d.favorites)).append('\n')
        sb.append("hid=").append(list(d.hidden.sorted())).append('\n')
        sb.append("ord=").append(list(d.allAppsOrder)).append('\n')
        sb.append("rows=").append(list(d.rowOrder)).append('\n')
        for ((k, v) in d.rowVisible.toSortedMap()) {
            sb.append("rowvis.").append(esc(k)).append('=').append(if (v) "1" else "0").append('\n')
        }
        for (f in d.folders) {
            // v1.5: id first, then name, then typed entry keys, then the
            // v1.3 lock flag as the trailing segment.
            sb.append("folder2=").append(esc(f.id)).append('|').append(esc(f.name))
                .append('|').append(list(f.entryKeys()))
                .append('|').append(if (f.locked) '1' else '0').append('\n')
        }
        // Status bar config (v1.1+). Older decoders ignore unknown
        // lines, and this decoder defaults it when absent, so the
        // codec stays backward compatible both ways.
        sb.append("status=").append(StatusBarCodec.encode(d.statusBar)).append('\n')
        return sb.toString()
    }

    /** Decode; any malformed line is skipped (fail-open), never throws. */
    fun decode(s: String?): LayoutData {
        val d = LayoutData()
        if (s.isNullOrBlank()) return d
        var sawRows = false
        // Folder lines are collected first and materialised after the
        // pass: legacy lines carry no id, and the ids they are assigned
        // must skip ids already used by folder2 lines anywhere in the
        // string, whatever order the lines appear in.
        val rawFolders = ArrayList<RawFolder>()
        for (line in s.lines()) {
            try {
                when {
                    line.startsWith("fav=") -> { d.favorites.clear(); d.favorites.addAll(parseList(line.drop(4))) }
                    line.startsWith("hid=") -> { d.hidden.clear(); d.hidden.addAll(parseList(line.drop(4))) }
                    line.startsWith("ord=") -> { d.allAppsOrder.clear(); d.allAppsOrder.addAll(parseList(line.drop(4))) }
                    line.startsWith("rows=") -> {
                        val rows = parseList(line.drop(5))
                        if (rows.isNotEmpty()) { d.rowOrder.clear(); d.rowOrder.addAll(rows); sawRows = true }
                    }
                    line.startsWith("rowvis.") -> {
                        val rest = line.drop(7)
                        val eq = rest.lastIndexOf('=')
                        if (eq > 0) d.rowVisible[unesc(rest.substring(0, eq))] = rest.substring(eq + 1) == "1"
                    }
                    line.startsWith("status=") -> {
                        d.statusBar = StatusBarCodec.decode(line.drop(7))
                    }
                    line.startsWith("folder2=") -> {
                        parseFolderLine(line.drop(8), withId = true)?.let { rawFolders.add(it) }
                    }
                    line.startsWith("folder=") -> {
                        parseFolderLine(line.drop(7), withId = false)?.let { rawFolders.add(it) }
                    }
                }
            } catch (e: Exception) {
                // Fail-open: one bad line must not lose the whole layout.
            }
        }
        if (!sawRows) {
            // Keep the built-in default row order (already in LayoutData).
        }
        // Assign ids to legacy folders: smallest unused f<N>, in the
        // order their lines appeared (migration is deterministic).
        val usedIds = rawFolders.mapNotNull { it.id }.toMutableSet()
        var nextN = 1
        fun freshLegacyId(): String {
            while ("f$nextN" in usedIds) nextN++
            val id = "f$nextN"
            usedIds.add(id)
            return id
        }
        for (raw in rawFolders) {
            d.folders.add(
                Folder(
                    raw.id ?: freshLegacyId(),
                    raw.name,
                    raw.entries.map { FolderEntry.fromKey(it) }.toMutableList(),
                    raw.locked
                )
            )
        }
        FolderTree.prune(d.folders)
        return d
    }

    /** One parsed folder line, before ids are finalised. */
    private data class RawFolder(
        val id: String?,
        val name: String,
        val entries: List<String>,
        val locked: Boolean
    )

    /**
     * Parse one folder line's payload. Layout: [id|]name|entries[|lock].
     * The entries segment runs to the end, except for the trailing
     * "|0"/"|1" lock segment (v1.3+); entry keys never contain a raw
     * '|' (escaped), so the last '|' unambiguously starts the flag.
     */
    private fun parseFolderLine(rest: String, withId: Boolean): RawFolder? {
        var remaining = rest
        var id: String? = null
        if (withId) {
            val bar = remaining.indexOf('|')
            if (bar < 0) return null
            id = unesc(remaining.substring(0, bar)).ifEmpty { null }
            remaining = remaining.substring(bar + 1)
        }
        val bar = remaining.indexOf('|')
        if (bar < 0) return null
        val name = unesc(remaining.substring(0, bar))
        var entriesPart = remaining.substring(bar + 1)
        var locked = false
        val lastBar = entriesPart.lastIndexOf('|')
        if (lastBar >= 0) {
            val tail = entriesPart.substring(lastBar + 1)
            if (tail == "0" || tail == "1") {
                locked = tail == "1"
                entriesPart = entriesPart.substring(0, lastBar)
            }
        }
        return RawFolder(id, name, parseList(entriesPart), locked)
    }
}
