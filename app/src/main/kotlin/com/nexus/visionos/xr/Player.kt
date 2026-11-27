package com.nexus.visionos.xr

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.VideoView
import com.nexus.visionos.R
import com.nexus.xr.Act

/** The video and audio player: a playlist, subtitles, and a screen that can grow very large. */
internal fun playerApp(context: Context, shell: Shell): SpatialApp {
    val video = VideoView(context)
    val caption = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 17f
        gravity = Gravity.CENTER
        setShadowLayer(6f, 0f, 2f, Color.BLACK)
        setBackgroundColor(0x80000000.toInt())
        setPadding(context.px(8), context.px(2), context.px(8), context.px(2))
        visibility = View.GONE
    }
    lateinit var status: TextView
    var current: android.net.Uri? = null
    var prepared = false
    /** Moves the subtitles later (+) or earlier (-) when they do not match the sound. */
    var shift = 0L
    var note = context.getString(R.string.media_hint)
    fun describe() {
        val count = shell.videoCount()
        status.text = buildList {
            if (note.isNotEmpty()) add(note)
            if (count > 1) add(context.getString(R.string.video_of, shell.videoIndex() + 1, count))
            if (shell.subtitles() != null) add(context.getString(R.string.subtitle_shift, "%+.1f".format(shift / 1000f)))
        }.joinToString("  ·  ")
    }
    val view = context.vertical {
        status = text(note, 13f)
        status.maxLines = 1
        addView(FrameLayout(context).apply {
            addView(video, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
            addView(caption, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = context.px(8) })
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(LinearLayout(context).apply {
            key(context.getString(R.string.choose_video), 2f) { shell.pickVideo() }
            iconKey(R.drawable.ic_ui_back) { shell.stepVideo(-1) }
            iconKey(R.drawable.ic_ui_rewind) { video.seekTo((video.currentPosition - 10_000).coerceAtLeast(0)) }
            iconKey(R.drawable.ic_ui_play) { video.start() }
            iconKey(R.drawable.ic_ui_pause) { video.pause() }
            iconKey(R.drawable.ic_ui_skip) { video.seekTo(video.currentPosition + 10_000) }
            iconKey(R.drawable.ic_ui_forward) { shell.stepVideo(1) }
        }, LinearLayout.LayoutParams(-1, context.px(52)))
        addView(LinearLayout(context).apply {
            key(context.getString(R.string.subtitles), 2f) { shell.pickSubtitles() }
            key("−0.5s") { shift -= 500; describe() }
            key("+0.5s") { shift += 500; describe() }
            // Normal, then a large curved screen, then a very large one.
            key(context.getString(R.string.screen_size), 2f) { shell.toggleCinema() }
        }, LinearLayout.LayoutParams(-1, context.px(48)))
    }
    video.setOnPreparedListener { prepared = true; video.start() }
    video.setOnCompletionListener { shell.stepVideo(1) }
    video.setOnErrorListener { _, _, _ -> note = context.getString(R.string.media_error); describe(); true }
    var version = -1
    var showing = ""
    val stop = ticker(200) {
        val chosen = shell.videoUri()
        if (chosen != null && chosen != current) {
            current = chosen
            prepared = false
            shift = 0
            note = ""
            video.setVideoURI(chosen)
            describe()
        }
        if (version != shell.subtitleVersion()) {
            version = shell.subtitleVersion()
            describe()
        }
        val line = if (prepared) shell.subtitles()?.textAt(video.currentPosition - shift).orEmpty() else ""
        if (line != showing) {
            showing = line
            caption.text = line
            caption.visibility = if (line.isEmpty()) View.GONE else View.VISIBLE
        }
    }
    return SpatialApp(view) { stop(); video.stopPlayback() }.also {
        it.command = { act ->
            when (act) {
                Act.PLAY -> video.start()
                Act.PAUSE -> video.pause()
                Act.SKIP -> video.seekTo(video.currentPosition + 10_000)
                Act.REWIND -> video.seekTo((video.currentPosition - 10_000).coerceAtLeast(0))
                else -> null
            } != null
        }
    }
}
