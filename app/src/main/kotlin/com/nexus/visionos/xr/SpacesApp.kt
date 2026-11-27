package com.nexus.visionos.xr

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import com.nexus.visionos.R
import com.nexus.xr.AppType
import com.nexus.xr.RoutineBook
import com.nexus.xr.Space

fun spaceName(context: Context, space: Space): String = context.getString(
    when (space) { Space.WORK -> R.string.space_work; Space.STUDY -> R.string.space_study; Space.CINEMA -> R.string.space_cinema }
)

/** Workspaces (saved sets of windows) and the user's own voice shortcuts. */
internal fun spacesApp(context: Context, shell: Shell): SpatialApp {
    val settings = shell.settings
    val input = editor(context).apply {
        setText(settings.routines.ifBlank { context.getString(R.string.routines_example) })
        textSize = 14f
        minLines = 3
        gravity = Gravity.TOP or Gravity.START
    }
    lateinit var result: android.widget.TextView
    lateinit var buttons: LinearLayout
    fun draw(): RoutineBook {
        val book = RoutineBook.parse(settings.routines.ifBlank { context.getString(R.string.routines_example) })
        buttons.removeAllViews()
        for (pair in book.routines.chunked(3)) buttons.row(height = context.px(48)) {
            for (routine in pair) key("▶ " + routine.name) { shell.runRoutine(routine) }
        }
        result.text = book.problems.joinToString("\n") {
            val reason = when (it.kind) {
                RoutineBook.Kind.FORMAT -> R.string.routine_format
                RoutineBook.Kind.NAME_TAKEN -> R.string.routine_taken
                RoutineBook.Kind.UNKNOWN_STEP -> R.string.routine_unknown
                RoutineBook.Kind.TOO_MANY -> R.string.routine_too_many
            }
            context.getString(R.string.routine_line, it.line) + " " + context.getString(reason) + " " + it.detail
        }
        return book
    }
    val view = context.scrolling {
        text(context.getString(R.string.spaces_title), 13f).alpha = 0.75f
        for (space in Space.entries) row(height = context.px(50)) {
            key(spaceName(context, space), 3f) { shell.openSpace(space) }
            key(context.getString(R.string.space_save), 2f) { shell.saveSpace(space) }
        }
        text(context.getString(R.string.routines_title), 13f).alpha = 0.75f
        buttons = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        addView(buttons)
        text(context.getString(R.string.routines_help), 12f).alpha = 0.75f
        addView(input, LinearLayout.LayoutParams(-1, -2))
        row(height = context.px(48)) {
            key(context.getString(R.string.routines_save), 3f) {
                settings.routines = input.text.toString().take(4000)
                draw()
            }
            iconKey(R.drawable.ic_ui_keyboard) { input.requestFocus(); shell.open(AppType.KEYBOARD) }
        }
        result = text("", 13f)
    }
    draw()
    return SpatialApp(view)
}
