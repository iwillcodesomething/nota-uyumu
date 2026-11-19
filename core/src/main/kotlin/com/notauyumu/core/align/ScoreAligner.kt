package com.notauyumu.core.align

import com.notauyumu.core.Measure
import com.notauyumu.core.Note
import com.notauyumu.core.Score
import com.notauyumu.core.transcribe.BasicPitch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Nota ile referans kaydı eşleştirir.
 *
 * Kayıt, notanın herhangi bir yerinden başlayıp herhangi bir yerinde bitebilir (ör. girişi atlayıp
 * doğrudan ana melodiden başlayan kayıt). Eşleştirme kroma (12 ses sınıfı) özellikleri üzerinde,
 * kaydın tamamını notanın bir alt dizisine oturtan DTW ile yapılır; tempo farkı ±2 kata kadar ve
 * yer yer değişebilir.
 *
 * Sonuçta:
 *  - nota kaydın zamanına taşınır ([retime]) — böylece kayıt eşlik olarak senkron çalınır,
 *  - her partinin kayıtla uyumu ölçülür ([agreement]),
 *  - istenirse kaydın açıkça farklı duyduğu notalar (oktav / yarım ses) düzeltilir ([correct]).
 */
object ScoreAligner {
    /** Özellik adımı (sn). */
    const val STEP = 0.1

    /** Eşleşme: [scoreSec] ↔ [recSec] çiftleri, ikisi de artan. */
    class Alignment(val scoreSec: DoubleArray, val recSec: DoubleArray, val cost: Double) {
        val scoreStart get() = scoreSec.first()
        val scoreEnd get() = scoreSec.last()
        val recStart get() = recSec.first()
        val recEnd get() = recSec.last()

        /** Nota zamanının kayıttaki karşılığı (doğrusal ara değer; uçlarda dış değer). */
        fun recTimeOf(t: Double): Double {
            val n = scoreSec.size
            if (n == 1) return recSec[0] + (t - scoreSec[0])
            var i = scoreSec.binarySearch(t).let { if (it >= 0) it else -it - 2 }.coerceIn(0, n - 2)
            while (i < n - 2 && scoreSec[i + 1] <= scoreSec[i]) i++
            val s0 = scoreSec[i]; val s1 = scoreSec[i + 1]
            if (s1 - s0 < 1e-9) return recSec[i]
            return recSec[i] + (t - s0) * (recSec[i + 1] - recSec[i]) / (s1 - s0)
        }
    }

    // ---------- Özellikler ----------

    /** Notanın kroma dizisi: her [STEP] dilimde çalan seslerin süre ağırlıklı ses sınıfı enerjisi. */
    fun scoreChroma(score: Score): Array<DoubleArray> {
        val end = score.lines.maxOfOrNull { l -> l.notes.maxOfOrNull { it.startSec + it.durationSec } ?: 0.0 } ?: 0.0
        val n = max(1, (end / STEP).roundToInt() + 1)
        val c = Array(n) { DoubleArray(12) }
        for (l in score.lines) for (note in l.notes) {
            val a = note.startSec; val b = note.startSec + note.durationSec
            var k = (a / STEP).toInt()
            while (k < n && k * STEP < b) {
                val ov = min(b, (k + 1) * STEP) - max(a, k * STEP)
                if (ov > 0) c[k][Math.floorMod(note.midi, 12)] += ov / STEP
                k++
            }
        }
        return c.map(::normalize).toTypedArray()
    }

    /** Kaydın kroma dizisi: Basic Pitch nota olasılıkları [STEP] dilimlere toplanır. */
    fun recordingChroma(act: BasicPitch.Activations, time: (Int) -> Double = BasicPitch::frameToTime): Array<DoubleArray> {
        if (act.nFrames == 0) return emptyArray()
        val end = time(act.nFrames - 1)
        val n = (end / STEP).toInt() + 1
        val c = Array(n) { DoubleArray(12) }
        val lo = 36 - BasicPitch.MIDI_OFFSET; val hi = 88 - BasicPitch.MIDI_OFFSET
        for (f in 0 until act.nFrames) {
            val k = (time(f) / STEP).toInt().coerceIn(0, n - 1)
            val row = act.note[f]
            for (p in lo until hi) {
                val v = row[p]
                if (v > 0.15f) c[k][(p + BasicPitch.MIDI_OFFSET) % 12] += (v - 0.15f).toDouble()
            }
        }
        return c
    }

    private fun normalize(v: DoubleArray): DoubleArray {
        val s = sqrt(v.sumOf { it * it })
        return if (s < 1e-9) v else DoubleArray(12) { v[it] / s }
    }

    private fun energy(v: DoubleArray) = v.sum()

    // ---------- Eşleştirme ----------

    /**
     * Kaydı notaya oturtur. Kaydın başındaki/sonundaki sessiz kısım (enerjisi düşük dilimler) atlanır;
     * kalanın tamamı notanın bitişik bir bölümüne eşlenir. Eşleşme yoksa null.
     */
    fun align(score: Score, act: BasicPitch.Activations, time: (Int) -> Double = BasicPitch::frameToTime): Alignment? =
        align(scoreChroma(score), recordingChroma(act, time))

    /** Eşleşme ve kaydın notaya göre aktarımı (yarım ses; kayıt notadan [semitones] yüksek). */
    class Match(val alignment: Alignment, val semitones: Int)

    /**
     * Kayıt notadan farklı tonda söylenmiş olabilir (koroların sık yaptığı gibi): 12 aktarım kaba
     * çözünürlükte denenir, en iyisi tam çözünürlükte eşlenir.
     */
    fun alignAnyKey(score: Score, act: BasicPitch.Activations, time: (Int) -> Double = BasicPitch::frameToTime): Match? {
        val sc = scoreChroma(score)
        val rec = recordingChroma(act, time)
        fun pool(a: Array<DoubleArray>, k: Int) = Array((a.size + k - 1) / k) { i ->
            DoubleArray(12) { c -> (i * k until minOf(a.size, i * k + k)).sumOf { a[it][c] } }
        }
        val scC = pool(sc, 4).map(::normalize).toTypedArray()
        val recC = pool(rec, 4)
        var bestK = 0; var bestCost = Double.MAX_VALUE
        for (k in -6..5) {
            val c = align(scC, rotate(recC, -k))?.cost ?: continue
            if (c < bestCost) { bestCost = c; bestK = k }
        }
        val al = align(sc, rotate(rec, -bestK)) ?: return null
        return Match(al, bestK)
    }

    /** Kroma vektörlerini [k] yarım ses kaydırır (ses sınıfı c → c+k). */
    private fun rotate(a: Array<DoubleArray>, k: Int) = Array(a.size) { i -> DoubleArray(12) { c -> a[i][Math.floorMod(c - k, 12)] } }

    /** Notayı [semitones] yarım ses aktarır. */
    fun transpose(score: Score, semitones: Int): Score =
        if (semitones == 0) score else score.copy(lines = score.lines.map { l -> l.copy(notes = l.notes.map { it.copy(midi = it.midi + semitones) }) })

    fun align(sc: Array<DoubleArray>, rawRec: Array<DoubleArray>): Alignment? {
        if (sc.isEmpty() || rawRec.isEmpty()) return null
        val en = rawRec.map(::energy)
        val loud = en.sorted()[(en.size * 0.9).toInt().coerceAtMost(en.size - 1)]
        val gate = loud * 0.08
        val first = en.indexOfFirst { it > gate }
        val last = en.indexOfLast { it > gate }
        if (first < 0 || last - first < 20) return null
        val rec = Array(last - first + 1) { normalize(rawRec[first + it]) }
        val silentRec = BooleanArray(rec.size) { en[first + it] <= gate }
        val silentScore = BooleanArray(sc.size) { energy(sc[it]) < 1e-9 }

        val n = rec.size; val m = sc.size
        fun cost(i: Int, j: Int): Float {
            if (silentRec[i] || silentScore[j]) return if (silentRec[i] && silentScore[j]) 0f else 0.6f
            var d = 0.0
            for (k in 0 until 12) d += rec[i][k] * sc[j][k]
            return (1.0 - d).toFloat()
        }
        // D[i][j]: kaydın 0..i'si notanın j'de biten bir bölümüne eşlendiğinde en düşük maliyet.
        // Adımlar (kayıt, nota): (1,1), (1,2), (2,1) → yerel tempo oranı 1/2..2.
        val inf = Float.MAX_VALUE / 4
        var prev2 = FloatArray(m) { inf }
        var prev = FloatArray(m) { cost(0, it) } // serbest başlangıç
        val back = Array(n) { ByteArray(m) }
        for (i in 1 until n) {
            val cur = FloatArray(m) { inf }
            for (j in 0 until m) {
                // Her kayıt dilimi tam bir kez ücretlendirilir → toplam/n = ortalama uyumsuzluk
                val c = cost(i, j)
                var best = inf; var dir: Byte = 0
                if (j >= 1 && prev[j - 1] + c < best) { best = prev[j - 1] + c; dir = 1 }
                if (j >= 2) { val v = prev[j - 2] + (c + cost(i, j - 1)) / 2; if (v < best) { best = v; dir = 2 } }
                if (i >= 2 && j >= 1) { val v = prev2[j - 1] + c + cost(i - 1, j); if (v < best) { best = v; dir = 3 } }
                cur[j] = best; back[i][j] = dir
            }
            prev2 = prev; prev = cur
        }
        // Serbest bitiş: son satırda en düşük maliyet
        var jEnd = 0
        for (j in 1 until m) if (prev[j] < prev[jEnd]) jEnd = j
        if (prev[jEnd] >= inf) return null
        val total = prev[jEnd].toDouble()
        val ps = ArrayList<Int>(); val pr = ArrayList<Int>()
        var i = n - 1; var j = jEnd
        while (true) {
            ps += j; pr += i
            if (i == 0) break
            when (back[i][j].toInt()) {
                1 -> { i -= 1; j -= 1 }
                2 -> { i -= 1; j -= 2 }
                3 -> { pr += i - 1; ps += j; i -= 2; j -= 1 }
                else -> break
            }
        }
        ps.reverse(); pr.reverse()
        // Aynı nota diliminin birden çok kayıt dilimine eşlendiği yerlerde ortalama al → kesin artan dizi
        val sOut = ArrayList<Double>(); val rOut = ArrayList<Double>()
        var k = 0
        while (k < ps.size) {
            var e = k; var sum = 0.0
            while (e < ps.size && ps[e] == ps[k]) { sum += pr[e]; e++ }
            sOut += ps[k] * STEP
            rOut += (first + sum / (e - k)) * STEP + STEP / 2
            k = e
        }
        smooth(rOut)
        return Alignment(sOut.toDoubleArray(), rOut.toDoubleArray(), total / n)
    }

    /** DTW yolundaki basamakları yumuşatır (5 noktalı kayan ortalama, artanlık korunur). */
    private fun smooth(r: MutableList<Double>) {
        if (r.size < 5) return
        val src = r.toList()
        for (i in 2 until src.size - 2) r[i] = (src[i - 2] + src[i - 1] + src[i] + src[i + 1] + src[i + 2]) / 5
        for (i in 1 until r.size) if (r[i] < r[i - 1]) r[i] = r[i - 1]
    }

    // ---------- Notayı kayda taşıma ----------

    /**
     * Notayı kaydın zamanına taşır. Yalnızca kayıtta karşılığı olan ölçüler kalır; ölçü sınırları
     * eşleşmeden okunur, ölçü içindeki notalar doğrusal yerleştirilir (ritim notadan gelir).
     */
    fun retime(score: Score, al: Alignment): Score {
        val margin = 0.25
        val keep = score.measures.filter { it.endSec > al.scoreStart + margin && it.startSec < al.scoreEnd - margin }
        if (keep.isEmpty()) return score.copy(lines = score.lines.map { it.copy(notes = emptyList()) }, measures = emptyList(), fromRecording = true)
        val bounds = DoubleArray(keep.size + 1) { i -> al.recTimeOf(if (i < keep.size) keep[i].startSec else keep.last().endSec) }
        for (i in 1 until bounds.size) if (bounds[i] < bounds[i - 1] + 0.05) bounds[i] = bounds[i - 1] + 0.05
        val first = keep.first().index
        fun map(t: Double): Double {
            val mi = keep.indexOfLast { it.startSec <= t + 1e-9 }.coerceAtLeast(0)
            val m = keep[mi]
            val f = if (m.endSec > m.startSec) (t - m.startSec) / (m.endSec - m.startSec) else 0.0
            return bounds[mi] + f * (bounds[mi + 1] - bounds[mi])
        }
        val measures = keep.mapIndexed { i, m ->
            Measure(i + 1, m.label, bounds[i], bounds[i + 1], m.beats.map { map(it) })
        }
        val t0 = keep.first().startSec; val t1 = keep.last().endSec
        val lines = score.lines.map { l ->
            l.copy(notes = l.notes.filter { it.startSec >= t0 - 1e-6 && it.startSec < t1 - 1e-6 }.map { n ->
                val s = map(n.startSec); val e = map(min(n.startSec + n.durationSec, t1))
                n.copy(startSec = s, durationSec = max(0.05, e - s), measureIndex = n.measureIndex - first + 1)
            })
        }
        return score.copy(lines = lines, measures = measures, fromRecording = true)
    }

    // ---------- Uyum ve düzeltme ----------

    /** Bir notanın kayıttaki ortalama olasılığı (aralığın ortası; geçişlerin bulanıklığı dışarıda). */
    private fun support(act: BasicPitch.Activations, frameOf: (Double) -> Int, n: Note, midi: Int): Double {
        val p = midi - BasicPitch.MIDI_OFFSET
        if (p !in 0 until BasicPitch.N_PITCH) return 0.0
        val a = frameOf(n.startSec + n.durationSec * 0.2)
        val b = frameOf(n.startSec + n.durationSec * 0.8)
        if (b < a) return 0.0
        var s = 0.0; var c = 0
        for (f in a..b) if (f in 0 until act.nFrames) { s += act.note[f][p]; c++ }
        return if (c == 0) 0.0 else s / c
    }

    private fun frameIndex(act: BasicPitch.Activations, time: (Int) -> Double): (Double) -> Int {
        val fps = (act.nFrames - 1).coerceAtLeast(1) / time(act.nFrames - 1).coerceAtLeast(1e-6)
        return { t -> (t * fps).roundToInt() }
    }

    /** Partiler için kayıtla uyum (0..1): notanın kayıtta duyulan payı (süre ağırlıklı). */
    fun agreement(retimed: Score, act: BasicPitch.Activations, time: (Int) -> Double = BasicPitch::frameToTime): Map<String, Double> {
        val fi = frameIndex(act, time)
        return retimed.lines.associate { l ->
            var ok = 0.0; var tot = 0.0
            for (n in l.notes) {
                tot += n.durationSec
                if (support(act, fi, n, n.midi) >= 0.3) ok += n.durationSec
            }
            l.id to if (tot > 0) ok / tot else 0.0
        }
    }

    class Correction(val lineId: String, val noteIndex: Int, val from: Int, val to: Int)

    /**
     * Kaydın açıkça başka bir ses duyduğu notaları düzeltir (temkinli):
     *  1) Bütün parti: notaların çoğu bir oktav aşağıda/yukarıda duyuluyorsa (ör. okunamayan 8vb anahtarı)
     *     parti kaydırılır.
     *  2) [singleNotes] açıksa tek nota: yazılan ses kayıtta neredeyse yoksa, ±1 yarım ses ya da ±12 güçlü
     *     duyuluyorsa ve o sesi o anda başka bir parti söylemiyorsa düzeltilir. Bisiklet denemesinde kazancı
     *     ölçülemeyecek kadar küçük, yanlış düzeltme riski ise gerçek olduğu için varsayılan kapalı.
     */
    fun correct(
        retimed: Score, act: BasicPitch.Activations, time: (Int) -> Double = BasicPitch::frameToTime, singleNotes: Boolean = false,
    ): Pair<Score, List<Correction>> {
        val fi = frameIndex(act, time)
        val fixes = ArrayList<Correction>()
        // 1) Parti oktavı
        var lines = retimed.lines.map { l ->
            if (l.notes.size < 8) return@map l
            val votes = intArrayOf(-12, 0, 12).associateWith { d -> l.notes.count { support(act, fi, it, it.midi + d) >= 0.3 } }
            val best = votes.maxBy { it.value }
            if (best.key != 0 && best.value >= 0.4 * l.notes.size && best.value >= 2 * (votes[0] ?: 0)) {
                l.notes.forEachIndexed { i, n -> fixes += Correction(l.id, i, n.midi, n.midi + best.key) }
                l.copy(notes = l.notes.map { it.copy(midi = it.midi + best.key) })
            } else l
        }
        // 2) Tek notalar
        if (singleNotes) lines = lines.map { l ->
            val others = lines.filter { it.id != l.id }
            l.copy(notes = l.notes.mapIndexed { i, n ->
                val own = support(act, fi, n, n.midi)
                if (own >= 0.15 || n.durationSec < 0.2) return@mapIndexed n
                val cand = intArrayOf(-1, 1, -12, 12).map { d -> d to support(act, fi, n, n.midi + d) }.maxBy { it.second }
                if (cand.second < 0.5 || cand.second < 4 * own) return@mapIndexed n
                val target = n.midi + cand.first
                val taken = others.any { o -> o.notes.any { it.midi == target && it.startSec < n.startSec + n.durationSec && it.startSec + it.durationSec > n.startSec } }
                if (taken) n else { fixes += Correction(l.id, i, n.midi, target); n.copy(midi = target) }
            })
        }
        return retimed.copy(lines = lines) to fixes
    }

    /** Ölçü aralığı etiketi, ör. "ölçü 8–77". */
    fun rangeLabel(retimed: Score): String =
        if (retimed.measures.isEmpty()) "" else "ölçü ${retimed.measures.first().label}–${retimed.measures.last().label}"
}
