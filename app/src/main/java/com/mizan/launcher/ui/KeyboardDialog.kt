package com.mizan.launcher.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A D-pad text-entry dialog (v1.9) — the Search screen's on-screen
 * keyboard, lifted into a reusable dialog for short text that must
 * be typeable with the remote alone (first user: profile rename in
 * Settings › Lock & profiles). Same rows and key styling as
 * SearchActivity's keyboard, plus an Aa case toggle (names want
 * lowercase; search queries don't care) and a ✓ Done key.
 *
 * There is deliberately no EditText and no soft keyboard: on the TV
 * there may be no IME worth using, and every other text-ish flow in
 * this app (arrow lock, search) is remote-only for the same reason.
 * Entered text is capped at [maxLen] as it's typed; interpretation
 * (trim/caps/defaults) belongs to the caller's codec.
 */
object KeyboardDialog {

    fun show(
        activity: Activity,
        title: String,
        accent: Int,
        initial: String,
        maxLen: Int,
        onDone: (String) -> Unit
    ) {
        val text = StringBuilder(initial.take(maxLen))
        var lower = false

        val root = LinearLayout(activity)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(Ui.dp(activity, 20), Ui.dp(activity, 8), Ui.dp(activity, 20), Ui.dp(activity, 4))

        val value = TextView(activity)
        value.textSize = 22f
        value.setPadding(0, 0, 0, Ui.dp(activity, 10))
        root.addView(value)

        val keysBox = LinearLayout(activity)
        keysBox.orientation = LinearLayout.VERTICAL
        root.addView(keysBox)

        fun refreshValue() {
            if (text.isEmpty()) {
                value.text = "Type a name…"
                value.setTextColor(0x99FFFFFF.toInt())
            } else {
                value.text = text.toString()
                value.setTextColor(0xFFFFFFFF.toInt())
            }
        }

        fun key(label: String, onPress: () -> Unit): TextView {
            val tv = TextView(activity)
            tv.text = label
            tv.setTextColor(0xFFFFFFFF.toInt())
            tv.textSize = 19f
            tv.gravity = Gravity.CENTER
            tv.isFocusable = true
            tv.isClickable = true
            tv.setPadding(Ui.dp(activity, 14), Ui.dp(activity, 8), Ui.dp(activity, 14), Ui.dp(activity, 8))
            val lp = LinearLayout.LayoutParams(-2, -2)
            lp.setMargins(Ui.dp(activity, 3), Ui.dp(activity, 3), Ui.dp(activity, 3), Ui.dp(activity, 3))
            tv.layoutParams = lp
            tv.background = Ui.tileBackground(accent, false, 10, activity)
            Ui.focusLift(tv, accent, 10)
            tv.setOnClickListener { onPress() }
            return tv
        }

        var firstKey: TextView? = null

        fun buildKeys() {
            keysBox.removeAllViews()
            firstKey = null
            val letterRows = listOf("ABCDEFGHIJ", "KLMNOPQRST", "UVWXYZ")
            for (rowText in letterRows) {
                val rowView = LinearLayout(activity)
                rowView.orientation = LinearLayout.HORIZONTAL
                for (ch in rowText) {
                    val shown = if (lower) ch.lowercaseChar() else ch
                    val k = key(shown.toString()) {
                        if (text.length < maxLen) {
                            text.append(shown)
                            refreshValue()
                        }
                    }
                    if (firstKey == null) firstKey = k
                    rowView.addView(k)
                }
                keysBox.addView(rowView)
            }
            val digits = LinearLayout(activity)
            digits.orientation = LinearLayout.HORIZONTAL
            for (ch in "0123456789") {
                digits.addView(key(ch.toString()) {
                    if (text.length < maxLen) {
                        text.append(ch)
                        refreshValue()
                    }
                })
            }
            keysBox.addView(digits)
            val special = LinearLayout(activity)
            special.orientation = LinearLayout.HORIZONTAL
            special.addView(key("␣ Space") {
                if (text.length < maxLen) {
                    text.append(' ')
                    refreshValue()
                }
            })
            special.addView(key("⌫") {
                if (text.isNotEmpty()) {
                    text.deleteCharAt(text.length - 1)
                    refreshValue()
                }
            })
            special.addView(key("Clear") {
                if (text.isNotEmpty()) {
                    text.setLength(0)
                    refreshValue()
                }
            })
            special.addView(key(if (lower) "AA" else "Aa") {
                lower = !lower
                buildKeys()
                // Keep the remote roughly where it was: refocus the
                // toggle in the REBUILT special row (the last row).
                val newSpecial = keysBox.getChildAt(keysBox.childCount - 1) as? LinearLayout
                (newSpecial?.getChildAt(3) as? TextView)?.requestFocus()
            })
            keysBox.addView(special)
        }
        buildKeys()
        refreshValue()

        val done = TextView(activity)
        done.text = "✓ Done"
        done.setTextColor(0xFFFFFFFF.toInt())
        done.textSize = 20f
        done.typeface = Typeface.DEFAULT_BOLD
        done.gravity = Gravity.CENTER
        done.isFocusable = true
        done.isClickable = true
        done.setPadding(0, Ui.dp(activity, 10), 0, Ui.dp(activity, 10))
        val dlp = LinearLayout.LayoutParams(-1, -2)
        dlp.setMargins(0, Ui.dp(activity, 10), 0, 0)
        done.layoutParams = dlp
        done.background = Ui.tileBackground(accent, false, 10, activity)
        Ui.focusLift(done, accent, 10)
        root.addView(done)

        val dialog = AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(root)
            .setNegativeButton("Cancel", null)
            .create()
        done.setOnClickListener {
            dialog.dismiss()
            onDone(text.toString())
        }
        dialog.show()
        firstKey?.post { firstKey?.requestFocus() }
    }
}
