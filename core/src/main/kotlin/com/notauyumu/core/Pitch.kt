package com.notauyumu.core

import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

object Pitch {
    private val NAMES = arrayOf("Do", "Do♯", "Re", "Mi♭", "Mi", "Fa", "Fa♯", "Sol", "Sol♯", "La", "Si♭", "Si")
    private val LETTERS = arrayOf("C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "G♯", "A", "B♭", "B")

    fun freqToMidi(freq: Double): Double = 69.0 + 12.0 * ln(freq / 440.0) / ln(2.0)

    fun midiToFreq(midi: Double): Double = 440.0 * 2.0.pow((midi - 69.0) / 12.0)

    /** Türkçe solfej adı + oktav, ör. 60 -> "Do4". */
    fun name(midi: Int): String = NAMES[Math.floorMod(midi, 12)] + (Math.floorDiv(midi, 12) - 1)

    fun solfege(midi: Int): String = NAMES[Math.floorMod(midi, 12)]

    fun letter(midi: Int): String = LETTERS[Math.floorMod(midi, 12)] + (Math.floorDiv(midi, 12) - 1)

    fun nearestName(midiFloat: Double): String = name(midiFloat.roundToInt())

    /** MusicXML adımı (C..B) + değiştirici + oktav -> MIDI. */
    fun fromStep(step: String, alter: Int, octave: Int): Int {
        val base = when (step.trim().uppercase()) {
            "C" -> 0; "D" -> 2; "E" -> 4; "F" -> 5; "G" -> 7; "A" -> 9; "B" -> 11
            else -> throw IllegalArgumentException("Geçersiz nota adı: $step")
        }
        return (octave + 1) * 12 + base + alter
    }

    /**
     * İki perde arasındaki cent farkı. [forgiveOctave] açıksa oktav (±1200 c)
     * katlanır; ses algılamadaki oktav hatası ve farklı oktavda söyleme cezalandırılmaz.
     */
    fun centsOff(sungMidi: Double, targetMidi: Int, forgiveOctave: Boolean): Double {
        var c = (sungMidi - targetMidi) * 100.0
        if (forgiveOctave) {
            c = ((c % 1200.0) + 1200.0) % 1200.0
            if (c > 600.0) c -= 1200.0
        }
        return c
    }
}
