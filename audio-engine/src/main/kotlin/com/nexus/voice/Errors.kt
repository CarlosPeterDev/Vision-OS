package com.nexus.voice

import kotlinx.serialization.json.*

object ElevenLabsErrors {
    /** Maps the documented `{"detail":{type,code,message,status}}` shape. */
    fun fromHttp(status: Int, body: String): VoiceException {
        val detail = runCatching { Json.parseToJsonElement(body).jsonObject["detail"] as? JsonObject }.getOrNull()
        val code = detail.str("code")
        val legacy = detail.str("status")
        val message = detail.str("message")
        val ids = listOfNotNull(code, legacy)
        val kind = when {
            status == 402 && "insufficient_credits" in ids -> ErrorKind.QUOTA
            ids.any { it == "quota_exceeded" || it == "insufficient_credits" } -> ErrorKind.QUOTA
            "concurrent_limit_exceeded" in ids -> ErrorKind.CONCURRENCY
            status == 429 -> ErrorKind.RATE_LIMIT
            status == 401 && ids.any { it == "missing_permissions" || it == "insufficient_permissions" } -> ErrorKind.PERMISSION
            status == 401 -> ErrorKind.AUTH
            status == 402 || status == 403 -> ErrorKind.PERMISSION
            status == 404 -> ErrorKind.NOT_FOUND
            status == 400 || status == 422 -> ErrorKind.BAD_REQUEST
            status >= 500 -> ErrorKind.SERVER
            else -> ErrorKind.UNKNOWN
        }
        return VoiceException(kind, "ElevenLabs HTTP $status${message?.let { ": $it" } ?: ""}")
    }

    /** Returns null when [type] is not a documented realtime STT error. */
    fun fromRealtime(type: String, message: String?): VoiceException? {
        val kind = when (type) {
            "auth_error" -> ErrorKind.AUTH
            "unaccepted_terms" -> ErrorKind.PERMISSION
            "quota_exceeded" -> ErrorKind.QUOTA
            "rate_limited", "commit_throttled" -> ErrorKind.RATE_LIMIT
            "queue_overflow", "resource_exhausted", "transcriber_error" -> ErrorKind.SERVER
            "session_time_limit_exceeded" -> ErrorKind.SESSION_LIMIT
            "input_error", "invalid_request", "chunk_size_exceeded" -> ErrorKind.BAD_REQUEST
            "insufficient_audio_activity" -> ErrorKind.NO_SPEECH
            "error" -> ErrorKind.UNKNOWN
            else -> return null
        }
        return VoiceException(kind, "Scribe $type${message?.let { ": $it" } ?: ""}")
    }
}

internal fun JsonObject?.str(key: String): String? = (this?.get(key) as? JsonPrimitive)?.contentOrNull
