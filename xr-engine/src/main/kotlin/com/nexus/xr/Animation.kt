package com.nexus.xr

import kotlin.math.*

/** Critically damped spring, solved exactly, so long frames never make it unstable. */
class Spring(var value: Float, private var velocity: Float = 0f) {
    fun step(target: Float, dt: Float, omega: Float): Float {
        val offset = value - target
        val slope = velocity + omega * offset
        val decay = exp(-omega * dt)
        value = target + (offset + slope * dt) * decay
        velocity = (velocity - omega * slope * dt) * decay
        return value
    }

    fun snap(target: Float) { value = target; velocity = 0f }
}

class AnimatedWindow(val window: SpatialWindow, val alpha: Float)

/**
 * Smooths window motion and fades windows in and out. Pass its result to [WindowManager.present] so
 * input matches what is drawn.
 */
class WindowAnimator {
    private class State(var window: SpatialWindow) {
        val yaw = Spring(window.yaw)
        // A new window rises into place.
        val pitch = Spring(window.pitch - 0.07f)
        val distance = Spring(window.distance)
        val scale = Spring(window.scale * 0.82f)
        val alpha = Spring(0f)
    }

    private val states = LinkedHashMap<Int, State>()

    /** Windows still fading out; their content surfaces must stay alive until they leave this set. */
    fun isVisible(id: Int) = id in states

    /** [level] is the [PerformanceGovernor] level: 0 smooth, 1 quick, 2 instant. */
    fun update(targets: List<SpatialWindow>, dt: Float, level: Int, held: Set<Int> = emptySet()): List<AnimatedWindow> {
        val shown = targets.filter { !it.minimized }.associateBy { it.id }
        for ((id, target) in shown) states.getOrPut(id) { State(target) }.window = target
        val omega = if (level == 0) 16f else 34f
        val step = dt.coerceIn(0f, 0.25f)
        val result = ArrayList<AnimatedWindow>(states.size)
        val iterator = states.entries.iterator()
        while (iterator.hasNext()) {
            val (id, s) = iterator.next()
            val leaving = id !in shown
            val t = s.window
            val yawTarget = s.yaw.value + atan2(sin(t.yaw - s.yaw.value), cos(t.yaw - s.yaw.value))
            val scaleTarget = if (leaving) t.scale * 0.85f else t.scale
            val alphaTarget = if (leaving) 0f else 1f
            // A window being moved or resized follows the hand without any lag.
            if (level >= PerformanceGovernor.MAX_LEVEL || id in held) {
                s.yaw.snap(yawTarget); s.pitch.snap(t.pitch); s.distance.snap(t.distance)
                s.scale.snap(scaleTarget); s.alpha.snap(alphaTarget)
            } else {
                s.yaw.step(yawTarget, step, omega); s.pitch.step(if (leaving) t.pitch - 0.05f else t.pitch, step, omega)
                s.distance.step(t.distance, step, omega); s.scale.step(scaleTarget, step, omega)
                s.alpha.step(alphaTarget, step, omega)
            }
            if (leaving && s.alpha.value < 0.03f) {
                iterator.remove()
                continue
            }
            result += AnimatedWindow(
                t.copy(yaw = s.yaw.value, pitch = s.pitch.value, distance = s.distance.value, scale = s.scale.value),
                s.alpha.value.coerceIn(0f, 1f)
            )
        }
        // Closing windows first, then the given focus order, which breaks painting ties.
        val order = shown.keys.withIndex().associate { it.value to it.index }
        return result.sortedBy { order[it.window.id] ?: -1 }
    }
}
