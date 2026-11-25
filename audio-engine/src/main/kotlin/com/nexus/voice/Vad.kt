package com.nexus.voice

import kotlin.math.log10
import kotlin.math.sqrt

enum class VadEvent { SPEECH_START, SPEECH_END }

data class VadConfig(
    val sampleRate: Int = 16000,
    val minLevelDb: Double = -45.0,
    val marginDb: Double = 10.0,
    val attackMs: Int = 100,
    val releaseMs: Int = 700
) {
    companion object {
        /** Stricter settings while the assistant is speaking, to resist leftover echo. */
        fun bargeIn() = VadConfig(minLevelDb = -35.0, marginDb = 15.0, attackMs = 240)
    }
}

/**
 * Energy-based detector with an adaptive noise floor and hysteresis.
 * It cannot tell speech from other loud sounds. Used on-device for barge-in.
 */
class VoiceActivityDetector(private val config: VadConfig = VadConfig()) {
    var isSpeaking = false
        private set
    private var floorDb = -60.0
    private var loudMs = 0.0
    private var quietMs = 0.0

    fun process(frame: ShortArray): VadEvent? {
        if (frame.isEmpty()) return null
        val ms = frame.size * 1000.0 / config.sampleRate
        var sum = 0.0
        for (s in frame) { val v = s / 32768.0; sum += v * v }
        val db = 20 * log10(sqrt(sum / frame.size) + 1e-9)
        val threshold = maxOf(config.minLevelDb, floorDb + config.marginDb)
        val loud = db > if (isSpeaking) threshold - 3 else threshold
        if (!loud && !isSpeaking) floorDb = (floorDb * 0.95 + db * 0.05).coerceIn(-80.0, -30.0)
        if (loud) { loudMs += ms; quietMs = 0.0 } else { quietMs += ms; loudMs = 0.0 }
        return when {
            !isSpeaking && loudMs >= config.attackMs -> { isSpeaking = true; VadEvent.SPEECH_START }
            isSpeaking && quietMs >= config.releaseMs -> { isSpeaking = false; VadEvent.SPEECH_END }
            else -> null
        }
    }
}
