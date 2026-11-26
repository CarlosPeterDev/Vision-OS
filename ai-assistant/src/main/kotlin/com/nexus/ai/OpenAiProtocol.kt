package com.nexus.ai

import kotlinx.serialization.json.*

/** LLM wire format. This module has no dependency on any voice provider. */
object OpenAiProtocol {
    fun endpoint(baseUrl: String): String {
        val base = baseUrl.trim().trimEnd('/')
        require(base.startsWith("https://")) { "The AI endpoint must use HTTPS" }
        return "$base/chat/completions"
    }

    const val ASSISTANT_PROMPT = "You are Nexus, a voice assistant. Answer briefly in plain spoken text, in the user's language."

    fun request(model: String, userText: String, system: String = ASSISTANT_PROMPT): String = buildJsonObject {
        put("model", model)
        put("stream", true)
        putJsonArray("messages") {
            addJsonObject {
                put("role", "system")
                put("content", system)
            }
            addJsonObject { put("role", "user"); put("content", userText) }
        }
    }.toString()

    fun isDone(line: String) = line.startsWith("data:") && line.removePrefix("data:").trim() == "[DONE]"

    /** Text delta carried by one server-sent-events line, or null. */
    fun delta(line: String): String? {
        if (!line.startsWith("data:") || isDone(line)) return null
        val root = runCatching { Json.parseToJsonElement(line.removePrefix("data:").trim()) }.getOrNull() as? JsonObject
        val choice = (root?.get("choices") as? JsonArray)?.firstOrNull() as? JsonObject
        val content = (choice?.get("delta") as? JsonObject)?.get("content") as? JsonPrimitive
        return content?.takeIf { it.isString }?.content?.ifEmpty { null }
    }
}
