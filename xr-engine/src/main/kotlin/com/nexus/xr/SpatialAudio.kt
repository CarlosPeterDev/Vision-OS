package com.nexus.xr

import kotlin.math.*

/** How one mono sound reaches the two ears from a direction. */
data class EarMix(val leftGain: Float, val rightGain: Float, val leftDelay: Int, val rightDelay: Int)

/**
 * A simple two-ear model: the far ear hears the sound a little later and a little quieter.
 * It tells left from right. It does not tell front from back or up from down.
 */
object SpatialAudio {
    private const val HEAD_RADIUS = 0.0875f
    private const val SOUND_SPEED = 343f

    /** [azimuth] in radians: zero straight ahead, positive to the right. */
    fun mix(azimuth: Float, sampleRate: Int): EarMix {
        val side = sin(azimuth)
        // Woodworth's estimate of the extra path to the far ear.
        val angle = asin(abs(side).coerceAtMost(1f))
        val delay = (HEAD_RADIUS / SOUND_SPEED * (angle + sin(angle)) * sampleRate).roundToInt()
        val far = 1f - 0.65f * abs(side)
        return if (side >= 0) EarMix(far, 1f, delay, 0) else EarMix(1f, far, 0, delay)
    }

    /** Interleaved left and right samples. */
    fun render(mono: ShortArray, mix: EarMix): ShortArray {
        val frames = mono.size + max(mix.leftDelay, mix.rightDelay)
        val out = ShortArray(frames * 2)
        for (i in mono.indices) {
            out[(i + mix.leftDelay) * 2] = (mono[i] * mix.leftGain).toInt().toShort()
            out[(i + mix.rightDelay) * 2 + 1] = (mono[i] * mix.rightGain).toInt().toShort()
        }
        return out
    }
}
