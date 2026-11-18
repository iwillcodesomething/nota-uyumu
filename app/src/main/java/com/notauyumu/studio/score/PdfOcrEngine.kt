package com.notauyumu.studio.score

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Xml
import com.notauyumu.studio.audio.YinDetector
import com.notauyumu.studio.model.ClefType
import com.notauyumu.studio.model.NoteAccuracyReport
import com.notauyumu.studio.model.NoteEvent
import com.notauyumu.studio.model.PartData
import com.notauyumu.studio.model.PerformanceSummary
import com.notauyumu.studio.model.PitchFrame
import com.notauyumu.studio.model.ScoreData
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class PdfParseResult(
    val score: ScoreData,
    val pageBitmaps: List<Bitmap>
)

private data class DetectedStaff(
    val linesY: List<Int>,
    val topY: Int,
    val bottomY: Int,
    val spacing: Float,
    val xStart: Int,
    val xEnd: Int
)

private data class NoteCandidate(
    val x: Int,
    val y: Int,
    val score: Float,
    val isHollow: Boolean
)

object PdfOcrEngine {

    fun parsePdfFromUri(context: Context, uri: Uri, title: String): PdfParseResult {
        val tempFile = File.createTempFile("score_input_", ".pdf", context.cacheDir)
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tempFile).use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("PDF dosyası okunamadı.")

        val bitmaps = mutableListOf<Bitmap>()
        val pfd = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
        pfd.use {
            PdfRenderer(it).use { renderer ->
                for (i in 0 until renderer.pageCount) {
                    renderer.openPage(i).use { page ->
                        val scale = 2.0f
                        val w = (page.width * scale).toInt().coerceAtMost(2400)
                        val h = (page.height * scale).toInt().coerceAtMost(3400)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmaps.add(bmp)
                    }
                }
            }
        }
        tempFile.delete()

        val score = analyzeBitmapsOfflineOMR(bitmaps, title)
        return PdfParseResult(score = score, pageBitmaps = bitmaps)
    }

    private fun analyzeBitmapsOfflineOMR(bitmaps: List<Bitmap>, title: String): ScoreData {
        data class MutablePart(
            val name: String,
            val clef: ClefType,
            val notes: MutableList<NoteEvent> = mutableListOf(),
            var cursorBeat: Float = 0f,
            var measureOffset: Int = 1
        )

        val partsMap = linkedMapOf<Int, MutablePart>()

        for (bmp in bitmaps) {
            val w = bmp.width
            val h = bmp.height
            val pixels = IntArray(w * h)
            bmp.getPixels(pixels, 0, w, 0, 0, w, h)

            val ink = ByteArray(w * h)
            val rowCounts = IntArray(h)

            for (y in 0 until h) {
                var rSum = 0
                val rowOff = y * w
                for (x in 0 until w) {
                    val px = pixels[rowOff + x]
                    val r = (px shr 16) and 0xFF
                    val g = (px shr 8) and 0xFF
                    val b = px and 0xFF
                    val lum = 0.299 * r + 0.587 * g + 0.114 * b
                    if (lum < 165.0) {
                        ink[rowOff + x] = 1
                        rSum++
                    }
                }
                rowCounts[y] = rSum
            }

            val lineThreshold = max(80, (w * 0.28f).toInt())
            val rawRows = mutableListOf<Int>()
            for (y in 2 until h - 2) {
                if (rowCounts[y] >= lineThreshold) rawRows.add(y)
            }

            val lineCenters = mutableListOf<Int>()
            var i = 0
            while (i < rawRows.size) {
                var sumY = rawRows[i]
                var cnt = 1
                while (i + 1 < rawRows.size && rawRows[i + 1] - rawRows[i] <= 3) {
                    i++
                    sumY += rawRows[i]
                    cnt++
                }
                lineCenters.add((sumY.toFloat() / cnt).roundToInt())
                i++
            }

            val staves = mutableListOf<DetectedStaff>()
            var idx = 0
            while (idx <= lineCenters.size - 5) {
                val y0 = lineCenters[idx]
                val y1 = lineCenters[idx + 1]
                val d = y1 - y0
                if (d in 5..36) {
                    val y2 = lineCenters[idx + 2]
                    val y3 = lineCenters[idx + 3]
                    val y4 = lineCenters[idx + 4]
                    val tol = max(2f, d * 0.28f)
                    if (abs((y2 - y1) - d) <= tol && abs((y3 - y2) - d) <= tol && abs((y4 - y3) - d) <= tol) {
                        var xStart = w
                        var xEnd = 0
                        for (x in 0 until w) {
                            var hits = 0
                            for (ly in listOf(y0, y1, y2, y3, y4)) {
                                if (ink[ly * w + x].toInt() == 1 || ink[(ly - 1) * w + x].toInt() == 1 || ink[(ly + 1) * w + x].toInt() == 1) {
                                    hits++
                                }
                            }
                            if (hits >= 3) {
                                if (x < xStart) xStart = x
                                if (x > xEnd) xEnd = x
                            }
                        }
                        if (xEnd - xStart > w * 0.25f) {
                            staves.add(
                                DetectedStaff(
                                    linesY = listOf(y0, y1, y2, y3, y4),
                                    topY = y0,
                                    bottomY = y4,
                                    spacing = (y4 - y0) / 4f,
                                    xStart = xStart,
                                    xEnd = xEnd
                                )
                            )
                        }
                        idx += 5
                        continue
                    }
                }
                idx++
            }

            if (staves.isEmpty()) continue

            val avgStaffH = staves.map { it.bottomY - it.topY }.average().toFloat()
            val gaps = (1 until staves.size).map { staves[it].topY - staves[it - 1].bottomY }
            val minGap = gaps.minOrNull() ?: 0
            val maxGap = gaps.maxOrNull() ?: 0
            val gapThreshold = when {
                staves.size == 2 -> avgStaffH * 3.2f
                maxGap > minGap * 1.35f -> (minGap + maxGap) / 2f
                else -> avgStaffH * 2.8f
            }
            val systems = mutableListOf(mutableListOf(staves[0]))
            for (s in 1 until staves.size) {
                val gap = staves[s].topY - staves[s - 1].bottomY
                if (gap <= gapThreshold && systems.last().size < 8) {
                    systems.last().add(staves[s])
                } else {
                    systems.add(mutableListOf(staves[s]))
                }
            }

            val cleanInk = ink.copyOf()
            for (staff in staves) {
                val maxThick = max(2, (staff.spacing * 0.24f).toInt())
                for (ly in staff.linesY) {
                    for (x in staff.xStart..staff.xEnd) {
                        var above = 0
                        var below = 0
                        while (ly - above - 1 >= 0 && cleanInk[(ly - above - 1) * w + x].toInt() == 1 && above <= maxThick + 2) above++
                        while (ly + below + 1 < h && cleanInk[(ly + below + 1) * w + x].toInt() == 1 && below <= maxThick + 2) below++
                        if (above + below + 1 <= maxThick) {
                            for (dy in -above..below) {
                                cleanInk[(ly + dy) * w + x] = 0
                            }
                        }
                    }
                }
            }

            for (sysStaves in systems) {
                sysStaves.forEachIndexed { partIdx, staff ->
                    val d = staff.spacing
                    if (!partsMap.containsKey(partIdx)) {
                        val name = when {
                            sysStaves.size == 1 -> "Parti 1 (Solo)"
                            sysStaves.size == 2 -> if (partIdx == 0) "Parti 1 (Üst Porte)" else "Parti 2 (Alt Porte)"
                            sysStaves.size == 4 -> listOf("Soprano", "Alto", "Tenor", "Bas")[partIdx]
                            else -> "Parti ${partIdx + 1}"
                        }
                        val clef = if (sysStaves.size == 2 && partIdx == 1) ClefType.BASS
                        else if (sysStaves.size == 4 && partIdx == 3) ClefType.BASS
                        else ClefType.TREBLE
                        partsMap[partIdx] = MutablePart(name = name, clef = clef)
                    }

                    val partRec = partsMap[partIdx]!!
                    val scanStartX = min(staff.xEnd, (staff.xStart + d * 5.5f).toInt())
                    val staffH = staff.bottomY - staff.topY
                    val barlinesX = mutableListOf<Int>()

                    for (x in scanStartX..staff.xEnd) {
                        var vc = 0
                        for (y in staff.topY..staff.bottomY) {
                            if (ink[y * w + x].toInt() == 1) vc++
                        }
                        if (vc >= staffH * 0.85f) {
                            if (barlinesX.isEmpty() || x - barlinesX.last() > d * 2) {
                                barlinesX.add(x)
                            }
                        }
                    }

                    val rx = max(3, (d * 0.58f).roundToInt())
                    val ry = max(3, (d * 0.45f).roundToInt())
                    val yMin = max(ry + 2, (staff.topY - d * 1.6f).toInt())
                    val yMax = min(h - ry - 3, (staff.bottomY + d * 1.6f).toInt())
                    val stepX = max(2, (d * 0.25f).roundToInt())
                    val stepY = max(1, (d * 0.2f).roundToInt())

                    val cands = mutableListOf<NoteCandidate>()
                    var x = scanStartX
                    while (x <= staff.xEnd - rx) {
                        var y = yMin
                        while (y <= yMax) {
                            var filled = 0
                            var total = 0
                            var innerFilled = 0
                            var innerTotal = 0
                            for (dy in -ry..ry) {
                                for (dx in -rx..rx) {
                                    val norm = (dx * dx).toFloat() / (rx * rx) + (dy * dy).toFloat() / (ry * ry)
                                    if (norm <= 1.0f) {
                                        total++
                                        val px = cleanInk[(y + dy) * w + (x + dx)].toInt() == 1
                                        if (px) filled++
                                        if (norm <= 0.35f) {
                                            innerTotal++
                                            if (px) innerFilled++
                                        }
                                    }
                                }
                            }
                            val fillR = if (total > 0) filled.toFloat() / total else 0f
                            val innerR = if (innerTotal > 0) innerFilled.toFloat() / innerTotal else 0f
                            val isSolid = fillR >= 0.58f && innerR >= 0.68f
                            val isHollow = !isSolid && fillR in 0.24f..0.60f && innerR <= 0.42f
                            if (isSolid || isHollow) {
                                // Çevre halkası kontrolü: gerçek nota başının etrafı çoğunlukla boştur (metin/bağ bloğu değil)
                                var extFilled = 0
                                var extTotal = 0
                                val erx = rx + 2
                                val ery = ry + 2
                                for (dy in -ery..ery) {
                                    for (dx in -erx..erx) {
                                        val norm = (dx * dx).toFloat() / (rx * rx) + (dy * dy).toFloat() / (ry * ry)
                                        if (norm in 1.25f..2.2f) {
                                            val yy = y + dy
                                            val xx = x + dx
                                            if (yy in 0 until h && xx in 0 until w) {
                                                extTotal++
                                                if (cleanInk[yy * w + xx].toInt() == 1) extFilled++
                                            }
                                        }
                                    }
                                }
                                val extR = if (extTotal > 0) extFilled.toFloat() / extTotal else 0f
                                if (extR < 0.36f) {
                                    cands.add(NoteCandidate(x, y, if (isSolid) fillR else fillR + 0.15f, isHollow))
                                }
                            }
                            y += stepY
                        }
                        x += stepX
                    }

                    cands.sortByDescending { it.score }
                    val picked = mutableListOf<NoteCandidate>()
                    for (c in cands) {
                        if (picked.none { abs(it.x - c.x) < d * 1.15f && abs(it.y - c.y) < d * 0.85f }) {
                            picked.add(c)
                        }
                    }

                    picked.sortBy { it.x }
                    val melodic = mutableListOf<NoteCandidate>()
                    for (p in picked) {
                        if (melodic.none { abs(it.x - p.x) < d * 0.7f }) {
                            melodic.add(p)
                        }
                    }

                    val iois = mutableListOf<Int>()
                    for (k in 1 until melodic.size) {
                        val dx = melodic[k].x - melodic[k - 1].x
                        if (dx > d * 1.1f && dx < d * 12f) iois.add(dx)
                    }
                    iois.sort()
                    val baseIoi = if (iois.isNotEmpty()) iois[iois.size / 2].toFloat() else d * 3.2f

                    melodic.forEachIndexed { mIdx, mn ->
                        val barCount = barlinesX.count { it < mn.x }
                        val measureNum = partRec.measureOffset + barCount
                        val step = ((staff.bottomY - mn.y) / (d / 2f)).roundToInt()
                        val midi = diatonicStepToMidi(step, partRec.clef)

                        var dur = if (mn.isHollow) 2f else 1f
                        if (mIdx < melodic.size - 1 && !mn.isHollow) {
                            val dxNext = (melodic[mIdx + 1].x - mn.x).toFloat()
                            if (dxNext < baseIoi * 0.65f) {
                                dur = 0.5f
                            } else if (dxNext > baseIoi * 2.1f) {
                                partRec.notes.add(
                                    NoteEvent(measureNum, YinDetector.midiToNoteName(midi.toFloat()), midi, 1f, partRec.cursorBeat, false)
                                )
                                partRec.cursorBeat += 1f
                                partRec.notes.add(
                                    NoteEvent(measureNum, "REST", null, 1f, partRec.cursorBeat, true)
                                )
                                partRec.cursorBeat += 1f
                                return@forEachIndexed
                            }
                        }

                        partRec.notes.add(
                            NoteEvent(measureNum, YinDetector.midiToNoteName(midi.toFloat()), midi, dur, partRec.cursorBeat, false)
                        )
                        partRec.cursorBeat += dur
                    }

                    partRec.measureOffset += max(1, barlinesX.size)
                }
            }
        }

        val parts = partsMap.values.filter { it.notes.isNotEmpty() }.mapIndexed { idx, mp ->
            PartData(id = "part-${idx + 1}", name = mp.name, clef = mp.clef, notes = mp.notes)
        }

        if (parts.isEmpty()) {
            throw IllegalStateException("PDF görselinde dizek veya nota başı tespit edilemedi.")
        }

        return ScoreData(title = title.removeSuffix(".pdf"), parts = parts)
    }

    fun parseMusicXmlFromUri(context: Context, uri: Uri, fileName: String): ScoreData {
        val rawBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("MusicXML dosyası okunamadı.")

        val xmlBytes = if (fileName.lowercase().endsWith(".mxl")) {
            var extracted: ByteArray? = null
            ZipInputStream(ByteArrayInputStream(rawBytes)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.name.startsWith("META-INF") &&
                        (entry.name.endsWith(".xml") || entry.name.endsWith(".musicxml"))
                    ) {
                        extracted = zis.readBytes()
                        break
                    }
                    entry = zis.nextEntry
                }
            }
            extracted ?: throw IllegalStateException("MXL arşivi içinde XML bulunamadı.")
        } else {
            rawBytes
        }

        val parser = Xml.newPullParser()
        parser.setInput(ByteArrayInputStream(xmlBytes), "UTF-8")

        val partNames = mutableMapOf<String, String>()
        val parts = mutableListOf<PartData>()

        var currentScorePartId: String? = null
        var currentPartId: String? = null
        var currentNotes = mutableListOf<NoteEvent>()
        var currentClef = ClefType.TREBLE
        var divisions = 1f
        var cursorBeat = 0f
        var measureNum = 1

        var inNote = false
        var isChord = false
        var isRest = false
        var step = "C"
        var alter = 0
        var octave = 4
        var durationVal = 1f
        var clefSign = "G"
        var clefLine = "2"
        var docBpm = 80
        var docBeatsPerMeasure = 4

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "score-part" -> currentScorePartId = parser.getAttributeValue(null, "id")
                    "part-name" -> if (currentScorePartId != null) {
                        partNames[currentScorePartId] = parser.nextText().trim()
                    }
                    "part" -> {
                        currentPartId = parser.getAttributeValue(null, "id") ?: "P${parts.size + 1}"
                        currentNotes = mutableListOf()
                        currentClef = ClefType.TREBLE
                        divisions = 1f
                        cursorBeat = 0f
                        measureNum = 1
                    }
                    "measure" -> {
                        measureNum = parser.getAttributeValue(null, "number")?.toIntOrNull() ?: measureNum
                    }
                    "divisions" -> divisions = parser.nextText().toFloatOrNull()?.coerceAtLeast(1f) ?: 1f
                    "beats" -> docBeatsPerMeasure = parser.nextText().toIntOrNull()?.coerceIn(2, 12) ?: docBeatsPerMeasure
                    "sound" -> {
                        val t = parser.getAttributeValue(null, "tempo")?.toFloatOrNull()?.roundToInt()
                        if (t != null && t in 30..240) docBpm = t
                    }
                    "per-minute" -> {
                        val t = parser.nextText().toFloatOrNull()?.roundToInt()
                        if (t != null && t in 30..240) docBpm = t
                    }
                    "sign" -> clefSign = parser.nextText().trim()
                    "line" -> clefLine = parser.nextText().trim()
                    "note" -> {
                        inNote = true; isChord = false; isRest = false
                        step = "C"; alter = 0; octave = 4; durationVal = divisions
                    }
                    "chord" -> if (inNote) isChord = true
                    "rest" -> if (inNote) isRest = true
                    "step" -> if (inNote) step = parser.nextText().trim()
                    "alter" -> if (inNote) alter = parser.nextText().toIntOrNull() ?: 0
                    "octave" -> if (inNote) octave = parser.nextText().toIntOrNull() ?: 4
                    "duration" -> if (inNote) durationVal = parser.nextText().toFloatOrNull() ?: divisions
                }

                XmlPullParser.END_TAG -> when (parser.name) {
                    "clef" -> {
                        currentClef = when {
                            clefSign == "F" -> ClefType.BASS
                            clefSign == "C" && clefLine == "4" -> ClefType.TENOR
                            clefSign == "C" -> ClefType.ALTO
                            else -> ClefType.TREBLE
                        }
                    }
                    "note" -> {
                        if (inNote && !isChord) {
                            val durBeats = (durationVal / divisions).coerceAtLeast(0.25f)
                            if (isRest) {
                                currentNotes.add(NoteEvent(measureNum, "REST", null, durBeats, cursorBeat, true))
                            } else {
                                val baseMap = mapOf("C" to 0, "D" to 2, "E" to 4, "F" to 5, "G" to 7, "A" to 9, "B" to 11)
                                val midi = (octave + 1) * 12 + (baseMap[step] ?: 0) + alter
                                currentNotes.add(
                                    NoteEvent(measureNum, YinDetector.midiToNoteName(midi.toFloat()), midi, durBeats, cursorBeat, false)
                                )
                            }
                            cursorBeat += durBeats
                        }
                        inNote = false
                    }
                    "part" -> {
                        if (currentNotes.isNotEmpty()) {
                            val pName = partNames[currentPartId] ?: "Parti ${parts.size + 1}"
                            parts.add(PartData("part-${parts.size + 1}", pName, currentClef, currentNotes))
                        }
                    }
                }
            }
            eventType = parser.next()
        }

        if (parts.isEmpty()) throw IllegalStateException("MusicXML içinde nota bulunamadı.")
        return ScoreData(title = fileName, parts = parts, bpm = docBpm, beatsPerMeasure = docBeatsPerMeasure)
    }

    fun diatonicStepToMidi(step: Int, clef: ClefType): Int {
        val base = when (clef) {
            ClefType.TREBLE -> 30
            ClefType.BASS -> 18
            ClefType.ALTO -> 24
            ClefType.TENOR -> 22
        }
        val diatonic = base + step
        val octave = Math.floorDiv(diatonic, 7)
        val degree = ((diatonic % 7) + 7) % 7
        val semitones = intArrayOf(0, 2, 4, 5, 7, 9, 11)
        return (octave + 1) * 12 + semitones[degree]
    }

    fun remapPartClef(part: PartData, newClef: ClefType): PartData {
        if (part.clef == newClef) return part
        val delta = diatonicStepToMidi(4, newClef) - diatonicStepToMidi(4, part.clef)
        return part.copy(
            clef = newClef,
            notes = part.notes.map { n ->
                if (n.isRest || n.midi == null) n
                else {
                    val m = n.midi + delta
                    n.copy(midi = m, note = YinDetector.midiToNoteName(m.toFloat()))
                }
            }
        )
    }

    fun computePerformanceSummary(
        part: PartData,
        frames: List<PitchFrame>,
        toleranceCents: Int,
        transposeOctaves: Int,
        passingThresholdPercent: Int = 60
    ): PerformanceSummary {
        val breakdown = mutableListOf<NoteAccuracyReport>()
        var totalFrames = 0
        var totalHit = 0
        var voicedSum = 0f
        var voicedAbsSum = 0f
        var voicedCount = 0
        var maxAbsCents = 0f
        var wrongFrames = 0
        var silentFrames = 0
        var hitNotes = 0
        val weakMeasuresSet = linkedSetOf<Int>()

        var refTotal = 0
        var refHit = 0

        for (f in frames) {
            if (f.referenceMidi != null && f.midiFloat != null) {
                refTotal++
                if (abs((f.midiFloat - f.referenceMidi) * 100f) <= toleranceCents) refHit++
            } else if (f.referenceMidi != null) {
                refTotal++
            }
        }

        part.notes.forEachIndexed { idx, n ->
            if (n.isRest || n.midi == null) return@forEachIndexed
            val targetMidi = n.midi + transposeOctaves * 12
            val noteFrames = frames.filter { it.beat >= n.startBeat && it.beat < n.startBeat + n.durationBeats }
            var nHit = 0
            var nSum = 0f
            var nAbsSum = 0f
            var nCnt = 0

            for (f in noteFrames) {
                if (f.midiFloat != null) {
                    val cents = (f.midiFloat - targetMidi) * 100f
                    val absC = abs(cents)
                    if (absC <= toleranceCents) nHit++ else wrongFrames++
                    if (absC > maxAbsCents && absC <= 1200f) maxAbsCents = absC
                    if (absC <= 600f) {
                        nSum += cents
                        nAbsSum += absC
                        nCnt++
                        voicedSum += cents
                        voicedAbsSum += absC
                        voicedCount++
                    }
                } else {
                    silentFrames++
                }
            }

            val pct = if (noteFrames.isNotEmpty()) ((nHit.toFloat() / noteFrames.size) * 100f).roundToInt() else 0
            if (pct >= passingThresholdPercent) hitNotes++ else weakMeasuresSet.add(n.measure)
            totalFrames += noteFrames.size
            totalHit += nHit

            breakdown.add(
                NoteAccuracyReport(
                    index = idx,
                    measure = n.measure,
                    note = YinDetector.midiToNoteName(targetMidi.toFloat()),
                    midi = targetMidi,
                    durationBeats = n.durationBeats,
                    accuracyPercent = pct,
                    avgCentsOff = if (nCnt > 0) (nSum / nCnt).roundToInt() else null,
                    meanAbsCents = if (nCnt > 0) (nAbsSum / nCnt).roundToInt() else null
                )
            )
        }

        val overall = if (totalFrames > 0) ((totalHit.toFloat() / totalFrames) * 100f).roundToInt() else 0
        val refMatch = if (refTotal > 0) ((refHit.toFloat() / refTotal) * 100f).roundToInt() else null
        val avgCents = if (voicedCount > 0) (voicedSum / voicedCount).roundToInt() else 0
        val meanAbs = if (voicedCount > 0) (voicedAbsSum / voicedCount).roundToInt() else 0
        val wrongRate = if (totalFrames > 0) ((wrongFrames.toFloat() / totalFrames) * 100f).roundToInt() else 0
        val silentRate = if (totalFrames > 0) ((silentFrames.toFloat() / totalFrames) * 100f).roundToInt() else 0

        val tendencyLabel = when {
            voicedCount < 5 -> "Yeterli ses algılanamadı. Mikrofonu veya Noise Gate eşiğini kontrol edin."
            avgCents > 10 -> "Tiz (Sürtone) Eğilim: Ortalama +$avgCents cent (mutlak ±$meanAbs c) sapma."
            avgCents < -10 -> "Pes (Detone) Eğilim: Ortalama $avgCents cent (mutlak ±$meanAbs c) sapma."
            else -> "Dengeli Entonasyon: Ortalama mutlak sapma ±$meanAbs cent."
        }

        return PerformanceSummary(
            partId = part.id,
            partName = part.name,
            overallAccuracy = overall,
            referenceMatchPercent = refMatch,
            avgCentsOff = avgCents,
            meanAbsCentsError = meanAbs,
            maxCentsError = maxAbsCents.roundToInt(),
            wrongNoteRatePercent = wrongRate,
            silentRatePercent = silentRate,
            weakMeasures = weakMeasuresSet.toList(),
            tendencyLabel = tendencyLabel,
            noteBreakdown = breakdown,
            totalTargetNotes = breakdown.size,
            hitNotesCount = hitNotes
        )
    }

    private fun buildTimeline(notes: List<NoteEvent>): List<NoteEvent> {
        var cursor = 0f
        return notes.map { n ->
            val item = n.copy(startBeat = cursor)
            cursor += n.durationBeats
            item
        }
    }

    val DEFAULT_SATB_SCORE = ScoreData(
        title = "Klasik 4 Partili Koro Etüdü (SATB)",
        parts = listOf(
            PartData(
                id = "part-1", name = "Soprano", clef = ClefType.TREBLE,
                notes = buildTimeline(
                    listOf(
                        NoteEvent(1, "C5", 72, 1f), NoteEvent(1, "D5", 74, 1f), NoteEvent(1, "E5", 76, 2f),
                        NoteEvent(2, "F5", 77, 1f), NoteEvent(2, "E5", 76, 1f), NoteEvent(2, "D5", 74, 1f),
                        NoteEvent(2, "REST", null, 1f, isRest = true),
                        NoteEvent(3, "E5", 76, 1f), NoteEvent(3, "D5", 74, 1f), NoteEvent(3, "C5", 72, 2f),
                        NoteEvent(4, "D5", 74, 2f), NoteEvent(4, "C5", 72, 2f)
                    )
                )
            ),
            PartData(
                id = "part-2", name = "Alto", clef = ClefType.TREBLE,
                notes = buildTimeline(
                    listOf(
                        NoteEvent(1, "G4", 67, 2f), NoteEvent(1, "G4", 67, 2f),
                        NoteEvent(2, "A4", 69, 1f), NoteEvent(2, "G4", 67, 1f), NoteEvent(2, "F4", 65, 1f),
                        NoteEvent(2, "REST", null, 1f, isRest = true),
                        NoteEvent(3, "G4", 67, 2f), NoteEvent(3, "E4", 64, 2f),
                        NoteEvent(4, "F4", 65, 2f), NoteEvent(4, "E4", 64, 2f)
                    )
                )
            ),
            PartData(
                id = "part-3", name = "Tenor", clef = ClefType.TREBLE,
                notes = buildTimeline(
                    listOf(
                        NoteEvent(1, "REST", null, 1f, isRest = true),
                        NoteEvent(1, "E4", 64, 1f), NoteEvent(1, "C4", 60, 2f),
                        NoteEvent(2, "C4", 60, 1f), NoteEvent(2, "C4", 60, 1f), NoteEvent(2, "B3", 59, 2f),
                        NoteEvent(3, "C4", 60, 2f), NoteEvent(3, "G3", 55, 2f),
                        NoteEvent(4, "B3", 59, 2f), NoteEvent(4, "G3", 55, 2f)
                    )
                )
            ),
            PartData(
                id = "part-4", name = "Bas", clef = ClefType.BASS,
                notes = buildTimeline(
                    listOf(
                        NoteEvent(1, "C3", 48, 2f), NoteEvent(1, "C3", 48, 2f),
                        NoteEvent(2, "F3", 53, 1f), NoteEvent(2, "C3", 48, 1f), NoteEvent(2, "G3", 55, 2f),
                        NoteEvent(3, "C3", 48, 2f), NoteEvent(3, "C3", 48, 2f),
                        NoteEvent(4, "G2", 43, 2f), NoteEvent(4, "C3", 48, 2f)
                    )
                )
            )
        )
    )
}
