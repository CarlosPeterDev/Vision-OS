package com.nexus.visionos

import com.nexus.ai.ChatTurn
import com.nexus.ai.ConversationMemory
import com.nexus.ai.OpenAiProtocol
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * The AI model is configured separately from the voice provider. With no model configured
 * this is a clearly labelled echo test, never a fake AI answer.
 */
class LlmClient(
    private val client: OkHttpClient,
    private val settings: Settings,
    private val secrets: SecretStore,
    private val echoPrefix: () -> String
) {
    val configured get() = settings.llmBaseUrl.isNotBlank() && settings.llmModel.isNotBlank()

    private val memory = ConversationMemory()

    /** Like [reply], but the model also sees the last few turns, including cut-off answers. */
    fun converse(userText: String): Flow<String> {
        if (!configured) return reply(userText)
        val answer = StringBuilder()
        return reply(userText, history = memory.history())
            .onEach { answer.append(it) }
            .onCompletion { memory.add(userText, answer.toString()) }
    }

    fun reply(
        userText: String, system: String = OpenAiProtocol.ASSISTANT_PROMPT, history: List<ChatTurn> = emptyList()
    ): Flow<String> {
        val base = settings.llmBaseUrl
        val model = settings.llmModel
        if (base.isBlank() || model.isBlank()) return flow { emit("${echoPrefix()} $userText") }
        return channelFlow {
            val builder = Request.Builder().url(OpenAiProtocol.endpoint(base))
                .post(OpenAiProtocol.request(model, userText, system, history).toRequestBody("application/json".toMediaType()))
            secrets.get(SecretStore.LLM)?.let { builder.header("Authorization", "Bearer $it") }
            client.execute(builder.build()) { response ->
                if (!response.isSuccessful) throw IOException("AI provider returned HTTP ${response.code}")
                val source = response.body?.source() ?: throw IOException("Empty AI response")
                var received = false
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    currentCoroutineContext().ensureActive()
                    if (OpenAiProtocol.isDone(line)) break
                    OpenAiProtocol.delta(line)?.let { received = true; send(it) }
                }
                if (!received) throw IOException("The AI provider sent no streamed text")
            }
        }.buffer(Channel.UNLIMITED).flowOn(Dispatchers.IO)
    }
}
