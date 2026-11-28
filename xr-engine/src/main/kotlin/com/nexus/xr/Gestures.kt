package com.nexus.xr

import kotlin.math.*

/** Normalized image point: x right, y down, both in [0,1]. */
data class Point(val x: Float, val y: Float)

/** Which of the user's hands it is. */
enum class Side { LEFT, RIGHT, UNKNOWN }

/** 21 MediaPipe hand landmarks. [id] is stable while the hand stays tracked. */
data class Hand(val id: Int, val points: List<Point>, val side: Side = Side.UNKNOWN)

enum class ClickStyle {
    /** Thumb and index touch. */
    PINCH,
    /** Experimental: push the index finger away from the camera side, judged from apparent hand size. */
    POKE
}

enum class HandChoice { BOTH, LEFT, RIGHT }

data class GestureConfig(
    val style: ClickStyle = ClickStyle.PINCH,
    val pressRatio: Float = 0.27f,
    val releaseRatio: Float = 0.42f,
    /** 0 responsive, 1 balanced, 2 steady. */
    val smoothing: Int = 1,
    val hands: HandChoice = HandChoice.BOTH
)

/** Turns an open-hand and a pinched sample into thresholds that suit one user's hand. */
fun calibratedConfig(base: GestureConfig, openRatio: Float, pinchedRatio: Float): GestureConfig? {
    val span = openRatio - pinchedRatio
    if (span < 0.25f) return null // the two poses were too alike to separate
    val press = (pinchedRatio + span * 0.2f).coerceIn(0.14f, 0.45f)
    val release = (pinchedRatio + span * 0.38f).coerceIn(press + 0.08f, 0.7f)
    return base.copy(pressRatio = press, releaseRatio = release)
}

/** One Euro filter (Casiez et al.): steady when still, responsive when moving. */
class OneEuro(private val minCutoff: Float = 1.4f, private val beta: Float = 6f, private val dCutoff: Float = 1f) {
    private var value: Float? = null
    private var slope = 0f

    private fun alpha(cutoff: Float, dt: Float) = 1f / (1f + 1f / (2f * PI.toFloat() * cutoff * dt))

    fun filter(raw: Float, dt: Float): Float {
        val previous = value
        if (previous == null || dt <= 0f) { value = raw; return raw }
        slope += alpha(dCutoff, dt) * ((raw - previous) / dt - slope)
        val next = previous + alpha(minCutoff + beta * abs(slope), dt) * (raw - previous)
        value = next
        return next
    }
}

enum class PointerPhase { HOVER, DOWN, MOVE, UP, CANCEL }

data class PointerState(
    val handId: Int,
    val phase: PointerPhase,
    val position: Point,
    val openPalm: Boolean,
    /** The inside of an open hand is visible, which means it is turned towards the user's face. */
    val palmToUser: Boolean = false,
    /** Thumb to index gap relative to palm size; used for calibration. */
    val ratio: Float = 1f,
    /** A still hand shape that may have an action of its own. */
    val pose: HandPose? = null
)

/**
 * Seen through the back camera, which looks the same way as the user, a right hand showing its
 * palm has the index knuckle right of the little-finger knuckle. A left hand mirrors that.
 */
fun palmFacesUser(hand: Hand): Boolean {
    if (hand.side == Side.UNKNOWN) return false
    val p = hand.points
    val ix = p[5].x - p[0].x
    val iy = p[5].y - p[0].y
    val lx = p[17].x - p[0].x
    val ly = p[17].y - p[0].y
    val cross = ix * ly - iy * lx
    return if (hand.side == Side.RIGHT) cross < 0 else cross > 0
}

/**
 * Click state machine with hysteresis and frame debouncing. For pinching, the cursor is the
 * midpoint of the thumb and index tips, which moves little while pinching.
 */
class PinchTracker(private val handId: Int, private val config: GestureConfig = GestureConfig()) {
    private var pinched = false
    private var agree = 0
    private val cutoff = floatArrayOf(3.0f, 1.4f, 0.6f)[config.smoothing.coerceIn(0, 2)]
    private val beta = floatArrayOf(8f, 6f, 4f)[config.smoothing.coerceIn(0, 2)]
    private val fx = OneEuro(cutoff, beta)
    private val fy = OneEuro(cutoff, beta)
    private var lastMs = 0L
    private var last = Point(0.5f, 0.5f)
    private var baseline = 0f

    fun update(hand: Hand, aspect: Float, timeMs: Long): PointerState {
        val p = hand.points
        fun dist(a: Int, b: Int) = hypot((p[a].x - p[b].x) * aspect, p[a].y - p[b].y)
        // Palm size makes the threshold independent of how far the hand is from the camera.
        val palm = max(dist(0, 9), dist(5, 17)).coerceAtLeast(1e-4f)
        val ratio = dist(4, 8) / palm
        val dt = if (lastMs == 0L) 0f else (timeMs - lastMs) / 1000f
        lastMs = timeMs
        val wants = if (config.style == ClickStyle.PINCH) {
            if (pinched) ratio < config.releaseRatio else ratio < config.pressRatio
        } else {
            if (baseline == 0f) baseline = palm
            val growth = palm / baseline
            val indexOut = dist(8, 0) > dist(6, 0) * 1.1f
            val poke = if (pinched) growth > POKE_RELEASE else growth > POKE_PRESS && indexOut
            // The resting size follows slowly, and holds still while a poke is in progress.
            if (!poke && !pinched) baseline += (palm - baseline) * (dt / 0.7f).coerceIn(0f, 1f)
            poke
        }
        var phase = if (pinched) PointerPhase.MOVE else PointerPhase.HOVER
        if (wants != pinched) {
            if (++agree >= FRAMES) {
                pinched = wants
                agree = 0
                phase = if (pinched) PointerPhase.DOWN else PointerPhase.UP
            }
        } else agree = 0

        // While a release is being confirmed the fingers are parting, which would drag the cursor.
        val releasing = phase == PointerPhase.UP || (pinched && agree > 0)
        if (!releasing) {
            val rawX = if (config.style == ClickStyle.PINCH) (p[4].x + p[8].x) / 2 else p[8].x
            val rawY = if (config.style == ClickStyle.PINCH) (p[4].y + p[8].y) / 2 else p[8].y
            last = Point(fx.filter(rawX, dt), fy.filter(rawY, dt))
        }
        val extended = FINGERS.count { (tip, middle) -> dist(tip, 0) > dist(middle, 0) * 1.18f }
        val open = !pinched && extended == 4 && ratio > 0.8f
        val pose = if (pinched || open) null else classifyPose(hand, aspect, ratio)
        return PointerState(handId, phase, last, open, open && palmFacesUser(hand), ratio, pose)
    }

    /** Called when the hand disappears. A held pinch is cancelled, never treated as a click. */
    fun lost(): PointerState? = if (pinched) PointerState(handId, PointerPhase.CANCEL, last, false) else null

    private companion object {
        const val FRAMES = 2
        const val POKE_PRESS = 1.09f
        const val POKE_RELEASE = 1.035f
        val FINGERS = listOf(8 to 6, 12 to 10, 16 to 14, 20 to 18)
    }
}

/**
 * Look and pinch: the head aims and a pinch clicks. While pinched, moving the hand offsets the
 * aim, so dragging and scrolling still work with a still head.
 */
class GazeAim {
    private val anchors = mutableMapOf<Int, Point>()

    fun position(state: PointerState): Point {
        if (state.phase == PointerPhase.DOWN) anchors[state.handId] = state.position
        val anchor = anchors[state.handId]
        if (state.phase == PointerPhase.UP || state.phase == PointerPhase.CANCEL) anchors.remove(state.handId)
        return if (anchor == null) CENTRE
        else Point(0.5f + state.position.x - anchor.x, 0.5f + state.position.y - anchor.y)
    }

    private companion object { val CENTRE = Point(0.5f, 0.5f) }
}

class GestureEngine {
    var config = GestureConfig()
        private set
    private class Slot(var tracker: PinchTracker, var seenMs: Long)
    private val slots = mutableMapOf<Int, Slot>()

    /** Processes one inference result. Hands missing for a short grace period are cancelled. */
    fun update(hands: List<Hand>, aspect: Float, timeMs: Long): List<PointerState> {
        val out = mutableListOf<PointerState>()
        for (hand in hands) {
            // A hand of unknown side is always accepted rather than silently ignored.
            val wanted = when (config.hands) {
                HandChoice.BOTH -> true
                HandChoice.LEFT -> hand.side != Side.RIGHT
                HandChoice.RIGHT -> hand.side != Side.LEFT
            }
            if (!wanted) continue
            val slot = slots.getOrPut(hand.id) { Slot(PinchTracker(hand.id, config), timeMs) }
            slot.seenMs = timeMs
            out += slot.tracker.update(hand, aspect, timeMs)
        }
        val iterator = slots.entries.iterator()
        while (iterator.hasNext()) {
            val slot = iterator.next().value
            if (timeMs - slot.seenMs > LOST_MS) {
                slot.tracker.lost()?.let { out += it }
                iterator.remove()
            }
        }
        return out
    }

    /** Applies new settings. Held pinches are cancelled and returned. */
    fun configure(next: GestureConfig): List<PointerState> {
        if (next == config) return emptyList()
        val cancelled = reset()
        config = next
        return cancelled
    }

    fun reset(): List<PointerState> = slots.values.mapNotNull { it.tracker.lost() }.also { slots.clear() }

    companion object { const val LOST_MS = 250L }
}

/** Fires once after the palm stays open, then waits until it closes again. */
class PalmHold(private val holdMs: Long = 900) {
    private var since = -1L
    private var fired = false
    fun update(open: Boolean, timeMs: Long): Boolean {
        if (!open) { since = -1; fired = false; return false }
        if (since < 0) since = timeMs
        if (!fired && timeMs - since >= holdMs) { fired = true; return true }
        return false
    }
}
