package com.mizan.launcher.ui

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout

/** TMDB Search (MAP §1.6): D-pad keyboard over TMDB tv+movie only; tap opens Details; already-added flagged. */
class TmdbSearchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL
        col.addView(Ui.sectionTitle(this, "Add titles — TMDB search"))
        val results = LinearLayout(this); results.orientation = LinearLayout.VERTICAL
        col.addView(KeyboardDialog.dpadKeyboard(this) { q -> runQuery(q, results) })
        col.addView(results)
        setContentView(Ui.rootScrollView(this, col))
    }
    private fun runQuery(q: String, results: LinearLayout) {
        if (q.isBlank()) { results.removeAllViews(); return }
        Ui.runBg {
            val hits = try { UiWiring.tmdb?.searchTitles(q)?.getOrNull() ?: UiWiring.searchTitles?.searchTmdb(q)?.getOrNull() ?: emptyList() } catch (e: Throwable) { emptyList() }
            Ui.runMain {
                results.removeAllViews()
                if (hits.isEmpty()) { results.addView(Ui.stateText(this, "No results (search fails open to empty)")); return@runMain }
                hits.forEach { hit ->
                    val label = hit.title + (hit.year?.let { " ($it)" } ?: "") + if (hit.alreadyAdded) " ✓ Added" else ""
                    results.addView(Ui.button(this, label) {
                        Ui.openDetails(this, hit.tmdbId, hit.kind.name.lowercase(), hit.title)
                    })
                    if (!hit.alreadyAdded) {
                        results.addView(Ui.button(this, "＋ Add \"${hit.title}\"") {
                            Ui.runBg {
                                // Phase 2D enriched add: title + poster ride
                                // the pending entry instead of empty strings.
                                UiWiring.tracker?.addToLibrary(hit.tmdbId, hit.kind, hit.title, hit.posterPath)
                                Ui.runMain { Ui.toast(this, "Added — For You overlay entry retires when tracked") }
                            }
                        })
                    }
                }
            }
        }
    }
}
