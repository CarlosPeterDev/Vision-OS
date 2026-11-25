package com.nexus.voice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach

/** Decides whether the cloud provider should be tried, shared by STT and TTS. */
class CloudHealth(private val now: () -> Long = System::currentTimeMillis) {
    private var blockedUntil = 0L
    @Volatile var lastError: VoiceException? = null
        private set

    @Synchronized fun available(): Boolean = now() >= blockedUntil

    @Synchronized fun record(error: VoiceException) {
        lastError = error
        val cooldown = when (error.kind) {
            // Needs user action: wait until settings change or the app restarts.
            ErrorKind.AUTH, ErrorKind.QUOTA -> Long.MAX_VALUE
            ErrorKind.PERMISSION -> 10 * 60_000L
            ErrorKind.RATE_LIMIT, ErrorKind.SERVER -> 30_000L
            ErrorKind.CONCURRENCY, ErrorKind.NETWORK -> 5_000L
            else -> 0L
        }
        if (cooldown == 0L) return
        val until = if (cooldown == Long.MAX_VALUE) Long.MAX_VALUE else now() + cooldown
        blockedUntil = maxOf(blockedUntil, until)
    }

    @Synchronized fun reset() { blockedUntil = 0; lastError = null }
}

/** STT and TTS are tracked separately because key permissions can differ per feature. */
class FallbackTts(
    private val primary: TtsProvider,
    private val backup: TtsProvider,
    private val health: CloudHealth,
    private val cloudEnabled: () -> Boolean,
    private val onFallback: (VoiceException) -> Unit = {}
) : TtsProvider {
    override val name = "Auto"

    override suspend fun speak(text: String, language: Language?, onAudioStart: () -> Unit) {
        if (cloudEnabled() && health.available()) {
            var started = false
            try {
                primary.speak(text, language) { started = true; onAudioStart() }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: VoiceException) {
                health.record(e)
                onFallback(e)
                if (started) return // do not repeat words the user already heard
            }
        }
        backup.speak(text, language, onAudioStart)
    }

    override fun stop() { primary.stop(); backup.stop() }
}

class FallbackStt(
    private val primary: SttProvider,
    private val backup: SttProvider,
    private val health: CloudHealth,
    private val cloudEnabled: () -> Boolean,
    private val onFallback: (VoiceException) -> Unit = {}
) : SttProvider {
    override val name = "Auto"

    override fun listen(language: Language?): Flow<SttEvent> = flow {
        if (cloudEnabled() && health.available()) {
            var heard = false
            try {
                emitAll(primary.listen(language).onEach { heard = true })
                return@flow
            } catch (e: VoiceException) {
                health.record(e)
                // The user must repeat after a mid-sentence failure, so surface it.
                if (heard || e.kind == ErrorKind.NO_SPEECH) throw e
                onFallback(e)
            }
        }
        emitAll(backup.listen(language))
    }
}
