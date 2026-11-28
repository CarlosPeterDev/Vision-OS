package com.nexus.xr

/**
 * A crash report that is safe to share: which code failed, never what it was working on.
 * Error messages are left out because they can quote typed text, file names or web addresses.
 */
object CrashText {
    fun of(error: Throwable): String = buildString {
        var current: Throwable? = error
        val seen = HashSet<Throwable>()
        var first = true
        while (current != null && seen.add(current) && seen.size <= 4) {
            if (!first) append("Caused by: ")
            first = false
            appendLine(current.javaClass.name)
            current.stackTrace.take(25).forEach { appendLine("  at ${it.className}.${it.methodName}:${it.lineNumber}") }
            current = current.cause
        }
    }
}
