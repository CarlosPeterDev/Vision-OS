package com.nexus.visionos

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.nexus.voice.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Android TextToSpeech fallback. */
class SystemTts(context: Context) : TtsProvider {
    override val name = "Android TextToSpeech"
    private class Pending(val onStart: () -> Unit, val done: CompletableDeferred<Unit> = CompletableDeferred())

    private val ready = CompletableDeferred<Boolean>()
    private val pending = ConcurrentHashMap<String, Pending>()
    private val engine = TextToSpeech(context.applicationContext) { ready.complete(it == TextToSpeech.SUCCESS) }

    init {
        engine.setAudioAttributes(speechAttributes)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String) { pending[id]?.onStart?.invoke() }
            override fun onDone(id: String) { pending[id]?.done?.complete(Unit) }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String) { fail(id) }
            override fun onError(id: String, errorCode: Int) { fail(id) }
            override fun onStop(id: String, interrupted: Boolean) { pending[id]?.done?.complete(Unit) }
        })
    }

    private fun fail(id: String) {
        pending[id]?.done?.completeExceptionally(VoiceException(ErrorKind.UNKNOWN, "Android speech synthesis failed"))
    }

    override suspend fun speak(text: String, language: Language?, onAudioStart: () -> Unit) {
        if (withTimeoutOrNull(5000) { ready.await() } != true) {
            throw VoiceException(ErrorKind.UNKNOWN, "No Android text-to-speech engine is available")
        }
        val arabic = text.any { it in '؀'..'ۿ' }
        val tag = if (arabic) Language.ARABIC.tag else language?.tag ?: Language.ENGLISH.tag
        if (engine.setLanguage(Locale.forLanguageTag(tag)) < TextToSpeech.LANG_AVAILABLE) {
            throw VoiceException(ErrorKind.NOT_FOUND, "No Android voice installed for $tag. Add it in the system text-to-speech settings.")
        }
        val id = UUID.randomUUID().toString()
        val item = Pending(onAudioStart)
        pending[id] = item
        try {
            if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) fail(id)
            item.done.await()
        } finally {
            if (!item.done.isCompleted) engine.stop()
            pending.remove(id)
        }
    }

    override fun stop() {
        engine.stop()
        pending.values.forEach { it.done.complete(Unit) }
    }

    fun shutdown() = engine.shutdown()
}

/**
 * Android's own speech recognition. Offline support depends on the device and its language packs.
 * With [offline] set, the on-device recognizer is used where the phone has one. [onDeviceOnly]
 * then refuses any recognizer that might send audio away.
 */
class SystemStt(
    private val context: Context,
    private val offline: Boolean = false,
    private val onDeviceOnly: () -> Boolean = { false }
) : SttProvider {
    override val name = "Android SpeechRecognizer"

    /** True when the phone has a recognizer that never uses the network. */
    val hasOnDevice: Boolean
        get() = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    override fun listen(language: Language?): Flow<SttEvent> = callbackFlow {
        val local = offline && hasOnDevice
        if (offline && !local && onDeviceOnly()) {
            throw VoiceException(ErrorKind.NOT_FOUND, context.getString(R.string.no_offline_recognizer))
        }
        if (!local && !SpeechRecognizer.isRecognitionAvailable(context)) {
            throw VoiceException(ErrorKind.NOT_FOUND, "This device has no speech recognition service")
        }
        val recognizer = if (local && Build.VERSION.SDK_INT >= 31) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        else SpeechRecognizer.createSpeechRecognizer(context)
        fun first(b: Bundle?) = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onPartialResults(partialResults: Bundle?) {
                first(partialResults).takeIf { it.isNotEmpty() }?.let { trySend(SttEvent.Partial(it)) }
            }
            override fun onResults(results: Bundle?) {
                trySend(SttEvent.Final(first(results)))
                close()
            }
            override fun onError(error: Int) {
                val kind = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> ErrorKind.NO_SPEECH
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> ErrorKind.NETWORK
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> ErrorKind.PERMISSION
                    // Language not supported or its offline pack not downloaded (Android 12 and later).
                    12, 13 -> ErrorKind.NOT_FOUND
                    else -> ErrorKind.UNKNOWN
                }
                close(
                    VoiceException(
                        kind, when {
                            kind == ErrorKind.NO_SPEECH -> "No speech heard"
                            error == 12 || error == 13 -> context.getString(R.string.no_language_pack)
                            else -> "Android recognition error $error"
                        }
                    )
                )
            }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, offline)
        // "Auto" leaves the device's default recognition language in place.
        language?.let { intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, it.tag) }
        recognizer.startListening(intent)
        awaitClose {
            recognizer.cancel()
            recognizer.destroy()
        }
    }.flowOn(Dispatchers.Main.immediate)
}
