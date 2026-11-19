package com.notauyumu.core

import com.notauyumu.core.transcribe.BasicPitch
import com.notauyumu.core.transcribe.BasicPitch.NoteEvent
import com.notauyumu.core.transcribe.VoiceSplitter
import com.notauyumu.core.transcribe.VoiceTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

class TranscribeLogicTest {

    @Test
    fun windowing_matchesUpstreamGeometry() {
        val audio = FloatArray(BasicPitch.SAMPLE_RATE * 5) // 5 sn
        val ws = BasicPitch.windows(audio)
        // (3840 + 110250) / 36164 -> 4 pencere
        assertEquals(4, ws.size)
        assertTrue(ws.all { it.size == BasicPitch.AUDIO_N_SAMPLES })
        val fake = BasicPitch.Model { w -> BasicPitch.WindowOutput(FloatArray(172 * 88) { 0.1f }, FloatArray(172 * 88)) }
        val act = BasicPitch.run(fake, audio)
        // floor(110250 / 36164 * 142) = 432 kare ≈ 5.01 sn
        assertEquals(432, act.nFrames)
        assertEquals(5.0, BasicPitch.frameToTime(act.nFrames), 0.05)
    }

    private fun emptyAct(n: Int) = BasicPitch.Activations(n, Array(n) { FloatArray(88) }, Array(n) { FloatArray(88) })

    @Test
    fun noteDecoding_onsetAndMelodiaPaths() {
        val act = emptyAct(300)
        val p60 = 60 - 21
        // Onsetli nota: 20..80. kare
        for (t in 20 until 80) act.note[t][p60] = 0.8f
        act.onset[19][p60] = 0.2f; act.onset[20][p60] = 0.9f; act.onset[21][p60] = 0.3f
        // Onsetsiz, yavaş yükselen sürekli nota (yalnızca melodia yakalar): Sol4
        val p67 = 67 - 21
        for (t in 150 until 200) act.note[t][p67] = minOf(0.6f, (t - 149) * 0.05f)
        // Kısa parazit: elenmeli
        for (t in 250 until 255) act.note[t][30] = 0.9f
        val notes = BasicPitch.notes(act, melodiaTrick = true)
        assertEquals(listOf(60, 67), notes.map { it.midi })
        assertEquals(BasicPitch.frameToTime(20), notes[0].startSec, 1e-9)
        assertEquals(BasicPitch.frameToTime(80), notes[0].endSec, 1e-9)
        assertEquals(0.8, notes[0].amplitude.toDouble(), 1e-5)
        // melodia kapalıyken yalnızca onsetli nota
        assertEquals(listOf(60), BasicPitch.notes(act, melodiaTrick = false).map { it.midi })
        // perde sınırı
        assertEquals(listOf(67), BasicPitch.notes(act, minMidi = 62).map { it.midi })
    }

    @Test
    fun monophonic_keepsStrongestAndDropsGhostOctaves() {
        val evs = listOf(
            NoteEvent(0.0, 1.0, 60, 0.9f),
            NoteEvent(0.1, 0.9, 72, 0.3f), // zayıf oktav yansıması
            NoteEvent(1.0, 2.0, 62, 0.8f),
            NoteEvent(1.9, 2.6, 64, 0.7f), // kısmi örtüşme -> kırpılır
            NoteEvent(2.6, 2.62, 65, 0.9f), // çok kısa
            NoteEvent(2.65, 3.0, 64, 0.6f), // aynı perdenin devamı -> birleşir
        )
        val m = VoiceSplitter.monophonic(evs)
        assertEquals(listOf(60, 62, 64), m.map { it.midi })
        assertEquals(2.0, m[2].startSec, 1e-9)
        assertEquals(3.0, m[2].endSec, 1e-9)
    }

    private fun demo(): Score = MusicXmlParser.parse(listOf("../app/src/main/assets/isinma-korali.musicxml", "app/src/main/assets/isinma-korali.musicxml").map(::File).first { it.exists() }.readBytes())

    /** Notadan yapay "model çıktısı" üretir: her ses kendi perdesinde 0.6 olasılıkla duyulur. */
    private fun paint(score: Score, frameSec: Double = 256.0 / 22050): BasicPitch.Activations {
        val n = (score.durationSec / frameSec).toInt() + 2
        val note = Array(n) { FloatArray(88) { 0.05f } }
        val onset = Array(n) { FloatArray(88) }
        for (l in score.lines) for (x in l.notes) {
            val a = (x.startSec / frameSec).toInt(); val b = ((x.endSec - 0.03) / frameSec).toInt()
            for (t in a until minOf(b, n)) note[t][x.midi - 21] = 0.6f
            for (t in a until minOf(a + 3, n)) onset[t][x.midi - 21] = 0.7f
        }
        return BasicPitch.Activations(n, note, onset)
    }

    @Test
    fun voiceTracker_recoversFourVoicesIncludingUnisons() {
        val s = demo()
        // Soprano ve Alto'yu ilk iki notada aynı perdeye getir (koroda sık görülen birlik)
        val alto = s.lines[1].copy(notes = s.lines[1].notes.mapIndexed { i, n -> if (i < 2) n.copy(midi = s.lines[0].notes[i].midi) else n })
        // Örnek koralde tenor yüksek yazılmış; gerçekçi bir tenor için bir oktav indir
        val tenor = s.lines[2].copy(notes = s.lines[2].notes.map { it.copy(midi = it.midi - 12) })
        val score = s.copy(lines = listOf(s.lines[0], alto, tenor, s.lines[3]))
        val act = paint(score)
        assertEquals(4, VoiceTracker.estimateVoices(act))
        val voices = VoiceTracker.split(act, 4) { it * 256.0 / 22050 }
        for (v in 0 until 4) {
            val want = score.lines[v].notes.map { it.midi }.fold(mutableListOf<Int>()) { acc, m -> if (acc.lastOrNull() != m) acc += m; acc }
            val got = voices[v].map { it.midi }.fold(mutableListOf<Int>()) { acc, m -> if (acc.lastOrNull() != m) acc += m; acc }
            // İlk birlik notası (aralık henüz öğrenilmemişken), seslerin çaprazlaştığı tek bir yer ya da sesin
            // olağan aralığının çok dışındaki tek bir nota (ör. basın Fa2'si) kaçabilir
            val d = Array(want.size + 1) { IntArray(got.size + 1) }
            for (i in 1..want.size) for (j in 1..got.size) d[i][j] = if (want[i - 1] == got[j - 1]) d[i - 1][j - 1] + 1 else maxOf(d[i - 1][j], d[i][j - 1])
            val sim = 2.0 * d[want.size][got.size] / (want.size + got.size)
            assertTrue("ses $v: beklenen $want, gelen $got", sim >= 0.85)
        }
    }

    @Test
    fun voiceTracker_silentVoiceAndRecordingNames() {
        val s = demo()
        val act = paint(s.copy(lines = listOf(s.lines[0], s.lines[3]))) // yalnız Soprano + Bas
        assertEquals(2, VoiceTracker.estimateVoices(act))
        val v = VoiceTracker.split(act, 2) { it * 256.0 / 22050 }
        assertTrue(v[0].all { it.midi >= 67 } && v[1].all { it.midi <= 55 })
        val score = VoiceSplitter.toScore("Kayıt", VoiceTracker.split(act, 5), VoiceSplitter.voiceNames(5), s.durationSec)
        assertEquals("Soprano", score.lines.first().name)
        assertEquals(listOf("Soprano", "Mezzosoprano", "Alto", "Tenor", "Bas"), VoiceSplitter.voiceNames(5))
    }
}
