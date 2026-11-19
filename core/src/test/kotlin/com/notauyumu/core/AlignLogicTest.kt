package com.notauyumu.core

import com.notauyumu.core.align.ScoreAligner
import com.notauyumu.core.transcribe.BasicPitch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * Nota + kayıt eşleştirme. Yapay bir koral üretilir, "kayıt" ondan farklı tempoda, yer yer hızlanıp
 * yavaşlayarak ve eserin ortasından (girişsiz) başlayacak biçimde boyanır.
 */
class AlignLogicTest {
    private val frame = 256.0 / 22050
    private val time = { f: Int -> f * frame }

    private fun chorale(measures: Int = 24, bpm: Double = 100.0, seed: Int = 3): Score {
        val rnd = Random(seed)
        val beat = 60.0 / bpm
        val ms = (0 until measures).map { i -> Measure(i + 1, "${i + 1}", i * 4 * beat, (i + 1) * 4 * beat, (0 until 4).map { (i * 4 + it) * beat }) }
        val scale = intArrayOf(0, 2, 4, 5, 7, 9, 11)
        val bases = intArrayOf(72, 65, 57, 48) // S A T B
        var group = 0
        val lines = bases.mapIndexed { v, base ->
            val notes = ArrayList<Note>()
            var deg = 0
            var t = 0.0
            while (t < measures * 4 * beat - 1e-6) {
                val len = if (rnd.nextInt(3) == 0) 2 else 1
                deg = (deg + rnd.nextInt(-2, 3)).coerceIn(-3, 6)
                val midi = base + Math.floorDiv(deg, 7) * 12 + scale[Math.floorMod(deg, 7)]
                notes += Note(midi, t, len * beat, (t / (4 * beat)).toInt() + 1, null, ++group)
                t += len * beat
            }
            VoiceLine("v$v", listOf("Soprano", "Alto", "Tenor", "Bas")[v], "p$v", notes)
        }
        return Score("Yapay koral", null, lines, ms, bpm)
    }

    /** Nota zamanını kayıt zamanına çeviren bükülme: [fromSec]'ten başlar, tempo 1.25 → 1.45 kat süzülür, başta [lead] sn sessizlik. */
    private class Warp(val fromSec: Double, val lead: Double) {
        fun rec(t: Double): Double {
            val u = t - fromSec
            // hız: 1.25 + 0.2·(u/30) → kayıt süresi ∫ du / hız
            var acc = 0.0; var x = 0.0; val dx = 0.01
            while (x < u) { acc += dx / (1.25 + 0.2 * (x / 30.0).coerceAtMost(1.0)); x += dx }
            return lead + acc
        }
    }

    private fun paintRecording(score: Score, w: Warp, octaveShift: Map<Int, Int> = emptyMap(), seed: Int = 5): BasicPitch.Activations {
        val rnd = Random(seed)
        val end = w.rec(score.durationSec) + 1.0
        val n = (end / frame).toInt()
        val note = Array(n) { FloatArray(88) { rnd.nextFloat() * 0.08f } }
        score.lines.forEachIndexed { v, l ->
            for (x in l.notes) {
                if (x.startSec < w.fromSec) continue
                val a = (w.rec(x.startSec) / frame).toInt(); val b = ((w.rec(x.endSec) - 0.03) / frame).toInt()
                val p = x.midi + (octaveShift[v] ?: 0) - 21
                for (t in a until minOf(b, n)) note[t][p] = 0.55f + rnd.nextFloat() * 0.2f
            }
        }
        return BasicPitch.Activations(n, note, Array(n) { FloatArray(88) })
    }

    @Test
    fun align_recordingStartingMidPieceAtDifferentTempo() {
        val score = chorale()
        val from = score.measures[6].startSec // kayıt 7. ölçüden başlıyor
        val w = Warp(from, lead = 1.5)
        val act = paintRecording(score, w)
        val al = ScoreAligner.align(score, act, time)!!
        val re = ScoreAligner.retime(score, al)
        val firstLabel = re.measures.first().label.toInt()
        assertTrue("ilk ölçü $firstLabel", firstLabel in 6..8)
        assertEquals("son ölçü", "24", re.measures.last().label)
        assertTrue(re.fromRecording)
        // Taşınan notaların başlangıçları kayıttaki gerçek başlangıçlara yakın olmalı
        val errs = ArrayList<Double>()
        score.lines.forEachIndexed { v, l ->
            val got = re.lines[v].notes
            for (x in l.notes.filter { it.startSec >= from + 1.0 }) {
                val truth = w.rec(x.startSec)
                val g = got.minByOrNull { abs(it.startSec - truth) } ?: continue
                if (g.midi == x.midi) errs += abs(g.startSec - truth)
            }
        }
        errs.sort()
        val median = errs[errs.size / 2]; val p90 = errs[(errs.size * 0.9).toInt()]
        assertTrue("medyan hata $median", median < 0.12)
        assertTrue("%90 hata $p90", p90 < 0.3)
        val agree = ScoreAligner.agreement(re, act, time)
        assertTrue("uyum $agree", agree.values.all { it > 0.85 })
    }

    @Test
    fun correct_fixesPartReadAnOctaveTooHigh() {
        val score = chorale(seed = 8)
        val w = Warp(0.0, lead = 0.5)
        // Kayıtta tenor yazılandan bir oktav aşağıda (okunamayan 8vb anahtarı gibi)
        val act = paintRecording(score, w, octaveShift = mapOf(2 to -12))
        val re = ScoreAligner.retime(score, ScoreAligner.align(score, act, time)!!)
        val before = ScoreAligner.agreement(re, act, time)
        assertTrue(before.getValue("v2") < 0.2)
        val (fixed, fixes) = ScoreAligner.correct(re, act, time)
        assertEquals(re.lines[2].notes.size, fixes.size)
        assertEquals(re.lines[2].notes.map { it.midi - 12 }, fixed.lines[2].notes.map { it.midi })
        assertEquals(re.lines[0].notes, fixed.lines[0].notes)
        assertTrue(ScoreAligner.agreement(fixed, act, time).getValue("v2") > 0.85)
    }

    @Test
    fun alignAnyKey_detectsTransposedRecording() {
        val score = chorale(seed = 11)
        val w = Warp(score.measures[3].startSec, lead = 1.0)
        val up2 = ScoreAligner.transpose(score, 2)
        val act = paintRecording(up2, w)
        val m = ScoreAligner.alignAnyKey(score, act, time)!!
        assertEquals(2, m.semitones)
        val re = ScoreAligner.retime(ScoreAligner.transpose(score, m.semitones), m.alignment)
        assertTrue(ScoreAligner.agreement(re, act, time).values.all { it > 0.85 })
    }

    @Test
    fun align_unrelatedRecordingCostsMore() {
        val score = chorale(seed = 3)
        val other = chorale(seed = 99)
        val good = ScoreAligner.align(score, paintRecording(score, Warp(0.0, 0.5)), time)!!.cost
        val bad = ScoreAligner.align(score, paintRecording(other, Warp(0.0, 0.5)), time)!!.cost
        assertTrue("uygun $good, ilgisiz $bad", good < 0.15 && bad > good + 0.15)
    }

    @Test
    fun align_rejectsSilence() {
        val score = chorale(measures = 4)
        val n = 2000
        val act = BasicPitch.Activations(n, Array(n) { FloatArray(88) }, Array(n) { FloatArray(88) })
        assertEquals(null, ScoreAligner.align(score, act, time))
    }
}
