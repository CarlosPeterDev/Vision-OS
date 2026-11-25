package com.nexus.visionos

import android.content.Context
import com.nexus.voice.ElevenLabsConfig
import com.nexus.voice.Language

/** Non-secret preferences. Voice settings and AI model settings are independent. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private fun text(key: String, default: String = "") = prefs.getString(key, default) ?: default
    private fun save(key: String, value: String) = prefs.edit().putString(key, value).apply()

    var voiceId get() = text("voiceId"); set(v) = save("voiceId", v)
    var voiceName get() = text("voiceName"); set(v) = save("voiceName", v)
    var ttsModel get() = text("ttsModel", "eleven_flash_v2_5"); set(v) = save("ttsModel", v)
    var sttModel get() = text("sttModel", "scribe_v2_realtime"); set(v) = save("sttModel", v)
    /** "auto", "en" or "ar". */
    var language get() = text("language", "auto"); set(v) = save("language", v)
    var cloudVoice get() = prefs.getBoolean("cloud", true); set(v) = prefs.edit().putBoolean("cloud", v).apply()
    var bargeIn get() = prefs.getBoolean("bargeIn", true); set(v) = prefs.edit().putBoolean("bargeIn", v).apply()
    var llmBaseUrl get() = text("llmBaseUrl"); set(v) = save("llmBaseUrl", v)
    var llmModel get() = text("llmModel"); set(v) = save("llmModel", v)

    val selectedLanguage: Language?
        get() = Language.entries.firstOrNull { it.code == language }

    fun elevenLabs() = ElevenLabsConfig(voiceId = voiceId, ttsModel = ttsModel, sttModel = sttModel)
}
