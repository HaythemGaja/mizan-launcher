package com.mizan.launcher.ui

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** D-pad keyboard dialog: text entry with per-field length caps (MAP §1.13). */
object KeyboardDialog {
    fun show(ctx: Context, title: String, initial: String = "", masked: Boolean = false, maxLen: Int = 120, onDone: (String) -> Unit) {
        val col = LinearLayout(ctx); col.orientation = LinearLayout.VERTICAL
        col.setPadding(Ui.dp(ctx, 24), Ui.dp(ctx, 16), Ui.dp(ctx, 24), Ui.dp(ctx, 16))
        val edit = EditText(ctx); edit.setText(initial)
        edit.inputType = if (masked) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else InputType.TYPE_CLASS_TEXT
        edit.filters = arrayOf(android.text.InputFilter.LengthFilter(maxLen))
        col.addView(edit)
        AlertDialog.Builder(ctx).setTitle(title).setView(col)
            .setPositiveButton("OK") { _, _ -> onDone(edit.text.toString().take(maxLen)) }
            .setNegativeButton("Cancel", null).show()
    }
    /** D-pad on-screen keyboard (letters, digits, space, backspace, clear) for search screens. */
    fun dpadKeyboard(ctx: Context, onQuery: (String) -> Unit): LinearLayout {
        val col = LinearLayout(ctx); col.orientation = LinearLayout.VERTICAL
        val field = TextView(ctx); field.textSize = 24f; field.text = ""
        col.addView(field)
        val rows = listOf("ABCDEFGHIJ", "KLMNOPQRST", "UVWXYZ0123", "456789")
        for (r in rows) {
            val row = LinearLayout(ctx); row.orientation = LinearLayout.HORIZONTAL
            for (ch in r) {
                val b = Ui.button(ctx, ch.toString()) { field.text = field.text.toString() + ch; onQuery(field.text.toString()) }
                row.addView(b)
            }
            col.addView(row)
        }
        val ctrl = LinearLayout(ctx); ctrl.orientation = LinearLayout.HORIZONTAL
        ctrl.addView(Ui.button(ctx, "Space") { field.text = field.text.toString() + " "; onQuery(field.text.toString()) })
        ctrl.addView(Ui.button(ctx, "⌫") { field.text = field.text.toString().dropLast(1); onQuery(field.text.toString()) })
        ctrl.addView(Ui.button(ctx, "Clear") { field.text = ""; onQuery("") })
        col.addView(ctrl)
        return col
    }
    object Caps {
        const val IPTV_SERVER = 120; const val IPTV_USER = 60; const val IPTV_PASS = 60
        const val M3U_EPG = 300; const val TMDB_KEY = 80
    }
}
