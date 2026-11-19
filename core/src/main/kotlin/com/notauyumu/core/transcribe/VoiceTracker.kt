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
 * Ardından [refine] izleri zaman boyunca Viterbi ile iyileştirir.
 *
 * "Bisiklet" (SSATB) gerçek kaydında ses başına doğruluk: eski ayırıcı %42, kare izleyici %68,
 * iyileştirmeyle %83 (S 93, MS 86, A 79, T 72, B 82; nota zamanları kayda oturtulmuş kıyas notası).
 * Tenorun kalan hatası çoğunlukla eserin kendisinden: tenor melodisi karelerin üçte birinde basın
 * altına iner; perde bilgisi tek başına hangi sesin hangisi olduğunu ayırt edemez.
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

    fun split(act: BasicPitch.Activations, voices: Int, tuning: Tuning = Tuning(), time: (Int) -> Double = BasicPitch::frameToTime): List<List<NoteEvent>> {
        val init = track(act, voices, tuning)
        val out = if (voices >= 2) refine(act, init).takeIf { weakFraction(act, it) <= MAX_WEAK } ?: init else init
        return out.map { toNotes(it, time = time) }
    }

    /**
     * İyileştirme, seslerin uzun süre söylediği (ör. "Uu" dolguları) kayıtlarda büyük kazanç sağlar; kısa,
     * kesik notalı ve bol susmalı eserlerde ise sesleri kayıtta neredeyse duyulmayan perdelere
     * sürükleyebilir. Atanan perdelerin bu kadarından fazlası zayıf duyuluyorsa kare izleyici kullanılır.
     */
    private const val MAX_WEAK = 0.25

    /** Atanan (ses, kare) perdelerinden kayıtta zayıf (< 0.2) duyulanların oranı. */
    fun weakFraction(act: BasicPitch.Activations, tr: Array<IntArray>): Double {
        var n = 0; var weak = 0
        for (v in tr) for (t in v.indices step 2) {
            val p = v[t]
            if (p <= 0) continue
            n++
            if (act.note[t][p - BasicPitch.MIDI_OFFSET] < 0.2f) weak++
        }
        return if (n == 0) 0.0 else weak.toDouble() / n
    }

    // ------------------------------------------------------------------ zamansal iyileştirme

    /** [refine] ağırlıkları (Bisiklet SSATB kaydı ve sentezlenmiş 4 sesli koralde ayarlandı). */
    data class Refine(
        val eps: Double = 0.5,
        val silence: Double = 3.0,
        val share: Double = 0.5,
        /** Seslerin sık sık aynı perdede buluştuğu (unison) kayıtlarda kullanılan paylaşım cezası. */
        val shareUnison: Double = 0.25,
        /** share ile çözülen izde, güçlü seslerin olduğu karelerin bu kadarından fazlasında unison varsa shareUnison'a geçilir. */
        val unisonSwitch: Double = 0.2,
        val order: Double = 2.0,
        val jump: Double = 2.0,
        val step: Double = 0.4,
        val silenceSwitch: Double = 2.0,
        val range: Double = 0.15,
        val freeRange: Double = 4.0,
        val onset: Double = 8.0,
        val onsetWindow: Int = 3,
        val rounds: Int = 4,
    )

    /**
     * Kare kare atamayı zaman boyunca iyileştirir: her ses sırayla, diğerleri sabitken, bütün kayıt
     * üzerinde Viterbi ile yeniden izlenir (faktöriyel HMM'de koordinat iniş). Kare kare izleyici geleceği
     * göremez; bu adım bir sesin kısa bir süre zayıf duyulduğu yerlerde perdesini korumasını, bir notaya
     * ancak orada gerçekten yeni bir başlangıç (onset) varsa geçmesini sağlar. Unison oranı kayıttan
     * ölçülür: sesleri çok buluşan eserlerde (ör. Bisiklet'te alto ile tenor karelerin üçte birinde aynı
     * perdede) paylaşım cezası düşürülür.
     */
    fun refine(act: BasicPitch.Activations, init: Array<IntArray>, k: Refine = Refine()): Array<IntArray> {
        val first = refineWith(act, init, k, k.share)
        return if (unisonFraction(act, first) > k.unisonSwitch) refineWith(act, init, k, k.shareUnison) else first
    }

    /** Güçlü (≥0.5) bir sesin duyulduğu karelerde iki sesin aynı perdeyi söylediği karelerin oranı. */
    fun unisonFraction(act: BasicPitch.Activations, tr: Array<IntArray>): Double {
        var n = 0; var dup = 0
        for (t in 0 until act.nFrames step 3) {
            val f = act.note[t]
            var strong = false
            for (p in (MIN_MIDI - BasicPitch.MIDI_OFFSET)..(MAX_MIDI - BasicPitch.MIDI_OFFSET)) {
                if (p in 1 until BasicPitch.N_PITCH - 1 && f[p] >= 0.5f && f[p] >= f[p - 1] && f[p] >= f[p + 1]) { strong = true; break }
            }
            if (!strong) continue
            n++
            val on = tr.map { it[t] }.filter { it > 0 }
            if (on.size != on.toSet().size) dup++
        }
        return if (n == 0) 0.0 else dup.toDouble() / n
    }

    private fun refineWith(act: BasicPitch.Activations, init: Array<IntArray>, k: Refine, share: Double): Array<IntArray> {
        val n = act.nFrames
        val voices = init.size
        val np = MAX_MIDI - MIN_MIDI + 1
        val sil = np // sessiz durum
        val ns = np + 1
        val off = MIN_MIDI - BasicPitch.MIDI_OFFSET
        // Perde olasılığından gelen maliyet ve "buraya geçiş için başlangıç yok" cezası
        val emBase = Array(n) { t -> FloatArray(np) { i -> (-kotlin.math.ln(act.note[t][off + i] + k.eps)).toFloat() } }
        val onsetPen = Array(n) { FloatArray(np) }
        for (t in 0 until n) for (i in 0 until np) {
            var m = 0f
            for (u in maxOf(0, t - k.onsetWindow)..minOf(n - 1, t + k.onsetWindow)) m = maxOf(m, act.onset[u][off + i])
            onsetPen[t][i] = (k.onset * (1 - minOf(1f, m / 0.5f))).toFloat()
        }
        val tr = Array(voices) { init[it].copyOf() }
        val em = FloatArray(ns)
        val back = Array(n) { ByteArray(ns) }
        var d = DoubleArray(ns); var nd = DoubleArray(ns)
        repeat(k.rounds) {
            val centers = DoubleArray(voices) { v ->
                val xs = tr[v].filter { it > 0 }.sorted()
                if (xs.isEmpty()) 60.0 else xs[xs.size / 2].toDouble()
            }
            for (v in 0 until voices) {
                fun emission(t: Int) {
                    for (i in 0 until np) {
                        val p = MIN_MIDI + i
                        var c = emBase[t][i] + k.range * sq(maxOf(0.0, abs(p - centers[v]) - k.freeRange))
                        for (u in 0 until voices) {
                            if (u == v) continue
                            val q = tr[u][t]
                            if (q <= 0) continue
                            if (q == p) c += share
                            val w = if (abs(u - v) == 1) 1.0 else 0.5
                            if ((u < v && p > q) || (u > v && p < q)) c += k.order * w
                        }
                        em[i] = c.toFloat()
                    }
                    em[sil] = k.silence.toFloat()
                }
                emission(0)
                for (i in 0 until ns) d[i] = em[i].toDouble()
                for (t in 1 until n) {
                    emission(t)
                    // en iyi önceki perde (geçiş için)
                    var bestAll = Double.MAX_VALUE; var bestAllI = 0
                    for (i in 0 until np) if (d[i] < bestAll) { bestAll = d[i]; bestAllI = i }
                    for (i in 0 until np) {
                        // aynı perdede kal
                        var best = d[i]; var bi = i
                        // sessizlikten gir
                        val fromSil = d[sil] + k.silenceSwitch + onsetPen[t][i]
                        if (fromSil < best) { best = fromSil; bi = sil }
                        // başka perdeden geç (12 yarım sesten uzak geçişler sabit maliyetli)
                        val pen = k.jump + onsetPen[t][i]
                        val far = bestAll + pen + k.step * 12
                        if (far < best && bestAllI != i) { best = far; bi = bestAllI }
                        for (j in maxOf(0, i - 11)..minOf(np - 1, i + 11)) {
                            if (j == i) continue
                            val c = d[j] + pen + k.step * abs(i - j)
                            if (c < best) { best = c; bi = j }
                        }
                        nd[i] = best + em[i]; back[t][i] = bi.toByte()
                    }
                    // sessizliğe geç ya da sessiz kal
                    var best = d[sil]; var bi = sil
                    for (j in 0 until np) { val c = d[j] + k.silenceSwitch; if (c < best) { best = c; bi = j } }
                    nd[sil] = best + em[sil]; back[t][sil] = bi.toByte()
                    val x = d; d = nd; nd = x
                }
                var s = 0
                for (i in 1 until ns) if (d[i] < d[s]) s = i
                for (t in n - 1 downTo 0) {
                    tr[v][t] = if (s == sil) -1 else MIN_MIDI + s
                    if (t > 0) s = back[t][s].toInt()
                }
            }
        }
        return tr
    }

    private fun sq(x: Double) = x * x
}
