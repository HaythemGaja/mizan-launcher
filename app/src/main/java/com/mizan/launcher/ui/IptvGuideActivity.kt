package com.mizan.launcher.ui

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.mizan.launcher.core.Design
import com.mizan.launcher.core.IptvEpg
import com.mizan.launcher.core.IptvLocks
import com.mizan.launcher.core.IptvLogic
import com.mizan.launcher.data.IptvClient
import com.mizan.launcher.data.PlayItem
import com.mizan.launcher.data.PlayQueue
import com.mizan.launcher.data.Prefs
import com.mizan.launcher.data.TmdbImages
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * The IPTV programme guide (v2.2, spec §20.7; v2.4 data flow,
 * spec §24) — one row per live channel (locked categories
 * hidden, same rule as everywhere): channel name, the NOW
 * programme with its time range and a through-progress bar, and
 * the NEXT programme. OK tunes the channel in PlayerActivity
 * (the guide's loaded slice is the zap queue).
 *
 * v2.4: channels arrive as store SLICES (worker-thread scans of
 * live.jsonl, hidden categories excluded, one window at a time
 * with Load more) — never a materialized catalogue. The EPG index
 * is built locally from the store's EPG file. Refresh happens
 * only from the header button; opening this screen fetches
 * nothing.
 *
 * For an Xtream channel the XMLTV lacks, focusing its row
 * fetches get_short_epg once per session as the fallback. No EPG
 * data anywhere → the guide simply lists channels with names
 * only; fail-open like the rest of IPTV.
 */
class IptvGuideActivity : Activity() {

    private lateinit var prefs: Prefs
    private val accent: Int = Design.NETFLIX_RED

    private var contentBox: LinearLayout? = null
    private var focusedKey: String? = null
    /** Short-EPG overlays by channel key (session only). */
    private val shortEpg = HashMap<String, List<IptvEpg.Programme>>()
    private val shortTried = HashSet<String>()
    @Volatile
    private var shortFetching = false

    // v2.4 slice state: the loaded channel window + its total,
    // keyed by (store generation, hidden set) so a refresh or an
    // unlock retires it; a generation counter retires stale
    // slice callbacks the same way the browser's does.
    private var guideItems: List<IptvLogic.LiveChannel> = emptyList()
    private var guideTotal = 0
    private var guideKey = ""
    private var guideGen = 0
    private var guideLoading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Design.NETFLIX_BG)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(Ui.dp(this, 48), Ui.dp(this, 26), Ui.dp(this, 48), Ui.dp(this, 30))
        scroll.addView(box)
        contentBox = box
        setContentView(scroll)
        rebuild()
        // Local EPG index from the store file (disk, not network).
        IptvClient.epgIndex(this) { if (!isFinishing) rebuild() }
    }

    override fun onResume() {
        super.onResume()
        rebuild()
    }

    /**
     * v2.3.2: every guide build runs behind this guard (same rule
     * as the browser): real catalogue/EPG data must never leave
     * this screen black — an escape is recorded and replaced by a
     * worded error line. Throwable, not Exception: inflation OOM
     * on a huge channel list is one of the defended failure modes.
     */
    private fun rebuild() {
        try {
            rebuildInner()
        } catch (e: Throwable) {
            Prefs.noteError(this, "iptvguide", e)
            val box = contentBox ?: return
            try {
                box.removeAllViews()
                val line = TextView(this)
                line.text = "The programme guide hit a snag — press Back and try again. " +
                    "The error is noted in the launcher's diagnostics."
                line.setTextColor(0xFFB3B3B3.toInt())
                line.textSize = 17f
                val lp = LinearLayout.LayoutParams(-1, -2)
                lp.setMargins(0, Ui.dp(this, 12), 0, 0)
                line.layoutParams = lp
                box.addView(line)
            } catch (e2: Throwable) {
                // Leave whatever is on screen rather than crash.
            }
        }
    }

    private fun hiddenCatKeys(snap: IptvClient.Snapshot): Set<String> =
        IptvLocks.hiddenKeys(
            snap.liveCats,
            IptvLocks.decodeKeys(prefs.iptvLockedCats),
            IptvLocks.decodeStamps(prefs.iptvCatUnlockStamps),
            prefs.unlockGraceMinutes,
            System.currentTimeMillis(),
            IptvClient.sessionStartMs,
            prefs.arrowPin.isNotEmpty()
        )

    private fun requestGuideSlice(append: Boolean, hidden: Set<String>) {
        if (guideLoading) return
        guideLoading = true
        val gen = ++guideGen
        val offset = if (append) guideItems.size else 0
        IptvClient.sliceAsync(
            this, IptvLogic.KIND_LIVE, excludeCats = hidden,
            offset = offset, limit = IptvLogic.GRID_CAP
        ) { page ->
            if (isFinishing) return@sliceAsync
            if (gen != guideGen) return@sliceAsync
            guideLoading = false
            guideTotal = page.total
            guideItems = if (append) guideItems + page.live else page.live
            rebuild()
        }
    }

    private fun rebuildInner() {
        val box = contentBox ?: return
        val focusedBefore = focusedKey
        box.removeAllViews()

        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        val title = TextView(this)
        title.text = "Programme guide"
        title.setTextColor(0xFFFFFFFF.toInt())
        title.textSize = 28f
        title.setTypeface(title.typeface, Typeface.BOLD)
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        val refreshBtn = headerButton("⟳ Refresh") {
            IptvClient.refreshAsync(this, force = true) { if (!isFinishing) rebuild() }
        }
        header.addView(refreshBtn)
        box.addView(header)

        val snap = IptvClient.snapshot(this)
        val progress = IptvClient.ingestProgressText()
        if (progress.isNotEmpty()) {
            noteLine(box, progress)
        } else if (snap.fetchedAt > 0L) {
            val timeText = SimpleDateFormat("h:mm a", Locale.US)
                .format(Date(snap.fetchedAt))
            noteLine(box, "Updated $timeText")
        }

        val hidden = hiddenCatKeys(snap)
        // Retire the loaded window when the store generation or
        // the hidden set moves, then (re)load its first slice.
        val key = IptvClient.storeStamp(this).toString() + "|" + hidden.hashCode()
        if (key != guideKey) {
            guideKey = key
            guideItems = emptyList()
            guideTotal = 0
            guideGen++
            guideLoading = false
            requestGuideSlice(append = false, hidden = hidden)
        }

        if (guideItems.isEmpty()) {
            val line = TextView(this)
            line.text = if (guideLoading) {
                "Loading channels…"
            } else {
                com.mizan.launcher.core.IptvStatus.emptyLine(IptvClient.status(this))
                    ?: "No live channels yet."
            }
            line.setTextColor(0xFFB3B3B3.toInt())
            line.textSize = 17f
            val lp = LinearLayout.LayoutParams(-1, -2)
            lp.setMargins(0, Ui.dp(this, 12), 0, 0)
            line.layoutParams = lp
            box.addView(line)
            return
        }

        // The final render still passes through the view cap
        // (core/IptvLogic.gridPage) as a hard bound, whatever the
        // loaded window grew to via Load more.
        val page = IptvLogic.gridPage(guideItems, MAX_RENDERED)
        if (guideTotal > page.items.size) {
            noteLine(box, "Showing ${page.items.size} of $guideTotal channels")
        }
        val index = IptvClient.epgIndex(this)
        val now = System.currentTimeMillis()
        val timeFmt = SimpleDateFormat("h:mm a", Locale.US)
        var firstRow: View? = null
        for (ch in page.items) {
            val programmes = shortEpg[ch.key]?.takeIf { it.isNotEmpty() }
                ?: IptvLogic.epgFor(index, ch)
            val (nowP, nextP) = IptvEpg.nowNext(programmes, now)
            val row = channelRow(ch, nowP, nextP, timeFmt, now, page.items)
            if (firstRow == null) firstRow = row
            box.addView(row)
        }
        if (guideItems.size < guideTotal && guideItems.size < MAX_RENDERED) {
            val more = headerButton("Load more (${guideTotal - guideItems.size} more)") {
                requestGuideSlice(append = true, hidden = hiddenCatKeys(IptvClient.snapshot(this)))
            }
            val mlp = LinearLayout.LayoutParams(-2, -2)
            mlp.setMargins(0, Ui.dp(this, 10), 0, Ui.dp(this, 6))
            more.layoutParams = mlp
            box.addView(more)
        }
        val target = focusedBefore?.let { findByTag(box, it) } ?: firstRow
        target?.post { target.requestFocus() }
    }

    private fun noteLine(box: LinearLayout, text: String) {
        val note = TextView(this)
        note.text = text
        note.setTextColor(0xFFB3B3B3.toInt())
        note.textSize = 15f
        val nlp = LinearLayout.LayoutParams(-1, -2)
        nlp.setMargins(0, Ui.dp(this, 10), 0, Ui.dp(this, 2))
        note.layoutParams = nlp
        box.addView(note)
    }

    private fun channelRow(
        ch: IptvLogic.LiveChannel,
        nowP: IptvEpg.Programme?,
        nextP: IptvEpg.Programme?,
        timeFmt: SimpleDateFormat,
        nowMs: Long,
        siblings: List<IptvLogic.LiveChannel>
    ): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.isFocusable = true
        row.isClickable = true
        row.tag = ch.key
        row.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10))
        row.background = Ui.tileBackground(accent, false, 12, this)
        row.setOnFocusChangeListener { v, hasFocus ->
            v.background = Ui.tileBackground(accent, hasFocus, 12, this)
            if (hasFocus) {
                focusedKey = ch.key
                maybeFetchShortEpg(ch)
            }
        }
        row.setOnClickListener {
            val items = siblings.map { playItemOf(it) }
            PlayQueue.set(items, siblings.indexOf(ch).coerceAtLeast(0))
            startActivity(PlayerActivity.intent(this, playItemOf(ch)))
        }
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.setMargins(0, Ui.dp(this, 4), 0, Ui.dp(this, 4))
        row.layoutParams = lp

        if (ch.icon.isNotBlank()) {
            val iv = ImageView(this)
            iv.scaleType = ImageView.ScaleType.FIT_CENTER
            val ilp = LinearLayout.LayoutParams(Ui.dp(this, 84), Ui.dp(this, 48))
            ilp.setMargins(0, 0, Ui.dp(this, 14), 0)
            iv.layoutParams = ilp
            row.addView(iv)
            TmdbImages.load(ch.icon, iv)
        }

        val texts = LinearLayout(this)
        texts.orientation = LinearLayout.VERTICAL
        val name = TextView(this)
        name.text = ch.name
        name.setTextColor(0xFFFFFFFF.toInt())
        name.textSize = 17f
        name.setTypeface(name.typeface, Typeface.BOLD)
        name.maxLines = 1
        name.ellipsize = android.text.TextUtils.TruncateAt.END
        texts.addView(name)
        if (nowP != null) {
            val nowLine = TextView(this)
            nowLine.text = "NOW  ${timeFmt.format(Date(nowP.startMs))} – " +
                "${timeFmt.format(Date(nowP.stopMs))}  ·  ${nowP.title}"
            nowLine.setTextColor(0xFFB3B3B3.toInt())
            nowLine.textSize = 14f
            nowLine.maxLines = 1
            nowLine.ellipsize = android.text.TextUtils.TruncateAt.END
            texts.addView(nowLine)
            val progress = IptvEpg.progress(nowP, nowMs)
            if (progress >= 0f) {
                val barBg = FrameLayout(this)
                barBg.setBackgroundColor(0xFF404040.toInt())
                val blp = LinearLayout.LayoutParams(Ui.dp(this, 320), Ui.dp(this, 4))
                blp.setMargins(0, Ui.dp(this, 4), 0, 0)
                barBg.layoutParams = blp
                val fill = View(this)
                fill.setBackgroundColor(accent)
                fill.layoutParams = FrameLayout.LayoutParams(
                    (Ui.dp(this, 320) * progress).toInt(), -1
                )
                barBg.addView(fill)
                texts.addView(barBg)
            }
        }
        if (nextP != null) {
            val nextLine = TextView(this)
            nextLine.text = "NEXT  ${timeFmt.format(Date(nextP.startMs))}  ·  ${nextP.title}"
            nextLine.setTextColor(0xFF8C8C8C.toInt())
            nextLine.textSize = 13f
            nextLine.maxLines = 1
            nextLine.ellipsize = android.text.TextUtils.TruncateAt.END
            texts.addView(nextLine)
        }
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        return row
    }

    /**
     * Short-EPG fallback: an Xtream channel with no XMLTV entry
     * fetches get_short_epg the first time its row is focused
     * (once per channel per session, one fetch at a time).
     */
    private fun maybeFetchShortEpg(ch: IptvLogic.LiveChannel) {
        if (!ch.isXtream || shortFetching) return
        if (ch.key in shortTried) return
        val index = IptvClient.epgIndex(this)
        if (IptvLogic.epgFor(index, ch).isNotEmpty()) return
        shortTried.add(ch.key)
        shortFetching = true
        thread(name = "mizan-iptv-short-epg") {
            try {
                val json = IptvClient.fetchShortEpgBlocking(this, ch.id)
                val programmes = IptvEpg.parseShortEpg(json, ch.epgId.ifEmpty { ch.id.toString() })
                if (programmes.isNotEmpty() && !isFinishing && !isDestroyed) {
                    shortEpg[ch.key] = programmes
                    runOnUiThread { rebuild() }
                }
            } catch (e: Exception) {
                Prefs.noteError(this, "iptvShortEpg", RuntimeException(e.javaClass.simpleName))
            } finally {
                shortFetching = false
            }
        }
    }

    private fun playItemOf(ch: IptvLogic.LiveChannel): PlayItem = PlayItem(
        kind = IptvLogic.KIND_LIVE, source = ch.source, id = ch.id.toString(),
        title = ch.name, icon = ch.icon, meta = ch.categoryName,
        catKey = ch.catKey, directUrl = ch.directUrl,
        tvArchive = ch.tvArchive, epgId = ch.epgId
    )

    private fun headerButton(text: String, onClick: () -> Unit): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.setTextColor(0xFFFFFFFF.toInt())
        tv.textSize = 16f
        tv.setTypeface(tv.typeface, Typeface.BOLD)
        tv.gravity = Gravity.CENTER
        tv.isFocusable = true
        tv.isClickable = true
        tv.setPadding(Ui.dp(this, 16), Ui.dp(this, 9), Ui.dp(this, 16), Ui.dp(this, 9))
        tv.background = Ui.tileBackground(accent, false, Design.RADIUS_CHIP, this)
        Ui.focusLift(tv, accent, Design.RADIUS_CHIP)
        tv.setOnClickListener { onClick() }
        return tv
    }

    private fun findByTag(root: View, tag: String): View? {
        if (root.tag == tag) return root
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) {
                findByTag(root.getChildAt(i), tag)?.let { return it }
            }
        }
        return null
    }

    companion object {
        /** Hard ceiling on rendered guide rows (Load-more stops here). */
        private const val MAX_RENDERED = 1000
    }
}
