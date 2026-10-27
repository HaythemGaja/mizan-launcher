package com.mizan.launcher.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.mizan.launcher.core.PlayInLogic

/** "Play in…" picker: play a title/stream in another installed video app. */
object PlayIn {
    /** Candidates for a VIEW intent on [url]; launcher itself excluded, unresolvable filtered. */
    fun candidates(ctx: Context, url: String): List<PlayInLogic.Candidate> {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        val pm = ctx.packageManager
        val raw = pm.queryIntentActivities(intent, 0).map {
            PlayInLogic.Candidate(it.activityInfo.packageName, it.loadLabel(pm).toString())
        }
        return PlayInLogic.filter(raw, ctx.packageName)
    }
    fun show(ctx: Context, url: String, title: String = "Play in…") {
        val cands = candidates(ctx, url)
        if (cands.isEmpty()) { Ui.toast(ctx, "No app can play this"); return }
        Ui.menu(ctx, title, cands.map { c -> c.label to {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply { setPackage(c.pkg); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            try { ctx.startActivity(intent) } catch (e: Throwable) { Ui.toast(ctx, "Could not open ${c.label}") }
        } })
    }
    fun openExternally(ctx: Context, url: String) = show(ctx, url, "Open externally")
}
