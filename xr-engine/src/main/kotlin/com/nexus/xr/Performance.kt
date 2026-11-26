package com.nexus.xr

/**
 * Lowers visual cost when the frame rate stays under [lowFps] and restores it carefully.
 * Level 0 is everything on, 1 is shorter animations and cheaper glass, 2 is no animations.
 */
class PerformanceGovernor(
    private val lowFps: Int = 15,
    private val highFps: Int = 24,
    private val dropAfterMs: Long = 3000,
    private val recoverAfterMs: Long = 10_000
) {
    var level = 0
        private set
    private var lowSince = -1L
    private var highSince = -1L
    private var changedAt = -1L
    private var raisedAt = -1L
    private var recoverWait = recoverAfterMs

    /** [fps] of 0 means not measured yet. */
    fun update(fps: Int, nowMs: Long): Int {
        // Give the previous change time to show in the measurement.
        if (fps <= 0 || (changedAt >= 0 && nowMs - changedAt < SETTLE_MS)) {
            lowSince = -1
            highSince = -1
            return level
        }
        lowSince = if (fps < lowFps) lowSince.takeIf { it >= 0 } ?: nowMs else -1
        highSince = if (fps >= highFps) highSince.takeIf { it >= 0 } ?: nowMs else -1
        if (level < MAX_LEVEL && lowSince >= 0 && nowMs - lowSince >= dropAfterMs) {
            // Falling again soon after recovering means the higher level is too expensive.
            if (raisedAt >= 0 && nowMs - raisedAt < 30_000) recoverWait = (recoverWait * 2).coerceAtMost(120_000)
            change(level + 1, nowMs)
        } else if (level > 0 && highSince >= 0 && nowMs - highSince >= recoverWait) {
            raisedAt = nowMs
            change(level - 1, nowMs)
        }
        return level
    }

    private fun change(to: Int, nowMs: Long) {
        level = to
        changedAt = nowMs
        lowSince = -1
        highSince = -1
    }

    companion object {
        const val MAX_LEVEL = 2
        private const val SETTLE_MS = 2000L
    }
}
