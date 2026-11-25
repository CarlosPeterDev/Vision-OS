package com.nexus.visionos

import android.app.Activity
import android.view.View
import android.view.WindowInsets
import android.widget.*

fun Activity.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

/** A scrolling column that stays clear of system bars (edge-to-edge is enforced on new Android). */
fun Activity.column(build: LinearLayout.() -> Unit): View {
    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(20), dp(20), dp(20))
        build()
    }
    return ScrollView(this).apply {
        addView(content)
        setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }
}

fun LinearLayout.label(text: CharSequence = "", size: Float = 15f): TextView = TextView(context).also {
    it.text = text
    it.textSize = size
    it.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
    it.setPadding(0, 12, 0, 12)
    addView(it)
}

fun LinearLayout.button(text: CharSequence, onClick: () -> Unit): Button = Button(context).also {
    it.text = text
    it.isAllCaps = false
    it.setOnClickListener { _ -> onClick() }
    addView(it)
}
