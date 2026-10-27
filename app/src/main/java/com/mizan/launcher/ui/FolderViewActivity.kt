package com.mizan.launcher.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout

/**
 * Folder open view (MAP §1.10): breadcrumb root → folder, grid of entries,
 * sub-folder entries move like apps, parent order persisted with the move.
 * Depth max 3; a folder cannot contain itself; an ancestor cannot move
 * under its descendant; deleting rehomes sub-folders to root, keeps apps.
 * Locked folder with a pattern prompts; without a pattern prompts to set one.
 */
class FolderViewActivity : Activity() {
    companion object {
        const val EXTRA_FOLDER_ID = "folder_id"
        fun open(ctx: Context, folderId: String?) {
            val i = Intent(ctx, FolderViewActivity::class.java)
            i.putExtra(EXTRA_FOLDER_ID, folderId ?: ""); ctx.startActivity(i)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val folderId = intent.getStringExtra(EXTRA_FOLDER_ID) ?: ""
        val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL
        setContentView(Ui.rootScrollView(this, col))
        Ui.runBg {
            val folders = try { UiWiring.apps?.getFolders() ?: emptyList() } catch (e: Throwable) { emptyList() }
            val folder = folders.firstOrNull { it.id == folderId }
            Ui.runMain {
                if (folder == null) {
                    col.addView(Ui.sectionTitle(this, "Folders"))
                    folders.forEach { f ->
                        col.addView(Ui.button(this, "▣ ${f.name}") { open(this, f.id) })
                    }
                    col.addView(Ui.button(this, "+ New folder") {
                        KeyboardDialog.show(this, "Folder name", maxLen = 40) { name ->
                            Ui.runBg { UiWiring.apps?.createFolder(name); Ui.runMain { recreate() } }
                        }
                    })
                    return@runMain
                }
                val openIt = {
                    col.addView(Ui.sectionTitle(this, "Root → ${folder.name}"))
                    folder.entries.forEach { entry ->
                        val label = if (entry.kind == "folder") "▣ ${entry.label}" else entry.label
                        col.addView(Ui.button(this, label) {
                            if (entry.kind == "folder") open(this, entry.ref)
                            else Ui.launchApp(this, entry.ref)
                        })
                    }
                    col.addView(Ui.button(this, "Rename folder") {
                        KeyboardDialog.show(this, "Folder name", folder.name, maxLen = 40) { name ->
                            Ui.runBg { UiWiring.apps?.renameFolder(folder.id, name); Ui.runMain { recreate() } }
                        }
                    })
                    col.addView(Ui.button(this, "Delete folder") {
                        Ui.confirm(this, "Delete folder", "Sub-folders move to root; apps are kept.") {
                            Ui.runBg { UiWiring.apps?.deleteFolder(folder.id); Ui.runMain { finish() } }
                        }
                    })
                }
                if (folder.locked) {
                    if (UiWiring.lock?.isPatternSet() == true) PatternGate.run(this) { openIt() }
                    else {
                        Ui.toast(this, "This folder is locked — set a pattern first")
                        ArrowPinDialog.showSet(this, { encoded ->
                            UiWiring.lock?.setPattern(encoded)
                            Ui.runBg { UiWiring.apps?.setFolderLocked(folder.id, true) }
                            openIt()
                        })
                    }
                } else openIt()
            }
        }
    }
}
