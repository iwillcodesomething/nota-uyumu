package com.notauyumu.core

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node as DomNode
import org.xml.sax.InputSource
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.StringReader
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class ScoreFormatException(message: String) : Exception(message)

/**
 * MusicXML (score-partwise) okuyucu. Sıkıştırılmış .mxl dosyalarını da açar.
 *
 * Desteklenenler: çoklu parti, parti içi çoklu ses (ör. tek portede S+A),
 * akorlar, bağlar (tie), şarkı sözü, tempo değişimleri, transpoze eden
 * partiler (ör. sekizlik tenor anahtarı), anakruz.
 * Bilerek yok sayılanlar: tekrar işaretleri (eser düz çalınır), süs notaları, cue notaları.
 */
object MusicXmlParser {

    private const val DEFAULT_BPM = 90.0

    fun parse(bytes: ByteArray, fallbackTitle: String = "Adsız eser"): Score {
        val xml = if (isZip(bytes)) extractFromMxl(bytes) else bytes
        return parseXml(xml, fallbackTitle)
    }

    fun parse(stream: InputStream, fallbackTitle: String = "Adsız eser"): Score =
        parse(stream.readBytes(), fallbackTitle)

    private fun isZip(b: ByteArray) = b.size > 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte()

    private fun extractFromMxl(bytes: ByteArray): ByteArray {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (!e.isDirectory) entries[e.name] = zip.readBytes()
            }
        }
        val container = entries["META-INF/container.xml"]
        if (container != null) {
            val doc = buildDocument(container)
            val path = doc.getElementsByTagName("rootfile").let { if (it.length > 0) (it.item(0) as Element).getAttribute("full-path") else "" }
            entries[path]?.let { return it }
        }
        return entries.entries.firstOrNull { (name, _) ->
            !name.startsWith("META-INF") && (name.endsWith(".xml") || name.endsWith(".musicxml"))
        }?.value ?: throw ScoreFormatException(".mxl arşivinin içinde nota dosyası bulunamadı.")
    }

    private fun buildDocument(bytes: ByteArray): Document {
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = false
        f.isValidating = false
        // Dış DTD indirmeyi kapat (internet gerektirmesin, güvenli olsun). Android bazı özellikleri desteklemez.
        for ((feature, value) in listOf(
            "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false,
            "http://xml.org/sax/features/external-general-entities" to false,
            "http://xml.org/sax/features/external-parameter-entities" to false,
        )) runCatching { f.setFeature(feature, value) }
        val builder = f.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
        builder.setErrorHandler(object : org.xml.sax.ErrorHandler {
            override fun warning(e: org.xml.sax.SAXParseException) {}
            override fun error(e: org.xml.sax.SAXParseException) { throw e }
            override fun fatalError(e: org.xml.sax.SAXParseException) { throw e }
        })
        return try {
            builder.parse(ByteArrayInputStream(bytes))
        } catch (e: Exception) {
            throw ScoreFormatException("Dosya okunamadı: geçerli bir MusicXML değil.")
        }
    }

    // ---------------------------------------------------------------------

    private data class RawNote(
        val partIndex: Int,
        val voice: String,
        val midi: Int,
        val startQ: Double,
        var durQ: Double,
        val measureIndex: Int,
        var lyric: String?,
    )

    private data class RawMeasure(val label: String, val startQ: Double, val lengthQ: Double, val beats: Int, val beatType: Int)

    private fun parseXml(bytes: ByteArray, fallbackTitle: String): Score {
        val doc = buildDocument(bytes)
        val root = doc.documentElement
        if (root.tagName == "score-timewise") throw ScoreFormatException("score-timewise biçimi desteklenmiyor; MuseScore'dan yeniden MusicXML olarak dışa aktarın.")
        if (root.tagName != "score-partwise") throw ScoreFormatException("Bu bir MusicXML nota dosyası değil.")

        val title = listOf(
            root.child("work")?.child("work-title")?.text(),
            root.child("movement-title")?.text(),
            root.children("credit").flatMap { it.children("credit-words") }.firstOrNull()?.text(),
        ).firstOrNull { !it.isNullOrBlank() }?.trim() ?: fallbackTitle

        val composer = root.child("identification")?.children("creator")
            ?.firstOrNull { it.getAttribute("type") == "composer" }?.text()?.trim()?.takeIf { it.isNotEmpty() }

        val partNames = LinkedHashMap<String, String>()
        root.child("part-list")?.children("score-part")?.forEachIndexed { i, sp ->
            val name = sp.child("part-name")?.text()?.trim().orEmpty()
            val abbr = sp.child("part-abbreviation")?.text()?.trim().orEmpty()
            partNames[sp.getAttribute("id")] = when {
                name.isNotEmpty() && !name.equals("MusicXML Part", true) -> name
                abbr.isNotEmpty() -> abbr
                else -> "Parti ${i + 1}"
            }
        }

        val parts = root.children("part")
        if (parts.isEmpty()) throw ScoreFormatException("Notada hiç parti yok.")

        val raw = ArrayList<RawNote>()
        val tempos = sortedMapOf<Double, Double>()
        var canonicalMeasures: List<RawMeasure> = emptyList()

        parts.forEachIndexed { partIndex, part ->
            val measures = parsePart(part, partIndex, raw, tempos)
            if (partIndex == 0) canonicalMeasures = measures
        }
        if (canonicalMeasures.isEmpty()) throw ScoreFormatException("Notada ölçü bulunamadı.")

        val tempoMap = TempoMap(tempos)

        val measures = canonicalMeasures.mapIndexed { i, m ->
            val beatQ = if (m.beatType == 8 && m.beats % 3 == 0 && m.beats > 3) 1.5 else 4.0 / m.beatType
            val beats = ArrayList<Double>()
            var q = m.startQ
            while (q < m.startQ + m.lengthQ - 1e-6) { beats += tempoMap.toSec(q); q += beatQ }
            Measure(i + 1, m.label, tempoMap.toSec(m.startQ), tempoMap.toSec(m.startQ + m.lengthQ), beats)
        }

        val lines = buildLines(raw, parts.map { partNames[it.getAttribute("id")] ?: "Parti" }, tempoMap)
        if (lines.isEmpty()) throw ScoreFormatException("Notada söylenebilecek perdeli nota bulunamadı.")

        return Score(title, composer, lines, measures, tempoMap.initialBpm)
    }

    private fun parsePart(part: Element, partIndex: Int, out: MutableList<RawNote>, tempos: MutableMap<Double, Double>): List<RawMeasure> {
        var divisions = 1.0
        var beats = 4
        var beatType = 4
        var transpose = 0
        var cursor = 0.0
        val measures = ArrayList<RawMeasure>()
        // Bağ (tie) devam eden notalar: anahtar = ses + perde
        val openTies = HashMap<String, RawNote>()

        for ((mi, measure) in part.children("measure").withIndex()) {
            val measureStart = cursor
            var maxPos = cursor
            var lastNoteStart = cursor

            for (el in measure.elementChildren()) {
                when (el.tagName) {
                    "attributes" -> {
                        el.child("divisions")?.text()?.toDoubleOrNull()?.let { if (it > 0) divisions = it }
                        el.child("time")?.let { t ->
                            t.child("beats")?.text()?.trim()?.split('+')?.sumOf { it.toIntOrNull() ?: 0 }?.takeIf { it > 0 }?.let { beats = it }
                            t.child("beat-type")?.text()?.trim()?.toIntOrNull()?.takeIf { it > 0 }?.let { beatType = it }
                        }
                        el.child("transpose")?.let { tr ->
                            transpose = (tr.child("chromatic")?.text()?.trim()?.toIntOrNull() ?: 0) +
                                12 * (tr.child("octave-change")?.text()?.trim()?.toIntOrNull() ?: 0)
                        }
                    }
                    "direction" -> {
                        val offsetQ = (el.child("offset")?.text()?.toDoubleOrNull() ?: 0.0) / divisions
                        val soundTempo = el.child("sound")?.getAttribute("tempo")?.toDoubleOrNull()
                        val metronome = el.children("direction-type").firstNotNullOfOrNull { it.child("metronome") }?.let(::metronomeToQuarterBpm)
                        (soundTempo ?: metronome)?.takeIf { it in 10.0..400.0 }?.let { tempos.putIfAbsent(cursor + offsetQ, it) }
                    }
                    "sound" -> el.getAttribute("tempo").toDoubleOrNull()?.takeIf { it in 10.0..400.0 }?.let { tempos.putIfAbsent(cursor, it) }
                    "backup" -> cursor -= (el.child("duration")?.text()?.toDoubleOrNull() ?: 0.0) / divisions
                    "forward" -> { cursor += (el.child("duration")?.text()?.toDoubleOrNull() ?: 0.0) / divisions; maxPos = maxOf(maxPos, cursor) }
                    "note" -> {
                        if (el.child("grace") != null) continue
                        val durQ = (el.child("duration")?.text()?.toDoubleOrNull() ?: 0.0) / divisions
                        val isChord = el.child("chord") != null
                        val start = if (isChord) lastNoteStart else cursor
                        if (!isChord) { lastNoteStart = cursor; cursor += durQ }
                        maxPos = maxOf(maxPos, cursor)
                        if (el.child("cue") != null) continue
                        val pitch = el.child("pitch") ?: continue // sus veya perdesiz vurmalı
                        val midi = Pitch.fromStep(
                            pitch.child("step")?.text() ?: continue,
                            pitch.child("alter")?.text()?.trim()?.toDoubleOrNull()?.toInt() ?: 0,
                            pitch.child("octave")?.text()?.trim()?.toIntOrNull() ?: continue,
                        ) + transpose
                        val voice = el.child("voice")?.text()?.trim()?.ifEmpty { null } ?: "1"
                        val ties = el.children("tie").map { it.getAttribute("type") } +
                            (el.child("notations")?.children("tied")?.map { it.getAttribute("type") } ?: emptyList())
                        val lyric = el.children("lyric").firstOrNull { it.getAttribute("number").let { n -> n.isEmpty() || n == "1" } }
                            ?.let(::lyricText)
                        val key = "$voice/$midi"
                        val pending = openTies[key]
                        if ("stop" in ties && pending != null && kotlin.math.abs(pending.startQ + pending.durQ - start) < 1e-3) {
                            pending.durQ += durQ
                            if ("start" !in ties) openTies.remove(key)
                        } else {
                            val n = RawNote(partIndex, voice, midi, start, durQ, mi + 1, lyric)
                            if (durQ > 0) out += n
                            if ("start" in ties) openTies[key] = n else openTies.remove(key)
                        }
                    }
                }
            }
            val nominal = beats * 4.0 / beatType
            var length = maxPos - measureStart
            if (length < 1e-6) length = nominal
            val implicit = measure.getAttribute("implicit") == "yes"
            if (!implicit && mi > 0 && length < nominal - 1e-6) length = nominal // eksik doldurulmuş ölçü
            measures += RawMeasure(measure.getAttribute("number").ifEmpty { "${mi + 1}" }, measureStart, length, beats, beatType)
            cursor = measureStart + length
        }
        return measures
    }

    private fun metronomeToQuarterBpm(m: Element): Double? {
        val perMinute = m.child("per-minute")?.text()?.trim()?.toDoubleOrNull() ?: return null
        val unitQ = when (m.child("beat-unit")?.text()?.trim()) {
            "whole" -> 4.0; "half" -> 2.0; "quarter" -> 1.0; "eighth" -> 0.5; "16th" -> 0.25
            else -> 1.0
        } * (if (m.child("beat-unit-dot") != null) 1.5 else 1.0)
        return perMinute * unitQ
    }

    private fun lyricText(l: Element): String? {
        val text = l.children("text").joinToString("") { it.text() }.trim()
        if (text.isEmpty()) return null
        return when (l.child("syllabic")?.text()?.trim()) {
            "begin" -> "$text-"
            "middle" -> "-$text-"
            "end" -> "-$text"
            else -> text
        }
    }

    private fun buildLines(raw: List<RawNote>, partNames: List<String>, tempo: TempoMap): List<VoiceLine> {
        val result = ArrayList<VoiceLine>()
        var chordCounter = 0
        raw.groupBy { it.partIndex }.toSortedMap().forEach { (pi, notes) ->
            val byVoice = notes.groupBy { it.voice }
                .entries.sortedByDescending { e -> e.value.map { it.midi }.average() }
            byVoice.forEachIndexed { vi, (voice, vNotes) ->
                val partName = partNames.getOrElse(pi) { "Parti ${pi + 1}" }
                val name = when {
                    byVoice.size == 1 -> partName
                    byVoice.size == 2 -> "$partName · ${if (vi == 0) "üst" else "alt"} ses"
                    else -> "$partName · ${vi + 1}. ses"
                }
                val sorted = vNotes.sortedWith(compareBy({ it.startQ }, { -it.midi }))
                val mapped = ArrayList<Note>(sorted.size)
                var lastStart = Double.NaN
                for (n in sorted) {
                    if (n.startQ != lastStart) { chordCounter++; lastStart = n.startQ }
                    val s = tempo.toSec(n.startQ)
                    mapped += Note(n.midi, s, tempo.toSec(n.startQ + n.durQ) - s, n.measureIndex, n.lyric, chordCounter)
                }
                result += VoiceLine("p$pi-v$voice", name, partName, mapped)
            }
        }
        return result
    }
}

/** Çeyrek nota konumunu saniyeye çevirir; tempo değişimlerini hesaba katar. */
internal class TempoMap(changes: Map<Double, Double>) {
    private val points: List<Pair<Double, Double>>
    val initialBpm: Double

    init {
        val list = changes.entries.sortedBy { it.key }.map { it.key to it.value }.toMutableList()
        initialBpm = list.firstOrNull()?.second ?: 90.0
        if (list.isEmpty() || list[0].first > 1e-9) list.add(0, 0.0 to initialBpm)
        points = list
    }

    fun toSec(q: Double): Double {
        var sec = 0.0
        for (i in points.indices) {
            val (startQ, bpm) = points[i]
            val endQ = points.getOrNull(i + 1)?.first ?: Double.MAX_VALUE
            if (q <= startQ) break
            sec += (minOf(q, endQ) - startQ) * 60.0 / bpm
            if (q <= endQ) break
        }
        if (q < 0) sec = q * 60.0 / points[0].second
        return sec
    }
}

// --- küçük DOM yardımcıları ---
internal fun Element.elementChildren(): List<Element> {
    val out = ArrayList<Element>()
    val nl = childNodes
    for (i in 0 until nl.length) { val n = nl.item(i); if (n.nodeType == DomNode.ELEMENT_NODE) out += n as Element }
    return out
}
internal fun Element.children(tag: String): List<Element> = elementChildren().filter { it.tagName == tag }
internal fun Element.child(tag: String): Element? = elementChildren().firstOrNull { it.tagName == tag }
internal fun Element.text(): String = textContent ?: ""
