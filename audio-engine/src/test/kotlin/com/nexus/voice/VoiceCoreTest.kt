package com.nexus.voice

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

private class FakeHttp(
    var status: Int = 200, var body: String = "", var chunks: List<ByteArray> = emptyList(),
    var failure: IOException? = null, var gets: MutableList<String> = mutableListOf()
) : HttpTransport {
    var url = ""; var json = ""
    override suspend fun get(url: String, headers: Map<String, String>): HttpReply {
        failure?.let { throw it }
        this.url = url
        return HttpReply(status, if (gets.isEmpty()) body else gets.removeAt(0))
    }
    override suspend fun postStream(url: String, headers: Map<String, String>, json: String, onChunk: suspend (ByteArray, Int) -> Unit): HttpReply {
        failure?.let { throw it }
        this.url = url; this.json = json
        if (status == 200) chunks.forEach { onChunk(it, it.size) }
        return HttpReply(status, body)
    }
}

private class FakeSink : AudioSink {
    val written = mutableListOf<Byte>(); var stops = 0; var drained = false
    override fun start(sampleRate: Int) {}
    override suspend fun write(pcm: ByteArray, length: Int) { assertEquals(0, length % 2); written += pcm.take(length) }
    override suspend fun drain() { drained = true }
    override fun stopNow() { stops++ }
}

private class FakeTts(private val failure: VoiceException? = null, private val startFirst: Boolean = false, private val hang: Boolean = false) : TtsProvider {
    override val name = "fake"
    val spoken = mutableListOf<String>(); var stopped = false
    override suspend fun speak(text: String, language: Language?, onAudioStart: () -> Unit) {
        if (startFirst) onAudioStart()
        failure?.let { throw it }
        spoken += text
        onAudioStart()
        if (hang) awaitCancellation()
    }
    override fun stop() { stopped = true }
}

private class FakeStt(private val events: List<SttEvent> = emptyList(), private val failure: VoiceException? = null) : SttProvider {
    override val name = "fake"; var calls = 0
    override fun listen(language: Language?) = flow {
        calls++
        events.forEach { emit(it) }
        failure?.let { throw it }
    }
}

private class FakeSockets(private val messages: List<String>) : SocketFactory {
    var closed = false; var url = ""
    val sent = mutableListOf<String>()
    override suspend fun open(url: String, headers: Map<String, String>): SocketSession {
        this.url = url
        return object : SocketSession {
            override val incoming = flow { delay(20); messages.forEach { emit(it) } }
            override fun send(text: String): Boolean { sent += text; return true }
            override fun close() { closed = true }
        }
    }
}

private val mic = object : AudioSource {
    override val sampleRate = 16000
    override fun frames() = flow { while (true) { emit(ShortArray(1600)); delay(5) } }
}
private val cfg = { ElevenLabsConfig(voiceId = "v1") }

class VoiceCoreTest {
    private fun api(http: HttpTransport, key: String? = "test-key") = ElevenLabsApi(http, cfg, { key })

    private fun kindOf(block: suspend () -> Unit): ErrorKind = runBlocking {
        try { block(); throw AssertionError("expected VoiceException") } catch (e: VoiceException) { e.kind }
    }

    @Test fun mapsHttpErrors() {
        fun k(s: Int, code: String) = ElevenLabsErrors.fromHttp(s, """{"detail":{"code":"$code","message":"m"}}""").kind
        assertEquals(ErrorKind.QUOTA, k(402, "insufficient_credits"))
        assertEquals(ErrorKind.QUOTA, ElevenLabsErrors.fromHttp(401, """{"detail":{"status":"quota_exceeded"}}""").kind)
        assertEquals(ErrorKind.AUTH, k(401, "invalid_api_key"))
        assertEquals(ErrorKind.CONCURRENCY, k(429, "concurrent_limit_exceeded"))
        assertEquals(ErrorKind.RATE_LIMIT, k(429, "rate_limit_exceeded"))
        assertEquals(ErrorKind.SERVER, ElevenLabsErrors.fromHttp(503, "<html>").kind)
        assertEquals(ErrorKind.BAD_REQUEST, ElevenLabsErrors.fromHttp(422, """{"detail":[]}""").kind)
    }

    @Test fun missingKeyAndNetworkFailures() {
        assertEquals(ErrorKind.AUTH, kindOf { api(FakeHttp(), null).quota() })
        assertEquals(ErrorKind.NETWORK, kindOf { api(FakeHttp(failure = IOException())).quota() })
    }

    @Test fun readsQuotaAndPagedVoices() = runBlocking {
        val q = api(FakeHttp(body = """{"tier":"free","character_count":900,"character_limit":1000,"next_character_count_reset_unix":null}""")).quota()
        assertEquals(100L, q.remaining); assertNull(q.resetUnix)
        val http = FakeHttp(gets = mutableListOf(
            """{"voices":[{"voice_id":"a","name":"A"}],"has_more":true,"next_page_token":"p2"}""",
            """{"voices":[{"voice_id":"b","name":"B"}],"has_more":false,"next_page_token":null}"""))
        assertEquals(listOf("a", "b"), api(http).voices().map { it.id })
        assertTrue(http.url.endsWith("next_page_token=p2"))
    }

    @Test fun listsOnlyTtsModels() = runBlocking {
        val http = FakeHttp(body = """[{"model_id":"x","name":"X","can_do_text_to_speech":true},{"model_id":"y","can_do_text_to_speech":false}]""")
        assertEquals(listOf("x"), api(http).ttsModels().map { it.id })
    }

    @Test fun ttsKeepsSamplesAligned() = runBlocking {
        val http = FakeHttp(chunks = listOf(byteArrayOf(1, 2, 3), byteArrayOf(4, 5), byteArrayOf(6)))
        val sink = FakeSink(); val usage = UsageMeter()
        ElevenLabsTts(http, api(http), cfg, sink, usage).speak("مرحبا", Language.ARABIC) {}
        assertEquals(listOf<Byte>(1, 2, 3, 4, 5, 6), sink.written)
        assertTrue(sink.drained); assertEquals(0, sink.stops); assertEquals(5L, usage.ttsCharacters)
        assertTrue(http.url.endsWith("/v1/text-to-speech/v1/stream?output_format=pcm_24000"))
        assertTrue(http.json.contains("\"language_code\":\"ar\""))
    }

    @Test fun ttsOmitsLanguageForMultilingualAndReportsQuota() {
        val http = FakeHttp(status = 402, body = """{"detail":{"code":"insufficient_credits"}}""")
        val tts = ElevenLabsTts(http, api(http), { cfg().copy(ttsModel = "eleven_multilingual_v2") }, FakeSink(), UsageMeter())
        assertEquals(ErrorKind.QUOTA, kindOf { tts.speak("hi", Language.ENGLISH) {} })
        assertFalse(http.json.contains("language_code"))
    }

    @Test fun scribeUrlAndChunk() {
        val url = ScribeProtocol.url(cfg(), Language.ARABIC)
        assertTrue(url.startsWith("wss://api.elevenlabs.io/v1/speech-to-text/realtime?"))
        assertTrue(url.contains("model_id=scribe_v2_realtime") && url.contains("language_code=ar"))
        assertFalse(ScribeProtocol.url(cfg(), null).contains("language_code"))
        val chunk = ScribeProtocol.audioChunk(shortArrayOf(0x0201), 16000)
        assertTrue(chunk.contains("\"audio_base_64\":\"AQI=\"") && chunk.contains("\"sample_rate\":16000"))
    }

    @Test fun scribeStreamsUntilFinalAndCloses() = runBlocking {
        val sockets = FakeSockets(listOf(
            """{"message_type":"session_started"}""", """{"message_type":"partial_transcript","text":"he"}""",
            """{"message_type":"committed_transcript","text":""}""",
            """{"message_type":"committed_transcript","text":"hello"}""", """{"message_type":"auth_error"}"""))
        val usage = UsageMeter()
        val events = withTimeout(3000) { ScribeStt(sockets, api(FakeHttp()), cfg, mic, usage).listen(null).toList() }
        assertEquals(listOf(SttEvent.Partial("he"), SttEvent.Final("hello")), events)
        assertTrue(sockets.closed); assertTrue(sockets.sent.isNotEmpty()); assertTrue(usage.sttMillis > 0)
    }

    @Test fun scribeQuotaAndEarlyClose() {
        fun run(vararg m: String): ErrorKind {
            val s = FakeSockets(m.toList())
            val kind = kindOf { withTimeout(3000) { ScribeStt(s, api(FakeHttp()), cfg, mic, UsageMeter()).listen(null).toList() } }
            assertTrue(s.closed)
            return kind
        }
        assertEquals(ErrorKind.QUOTA, run("""{"message_type":"quota_exceeded","error":"x"}"""))
        assertEquals(ErrorKind.NETWORK, run())
    }

    @Test fun scribeSilenceTimesOutAndCloses() {
        var closed = false
        val silent = object : SocketFactory {
            override suspend fun open(url: String, headers: Map<String, String>) = object : SocketSession {
                override val incoming = flow<String> { awaitCancellation() }
                override fun send(text: String) = true
                override fun close() { closed = true }
            }
        }
        val stt = ScribeStt(silent, api(FakeHttp()), cfg, mic, UsageMeter(), idleTimeoutMs = 100)
        assertEquals(ErrorKind.NO_SPEECH, kindOf { withTimeout(3000) { stt.listen(null).toList() } })
        assertTrue(closed)
    }

    private fun tone(amplitude: Int) = ShortArray(320) { (if (it % 2 == 0) amplitude else -amplitude).toShort() }

    @Test fun vadHasAttackAndRelease() {
        val vad = VoiceActivityDetector()
        repeat(20) { assertNull(vad.process(tone(20))) }
        val loud = (1..10).mapNotNull { vad.process(tone(8000)) }
        assertEquals(listOf(VadEvent.SPEECH_START), loud)
        repeat(30) { assertNull(vad.process(tone(20))) } // 600 ms is shorter than release
        assertEquals(listOf(VadEvent.SPEECH_END), (1..10).mapNotNull { vad.process(tone(20)) })
    }

    @Test fun vadIgnoresShortClick() {
        val vad = VoiceActivityDetector(VadConfig.bargeIn())
        repeat(5) { assertNull(vad.process(tone(8000))) }
        repeat(10) { assertNull(vad.process(tone(20))) }
    }

    @Test fun ttsFallsBackOnQuotaAndStaysThere() = runBlocking {
        var now = 0L
        val health = CloudHealth { now }
        val primary = FakeTts(VoiceException(ErrorKind.QUOTA, "q")); val backup = FakeTts()
        val tts = FallbackTts(primary, backup, health, { true })
        tts.speak("one", null) {}; now = 1_000_000_000; tts.speak("two", null) {}
        assertEquals(listOf("one", "two"), backup.spoken); assertFalse(health.available())
        health.reset(); assertTrue(health.available())
    }

    @Test fun ttsDoesNotRepeatPartiallyPlayedSentence() = runBlocking {
        val backup = FakeTts()
        FallbackTts(FakeTts(VoiceException(ErrorKind.NETWORK, "n"), startFirst = true), backup, CloudHealth(), { true }).speak("one", null) {}
        assertTrue(backup.spoken.isEmpty())
    }

    @Test fun networkCooldownExpires() {
        var now = 0L
        val health = CloudHealth { now }
        health.record(VoiceException(ErrorKind.NETWORK, "n"))
        assertFalse(health.available()); now = 5_000; assertTrue(health.available())
    }

    @Test fun sttFallbackAndDisabledCloud() = runBlocking {
        val backup = FakeStt(listOf(SttEvent.Final("b")))
        val primary = FakeStt(failure = VoiceException(ErrorKind.AUTH, "a"))
        assertEquals(listOf(SttEvent.Final("b")), FallbackStt(primary, backup, CloudHealth(), { true }).listen(null).toList())
        val unused = FakeStt()
        FallbackStt(unused, backup, CloudHealth(), { false }).listen(null).toList()
        assertEquals(0, unused.calls)
    }

    @Test fun sttMidSentenceFailureSurfaces() {
        val primary = FakeStt(listOf(SttEvent.Partial("he")), VoiceException(ErrorKind.NETWORK, "n"))
        assertEquals(ErrorKind.NETWORK, kindOf { FallbackStt(primary, FakeStt(), CloudHealth(), { true }).listen(null).toList() })
    }

    @Test fun chunksEnglishArabicAndDecimals() {
        val c = SentenceChunker()
        assertEquals(emptyList<String>(), c.push("The value is 3."))
        assertEquals(listOf("The value is 3.14 today."), c.push("14 today. إزيك"))
        assertEquals(listOf("إزيك عامل إيه النهارده؟"), c.push(" عامل إيه النهارده؟ تمام"))
        assertEquals("تمام", c.flush()); assertNull(c.flush())
        val long = SentenceChunker(maxChars = 30).push("word ".repeat(20))
        assertTrue(long.isNotEmpty() && long.all { it.length <= 30 })
    }

    @Test fun engineCompletesTurn() = runBlocking {
        val tts = FakeTts()
        val engine = VoiceEngine(this, FakeStt(listOf(SttEvent.Final("hi"))), tts, null) { flowOf("Hello there friend. ", "Bye now") }
        engine.start(Language.ENGLISH)
        coroutineContext[Job]!!.children.toList().joinAll()
        assertEquals(listOf("Hello there friend.", "Bye now"), tts.spoken)
        assertEquals(VoiceState.IDLE, engine.state.value)
    }

    @Test fun engineBargeInStopsSpeechAndModel() = runBlocking {
        val tts = FakeTts(hang = true)
        var modelCancelled = false
        val engine = VoiceEngine(this, FakeStt(listOf(SttEvent.Final("hi"))), tts, { flow { delay(30); emit(Unit) } }) {
            flow {
                try { emit("This is a long answer. "); awaitCancellation() } finally { modelCancelled = true }
            }
        }
        engine.start(null)
        withTimeout(3000) { engine.state.first { it == VoiceState.INTERRUPTED } }
        assertTrue(tts.stopped && modelCancelled)
    }

    @Test fun engineReportsOffline() = runBlocking {
        val engine = VoiceEngine(this, FakeStt(failure = VoiceException(ErrorKind.NETWORK, "down")), FakeTts(), null) { emptyFlow() }
        engine.start(null)
        withTimeout(3000) { engine.state.first { it == VoiceState.OFFLINE } }
        assertEquals("down", engine.error.value)
    }
}
