package com.nexus.voice

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import java.io.IOException
import java.net.URLEncoder

data class ElevenLabsConfig(
    val voiceId: String = "",
    val ttsModel: String = "eleven_flash_v2_5",
    val sttModel: String = "scribe_v2_realtime",
    val outputSampleRate: Int = 24000,
    val baseUrl: String = "https://api.elevenlabs.io"
)

data class Voice(val id: String, val name: String, val category: String?)
data class Model(val id: String, val name: String)
data class Quota(val tier: String, val used: Long, val limit: Long, val resetUnix: Long?) {
    val remaining: Long get() = (limit - used).coerceAtLeast(0)
}

internal fun enc(v: String): String = URLEncoder.encode(v, "UTF-8")

internal inline fun <T> network(block: () -> T): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: IOException) {
    throw VoiceException(ErrorKind.NETWORK, "Network error: ${e.javaClass.simpleName}", e)
}

/** REST calls. [key] is read per request and is never stored or logged here. */
class ElevenLabsApi(
    private val http: HttpTransport,
    private val config: () -> ElevenLabsConfig,
    private val key: () -> String?
) {
    internal fun headers(): Map<String, String> {
        val k = key()?.takeIf { it.isNotBlank() }
            ?: throw VoiceException(ErrorKind.AUTH, "No ElevenLabs API key configured")
        return mapOf("xi-api-key" to k)
    }

    private suspend fun getJson(path: String): JsonElement {
        val reply = network { http.get(config().baseUrl + path, headers()) }
        if (reply.status !in 200..299) throw ElevenLabsErrors.fromHttp(reply.status, reply.body)
        return try {
            Json.parseToJsonElement(reply.body)
        } catch (e: IllegalArgumentException) {
            throw VoiceException(ErrorKind.UNKNOWN, "Unreadable ElevenLabs response", e)
        }
    }

    suspend fun voices(): List<Voice> {
        val result = mutableListOf<Voice>()
        var token: String? = null
        var pages = 0
        do {
            val page = getJson("/v2/voices?page_size=100" + (token?.let { "&next_page_token=" + enc(it) } ?: "")).jsonObject
            page["voices"]?.jsonArray?.forEach {
                val o = it.jsonObject
                val id = o.str("voice_id") ?: return@forEach
                result += Voice(id, o.str("name") ?: id, o.str("category"))
            }
            val more = (page["has_more"] as? JsonPrimitive)?.booleanOrNull == true
            token = if (more) page.str("next_page_token") else null
        } while (token != null && ++pages < 20)
        return result
    }

    suspend fun ttsModels(): List<Model> = getJson("/v1/models").jsonArray.mapNotNull {
        val o = it.jsonObject
        if ((o["can_do_text_to_speech"] as? JsonPrimitive)?.booleanOrNull != true) return@mapNotNull null
        val id = o.str("model_id") ?: return@mapNotNull null
        Model(id, o.str("name") ?: id)
    }

    suspend fun quota(): Quota {
        val o = getJson("/v1/user/subscription").jsonObject
        fun long(k: String) = (o[k] as? JsonPrimitive)?.longOrNull
        return Quota(
            o.str("tier") ?: "unknown",
            long("character_count") ?: throw VoiceException(ErrorKind.UNKNOWN, "Missing character_count"),
            long("character_limit") ?: throw VoiceException(ErrorKind.UNKNOWN, "Missing character_limit"),
            long("next_character_count_reset_unix")
        )
    }
}

/** Local session counters. These are estimates; [Quota] from the server is authoritative. */
class UsageMeter {
    @Volatile var ttsCharacters = 0L
        private set
    @Volatile var sttMillis = 0L
        private set
    @Synchronized fun addTts(chars: Int) { ttsCharacters += chars }
    @Synchronized fun addStt(millis: Long) { sttMillis += millis }
}

/** HTTP streaming TTS (`POST /v1/text-to-speech/{voice_id}/stream`) with raw PCM output. */
class ElevenLabsTts(
    private val http: HttpTransport,
    private val api: ElevenLabsApi,
    private val config: () -> ElevenLabsConfig,
    private val sink: AudioSink,
    private val usage: UsageMeter
) : TtsProvider {
    override val name = "ElevenLabs"

    override suspend fun speak(text: String, language: Language?, onAudioStart: () -> Unit) {
        val c = config()
        if (c.voiceId.isBlank()) throw VoiceException(ErrorKind.NOT_FOUND, "No ElevenLabs voice selected")
        val url = "${c.baseUrl}/v1/text-to-speech/${enc(c.voiceId)}/stream?output_format=pcm_${c.outputSampleRate}"
        val body = buildJsonObject {
            put("text", text)
            put("model_id", c.ttsModel)
            // Enforced only where known to be supported; multilingual_v2 does not support it.
            if (language != null && c.ttsModel.endsWith("_v2_5")) put("language_code", language.code)
        }.toString()

        var started = false
        var completed = false
        var carry: Byte? = null // network chunks may split a 16-bit sample
        try {
            val reply = network {
                http.postStream(url, api.headers(), body) { bytes, length ->
                    if (length == 0) return@postStream
                    var data = bytes.copyOf(length)
                    carry?.let { data = byteArrayOf(it) + data; carry = null }
                    if (data.size % 2 != 0) { carry = data.last(); data = data.copyOf(data.size - 1) }
                    if (data.isEmpty()) return@postStream
                    if (!started) { sink.start(c.outputSampleRate); started = true; onAudioStart() }
                    sink.write(data, data.size)
                }
            }
            if (reply.status !in 200..299) throw ElevenLabsErrors.fromHttp(reply.status, reply.body)
            usage.addTts(text.length)
            if (started) sink.drain()
            completed = true
        } finally {
            if (!completed && started) sink.stopNow()
        }
    }

    override fun stop() = sink.stopNow()
}
