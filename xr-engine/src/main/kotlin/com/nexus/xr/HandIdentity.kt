package com.nexus.xr

import kotlin.math.hypot

/** Keeps a stable id per hand by matching each wrist to the nearest one in the last result. */
class HandIdentity(private val maxJump: Float = 0.3f) {
    private var previous = emptyMap<Int, Point>()

    fun assign(hands: List<List<Point>>, sides: List<Side> = emptyList()): List<Hand> {
        val free = previous.toMutableMap()
        val used = mutableSetOf<Int>()
        val result = hands.mapIndexed { index, points ->
            val wrist = points[0]
            val match = free.minByOrNull { hypot(it.value.x - wrist.x, it.value.y - wrist.y) }
                ?.takeIf { hypot(it.value.x - wrist.x, it.value.y - wrist.y) <= maxJump }?.key
            if (match != null) free.remove(match)
            // Two slots are enough for two hands; avoid ids of hands that may return shortly.
            val id = match ?: generateSequence(0) { it + 1 }.first { it !in used && it !in previous }
            used += id
            Hand(id, points, sides.getOrElse(index) { Side.UNKNOWN })
        }
        previous = result.associate { it.id to it.points[0] }
        return result
    }
}
