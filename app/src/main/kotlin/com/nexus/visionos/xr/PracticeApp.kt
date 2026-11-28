package com.nexus.visionos.xr

import android.content.Context
import android.widget.Button
import com.nexus.visionos.R
import com.nexus.xr.HandPose
import com.nexus.xr.PoseAction
import com.nexus.xr.TrackingAdvice
import com.nexus.xr.TrackingProblem

fun poseName(context: Context, pose: HandPose): String = context.resources.getStringArray(R.array.pose_names)[pose.ordinal]
fun actionName(context: Context, action: PoseAction): String = context.resources.getStringArray(R.array.pose_actions)[action.ordinal]

private const val TARGETS = 10

/** Shows what the hand tracker sees, gives pinch practice and assigns actions to hand shapes. */
internal fun practiceApp(context: Context, shell: Shell): SpatialApp {
    val settings = shell.settings
    var target = -1
    var hits = 0
    var misses = 0
    var running = false
    val buttons = mutableListOf<Button>()
    lateinit var live: android.widget.TextView
    lateinit var score: android.widget.TextView
    fun paint() = buttons.forEachIndexed { i, button ->
        val aimed = running && i == target
        button.text = if (aimed) context.getString(R.string.practice_press) else ""
        button.background = glassStates(if (aimed) 0x52E08A else 0xFFFFFF, if (aimed) 0xA0 else 0x1C, context.px(14).toFloat())
    }
    fun pickTarget() {
        target = (0 until 9).filter { it != target }.random()
        paint()
    }
    fun pressed(index: Int) {
        if (!running) return
        if (index != target) misses++
        else if (++hits >= TARGETS) {
            running = false
            score.text = context.getString(if (misses >= 4) R.string.practice_done_misses else R.string.practice_done, misses)
            paint()
            return
        } else pickTarget()
        score.text = context.getString(R.string.practice_score, hits, TARGETS, misses)
    }
    val view = context.scrolling {
        live = text(size = 13f)
        score = text(context.getString(R.string.practice_intro), 13f)
        for (line in 0..2) row(height = context.px(50)) {
            for (column in 0..2) {
                val index = line * 3 + column
                buttons += key("") { pressed(index) }
            }
        }
        row(height = context.px(48)) {
            key(context.getString(R.string.practice_start)) {
                hits = 0; misses = 0; running = true
                score.text = context.getString(R.string.practice_score, 0, TARGETS, 0)
                pickTarget()
            }
            key(context.getString(R.string.calibrate)) { shell.startCalibration() }
        }
        fun adjustPinch(delta: Float) {
            val press = (settings.pressRatio + delta).coerceIn(0.15f, 0.42f)
            settings.pressRatio = press
            settings.releaseRatio = press + 0.15f
        }
        row(height = context.px(48)) {
            key(context.getString(R.string.pinch_easier)) { adjustPinch(0.03f) }
            key(context.getString(R.string.pinch_harder)) { adjustPinch(-0.03f) }
        }
        text(context.getString(R.string.pose_heading), 13f).alpha = 0.75f
        for (pose in HandPose.entries) row(height = context.px(48)) {
            fun label() = poseName(context, pose) + "  →  " + actionName(context, settings.poseAction(pose))
            lateinit var button: Button
            button = key(label()) {
                settings.setPoseAction(pose, PoseAction.entries[(settings.poseAction(pose).ordinal + 1) % PoseAction.entries.size])
                button.text = label()
            }
        }
        text(context.getString(R.string.pose_help), 11f).alpha = 0.7f
    }
    paint()
    val stop = ticker(300) {
        val sample = shell.trackingSample()
        val lines = mutableListOf(context.getString(R.string.practice_hands, sample.hands, sample.latencyMs))
        if (sample.hands > 0) {
            lines += context.getString(R.string.practice_pinch, sample.ratio, settings.pressRatio, settings.releaseRatio)
            lines += context.getString(R.string.practice_pose, sample.pose?.let { poseName(context, it) } ?: "—")
        }
        val problems = TrackingAdvice.problems(sample)
        lines += if (problems.isEmpty()) context.getString(R.string.track_good) else problems.joinToString("\n") {
            "! " + context.getString(
                when (it) {
                    TrackingProblem.FAILED -> R.string.track_failed
                    TrackingProblem.NO_HAND -> R.string.track_no_hand
                    TrackingProblem.TOO_FAR -> R.string.track_far
                    TrackingProblem.TOO_CLOSE -> R.string.track_close
                    TrackingProblem.AT_EDGE -> R.string.track_edge
                    TrackingProblem.SLOW -> R.string.track_slow
                }
            )
        }
        live.text = lines.joinToString("\n")
    }
    return SpatialApp(view, stop)
}
