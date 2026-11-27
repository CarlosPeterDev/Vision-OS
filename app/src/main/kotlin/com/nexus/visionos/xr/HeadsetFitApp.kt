package com.nexus.visionos.xr

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import com.nexus.visionos.R
import com.nexus.xr.HeadsetFit

/** Places each headset picture behind its lens. Every change shows at once. */
internal fun headsetFitApp(context: Context, shell: Shell): SpatialApp {
    val settings = shell.settings
    val refreshers = mutableListOf<() -> Unit>()
    fun change(edit: (HeadsetFit) -> HeadsetFit) {
        settings.fit = edit(settings.fit)
        refreshers.forEach { it() }
    }
    fun percent(value: Float) = "%+.0f%%".format(value * 100)
    val step = 0.01f
    val view = context.scrolling {
        row(height = context.px(52)) {
            key(context.getString(R.string.ctl_headset)) { shell.toggleHeadset() }
            key(context.getString(R.string.lens_grid)) { shell.toggleLensGrid() }
            key(context.getString(R.string.fit_drag)) { shell.startDragFit() }
        }
        fun stepper(label: () -> String, less: () -> Unit, more: () -> Unit) = row(height = context.px(48)) {
            // Left to right in every language, so minus stays on the left.
            layoutDirection = android.view.View.LAYOUT_DIRECTION_LTR
            key("−") { less() }
            val title = text(label(), 14f)
            removeView(title)
            addView(title, LinearLayout.LayoutParams(0, -1, 3f))
            title.gravity = Gravity.CENTER
            refreshers += { title.text = label() }
            key("+") { more() }
        }
        stepper({ context.getString(R.string.fit_gap) + ": " + percent(settings.fit.gap) },
            { change { it.copy(gap = it.gap - step) } }, { change { it.copy(gap = it.gap + step) } })
        stepper({ context.getString(R.string.fit_width) + ": %.0f%%".format(settings.fit.width * 100) },
            { change { it.copy(width = it.width - 0.03f) } }, { change { it.copy(width = it.width + 0.03f) } })
        stepper({ context.getString(R.string.fit_height) + ": %.0f%%".format(settings.fit.height * 100) },
            { change { it.copy(height = it.height - 0.03f) } }, { change { it.copy(height = it.height + 0.03f) } })
        /** Four arrows that move one eye's picture only. */
        fun eye(name: Int, position: () -> Pair<Float, Float>, move: (HeadsetFit, Float, Float) -> HeadsetFit) {
            val title = text("", 13f).apply { gravity = Gravity.CENTER }
            refreshers += { title.text = context.getString(name) + "   ↔ " + percent(position().first) + "   ↕ " + percent(position().second) }
            row(height = context.px(52)) {
                layoutDirection = android.view.View.LAYOUT_DIRECTION_LTR
                for ((arrow, dx, dy) in listOf(Triple("←", -step, 0f), Triple("→", step, 0f), Triple("↑", 0f, step), Triple("↓", 0f, -step))) {
                    key(arrow) { change { move(it, dx, dy) } }
                }
            }
        }
        eye(R.string.fit_left, { settings.fit.leftX to settings.fit.leftY }) { fit, dx, dy -> fit.copy(leftX = fit.leftX + dx, leftY = fit.leftY + dy) }
        eye(R.string.fit_right, { settings.fit.rightX to settings.fit.rightY }) { fit, dx, dy -> fit.copy(rightX = fit.rightX + dx, rightY = fit.rightY + dy) }
        row(height = context.px(48)) {
            key(context.getString(R.string.fit_reset)) { change { HeadsetFit() } }
        }
        text(context.getString(R.string.fit_help), 12f).alpha = 0.75f
    }
    refreshers.forEach { it() }
    // A finger drag or a voice command can change the values while this window is open.
    val stop = ticker(500) { refreshers.forEach { it() } }
    return SpatialApp(view, stop)
}
