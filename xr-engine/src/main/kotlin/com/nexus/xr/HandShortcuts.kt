package com.nexus.xr

import kotlin.math.hypot
import kotlin.math.max

/**
 * Still hand shapes that can each be given an action. A fist is left out on purpose: its thumb
 * rests on the index finger, which looks like a pinch.
 */
enum class HandPose { VICTORY, THREE, THUMB_UP, HORNS, CALL }

/** [ratio] is the thumb-to-index gap relative to palm size; near zero means a pinch, which is never a pose. */
fun classifyPose(hand: Hand, aspect: Float, ratio: Float): HandPose? {
    if (ratio <= POSE_MIN_RATIO) return null
    val p = hand.points
    fun dist(a: Int, b: Int) = hypot((p[a].x - p[b].x) * aspect, p[a].y - p[b].y)
    val palm = max(dist(0, 9), dist(5, 17)).coerceAtLeast(1e-4f)
    // A finger is straight when its tip reaches clearly past its middle joint, and folded when it does not.
    val out = BooleanArray(4)
    for ((i, finger) in listOf(8 to 6, 12 to 10, 16 to 14, 20 to 18).withIndex()) {
        val reach = dist(finger.first, 0) / dist(finger.second, 0).coerceAtLeast(1e-4f)
        if (reach > 1.18f) out[i] = true else if (reach > 1.05f) return null // half bent: not a clear shape
    }
    val thumb = dist(4, 17) > dist(3, 17) * 1.08f && dist(4, 5) > palm * 0.55f
    val (index, middle, ring, pinky) = out.toList()
    return when {
        index && middle && !ring && !pinky && !thumb && dist(8, 12) > palm * 0.3f -> HandPose.VICTORY
        index && middle && ring && !pinky && !thumb -> HandPose.THREE
        index && !middle && !ring && pinky && !thumb -> HandPose.HORNS
        !index && !middle && !ring && pinky && thumb -> HandPose.CALL
        !index && !middle && !ring && !pinky && thumb && p[4].y < p[5].y - palm * 0.4f && (1..20).all { p[4].y <= p[it].y } -> HandPose.THUMB_UP
        else -> null
    }
}

const val POSE_MIN_RATIO = 0.55f

/** Fires once when one pose is held, then waits until that pose is let go. */
class PoseHold(private val holdMs: Long = 1000) {
    private var pose: HandPose? = null
    private var since = 0L
    private var fired = false
    fun update(current: HandPose?, timeMs: Long): HandPose? {
        if (current != pose) {
            pose = current
            since = timeMs
            fired = false
        }
        if (current == null || fired || timeMs - since < holdMs) return null
        fired = true
        return current
    }
}

/** What a hand pose can be set to do. [say] is the built-in command it runs. */
enum class PoseAction(val say: String?) {
    NOTHING(null), KEYBOARD("open keyboard"), PHOTO("take photo"), ARRANGE("arrange"), RECENTER("recenter"),
    VOICE(null), NEXT_SCENE("next scene"), CLOSE_WINDOW("close this"), SCROLL_DOWN("scroll down"),
    SCROLL_UP("scroll up"), BACK("go back"), PLAY("play"), PAUSE("pause"), HEADSET(null)
}
