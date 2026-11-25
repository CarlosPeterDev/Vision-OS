package com.nexus.visionos

import android.annotation.SuppressLint
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.SystemClock
import com.nexus.voice.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Microphone frames of 20 ms at 16 kHz, with echo cancellation where the device has it. */
class MicSource : AudioSource {
    override val sampleRate = 16000
    private val preRoll = ArrayDeque<ShortArray>()
    private var preRollAt = 0L

    override fun frames(): Flow<ShortArray> = flow {
        takePreRoll().forEach { emit(it) }
        emitAll(capture())
    }

    @SuppressLint("MissingPermission") // requested by MainActivity before any session
    private fun capture(): Flow<ShortArray> = flow {
        val min = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 6400)
            )
        } catch (e: SecurityException) {
            throw VoiceException(ErrorKind.PERMISSION, "Microphone permission denied", e)
        }
        var echo: AcousticEchoCanceler? = null
        var noise: NoiseSuppressor? = null
        try {
            if (record.state != AudioRecord.STATE_INITIALIZED) throw VoiceException(ErrorKind.UNKNOWN, "Microphone unavailable")
            if (AcousticEchoCanceler.isAvailable()) echo = AcousticEchoCanceler.create(record.audioSessionId)?.also { it.enabled = true }
            if (NoiseSuppressor.isAvailable()) noise = NoiseSuppressor.create(record.audioSessionId)?.also { it.enabled = true }
            record.startRecording()
            val buffer = ShortArray(320)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = record.read(buffer, 0, buffer.size)
                if (n < 0) throw VoiceException(ErrorKind.UNKNOWN, "Microphone read failed ($n)")
                if (n > 0) emit(buffer.copyOf(n))
            }
        } finally {
            echo?.release()
            noise?.release()
            runCatching { record.stop() }
            record.release()
        }
    }.flowOn(Dispatchers.IO)

    @Synchronized private fun takePreRoll(): List<ShortArray> {
        val fresh = SystemClock.elapsedRealtime() - preRollAt < 2000
        return (if (fresh) preRoll.toList() else emptyList()).also { preRoll.clear() }
    }

    @Synchronized private fun savePreRoll(frames: Collection<ShortArray>) {
        preRoll.clear()
        preRoll.addAll(frames)
        preRollAt = SystemClock.elapsedRealtime()
    }

    /** Emits once when local VAD hears speech, keeping the last 600 ms for the next recognition. */
    fun bargeIn(): Flow<Unit> = flow {
        val vad = VoiceActivityDetector(VadConfig.bargeIn())
        val recent = ArrayDeque<ShortArray>()
        capture().first { frame ->
            recent.addLast(frame)
            if (recent.size > 30) recent.removeFirst()
            vad.process(frame) == VadEvent.SPEECH_START
        }
        savePreRoll(recent)
        emit(Unit)
    }

    companion object {
        /** Without echo cancellation the assistant would likely interrupt itself. */
        val supportsBargeIn: Boolean get() = AcousticEchoCanceler.isAvailable()
    }
}

class TrackSink : AudioSink {
    private val lock = Any()
    private var track: AudioTrack? = null
    private var framesWritten = 0L
    private var rate = 24000

    override fun start(sampleRate: Int) = synchronized(lock) {
        release()
        rate = sampleRate
        framesWritten = 0
        val min = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        track = AudioTrack.Builder()
            .setAudioAttributes(speechAttributes)
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
            )
            .setBufferSizeInBytes(maxOf(min, sampleRate / 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { it.play() }
    }

    override suspend fun write(pcm: ByteArray, length: Int) {
        var offset = 0
        while (offset < length) {
            currentCoroutineContext().ensureActive()
            val n = synchronized(lock) {
                val t = track ?: return
                t.write(pcm, offset, length - offset, AudioTrack.WRITE_NON_BLOCKING)
            }
            if (n < 0) throw VoiceException(ErrorKind.UNKNOWN, "Audio playback failed ($n)")
            offset += n
            framesWritten += n / 2
            if (n == 0) delay(10)
        }
    }

    override suspend fun drain() {
        // stop() in stream mode plays out the buffered tail, including very short clips.
        synchronized(lock) { track?.stop() }
        withTimeoutOrNull(3000) {
            while (synchronized(lock) { track?.let { it.playbackHeadPosition < framesWritten } == true }) delay(20)
        }
        synchronized(lock) { release() }
    }

    override fun stopNow() = synchronized(lock) {
        track?.let { runCatching { it.pause(); it.flush() } }
        release()
    }

    private fun release() {
        track?.release()
        track = null
    }
}

val speechAttributes: AudioAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_ASSISTANT)
    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
    .build()
