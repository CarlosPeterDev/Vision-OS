package com.nexus.visionos

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.nexus.voice.ElevenLabsApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class SettingsActivity : ComponentActivity() {
    private lateinit var settings: Settings
    private lateinit var secrets: SecretStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Blocks screenshots and recent-app previews of the key entry screen.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        settings = Settings(this)
        secrets = SecretStore(this)
        val api = ElevenLabsApi(OkHttpTransport(newHttpClient()), settings::elevenLabs) { secrets.get(SecretStore.ELEVENLABS) }

        setContentView(column {
            label(getString(R.string.elevenlabs_section), 20f)
            label(getString(R.string.disclosure))
            keyEditor(SecretStore.ELEVENLABS, R.string.elevenlabs_key)
            check(R.string.use_cloud, settings.cloudVoice) { settings.cloudVoice = it }
            check(R.string.barge_setting, settings.bargeIn) { settings.bargeIn = it }

            val voice = label()
            fun showVoice() { voice.text = getString(R.string.current_voice, settings.voiceName.ifEmpty { "—" }) }
            showVoice()
            button(getString(R.string.choose_voice)) {
                load { api.voices() }.then { voices ->
                    pick(voices.map { "${it.name} (${it.category ?: "?"})" }) {
                        settings.voiceId = voices[it].id
                        settings.voiceName = voices[it].name
                        showVoice()
                    }
                }
            }
            label(getString(R.string.free_voice_note))

            val ttsModel = field(R.string.tts_model, settings.ttsModel)
            button(getString(R.string.choose_model)) {
                load { api.ttsModels() }.then { models -> pick(models.map { "${it.name}\n${it.id}" }) { ttsModel.setText(models[it].id) } }
            }
            val sttModel = field(R.string.stt_model, settings.sttModel)
            button(getString(R.string.choose_model)) {
                val ids = listOf("scribe_v2_realtime", "scribe_v3_live")
                pick(ids) { sttModel.setText(ids[it]) }
            }

            val language = label()
            fun showLanguage() { language.text = getString(R.string.language, settings.language) }
            showLanguage()
            button(getString(R.string.choose_language)) {
                val codes = listOf("auto", "ar", "en")
                pick(listOf(getString(R.string.lang_auto), "العربية", "English")) { settings.language = codes[it]; showLanguage() }
            }

            label(getString(R.string.llm_section), 20f)
            label(getString(R.string.llm_note))
            val url = field(R.string.llm_url, settings.llmBaseUrl)
            val model = field(R.string.llm_model, settings.llmModel)
            keyEditor(SecretStore.LLM, R.string.llm_key)

            button(getString(R.string.save)) {
                val endpoint = url.text.toString().trim()
                if (endpoint.isNotEmpty() && !endpoint.startsWith("https://")) {
                    toast(getString(R.string.https_only))
                } else {
                    // A saved AI key must never follow the endpoint to a different server.
                    if (endpoint != settings.llmBaseUrl && settings.llmBaseUrl.isNotEmpty()) secrets.delete(SecretStore.LLM)
                    settings.llmBaseUrl = endpoint
                    settings.llmModel = model.text.toString().trim()
                    settings.ttsModel = ttsModel.text.toString().trim().ifEmpty { "eleven_flash_v2_5" }
                    settings.sttModel = sttModel.text.toString().trim().ifEmpty { "scribe_v2_realtime" }
                    finish()
                }
            }
        })
    }

    private fun LinearLayout.field(hint: Int, value: String): EditText {
        label(getString(hint))
        return EditText(context).also {
            it.setText(value)
            it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            it.textDirection = View.TEXT_DIRECTION_LTR
            addView(it)
        }
    }

    /** The stored key is never shown again; it can only be replaced or deleted. */
    private fun LinearLayout.keyEditor(name: String, title: Int) {
        val state = label()
        val input = EditText(context).also {
            it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            it.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            it.isSaveEnabled = false
            addView(it)
        }
        fun show() { state.text = getString(title) + ": " + getString(if (secrets.has(name)) R.string.key_saved else R.string.key_missing) }
        show()
        button(getString(R.string.save_key)) {
            val value = input.text.toString().trim()
            if (value.isNotEmpty()) {
                runCatching { secrets.put(name, value) }.onFailure { toast(getString(R.string.keystore_failed)) }
                input.setText("")
                show()
            }
        }
        button(getString(R.string.delete_key)) { secrets.delete(name); show() }
    }

    private fun LinearLayout.check(title: Int, value: Boolean, onChange: (Boolean) -> Unit) {
        addView(CheckBox(context).also {
            it.text = getString(title)
            it.isChecked = value
            it.setOnCheckedChangeListener { _, checked -> onChange(checked) }
        })
    }

    private class Loader<T>(private val run: ((T) -> Unit) -> Unit) {
        fun then(block: (T) -> Unit) = run(block)
    }

    private fun <T> load(request: suspend () -> List<T>) = Loader<List<T>> { done ->
        lifecycleScope.launch {
            try {
                val items = request()
                if (items.isEmpty()) toast(getString(R.string.nothing_found)) else done(items)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun pick(items: List<String>, onPick: (Int) -> Unit) {
        AlertDialog.Builder(this).setItems(items.toTypedArray()) { _, i -> onPick(i) }.show()
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}
