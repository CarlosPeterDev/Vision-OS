package com.nexus.xr

enum class Check { CAMERA, CAMERA_UPRIGHT, MOTION, HAND_SEEN, HAND_PINCH, HAND_OUTLINE, MICROPHONE, SPEAKER, SPEECH, INTERRUPT, WALK, HEAT }

enum class Outcome { NOT_RUN, PASS, FAIL }

data class CheckResult(val outcome: Outcome, val detail: String = "")

/** The results of the on-phone test, and the plain-text report the user may share. */
class DeviceReport {
    private val results = LinkedHashMap<Check, CheckResult>().apply { Check.entries.forEach { put(it, CheckResult(Outcome.NOT_RUN)) } }

    @Synchronized fun get(check: Check) = results.getValue(check)

    @Synchronized fun set(check: Check, pass: Boolean, detail: String = "") {
        results[check] = CheckResult(if (pass) Outcome.PASS else Outcome.FAIL, detail)
    }

    /** For checks that watch the app by itself: once passed they stay passed. */
    fun observe(check: Check, passed: Boolean, detail: String) {
        if (passed) set(check, true, detail)
    }

    @Synchronized fun count(outcome: Outcome) = results.values.count { it.outcome == outcome }

    /** [header] lines describe the phone and the app. */
    @Synchronized fun text(header: List<String>): String = buildString {
        appendLine("Nexus Vision OS device test")
        header.forEach { appendLine(it) }
        for ((check, result) in results) {
            append(result.outcome.name.padEnd(8)).append(check.name)
            if (result.detail.isNotEmpty()) append(" - ").append(result.detail.replace('\n', ' '))
            appendLine()
        }
        append("Passed ${count(Outcome.PASS)}, failed ${count(Outcome.FAIL)}, not run ${count(Outcome.NOT_RUN)}")
    }
}

/** Loudness of microphone samples in decibels below full scale; silence is about -90. */
fun peakDb(samples: ShortArray): Float {
    var peak = 0
    for (s in samples) peak = maxOf(peak, kotlin.math.abs(s.toInt()))
    return (20 * kotlin.math.log10(peak.coerceAtLeast(1) / 32768.0)).toFloat()
}
