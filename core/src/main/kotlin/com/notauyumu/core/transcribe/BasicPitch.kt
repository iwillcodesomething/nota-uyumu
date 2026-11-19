package com.notauyumu.core.transcribe

import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Spotify Basic Pitch (Apache-2.0, https://github.com/spotify/basic-pitch) çıkarım hattının
 * Kotlin aktarımı: `basic_pitch/inference.py` (pencereleme, birleştirme) ve
 * `basic_pitch/note_creation.py` (`output_to_notes_polyphonic`, `get_infered_onsets`,
 * `model_frames_to_time`). Sabitler ve eşikler özgün koddakilerle aynıdır.
 */
object BasicPitch {
    const val SAMPLE_RATE = 22050
    const val FFT_HOP = 256
    const val N_PITCH = 88
    const val MIDI_OFFSET = 21
    private const val MAX_FREQ_IDX = 87
    private const val ANNOTATIONS_FPS = SAMPLE_RATE / FFT_HOP // 86
    const val ANNOT_N_FRAMES = ANNOTATIONS_FPS * 2 // 172
    const val AUDIO_N_SAMPLES = SAMPLE_RATE * 2 - FFT_HOP // 43844
    const val OVERLAP_FRAMES = 30
    private const val OVERLAP_LEN = OVERLAP_FRAMES * FFT_HOP // 7680
    const val HOP_SIZE = AUDIO_N_SAMPLES - OVERLAP_LEN // 36164
    private const val MAGIC_ALIGNMENT_OFFSET = 0.0018

    const val ONSET_THRESHOLD = 0.5f
    const val FRAME_THRESHOLD = 0.3f
    /** 127.7 ms -> kare. */
    val MIN_NOTE_LEN = (127.7 / 1000.0 * (SAMPLE_RATE.toDouble() / FFT_HOP)).roundToInt() // 11
    private const val ENERGY_TOL = 11

    /** Tek bir pencere için model çıktısı: [frames x 88] satır-öncelikli diziler. */
    class WindowOutput(val note: FloatArray, val onset: FloatArray)

    /** Modeli çalıştıran soyutlama (ONNX Runtime ya da test sahtesi). */
    fun interface Model {
        /** [window] uzunluğu [AUDIO_N_SAMPLES]. */
        fun run(window: FloatArray): WindowOutput
    }

    /** Ses başına yarım örtüşme kadar sessizlik ekleyip pencerelere böler. */
    fun windows(audio: FloatArray): List<FloatArray> {
        val padded = FloatArray(OVERLAP_LEN / 2 + audio.size)
        System.arraycopy(audio, 0, padded, OVERLAP_LEN / 2, audio.size)
        val out = ArrayList<FloatArray>()
        var i = 0
        while (i < padded.size) {
            val w = FloatArray(AUDIO_N_SAMPLES)
            val n = minOf(AUDIO_N_SAMPLES, padded.size - i)
            System.arraycopy(padded, i, w, 0, n)
            out += w
            i += HOP_SIZE
        }
        return out
    }

    /** Matris: [frames][88]. */
    class Activations(val nFrames: Int, val note: Array<FloatArray>, val onset: Array<FloatArray>)

    /** Pencere çıktılarını birleştirir: her pencereden örtüşmenin yarısı baştan ve sondan atılır. */
    fun unwrap(outputs: List<WindowOutput>, originalLength: Int): Activations {
        val nOlap = OVERLAP_FRAMES / 2
        val keep = ANNOT_N_FRAMES - 2 * nOlap
        val total = outputs.size * keep
        val nExpectedWindows = originalLength.toDouble() / HOP_SIZE
        val framesPerWindow = ANNOT_N_FRAMES - OVERLAP_FRAMES
        val n = minOf(total, (nExpectedWindows * framesPerWindow).toInt())
        val note = Array(n) { FloatArray(N_PITCH) }
        val onset = Array(n) { FloatArray(N_PITCH) }
        var t = 0
        loop@ for (w in outputs) {
            for (f in nOlap until ANNOT_N_FRAMES - nOlap) {
                if (t >= n) break@loop
                System.arraycopy(w.note, f * N_PITCH, note[t], 0, N_PITCH)
                System.arraycopy(w.onset, f * N_PITCH, onset[t], 0, N_PITCH)
                t++
            }
        }
        return Activations(n, note, onset)
    }

    fun run(model: Model, audio22k: FloatArray, progress: (Float) -> Unit = {}): Activations {
        val ws = windows(audio22k)
        val outs = ArrayList<WindowOutput>(ws.size)
        ws.forEachIndexed { i, w -> outs += model.run(w); progress((i + 1).toFloat() / ws.size) }
        return unwrap(outs, audio22k.size)
    }

    fun frameToTime(frame: Int): Double {
        val original = frame.toDouble() * FFT_HOP / SAMPLE_RATE
        val windowOffset = (FFT_HOP.toDouble() / SAMPLE_RATE) * (ANNOT_N_FRAMES - AUDIO_N_SAMPLES.toDouble() / FFT_HOP) + MAGIC_ALIGNMENT_OFFSET
        return original - windowOffset * floor(frame.toDouble() / ANNOT_N_FRAMES)
    }

    data class NoteEvent(val startSec: Double, val endSec: Double, val midi: Int, val amplitude: Float)

    private fun inferOnsets(onsets: Array<FloatArray>, frames: Array<FloatArray>, nDiff: Int = 2): Array<FloatArray> {
        val n = frames.size
        val diff = Array(n) { FloatArray(N_PITCH) }
        var maxDiff = 0f
        var maxOnset = 0f
        for (t in 0 until n) for (p in 0 until N_PITCH) {
            var m = Float.MAX_VALUE
            for (k in 1..nDiff) {
                val prev = if (t - k >= 0) frames[t - k][p] else 0f
                m = minOf(m, frames[t][p] - prev)
            }
            if (m < 0f || t < nDiff) m = 0f
            diff[t][p] = m
            if (m > maxDiff) maxDiff = m
            if (onsets[t][p] > maxOnset) maxOnset = onsets[t][p]
        }
        val out = Array(n) { FloatArray(N_PITCH) }
        for (t in 0 until n) for (p in 0 until N_PITCH) {
            val d = if (maxDiff > 0f) maxOnset * diff[t][p] / maxDiff else 0f
            out[t][p] = maxOf(onsets[t][p], d)
        }
        return out
    }

    /**
     * `output_to_notes_polyphonic` aktarımı. [minMidi]/[maxMidi] ile perde aralığı sınırlanabilir
     * (insan sesi için gereksiz bas/tiz bölgeyi atmak).
     */
    fun notes(
        act: Activations,
        onsetThresh: Float = ONSET_THRESHOLD,
        frameThresh: Float = FRAME_THRESHOLD,
        minNoteLen: Int = MIN_NOTE_LEN,
        minMidi: Int? = null,
        maxMidi: Int? = null,
        melodiaTrick: Boolean = true,
    ): List<NoteEvent> {
        val n = act.nFrames
        if (n < 3) return emptyList()
        val frames = Array(n) { act.note[it].copyOf() }
        var onsets = Array(n) { act.onset[it].copyOf() }
        // constrain_frequency
        val lo = minMidi?.let { (it - MIDI_OFFSET).coerceIn(0, N_PITCH) } ?: 0
        val hi = maxMidi?.let { (it - MIDI_OFFSET).coerceIn(0, N_PITCH) } ?: N_PITCH
        for (t in 0 until n) for (p in 0 until N_PITCH) if (p < lo || p >= hi) { frames[t][p] = 0f; onsets[t][p] = 0f }
        onsets = inferOnsets(onsets, frames)

        // argrelmax (zaman ekseninde katı yerel tepe) >= eşik; zamanda geriye doğru
        val starts = ArrayList<IntArray>()
        for (t in 1 until n - 1) for (p in 0 until N_PITCH) {
            val v = onsets[t][p]
            if (v >= onsetThresh && v > onsets[t - 1][p] && v > onsets[t + 1][p]) starts += intArrayOf(t, p)
        }
        // np.where satır öncelikli sıralar (t, sonra p); [::-1] ile ters çevrilir
        starts.reverse()

        val energy = Array(n) { frames[it].copyOf() }
        val events = ArrayList<IntArray>() // [start, end, pitchIdx]
        val amps = ArrayList<Float>()

        for ((start, f) in starts) {
            if (start >= n - 1) continue
            var i = start + 1
            var k = 0
            while (i < n - 1 && k < ENERGY_TOL) {
                if (energy[i][f] < frameThresh) k++ else k = 0
                i++
            }
            i -= k
            if (i - start <= minNoteLen) continue
            for (t in start until i) {
                energy[t][f] = 0f
                if (f < MAX_FREQ_IDX) energy[t][f + 1] = 0f
                if (f > 0) energy[t][f - 1] = 0f
            }
            events += intArrayOf(start, i, f)
            amps += meanCol(frames, start, i, f)
        }

        if (melodiaTrick) {
            // Satır başına en büyük değer önbelleği: tüm matrisi her turda taramamak için
            val rowMax = FloatArray(n) { t -> energy[t].max() }
            fun refresh(from: Int, to: Int) { for (t in maxOf(0, from)..minOf(n - 1, to)) rowMax[t] = energy[t].max() }
            while (true) {
                var bt = -1; var best = 0f
                for (t in 0 until n) if (rowMax[t] > best) { best = rowMax[t]; bt = t }
                if (best <= frameThresh) break
                var bf = 0
                for (p in 0 until N_PITCH) if (energy[bt][p] > energy[bt][bf]) bf = p
                energy[bt][bf] = 0f
                // ileri
                var i = bt + 1
                var k = 0
                while (i < n - 1 && k < ENERGY_TOL) {
                    if (energy[i][bf] < frameThresh) k++ else k = 0
                    energy[i][bf] = 0f
                    if (bf < MAX_FREQ_IDX) energy[i][bf + 1] = 0f
                    if (bf > 0) energy[i][bf - 1] = 0f
                    i++
                }
                val iEnd = i - 1 - k
                val forwardLast = i
                // geri
                i = bt - 1
                k = 0
                while (i > 0 && k < ENERGY_TOL) {
                    if (energy[i][bf] < frameThresh) k++ else k = 0
                    energy[i][bf] = 0f
                    if (bf < MAX_FREQ_IDX) energy[i][bf + 1] = 0f
                    if (bf > 0) energy[i][bf - 1] = 0f
                    i--
                }
                val iStart = i + 1 + k
                refresh(i, forwardLast) // yalnızca bu turda değişen satırlar
                if (iEnd - iStart <= minNoteLen) continue
                events += intArrayOf(iStart, iEnd, bf)
                amps += meanCol(frames, iStart, iEnd, bf)
            }
        }

        return events.mapIndexed { idx, (s, e, f) ->
            NoteEvent(frameToTime(s), frameToTime(e), f + MIDI_OFFSET, amps[idx])
        }.sortedWith(compareBy({ it.startSec }, { -it.midi }))
    }

    private fun meanCol(m: Array<FloatArray>, from: Int, to: Int, col: Int): Float {
        if (to <= from) return 0f
        var s = 0.0
        for (t in from until to) s += m[t][col]
        return (s / (to - from)).toFloat()
    }

    private operator fun IntArray.component1() = this[0]
    private operator fun IntArray.component2() = this[1]
    private operator fun IntArray.component3() = this[2]
}
