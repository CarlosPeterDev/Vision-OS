package com.nexus.xr

class Cue(val startMs: Long, val endMs: Long, val text: String)

/** SubRip (.srt) and WebVTT (.vtt) subtitles. */
class Subtitles(val cues: List<Cue>) {
    /** The text on screen at [positionMs]; empty between lines. */
    fun textAt(positionMs: Long): String {
        var low = 0
        var high = cues.size
        // The first cue that starts after the position; the ones just before it may be showing.
        while (low < high) {
            val middle = (low + high) / 2
            if (cues[middle].startMs <= positionMs) low = middle + 1 else high = middle
        }
        return cues.subList(maxOf(0, low - 4), low).filter { positionMs < it.endMs }.joinToString("\n") { it.text }
    }

    companion object {
        private val TIMES = Regex("((?:\\d+:)?\\d{1,2}:\\d{2}[.,]\\d{1,3})\\s*-->\\s*((?:\\d+:)?\\d{1,2}:\\d{2}[.,]\\d{1,3})")
        private val TAGS = Regex("<[^>]*>|\\{\\\\[^}]*\\}")

        fun parse(text: String): Subtitles {
            val cues = mutableListOf<Cue>()
            val blocks = text.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n').split(Regex("\n[ \t]*\n"))
            for (block in blocks) {
                val lines = block.split('\n')
                val at = lines.indexOfFirst { TIMES.containsMatchIn(it) }
                if (at < 0) continue
                val match = TIMES.find(lines[at])!!
                val body = lines.drop(at + 1).map { it.replace(TAGS, "").trim() }.filter { it.isNotEmpty() }.joinToString("\n")
                val start = millis(match.groupValues[1])
                val end = millis(match.groupValues[2])
                if (body.isNotEmpty() && end > start) cues += Cue(start, end, body)
            }
            return Subtitles(cues.sortedBy { it.startMs })
        }

        private fun millis(time: String): Long {
            val fraction = time.substringAfterLast(if (',' in time) ',' else '.')
            val clock = time.dropLast(fraction.length + 1).split(':').map { it.toLong() }
            val seconds = clock.fold(0L) { total, part -> total * 60 + part }
            return seconds * 1000 + fraction.padEnd(3, '0').toLong()
        }
    }
}
