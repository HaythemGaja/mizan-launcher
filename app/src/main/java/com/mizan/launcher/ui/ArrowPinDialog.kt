package com.mizan.launcher.ui

import android.app.AlertDialog
import android.content.Context
import android.view.KeyEvent
import android.widget.LinearLayout
import android.widget.TextView
import com.mizan.launcher.core.ArrowDir
import com.mizan.launcher.core.ArrowPinCodec
import com.mizan.launcher.core.ArrowPinEntry
import com.mizan.launcher.core.ArrowPinEvent
import com.mizan.launcher.core.ArrowPinSetupFlow

/**
 * Arrow-pattern dialog (MAP §1.13): D-pad sequence 4–8 steps, glyphs are
 * arrows not digits; set requires confirm (mismatch restarts); submit
 * below minimum is "too short"; 9th append rejected as full; backspace
 * on empty cancels. Encoded form is UDLRU; legacy numeric PINs decode empty.
 */
object ArrowPinDialog {
    private fun glyphs(seq: List<ArrowDir>): String = seq.joinToString(" ") { it.glyph }

    /** Enter an existing pattern; onResult(encoded) only on valid submit. */
    fun showEnter(ctx: Context, onResult: (String) -> Unit, onCancel: () -> Unit = {}) {
        val entry = ArrowPinEntry()
        val label = TextView(ctx); label.textSize = 28f
        label.text = "Enter pattern (arrows, OK to submit)"
        val dlg = AlertDialog.Builder(ctx).setTitle("Pattern").setView(label)
            .setNegativeButton("Cancel") { _, _ -> onCancel() }.create()
        dlg.setOnKeyListener { _, code, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            val dir = when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> ArrowDir.UP; KeyEvent.KEYCODE_DPAD_DOWN -> ArrowDir.DOWN
                KeyEvent.KEYCODE_DPAD_LEFT -> ArrowDir.LEFT; KeyEvent.KEYCODE_DPAD_RIGHT -> ArrowDir.RIGHT
                KeyEvent.KEYCODE_DEL -> { if (entry.pressBack() is ArrowPinEvent.Cancelled) { dlg.dismiss(); onCancel() }; label.text = glyphs(entry.steps()); return@setOnKeyListener true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    when (val ev = entry.submit()) {
                        is ArrowPinEvent.Submitted -> { dlg.dismiss(); onResult(ev.sequence) }
                        is ArrowPinEvent.TooShort -> label.text = "Too short — 4 to 8 arrows"
                        else -> {}
                    }; return@setOnKeyListener true
                }
                else -> return@setOnKeyListener false
            }
            when (entry.append(dir)) {
                is ArrowPinEvent.RejectedFull -> label.text = glyphs(entry.steps()) + " (full)"
                else -> label.text = glyphs(entry.steps())
            }
            true
        }
        dlg.show()
    }

    /** Set a new pattern: enter twice; mismatch restarts with a fresh build. */
    fun showSet(ctx: Context, onDone: (String) -> Unit, onCancel: () -> Unit = {}) {
        val flow = ArrowPinSetupFlow()
        val label = TextView(ctx); label.textSize = 22f; label.text = "Enter new pattern (4–8 arrows)"
        var first: String? = null
        val dlg = AlertDialog.Builder(ctx).setTitle("Set pattern").setView(label)
            .setNegativeButton("Cancel") { _, _ -> onCancel() }.create()
        // Simpler two-phase flow driven by the enter dialog twice.
        dlg.dismiss()
        showEnter(ctx, { entered ->
            if (first == null) {
                first = entered
                showEnter(ctx, { second ->
                    if (second == first) onDone(second) else {
                        Ui.toast(ctx, "Patterns did not match — try again")
                        showSet(ctx, onDone, onCancel)
                    }
                }, onCancel)
            }
        }, onCancel)
        @Suppress("UNUSED_VARIABLE") val unused = flow
    }
}
