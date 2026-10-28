package com.calm.launcher.ui

import android.app.Activity
import android.app.AlertDialog
import android.view.KeyEvent
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.calm.launcher.core.ArrowPin

/** Shared dialogs: arrow-pattern entry, text entry, and confirmation. */
object Dialogs {

    private fun Activity.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /**
     * Collects an arrow pattern from the D-pad. OK (or Center) submits once
     * [ArrowPin.MIN_LEN] arrows are in; Delete removes the last arrow; Back cancels.
     * Keys are consumed while the dialog is up so nothing leaks to the home screen.
     */
    fun arrowPattern(activity: Activity, title: String, hint: String, onDone: (String) -> Unit) {
        val seq = StringBuilder()
        val message = TextView(activity).apply {
            textSize = 18f
            setPadding(activity.dp(24), activity.dp(16), activity.dp(24), activity.dp(16))
        }
        fun refresh() {
            val arrows = seq.map { glyph(it) }.joinToString("  ")
            message.text = "$hint\n\n${if (arrows.isEmpty()) "…" else arrows}"
        }
        refresh()
        var dialog: AlertDialog? = null
        dialog = AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(message)
            .setNegativeButton("Cancel", null)
            .setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK) return@setOnKeyListener false
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener true
                val letter = when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> ArrowPin.UP
                    KeyEvent.KEYCODE_DPAD_DOWN -> ArrowPin.DOWN
                    KeyEvent.KEYCODE_DPAD_LEFT -> ArrowPin.LEFT
                    KeyEvent.KEYCODE_DPAD_RIGHT -> ArrowPin.RIGHT
                    else -> null
                }
                when {
                    letter != null -> {
                        if (seq.length < ArrowPin.MAX_LEN) seq.append(letter)
                        refresh()
                    }
                    keyCode == KeyEvent.KEYCODE_DEL -> {
                        if (seq.isNotEmpty()) seq.deleteCharAt(seq.length - 1)
                        refresh()
                    }
                    keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER -> {
                        if (seq.length >= ArrowPin.MIN_LEN) {
                            dialog?.dismiss()
                            onDone(seq.toString())
                        } else {
                            Toast.makeText(activity, "Use at least ${ArrowPin.MIN_LEN} arrows", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                true
            }
            .create()
        dialog.show()
    }

    /**
     * Two-step pattern setup: enter, then confirm. Saves to [store] and runs [onSet]
     * only when both entries match; otherwise nothing changes.
     */
    fun setPattern(activity: Activity, store: com.calm.launcher.data.Store, onSet: () -> Unit) {
        arrowPattern(activity, "Set arrow pattern", "Enter a pattern (3–8 arrows)") { first ->
            arrowPattern(activity, "Confirm pattern", "Enter the same pattern again") { second ->
                if (first == second) {
                    store.arrowPattern = first
                    onSet()
                } else {
                    Toast.makeText(activity, "Patterns didn't match — nothing changed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** A single text field. Blank input is reported as null. */
    fun text(activity: Activity, title: String, initial: String, onDone: (String?) -> Unit) {
        val field = EditText(activity).apply {
            setText(initial)
            setSingleLine(true)
            setSelection(text.length)
        }
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(field)
            .setPositiveButton("OK") { _, _ -> onDone(field.text.toString().trim().ifEmpty { null }) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun confirm(activity: Activity, title: String, message: String, action: String, onYes: () -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(action) { _, _ -> onYes() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun glyph(c: Char): String = when (c) {
        ArrowPin.UP -> "↑"
        ArrowPin.DOWN -> "↓"
        ArrowPin.LEFT -> "←"
        else -> "→"
    }
}
