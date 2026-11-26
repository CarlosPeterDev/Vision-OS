package com.nexus.visionos.xr

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** Short synthesized interface sounds and vibration ticks. No audio files. */
class Feedback(context: Context, private val settings: XrSettings) {
    private val vibrator = context.getSystemService(Vibrator::class.java)

    private fun tone(frequency: Double, millis: Int, volume: Double): AudioTrack? = runCatching {
        val rate = 22050
        val samples = ShortArray(rate * millis / 1000) {
            val t = it.toDouble() / rate
            val fade = minOf(1.0, it / 60.0) * exp(-t * 4500 / millis)
            (sin(2 * PI * frequency * t) * fade * volume * Short.MAX_VALUE).toInt().toShort()
        }
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            )
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
            )
            .setBufferSizeInBytes(samples.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
            .also { it.write(samples, 0, samples.size) }
    }.getOrNull()

    private val hoverTone = tone(1500.0, 25, 0.05)
    private val clickTone = tone(880.0, 60, 0.16)

    private fun play(track: AudioTrack?) {
        if (!settings.sounds || track == null) return
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

    fun hover() { play(hoverTone); tick(8, 40) }
    fun click() { play(clickTone); tick(22, 120) }

    fun release() {
        hoverTone?.release()
        clickTone?.release()
    }
}
