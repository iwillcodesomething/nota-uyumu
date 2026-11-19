package com.notauyumu.core

import com.notauyumu.core.transcribe.BasicPitch
import com.notauyumu.core.transcribe.OrtBasicPitchModel
import com.notauyumu.core.transcribe.VoiceSplitter
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Gerçek Basic Pitch modeliyle uçtan uca test (CI'da ONNX Runtime ile çalışır):
 * örnek koral sentezlenir, model notaları geri çıkarır, sesler ayrılır ve
 * notadaki asıl partilerle karşılaştırılır.
 */
class ModelIntegrationTest {
    private val sr = BasicPitch.SAMPLE_RATE

    private fun file(rel: String) = listOf("../$rel", rel).map(::File).first { it.exists() }
    private fun demo() = MusicXmlParser.parse(file("app/src/main/assets/isinma-korali.musicxml").readBytes())
    private fun model() = OrtBasicPitchModel(file("app/src/main/assets/basic-pitch/nmp.onnx").readBytes())

    private fun render(score: Score, lineId: String, others: Float): FloatArray {
        val mix = MixSettings(ownGain = 0.9f, othersGain = others, metronome = false, countIn = false, startingPitch = false)
        val arr = ArrangementBuilder.build(score, lineId, mix, 0.0, score.durationSec)
        val r = SynthRenderer(sr, arr, mix)
        val out = ArrayList<Float>()
        val buf = FloatArray(1024)
        while (r.render(buf)) buf.forEach { out += it }
        return out.toFloatArray()
    }

    /** Hedef notaların süresinin ne kadarında çıkarılan hatta aynı perde (oktav dahil) çalıyor. */
    private fun overlapScore(truth: VoiceLine, got: List<BasicPitch.NoteEvent>, forgiveOctave: Boolean): Double {
        var total = 0.0; var ok = 0.0
        var t = 0.0
        while (t < (truth.notes.lastOrNull()?.endSec ?: 0.0)) {
            val target = truth.notes.firstOrNull { t >= it.startSec + 0.05 && t < it.endSec - 0.05 }
            if (target != null) {
                total += 1
                val g = got.firstOrNull { t >= it.startSec && t < it.endSec }
                if (g != null && (g.midi == target.midi || (forgiveOctave && (g.midi - target.midi) % 12 == 0))) ok += 1
            }
            t += 0.02
        }
        return if (total > 0) ok / total else 0.0
    }

    @Test
    fun singlePartRecording_isTranscribedAccurately() {
        val s = demo()
        model().use { m ->
            val audio = render(s, s.lines[0].id, 0f)
            val act = BasicPitch.run(m, audio)
            val notes = BasicPitch.notes(act, minMidi = VoiceSplitter.VOICE_MIN_MIDI, maxMidi = VoiceSplitter.VOICE_MAX_MIDI)
            val mono = VoiceSplitter.monophonic(notes)
            val score = overlapScore(s.lines[0], mono, forgiveOctave = false)
            println("Tek parti (soprano) eşleşme: %.2f, ${mono.size} nota (asıl ${s.lines[0].notes.size})".format(score))
            println("  çıkan: " + mono.joinToString { "${Pitch.name(it.midi)}@%.2f".format(it.startSec) })
            assertTrue("tek parti doğruluğu düşük: $score", score >= 0.8)
        }
    }

    @Test
    fun tuttiRecording_isSplitIntoFourVoices() {
        val s = demo()
        model().use { m ->
            val audio = render(s, s.lines[0].id, 0.9f / 0.16f * 0.22f) // dört ses eşit düzeyde
            val act = BasicPitch.run(m, audio)
            val notes = BasicPitch.notes(act, minMidi = VoiceSplitter.VOICE_MIN_MIDI, maxMidi = VoiceSplitter.VOICE_MAX_MIDI)
            val voices = VoiceSplitter.satb(notes, 4)
            val scores = s.lines.mapIndexed { i, l -> overlapScore(l, voices[i], forgiveOctave = false) }
            s.lines.forEachIndexed { i, l -> println("Tutti ${l.name}: %.2f  (${voices[i].size} nota) ".format(scores[i]) + voices[i].joinToString { Pitch.name(it.midi) }) }
            println("Model toplam ${notes.size} nota buldu (asıl ${s.lines.sumOf { it.notes.size }})")
            println("HAM_NOTALAR " + notes.joinToString(";") { "%.4f,%.4f,%d,%.4f".format(java.util.Locale.ROOT, it.startSec, it.endSec, it.midi, it.amplitude) })
            assertTrue("tutti ortalama doğruluk düşük: $scores", scores.average() >= 0.55)
        }
    }
}
