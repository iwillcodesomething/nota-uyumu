package com.notauyumu.core

import com.notauyumu.core.OmrLogicTest.Companion.expected
import com.notauyumu.core.OmrLogicTest.Companion.loadGray
import com.notauyumu.core.OmrLogicTest.Companion.resource
import com.notauyumu.core.omr.HomrModel
import com.notauyumu.core.omr.OmrPipeline
import com.notauyumu.core.omr.OmrSymbol
import com.notauyumu.core.omr.StaffFinder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * homr modelleriyle uçtan uca OMR testleri. Modeller depoda değildir; CI derleme
 * öncesinde homr'un GitHub sürümünden indirip app/src/main/assets/homr/ altına koyar.
 */
class OmrModelTest {
    private fun model(): HomrModel {
        fun f(n: String) = listOf("../app/src/main/assets/homr/$n", "app/src/main/assets/homr/$n").map(::File).first { it.exists() }.path
        return HomrModel(f("encoder.onnx"), f("decoder.onnx"))
    }

    private fun lcs(a: List<Any>, b: List<Any>): Int {
        val d = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in 1..a.size) for (j in 1..b.size) d[i][j] = if (a[i - 1] == b[j - 1]) d[i - 1][j - 1] + 1 else maxOf(d[i - 1][j], d[i][j - 1])
        return d[a.size][b.size]
    }
    private fun similarity(a: List<Any>, b: List<Any>) = 2.0 * lcs(a, b) / (a.size + b.size).coerceAtLeast(1)
    private fun key(s: OmrSymbol) = "${s.rhythm}:${s.pitch}${s.lift}"

    @Test
    fun kotlinDecoderMatchesPythonReference() {
        val page = StaffFinder.preparePage(loadGray(resource("tabi.png")))
        val staves = StaffFinder.find(page)
        val exp = expected()
        model().use { m ->
            for (i in 0..1) {
                val t = System.currentTimeMillis()
                val got = m.read(StaffFinder.modelInput(page, staves, i))
                val sim = similarity(exp[i]!!.map(::key), got.map(::key))
                println("Porte $i: ${got.size} sembol, Python ile benzerlik %.3f, %d ms".format(sim, System.currentTimeMillis() - t))
                println("   " + got.take(20).joinToString(" ") { key(it) })
                assertTrue("porte $i benzerlik $sim", sim >= 0.97)
            }
        }
    }

    @Test
    fun phonePhotoPipeline() {
        model().use { m ->
            val t = System.currentTimeMillis()
            val s = OmrPipeline.read("foto", listOf(loadGray(resource("fotograf.jpg"))), OmrPipeline.Reader { m.read(it) }) { f, msg -> if ((f * 100).toInt() % 25 == 0) println("  %.0f%% %s".format(f * 100, msg)) }
            println("Fotoğraf: ${s.measures.size} ölçü, partiler ${s.lines.map { it.name + "(" + it.notes.size + ")" }}, ${System.currentTimeMillis() - t} ms")
            assertTrue(s.measures.size in 26..30)
            assertEquals(61, s.lines.first().notes.first().midi)
        }
    }

    /** CI'da scripts/koro_gorselleri.py ile üretilen görüntüler (yoksa test atlanır). */
    private fun choir(name: String): File? = System.getenv("OMR_CHOIR_DIR")?.let { File(it, "$name.png") }?.takeIf { it.exists() }

    private fun demo() = MusicXmlParser.parse(listOf("../app/src/main/assets/isinma-korali.musicxml", "app/src/main/assets/isinma-korali.musicxml").map(::File).first { it.exists() }.readBytes())

    private fun checkChoir(name: String, minSim: Double) {
        val img = choir(name) ?: run { println("$name.png yok, atlandı"); return }
        val truth = demo()
        model().use { m ->
            val s = OmrPipeline.read(name, listOf(loadGray(img)), OmrPipeline.Reader { m.read(it) })
            println("Koro ($name): ${s.measures.size} ölçü, ${s.lines.map { it.name }}")
            assertEquals("$name: 4 ses hattı beklenir", 4, s.lines.size)
            val sims = truth.lines.mapIndexed { i, l ->
                println("  ${l.name}: ${s.lines[i].notes.joinToString(" ") { Pitch.name(it.midi) }}")
                // Oktav hatası (ör. sekizlik tenor anahtarı) ayrıca raporlanır; karşılaştırma perde sınıfıyla
                similarity(l.notes.map { Pitch.solfege(it.midi) }, s.lines[i].notes.map { Pitch.solfege(it.midi) })
            }
            println("  benzerlik: ${sims.map { "%.2f".format(it) }}")
            assertTrue("$name benzerlik $sims", sims.all { it >= minSim })
        }
    }

    @Test fun choirOpenScore() = checkChoir("acik", 0.9)
    @Test fun choirClosedScore() = checkChoir("kapali", 0.85)
}
