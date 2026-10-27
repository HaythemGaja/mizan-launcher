package com.mizan.launcher.ui

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout
import com.mizan.launcher.domain.IptvChannel
import com.mizan.launcher.domain.SeriesItem
import com.mizan.launcher.domain.VodItem

/**
 * IPTV browser (MAP §1.7): tabs 📺 Live · 🎬 Movies · 📚 Series · ★ Favorites · 🔍 Search.
 * Guide lives in its own Activity. Grid page cap 200 per category ("load more"
 * paging); empty categories skipped, favorites first, locks applied; browser
 * rebuilds guarded, reads store slices, never fetches on open/resume.
 * Category locks: pattern-locked categories ask for the arrow pattern;
 * adult-named categories (adult, xxx, +18, 18+) start locked when a pattern is set.
 */
class IptvActivity : Activity() {
    private var tab = "live"
    private val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private var page = 0
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        col.addView(Ui.sectionTitle(this, "IPTV"))
        listOf("📺 Live" to "live", "🎬 Movies" to "vod", "📚 Series" to "series", "★ Favorites" to "fav", "🔍 Search" to "search").forEach { (label, id) ->
            col.addView(Ui.button(this, label) { tab = id; page = 0; render() })
        }
        col.addView(Ui.button(this, "📖 Guide") { startActivity(android.content.Intent(this, IptvGuideActivity::class.java)) })
        col.addView(Ui.button(this, "↻ Refresh now") { Ui.runBg { UiWiring.iptv?.refresh(); Ui.runMain { render() } } })
        setContentView(Ui.rootScrollView(this, col))
        render()
    }
    private fun isAdultName(name: String): Boolean {
        val n = name.lowercase()
        return n.contains("adult") || n.contains("xxx") || n.contains("+18") || n.contains("18+")
    }
    private fun categoryLocked(catId: String, catName: String): Boolean {
        val locked = UiWiring.iptv?.getLockedCategories()?.contains(catId) == true
        val adultLocked = isAdultName(catName) && (UiWiring.lock?.isPatternSet() == true)
        return locked || adultLocked
    }
    private fun render() {
        while (col.childCount > 8) col.removeViewAt(8)
        val iptv = UiWiring.iptv
        if (iptv == null) { col.addView(Ui.stateText(this, "IPTV not configured — add an account in Settings → IPTV")); return }
        Ui.runBg {
            try {
                when (tab) {
                    "live" -> {
                        val cats = iptv.getLiveCategories()
                        val favFirst = cats.sortedBy { if (it.second.lowercase().contains("fav")) 0 else 1 }
                        Ui.runMain {
                            if (cats.isEmpty()) col.addView(Ui.stateText(this, "No live categories"))
                            favFirst.forEach { (id, name) ->
                                col.addView(Ui.button(this, "▸ $name") {
                                    if (categoryLocked(id, name) && !iptv.isCategoryUnlocked(id)) {
                                        PatternGate.run(this) { renderChannels(id, name) }
                                    } else renderChannels(id, name)
                                })
                                col.addView(Ui.button(this, "Hold-OK: lock \"$name\" (long-press)") {
                                    // Long-press equivalent via explicit button: pattern-lock a category from here.
                                    PatternGate.run(this) { Ui.runBg { iptv.setCategoryLocked(id, true); Ui.runMain { Ui.toast(this, "Category locked") } } }
                                })
                            }
                        }
                    }
                    "vod" -> renderPaged({ iptv.getVod(null) }, { v: VodItem -> vodCard(v) })
                    "series" -> renderPaged({ iptv.getSeries(null) }, { s: SeriesItem -> seriesCard(s) })
                    "fav" -> Ui.runMain {
                        val favs = iptv.getFavorites()
                        if (favs.isEmpty()) col.addView(Ui.stateText(this, "No favorites yet"))
                        favs.forEach { key -> col.addView(Ui.text(this, key, 16f)) }
                    }
                    "search" -> Ui.runMain {
                        col.addView(KeyboardDialog.dpadKeyboard(this) { q -> runIptvSearch(q) })
                    }
                }
            } catch (e: Throwable) {
                Ui.runMain { col.addView(Ui.stateText(this, "IPTV error — tap Refresh to retry")) }
            }
        }
    }
    private fun <T> renderPaged(load: () -> List<T>, card: (T) -> Unit) {
        Ui.runBg {
            val all = try { load() } catch (e: Throwable) { emptyList() }
            val slice = all.drop(page * 200).take(200)
            Ui.runMain {
                if (all.isEmpty()) col.addView(Ui.stateText(this, "Nothing here"))
                slice.forEach { card(it) }
                if (all.size > (page + 1) * 200) col.addView(Ui.button(this, "Load more") { page++; render() })
                else if (all.isNotEmpty()) col.addView(Ui.stateText(this, "Showing ${slice.size} of ${all.size}"))
            }
        }
    }
    private fun renderChannels(catId: String, catName: String) {
        while (col.childCount > 8) col.removeViewAt(8)
        Ui.runBg {
            val chans = try { UiWiring.iptv?.getLiveChannels(catId) ?: emptyList() } catch (e: Throwable) { emptyList() }
            val slice = chans.drop(page * 200).take(200)
            Ui.runMain {
                col.addView(Ui.sectionTitle(this, catName))
                if (chans.isEmpty()) col.addView(Ui.stateText(this, "No channels in this category"))
                slice.forEach { ch -> channelCard(ch) }
                if (chans.size > (page + 1) * 200) col.addView(Ui.button(this, "Load more") { page++; renderChannels(catId, catName) })
            }
        }
    }
    private fun channelCard(ch: IptvChannel) {
        val sub = listOfNotNull(ch.nowTitle, ch.nowProgress?.let { "${(it * 100).toInt()}%" }).joinToString(" · ")
        val v = PosterCards.textCard(this, ch.name, sub,
            onClick = { Ui.openPlayer(this, UiWiring.iptv!!.playItemForChannel(ch)) },
            onLong = { channelMenu(ch) })
        col.addView(v)
    }
    private fun channelMenu(ch: IptvChannel) {
        val iptv = UiWiring.iptv ?: return
        val items = mutableListOf<Pair<String, () -> Unit>>()
        items += "▶ Play" to { Ui.openPlayer(this, iptv.playItemForChannel(ch)) }
        if (ch.hasCatchUp) items += "Catch-up" to { Ui.openPlayer(this, iptv.playItemForChannel(ch)) }
        items += "★ Favorite" to { Ui.runBg { iptv.setFavorite(ch.id, true) } }
        Ui.menu(this, ch.name, items)
    }
    private fun vodCard(v: VodItem) {
        val sub = listOfNotNull(v.rating, v.resumeMs?.let { "Resume ${it / 1000}s" }, if (v.finished) "Finished" else null).joinToString(" · ")
        col.addView(PosterCards.textCard(this, v.name, sub,
            onClick = { Ui.openPlayer(this, UiWiring.iptv!!.playItemForVod(v)) },
            onLong = {
                val iptv = UiWiring.iptv ?: return@textCard
                Ui.menu(this, v.name, listOf(
                    "▶ Play" to { Ui.openPlayer(this, iptv.playItemForVod(v)) },
                    "Resume" to { Ui.openPlayer(this, iptv.playItemForVod(v)) },
                    "Clear resume" to { Ui.runBg { iptv.setResume(v.id, null) } },
                    "★ Favorite" to { Ui.runBg { iptv.setFavorite(v.id, true) } }
                ))
            }))
    }
    private fun seriesCard(s: SeriesItem) {
        col.addView(PosterCards.textCard(this, s.name, "",
            onClick = { renderSeriesDetail(s) },
            onLong = {
                val iptv = UiWiring.iptv ?: return@textCard
                Ui.menu(this, s.name, listOf("★ Favorite" to { Ui.runBg { iptv.setFavorite(s.id, true) } }))
            }))
    }
    private fun renderSeriesDetail(s: SeriesItem) {
        while (col.childCount > 8) col.removeViewAt(8)
        Ui.runBg {
            val eps = try { UiWiring.iptv?.getSeriesEpisodes(s.id) ?: emptyList() } catch (e: Throwable) { emptyList() }
            val bySeason = eps.sortedWith(compareBy({ it.season }, { it.episode })).groupBy { it.season }
            Ui.runMain {
                col.addView(Ui.sectionTitle(this, s.name))
                bySeason.forEach { (season, list) ->
                    col.addView(Ui.text(this, "Season $season", 20f, 0xFFFFFFFF.toInt(), true))
                    list.forEach { ep ->
                        val title = ep.title.ifBlank { "Episode ${ep.episode}" }
                        col.addView(PosterCards.textCard(this, "E${ep.episode} $title", ep.plot,
                            onClick = { Ui.openPlayer(this, UiWiring.iptv!!.playItemForEpisode(ep)) }))
                    }
                }
            }
        }
    }
    private fun runIptvSearch(q: String) {
        if (q.isBlank()) return
        Ui.runBg {
            val (chans, vods, series) = try { UiWiring.iptv?.search(q) ?: Triple(emptyList(), emptyList(), emptyList()) } catch (e: Throwable) { Triple(emptyList(), emptyList(), emptyList()) }
            Ui.runMain {
                // Search results are appended below the keyboard; keep it simple and rebuild the tab area.
                chans.take(200).forEach { channelCard(it) }
                vods.take(200).forEach { vodCard(it) }
                series.take(200).forEach { seriesCard(it) }
            }
        }
    }
}
