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

    @Test fun conversationHistoryIsSentInOrderAndForgotten() {
        var time = 0L
        val memory = ConversationMemory(maxTurns = 2, forgetAfterMs = 100, now = { time })
        memory.add("a", "1"); memory.add("b", "2"); memory.add("c", "3"); memory.add("d", "")
        assertEquals(listOf("b", "c"), memory.history().map { it.user })
        val body = OpenAiProtocol.request("m", "next", "sys", memory.history())
        assertTrue(body.indexOf("\"b\"") < body.indexOf("\"c\"") && body.indexOf("\"c\"") < body.indexOf("\"next\""))
        time = 101
        assertTrue(memory.history().isEmpty())
    }
}
