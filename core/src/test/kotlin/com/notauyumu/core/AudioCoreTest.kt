package com.notauyumu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

class AudioCoreTest {
    private val sr = 22050

    private fun tone(freq: Double, harmonics: Int = 1, amp: Double = 0.3, n: Int = 1024): FloatArray =
        FloatArray(n) { i -> (1..harmonics).sumOf { h -> amp / h * sin(2 * PI * freq * h * i / sr) }.toFloat() }

    @Test
    fun yin_detectsPureAndRichTonesAcrossVoiceRange() {
        val yin = YinDetector(sr, 1024)
        for (f in listOf(82.4, 110.0, 196.0, 261.63, 440.0, 698.46, 1046.5)) {
            val r = yin.detect(tone(f))
            assertEquals("saf $f Hz", f, r.freq, f * 0.006)
            val rich = yin.detect(tone(f, harmonics = 5))
            assertEquals("zengin $f Hz", f, rich.freq, f * 0.006)
        }
    }

    @Test
    fun yin_returnsZeroForSilenceAndNoise() {
        val yin = YinDetector(sr, 1024)
        assertEquals(0.0, yin.detect(FloatArray(1024)).freq, 0.0)
        val rnd = Random(7)
        val noise = FloatArray(1024) { (rnd.nextFloat() - 0.5f) * 0.6f }
        assertEquals(0.0, yin.detect(noise).freq, 0.0)
    }

    @Test
    fun pitch_namesAndCents() {
        assertEquals("Do4", Pitch.name(60))
        assertEquals("La4", Pitch.name(69))
        assertEquals("Si♭3", Pitch.name(58))
        assertEquals(69.0, Pitch.freqToMidi(440.0), 1e-9)
        assertEquals(-20.0, Pitch.centsOff(59.8, 60, false), 1e-6)
        assertEquals(10.0, Pitch.centsOff(72.1, 60, true), 1e-6)
        assertEquals(1210.0, Pitch.centsOff(72.1, 60, false), 1e-6)
    }

    private fun line(): Pair<VoiceLine, List<Measure>> {
        val notes = listOf(
            Note(60, 0.0, 1.0, 1, null, 1),
            Note(62, 1.0, 1.0, 1, null, 2),
            Note(64, 2.0, 1.0, 2, null, 3),
            Note(65, 3.0, 1.0, 2, null, 4),
        )
        val measures = listOf(Measure(1, "1", 0.0, 2.0, listOf(0.0, 1.0)), Measure(2, "2", 2.0, 4.0, listOf(2.0, 3.0)))
        return VoiceLine("x", "Test", "Test", notes) to measures
    }

    private fun sing(l: VoiceLine, offsetCents: (Note) -> Double?): List<PitchSample> {
        val out = ArrayList<PitchSample>()
        var t = 0.0
        while (t < 4.0) {
            val n = l.notes.first { t >= it.startSec && t < it.endSec }
            out += PitchSample(t, offsetCents(n)?.let { n.midi + it / 100.0 })
            t += 0.02
        }
        return out
    }

    @Test
    fun scoring_perfectSlightlyFlatAndWrongPerformances() {
        val (l, m) = line()
        val st = ScoringSettings(toleranceCents = 35.0)
        val perfect = Scorer.evaluate(l, m, sing(l) { 0.0 }, st)
        assertTrue("kusursuz ≈ %100, gelen ${perfect.overall}", perfect.overall > 0.97)

        val flat = Scorer.evaluate(l, m, sing(l) { -20.0 }, st)
        assertTrue(flat.overall > 0.97)
        assertEquals(-20.0, flat.tendencyCents!!, 0.5)
        assertTrue(Scorer.tendencyText(flat.tendencyCents)!!.contains("pes"))

        val wrong = Scorer.evaluate(l, m, sing(l) { n -> if (n.measureIndex == 2) 100.0 else 0.0 }, st)
        assertEquals(0.5, wrong.overall, 0.03)
        assertEquals(listOf(2), wrong.weakestMeasures().map { it.measureIndex })

        val silent = Scorer.evaluate(l, m, sing(l) { null }, st)
        assertEquals(0.0, silent.overall, 1e-9)
        assertEquals(0.0, silent.voicedRatio, 1e-9)
    }

    @Test
    fun scoring_octaveErrorsForgivenOnlyWhenEnabled() {
        val (l, m) = line()
        val oct = sing(l) { 1200.0 }
        assertTrue(Scorer.evaluate(l, m, oct, ScoringSettings(forgiveOctave = true)).overall > 0.97)
        assertTrue(Scorer.evaluate(l, m, oct, ScoringSettings(forgiveOctave = false)).overall < 0.01)
    }

    @Test
    fun scoring_respectsLoopRange() {
        val (l, m) = line()
        val r = Scorer.evaluate(l, m, sing(l) { 0.0 }, ScoringSettings(), fromSec = 2.0, untilSec = 4.0)
        assertEquals(2, r.notes.size)
        assertEquals(listOf(2), r.measures.map { it.measureIndex })
    }

    @Test
    fun grade_live() {
        val st = ScoringSettings(toleranceCents = 30.0)
        assertEquals(Grade.HIT, Scorer.grade(60.2, listOf(60), st))
        assertEquals(Grade.NEAR, Scorer.grade(60.5, listOf(60), st))
        assertEquals(Grade.MISS, Scorer.grade(61.0, listOf(60), st))
        assertEquals(Grade.HIT, Scorer.grade(64.1, listOf(60, 64), st))
        assertEquals(Grade.SILENT, Scorer.grade(null, listOf(60), st))
    }

    @Test
    fun synth_rendersCleanAudioWithCountInAndStops() {
        val (l, m) = line()
        val score = Score("t", null, listOf(l, l.copy(id = "y", name = "Öbür")), m, 60.0)
        val mix = MixSettings()
        val arr = ArrangementBuilder.build(score, "x", mix, 0.0, score.durationSec)
        assertEquals(2.0, arr.countInSec, 1e-9) // 2 vuruşluk ölçü
        assertEquals(-2.0, arr.scoreOffsetSec, 1e-9)
        val r = SynthRenderer(sr, arr, mix)
        val buf = FloatArray(512)
        var peak = 0f; var frames = 0; var energyAfterCountIn = 0.0
        while (r.render(buf)) {
            for (v in buf) { assertTrue(!v.isNaN()); peak = maxOf(peak, abs(v)) }
            if (frames > sr * 2.1) energyAfterCountIn += buf.sumOf { (it * it).toDouble() }
            frames += buf.size
            assertTrue("sonsuz döngü", frames < sr * 20)
        }
        assertTrue("tepe ≤ 1, gelen $peak", peak <= 1f && peak > 0.05f)
        assertTrue(energyAfterCountIn > 1.0)
        assertEquals(6.3, frames.toDouble() / sr, 0.05)

        // YIN kendi sentezimizi doğru duymalı
        val only = ArrangementBuilder.build(score, "x", MixSettings(othersGain = 0f, metronome = false, countIn = false), 0.0, 1.0)
        val r2 = SynthRenderer(sr, only, MixSettings(othersGain = 0f, metronome = false, countIn = false))
        val b2 = FloatArray(4096); r2.render(b2)
        val f = YinDetector(sr, 1024).detect(b2.copyOfRange(2048, 3072)).freq
        assertEquals(261.63, f, 2.0)
    }
}
