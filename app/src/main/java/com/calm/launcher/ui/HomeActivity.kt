package com.calm.launcher.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.calm.launcher.core.AppEntry
import com.calm.launcher.core.ArrowPin
import com.calm.launcher.core.Folder
import com.calm.launcher.core.Folders
import com.calm.launcher.core.HomeModel
import com.calm.launcher.core.Wallpapers
import com.calm.launcher.data.AppCatalog
import com.calm.launcher.data.Store

/**
 * The home screen: clock, Folders, Favorites, Recently opened, All apps.
 * D-pad moves between tiles; OK opens; long-press opens the app menu.
 * Locked apps and folders ask for the arrow pattern first.
 */
class HomeActivity : Activity() {

    private lateinit var store: Store
    private lateinit var content: LinearLayout
    private lateinit var clockView: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var apps: List<AppEntry> = emptyList()

    private val clockTick = object : Runnable {
        override fun run() {
            updateClock()
            handler.postDelayed(this, 30_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(32), dp(48), dp(48))
        }
        clockView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 40f
        }
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        })
    }

    override fun onResume() {
        super.onResume()
        apps = AppCatalog.load(this)
        rebuild()
        handler.removeCallbacks(clockTick)
        handler.post(clockTick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(clockTick)
    }

    /** The launcher is home; there is nothing above it to go back to. */
    override fun onBackPressed() {}

    private fun rebuild() {
        val preset = Wallpapers.presetAt(store.wallpaperIndex.toString())
        window.decorView.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(preset.top, preset.bottom)
        )
        content.removeAllViews()
        content.addView(headerRow())

        val installedPkgs = apps.map { it.pkg }.toSet()
        val folders = Folders.visible(store.folders, installedPkgs)
        val favorites = HomeModel.favorites(store.favorites, apps)
        val favoritePkgs = favorites.map { it.pkg }.toSet()
        val recent = HomeModel.recent(store.recent, apps, favoritePkgs)

        if (folders.isNotEmpty()) {
            content.addView(sectionTitle("Folders"))
            content.addView(folderRow(folders))
        }
        content.addView(sectionTitle("Favorites"))
        content.addView(appRow(favorites, "Long-press any app to add it here."))
        content.addView(sectionTitle("Recently opened"))
        content.addView(appRow(recent, "Apps you open will appear here."))
        content.addView(sectionTitle("All apps"))
        content.addView(appRow(HomeModel.allApps(apps), "No launchable apps found."))
    }

    /** Clock on the left, Settings on the right. */
    private fun headerRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        updateClock()
        row.addView(clockView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(this).apply {
            text = "Live TV"
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(dp(20), dp(10), dp(20), dp(10))
            background = tileBackground(focused = false)
            isFocusable = true
            isClickable = true
            setOnClickListener { startActivity(Intent(this@HomeActivity, IptvActivity::class.java)) }
            setOnFocusChangeListener { v, hasFocus -> v.background = tileBackground(focused = hasFocus) }
        })
        row.addView(TextView(this).apply {
            text = "Settings"
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(dp(20), dp(10), dp(20), dp(10))
            background = tileBackground(focused = false)
            isFocusable = true
            isClickable = true
            setOnClickListener { startActivity(Intent(this@HomeActivity, SettingsActivity::class.java)) }
            setOnFocusChangeListener { v, hasFocus ->
                v.background = tileBackground(focused = hasFocus)
            }
        })
        return row
    }

    private fun updateClock() {
        val now = java.util.Calendar.getInstance()
        clockView.text = HomeModel.clockText(
            now.get(java.util.Calendar.HOUR_OF_DAY),
            now.get(java.util.Calendar.MINUTE),
            store.clock24h
        )
    }

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text.uppercase()
        setTextColor(SECONDARY_TEXT)
        textSize = 15f
        letterSpacing = 0.12f
        setPadding(0, dp(32), 0, dp(12))
    }

    private fun folderRow(folders: List<Folder>): View {
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        folders.forEach { folder ->
            line.addView(tileBox(
                title = folder.name,
                subtitle = "${folder.pkgs.size} apps",
                iconFor = { null },
                onClick = { openFolder(folder) },
                onLong = { folderMenu(folder) }
            ))
        }
        return scrollRow(line)
    }

    private fun appRow(items: List<AppEntry>, emptyHint: String): View {
        if (items.isEmpty()) {
            return TextView(this).apply {
                text = emptyHint
                setTextColor(SECONDARY_TEXT)
                textSize = 16f
            }
        }
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        items.forEach { app ->
            line.addView(tileBox(
                title = app.label,
                subtitle = null,
                iconFor = { pkg -> try { packageManager.getApplicationIcon(pkg) } catch (e: Exception) { null } },
                pkg = app.pkg,
                onClick = { launch(app) },
                onLong = { appMenu(app) }
            ))
        }
        return scrollRow(line)
    }

    private fun scrollRow(line: LinearLayout): View = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        clipChildren = false
        clipToPadding = false
        addView(line)
    }

    /** One tile: icon (apps only), label, optional subtitle. Lifts on focus. */
    private fun tileBox(
        title: String,
        subtitle: String?,
        iconFor: (String) -> android.graphics.drawable.Drawable?,
        pkg: String? = null,
        onClick: () -> Unit,
        onLong: () -> Unit
    ): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(12), dp(16), dp(12), dp(14))
            layoutParams = LinearLayout.LayoutParams(dp(168), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, dp(16), 0)
            }
            isFocusable = true
            isClickable = true
            background = tileBackground(focused = false)
        }
        if (pkg != null) {
            box.addView(ImageView(this).apply {
                setImageDrawable(iconFor(pkg))
                layoutParams = LinearLayout.LayoutParams(dp(72), dp(72))
            })
        } else {
            box.addView(TextView(this).apply {
                text = "▦"
                setTextColor(Color.WHITE)
                textSize = 36f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(72), dp(72))
            })
        }
        box.addView(TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 15f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        })
        if (subtitle != null) {
            box.addView(TextView(this).apply {
                text = subtitle
                setTextColor(SECONDARY_TEXT)
                textSize = 13f
                gravity = Gravity.CENTER
            })
        }
        box.setOnFocusChangeListener { v, hasFocus ->
            v.animate().cancel()
            val s = if (hasFocus) FOCUS_SCALE else 1f
            v.animate().scaleX(s).scaleY(s).setDuration(FOCUS_MS)
                .setInterpolator(DecelerateInterpolator()).start()
            v.background = tileBackground(focused = hasFocus)
            v.elevation = if (hasFocus) dp(10).toFloat() else 0f
        }
        box.setOnClickListener { onClick() }
        box.setOnLongClickListener { onLong(); true }
        return box
    }

    private fun tileBackground(focused: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(16).toFloat()
        if (focused) {
            setColor(FOCUS_FILL)
            setStroke(dp(3), ACCENT)
        } else {
            setColor(TILE_FILL)
        }
    }

    // ---- actions ----

    private fun isLocked(token: String) = token in store.locked

    /** Runs [then] now, or after the arrow pattern if the item is locked. */
    private fun guard(token: String, title: String, then: () -> Unit) {
        val pattern = store.arrowPattern
        if (!isLocked(token) || pattern.isEmpty()) {
            then(); return
        }
        Dialogs.arrowPattern(this, "Locked", "Enter the arrow pattern to open $title") { entered ->
            if (ArrowPin.matches(pattern, entered)) then()
            else Toast.makeText(this, "Wrong pattern", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launch(app: AppEntry) = guard("app:${app.pkg}", app.label) {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(app.category)
            .setComponent(ComponentName(app.pkg, app.activity))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
            store.recent = HomeModel.touchRecent(store.recent, app.pkg)
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't open ${app.label}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openFolder(folder: Folder) = guard("folder:${folder.id}", folder.name) {
        val members = apps.filter { it.pkg in folder.pkgs }
        AlertDialog.Builder(this)
            .setTitle(folder.name)
            .setItems(members.map { it.label }.toTypedArray()) { _, which -> launch(members[which]) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun appMenu(app: AppEntry) {
        val inFolder = store.folders.firstOrNull { app.pkg in it.pkgs }
        val favorite = app.pkg in store.favorites
        val lockToken = "app:${app.pkg}"
        val items = mutableListOf<Pair<String, () -> Unit>>()
        items += (if (favorite) "Remove from favorites" else "Add to favorites") to { toggleFavorite(app) }
        items += "Move to folder…" to { chooseFolder(app) }
        if (inFolder != null) items += "Remove from folder" to {
            store.folders = Folders.removeFromAll(store.folders, app.pkg)
            rebuild()
        }
        items += (if (isLocked(lockToken)) "Unlock" else "Lock") to { toggleLock(lockToken, app.label) }
        AlertDialog.Builder(this)
            .setTitle(app.label)
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun toggleFavorite(app: AppEntry) {
        val updated = HomeModel.toggleFavorite(store.favorites, app.pkg)
        if (updated == null) {
            Toast.makeText(this, "Favorites are full (${HomeModel.MAX_FAVORITES})", Toast.LENGTH_SHORT).show()
            return
        }
        store.favorites = updated
        rebuild()
    }

    private fun chooseFolder(app: AppEntry) {
        val folders = store.folders
        val names = folders.map { it.name } + "New folder…"
        AlertDialog.Builder(this)
            .setTitle("Move ${app.label} to")
            .setItems(names.toTypedArray()) { _, which ->
                if (which == folders.size) {
                    Dialogs.text(this, "Folder name", "") { name ->
                        if (name == null) return@text
                        val id = Folders.newId(folders)
                        val created = folders + Folder(id, name, emptyList())
                        store.folders = Folders.moveInto(created, app.pkg, id)
                        rebuild()
                    }
                } else {
                    store.folders = Folders.moveInto(folders, app.pkg, folders[which].id)
                    rebuild()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun folderMenu(folder: Folder) {
        AlertDialog.Builder(this)
            .setTitle(folder.name)
            .setItems(arrayOf("Rename", "Lock / unlock", "Delete folder")) { _, which ->
                when (which) {
                    0 -> Dialogs.text(this, "Rename folder", folder.name) { name ->
                        if (name != null) { store.folders = Folders.rename(store.folders, folder.id, name); rebuild() }
                    }
                    1 -> toggleLock("folder:${folder.id}", folder.name)
                    2 -> Dialogs.confirm(this, "Delete ${folder.name}?",
                        "Its apps stay installed and stay in All apps.", "Delete") {
                        store.folders = Folders.delete(store.folders, folder.id)
                        rebuild()
                    }
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    /** Locks need a pattern to exist first; setting one is a two-step entry. */
    private fun toggleLock(token: String, label: String) {
        if (store.arrowPattern.isEmpty()) {
            Dialogs.setPattern(this, store) { Toast.makeText(this, "Locked $label", Toast.LENGTH_SHORT).show(); lock(token) }
            return
        }
        guard(token, label) {
            store.locked = if (isLocked(token)) store.locked - token else store.locked + token
            rebuild()
        }
    }

    private fun lock(token: String) {
        if (!isLocked(token)) store.locked = store.locked + token
        rebuild()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val TILE_FILL = 0x14FFFFFF
        const val FOCUS_FILL = 0x38000000
        const val ACCENT = 0xFF7FB8FF.toInt()
        const val SECONDARY_TEXT = 0xFF9AA4B0.toInt()
        const val FOCUS_SCALE = 1.06f
        const val FOCUS_MS = 160L
    }
}
