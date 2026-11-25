package com.nexus.xr

import kotlin.math.*

/** Normalized image point: x right, y down, both in [0,1]. */
data class Point(val x: Float, val y: Float)

/** 21 MediaPipe hand landmarks. [id] is stable while the hand stays tracked. */
data class Hand(val id: Int, val points: List<Point>)

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

data class PointerState(val handId: Int, val phase: PointerPhase, val position: Point, val openPalm: Boolean)

/**
 * Pinch state machine with hysteresis and frame debouncing. The cursor is the midpoint of
 * the thumb and index tips, which moves little while pinching.
 */
class PinchTracker(private val handId: Int) {
    private var pinched = false
    private var agree = 0
    private val fx = OneEuro()
    private val fy = OneEuro()
    private var lastMs = 0L
    private var last = Point(0.5f, 0.5f)

    fun update(hand: Hand, aspect: Float, timeMs: Long): PointerState {
        val p = hand.points
        fun dist(a: Int, b: Int) = hypot((p[a].x - p[b].x) * aspect, p[a].y - p[b].y)
        // Palm size makes the threshold independent of how far the hand is from the camera.
        val palm = max(dist(0, 9), dist(5, 17)).coerceAtLeast(1e-4f)
        val ratio = dist(4, 8) / palm
        val wants = if (pinched) ratio < RELEASE else ratio < PRESS
        var phase = if (pinched) PointerPhase.MOVE else PointerPhase.HOVER
        if (wants != pinched) {
            if (++agree >= FRAMES) {
                pinched = wants
                agree = 0
                phase = if (pinched) PointerPhase.DOWN else PointerPhase.UP
            }
        } else agree = 0

        val dt = if (lastMs == 0L) 0f else (timeMs - lastMs) / 1000f
        lastMs = timeMs
        last = Point(fx.filter((p[4].x + p[8].x) / 2, dt), fy.filter((p[4].y + p[8].y) / 2, dt))
        val extended = FINGERS.count { (tip, middle) -> dist(tip, 0) > dist(middle, 0) * 1.18f }
        return PointerState(handId, phase, last, !pinched && extended == 4 && ratio > 0.8f)
    }

    /** Called when the hand disappears. A held pinch is cancelled, never treated as a click. */
    fun lost(): PointerState? = if (pinched) PointerState(handId, PointerPhase.CANCEL, last, false) else null

    companion object {
        private const val PRESS = 0.27f
        private const val RELEASE = 0.42f
        private const val FRAMES = 2
        private val FINGERS = listOf(8 to 6, 12 to 10, 16 to 14, 20 to 18)
    }
}

class GestureEngine {
    private class Slot(var tracker: PinchTracker, var seenMs: Long)
    private val slots = mutableMapOf<Int, Slot>()

    /** Processes one inference result. Hands missing for a short grace period are cancelled. */
    fun update(hands: List<Hand>, aspect: Float, timeMs: Long): List<PointerState> {
        val out = mutableListOf<PointerState>()
        for (hand in hands) {
            val slot = slots.getOrPut(hand.id) { Slot(PinchTracker(hand.id), timeMs) }
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
