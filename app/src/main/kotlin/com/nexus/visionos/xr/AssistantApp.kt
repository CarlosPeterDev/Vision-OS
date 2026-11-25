package com.nexus.visionos.xr

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.nexus.visionos.R
import com.nexus.visionos.VoiceStack
import com.nexus.voice.VoiceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** The Nexus orb. Its colour and size follow the real engine state. */
fun assistantApp(
    context: Context, scope: CoroutineScope, stack: VoiceStack, notice: () -> String, talk: () -> Unit
): SpatialApp {
    val density = context.resources.displayMetrics.density
    fun px(v: Int) = (v * density).toInt()
    val orb = View(context)
    val state = TextView(context).apply { setTextColor(Color.WHITE); gravity = Gravity.CENTER; textSize = 15f }
    val words = TextView(context).apply { setTextColor(Color.WHITE); textSize = 16f }
    fun button(label: Int, action: () -> Unit) = Button(context).apply {
        setText(label); isAllCaps = false; isFocusable = false
        setOnClickListener { action() }
    }
    val view = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(px(12), px(12), px(12), px(12))
        addView(orb, LinearLayout.LayoutParams(px(90), px(90)))
        addView(state)
        addView(ScrollView(context).apply { addView(words) }, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(LinearLayout(context).apply {
            addView(button(R.string.talk, talk), LinearLayout.LayoutParams(0, -2, 1f))
            addView(button(R.string.stop) { stack.engine.cancel() }, LinearLayout.LayoutParams(0, -2, 1f))
        })
    }
    val engine = stack.engine
    val job: Job = scope.launch {
        combine(engine.state, engine.transcript, engine.reply, engine.error) { s, heard, reply, error ->
            val color = when (s) {
                VoiceState.IDLE -> 0xFF8FB7FF
                VoiceState.LISTENING -> 0xFF59F0B5
                VoiceState.PROCESSING -> 0xFFB48CFF
                VoiceState.SPEAKING -> 0xFF5CD6FF
                VoiceState.INTERRUPTED -> 0xFFFFC75C
                VoiceState.ERROR -> 0xFFFF6B6B
                VoiceState.OFFLINE -> 0xFF8A8F99
            }.toInt()
            orb.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                gradientType = GradientDrawable.RADIAL_GRADIENT
                gradientRadius = px(60).toFloat()
                setGradientCenter(0.35f, 0.3f)
                colors = intArrayOf(Color.WHITE, color, color and 0x33FFFFFF)
            }
            val scale = if (s == VoiceState.LISTENING || s == VoiceState.SPEAKING) 1.15f else 1f
            orb.animate().scaleX(scale).scaleY(scale).setDuration(250).start()
            state.text = s.name
            words.text = listOfNotNull(heard.ifEmpty { null }, reply.ifEmpty { null }, error, notice().ifEmpty { null })
                .joinToString("\n\n")
        }.collect()
    }
    return SpatialApp(view) { job.cancel() }
}
