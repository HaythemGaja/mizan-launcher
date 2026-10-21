package com.mizan.launcher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.mizan.launcher.core.SwitchLogic

/**
 * iPhone-style on/off switch for Settings (v1.3).
 *
 * [SwitchPillView] draws only the switch: a pill track (grey when OFF,
 * iOS green when ON — colours decided by core/SwitchLogic) with a white
 * knob that sits left (off) or right (on), readable at a glance from
 * the couch. [SwitchRowView] is the full settings row: title/subtitle
 * on the left, the pill on the right, focus ring around the whole row.
 *
 * Interaction: OK/CENTER toggles (via the normal click path);
 * LEFT/RIGHT also toggle while the row is focused — SettingsActivity's
 * key dispatcher deliberately lets LEFT/RIGHT fall through to a
 * focused SwitchRowView instead of jumping back to the category list
 * (BACK still returns to the list, per the nav machine).
 */
class SwitchPillView(ctx: Context) : View(ctx) {

    var checked: Boolean = false
        set(v) {
            field = v
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        // Track: full pill.
        paint.color = SwitchLogic.trackColor(checked)
        paint.style = Paint.Style.FILL
        canvas.drawRoundRect(0f, 0f, w, h, h / 2f, h / 2f, paint)
        // Knob: white circle with a small inset, travelling per state.
        val inset = h * 0.09f
        val r = (h - inset * 2) / 2f
        val travel = w - inset * 2 - r * 2
        val cx = inset + r + SwitchLogic.knobFraction(checked) * travel
        // Soft shadow under the knob for depth.
        paint.color = 0x33000000
        canvas.drawCircle(cx, h / 2f + h * 0.04f, r, paint)
        paint.color = SwitchLogic.KNOB
        canvas.drawCircle(cx, h / 2f, r, paint)
    }
}

/**
 * One boolean settings row with a [SwitchPillView] on its right edge.
 * The caller (SettingsActivity) wires focus visuals/memory via
 * Ui.focusLift and its focus hooks, exactly like text rows.
 */
class SwitchRowView(
    ctx: Context,
    title: String,
    subtitle: String,
    titleColour: Int,
    subColour: Int,
    titleSp: Float,
    subSp: Float,
    padVDp: Int,
    initiallyChecked: Boolean,
    private val onToggle: (Boolean) -> Unit
) : LinearLayout(ctx) {

    private val pill = SwitchPillView(ctx)

    var checked: Boolean = initiallyChecked
        private set(v) {
            field = v
            pill.checked = v
        }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = true
        isClickable = true
        setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, padVDp), Ui.dp(ctx, 16), Ui.dp(ctx, padVDp))
        val lp = LayoutParams(-1, -2)
        lp.setMargins(0, Ui.dp(ctx, 4), 0, Ui.dp(ctx, 4))
        layoutParams = lp

        val texts = LinearLayout(ctx)
        texts.orientation = VERTICAL
        val titleView = TextView(ctx)
        titleView.text = title
        titleView.setTextColor(titleColour)
        titleView.textSize = titleSp
        texts.addView(titleView)
        if (subtitle.isNotEmpty()) {
            val sub = TextView(ctx)
            sub.text = subtitle
            sub.setTextColor(subColour)
            sub.textSize = subSp
            texts.addView(sub)
        }
        addView(texts, LayoutParams(0, -2, 1f))

        pill.checked = initiallyChecked
        val density = ctx.resources.displayMetrics.density
        val pillW = (52 * density).toInt()
        val pillH = (32 * density).toInt()
        addView(pill, LayoutParams(pillW, pillH))

        setOnClickListener { toggle() }
    }

    /** Flip the switch and report the new state. */
    fun toggle() {
        checked = SwitchLogic.toggled(checked)
        onToggle(checked)
    }

    /**
     * LEFT/RIGHT flip the switch (iOS settings behaviour). CENTER/ENTER
     * are left alone — the default click handling toggles via the
     * click listener, so handling them here too would double-toggle.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) &&
            event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0
        ) {
            toggle()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
