package com.notauyumu.core.transcribe

import com.notauyumu.core.Measure
import com.notauyumu.core.Note
import com.notauyumu.core.Score
import com.notauyumu.core.VoiceLine
import com.notauyumu.core.transcribe.BasicPitch.NoteEvent
import kotlin.math.abs

/** Kayıttan çıkarılan notaları söylenebilir ses hatlarına ayırır. */
object VoiceSplitter {

    /** İnsan sesi aralığı (Mi2–Do6); bunun dışı enstrüman/harmonik gürültüsüdür. */
    const val VOICE_MIN_MIDI = 40
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

    /**
     * Tutti kaydı: notaları [voices] adet sese (0 = en üst) dağıtır.
     *
     * Her ses için bir "merkez perde" tahmin edilir ve birkaç turda iyileştirilir. Aynı anda
     * başlayan notalar, perde sırası korunarak (üst ses alt sesin altına inmez) en düşük
     * maliyetli biçimde seslere atanır. Maliyet: sesin merkezine ve önceki notasına uzaklık,
     * çalmakta olan notayı kesme, diğer seslerle çaprazlama. Bir notayı hiçbir sese vermemek
     * de bir seçenektir: modelin bulduğu harmonik yansımalar (ör. basın bir oktav üstü)
     * çoğu zaman bu yolla elenir.
     */
    /** Atama maliyeti ayarları. */
    data class Tuning(
        val freeRange: Double = 5.0,
        val centerW: Double = 0.5,
        val lastW: Double = 0.1,
        val busy: Double = 3.0,
        val crossW: Double = 2.0,
        val dropBase: Double = 3.5,
        val dropAmp: Double = 7.0,
    )

    fun satb(events: List<NoteEvent>, voices: Int, clusterSec: Double = 0.1, tuning: Tuning = Tuning()): List<List<NoteEvent>> {
        require(voices in 2..6)
        val sorted = events.filter { it.midi in VOICE_MIN_MIDI..VOICE_MAX_MIDI && it.endSec > it.startSec }.sortedBy { it.startSec }
        if (sorted.isEmpty()) return List(voices) { emptyList() }
        var centers = initialCenters(sorted, voices)
        var result: List<List<NoteEvent>> = emptyList()
        repeat(4) {
            result = assignAll(sorted, voices, centers, clusterSec, tuning)
            val next = DoubleArray(voices) { v -> weightedMedian(result[v]) ?: centers[v] }
            // Sıra bozulmasın
            for (v in 1 until voices) if (next[v] > next[v - 1] - 1) next[v] = next[v - 1] - 1
            centers = next
        }
        return result.map { v -> v.filter { n -> n.endSec - n.startSec >= 0.08 } }
    }

    private fun initialCenters(notes: List<NoteEvent>, voices: Int): DoubleArray {
        val weighted = notes.sortedBy { it.midi }
        val total = weighted.sumOf { it.endSec - it.startSec }
        return DoubleArray(voices) { v ->
            val q = (voices - 1 - v + 0.5) / voices
            var acc = 0.0
            weighted.firstOrNull { acc += it.endSec - it.startSec; acc >= q * total }?.midi?.toDouble() ?: 60.0
        }
    }

    private fun weightedMedian(notes: List<NoteEvent>): Double? {
        if (notes.isEmpty()) return null
        val sorted = notes.sortedBy { it.midi }
        val total = sorted.sumOf { it.endSec - it.startSec }
        var acc = 0.0
        return sorted.first { acc += it.endSec - it.startSec; acc >= total / 2 }.midi.toDouble()
    }

    private fun assignAll(sorted: List<NoteEvent>, voices: Int, centers: DoubleArray, clusterSec: Double, k: Tuning): List<List<NoteEvent>> {
        val out = List(voices) { ArrayList<NoteEvent>() }
        val last = centers.copyOf()
        var i = 0
        while (i < sorted.size) {
            val t0 = sorted[i].startSec
            var j = i
            while (j < sorted.size && sorted[j].startSec - t0 <= clusterSec) j++
            val cluster = sorted.subList(i, j).groupBy { it.midi }.values.map { g -> g.maxBy { it.amplitude } }.sortedByDescending { it.midi }
            i = j
            // Şu an çalmakta olan (bu kümeden önce başlamış) notalar
            val sounding = arrayOfNulls<NoteEvent>(voices)
            for (v in 0 until voices) out[v].lastOrNull()?.let { if (it.endSec > t0 + 0.1) sounding[v] = it }

            fun cost(n: NoteEvent, v: Int): Double {
                var c = k.centerW * maxOf(0.0, abs(n.midi - centers[v]) - k.freeRange) + k.lastW * abs(n.midi - last[v])
                sounding[v]?.let { c += k.busy + 4.0 * minOf(1.0, (it.endSec - n.startSec) / 0.5) }
                for (u in 0 until voices) {
                    if (u == v) continue
                    val o = sounding[u] ?: continue
                    if (u < v && o.midi < n.midi) c += k.crossW * (n.midi - o.midi)
                    if (u > v && o.midi > n.midi) c += k.crossW * (o.midi - n.midi)
                }
                return c
            }
            fun dropCost(n: NoteEvent) = k.dropBase + k.dropAmp * n.amplitude

            // dp[a][b]: ilk a notayı ilk b sese (sıralı) yerleştirme ya da atma
            val m = cluster.size
            val inf = Double.MAX_VALUE / 4
            val dp = Array(m + 1) { DoubleArray(voices + 1) { inf } }
            val choice = Array(m + 1) { IntArray(voices + 1) } // 0: sesi atla, 1: notayı ata, 2: notayı at
            dp[0][0] = 0.0
            for (a in 0..m) for (b in 0..voices) {
                if (a == 0 && b == 0) continue
                var best = inf; var ch = -1
                if (b > 0 && dp[a][b - 1] < best) { best = dp[a][b - 1]; ch = 0 }
                if (a > 0 && b > 0) { val c = dp[a - 1][b - 1] + cost(cluster[a - 1], b - 1); if (c < best) { best = c; ch = 1 } }
                if (a > 0) { val c = dp[a - 1][b] + dropCost(cluster[a - 1]); if (c < best) { best = c; ch = 2 } }
                dp[a][b] = best; choice[a][b] = ch
            }
            var a = m; var b = voices
            val picks = ArrayList<Pair<NoteEvent, Int>>()
            while (a > 0 || b > 0) {
                when (choice[a][b]) {
                    0 -> b--
                    1 -> { picks += cluster[a - 1] to (b - 1); a--; b-- }
                    else -> a--
                }
            }
            for ((n, v) in picks) {
                trimLast(out[v], n.startSec)
                out[v] += n
                last[v] = n.midi.toDouble()
            }
        }
        return out
    }

    private fun trimLast(list: MutableList<NoteEvent>, at: Double) {
        val l = list.lastOrNull() ?: return
        if (l.endSec > at) list[list.size - 1] = l.copy(endSec = maxOf(l.startSec, at))
    }

    fun voiceNames(count: Int): List<String> = when (count) {
        1 -> listOf("Kayıttaki parti")
        2 -> listOf("Üst ses", "Alt ses")
        3 -> listOf("1. ses (üst)", "2. ses", "3. ses (alt)")
        4 -> listOf("Soprano", "Alto", "Tenor", "Bas")
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
