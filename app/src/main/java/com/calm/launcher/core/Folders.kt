package com.calm.launcher.core

/** A named group of apps. [pkgs] keeps its order. */
data class Folder(val id: String, val name: String, val pkgs: List<String>)

/**
 * Folders: one level deep, each app in at most one folder.
 * Stored one folder per line as "id<TAB>name<TAB>pkg,pkg". Names have tabs
 * and line breaks replaced; bad lines are skipped, never fatal.
 */
object Folders {
    const val MAX_NAME = 24

    fun cleanName(raw: String): String =
        raw.replace(Regex("[\\t\\n\\r]"), " ").trim().take(MAX_NAME)

    fun encode(folders: List<Folder>): String =
        folders.joinToString("\n") { f ->
            listOf(f.id, cleanName(f.name), f.pkgs.joinToString(",")).joinToString("\t")
        }

    fun decode(raw: String?): List<Folder> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split('\n').mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size != 3 || parts[0].isBlank() || parts[1].isBlank()) return@mapNotNull null
            val pkgs = parts[2].split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            Folder(parts[0].trim(), parts[1], pkgs)
        }.distinctBy { it.id }
    }

    /** A new folder id that no existing folder uses. */
    fun newId(existing: List<Folder>): String {
        var n = existing.size + 1
        while (existing.any { it.id == "f$n" }) n++
        return "f$n"
    }

    /** Put [pkg] in folder [id], taking it out of any other folder first. */
    fun moveInto(folders: List<Folder>, pkg: String, id: String): List<Folder> =
        folders.map { f ->
            val without = f.pkgs - pkg
            if (f.id == id) f.copy(pkgs = without + pkg) else f.copy(pkgs = without)
        }

    /** Take [pkg] out of whichever folder holds it. */
    fun removeFromAll(folders: List<Folder>, pkg: String): List<Folder> =
        folders.map { it.copy(pkgs = it.pkgs - pkg) }

    fun delete(folders: List<Folder>, id: String): List<Folder> = folders.filter { it.id != id }

    fun rename(folders: List<Folder>, id: String, name: String): List<Folder> =
        folders.map { if (it.id == id) it.copy(name = cleanName(name).ifEmpty { it.name }) else it }

    /** Folders with no installed apps left are hidden; uninstalled apps are dropped. */
    fun visible(folders: List<Folder>, installedPkgs: Set<String>): List<Folder> =
        folders.map { f -> f.copy(pkgs = f.pkgs.filter { it in installedPkgs }) }
            .filter { it.pkgs.isNotEmpty() }
}
