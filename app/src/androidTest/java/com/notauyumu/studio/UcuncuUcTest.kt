package com.notauyumu.studio

import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.notauyumu.core.ArrangementBuilder
import com.notauyumu.core.MixSettings
import com.notauyumu.core.Score
import com.notauyumu.core.SynthRenderer
import com.notauyumu.studio.audio.PracticeEngine
import com.notauyumu.studio.data.Library
import com.notauyumu.studio.data.PageLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile

/**
 * Uçtan uca cihaz testleri: uygulamanın gerçek veri yolları (ONNX Runtime, PdfRenderer, MediaCodec,
 * AudioTrack) emülatörde çalıştırılır. Süreler "NU_TEST" etiketiyle loglanır.
 */
@RunWith(AndroidJUnit4::class)
class UcuncuUcTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val testCtx = InstrumentationRegistry.getInstrumentation().context
    private val lib = Library(ctx)

    private fun log(msg: String) { Log.i("NU_TEST", msg); println("NU_TEST $msg") }

    private inline fun <T> timed(label: String, block: () -> T): T {
        val t0 = System.currentTimeMillis()
        val r = block()
        log("$label: ${System.currentTimeMillis() - t0} ms")
        return r
    }

    private fun asset(name: String): File {
        val f = File(ctx.cacheDir, name)
        testCtx.assets.open(name).use { i -> f.outputStream().use { i.copyTo(it) } }
        return f
    }

    private fun demo(): Score = lib.load(Library.DEMO_ID)

    private fun wav(name: String, x: FloatArray, rate: Int = 22050): File {
        val f = File(ctx.cacheDir, name)
        RandomAccessFile(f, "rw").use { r ->
            r.setLength(0)
            fun le32(v: Int) = r.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
            fun le16(v: Int) = r.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
            r.writeBytes("RIFF"); le32(36 + x.size * 2); r.writeBytes("WAVEfmt "); le32(16); le16(1); le16(1)
            le32(rate); le32(rate * 2); le16(2); le16(16); r.writeBytes("data"); le32(x.size * 2)
            val b = ByteArray(x.size * 2)
            for (i in x.indices) { val s = (x[i].coerceIn(-1f, 1f) * 32767).toInt(); b[2 * i] = s.toByte(); b[2 * i + 1] = (s shr 8).toByte() }
            r.write(b)
        }
        return f
    }

    private fun render(score: Score, rate: Int = 22050): FloatArray {
        val mix = MixSettings(ownGain = 0.9f, othersGain = 0.9f, metronome = false, countIn = false, startingPitch = false)
        val arr = ArrangementBuilder.build(score, score.lines[0].id, mix, 0.0, score.durationSec)
        val r = SynthRenderer(rate, arr, mix)
        val out = ArrayList<Float>(); val buf = FloatArray(2048)
        while (r.render(buf)) buf.forEach { out += it }
        return out.toFloatArray()
    }

    @Test fun t01_uygulamaAcilir() {
        ActivityScenario.launch(MainActivity::class.java).use { s -> Thread.sleep(3000); s.onActivity { } }
        log("açılış tamam")
    }

    @Test fun t02_demoYuklenir() {
        val s = timed("demo yükleme") { demo() }
        assertTrue(s.lines.size >= 4)
    }

    @Test fun t03_calismaSesiCalar() {
        val s = demo()
        val mix = MixSettings()
        val arr = ArrangementBuilder.build(s, s.lines[0].id, mix, 0.0, minOf(6.0, s.durationSec))
        val e = PracticeEngine(arr, mix, listenToMic = false, latencySec = 0.0)
        e.start(); Thread.sleep(2500)
        val t = e.scoreTimeSec()
        e.stop()
        log("çalma: 2.5 sn sonra eser zamanı $t, hata=${e.failure}")
        assertEquals(null, e.failure)
        assertTrue("çalma ilerlemedi ($t)", t > 0.5)
    }

    @Test fun t04_fotograftanOkur() {
        val f = asset("fotograf.jpg")
        val page = timed("fotoğraf açma") { PageLoader.image(ctx, Uri.fromFile(f)) }
        val e = timed("fotoğraf OMR") { lib.importScanned("Foto", listOf(page)) { _, _ -> } }
        val s = lib.load(e.id)
        log("fotoğraf: ${s.lines.size} parti, ${s.lines.sumOf { it.notes.size }} nota, ${s.measures.size} ölçü")
        assertTrue(s.lines.isNotEmpty() && s.lines.sumOf { it.notes.size } > 10)
    }

    @Test fun t05_pdfdenOkur() {
        val png = asset("tabi.png")
        val bmp = BitmapFactory.decodeFile(png.path)
        val pdf = File(ctx.cacheDir, "tabi.pdf")
        PdfDocument().apply {
            val p = startPage(PdfDocument.PageInfo.Builder(595, (595L * bmp.height / bmp.width).toInt(), 1).create())
            p.canvas.drawBitmap(bmp, null, android.graphics.Rect(0, 0, 595, (595L * bmp.height / bmp.width).toInt()), null)
            finishPage(p); pdf.outputStream().use { writeTo(it) }; close()
        }
        val uri = Uri.fromFile(pdf)
        assertEquals(PageLoader.Kind.PDF, PageLoader.kindOf(ctx, uri))
        val pages = timed("PDF sayfaları") { PageLoader.pdfPages(ctx, uri) }
        val e = timed("PDF OMR") { lib.importScanned("PDF", pages) { _, _ -> } }
        val s = lib.load(e.id)
        log("PDF: ${s.lines.size} parti, ${s.lines.sumOf { it.notes.size }} nota, ${s.measures.size} ölçü")
        assertTrue(s.lines.sumOf { it.notes.size } > 10)
    }

    @Test fun t06_tuttiKayittanPartiler() {
        val s = demo()
        val f = wav("tutti.wav", render(s))
        val e = timed("tutti kayıt (otomatik ses sayısı)") { lib.importRecording(Uri.fromFile(f), Library.RecordingKind.TUTTI, 0) { _, _ -> } }
        val r = lib.load(e.id)
        log("tutti: ${r.lines.size} parti: " + r.lines.joinToString { "${it.name}=${it.notes.size}" })
        assertTrue(r.lines.size >= 3)
    }

    @Test fun t07_tekPartiKayit() {
        val s = demo()
        val solo = s.copy(lines = listOf(s.lines[0]))
        val f = wav("solo.wav", render(solo))
        val e = timed("tek parti kayıt") { lib.importRecording(Uri.fromFile(f), Library.RecordingKind.SINGLE, 1) { _, _ -> } }
        val r = lib.load(e.id)
        log("tek parti: ${r.lines[0].notes.size} nota (notada ${solo.lines[0].notes.size})")
        assertTrue(r.lines[0].notes.size >= solo.lines[0].notes.size / 2)
    }

    @Test fun t08_notaArtiKayit() {
        val s = demo()
        val fast = s.scaled(1.25)
        val full = render(fast)
        val from = (fast.measures[1].startSec * 22050).toInt()
        val f = wav("ref.wav", FloatArray(22050) + full.copyOfRange(from, full.size))
        val e = timed("nota + kayıt eşleştirme") { lib.importAligned(Library.DEMO_ID, Uri.fromFile(f)) { _, _ -> } }
        val r = lib.load(e.id)
        log("eşleştirme: ${e.composer}; " + r.lines.joinToString { it.name })
        assertTrue(r.measures.size >= 2)
    }
}
