package com.notauyumu.core.transcribe

import com.notauyumu.core.transcribe.BasicPitch.NoteEvent
import kotlin.math.abs

/**
 * Tutti kaydında sesleri kare kare izler (Basic Pitch'in ham perde olasılıkları üzerinde).
 *
 * Neden olaylar (nota) üzerinde değil de karelerde? Gerçek koro kayıtlarında model her sesi
 * çoğunlukla doğru perdeden duyar, ama onu ayrı nota olaylarına bölerken bilgi kaybolur ve
 * aynı notayı söyleyen iki ses (ör. Soprano ile Mezzo aynı anda La) tek olaya iner. Burada
 * her karede o anda duyulan perdeler (adaylar) seslere dağıtılır:
 *  - ses sırası korunur (üst ses alttakinin altına inmez), ama iki ses aynı perdeyi paylaşabilir;
 *  - her ses önceki karedeki perdesine ve kendi aralığına yakın kalmak ister;
 *  - güçlü bir adayı kimseye vermemek ya da bir sesi susturmak maliyetlidir.
 * Ses merkezleri birkaç turda kaydın kendisinden öğrenilir.
 *
 * "Bisiklet" (SSATB) gerçek kaydında ses başına ortalama doğruluk %42'den %63'e çıktı (bas %52 → %74).
 */
object VoiceTracker {
    data class Tuning(
        val threshold: Float = 0.2f,
        val continuity: Double = 0.5,
        val range: Double = 0.4,
        val freeRange: Double = 5.0,
        val silence: Double = 3.0,
        val skip: Double = 8.0,
        val share: Double = 0.5,
        val iterations: Int = 3,
    )

    const val MIN_MIDI = 36 // Do2: bas sesler Re2'ye inebilir
    const val MAX_MIDI = 86

    private class Cand(val midi: Int, val amp: Float)

    private fun candidates(frame: FloatArray, thr: Float): List<Cand> {
        val out = ArrayList<Cand>()
        for (p in (MIN_MIDI - BasicPitch.MIDI_OFFSET)..(MAX_MIDI - BasicPitch.MIDI_OFFSET)) {
            if (p !in frame.indices) continue
            val v = frame[p]
            if (v >= thr && v >= (frame.getOrNull(p - 1) ?: 0f) && v >= (frame.getOrNull(p + 1) ?: 0f)) out += Cand(p + BasicPitch.MIDI_OFFSET, v)
        }
        out.sortByDescending { it.midi }
        return out
    }

    /**
     * Kayıtta aynı anda en çok kaç sesin duyulduğunu tahmin eder (2..6): ses olan
     * karelerdeki eşzamanlı perde sayısının %90'lık dilimi.
     */
    fun estimateVoices(act: BasicPitch.Activations, tuning: Tuning = Tuning()): Int {
        val counts = ArrayList<Int>()
        for (t in 0 until act.nFrames step 4) { val c = candidates(act.note[t], tuning.threshold + 0.05f).size; if (c > 0) counts += c }
        if (counts.isEmpty()) return 4
        counts.sort()
        return counts[(counts.size * 0.9).toInt().coerceAtMost(counts.size - 1)].coerceIn(2, 6)
    }

    /** Her ses için kare başına MIDI perdesi (-1 = sessiz). [voices] adet, 0 = en üst ses. */
    fun track(act: BasicPitch.Activations, voices: Int, k: Tuning = Tuning()): Array<IntArray> {
        require(voices in 1..6)
        val n = act.nFrames
        val cands = Array(n) { candidates(act.note[it], k.threshold) }
        val all = cands.flatMap { c -> c.map { it.midi } }.sorted()
        if (all.isEmpty()) return Array(voices) { IntArray(n) { -1 } }
        var centers = DoubleArray(voices) { v -> all[((voices - 1 - v + 0.5) / voices * (all.size - 1)).toInt()].toDouble() }
        var out = Array(voices) { IntArray(n) { -1 } }
        repeat(k.iterations) {
            out = Array(voices) { IntArray(n) { -1 } }
            val prev = centers.copyOf()
            val idle = IntArray(voices) { 1000 } // sesin kaç karedir sustuğu
            for (t in 0 until n) {
                assignFrame(cands[t], voices, centers, prev, idle, k, out, t)
                for (v in 0 until voices) idle[v] = if (out[v][t] > 0) 0 else idle[v] + 1
            }
            centers = DoubleArray(voices) { v ->
                val xs = out[v].filter { it > 0 }.sorted()
                if (xs.isEmpty()) centers[v] else xs[xs.size / 2].toDouble()
            }
        }
        return out
    }

    /**
     * Tek kare için en düşük maliyetli atama (dinamik programlama). Durum: bir önceki (daha üst)
     * sesin aldığı adayın sırası; sonraki ses aynı adayı (paylaşım) ya da daha alçak bir adayı alır.
     */
    private fun assignFrame(c: List<Cand>, voices: Int, centers: DoubleArray, prev: DoubleArray, idle: IntArray, k: Tuning, out: Array<IntArray>, t: Int) {
        val nc = c.size
        val cum = DoubleArray(nc + 1); for (i in 0 until nc) cum[i + 1] = cum[i] + c[i].amp
        val inf = Double.MAX_VALUE / 4
        var dp = DoubleArray(nc + 1) { inf }; dp[0] = 0.0 // indeks = son atanan aday + 1 (0: henüz yok)
        val back = Array(voices) { IntArray(nc + 1) }
        val choice = Array(voices) { IntArray(nc + 1) }
        for (v in 0 until voices) {
            val nd = DoubleArray(nc + 1) { inf }
            for (si in 0..nc) {
                val base = dp[si]
                if (base >= inf) continue
                val s = si - 1
                // sessiz kal
                val silent = base + k.silence
                if (silent < nd[si]) { nd[si] = silent; back[v][si] = si; choice[v][si] = -1 }
                for (j in maxOf(s, 0) until nc) {
                    val p = c[j].midi
                    // Uzun süre susmuş ses yeni bir girişte önceki perdesine bağlı kalmaz
                    val cont = if (idle[v] > 40) 0.2 * k.continuity else k.continuity
                    var cost = cont * minOf(abs(p - prev[v]), 12.0) + k.range * maxOf(0.0, abs(p - centers[v]) - k.freeRange)
                    cost += if (j == s) k.share else k.skip * (cum[j] - cum[s + 1])
                    val value = base + cost
                    if (value < nd[j + 1]) { nd[j + 1] = value; back[v][j + 1] = si; choice[v][j + 1] = j }
                }
            }
            dp = nd
        }
        var si = 0; var best = inf
        for (i in 0..nc) { val f = dp[i] + k.skip * (cum[nc] - cum[i]); if (f < best) { best = f; si = i } }
        for (v in voices - 1 downTo 0) {
            val j = choice[v][si]
            if (j >= 0) { out[v][t] = c[j].midi; prev[v] = c[j].midi.toDouble() }
            si = back[v][si]
        }
    }

    /** Kare izini notalara çevirir: 5 karelik çoğunluk süzgeci, en az [minSec] süren bölgeler. */
    fun toNotes(track: IntArray, minSec: Double = 0.1, time: (Int) -> Double = BasicPitch::frameToTime): List<NoteEvent> {
        val n = track.size
        val y = track.copyOf()
        for (t in 2 until n - 2) {
            val w = IntArray(5) { track[t - 2 + it] }
            y[t] = w.groupBy { it }.maxBy { it.value.size }.key
        }
        val out = ArrayList<NoteEvent>()
        var t = 0
        while (t < n) {
            if (y[t] <= 0) { t++; continue }
            val s = t
            while (t < n && y[t] == y[s]) t++
            val a = time(s); val b = time(t)
            if (b - a >= minSec) out += NoteEvent(a, b, y[s], 1f)
        }
        return out
    }

    fun split(act: BasicPitch.Activations, voices: Int, tuning: Tuning = Tuning(), time: (Int) -> Double = BasicPitch::frameToTime): List<List<NoteEvent>> =
        track(act, voices, tuning).map { toNotes(it, time = time) }
}
