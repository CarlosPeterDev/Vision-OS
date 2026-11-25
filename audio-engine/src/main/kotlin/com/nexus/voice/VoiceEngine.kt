package com.nexus.voice

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

/**
 * Turn coordinator. It knows nothing about any LLM: the reply is just a [Flow] of text
 * produced by [respond]. Call all public methods from the thread that owns [scope].
 */
class VoiceEngine(
    private val scope: CoroutineScope,
    private val stt: SttProvider,
    private val tts: TtsProvider,
    /** Emits once when the user talks over playback. Null disables voice barge-in. */
    private val bargeIn: (() -> Flow<Unit>)?,
    private val respond: (String) -> Flow<String>
) {
    private val _state = MutableStateFlow(VoiceState.IDLE)
    val state: StateFlow<VoiceState> = _state
    private val _transcript = MutableStateFlow("")
    val transcript: StateFlow<String> = _transcript
    private val _reply = MutableStateFlow("")
    val reply: StateFlow<String> = _reply
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private var job: Job? = null

    /** Starts one turn; in [continuous] mode it listens again after each reply. */
    fun start(language: Language?, continuous: Boolean = false): Job {
        val previous = job
        previous?.cancel()
        tts.stop()
        return scope.launch {
            previous?.join() // the microphone must be released before reopening it
            do {
                val keepGoing = turn(language)
            } while (continuous && keepGoing)
        }.also { job = it }
    }

    /** Stops speech, model streaming and recognition. */
    fun cancel() {
        job?.cancel()
        tts.stop()
        _state.value = VoiceState.IDLE
    }

    private suspend fun turn(language: Language?): Boolean {
        _error.value = null
        _reply.value = ""
        _transcript.value = ""
        try {
            _state.value = VoiceState.LISTENING
            var heard = ""
            stt.listen(language).collect {
                when (it) {
                    is SttEvent.Partial -> _transcript.value = it.text
                    is SttEvent.Final -> { heard = it.text; _transcript.value = it.text }
                }
            }
            if (heard.isBlank()) { _state.value = VoiceState.IDLE; return false }
            _state.value = VoiceState.PROCESSING
            if (speakReply(respond(heard), language)) {
                _state.value = VoiceState.INTERRUPTED
            } else {
                _state.value = VoiceState.IDLE
            }
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _error.value = e.message ?: e.javaClass.simpleName
            _state.value = if ((e as? VoiceException)?.kind == ErrorKind.NETWORK) VoiceState.OFFLINE else VoiceState.ERROR
            return false
        }
    }

    /** Returns true when the user interrupted. */
    private suspend fun speakReply(text: Flow<String>, language: Language?): Boolean = coroutineScope {
        var interrupted = false
        var monitor: Job? = null
        val speaking = launch {
            val sentences = Channel<String>(capacity = 8)
            launch {
                val chunker = SentenceChunker()
                text.collect { delta ->
                    _reply.value += delta
                    chunker.push(delta).forEach { sentences.send(it) }
                }
                chunker.flush()?.let { sentences.send(it) }
                sentences.close()
            }
            for (sentence in sentences) {
                tts.speak(sentence, language) { _state.value = VoiceState.SPEAKING }
            }
        }
        if (bargeIn != null) {
            monitor = launch {
                state.first { it == VoiceState.SPEAKING }
                try {
                    bargeIn.invoke().first()
                    interrupted = true
                    speaking.cancel()
                    tts.stop()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Barge-in is optional; playback continues and tap-to-stop still works.
                    _error.value = "Voice interruption unavailable: ${e.message}"
                }
            }
        }
        speaking.join()
        monitor?.cancelAndJoin()
        interrupted
    }
}
