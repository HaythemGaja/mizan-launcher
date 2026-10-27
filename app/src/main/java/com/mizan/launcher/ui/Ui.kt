package com.mizan.launcher.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.mizan.launcher.core.Design

/** Shared UI helpers (old ui/Ui.kt equivalent, rebuilt). No data/app imports. */
object Ui {
    fun dp(ctx: Context, v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()
    fun sp(ctx: Context, v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, ctx.resources.displayMetrics)

    fun bg(color: Int, radiusDp: Int, ctx: Context): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(color); d.cornerRadius = dp(ctx, radiusDp).toFloat()
        return d
    }
    fun focusBg(ctx: Context): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(Design.FOCUS_FILL); d.cornerRadius = dp(ctx, Design.RADIUS_TILE).toFloat()
        d.setStroke(dp(ctx, Design.FOCUS_STROKE_DP), Color.WHITE)
        return d
    }
    fun tileBg(ctx: Context): GradientDrawable = bg(Design.TILE_FILL, Design.RADIUS_TILE, ctx)

    /** Focusable view with visible focus state + slight scale (Design.FOCUS_SCALE). */
    fun focusable(v: View): View {
        v.isFocusable = true; v.isFocusableInTouchMode = false
        val normal = v.background
        v.setOnFocusChangeListener { view, focused ->
            view.scaleX = if (focused) Design.FOCUS_SCALE else 1f
            view.scaleY = if (focused) Design.FOCUS_SCALE else 1f
            if (focused) view.background = focusBg(view.context) else view.background = normal
        }
        return v
    }

    fun text(ctx: Context, s: String, sizeSp: Float = 18f, color: Int = Color.WHITE, bold: Boolean = false): TextView {
        val t = TextView(ctx); t.text = s; t.textSize = sizeSp; t.setTextColor(color)
        if (bold) t.setTypeface(null, android.graphics.Typeface.BOLD)
        return t
    }
    fun button(ctx: Context, label: String, onClick: () -> Unit): Button {
        val b = Button(ctx); b.text = label
        b.setOnClickListener { onClick() }
        focusable(b); return b
    }
    fun row(ctx: Context, horizontal: Boolean = true): LinearLayout {
        val l = LinearLayout(ctx); l.orientation = if (horizontal) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        return l
    }
    fun scroll(ctx: Context, child: View): ScrollView {
        val s = ScrollView(ctx); s.addView(child); return s
    }
    fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    fun runBg(block: () -> Unit) { Thread(block).start() }
    fun runMain(block: () -> Unit) { Handler(Looper.getMainLooper()).post(block) }

    /** Simple list menu dialog; D-pad navigable via AlertDialog list. */
    fun menu(ctx: Context, title: String, items: List<Pair<String, () -> Unit>>) {
        if (items.isEmpty()) return
        AlertDialog.Builder(ctx).setTitle(title)
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .setNegativeButton("Cancel", null).show()
    }
    fun confirm(ctx: Context, title: String, message: String, onYes: () -> Unit) {
        AlertDialog.Builder(ctx).setTitle(title).setMessage(message)
            .setPositiveButton("Yes") { _, _ -> onYes() }
            .setNegativeButton("Cancel", null).show()
    }
    fun sectionTitle(ctx: Context, title: String): TextView {
        val t = text(ctx, title, 22f, Color.WHITE, true)
        t.setPadding(dp(ctx, Design.CONTENT_MARGIN_DP), dp(ctx, 8), 0, dp(ctx, 8)); return t
    }
    fun stateText(ctx: Context, s: String): TextView {
        val t = text(ctx, s, 18f, 0xFFAAAAAA.toInt()); t.setPadding(dp(ctx, Design.CONTENT_MARGIN_DP), dp(ctx, 8), 0, dp(ctx, 8)); return t
    }
    /** Guarded section build: a throwing section yields an error tile, never a dead screen. */
    fun guarded(ctx: Context, build: () -> View): View = try { build() } catch (e: Throwable) {
        val t = stateText(ctx, "This section hit a snag — tap to retry")
        t.setOnClickListener { UiWiring.diagnostics?.noteError("section: " + e.javaClass.simpleName) }
        t
    }
    fun launchApp(ctx: Context, packageName: String): Boolean {
        val intent = ctx.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try { ctx.startActivity(intent); true } catch (e: Throwable) { false }
    }
    fun openDetails(ctx: Context, tmdbId: Long, kind: String, title: String = "", poster: String = "") {
        val i = Intent(ctx, DetailsActivity::class.java)
        i.putExtra(DetailsActivity.EXTRA_ID, tmdbId); i.putExtra(DetailsActivity.EXTRA_TYPE, kind)
        i.putExtra(DetailsActivity.EXTRA_TITLE, title); i.putExtra(DetailsActivity.EXTRA_POSTER, poster)
        ctx.startActivity(i)
    }
    fun openPlayer(ctx: Context, item: com.mizan.launcher.domain.PlayItem) {
        UiWiring.onPlay?.invoke(item) ?: run {
            val i = Intent(ctx, PlayerActivity::class.java)
            i.putExtra(PlayerActivity.EXTRA_ID, item.id)
            i.putExtra(PlayerActivity.EXTRA_TITLE, item.title)
            i.putExtra(PlayerActivity.EXTRA_URL, item.streamUrl)
            i.putExtra(PlayerActivity.EXTRA_KIND, item.kind.name)
            i.putExtra(PlayerActivity.EXTRA_MARK, item.libraryMarkToken ?: "")
            item.tmdbId?.let { i.putExtra(PlayerActivity.EXTRA_TMDB, it) }
            item.season?.let { i.putExtra(PlayerActivity.EXTRA_SEASON, it) }
            item.episode?.let { i.putExtra(PlayerActivity.EXTRA_EPISODE, it) }
            ctx.startActivity(i)
        }
    }
    fun isDpadCenter(keyCode: Int) = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
    fun rootScrollView(ctx: Context, content: LinearLayout): View {
        content.setPadding(0, dp(ctx, 16), 0, dp(ctx, 48))
        return scroll(ctx, content)
    }
}
