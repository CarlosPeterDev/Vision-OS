package com.nexus.voice

import kotlinx.coroutines.flow.Flow

/** Matches the Nexus orb states. */
enum class VoiceState { IDLE, LISTENING, PROCESSING, SPEAKING, INTERRUPTED, ERROR, OFFLINE }

enum class Language(val tag: String, val code: String) { ENGLISH("en-US", "en"), ARABIC("ar-EG", "ar") }

enum class ErrorKind {
    AUTH, PERMISSION, QUOTA, RATE_LIMIT, CONCURRENCY, SERVER, NETWORK,
    BAD_REQUEST, NOT_FOUND, SESSION_LIMIT, NO_SPEECH, UNKNOWN
}

class VoiceException(val kind: ErrorKind, message: String, cause: Throwable? = null) : Exception(message, cause)

sealed interface SttEvent {
    data class Partial(val text: String) : SttEvent
    data class Final(val text: String) : SttEvent
}

/** The flow completes after one utterance and throws [VoiceException] on failure. */
interface SttProvider {
    val name: String
    fun listen(language: Language?): Flow<SttEvent>
}

/** [speak] suspends until playback ends. Cancelling the caller must stop audio. */
interface TtsProvider {
    val name: String
    suspend fun speak(text: String, language: Language?, onAudioStart: () -> Unit)
    fun stop()
}

/** 16-bit mono PCM output. */
interface AudioSink {
    fun start(sampleRate: Int)
    suspend fun write(pcm: ByteArray, length: Int)
    suspend fun drain()
    fun stopNow()
}

/** 16-bit mono PCM microphone frames. The device is released when collection ends. */
interface AudioSource {
    val sampleRate: Int
    fun frames(): Flow<ShortArray>
}

class HttpReply(val status: Int, val body: String)

/** Transport boundary, so protocol logic is testable without a network. */
interface HttpTransport {
    suspend fun get(url: String, headers: Map<String, String>): HttpReply
    /** POSTs JSON. Calls [onChunk] for a 2xx body, otherwise returns the error body. */
    suspend fun postStream(
        url: String, headers: Map<String, String>, json: String,
        onChunk: suspend (ByteArray, Int) -> Unit
    ): HttpReply
}

interface SocketSession {
    val incoming: Flow<String>
    fun send(text: String): Boolean
    fun close()
}

interface SocketFactory {
    suspend fun open(url: String, headers: Map<String, String>): SocketSession
}
