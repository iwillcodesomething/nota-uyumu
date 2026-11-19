package com.notauyumu.core

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.tanh

enum class SoundKind { OWN, OTHER, CLICK_STRONG, CLICK_WEAK }

data class SynthEvent(val startSec: Double, val durSec: Double, val midi: Int, val kind: SoundKind)

/** Oynatma planı: olaylar oynatma zamanına göredir; [scoreOffsetSec] eklenince eser zamanı elde edilir. */
data class Arrangement(
    val events: List<SynthEvent>,
    val scoreOffsetSec: Double,
    val lengthSec: Double,
    val countInSec: Double,
)

data class MixSettings(
    val ownGain: Float = 0.9f,
    val othersGain: Float = 0.35f,
    val metronome: Boolean = true,
    val countIn: Boolean = true,
)

object ArrangementBuilder {
    fun build(score: Score, lineId: String, mix: MixSettings, fromSec: Double, untilSec: Double): Arrangement {
        val firstMeasure = score.measureAt(fromSec)
        val beatLen = firstMeasure?.beats?.let { b -> if (b.size >= 2) b[1] - b[0] else null }
            ?: (60.0 / score.tempoBpm)
        val beatsInBar = firstMeasure?.beats?.size?.coerceIn(2, 6) ?: 4
        val countIn = if (mix.countIn) beatLen * beatsInBar else 0.0
        val offset = fromSec - countIn // oynatma t=0 anı eserde bu saniyeye denk gelir

        val ev = ArrayList<SynthEvent>()
        if (mix.countIn) for (i in 0 until beatsInBar) {
            ev += SynthEvent(i * beatLen, 0.05, 0, if (i == 0) SoundKind.CLICK_STRONG else SoundKind.CLICK_WEAK)
        }
        for (line in score.lines) {
            val own = line.id == lineId
            if (!own && mix.othersGain <= 0f) continue
            if (own && mix.ownGain <= 0f) continue
            for (n in line.notes) {
                if (n.endSec <= fromSec || n.startSec >= untilSec) continue
                val s = maxOf(n.startSec, fromSec)
                val e = minOf(n.endSec, untilSec)
                ev += SynthEvent(s - offset, e - s, n.midi, if (own) SoundKind.OWN else SoundKind.OTHER)
            }
        }
        if (mix.metronome) for (m in score.measures) {
            m.beats.forEachIndexed { i, b ->
                if (b >= fromSec - 1e-6 && b < untilSec - 1e-6)
                    ev += SynthEvent(b - offset, 0.05, 0, if (i == 0) SoundKind.CLICK_STRONG else SoundKind.CLICK_WEAK)
            }
        }
        ev.sortBy { it.startSec }
        val end = minOf(untilSec, score.durationSec) - offset
        return Arrangement(ev, offset, end + 0.3, countIn)
    }
}

/**
 * Akış hâlinde ses üreten basit sentezleyici. Yumuşak, "uu" benzeri org tınısı
 * (temel + birkaç harmonik); vibrato yok ki referans perde net olsun.
 * Tek iş parçacığından çağrılmalıdır.
 */
class SynthRenderer(private val sampleRate: Int, private val arrangement: Arrangement, private val mix: MixSettings) {
    private class Voice(val ev: SynthEvent, val startFrame: Long, val endFrame: Long, val freq: Double, val gain: Float) {
        var phase = 0.0
    }

    private val events = arrangement.events
    private var nextEvent = 0
    private val active = ArrayList<Voice>()
    private val attack = (0.025 * sampleRate).toInt()
    private val release = (0.08 * sampleRate).toInt()
    var framePosition = 0L
        private set

    /** Bir sonraki [out].size kareyi üretir. Eser bittiyse false döner. */
    fun render(out: FloatArray): Boolean {
        val n = out.size
        out.fill(0f)
        val blockEnd = framePosition + n
        while (nextEvent < events.size && (events[nextEvent].startSec * sampleRate).toLong() < blockEnd) {
            val e = events[nextEvent++]
            val sf = (e.startSec * sampleRate).toLong()
            val ef = sf + (e.durSec * sampleRate).toLong()
            val (freq, gain) = when (e.kind) {
                SoundKind.OWN -> Pitch.midiToFreq(e.midi.toDouble()) to mix.ownGain * 0.22f
                SoundKind.OTHER -> Pitch.midiToFreq(e.midi.toDouble()) to mix.othersGain * 0.16f
                SoundKind.CLICK_STRONG -> 1760.0 to 0.35f
                SoundKind.CLICK_WEAK -> 1175.0 to 0.22f
            }
            active += Voice(e, sf, ef, freq, gain)
        }
        val it = active.iterator()
        while (it.hasNext()) {
            val v = it.next()
            val click = v.ev.kind == SoundKind.CLICK_STRONG || v.ev.kind == SoundKind.CLICK_WEAK
            val stopFrame = if (click) v.startFrame + (0.06 * sampleRate).toLong() else v.endFrame + release
            val inc = 2 * PI * v.freq / sampleRate
            for (i in 0 until n) {
                val f = framePosition + i
                if (f < v.startFrame) continue
                if (f >= stopFrame) break
                val t = (f - v.startFrame).toInt()
                val env: Double = if (click) {
                    exp(-t / (0.012 * sampleRate))
                } else {
                    val a = if (t < attack) t.toDouble() / attack else 1.0
                    val r = if (f > v.endFrame) 1.0 - (f - v.endFrame).toDouble() / release else 1.0
                    a * r
                }
                val p = v.phase
                val s = if (click) sin(p) else sin(p) + 0.42 * sin(2 * p) + 0.18 * sin(3 * p) + 0.07 * sin(4 * p)
                out[i] += (s * env * v.gain).toFloat()
                v.phase = p + inc
                if (v.phase > 2 * PI * 1000) v.phase -= 2 * PI * 1000
            }
            if (stopFrame <= blockEnd) it.remove()
        }
        for (i in 0 until n) out[i] = tanh(out[i].toDouble()).toFloat()
        framePosition = blockEnd
        return framePosition < (arrangement.lengthSec * sampleRate).toLong()
    }
}
