package com.nexus.xr

/** A calendar entry with a reminder at [atMillis]. [rung] means its reminder was already shown. */
data class CalendarEvent(val id: Long, val atMillis: Long, val title: String, val rung: Boolean = false)

object EventCodec {
    const val MAX_EVENTS = 300

    fun encode(events: List<CalendarEvent>) = events.joinToString("\n") {
        "${it.id}\t${it.atMillis}\t${if (it.rung) 1 else 0}\t${it.title.replace('\n', ' ').replace('\t', ' ')}"
    }

    fun decode(text: String) = text.lines().mapNotNull { line ->
        val p = line.split('\t', limit = 4)
        if (p.size != 4) return@mapNotNull null
        CalendarEvent(p[0].toLongOrNull() ?: return@mapNotNull null, p[1].toLongOrNull() ?: return@mapNotNull null, p[3], p[2] == "1")
    }

    /** Reminders whose time has come and that were not shown yet. */
    fun due(events: List<CalendarEvent>, nowMillis: Long) = events.filter { !it.rung && it.atMillis <= nowMillis }.sortedBy { it.atMillis }

    /** When full, the oldest finished entries make room first. */
    fun add(events: List<CalendarEvent>, event: CalendarEvent): List<CalendarEvent> {
        val all = (events + event).sortedBy { it.atMillis }
        if (all.size <= MAX_EVENTS) return all
        val drop = all.filter { it.rung && it.id != event.id }.take(all.size - MAX_EVENTS).map { it.id }.toSet()
        return all.filter { it.id !in drop }
    }
}
