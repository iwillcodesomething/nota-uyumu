package com.notauyumu.core

/**
 * Bir eserin oynatılabilir, zamanlanmış hâli.
 *
 * Bütün zamanlar saniye cinsindendir ve %100 tempoya göredir.
 * Tempo değişikliği oynatma anında [Score.scaled] ile uygulanır.
 */
data class Score(
    val title: String,
    val composer: String?,
    val lines: List<VoiceLine>,
    val measures: List<Measure>,
    /** 0 ise bilinmiyor (ses kaydından çıkarılmış eser). */
    val tempoBpm: Double,
    /** Notadan değil ses kaydından çıkarıldıysa tempo/ölçü yoktur; ölçüler yaklaşık "bölüm"lerdir. */
    val fromRecording: Boolean = false,
) {
    val durationSec: Double get() = measures.lastOrNull()?.endSec ?: 0.0

    /** Ölçü numarasına (1 tabanlı sıra) göre başlangıç saniyesi. */
    fun measureAt(timeSec: Double): Measure? =
        measures.lastOrNull { it.startSec <= timeSec + 1e-6 } ?: measures.firstOrNull()

    /** Tempo çarpanını (ör. 0.75 = %75) uygular. */
    fun scaled(factor: Double): Score {
        require(factor > 0)
        val k = 1.0 / factor
        return copy(
            lines = lines.map { l -> l.copy(notes = l.notes.map { it.copy(startSec = it.startSec * k, durationSec = it.durationSec * k) }) },
            measures = measures.map { it.copy(startSec = it.startSec * k, endSec = it.endSec * k, beats = it.beats.map { b -> b * k }) },
            tempoBpm = tempoBpm * factor,
        )
    }
}

/** Söylenebilecek tek bir ses hattı: bir partinin bir sesi (ör. "Alto" ya da "Kadınlar · alt ses"). */
data class VoiceLine(
    val id: String,
    val name: String,
    val partName: String,
    val notes: List<Note>,
) {
    val lowestMidi: Int get() = notes.minOfOrNull { it.midi } ?: 60
    val highestMidi: Int get() = notes.maxOfOrNull { it.midi } ?: 72
    val rangeLabel: String get() = "${Pitch.name(lowestMidi)} – ${Pitch.name(highestMidi)}"
}

data class Note(
    val midi: Int,
    val startSec: Double,
    val durationSec: Double,
    /** 1 tabanlı ölçü sırası (eserdeki görünen numara değil, sıra). */
    val measureIndex: Int,
    val lyric: String? = null,
    /** Aynı anda başlayan akor notaları aynı grupta toplanır. */
    val chordGroup: Int,
) {
    val endSec: Double get() = startSec + durationSec
}

data class Measure(
    /** 1 tabanlı sıra. */
    val index: Int,
    /** Notada yazan ölçü numarası (ör. anakruz için "0"). */
    val label: String,
    val startSec: Double,
    val endSec: Double,
    /** Ölçüdeki vuruşların başlangıç saniyeleri (metronom için). */
    val beats: List<Double>,
)
