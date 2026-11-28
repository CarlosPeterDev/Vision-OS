package com.nexus.xr

/** Measured frame rates, one a second, for the last [size] seconds. */
class FrameLog(private val size: Int = 60) {
    private val values = ArrayDeque<Int>()
    fun add(fps: Int) {
        values.addLast(fps)
        while (values.size > size) values.removeFirst()
    }
    val count get() = values.size
    fun average() = if (values.isEmpty()) 0 else Math.round(values.average().toFloat())
    fun lowest() = values.minOrNull() ?: 0
}

/**
 * Battery used per hour, worked out only from what the phone reported. It stays unknown until
 * enough time has passed, and starts again after charging.
 */
class BatteryDrain(private val minimumMs: Long = 300_000) {
    private var startMs = -1L
    private var startLevel = 0

    fun add(timeMs: Long, level: Int, charging: Boolean) {
        if (charging || level !in 0..100) {
            startMs = -1
        } else if (startMs < 0 || level > startLevel) {
            startMs = timeMs
            startLevel = level
        }
    }

    fun measuredMinutes(timeMs: Long) = if (startMs < 0) 0 else ((timeMs - startMs) / 60_000).toInt()

    /** Percent per hour, or null while there is not enough to go on. */
    fun perHour(timeMs: Long, level: Int): Int? {
        if (startMs < 0 || timeMs - startMs < minimumMs) return null
        return Math.round((startLevel - level).coerceAtLeast(0) * 3_600_000f / (timeMs - startMs))
    }
}
