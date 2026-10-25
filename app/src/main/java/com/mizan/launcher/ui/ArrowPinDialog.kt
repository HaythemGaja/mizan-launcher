package com.mizan.launcher.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.mizan.launcher.core.ArrowDir
import com.mizan.launcher.core.ArrowPinCodec
import com.mizan.launcher.core.ArrowPinEntry
import com.mizan.launcher.core.ArrowPinEvent
import com.mizan.launcher.core.ArrowPinSetupFlow
import com.mizan.launcher.core.ArrowPinSetupResult
import com.mizan.launcher.data.Prefs

/**
 * The arrow-sequence lock dialog — the ONLY lock entry in the app
 * (unlocking a locked app, setting/changing/clearing the lock).
 *
 * v1.2: the secret is a sequence of D-pad directions, 4–8 steps. There
 * are NO digits and NO on-screen number grid: the dialog captures the
 * remote's arrow keys directly and feeds the pure [ArrowPinEntry]
 * machine. Entry display differs by flow on purpose:
 * - SET flow shows the arrow glyphs (↑↓←→) as they're typed, so the
 *   user can learn and verify their own pattern while creating it.
 * - UNLOCK flow shows dots (•) only, so a bystander watching the TV
 *   doesn't learn the pattern from the screen.
 * Wrong unlock attempts clear the entry, shake the dialog card, show
 * "Wrong pattern — try again", and are counted by the caller.
 * No text field, no soft keyboard — ever.
 */
object ArrowPinDialog {

    /**
     * Unlock prompt: checks entries against [storedEncoded] internally.
     * v3.3 (ask 45): SAME dialog, just auto-submit — the dialog
     * submits by itself the moment the entered count reaches the
     * stored pattern length, so no OK press is ever needed to
     * unlock. (An unset/garbled stored value falls back to OK-only.)
     */
    fun showUnlock(
        activity: Activity,
        title: String,
        accent: Int,
        storedEncoded: String,
        onSuccess: () -> Unit,
        onWrongAttempt: () -> Unit = {},
        onCancel: () -> Unit = {}
    ) {
        val storedLength = com.mizan.launcher.core.ArrowPinCodec.decode(storedEncoded).size
        show(
            activity = activity,
            initialTitle = title,
            accent = accent,
            showGlyphs = false,
            hint = "Press your arrow pattern · Back deletes",
            autoSubmitLength = storedLength,
            onSubmitSeq = { seq ->
                if (ArrowPinCodec.matches(ArrowPinCodec.decode(seq), storedEncoded)) {
                    SubmitOutcome.Accept { onSuccess() }
                } else {
                    onWrongAttempt()
                    SubmitOutcome.Retry("Wrong pattern — try again")
                }
            },
            onCancel = onCancel
        )
    }

    /**
     * Set/change prompt: enter a pattern, then repeat it to confirm
     * (ArrowPinSetupFlow). A mismatch restarts inside the same dialog.
     */
    fun showSetup(
        activity: Activity,
        title: String,
        accent: Int,
        onDone: (String) -> Unit,
        onCancel: () -> Unit = {}
    ) {
        val flow = ArrowPinSetupFlow()
        var titleRef = title
        show(
            activity = activity,
            initialTitle = title,
            accent = accent,
            showGlyphs = true,
            hint = "Press arrows to build a pattern of 4–8 · OK when done · Back deletes",
            titleProvider = { titleRef },
            onSubmitSeq = { seq ->
                when (val result = flow.submit(seq)) {
                    ArrowPinSetupResult.NeedConfirm -> {
                        titleRef = "Repeat the pattern to confirm"
                        SubmitOutcome.NextPhase("")
                    }
                    is ArrowPinSetupResult.Done -> SubmitOutcome.Accept { onDone(result.sequence) }
                    ArrowPinSetupResult.Mismatch -> {
                        titleRef = title
                        SubmitOutcome.NextPhase("Didn't match — build your pattern again")
                    }
                }
            },
            onCancel = onCancel
        )
    }

    /** What the dialog should do after a submitted sequence. */
    private sealed class SubmitOutcome {
        /** Entry correct / flow complete: dismiss and run [after]. */
        data class Accept(val after: () -> Unit) : SubmitOutcome()
        /** Wrong unlock attempt: shake, show [message], stay open cleared. */
        data class Retry(val message: String) : SubmitOutcome()
        /** Setup phase change: update title, show [message], stay open cleared. */
        data class NextPhase(val message: String) : SubmitOutcome()
    }

    private fun show(
        activity: Activity,
        initialTitle: String,
        accent: Int,
        showGlyphs: Boolean,
        hint: String,
        titleProvider: (() -> String)? = null,
        autoSubmitLength: Int? = null,
        onSubmitSeq: (String) -> SubmitOutcome,
        onCancel: () -> Unit
    ) {
        try {
            val machine = ArrowPinEntry()
            val ctx = activity
            val pad = Ui.dp(ctx, 20)
            val root = LinearLayout(ctx)
            root.orientation = LinearLayout.VERTICAL
            root.gravity = Gravity.CENTER_HORIZONTAL
            root.setPadding(pad, pad, pad, pad)
            val bg = GradientDrawable()
            bg.cornerRadius = Ui.dp(ctx, 18).toFloat()
            bg.setColor(0xF21A1A1E.toInt())
            root.background = bg

            val titleView = TextView(ctx)
            titleView.text = initialTitle
            titleView.setTextColor(Color.WHITE)
            titleView.textSize = 22f
            titleView.typeface = Typeface.DEFAULT_BOLD
            titleView.gravity = Gravity.CENTER
            root.addView(titleView)

            // Progress: glyphs in set flow, dots in unlock flow.
            val progress = TextView(ctx)
            progress.textSize = 30f
            progress.setTextColor(accent)
            progress.gravity = Gravity.CENTER
            progress.setPadding(0, Ui.dp(ctx, 10), 0, Ui.dp(ctx, 4))
            progress.minHeight = Ui.dp(ctx, 48)
            root.addView(progress)

            val feedback = TextView(ctx)
            feedback.setTextColor(0xFFFF9A8A.toInt())
            feedback.textSize = 16f
            feedback.gravity = Gravity.CENTER
            feedback.minHeight = Ui.dp(ctx, 24)
            root.addView(feedback)

            val hintView = TextView(ctx)
            hintView.text = hint
            hintView.setTextColor(0xFF9A9AA2.toInt())
            hintView.textSize = 14f
            hintView.gravity = Gravity.CENTER
            root.addView(hintView)

            val dialog = AlertDialog.Builder(ctx).setView(root).setCancelable(false).create()

            fun render() {
                titleView.text = titleProvider?.invoke() ?: initialTitle
                progress.text = if (machine.entered == 0) {
                    "· · · ·"
                } else if (showGlyphs) {
                    machine.steps().joinToString(" ") { it.glyph }
                } else {
                    buildString { repeat(machine.entered) { append("● ") } }.trim()
                }
            }

            /** Small shake of the card — wrong-attempt feedback only. */
            fun shake() {
                try {
                    root.animate().cancel()
                    root.translationX = 0f
                    root.animate().translationX(Ui.dp(ctx, 12).toFloat()).setDuration(45)
                        .withEndAction {
                            root.animate().translationX(Ui.dp(ctx, -12).toFloat()).setDuration(70)
                                .withEndAction {
                                    root.animate().translationX(0f).setDuration(50).start()
                                }.start()
                        }.start()
                } catch (e: Exception) {
                    // Feedback is cosmetic; entry flow must not depend on it.
                }
            }

            fun handle(event: ArrowPinEvent) {
                when (event) {
                    ArrowPinEvent.Changed -> { feedback.text = ""; render() }
                    ArrowPinEvent.RejectedFull -> {
                        feedback.text = "That's the maximum — 8 steps. Press OK."
                        render()
                    }
                    ArrowPinEvent.TooShort -> {
                        feedback.text = "Need at least 4 arrows — keep going"
                        render()
                    }
                    ArrowPinEvent.Cancelled -> {
                        dialog.dismiss()
                        onCancel()
                    }
                    is ArrowPinEvent.Submitted -> {
                        when (val outcome = onSubmitSeq(event.sequence)) {
                            is SubmitOutcome.Accept -> {
                                dialog.dismiss()
                                outcome.after()
                            }
                            is SubmitOutcome.Retry -> {
                                feedback.text = outcome.message
                                shake()
                                render()
                            }
                            is SubmitOutcome.NextPhase -> {
                                feedback.text = outcome.message
                                if (outcome.message.startsWith("Didn't")) shake()
                                render()
                            }
                        }
                    }
                }
            }

            var backDownAt: Long = 0
            dialog.setOnKeyListener { _, keyCode, event ->
                val dir = when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> ArrowDir.UP
                    KeyEvent.KEYCODE_DPAD_DOWN -> ArrowDir.DOWN
                    KeyEvent.KEYCODE_DPAD_LEFT -> ArrowDir.LEFT
                    KeyEvent.KEYCODE_DPAD_RIGHT -> ArrowDir.RIGHT
                    else -> null
                }
                when {
                    dir != null -> {
                        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                            handle(machine.append(dir))
                            // v3.3 (ask 45): unlock auto-submit — the
                            // moment the entry reaches the stored
                            // length, submit without any OK press.
                            // A mismatch clears inside handle() and
                            // the user retries at once; partial
                            // entries never submit (pure rule:
                            // ArrowPinAutoSubmit, sim section 53).
                            if (autoSubmitLength != null &&
                                com.mizan.launcher.core.ArrowPinAutoSubmit.shouldSubmit(
                                    machine.entered, autoSubmitLength
                                )
                            ) {
                                handle(machine.submit())
                            }
                        }
                        true
                    }
                    keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                        keyCode == KeyEvent.KEYCODE_ENTER ||
                        keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                            handle(machine.submit())
                        }
                        true
                    }
                    keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE -> {
                        when (event.action) {
                            KeyEvent.ACTION_DOWN -> {
                                if (event.repeatCount == 0) backDownAt = event.eventTime
                                true
                            }
                            KeyEvent.ACTION_UP -> {
                                val held = event.eventTime - backDownAt
                                if (backDownAt > 0 && held >= 600) handle(machine.cancel())
                                else handle(machine.pressBack())
                                backDownAt = 0
                                true
                            }
                            else -> true
                        }
                    }
                    else -> false
                }
            }

            dialog.show()
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
            render()
        } catch (e: Exception) {
            Prefs.noteError(activity, "arrowPinDialog", e)
            onCancel()
        }
    }
}
