package com.notauyumu.core.transcribe

import com.notauyumu.core.Measure
import com.notauyumu.core.Note
import com.notauyumu.core.Score
import com.notauyumu.core.VoiceLine
import com.notauyumu.core.transcribe.BasicPitch.NoteEvent
import kotlin.math.abs

/** Kayıttan çıkarılan notaları söylenebilir ses hatlarına ayırır. */
object VoiceSplitter {

    /** İnsan sesi aralığı (Do2–Do6); bunun dışı enstrüman/harmonik gürültüsüdür. */
    const val VOICE_MIN_MIDI = 36
    const val VOICE_MAX_MIDI = 84

    /**
     * Tek parti kaydı: her an yalnızca bir nota kalır. Örtüşmelerde güçlü olan kazanır;
     * güçlü notanın oktavı/beşlisi gibi zayıf harmonik yansımalar atılır.
     */
    fun monophonic(events: List<NoteEvent>, minDurSec: Double = 0.09): List<NoteEvent> {
        val sorted = events.sortedByDescending { it.amplitude * (it.endSec - it.startSec) }
        val kept = ArrayList<NoteEvent>()
        for (e in sorted) {
            var s = e.startSec
            var en = e.endSec
            var drop = false
            for (k in kept) {
                if (k.endSec <= s || k.startSec >= en) continue
                // tamamen içinde ise at; kısmi örtüşmede kırp
                when {
                    k.startSec <= s && k.endSec >= en -> { drop = true }
                    k.startSec <= s -> s = k.endSec
                    k.endSec >= en -> en = k.startSec
                    else -> { // ortada kalan güçlü nota: daha uzun kalan tarafı tut
                        if (k.startSec - s >= en - k.endSec) en = k.startSec else s = k.endSec
                    }
                }
                if (drop || en - s < minDurSec) { drop = true; break }
            }
            if (!drop && en - s >= minDurSec) kept += e.copy(startSec = s, endSec = en)
        }
        return mergeRepeats(kept.sortedBy { it.startSec })
    }

    /** Aynı perdeden, aralarında çok kısa boşluk olan parçaları birleştirir (vibrato kırılmaları). */
    private fun mergeRepeats(notes: List<NoteEvent>, gap: Double = 0.06): List<NoteEvent> {
        val out = ArrayList<NoteEvent>()
        for (n in notes) {
            val last = out.lastOrNull()
            if (last != null && last.midi == n.midi && n.startSec - last.endSec <= gap) {
                out[out.size - 1] = last.copy(endSec = maxOf(last.endSec, n.endSec))
            } else out += n
        }
        return out
    }

    fun voiceNames(count: Int): List<String> = when (count) {
        1 -> listOf("Kayıttaki parti")
        2 -> listOf("Üst ses", "Alt ses")
        3 -> listOf("1. ses (üst)", "2. ses", "3. ses (alt)")
        4 -> listOf("Soprano", "Alto", "Tenor", "Bas")
        5 -> listOf("Soprano", "Mezzosoprano", "Alto", "Tenor", "Bas")
        else -> (1..count).map { "$it. ses" }
    }

    /**
     * Kayıttan eser oluşturur. Ölçü bilgisi olmadığı için eser yaklaşık 4 saniyelik
     * "bölüm"lere ayrılır; bölüm sınırları mümkünse nota başlangıçlarına oturtulur.
     */
    fun toScore(title: String, lines: List<List<NoteEvent>>, names: List<String>, durationSec: Double): Score {
        var group = 0
        val voiceLines = lines.mapIndexedNotNull { idx, evs ->
            if (evs.isEmpty()) return@mapIndexedNotNull null
            VoiceLine(
                id = "rec-$idx",
                name = names.getOrElse(idx) { "${idx + 1}. ses" },
                partName = names.getOrElse(idx) { "Kayıt" },
                notes = evs.map { Note(it.midi, it.startSec, it.endSec - it.startSec, 1, null, ++group) },
            )
        }
        val end = maxOf(durationSec, voiceLines.maxOfOrNull { l -> l.notes.maxOf { it.endSec } } ?: 0.0)
        val onsets = voiceLines.flatMap { l -> l.notes.map { it.startSec } }.sorted()
        val bounds = ArrayList<Double>().apply { add(0.0) }
        var t = 4.0
        while (t < end - 1.5) {
            // ±1 sn içindeki en yakın nota başlangıcına yasla
            val snap = onsets.filter { abs(it - t) <= 1.0 && it > bounds.last() + 2.0 }.minByOrNull { abs(it - t) } ?: t
            bounds += snap
            t = snap + 4.0
        }
        bounds += end
        val measures = (0 until bounds.size - 1).map { k -> Measure(k + 1, "${k + 1}", bounds[k], bounds[k + 1], emptyList()) }
        fun measureOf(s: Double) = measures.indexOfLast { it.startSec <= s + 1e-6 }.coerceAtLeast(0) + 1
        val fixed = voiceLines.map { l -> l.copy(notes = l.notes.map { it.copy(measureIndex = measureOf(it.startSec)) }) }
        return Score(title, "Ses kaydından", fixed, measures, 0.0, fromRecording = true)
    }
}
