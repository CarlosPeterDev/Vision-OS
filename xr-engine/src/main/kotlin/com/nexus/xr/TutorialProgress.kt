package com.nexus.xr

/** What a tutorial lesson accepts as proof. Each lesson watches only its own action. */
enum class Lesson { TAP, SCROLL, MOVE, RESIZE, PALM }

/**
 * Steps through the lessons. A lesson is practised only when its own action happens while it is
 * showing; skipping records nothing, and a lesson practised earlier stays practised.
 */
class TutorialProgress(saved: String = "") {
    private val done = saved.split(',').mapNotNull { name -> Lesson.entries.firstOrNull { it.name == name } }.toMutableSet()
    private var index = 0
    private var baseline = 0

    /** The lesson on screen; null once the last one has been passed. */
    val current: Lesson? get() = Lesson.entries.getOrNull(index)
    val number get() = index + 1
    val practised: Set<Lesson> get() = done.toSet()

    fun encode() = Lesson.entries.filter { it in done }.joinToString(",") { it.name }

    /** Call when a lesson appears, with its action's count so far, so earlier actions do not count. */
    fun begin(count: Int) { baseline = count }

    /** Returns true when the action happened since [begin]; the tutorial then moves on. */
    fun observe(count: Int): Boolean {
        val lesson = current ?: return false
        if (count <= baseline) return false
        done += lesson
        index++
        return true
    }

    fun skip() { if (current != null) index++ }
    fun restart() { index = 0 }
}
