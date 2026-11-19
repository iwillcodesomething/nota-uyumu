package com.notauyumu.core

import kotlin.math.sqrt

/**
 * YIN temel frekans tahmini (de Cheveigné & Kawahara, 2002).
 * İnsan sesi için 60–1300 Hz aralığında çalışır. Tek iş parçacığından kullanılmalıdır.
 */
class YinDetector(
    private val sampleRate: Int,
    val windowSize: Int = 2048,
    private val threshold: Double = 0.12,
    private val minFreq: Double = 60.0,
    private val maxFreq: Double = 1300.0,
) {
    private val maxTau = minOf(windowSize / 2, (sampleRate / minFreq).toInt() + 2)
    private val minTau = maxOf(2, (sampleRate / maxFreq).toInt())
    private val diff = DoubleArray(maxTau + 1)

    data class Result(val freq: Double, val confidence: Double, val rms: Double)

    /** [frame] en az [windowSize] uzunlukta olmalı. Sessiz/periyodik değilse freq = 0. */
    fun detect(frame: FloatArray, gateRms: Double = 0.008): Result {
        var sumSq = 0.0
        for (i in 0 until windowSize) sumSq += frame[i] * frame[i]
        val rms = sqrt(sumSq / windowSize)
        if (rms < gateRms) return Result(0.0, 0.0, rms)

        val w = windowSize - maxTau
        // Fark fonksiyonu
        for (tau in 1..maxTau) {
            var s = 0.0
            for (j in 0 until w) {
                val d = frame[j] - frame[j + tau]
                s += d * d
            }
            diff[tau] = s
        }
        // Birikimli ortalama normalizasyonu
        diff[0] = 1.0
        var running = 0.0
        for (tau in 1..maxTau) {
            running += diff[tau]
            diff[tau] = if (running == 0.0) 1.0 else diff[tau] * tau / running
        }
        // Mutlak eşik
        var tau = minTau
        var found = -1
        while (tau <= maxTau) {
            if (diff[tau] < threshold) {
                while (tau + 1 <= maxTau && diff[tau + 1] < diff[tau]) tau++
                found = tau
                break
            }
            tau++
        }
        if (found < 0) {
            // Eşik altında değer yoksa en düşük minimumu kullan ama güveni düşük say
            var best = minTau
            for (t in minTau..maxTau) if (diff[t] < diff[best]) best = t
            if (diff[best] > 0.35) return Result(0.0, 0.0, rms)
            found = best
        }
        // Parabolik iyileştirme
        val betterTau = if (found in 1 until maxTau) {
            val s0 = diff[found - 1]; val s1 = diff[found]; val s2 = diff[found + 1]
            val denom = 2 * (2 * s1 - s2 - s0)
            if (denom != 0.0) found + (s2 - s0) / denom else found.toDouble()
        } else found.toDouble()
        val freq = sampleRate / betterTau
        if (freq < minFreq || freq > maxFreq) return Result(0.0, 0.0, rms)
        return Result(freq, 1.0 - diff[found].coerceIn(0.0, 1.0), rms)
    }
}

/** Kısa süreli sıçramaları bastıran medyan süzgeci (perde MIDI değerleri üzerinde). */
class MedianSmoother(private val size: Int = 5) {
    private val buf = ArrayDeque<Double>()
    fun push(midi: Double?): Double? {
        if (midi == null) { buf.clear(); return null }
        buf.addLast(midi)
        if (buf.size > size) buf.removeFirst()
        if (buf.size < 3) return midi
        val s = buf.sorted()
        return s[s.size / 2]
    }
}
