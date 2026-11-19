package com.notauyumu.core

import kotlin.math.abs

/** Mikrofondan gelen tek bir ölçüm. [midi] null ise o anda ses yok. Zaman eser zamanıdır. */
data class PitchSample(val timeSec: Double, val midi: Double?)

enum class Grade { HIT, NEAR, MISS, SILENT }

data class ScoringSettings(
    /** Doğru kabul edilen sapma (cent). */
    val toleranceCents: Double = 35.0,
    /** Oktav farkını hata sayma. */
    val forgiveOctave: Boolean = true,
)

data class NoteResult(
    val note: Note,
    /** 0..1: notanın değerlendirilen süresinin ne kadarında doğru perdedeydin. */
    val accuracy: Double,
    /** Ses verilen ölçümlerde ortalama işaretli sapma (cent); + tiz, - pes. Ses yoksa null. */
    val meanCents: Double?,
    /** Notanın ne kadarında ses vardı. */
    val voicedRatio: Double,
)

data class MeasureResult(val measureIndex: Int, val label: String, val accuracy: Double)

data class PerformanceReport(
    val lineName: String,
    val overall: Double,
    val notes: List<NoteResult>,
    val measures: List<MeasureResult>,
    /** Bütün sesli ölçümlerin ortalama işaretli sapması: genel pes/tiz eğilimi. */
    val tendencyCents: Double?,
    val voicedRatio: Double,
) {
    fun weakestMeasures(count: Int = 3): List<MeasureResult> =
        measures.filter { it.accuracy < 0.85 }.sortedBy { it.accuracy }.take(count).sortedBy { it.measureIndex }
}

object Scorer {

    /** Bir anlık ölçümü hedefe göre derecelendirir (canlı renklendirme için). */
    fun grade(sungMidi: Double?, targets: List<Int>, s: ScoringSettings): Grade {
        if (sungMidi == null) return Grade.SILENT
        if (targets.isEmpty()) return Grade.SILENT
        val err = targets.minOf { abs(Pitch.centsOff(sungMidi, it, s.forgiveOctave)) }
        return when {
            err <= s.toleranceCents -> Grade.HIT
            err <= s.toleranceCents * 2 -> Grade.NEAR
            else -> Grade.MISS
        }
    }

    /** Verilen zamanda çalan hedef notalar (akor olabilir). */
    fun targetsAt(line: VoiceLine, timeSec: Double): List<Note> =
        line.notes.filter { timeSec >= it.startSec && timeSec < it.endSec }

    /**
     * Performansı puanlar. [samples] zamana göre sıralı olmalıdır.
     * [fromSec]/[untilSec] çalışılan aralığı belirler (ölçü döngüsü).
     */
    fun evaluate(
        line: VoiceLine,
        measures: List<Measure>,
        samples: List<PitchSample>,
        s: ScoringSettings,
        fromSec: Double = 0.0,
        untilSec: Double = Double.MAX_VALUE,
    ): PerformanceReport {
        val notes = line.notes.filter { it.startSec >= fromSec - 1e-6 && it.startSec < untilSec - 1e-6 }
        // Akorlarda: aynı grupta her an tek bir "en yakın" nota ile değerlendirilir; akor üyelerini tek nota sayıyoruz.
        val groups = notes.groupBy { it.chordGroup }.values.map { g -> g.sortedByDescending { it.midi } }

        val results = ArrayList<NoteResult>()
        var allSigned = 0.0; var allSignedN = 0
        var totalWeight = 0.0; var weighted = 0.0
        var totalWindow = 0; var totalVoiced = 0

        for (group in groups) {
            val ref = group.first()
            // Atak payı: notanın ilk %20'si (en fazla 120 ms) ve son 40 ms değerlendirilmez.
            val grace = minOf(0.12, ref.durationSec * 0.2)
            val from = ref.startSec + grace
            val to = ref.endSec - minOf(0.04, ref.durationSec * 0.1)
            val window = samplesBetween(samples, from, to)
            if (window.isEmpty()) continue // çok kısa nota: değerlendirme dışı
            var hits = 0; var voiced = 0; var signed = 0.0
            for (p in window) {
                val m = p.midi ?: continue
                voiced++
                val nearest = group.minBy { abs(Pitch.centsOff(m, it.midi, s.forgiveOctave)) }
                val c = Pitch.centsOff(m, nearest.midi, s.forgiveOctave)
                signed += c
                if (abs(c) <= s.toleranceCents) hits++
            }
            val acc = hits.toDouble() / window.size
            val mean = if (voiced > 0) signed / voiced else null
            allSigned += signed; allSignedN += voiced
            totalWindow += window.size; totalVoiced += voiced
            weighted += acc * ref.durationSec; totalWeight += ref.durationSec
            group.forEach { results += NoteResult(it, acc, mean, voiced.toDouble() / window.size) }
        }

        val measureResults = results.groupBy { it.note.measureIndex }.toSortedMap().map { (mi, rs) ->
            val byGroup = rs.distinctBy { it.note.chordGroup }
            val w = byGroup.sumOf { it.note.durationSec }
            val a = if (w > 0) byGroup.sumOf { it.accuracy * it.note.durationSec } / w else 0.0
            MeasureResult(mi, measures.getOrNull(mi - 1)?.label ?: "$mi", a)
        }

        return PerformanceReport(
            lineName = line.name,
            overall = if (totalWeight > 0) weighted / totalWeight else 0.0,
            notes = results,
            measures = measureResults,
            tendencyCents = if (allSignedN >= 10) allSigned / allSignedN else null,
            voicedRatio = if (totalWindow > 0) totalVoiced.toDouble() / totalWindow else 0.0,
        )
    }

    private fun samplesBetween(samples: List<PitchSample>, from: Double, to: Double): List<PitchSample> {
        if (to <= from) return emptyList()
        var lo = 0; var hi = samples.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (samples[mid].timeSec < from) lo = mid + 1 else hi = mid }
        val out = ArrayList<PitchSample>()
        var i = lo
        while (i < samples.size && samples[i].timeSec < to) { out += samples[i]; i++ }
        return out
    }

    /** Kullanıcıya gösterilecek kısa yorum. */
    fun tendencyText(cents: Double?): String? = when {
        cents == null -> null
        cents <= -12 -> "Genel olarak yaklaşık ${(-cents).toInt()} cent pes söylüyorsun. Notalara biraz yukarıdan yaklaşmayı dene."
        cents >= 12 -> "Genel olarak yaklaşık ${cents.toInt()} cent tiz söylüyorsun. Sesi biraz gevşetmeyi dene."
        else -> "Perde eğilimin dengeli; ne pes ne tiz kalıyorsun."
    }
}
