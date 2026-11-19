package com.notauyumu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MusicXmlParserTest {

    private fun demoBytes(): ByteArray {
        val candidates = listOf("../app/src/main/assets/isinma-korali.musicxml", "app/src/main/assets/isinma-korali.musicxml")
        return candidates.map(::File).first { it.exists() }.readBytes()
    }

    @Test
    fun demoChorale_hasFourVoicesWithCorrectPitchesAndTiming() {
        val s = MusicXmlParser.parse(demoBytes())
        assertEquals("Isınma Korali", s.title)
        assertEquals("Nota Uyumu", s.composer)
        assertEquals(listOf("Soprano", "Alto", "Tenor", "Bas"), s.lines.map { it.name })
        assertEquals(72.0, s.tempoBpm, 1e-9)
        assertEquals(8, s.measures.size)
        assertEquals("süre 32 vuruş @72", 32 * 60.0 / 72, s.durationSec, 1e-6)
        val sop = s.lines[0]
        assertEquals(15, sop.notes.size)
        assertEquals(76, sop.notes[0].midi) // Mi5
        assertEquals("Hep", sop.notes[0].lyric)
        assertEquals("a-", sop.notes[2].lyric)
        // Tenor sekizlik anahtarda yazılı; transpoze uygulanınca ilk nota Sol4 (67) çalmalı
        assertEquals(67, s.lines[2].notes[0].midi)
        assertEquals(48, s.lines[3].notes[0].midi) // Do3
        assertEquals(4, s.measures[0].beats.size)
        assertEquals(8, s.lines[0].notes.last().measureIndex)
    }

    private fun closedScore(): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <score-partwise version="3.1">
          <movement-title>Kapalı Partisyon</movement-title>
          <part-list><score-part id="A"><part-name>Kadınlar</part-name></score-part></part-list>
          <part id="A">
            <measure number="0" implicit="yes">
              <attributes><divisions>4</divisions><time><beats>3</beats><beat-type>4</beat-type></time></attributes>
              <sound tempo="60"/>
              <note><pitch><step>G</step><octave>4</octave></pitch><duration>4</duration><voice>1</voice></note>
              <backup><duration>4</duration></backup>
              <note><pitch><step>E</step><octave>4</octave></pitch><duration>4</duration><voice>2</voice></note>
            </measure>
            <measure number="1">
              <note><pitch><step>C</step><octave>5</octave></pitch><duration>8</duration><tie type="start"/><voice>1</voice><lyric><text>uzun</text></lyric></note>
              <note><pitch><step>C</step><octave>5</octave></pitch><duration>4</duration><tie type="stop"/><voice>1</voice></note>
              <backup><duration>12</duration></backup>
              <note><pitch><step>F</step><alter>1</alter><octave>4</octave></pitch><duration>4</duration><voice>2</voice></note>
              <note><chord/><pitch><step>A</step><octave>4</octave></pitch><duration>4</duration><voice>2</voice></note>
              <note><rest/><duration>4</duration><voice>2</voice></note>
              <note><grace/><pitch><step>D</step><octave>4</octave></pitch><voice>2</voice></note>
              <note><pitch><step>E</step><octave>4</octave></pitch><duration>4</duration><voice>2</voice></note>
            </measure>
            <measure number="2">
              <direction><direction-type><metronome><beat-unit>half</beat-unit><per-minute>60</per-minute></metronome></direction-type></direction>
              <note><pitch><step>B</step><alter>-1</alter><octave>4</octave></pitch><duration>12</duration><voice>1</voice></note>
              <backup><duration>12</duration></backup>
              <note><pitch><step>D</step><octave>4</octave></pitch><duration>12</duration><voice>2</voice></note>
            </measure>
          </part>
        </score-partwise>
    """.trimIndent()

    @Test
    fun closedScore_splitsVoicesMergesTiesHandlesChordsPickupAndTempoChange() {
        val s = MusicXmlParser.parse(closedScore().toByteArray())
        assertEquals("Kapalı Partisyon", s.title)
        assertEquals(listOf("Kadınlar · üst ses", "Kadınlar · alt ses"), s.lines.map { it.name })
        val upper = s.lines[0].notes
        assertEquals(listOf(67, 72, 70), upper.map { it.midi })
        // Anakruz 1 vuruş (1 sn @60), bağlı Do5 3 vuruş = 3 sn
        assertEquals(1.0, upper[1].startSec, 1e-9)
        assertEquals(3.0, upper[1].durationSec, 1e-9)
        assertEquals("uzun", upper[1].lyric)
        // 2. ölçüde tempo yarım=60 -> çeyrek=120: 3 vuruş = 1.5 sn
        assertEquals(4.0, upper[2].startSec, 1e-9)
        assertEquals(1.5, upper[2].durationSec, 1e-9)
        val lower = s.lines[1].notes
        // Akor (Fa#4 + La4) aynı grupta; süs notası atlandı; sus atlandı
        assertEquals(listOf(64, 69, 66, 64, 62), lower.map { it.midi })
        assertEquals(lower[1].chordGroup, lower[2].chordGroup)
        assertEquals(3.0, lower[3].startSec, 1e-9)
        assertEquals(3, s.measures.size)
        assertEquals("0", s.measures[0].label)
        assertEquals(1, s.measures[0].beats.size)
        assertEquals(5.5, s.durationSec, 1e-9)
    }

    @Test
    fun compressedMxl_isOpenedThroughContainer() {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            z.putNextEntry(ZipEntry("META-INF/container.xml"))
            z.write("""<?xml version="1.0"?><container><rootfiles><rootfile full-path="score/main.xml"/></rootfiles></container>""".toByteArray())
            z.closeEntry()
            z.putNextEntry(ZipEntry("score/main.xml")); z.write(demoBytes()); z.closeEntry()
        }
        val s = MusicXmlParser.parse(bos.toByteArray())
        assertEquals(4, s.lines.size)
    }

    @Test
    fun invalidInput_givesTurkishError() {
        var msg: String? = null
        try { MusicXmlParser.parse("bu bir nota değil".toByteArray()) } catch (e: ScoreFormatException) { msg = e.message }
        assertNotNull("hata beklenir", msg)
        assertTrue(msg!!.contains("MusicXML"))
    }

    @Test
    fun scaledScore_slowsEverythingDown() {
        val s = MusicXmlParser.parse(demoBytes())
        val slow = s.scaled(0.5)
        assertEquals(s.durationSec * 2, slow.durationSec, 1e-9)
        assertEquals(36.0, slow.tempoBpm, 1e-9)
        assertEquals(s.lines[0].notes[3].startSec * 2, slow.lines[0].notes[3].startSec, 1e-9)
    }
}
