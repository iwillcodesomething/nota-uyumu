package com.notauyumu.core

import com.notauyumu.core.transcribe.BasicPitch
import com.notauyumu.core.transcribe.BasicPitch.NoteEvent
import com.notauyumu.core.transcribe.VoiceSplitter
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

    @Test
    fun satb_recoversFourVoicesFromMixedNotes() {
        val s = demo()
        val rnd = Random(3)
        val mixed = s.lines.flatMap { l -> l.notes.map { NoteEvent(it.startSec + rnd.nextDouble(0.0, 0.03), it.endSec, it.midi, 0.5f + rnd.nextFloat() * 0.4f) } }.shuffled(rnd)
        val voices = VoiceSplitter.satb(mixed, 4)
        for (v in 0 until 4) {
            assertEquals("ses $v perdeleri", s.lines[v].notes.map { it.midi }, voices[v].map { it.midi })
        }
        val score = VoiceSplitter.toScore("Kayıt", voices, VoiceSplitter.voiceNames(4), s.durationSec)
        assertTrue(score.fromRecording)
        assertEquals(listOf("Soprano", "Alto", "Tenor", "Bas"), score.lines.map { it.name })
        assertTrue("bölümler ~4 sn", score.measures.size in 5..8)
        assertEquals(s.durationSec, score.measures.last().endSec, 0.2)
    }

    @Test
    fun satb_handlesMissingVoicesAndExtraNotes() {
        // Önce yalnız iki ses, sonra dört ses + fazladan bir nota
        val evs = listOf(
            NoteEvent(0.0, 1.0, 72, 0.9f), NoteEvent(0.0, 1.0, 48, 0.9f),
            NoteEvent(1.0, 2.0, 74, 0.9f), NoteEvent(1.0, 2.0, 67, 0.8f), NoteEvent(1.0, 2.0, 60, 0.8f),
            NoteEvent(1.0, 2.0, 50, 0.9f), NoteEvent(1.0, 2.0, 55, 0.1f),
        )
        val v = VoiceSplitter.satb(evs, 4)
        assertEquals(listOf(72, 74), v[0].map { it.midi })
        assertEquals(listOf(48, 50), v[3].map { it.midi })
        assertEquals(listOf(67), v[1].map { it.midi })
        assertEquals(listOf(60), v[2].map { it.midi })
    }
}
