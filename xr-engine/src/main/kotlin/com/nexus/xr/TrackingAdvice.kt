package com.nexus.xr

/** What the hand tracker sees right now. [handSize] is the palm's share of the picture height. */
data class TrackingSample(
    val hands: Int,
    val latencyMs: Long,
    val handSize: Float = 0f,
    val nearEdge: Boolean = false,
    /** Thumb to index gap relative to palm size. */
    val ratio: Float = 1f,
    val pose: HandPose? = null,
    val error: String? = null
)

enum class TrackingProblem { FAILED, NO_HAND, TOO_FAR, TOO_CLOSE, AT_EDGE, SLOW }

object TrackingAdvice {
    fun problems(sample: TrackingSample): List<TrackingProblem> = buildList {
        if (sample.error != null) {
            add(TrackingProblem.FAILED)
            return@buildList
        }
        if (sample.latencyMs > 180) add(TrackingProblem.SLOW)
        if (sample.hands == 0) {
            add(TrackingProblem.NO_HAND)
            return@buildList
        }
        if (sample.handSize < 0.12f) add(TrackingProblem.TOO_FAR)
        if (sample.handSize > 0.55f) add(TrackingProblem.TOO_CLOSE)
        if (sample.nearEdge) add(TrackingProblem.AT_EDGE)
    }
}
