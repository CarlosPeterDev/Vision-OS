package com.nexus.xr

import kotlin.math.abs
import kotlin.math.sign

/** A cursor in the view, in the same 0..1 coordinates as the hands, moved by a mouse or controller. */
class AirCursor {
    var x = 0.5f
        private set
    var y = 0.5f
        private set

    fun centre() { x = 0.5f; y = 0.5f }

    /** Moves by a share of the view's width and height. */
    fun move(dx: Float, dy: Float) {
        x = (x + dx).coerceIn(0.02f, 0.98f)
        y = (y + dy).coerceIn(0.02f, 0.98f)
    }

    /** One step of a held thumb stick; full tilt crosses most of the view in about a second. */
    fun stick(stickX: Float, stickY: Float, seconds: Float) {
        val time = seconds.coerceIn(0f, 0.1f)
        move(curve(stickX) * time * 0.8f, curve(stickY) * time * 1.1f)
    }

    companion object {
        const val DEAD_ZONE = 0.18f
        /** Ignores a resting stick's drift and gives fine control near the centre. */
        fun curve(value: Float): Float {
            val past = ((abs(value) - DEAD_ZONE) / (1f - DEAD_ZONE)).coerceIn(0f, 1f)
            return sign(value) * past * past
        }
    }
}
