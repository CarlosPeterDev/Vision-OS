package com.nexus.visionos

import android.Manifest
import android.app.AlertDialog
import com.nexus.visionos.xr.XrActivity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.nexus.voice.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var settings: Settings
    private lateinit var secrets: SecretStore
    private lateinit var stack: VoiceStack
    private lateinit var engine: VoiceEngine
    private var keyStamp = ""
    private var continuousRequested = false
    private var activeSession: Job? = null

    private lateinit var status: TextView
    private lateinit var provider: TextView
    private lateinit var heard: TextView
    private lateinit var answer: TextView
    private lateinit var problem: TextView
    private lateinit var quota: TextView

    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private val focus by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(speechAttributes)
            .setOnAudioFocusChangeListener {
                // The system recognizer may take transient focus itself, so that only stops playback.
                val lostForGood = it == AudioManager.AUDIOFOCUS_LOSS
                val interrupted = it < 0 && engine.state.value == VoiceState.SPEAKING
                if (lostForGood || interrupted) engine.cancel()
            }
            .build()
    }

    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) startVoice(continuousRequested) else toast(getString(R.string.mic_denied))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        stack = VoiceStack(this, lifecycleScope, ::fallbackNotice)
        settings = stack.settings
        secrets = stack.secrets
        engine = stack.engine
        buildUi()
        observe()
    }

    private fun buildUi() {
        setContentView(column {
            label(getString(R.string.app_name), 26f)
            button(getString(R.string.start_spatial)) { startSpatial(false) }
            button(getString(R.string.start_headset)) { confirmHeadset() }
            label(getString(R.string.gesture_help))
            status = label(size = 20f)
            provider = label()
            button(getString(R.string.talk)) { requestVoice(false) }
            button(getString(R.string.live)) { requestVoice(true) }
            button(getString(R.string.stop)) { stopVoice() }
            heard = label()
            answer = label(size = 17f)
            problem = label()
            quota = label()
            button(getString(R.string.check_usage)) { checkUsage() }
            button(getString(R.string.voice_settings)) { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
            button(getString(R.string.passthrough)) { startActivity(Intent(this@MainActivity, PassthroughActivity::class.java)) }
            label(
                getString(if (MicSource.supportsBargeIn) R.string.barge_supported else R.string.barge_unsupported)
            )
        })
    }

    private fun startSpatial(headset: Boolean) {
        startActivity(Intent(this, XrActivity::class.java).putExtra(XrActivity.EXTRA_HEADSET, headset))
    }

    private fun confirmHeadset() {
        AlertDialog.Builder(this)
            .setTitle(R.string.start_headset)
            .setMessage(R.string.headset_warning)
            .setPositiveButton(R.string.headset_accept) { _, _ -> startSpatial(true) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    engine.state.collect {
                        status.text = getString(R.string.state, it.name)
                        val recording = it == VoiceState.LISTENING ||
                            (it == VoiceState.SPEAKING && settings.bargeIn && MicSource.supportsBargeIn)
                        title = if (recording) "● MIC" else getString(R.string.app_name)
                        provider.text = providerText(recording)
                        if (it == VoiceState.IDLE || it == VoiceState.ERROR || it == VoiceState.OFFLINE) {
                            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        }
                    }
                }
                launch { engine.transcript.collect { heard.text = it } }
                launch { engine.reply.collect { answer.text = it } }
                launch { engine.error.collect { e -> if (e != null) problem.text = e } }
            }
        }
    }

    private fun providerText(recording: Boolean): String {
        val cloud = settings.cloudVoice && secrets.has(SecretStore.ELEVENLABS)
        val stt = if (cloud && stack.sttHealth.available()) "ElevenLabs Scribe" else "Android"
        val tts = if (cloud && settings.voiceId.isNotBlank() && stack.ttsHealth.available()) "ElevenLabs" else "Android"
        val mic = getString(if (recording) R.string.mic_on else R.string.mic_off)
        return "STT: $stt  |  TTS: $tts\n$mic"
    }

    private fun fallbackNotice(feature: String, e: VoiceException) {
        val reason = when (e.kind) {
            ErrorKind.QUOTA -> getString(R.string.err_quota)
            ErrorKind.AUTH -> getString(R.string.err_auth)
            ErrorKind.PERMISSION -> getString(R.string.err_permission)
            ErrorKind.RATE_LIMIT, ErrorKind.CONCURRENCY -> getString(R.string.err_rate)
            ErrorKind.NETWORK -> getString(R.string.err_network)
            else -> e.message.orEmpty()
        }
        runOnUiThread { problem.text = getString(R.string.fallback, feature, reason) }
    }

    private fun requestVoice(continuous: Boolean) {
        continuousRequested = continuous
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startVoice(continuous)
        else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startVoice(continuous: Boolean) {
        if (audioManager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            toast(getString(R.string.audio_busy))
            return
        }
        problem.text = ""
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val session = engine.start(settings.selectedLanguage, continuous)
        activeSession = session
        session.invokeOnCompletion {
            runOnUiThread { if (activeSession === session) audioManager.abandonAudioFocusRequest(focus) }
        }
    }

    private fun stopVoice() {
        engine.cancel()
        audioManager.abandonAudioFocusRequest(focus)
    }

    private fun checkUsage() {
        quota.text = "…"
        lifecycleScope.launch {
            val session = getString(R.string.session_usage, stack.usage.ttsCharacters, stack.usage.sttMillis / 1000)
            quota.text = try {
                val q = stack.api.quota()
                getString(R.string.quota, q.tier, q.used, q.limit, q.remaining) + "\n" + session
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "${e.message}\n$session"
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // A changed key or voice setting gives the cloud provider another chance.
        val stamp = listOf(secrets.get(SecretStore.ELEVENLABS).hashCode(), settings.voiceId, settings.ttsModel, settings.sttModel).joinToString()
        if (stamp != keyStamp) {
            keyStamp = stamp
            stack.sttHealth.reset()
            stack.ttsHealth.reset()
        }
        provider.text = providerText(false)
    }

    override fun onStop() {
        stopVoice() // no background microphone use
        super.onStop()
    }

    override fun onDestroy() {
        stack.shutdown()
        super.onDestroy()
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}
