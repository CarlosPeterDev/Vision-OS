package com.nexus.visionos.xr

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import com.nexus.xr.SpatialAudio
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/** Short synthesized interface sounds and vibration ticks. No audio files. */
class Feedback(context: Context, private val settings: XrSettings) {
    private val vibrator = context.getSystemService(Vibrator::class.java)

    private val rate = 44100

    private fun wave(frequency: Double, millis: Int, volume: Double) = ShortArray(rate * millis / 1000) {
        val t = it.toDouble() / rate
        val fade = minOf(1.0, it / 120.0) * exp(-t * 4500 / millis)
        (sin(2 * PI * frequency * t) * fade * volume * Short.MAX_VALUE).toInt().toShort()
    }

    private val hoverWave = wave(1500.0, 25, 0.05)
    private val clickWave = wave(880.0, 60, 0.16)
    /** Two rising notes, for reminders. */
    private val chimeWave = wave(660.0, 220, 0.3) + wave(990.0, 380, 0.3)

    /** One player per sound and direction, made when first needed. */
    private val tracks = HashMap<Pair<ShortArray, Int>, AudioTrack?>()

    private fun track(mono: ShortArray, direction: Int): AudioTrack? = tracks.getOrPut(mono to direction) {
        runCatching {
            val samples = SpatialAudio.render(mono, SpatialAudio.mix(direction * STEP, rate))
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                )
                .setAudioFormat(
                    AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
                )
                .setBufferSizeInBytes(samples.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
                .also { it.write(samples, 0, samples.size) }
        }.getOrNull()
    }

    private fun play(mono: ShortArray, azimuth: Float) {
        if (!settings.sounds) return
        val direction = if (settings.spatialSound) (azimuth / STEP).roundToInt().coerceIn(-4, 4) else 0
        val track = track(mono, direction) ?: return
        runCatching { track.stop(); track.reloadStaticData(); track.play() }
    }

    private fun tick(millis: Long, strength: Int) {
        if (!settings.haptics || vibrator?.hasVibrator() != true) return
        runCatching {
            vibrator.vibrate(
                if (Build.VERSION.SDK_INT >= 29 && millis >= 20) VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
                else VibrationEffect.createOneShot(millis, strength)
            )
        }
    }

    /** [azimuth] in radians says where the sound comes from: zero ahead, positive to the right. */
    fun hover(azimuth: Float = 0f) { play(hoverWave, azimuth); tick(8, 40) }
    fun click(azimuth: Float = 0f) { play(clickWave, azimuth); tick(22, 120) }
    fun chime() { play(chimeWave, 0f); tick(60, 200) }

    fun release() {
        tracks.values.forEach { it?.release() }
        tracks.clear()
    }

    private companion object {
        const val STEP = (PI / 8).toFloat()
    }
}
