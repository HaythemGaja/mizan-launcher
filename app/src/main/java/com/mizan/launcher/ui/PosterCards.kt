package com.mizan.launcher.ui

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.mizan.launcher.core.Design
import com.mizan.launcher.domain.HomeCard
import com.mizan.launcher.domain.InstalledApp
import com.mizan.launcher.domain.TmdbImages

/** Poster / app-tile cards. Posters render complete, never centre-cropped (v2.9 rule). */
object PosterCards {
    /** Portrait 2:3 card for Up Next / Missed / For You / Library (MAP v3.9). */
    fun homeCard(ctx: Context, card: HomeCard, onClick: () -> Unit, onLong: (() -> Unit)? = null): View {
        val w = Ui.dp(ctx, 180); val h = w * 3 / 2
        val col = LinearLayout(ctx); col.orientation = LinearLayout.VERTICAL
        col.layoutParams = LinearLayout.LayoutParams(w, LinearLayout.LayoutParams.WRAP_CONTENT)
        val img = ImageView(ctx); img.layoutParams = LinearLayout.LayoutParams(w, h)
        img.scaleType = ImageView.ScaleType.FIT_CENTER
        img.setBackgroundColor(0xFF222222.toInt())
        img.contentDescription = card.title
        // No network image loader in this layer: poster URL is exposed for the
        // app layer / future image loader; placeholder shows title initial.
        img.setImageDrawable(null)
        // Phase 2D: the app layer's image loader (UiWiring.imageLoader)
        // fills the poster; without it the title-initial placeholder stands.
        try { posterUrl(card)?.let { url -> UiWiring.imageLoader?.invoke(img, url) } } catch (e: Throwable) { }
        val label = Ui.text(ctx, card.title, 16f); label.maxLines = 2
        val sub = Ui.text(ctx, card.countdownLabel.ifBlank { card.progress?.let { "${it.watchedCount}/${it.totalCount}" } ?: "" }, 14f, 0xFFAAAAAA.toInt())
        col.addView(img); col.addView(label); if (sub.text.isNotEmpty()) col.addView(sub)
        Ui.focusable(col); col.setOnClickListener { onClick() }
        if (onLong != null) col.setOnLongClickListener { onLong(); true }
        col.setPadding(Ui.dp(ctx, 6), Ui.dp(ctx, 6), Ui.dp(ctx, 6), Ui.dp(ctx, 6))
        return col
    }
    fun posterUrl(card: HomeCard): String? = TmdbImages.posterUrl(card.posterPath)
        ?: TmdbImages.backdropUrl(card.backdropPath)

    /** App tile: icon shape + size follow profile settings passed in; label only when focused. */
    fun appTile(ctx: Context, app: InstalledApp, iconShape: String = "rounded", size: String = "standard", onClick: () -> Unit, onLong: (() -> Unit)? = null): View {
        val px = when (size) { "compact" -> 96; "large" -> 160; else -> 128 }
        val col = LinearLayout(ctx); col.orientation = LinearLayout.VERTICAL; col.gravity = Gravity.CENTER
        val icon = ImageView(ctx)
        val s = Ui.dp(ctx, px); icon.layoutParams = LinearLayout.LayoutParams(s, s)
        icon.scaleType = ImageView.ScaleType.FIT_CENTER
        try {
            val pm = ctx.packageManager
            icon.setImageDrawable(pm.getApplicationIcon(app.packageName))
        } catch (e: Throwable) { icon.setBackgroundColor(0xFF333333.toInt()) }
        // Shape is approximated via background radius; icon drawable itself is the app icon.
        val label = Ui.text(ctx, app.label, 15f); label.visibility = View.GONE
        col.addView(icon); col.addView(label)
        col.setOnFocusChangeListener { _, focused -> label.visibility = if (focused) View.VISIBLE else View.GONE }
        Ui.focusable(col); col.setOnClickListener { onClick() }
        if (onLong != null) col.setOnLongClickListener { onLong(); true }
        if (app.folderId != null) {
            val badge = Ui.text(ctx, "▣ ${app.folderBreadcrumb}", 12f, 0xFFAAAAAA.toInt()); col.addView(badge)
        }
        return col
    }
    fun rowView(ctx: Context, title: String, cards: List<View>): View {
        val col = LinearLayout(ctx); col.orientation = LinearLayout.VERTICAL
        col.addView(Ui.sectionTitle(ctx, title))
        val row = LinearLayout(ctx); row.orientation = LinearLayout.HORIZONTAL
        val hs = android.widget.HorizontalScrollView(ctx)
        cards.forEach { row.addView(it) }
        hs.addView(row); col.addView(hs)
        return col
    }
    fun textCard(ctx: Context, title: String, sub: String, onClick: () -> Unit, onLong: (() -> Unit)? = null): View {
        val col = LinearLayout(ctx); col.orientation = LinearLayout.VERTICAL
        col.setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 12), Ui.dp(ctx, 16), Ui.dp(ctx, 12))
        col.background = Ui.tileBg(ctx)
        col.addView(Ui.text(ctx, title, 18f, Color.WHITE, true))
        if (sub.isNotEmpty()) col.addView(Ui.text(ctx, sub, 14f, 0xFFAAAAAA.toInt()))
        Ui.focusable(col); col.setOnClickListener { onClick() }
        if (onLong != null) col.setOnLongClickListener { onLong(); true }
        return col
    }
}
