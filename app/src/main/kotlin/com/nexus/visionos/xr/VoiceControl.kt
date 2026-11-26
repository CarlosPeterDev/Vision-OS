package com.nexus.visionos.xr

import com.nexus.voice.ErrorKind
import com.nexus.voice.Language
import com.nexus.voice.SttEvent
import com.nexus.voice.SttProvider
import com.nexus.voice.VoiceException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Hands-free listening: one sentence at a time, again and again, until switched off.
 * [heard] finishes before listening restarts, so replies are never heard as commands.
 */
class VoiceControl(
    private val scope: CoroutineScope,
    private val stt: SttProvider,
    private val language: () -> Language?,
    private val partial: (String) -> Unit,
    private val heard: suspend (String) -> Unit,
    /** Called when listening ends by itself, with the reason. */
    private val failed: (String) -> Unit
) {
    private var job: Job? = null
    val active get() = job?.isActive == true

    fun start() {
        if (active) return
        job = scope.launch {
            var failures = 0
            while (isActive) {
                try {
                    var sentence = ""
                    stt.listen(language()).collect {
                        when (it) {
                            is SttEvent.Partial -> partial(it.text)
                            is SttEvent.Final -> sentence = it.text
                        }
                    }
                    failures = 0
                    partial("")
                    if (sentence.isNotBlank()) heard(sentence)
                    delay(150)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    partial("")
                    val kind = (e as? VoiceException)?.kind
                    if (kind == ErrorKind.NO_SPEECH) {
                        delay(200)
                        continue
                    }
                    failures++
                    // A busy recognizer clears quickly; a missing one or a refused permission does not.
                    if (kind == ErrorKind.PERMISSION || kind == ErrorKind.NOT_FOUND || failures >= 5) {
                        job = null
                        failed(e.message ?: e.javaClass.simpleName)
                        return@launch
                    }
                    delay(800L * failures)
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
