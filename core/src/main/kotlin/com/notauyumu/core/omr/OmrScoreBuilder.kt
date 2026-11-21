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
    // ------------------------------------------------------------ anahtar ve parti eşleme

    private const val STEPS = "CDEFGAB"

    /** Anahtarın alt çizgisindeki notanın diyatonik sırası (oktav·7 + basamak). */
    private fun bottomLine(clef: String): Int? = when (clef.removeSuffix("_8vb").removeSuffix("_8va")) {
        "clef_G2" -> 4 * 7 + 2   // Mi4
        "clef_F4" -> 2 * 7 + 4   // Sol2
        "clef_C3" -> 3 * 7 + 3   // Fa3
        "clef_C4" -> 2 * 7 + 1 + 7 // Re3
        "clef_C1" -> 3 * 7 + 0 + 7 // Do4
        else -> null
    }

    private fun clefOf(staff: List<OmrSymbol>): String? = staff.firstOrNull { it.rhythm.startsWith("clef_") }?.rhythm

    private fun medianPitch(staff: List<OmrSymbol>): Double? {
        val clefOct = clefOf(staff)?.let { if (it.endsWith("_8vb")) -12 else if (it.endsWith("_8va")) 12 else 0 } ?: 0
        val ps = staff.filter { it.rhythm.startsWith("note_") && it.position != "lower" && it.position != "lower2" }.mapNotNull { midi(it.pitch, it.lift) }.sorted()
        return if (ps.isEmpty()) null else ps[ps.size / 2].toDouble() + clefOct
    }

    /**
     * Fotoğraflarda model anahtarı ara sıra yanlış okur (ör. sol anahtarını fa anahtarı sanır); o porte
     * perdeleri bütünüyle kayar ve parti başka bir sesin notalarına benzer. Partinin diğer sistemlerdeki
     * çoğunluk anahtarı esas alınır, notalar porte üzerindeki yerleri korunarak yeniden adlandırılır;
     * değişiklik işaretleri partinin doğru okunmuş portelerindeki donanımdan tahmin edilir.
     */
    private fun withClef(staff: List<OmrSymbol>, expected: String, key: Map<Char, String>): List<OmrSymbol> {
        val read = clefOf(staff) ?: return staff
        if (read == expected) return staff
        val from = bottomLine(read) ?: return staff
        val to = bottomLine(expected) ?: return staff
        val shift = to - from
        return staff.map { s ->
            when {
                s.rhythm.startsWith("clef_") -> OmrSymbol(expected, s.pitch, s.lift, s.position)
                s.rhythm.startsWith("note_") && s.pitch.length >= 2 && s.pitch[0] in STEPS -> {
                    val oct = s.pitch.substring(1).toIntOrNull() ?: return@map s
                    val dia = oct * 7 + STEPS.indexOf(s.pitch[0]) + shift
                    val step = STEPS[Math.floorMod(dia, 7)]
                    val lift = if (s.lift == "N") "N" else key[step] ?: "_"
                    OmrSymbol(s.rhythm, "$step${Math.floorDiv(dia, 7)}", lift, s.position)
                }
                else -> s
            }
        }
    }

    /** Partinin (doğru anahtarla okunmuş portelerinden) basamak başına en sık değişiklik işareti. */
    private fun keyOf(staves: List<List<OmrSymbol>>): Map<Char, String> {
        val counts = HashMap<Char, HashMap<String, Int>>()
        for (st in staves) for (s in st) if (s.rhythm.startsWith("note_") && s.pitch.isNotEmpty() && s.pitch[0] in STEPS) {
            val l = if (s.lift == "#" || s.lift == "b") s.lift else "_"
            counts.getOrPut(s.pitch[0]) { HashMap() }.merge(l, 1, Int::plus)
        }
        return counts.mapValues { (_, m) -> m.maxBy { it.value }.key }
    }

    /**
     * Sistemlerdeki portelerin hangi partiye ait olduğunu belirler ve anahtar hatalarını düzeltir.
     * Tam sistemlerde (en sık porte sayısı) j. porte j. partidir. Eksik sistemlerde (ör. yalnızca piyano
     * girişi ya da susan partilerin gizlendiği sistemler) porteler, sırası korunarak anahtarı ve ses aralığı
     * en çok benzeyen partilere eşlenir.
     */
    private fun assignParts(systems: List<List<List<OmrSymbol>>>, partCount: Int): List<List<Pair<Int, List<OmrSymbol>>>> {
        val full = systems.filter { it.size == partCount }
        val clefs = List(partCount) { pi -> full.mapNotNull { clefOf(it[pi]) }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key }
        val keys = List(partCount) { pi -> keyOf(full.map { it[pi] }.filter { clefOf(it) == clefs[pi] }) }
        val medians = List(partCount) { pi ->
            val ms = full.map { it[pi] }.filter { clefOf(it) == clefs[pi] }.mapNotNull { medianPitch(it) }.sorted()
            if (ms.isEmpty()) null else ms[ms.size / 2]
        }
        return systems.map { sys ->
            val mapping: List<Int> = if (sys.size == partCount) sys.indices.toList() else if (sys.size > partCount) sys.indices.map { minOf(it, partCount - 1) } else {
                // Sırayı koruyan en ucuz eşleme (parti sayısı küçük: tüm seçimler denenir)
                var best: List<Int> = sys.indices.toList(); var bestCost = Double.MAX_VALUE
                fun rec(i: Int, from: Int, acc: List<Int>, cost: Double) {
                    if (cost >= bestCost) return
                    if (i == sys.size) { best = acc; bestCost = cost; return }
                    for (p in from..partCount - (sys.size - i)) {
                        val c = (if (clefs[p] != null && clefOf(sys[i]) != null && clefs[p]!!.removeSuffix("_8vb") != clefOf(sys[i])!!.removeSuffix("_8vb")) 10.0 else 0.0) +
                            (medianPitch(sys[i])?.let { m -> medians[p]?.let { kotlin.math.abs(m - it) / 6.0 } } ?: 0.0)
                        rec(i + 1, p + 1, acc + p, cost + c)
                    }
                }
                rec(0, 0, emptyList(), 0.0)
                best
            }
            sys.indices.map { i ->
                val pi = mapping[i]
                val exp = clefs[pi]
                pi to if (exp != null && sys.size == partCount) withClef(sys[i], exp, keys[pi]) else sys[i]
            }
        }
    }

    private fun splitMerged(f: List<StaffMeasure>, ref: List<StaffMeasure>): List<StaffMeasure> {
        val out = ArrayList<StaffMeasure>()
        var j = 0
        for (m in f) {
            val a = ref.getOrNull(j)?.lengthQ ?: 0.0
            val b = ref.getOrNull(j + 1)?.lengthQ ?: 0.0
            if (a > 0 && b > 0 && kotlin.math.abs(m.lengthQ - a) > 0.25 && kotlin.math.abs(m.lengthQ - (a + b)) <= 0.25) {
                val first = StaffMeasure(m.events.filter { it.startQ < a - 1e-6 }.map { Ev(it.startQ, minOf(it.durQ, a - it.startQ), it.midi) }.toMutableList(), a)
                val second = StaffMeasure(m.events.filter { it.startQ >= a - 1e-6 }.map { Ev(it.startQ - a, it.durQ, it.midi) }.toMutableList(), m.lengthQ - a)
                out += first; out += second; j += 2
            } else { out += m; j += 1 }
        }
        return out
    }

    fun build(title: String, systems: List<List<List<OmrSymbol>>>, bpm: Double = DEFAULT_BPM): Score {
        val secPerQ = 60.0 / bpm
        val partCount = systems.groupingBy { it.size }.eachCount().maxByOrNull { it.value }?.key ?: 0
        val assigned = assignParts(systems, partCount)
        val partEvents = List(partCount) { ArrayList<Pair<Double, Ev>>() } // (mutlak çeyrek başlangıç, olay)
        val measures = ArrayList<Measure>()
        var globalQ = 0.0
        var compoundAny = false
        for (system in assigned) {
            val parsed = system.map { parseStaff(it.second) }
            compoundAny = compoundAny || parsed.any { it.compound }
            val count = parsed.maxOfOrNull { p -> p.measures.sumOf { maxOf(1, it.empty) } } ?: 0
            // Çok ölçülü susları açarak her portenin ölçü listesini düzleştir
            val flat0 = parsed.map { p -> p.measures.flatMap { m -> if (m.empty > 0 && m.events.isEmpty()) List(m.empty) { StaffMeasure() } else listOf(m) } }
            // Bir portede ölçü çizgisi kaçırıldıysa (fotoğraf/tarama) iki ölçü tek ölçü gibi okunur ve o
            // portenin sonraki bütün ölçüleri kayar: en çok ölçülü porteye göre birleşik ölçüler bölünür.
            val ref = flat0.maxBy { it.size }
            val flat = flat0.map { f -> if (f.size >= ref.size) f else splitMerged(f, ref) }
            val lengths = DoubleArray(count) { j -> flat.maxOf { f -> f.getOrNull(j)?.lengthQ ?: 0.0 } }
            val fallback = lengths.filter { it > 0 }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: 4.0
            for (j in 0 until count) {
                val len = if (lengths[j] > 0) lengths[j] else fallback
                val beatQ = if (compoundAny && (len / 1.5) % 1.0 == 0.0) 1.5 else 1.0
                val beats = generateSequence(0.0) { it + beatQ }.takeWhile { it < len - 1e-6 }.map { (globalQ + it) * secPerQ }.toList()
                measures += Measure(measures.size + 1, "${measures.size + 1}", globalQ * secPerQ, (globalQ + len) * secPerQ, beats)
                flat.forEachIndexed { staffIdx, f ->
                    val pi = system[staffIdx].first
                    if (pi !in 0 until partCount) return@forEachIndexed
                    f.getOrNull(j)?.events?.forEach { e -> partEvents[pi] += (globalQ + e.startQ) to e }
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

        // Piyano eşliği: 5+ portelik sistemlerde en alttaki sol + fa anahtarlı porte çifti, çoğunlukla
        // akorlu ya da yalnız başına (giriş/ara) sistemlerde görünüyorsa piyanodur; koro partisi sayılmaz.
        val full = systems.filter { it.size == partCount }
        val partClefs = List(partCount) { pi -> full.mapNotNull { clefOf(it[pi]) }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key }
        val chordShares = partEvents.map { evs -> val g = evs.groupBy { it.first }; if (g.isEmpty()) 0.0 else g.values.count { it.size >= 2 }.toDouble() / g.size }
        val pianoAlone = systems.any { it.size == 2 } && partCount >= 4
        val piano = partCount >= 5 && partClefs[partCount - 2] == "clef_G2" && partClefs[partCount - 1] == "clef_F4" &&
            (chordShares[partCount - 2] >= 0.3 || chordShares[partCount - 1] >= 0.3 || pianoAlone)
        val vocalCount = if (piano) partCount - 2 else partCount

        var group = 0
        val lines = ArrayList<VoiceLine>()
        val pianoLines = ArrayList<VoiceLine>()
        fun measureOf(q: Double) = measures.indexOfLast { it.startSec <= q * secPerQ + 1e-6 }.coerceAtLeast(0) + 1
        partEvents.forEachIndexed { pi, evs ->
            if (evs.isEmpty()) return@forEachIndexed
            val groups = evs.groupBy { it.first }.toSortedMap()
            val chordShare = groups.values.count { it.size >= 2 }.toDouble() / groups.size
            val partName = "${pi + 1}. porte"
            if (pi >= vocalCount) {
                val notes = ArrayList<Note>()
                for ((q, g) in groups) { group++; g.forEach { (_, e) -> notes += Note(e.midi, q * secPerQ, e.durQ * secPerQ, measureOf(q), null, group) } }
                pianoLines += VoiceLine("omr-piano-$pi", if (pi == partCount - 2) "Piyano · sağ el" else "Piyano · sol el", "Piyano", notes)
                return@forEachIndexed
            }
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
        return Score(title, SOURCE_LABEL, choirNames(lines) + pianoLines, measures, bpm)
    }

    /**
     * 4 ya da 5 ses hattı varsa koro düzenine göre ad verir (porte numarası korunur), ör.
     * "Soprano · 1. porte". Diğer sayılarda porte adları kalır.
     */
    fun choirNames(lines: List<VoiceLine>): List<VoiceLine> {
        val names = when (lines.size) {
            4 -> listOf("Soprano", "Alto", "Tenor", "Bas")
            5 -> listOf("Soprano", "Mezzosoprano", "Alto", "Tenor", "Bas")
            else -> return lines
        }
        return lines.mapIndexed { i, l -> l.copy(name = "${names[i]} · ${l.name}") }
    }
}
