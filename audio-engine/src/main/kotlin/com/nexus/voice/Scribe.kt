package com.nexus.voice

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import java.util.Base64

object ScribeProtocol {
    fun url(config: ElevenLabsConfig, language: Language?): String {
        val ws = config.baseUrl.replaceFirst("https://", "wss://")
        return "$ws/v1/speech-to-text/realtime?model_id=${enc(config.sttModel)}" +
            "&audio_format=pcm_16000&commit_strategy=vad" +
            (language?.let { "&language_code=${it.code}" } ?: "")
    }

    fun audioChunk(pcm: ShortArray, sampleRate: Int, commit: Boolean = false): String {
        val bytes = ByteArray(pcm.size * 2)
        for (i in pcm.indices) {
            bytes[2 * i] = pcm[i].toInt().toByte()
            bytes[2 * i + 1] = (pcm[i].toInt() shr 8).toByte()
        }
        return buildJsonObject {
            put("message_type", "input_audio_chunk")
            put("audio_base_64", Base64.getEncoder().encodeToString(bytes))
            put("commit", commit)
            put("sample_rate", sampleRate)
        }.toString()
    }

    /** Returns null for messages that carry nothing for the caller. Throws on server errors. */
    fun parse(message: String): SttEvent? {
        val o = runCatching { Json.parseToJsonElement(message).jsonObject }.getOrNull() ?: return null
        val type = o.str("message_type") ?: return null
        return when (type) {
            "partial_transcript" -> SttEvent.Partial(o.str("text").orEmpty())
            "committed_transcript" -> SttEvent.Final(o.str("text").orEmpty())
            else -> ElevenLabsErrors.fromRealtime(type, o.str("error"))?.let { throw it }
        }
    }
}

/** ElevenLabs realtime Scribe over WebSocket, one committed utterance per [listen]. */
class ScribeStt(
    private val sockets: SocketFactory,
    private val api: ElevenLabsApi,
    private val config: () -> ElevenLabsConfig,
    private val microphone: AudioSource,
    private val usage: UsageMeter,
    /** Ends a session with no transcript activity, so silence cannot keep spending quota. */
    private val idleTimeoutMs: Long = 12_000
) : SttProvider {
    override val name = "ElevenLabs Scribe"

    override fun listen(language: Language?): Flow<SttEvent> = channelFlow {
        require(microphone.sampleRate == 16000) { "Scribe session is configured for 16 kHz PCM" }
        val session = network { sockets.open(ScribeProtocol.url(config(), language), api.headers()) }
        var samples = 0L
        var gotFinal = false
        var lastHeard = System.nanoTime()
        try {
            val upload = launch {
                microphone.frames().collect { frame ->
                    if (!session.send(ScribeProtocol.audioChunk(frame, microphone.sampleRate))) {
                        throw VoiceException(ErrorKind.NETWORK, "Scribe connection closed while sending audio")
                    }
                    samples += frame.size
                }
            }
            val watchdog = launch {
                while (true) {
                    val left = idleTimeoutMs - (System.nanoTime() - lastHeard) / 1_000_000
                    if (left <= 0) throw VoiceException(ErrorKind.NO_SPEECH, "No speech heard")
                    delay(left)
                }
            }
            try {
                network {
                    session.incoming.takeWhile { !gotFinal }.collectUntilFinal { event ->
                        // An empty commit means silence; keep listening.
                        if (event is SttEvent.Final && event.text.isBlank()) return@collectUntilFinal false
                        lastHeard = System.nanoTime()
                        send(event)
                        if (event is SttEvent.Final) gotFinal = true
                        gotFinal
                    }
                }
            } finally {
                watchdog.cancelAndJoin()
                upload.cancelAndJoin()
            }
            if (!gotFinal) throw VoiceException(ErrorKind.NETWORK, "Scribe closed before a final transcript")
        } finally {
            usage.addStt(samples * 1000 / microphone.sampleRate)
            session.close()
        }
    }
}

private class Done : RuntimeException() {
    override fun fillInStackTrace(): Throwable = this
}

private suspend fun Flow<String>.collectUntilFinal(handle: suspend (SttEvent) -> Boolean) {
    val done = Done()
    try {
        collect { raw ->
            val event = ScribeProtocol.parse(raw) ?: return@collect
            if (handle(event)) throw done
        }
    } catch (e: Done) {
        if (e !== done) throw e
    }
}
