package com.nexus.xr

/** The user's own voice shortcut: one phrase that runs several built-in commands. */
class Routine(val name: String, val steps: List<VoiceCommand>)

class RoutineBook(val routines: List<Routine>, val problems: List<Problem>) {
    /** [line] counts from 1. [detail] is the part that was not accepted. */
    class Problem(val line: Int, val kind: Kind, val detail: String)
    enum class Kind { FORMAT, NAME_TAKEN, UNKNOWN_STEP, TOO_MANY }

    fun find(sentence: String): Routine? {
        val heard = VoiceCommands.normalize(sentence)
        return routines.firstOrNull { VoiceCommands.normalize(it.name) == heard }
    }

    companion object {
        const val MAX_ROUTINES = 30

        /** One shortcut per line, written as: name = command; command; command */
        fun parse(text: String): RoutineBook {
            val parser = VoiceCommands()
            val routines = mutableListOf<Routine>()
            val problems = mutableListOf<Problem>()
            val names = HashSet<String>()
            text.lines().forEachIndexed { index, raw ->
                val line = raw.trim()
                if (line.isEmpty()) return@forEachIndexed
                fun reject(kind: Kind, detail: String) { problems += Problem(index + 1, kind, detail) }
                val name = line.substringBefore('=', "").trim()
                val parts = line.substringAfter('=', "").split(';', '؛').map { it.trim() }.filter { it.isNotEmpty() }
                if (VoiceCommands.normalize(name).isEmpty() || parts.isEmpty()) return@forEachIndexed reject(Kind.FORMAT, line)
                // A built-in command keeps its meaning, and so do the words that answer a question.
                if (parser.parse(name) != null || VoiceCommands.isAnswer(name) || !names.add(VoiceCommands.normalize(name))) {
                    return@forEachIndexed reject(Kind.NAME_TAKEN, name)
                }
                if (parts.size > AiControl.MAX_STEPS) return@forEachIndexed reject(Kind.TOO_MANY, name)
                val steps = parts.map { part -> parser.parse(part) ?: return@forEachIndexed reject(Kind.UNKNOWN_STEP, part) }
                if (routines.size >= MAX_ROUTINES) return@forEachIndexed reject(Kind.TOO_MANY, name)
                routines += Routine(name, steps)
            }
            return RoutineBook(routines, problems)
        }
    }
}
