package com.nexus.visionos

import android.content.Context
import android.content.Intent
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

/** Android SpeechRecognizer fallback. Offline support depends on the device and language packs. */
class SystemStt(private val context: Context) : SttProvider {
    override val name = "Android SpeechRecognizer"

    override fun listen(language: Language?): Flow<SttEvent> = callbackFlow {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            throw VoiceException(ErrorKind.NOT_FOUND, "This device has no speech recognition service")
        }
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
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
                    else -> ErrorKind.UNKNOWN
                }
                close(VoiceException(kind, if (kind == ErrorKind.NO_SPEECH) "No speech heard" else "Android recognition error $error"))
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
        // "Auto" leaves the device's default recognition language in place.
        language?.let { intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, it.tag) }
        recognizer.startListening(intent)
        awaitClose {
            recognizer.cancel()
            recognizer.destroy()
        }
    }.flowOn(Dispatchers.Main.immediate)
}
