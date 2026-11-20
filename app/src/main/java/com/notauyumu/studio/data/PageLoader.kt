package com.notauyumu.studio.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import com.notauyumu.core.ScoreFormatException
import com.notauyumu.core.omr.GrayImage
import com.notauyumu.core.omr.StaffFinder

/** PDF sayfalarını ve fotoğrafları nota okuyucunun beklediği gri görüntülere çevirir. */
object PageLoader {
    private const val MAX_PAGES = 30

    enum class Kind { MUSICXML, PDF, IMAGE }

    fun kindOf(context: Context, uri: Uri): Kind {
        val mime = context.contentResolver.getType(uri).orEmpty()
        if (mime == "application/pdf") return Kind.PDF
        if (mime.startsWith("image/")) return Kind.IMAGE
        val head = context.contentResolver.openInputStream(uri)?.use { s -> ByteArray(8).also { s.read(it) } } ?: return Kind.MUSICXML
        return when {
            head[0] == '%'.code.toByte() && head[1] == 'P'.code.toByte() && head[2] == 'D'.code.toByte() -> Kind.PDF
            head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() -> Kind.IMAGE // JPEG
            head[0] == 0x89.toByte() && head[1] == 'P'.code.toByte() -> Kind.IMAGE // PNG
            head.copyOfRange(4, 8).decodeToString() == "ftyp" -> Kind.IMAGE // HEIC
            else -> Kind.MUSICXML
        }
    }

    /**
     * Bulut sağlayıcılarından (Drive, WhatsApp, e-posta eki…) gelen dosyalar çoğu zaman aranabilir
     * (seekable) değildir; PdfRenderer ve MediaExtractor bunları açamaz. Önce önbelleğe kopyalanır.
     */
    fun localCopy(context: Context, uri: Uri, name: String): Uri {
        if (uri.scheme == "file") return uri
        val dir = java.io.File(context.cacheDir, "import").apply { mkdirs() }
        val f = java.io.File(dir, name)
        val input = try { context.contentResolver.openInputStream(uri) } catch (e: Exception) { null }
            ?: throw ScoreFormatException("Dosya açılamadı.")
        input.use { i -> f.outputStream().use { i.copyTo(it, 1 shl 16) } }
        return Uri.fromFile(f)
    }

    fun pdfPages(context: Context, uri0: Uri, progress: (Float) -> Unit = {}): List<GrayImage> {
        val uri = localCopy(context, uri0, "import.pdf")
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: throw ScoreFormatException("PDF açılamadı.")
        return pfd.use { fd ->
            val renderer = try { PdfRenderer(fd) } catch (e: Exception) { throw ScoreFormatException("PDF açılamadı; dosya şifreli ya da bozuk olabilir.") }
            renderer.use { r ->
                if (r.pageCount > MAX_PAGES) throw ScoreFormatException("PDF çok uzun (en fazla $MAX_PAGES sayfa).")
                (0 until r.pageCount).map { i ->
                    progress(i.toFloat() / r.pageCount)
                    r.openPage(i).use { page ->
                        val w = StaffFinder.PAGE_WIDTH
                        val h = (page.height.toLong() * w / page.width).toInt()
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        toGray(bmp).also { bmp.recycle() }
                    }
                }
            }
        }
    }

    /** Fotoğrafı (EXIF yönüne göre döndürülmüş) yaklaşık 2400 piksel genişliğe indirerek okur. */
    fun image(context: Context, uri: Uri): GrayImage {
        val target = 2400
        val bmp: Bitmap = if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { d, info, _ ->
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val w = info.size.width; val h = info.size.height
                val longSide = maxOf(w, h)
                if (longSide > target * 1.4) { val k = target * 1.4 / longSide; d.setTargetSize((w * k).toInt(), (h * k).toInt()) }
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= target) sample *= 2
            val raw = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
                ?: throw ScoreFormatException("Fotoğraf açılamadı.")
            val rotation = context.contentResolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
            if (rotation == 0) raw else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
        }
        return toGray(bmp).also { bmp.recycle() }
    }

    private fun toGray(bmp: Bitmap): GrayImage {
        val w = bmp.width; val h = bmp.height
        val px = ByteArray(w * h)
        val row = IntArray(w)
        for (y in 0 until h) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val c = row[x]
                val a = (c ushr 24) and 255
                val g = (((c shr 16) and 255) * 299 + ((c shr 8) and 255) * 587 + (c and 255) * 114) / 1000
                px[y * w + x] = (if (a < 255) 255 - (255 - g) * a / 255 else g).toByte()
            }
        }
        return GrayImage(w, h, px)
    }
}
