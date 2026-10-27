package com.mizan.launcher.ui

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** iPhone-style on/off switch: on = green track + knob right, off = grey + knob left. */
class SwitchPillView(ctx: Context, initial: Boolean) : LinearLayout(ctx) {
    var checked: Boolean = initial
        private set
    private val knob = TextView(ctx)
    init {
        orientation = HORIZONTAL
        val w = Ui.dp(ctx, 64); val h = Ui.dp(ctx, 32)
        layoutParams = LayoutParams(w, h)
        knob.text = "●"; knob.textSize = 22f
        addView(knob); render()
        isFocusable = true
        setOnClickListener { toggle() }
    }
    fun toggle() { setChecked(!checked) }
    fun setChecked(v: Boolean) {
        checked = v; render()
        onToggle?.invoke(v)
    }
    var onToggle: ((Boolean) -> Unit)? = null
    private fun render() {
        setBackgroundColor(if (checked) 0xFF34C759.toInt() else 0xFF888888.toInt())
        knob.setTextColor(Color.WHITE)
        // Knob right when on, left when off (gravity approximation on a row).
        gravity = if (checked) android.view.Gravity.END else android.view.Gravity.START
    }
}

object SwitchRow {
    /** A labelled settings row with a switch pill; toggle flips both ways. */
    fun row(ctx: Context, label: String, note: String = "", initial: Boolean, onToggle: (Boolean) -> Unit): View {
        val r = LinearLayout(ctx); r.orientation = LinearLayout.HORIZONTAL
        r.setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 10), Ui.dp(ctx, 16), Ui.dp(ctx, 10))
        val col = LinearLayout(ctx); col.orientation = LinearLayout.VERTICAL
        col.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        col.addView(Ui.text(ctx, label, 18f, Color.WHITE, true))
        if (note.isNotEmpty()) col.addView(Ui.text(ctx, note, 14f, 0xFFAAAAAA.toInt()))
        val pill = SwitchPillView(ctx, initial); pill.onToggle = onToggle
        r.addView(col); r.addView(pill)
        Ui.focusable(r)
        r.setOnClickListener { pill.toggle() }
        return r
    }
}
