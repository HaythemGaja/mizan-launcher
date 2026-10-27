package com.mizan.launcher.ui

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout

/** IPTV Guide (MAP §1.8): channel rows with now/next + progress at the current clock; paged like the browser. */
class IptvGuideActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL
        col.addView(Ui.sectionTitle(this, "Guide"))
        setContentView(Ui.rootScrollView(this, col))
        val iptv = UiWiring.iptv
        if (iptv == null) { col.addView(Ui.stateText(this, "IPTV not configured")); return }
        Ui.runBg {
            try {
                val cats = iptv.getLiveCategories()
                val chans = cats.flatMap { (id, _) -> try { iptv.getLiveChannels(id) } catch (e: Throwable) { emptyList() } }.take(200)
                Ui.runMain {
                    if (chans.isEmpty()) { col.addView(Ui.stateText(this, "No guide data")); return@runMain }
                    val now = System.currentTimeMillis()
                    chans.forEach { ch ->
                        val progs = try { iptv.getGuide(ch.id) } catch (e: Throwable) { emptyList() }
                        val nowProg = progs.firstOrNull { now in it.startMs until it.endMs }
                        val nextProg = progs.firstOrNull { it.startMs > now }
                        val frac = if (nowProg != null && nowProg.endMs > nowProg.startMs)
                            (now - nowProg.startMs).toFloat() / (nowProg.endMs - nowProg.startMs) else null
                        val sub = listOfNotNull(
                            nowProg?.let { "Now: ${it.title}" + (frac?.let { f -> " ${(f * 100).toInt()}%" } ?: "") },
                            nextProg?.let { "Next: ${it.title}" }
                        ).joinToString(" · ")
                        col.addView(PosterCards.textCard(this, ch.name, sub,
                            onClick = { Ui.openPlayer(this, iptv.playItemForChannel(ch)) }))
                    }
                }
            } catch (e: Throwable) {
                Ui.runMain { col.addView(Ui.stateText(this, "Guide hit a snag — tap to retry")) }
            }
        }
    }
}
