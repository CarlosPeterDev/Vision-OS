package com.nexus.visionos

import android.content.Context
import com.nexus.voice.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf

/** Wires the voice engine to Android. Shared by the start screen and the spatial assistant. */
class VoiceStack(context: Context, scope: CoroutineScope, onFallback: (String, VoiceException) -> Unit) {
    val settings = Settings(context)
    val secrets = SecretStore(context)
    val usage = UsageMeter()
    val sttHealth = CloudHealth()
    val ttsHealth = CloudHealth()
    private val client = newHttpClient()
    private val transport = OkHttpTransport(client)
    val api = ElevenLabsApi(transport, settings::elevenLabs) { secrets.get(SecretStore.ELEVENLABS) }
    private val mic = MicSource()
    private val systemTts = SystemTts(context)
    val engine: VoiceEngine
    val stt: SttProvider
    /** Returns a spoken answer for sentences the app handles itself, or null to ask the AI model. */
    var interceptor: ((String) -> String?)? = null

    init {
        val cloudEnabled = { settings.cloudVoice && secrets.has(SecretStore.ELEVENLABS) }
        stt = FallbackStt(
            ScribeStt(OkHttpSockets(client), api, settings::elevenLabs, mic, usage), SystemStt(context.applicationContext),
            sttHealth, cloudEnabled
        ) { onFallback("STT", it) }
        // Without a selected voice, speech output stays on Android and STT can still use Scribe.
        val tts = FallbackTts(
            ElevenLabsTts(transport, api, settings::elevenLabs, TrackSink(), usage), systemTts,
            ttsHealth, { cloudEnabled() && settings.voiceId.isNotBlank() }
        ) { onFallback("TTS", it) }
        val llm = LlmClient(client, settings, secrets) { context.getString(R.string.echo_prefix) }
        val interrupt: (() -> Flow<Unit>)? =
            if (MicSource.supportsBargeIn) ({ if (settings.bargeIn) mic.bargeIn() else emptyFlow() }) else null
        engine = VoiceEngine(scope, stt, tts, interrupt) { heard ->
            interceptor?.invoke(heard)?.let { flowOf(it) } ?: llm.reply(heard)
        }
    }

    fun shutdown() {
        engine.cancel()
        systemTts.shutdown()
    }
}
