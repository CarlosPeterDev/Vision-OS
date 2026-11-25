package com.nexus.ai

import org.junit.Assert.*
import org.junit.Test

class SseTest {
    @Test fun parsesDeltas() {
        assertEquals("أهلا", OpenAiProtocol.delta("""data: {"choices":[{"delta":{"content":"أهلا"}}]}"""))
        assertNull(OpenAiProtocol.delta("""data: {"choices":[{"delta":{"role":"assistant","content":null}}]}"""))
        assertNull(OpenAiProtocol.delta("data: [DONE]"))
        assertNull(OpenAiProtocol.delta(": keep-alive"))
        assertNull(OpenAiProtocol.delta("data: {broken"))
        assertTrue(OpenAiProtocol.isDone("data: [DONE]"))
    }

    @Test fun requiresHttps() {
        assertEquals("https://a.test/v1/chat/completions", OpenAiProtocol.endpoint("https://a.test/v1/"))
        assertThrows(IllegalArgumentException::class.java) { OpenAiProtocol.endpoint("http://a.test/v1") }
    }
}
