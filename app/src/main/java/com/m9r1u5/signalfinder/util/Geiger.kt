package com.m9r1u5.signalfinder.util

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.m9r1u5.signalfinder.signal.SignalMath

/** Clicks (beep and/or buzz) faster as the signal gets stronger. */
class Geiger(context: Context) {

    enum class Mode { SOUND, VIBRATE, BOTH, OFF }

    var mode: Mode = Mode.SOUND
    /** Returns the current 0–100 score, or null when there's no service. */
    var scoreProvider: () -> Int? = { null }

    private val handler = Handler(Looper.getMainLooper())
    private var tone: ToneGenerator? = null
    private var running = false

    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val s = scoreProvider()
            click(s)
            handler.postDelayed(this, SignalMath.geigerIntervalMs(s))
        }
    }

    fun start() {
        if (running) return
        running = true
        ensureTone()
        handler.post(tick)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
        tone?.release()
        tone = null
    }

    private fun ensureTone() {
        if (tone == null) {
            tone = try {
                ToneGenerator(AudioManager.STREAM_MUSIC, 90)
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun sound() = mode == Mode.SOUND || mode == Mode.BOTH
    private fun buzz() = mode == Mode.VIBRATE || mode == Mode.BOTH

    private fun click(score: Int?) {
        if (mode == Mode.OFF) return
        if (score == null) {
            if (sound()) tone?.startTone(ToneGenerator.TONE_SUP_ERROR, 150)
            if (buzz()) vibrate(150)
            return
        }
        if (sound()) tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 40)
        if (buzz()) vibrate(35)
    }

    /** Distinct alert for a new best reading. Works even when clicks are off. */
    fun celebrate() {
        ensureTone()
        tone?.startTone(ToneGenerator.TONE_PROP_ACK, 300)
        try {
            vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 90, 70, 90, 70, 160), -1))
        } catch (e: Exception) { }
    }

    private fun vibrate(ms: Long) {
        try {
            vibrator?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) { }
    }
}
