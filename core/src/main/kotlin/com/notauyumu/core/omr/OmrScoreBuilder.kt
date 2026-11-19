package com.notauyumu.core.omr

import com.notauyumu.core.Measure
import com.notauyumu.core.Note
import com.notauyumu.core.Pitch
import com.notauyumu.core.Score
import com.notauyumu.core.VoiceLine

/**
 * homr sembollerinden eser oluşturur. Sistemler alt alta dizilir; bir sistemdeki
 * j. porte j. partiye aittir. Ölçüler barlardan çıkarılır ve aynı sistemdeki
 * porteler ölçü ölçü hizalanır.
 */
object OmrScoreBuilder {
    const val DEFAULT_BPM = 90.0
    const val SOURCE_LABEL = "PDF / fotoğraftan okundu"

    private class Ev(val startQ: Double, val durQ: Double, val midi: Int)
    private class StaffMeasure(val events: MutableList<Ev> = ArrayList(), var lengthQ: Double = 0.0, var empty: Int = 0)
    private class ParsedStaff(val measures: List<StaffMeasure>, val compound: Boolean)

    private val durRe = Regex("^(\\d+)(G?)(\\.{0,2})$")

    /** "4." -> 1.5 çeyrek; "0" -> brevis; "G" (süs notası) -> null. */
    fun durationQ(token: String): Double? {
        val m = durRe.find(token) ?: return null
        if (m.groupValues[2] == "G") return null
        val d = m.groupValues[1].toInt()
        var q = if (d == 0) 8.0 else 4.0 / d
        q *= when (m.groupValues[3]) { "." -> 1.5; ".." -> 1.75; else -> 1.0 }
        return q
    }

    /** "C#4" değil: homr perdeyi "C4", değişikliği ayrı ("#", "b", "##", "bb", "N", "_") verir. */
    fun midi(pitch: String, lift: String): Int? {
        if (pitch.length < 2 || pitch[0] !in "ABCDEFG") return null
        val octave = pitch.substring(1).toIntOrNull() ?: return null
        val alter = when (lift) { "#" -> 1; "##" -> 2; "b" -> -1; "bb" -> -2; else -> 0 }
        return Pitch.fromStep(pitch[0].toString(), alter, octave)
    }

    private fun parseStaff(symbols: List<OmrSymbol>): ParsedStaff {
        val measures = ArrayList<StaffMeasure>()
        var cur = StaffMeasure()
        var cursor = 0.0
        var lastStart = 0.0
        var chordNext = false
        var clefOct = 0
        var shiftOct = 0
        var compound = false
        fun close() {
            if (cur.events.isNotEmpty() || cursor > 0 || cur.empty > 0) {
                cur.lengthQ = cursor
                measures += cur
            }
            cur = StaffMeasure(); cursor = 0.0; chordNext = false
        }
        for (s in symbols) {
            // Tek porte okunurken homr'un yalnızca üst/konumsuz sembolleri kullanılır (homr ile aynı)
            if (s.position == "lower" || s.position == "lower2") continue
            val r = s.rhythm
            when {
                r == "chord" -> chordNext = true
                r == "barline" || r == "doublebarline" || r == "bolddoublebarline" || r.startsWith("repeat") -> close()
                r.startsWith("clef_") -> clefOct = when { r.endsWith("_8vb") -> -1; r.endsWith("_8va") -> 1; else -> 0 }
                r == "octaveShift_8va" -> shiftOct = 1
                r == "octaveShift_8vb" -> shiftOct = -1
                r == "octaveShiftStop" -> shiftOct = 0
                r.startsWith("timeSignature/") -> compound = r.substringAfter("/") == "8"
                r.startsWith("rest_") && r.endsWith("m") -> { cur.empty += r.removePrefix("rest_").removeSuffix("m").toIntOrNull() ?: 1 }
                r.startsWith("rest_") -> { durationQ(r.removePrefix("rest_"))?.let { cursor += it }; chordNext = false }
                r.startsWith("note_") -> {
                    val q = durationQ(r.removePrefix("note_"))
                    val m = midi(s.pitch, s.lift)
                    if (q == null || m == null) { chordNext = false; continue }
                    val start = if (chordNext) lastStart else cursor
                    if (!chordNext) { lastStart = cursor; cursor += q } else cursor = maxOf(cursor, start + q)
                    cur.events += Ev(start, q, m + 12 * (clefOct + shiftOct))
                    chordNext = false
                }
            }
        }
        close()
        return ParsedStaff(measures, compound)
    }

    /**
     * [systems]: her sistem için yukarıdan aşağı porte sembolleri.
     * Bir parti çoğunlukla iki sesli akorlardan oluşuyorsa (ör. tek portede S+A) üst/alt
     * ses olarak ikiye ayrılır.
     */
    fun build(title: String, systems: List<List<List<OmrSymbol>>>, bpm: Double = DEFAULT_BPM): Score {
        val secPerQ = 60.0 / bpm
        val partCount = systems.groupingBy { it.size }.eachCount().maxByOrNull { it.value }?.key ?: 0
        val partEvents = List(partCount) { ArrayList<Pair<Double, Ev>>() } // (mutlak çeyrek başlangıç, olay)
        val measures = ArrayList<Measure>()
        var globalQ = 0.0
        var compoundAny = false
        for (system in systems) {
            val parsed = system.map { parseStaff(it) }
            compoundAny = compoundAny || parsed.any { it.compound }
            val count = parsed.maxOfOrNull { p -> p.measures.sumOf { maxOf(1, it.empty) } } ?: 0
            // Çok ölçülü susları açarak her portenin ölçü listesini düzleştir
            val flat = parsed.map { p -> p.measures.flatMap { m -> if (m.empty > 0 && m.events.isEmpty()) List(m.empty) { StaffMeasure() } else listOf(m) } }
            val lengths = DoubleArray(count) { j -> flat.maxOf { f -> f.getOrNull(j)?.lengthQ ?: 0.0 } }
            val fallback = lengths.filter { it > 0 }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: 4.0
            for (j in 0 until count) {
                val len = if (lengths[j] > 0) lengths[j] else fallback
                val beatQ = if (compoundAny && (len / 1.5) % 1.0 == 0.0) 1.5 else 1.0
                val beats = generateSequence(0.0) { it + beatQ }.takeWhile { it < len - 1e-6 }.map { (globalQ + it) * secPerQ }.toList()
                measures += Measure(measures.size + 1, "${measures.size + 1}", globalQ * secPerQ, (globalQ + len) * secPerQ, beats)
                flat.forEachIndexed { staffIdx, f ->
                    if (staffIdx >= partCount) return@forEachIndexed
                    f.getOrNull(j)?.events?.forEach { e -> partEvents[staffIdx] += (globalQ + e.startQ) to e }
                }
                globalQ += len
            }
        }

        // Sekizlik sol anahtarı (tenor) modelce her zaman tanınmaz: koro düzeninde alttaki parti
        // üsttekinden belirgin biçimde yüksek çıkıyorsa yazılı perdesi bir oktav yukarıdadır.
        for (pi in 1 until partCount) {
            val above = partEvents[pi - 1].map { it.second.midi }.sorted()
            val here = partEvents[pi].map { it.second.midi }.sorted()
            if (above.isEmpty() || here.isEmpty()) continue
            val ma = above[above.size / 2]; val mh = here[here.size / 2]
            if (mh > ma + 2 && mh - 12 <= ma) {
                partEvents[pi].replaceAll { (q, e) -> q to Ev(e.startQ, e.durQ, e.midi - 12) }
            }
        }

        var group = 0
        val lines = ArrayList<VoiceLine>()
        fun measureOf(q: Double) = measures.indexOfLast { it.startSec <= q * secPerQ + 1e-6 }.coerceAtLeast(0) + 1
        partEvents.forEachIndexed { pi, evs ->
            if (evs.isEmpty()) return@forEachIndexed
            val groups = evs.groupBy { it.first }.toSortedMap()
            val chordShare = groups.values.count { it.size >= 2 }.toDouble() / groups.size
            val partName = "${pi + 1}. porte"
            if (chordShare >= 0.3) {
                val upper = ArrayList<Note>(); val lower = ArrayList<Note>()
                for ((q, g) in groups) {
                    val hi = g.maxBy { it.second.midi }.second; val lo = g.minBy { it.second.midi }.second
                    upper += Note(hi.midi, q * secPerQ, hi.durQ * secPerQ, measureOf(q), null, ++group)
                    lower += Note(lo.midi, q * secPerQ, lo.durQ * secPerQ, measureOf(q), null, ++group)
                }
                lines += VoiceLine("omr-$pi-u", "$partName · üst ses", partName, upper)
                lines += VoiceLine("omr-$pi-l", "$partName · alt ses", partName, lower)
            } else {
                val notes = ArrayList<Note>()
                for ((q, g) in groups) { group++; g.forEach { (_, e) -> notes += Note(e.midi, q * secPerQ, e.durQ * secPerQ, measureOf(q), null, group) } }
                lines += VoiceLine("omr-$pi", partName, partName, notes)
            }
        }
        return Score(title, SOURCE_LABEL, lines, measures, bpm)
    }
}
