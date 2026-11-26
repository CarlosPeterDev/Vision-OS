package com.nexus.xr

import kotlin.math.hypot
import kotlin.math.min

enum class PressAction { DOWN, MOVE, UP, CANCEL, LONG_PRESS }

data class PressEvent(val action: PressAction, val x: Float, val y: Float)

/**
 * Decides what a held pointer means before the app sees it. Releasing before [longPressMs] is
 * always a tap, so Android's own short long-press timer never starts early. The DOWN of a tap
 * is delayed until release and delivered together with its UP.
 *
 * Held time only grows from [move] calls, and one gap adds at most [maxStepMs]. A frame that
 * arrives late because the phone lagged therefore cannot turn a tap into a long press.
 */
class PressGate(private val slop: Float, longPressMs: Long = 2000, private val maxStepMs: Long = 250) {
    /** Adjustable in Settings; never shorter than Android users expect. */
    var longPressMs = longPressMs

    private enum class State { IDLE, PENDING, DRAGGING, LONG }

    private var state = State.IDLE
    private var startX = 0f
    private var startY = 0f
    private var held = 0L
    private var lastMs = 0L

    /** Progress of a still hold towards a long press, from 0 to 1. */
    val progress get() = if (state == State.PENDING) (held.toFloat() / longPressMs).coerceIn(0f, 1f) else 0f

    fun down(x: Float, y: Float, timeMs: Long): List<PressEvent> {
        val cancelled = cancel()
        state = State.PENDING
        startX = x
        startY = y
        held = 0
        lastMs = timeMs
        return cancelled
    }

    fun move(x: Float, y: Float, timeMs: Long): List<PressEvent> {
        val moved = hypot(x - startX, y - startY) > slop
        val step = (timeMs - lastMs).coerceAtLeast(0)
        lastMs = timeMs
        return when (state) {
            State.IDLE -> emptyList()
            State.DRAGGING -> listOf(PressEvent(PressAction.MOVE, x, y))
            State.LONG -> if (moved) { state = State.DRAGGING; listOf(PressEvent(PressAction.MOVE, x, y)) } else emptyList()
            State.PENDING -> if (moved) {
                state = State.DRAGGING
                listOf(PressEvent(PressAction.DOWN, startX, startY), PressEvent(PressAction.MOVE, x, y))
            } else {
                held += min(step, maxStepMs)
                if (held >= longPressMs) {
                    state = State.LONG
                    listOf(PressEvent(PressAction.DOWN, startX, startY), PressEvent(PressAction.LONG_PRESS, startX, startY))
                } else emptyList()
            }
        }
    }

    fun up(x: Float, y: Float): List<PressEvent> {
        val was = state
        state = State.IDLE
        return when (was) {
            State.IDLE -> emptyList()
            State.PENDING -> listOf(PressEvent(PressAction.DOWN, startX, startY), PressEvent(PressAction.UP, startX, startY))
            State.DRAGGING -> listOf(PressEvent(PressAction.UP, x, y))
            // The long press already happened, so releasing must not also click.
            State.LONG -> listOf(PressEvent(PressAction.CANCEL, startX, startY))
        }
    }

    /** A lost hand never clicks. */
    fun cancel(): List<PressEvent> {
        val was = state
        state = State.IDLE
        return if (was == State.DRAGGING || was == State.LONG) listOf(PressEvent(PressAction.CANCEL, startX, startY)) else emptyList()
    }
}
