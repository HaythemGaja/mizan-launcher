package com.mizan.launcher.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.LinearLayout
import com.mizan.launcher.core.AppSearch
import com.mizan.launcher.domain.HomeCard

/** App Search (MAP §1.3): D-pad keyboard, substring match, prefix before substring, empty = all in order. */
class SearchActivity : Activity() {
    private val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private var query = ""
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        col.addView(Ui.sectionTitle(this, "Search"))
        col.addView(KeyboardDialog.dpadKeyboard(this) { q -> query = q; runSearch() })
        col.addView(Ui.button(this, "🎤 Voice search") {
            try {
                startActivityForResult(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 1)
            } catch (e: Throwable) { Ui.toast(this, "Voice search unavailable") }
        })
        setContentView(Ui.rootScrollView(this, col))
        runSearch()
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == RESULT_OK) {
            val spoken = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: ""
            query = spoken; runSearch()
        }
    }
    private fun runSearch() {
        Ui.runBg {
            val search = UiWiring.searchTitles
            val apps = try {
                search?.searchApps(query) ?: UiWiring.apps?.getAllApps()?.let { all ->
                    if (query.isBlank()) all else AppSearch.filter(all.map { AppSearch.Item(it.packageName, it.label) }, query)
                        .mapNotNull { item -> all.firstOrNull { it.packageName == item.pkg } }
                } ?: emptyList()
            } catch (e: Throwable) { emptyList() }
            val titles: List<HomeCard> = try { search?.searchTracker(query) ?: emptyList() } catch (e: Throwable) { emptyList() }
            Ui.runMain {
                while (col.childCount > 3) col.removeViewAt(3)
                if (apps.isEmpty() && titles.isEmpty()) { col.addView(Ui.stateText(this, "No results")); return@runMain }
                apps.forEach { app ->
                    col.addView(PosterCards.appTile(this, app, onClick = {
                        if (app.locked) PatternGate.run(this) { Ui.launchApp(this, app.packageName) }
                        else Ui.launchApp(this, app.packageName)
                    }))
                }
                titles.forEach { card ->
                    val sub = card.progress?.let { "${it.watchedCount}/${it.totalCount} watched" } ?: card.countdownLabel
                    col.addView(PosterCards.textCard(this, card.title, sub,
                        onClick = { Ui.openDetails(this, card.tmdbId, card.kind.name.lowercase(), card.title) }))
                }
            }
        }
    }
}
