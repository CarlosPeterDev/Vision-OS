package com.nexus.xr

/** What heat asks of the app. Nothing here stops it; it only does less work. */
data class HeatLimits(
    /** Lowest effects level, on the [PerformanceGovernor] scale. */
    val minLevel: Int,
    /** Highest glass quality. */
    val maxQuality: Int,
    /** Shortest time between two hand-tracking pictures. */
    val handIntervalMs: Long,
    /** Shortest time between two drawn frames; 0 draws at the screen's own rate. */
    val frameIntervalMs: Long,
    /** True when the user should be told that the phone is very hot. */
    val warn: Boolean
) {
    companion object {
        val NONE = HeatLimits(0, 2, 33, 0, false)
    }
}

/**
 * Follows Android's thermal status (0 none, 1 light, 2 moderate, 3 severe, 4 and up critical).
 * Limits tighten at once and relax one step at a time, only after the phone has stayed cooler for
 * [coolAfterMs], so the picture does not keep changing back and forth.
 */
class ThermalPolicy(private val coolAfterMs: Long = 30_000) {
    var step = 0
        private set
    private var coolerSince = -1L

    /** [headroom] is Android's forecast where 1 means severe throttling; NaN when unknown. */
    fun update(status: Int, headroom: Float, nowMs: Long): HeatLimits {
        var wanted = when {
            status >= 4 -> 3
            status == 3 -> 2
            status == 2 -> 1
            else -> 0
        }
        // The forecast lets the app ease off just before the phone starts throttling.
        if (wanted == 0 && !headroom.isNaN() && headroom >= 0.95f) wanted = 1
        when {
            wanted > step -> { step = wanted; coolerSince = -1 }
            wanted == step -> coolerSince = -1
            coolerSince < 0 -> coolerSince = nowMs
            nowMs - coolerSince >= coolAfterMs -> { step--; coolerSince = nowMs }
        }
        return limits(step)
    }

    fun reset() { step = 0; coolerSince = -1 }

    private fun limits(step: Int) = when (step) {
        1 -> HeatLimits(0, 1, 50, 0, false)
        2 -> HeatLimits(1, 1, 66, 32, false)
        3 -> HeatLimits(2, 0, 90, 32, true)
        else -> HeatLimits.NONE
    }
}
