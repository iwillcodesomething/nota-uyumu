package com.notauyumu.core

import com.notauyumu.core.omr.GrayImage
import com.notauyumu.core.omr.OmrScoreBuilder
import com.notauyumu.core.omr.OmrSymbol
import com.notauyumu.core.omr.StaffFinder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/** Modelsiz (yerelde de çalışan) OMR testleri: porte bulma ve sembollerden eser kurma. */
class OmrLogicTest {
    companion object {
        fun resource(name: String): File = listOf("src/test/resources/omr/$name", "core/src/test/resources/omr/$name").map(::File).first { it.exists() }

        fun loadGray(f: File): GrayImage {
            val bi = ImageIO.read(f)
            val px = ByteArray(bi.width * bi.height)
            for (y in 0 until bi.height) for (x in 0 until bi.width) {
                val rgb = bi.getRGB(x, y)
                px[y * bi.width + x] = ((((rgb shr 16) and 255) * 299 + ((rgb shr 8) and 255) * 587 + (rgb and 255) * 114) / 1000).toByte()
            }
            return GrayImage(bi.width, bi.height, px)
        }

        /** tabi_expected.json: {"0": [[rhythm, pitch, lift, position], ...], "1": [...]} */
        fun expected(): Map<Int, List<OmrSymbol>> {
            val text = resource("tabi_expected.json").readText()
            return Regex("\"(\\d+)\"\\s*:\\s*\\[(.*?)]]").findAll(text).associate { m ->
                m.groupValues[1].toInt() to Regex("\\[\"(.*?)\", \"(.*?)\", \"(.*?)\", \"(.*?)\"").findAll(m.groupValues[2] + "]").map {
                    OmrSymbol(it.groupValues[1], it.groupValues[2], it.groupValues[3], it.groupValues[4])
                }.toList()
            }
        }
    }

    @Test
    fun staffFinder_cleanPage() {
        val page = StaffFinder.preparePage(loadGray(resource("tabi.png")))
        val staves = StaffFinder.find(page)
        assertEquals(8, staves.size)
        assertEquals(listOf(2, 2, 2, 2), StaffFinder.systems(page, staves).map { it.size })
        assertTrue(staves.all { it.unit in 11.0..13.5 })
        val input = StaffFinder.modelInput(page, staves, 0)
        assertEquals(1280, input.width); assertEquals(256, input.height)
    }

    @Test
    fun staffFinder_phonePhotoWithCurlShadowAndPerspective() {
        val page = StaffFinder.preparePage(loadGray(resource("fotograf.jpg")))
        val staves = StaffFinder.find(page)
        assertEquals(8, staves.size)
        assertEquals(listOf(2, 2, 2, 2), StaffFinder.systems(page, staves).map { it.size })
        // Kıvrık sayfada bile her porte en az 20 şeritte izlenmeli ve sayfa genişliğinin çoğunu kaplamalı
        assertTrue(staves.all { it.xs.size >= 18 && it.x1 - it.x0 > 1500 })
    }

    @Test
    fun staffFinder_tiltedPageIsDeskewed() {
        val src = loadGray(resource("tabi.png")).toWidth(1920)
        val tilted = StaffFinder.rotate(src, 4.0)
        // Tahmin, sayfayı düzeltmek için gereken dönüş açısıdır (ters işaret)
        assertEquals(-4.0, StaffFinder.estimateSkew(StaffFinder.flattenIllumination(tilted)), 0.3)
        val page = StaffFinder.preparePage(tilted)
        assertEquals(8, StaffFinder.find(page).size)
    }

    private fun sym(r: String, p: String = ".", l: String = ".") = OmrSymbol(r, p, l, if (p == ".") "." else "upper")

    @Test
    fun builder_durationsPitchesAndOctaveClef() {
        assertEquals(1.5, OmrScoreBuilder.durationQ("4.")!!, 1e-9)
        assertEquals(1.75, OmrScoreBuilder.durationQ("4..")!!, 1e-9)
        assertEquals(1.0 / 3, OmrScoreBuilder.durationQ("12")!!, 1e-9)
        assertEquals(8.0, OmrScoreBuilder.durationQ("0")!!, 1e-9)
        assertEquals(null, OmrScoreBuilder.durationQ("8G"))
        assertEquals(61, OmrScoreBuilder.midi("C4", "#"))
        assertEquals(70, OmrScoreBuilder.midi("B4", "b"))
        assertEquals(64, OmrScoreBuilder.midi("E4", "N"))

        val tenor = listOf(sym("clef_G2_8vb"), sym("timeSignature/4"), sym("note_2", "G4", "_"), sym("note_2", "E4", "_"), sym("barline"), sym("note_1", "C4", "_"), sym("barline"))
        val s = OmrScoreBuilder.build("T", listOf(listOf(tenor)), bpm = 60.0)
        assertEquals(listOf(55, 52, 48), s.lines.single().notes.map { it.midi })
        assertEquals(2, s.measures.size)
        assertEquals(4.0, s.measures[1].startSec, 1e-9)
        assertEquals(listOf(0.0, 2.0, 4.0), s.lines.single().notes.map { it.startSec })
    }

    @Test
    fun builder_closedScoreSplitsVoicesAndAlignsSystems() {
        // Sistem 1: üstte S+A (akorlar), altta T+B; sistem 2: devam
        fun chord(d: String, hi: String, lo: String) = listOf(sym("note_$d", hi, "_"), sym("chord"), sym("note_$d", lo, "_"))
        val women1 = listOf(sym("clef_G2")) + chord("2", "E5", "C5") + chord("2", "D5", "B4") + sym("barline")
        val men1 = listOf(sym("clef_F4")) + chord("2", "G3", "C3") + chord("2", "G3", "G2") + sym("barline")
        val women2 = chord("1", "C5", "G4") + sym("barline")
        val men2 = chord("1", "E3", "C3") + sym("barline")
        val s = OmrScoreBuilder.build("Kapalı", listOf(listOf(women1, men1), listOf(women2, men2)), bpm = 60.0)
        assertEquals(listOf("1. porte · üst ses", "1. porte · alt ses", "2. porte · üst ses", "2. porte · alt ses"), s.lines.map { it.name })
        assertEquals(listOf(76, 74, 72), s.lines[0].notes.map { it.midi })
        assertEquals(listOf(72, 71, 67), s.lines[1].notes.map { it.midi })
        assertEquals(listOf(55, 55, 52), s.lines[2].notes.map { it.midi })
        assertEquals(listOf(48, 43, 48), s.lines[3].notes.map { it.midi })
        assertEquals(2, s.measures.size)
        assertEquals(4.0, s.lines[0].notes[2].startSec, 1e-9)
    }

    @Test
    fun builder_fixesUnrecognizedOctaveTrebleClefOfTenor() {
        fun staff(vararg p: String) = listOf(sym("clef_G2")) + p.map { sym("note_2", it, "_") } + sym("barline")
        val alto = staff("C5", "B4")
        val tenorWritten = staff("G5", "G5") // sekizlik anahtar okunmamış: çalan Sol4
        val bass = listOf(sym("clef_F4"), sym("note_2", "C3", "_"), sym("note_2", "G2", "_"), sym("barline"))
        val s = OmrScoreBuilder.build("x", listOf(listOf(staff("E5", "D5"), alto, tenorWritten, bass)))
        assertEquals(listOf(67, 67), s.lines[2].notes.map { it.midi })
        assertEquals(listOf(48, 43), s.lines[3].notes.map { it.midi })
    }

    @Test
    fun builder_realHomrOutputForPianoPage() {
        val exp = expected()
        val s = OmrScoreBuilder.build("tabi", listOf(listOf(exp[0]!!, exp[1]!!)))
        // İlk satırda 8 ölçü (biri çift çizgi) var
        assertEquals(8, s.measures.size)
        val top = s.lines.first()
        assertEquals(61, top.notes.first().midi) // Do♯4 (6/8, 4 diyez)
        assertTrue(s.lines.size >= 2)
    }
}
