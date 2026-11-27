package com.nexus.xr

import kotlin.math.roundToInt

/** Where one eye's picture sits on the screen, in pixels, measured from the bottom left. */
data class EyeRect(val x: Int, val y: Int, val width: Int, val height: Int) {
    val aspect get() = width.toFloat() / height
}

/**
 * Places the two headset pictures so each one sits behind its lens. Distances are fractions of the
 * screen: [gap] of its width, sideways moves of half its width (one eye's side), up and down of its
 * height. A positive gap moves the pictures apart; positive moves go right and up.
 */
data class HeadsetFit(
    val gap: Float = 0f,
    val width: Float = 1f,
    val height: Float = 1f,
    val leftX: Float = 0f,
    val leftY: Float = 0f,
    val rightX: Float = 0f,
    val rightY: Float = 0f
) {
    /** [side] is 0 for the left eye and 1 for the right. */
    fun eye(side: Int, screenWidth: Int, screenHeight: Int): EyeRect {
        val half = screenWidth / 2
        val w = (half * width).roundToInt().coerceAtLeast(16)
        val h = (screenHeight * height).roundToInt().coerceAtLeast(16)
        val apart = (if (side == 0) -gap else gap) / 2 * screenWidth
        val centreX = half * side + half / 2f + apart + (if (side == 0) leftX else rightX) * half
        val centreY = screenHeight / 2f + (if (side == 0) leftY else rightY) * screenHeight
        return EyeRect((centreX - w / 2f).roundToInt(), (centreY - h / 2f).roundToInt(), w, h)
    }

    /** Drags one eye's picture by a distance given as fractions of the screen; [up] is positive upwards. */
    fun dragged(side: Int, right: Float, up: Float): HeadsetFit =
        if (side == 0) copy(leftX = move(leftX + right * 2), leftY = move(leftY + up))
        else copy(rightX = move(rightX + right * 2), rightY = move(rightY + up))

    /** Makes both pictures larger or smaller together, keeping their shape. */
    fun scaled(factor: Float): HeadsetFit {
        val allowed = factor.coerceIn(
            maxOf(MIN_SIZE / width, MIN_SIZE / height), minOf(MAX_SIZE / width, MAX_SIZE / height)
        )
        return copy(width = size(width * allowed), height = size(height * allowed))
    }

    fun clamped() = HeadsetFit(
        gap.coerceIn(-MAX_GAP, MAX_GAP), size(width), size(height), move(leftX), move(leftY), move(rightX), move(rightY)
    )

    companion object {
        const val MIN_SIZE = 0.4f
        const val MAX_SIZE = 1f
        const val MAX_GAP = 0.3f
        const val MAX_MOVE = 0.35f
        private fun size(value: Float) = value.coerceIn(MIN_SIZE, MAX_SIZE)
        private fun move(value: Float) = value.coerceIn(-MAX_MOVE, MAX_MOVE)
    }
}
