package com.notauyumu.studio.audio

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class YinResult(
    val frequency: Float?,
    val probability: Float,
    val rms: Float
)

object YinDetector {
    private val NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    fun computeRms(buffer: FloatArray): Float {
        var sum = 0.0
        for (sample in buffer) {
            sum += sample * sample
        }
        return sqrt(sum / buffer.size).toFloat()
    }

    /**
     * Saf Kotlin ile yazılmış YIN temel frekans (f0) algılama algoritması.
     */
    fun detectPitch(
        buffer: FloatArray,
        sampleRate: Int,
        rmsThreshold: Float = 0.015f,
        threshold: Float = 0.15f,
        minFreq: Float = 65f,
        maxFreq: Float = 1400f
    ): YinResult {
        val rms = computeRms(buffer)
        if (rms < rmsThreshold) {
            return YinResult(null, 0f, rms)
        }

        val halfLen = buffer.size / 2
        val minTau = max(2, (sampleRate / maxFreq).toInt())
        val maxTau = min(halfLen - 1, (sampleRate / minFreq).toInt())
        if (maxTau <= minTau) return YinResult(null, 0f, rms)

        val yinBuffer = FloatArray(maxTau + 1)

        // 1. Fark fonksiyonu d(tau)
        for (tau in 1..maxTau) {
            var sum = 0f
            for (i in 0 until halfLen) {
                val delta = buffer[i] - buffer[i + tau]
                sum += delta * delta
            }
            yinBuffer[tau] = sum
        }

        // 2. Kümülatif ortalama normalize fark fonksiyonu d'(tau)
        yinBuffer[0] = 1f
        var runningSum = 0f
        for (tau in 1..maxTau) {
            runningSum += yinBuffer[tau]
            yinBuffer[tau] = if (runningSum > 0f) (yinBuffer[tau] * tau) / runningSum else 1f
        }

        // 3. Mutlak eşik
        var tauEstimate = -1
        var tau = minTau
        while (tau <= maxTau) {
            if (yinBuffer[tau] < threshold) {
                while (tau + 1 <= maxTau && yinBuffer[tau + 1] < yinBuffer[tau]) {
                    tau++
                }
                tauEstimate = tau
                break
            }
            tau++
        }

        if (tauEstimate == -1) return YinResult(null, 0f, rms)

        // 4. Parabolik enterpolasyon
        var betterTau = tauEstimate.toFloat()
        if (tauEstimate in 2 until maxTau) {
            val s0 = yinBuffer[tauEstimate - 1]
            val s1 = yinBuffer[tauEstimate]
            val s2 = yinBuffer[tauEstimate + 1]
            val denom = 2f * s1 - s2 - s0
            if (abs(denom) > 1e-6f) {
                betterTau = tauEstimate + (s2 - s0) / (2f * denom)
            }
        }

        val frequency = sampleRate / betterTau
        if (!frequency.isFinite() || frequency < minFreq || frequency > maxFreq) {
            return YinResult(null, 0f, rms)
        }

        val probability = (1f - yinBuffer[tauEstimate]).coerceIn(0f, 1f)
        return YinResult(frequency, probability, rms)
    }

    fun frequencyToMidiFloat(frequency: Float): Float {
        return (69.0 + 12.0 * log2(frequency / 440.0)).toFloat()
    }

    fun midiToFrequency(midi: Int): Double {
        return 440.0 * 2.0.pow((midi - 69) / 12.0)
    }

    fun midiToNoteName(midi: Float): String {
        val rounded = midi.roundToInt()
        val name = NOTE_NAMES[((rounded % 12) + 12) % 12]
        val octave = floor(rounded / 12.0).toInt() - 1
        return "$name$octave"
    }
}
