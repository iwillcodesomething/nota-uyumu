package com.notauyumu.core.omr

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** 8 bit gri tonlamalı görüntü (0 = siyah, 255 = beyaz), satır öncelikli. */
class GrayImage(val width: Int, val height: Int, val pixels: ByteArray) {
    init { require(pixels.size == width * height) }
    operator fun get(x: Int, y: Int): Int = pixels[y * width + x].toInt() and 0xFF

    /** Çift doğrusal örnekleme; dışarısı beyaz. */
    fun sample(x: Double, y: Double): Int {
        if (x < 0 || y < 0 || x > width - 1 || y > height - 1) return 255
        val x0 = x.toInt(); val y0 = y.toInt()
        val x1 = minOf(width - 1, x0 + 1); val y1 = minOf(height - 1, y0 + 1)
        val tx = x - x0; val ty = y - y0
        val v = (this[x0, y0] * (1 - tx) + this[x1, y0] * tx) * (1 - ty) + (this[x0, y1] * (1 - tx) + this[x1, y1] * tx) * ty
        return v.roundToInt()
    }

    /** Alan ortalamalı (küçültmede) / çift doğrusal (büyütmede) yeniden boyutlandırma. */
    fun resized(w: Int, h: Int): GrayImage {
        if (w == width && h == height) return this
        val out = ByteArray(w * h)
        val sx = width.toDouble() / w
        val sy = height.toDouble() / h
        if (sx >= 1.0 && sy >= 1.0) {
            for (y in 0 until h) {
                val y0 = (y * sy).toInt(); val y1 = minOf(height, maxOf(y0 + 1, ((y + 1) * sy).toInt()))
                for (x in 0 until w) {
                    val x0 = (x * sx).toInt(); val x1 = minOf(width, maxOf(x0 + 1, ((x + 1) * sx).toInt()))
                    var s = 0; var n = 0
                    for (yy in y0 until y1) { val row = yy * width; for (xx in x0 until x1) { s += pixels[row + xx].toInt() and 0xFF; n++ } }
                    out[y * w + x] = (s / n).toByte()
                }
            }
        } else {
            for (y in 0 until h) for (x in 0 until w) {
                out[y * w + x] = sample(((x + 0.5) * sx - 0.5).coerceIn(0.0, width - 1.0), ((y + 0.5) * sy - 0.5).coerceIn(0.0, height - 1.0)).toByte()
            }
        }
        return GrayImage(w, h, out)
    }

    fun toWidth(target: Int): GrayImage = resized(target, (height.toLong() * target / width).toInt().coerceAtLeast(1))
}

/**
 * Bulunan bir porte. Sayfa kıvrık ya da eğik olabileceği için üst/alt çizgi konumu
 * x'e göre değişir: [xs] noktalarında [tops]/[bottoms] ölçülmüştür, arası doğrusal.
 */
class Staff(val xs: DoubleArray, val tops: DoubleArray, val bottoms: DoubleArray, val unit: Double, val x0: Int, val x1: Int) {
    private fun interp(arr: DoubleArray, x: Double): Double {
        if (xs.size == 1) return arr[0]
        // Uçlarda en yakın iki noktadan doğrusal uzatma (eğim sınırlı)
        if (x <= xs[0]) { val k = ((arr[1] - arr[0]) / (xs[1] - xs[0])).coerceIn(-0.15, 0.15); return arr[0] + k * (x - xs[0]) }
        if (x >= xs.last()) { val n = xs.size; val k = ((arr[n - 1] - arr[n - 2]) / (xs[n - 1] - xs[n - 2])).coerceIn(-0.15, 0.15); return arr[n - 1] + k * (x - xs[n - 1]) }
        var i = 1
        while (xs[i] < x) i++
        val t = (x - xs[i - 1]) / (xs[i] - xs[i - 1])
        return arr[i - 1] * (1 - t) + arr[i] * t
    }
    fun top(x: Double) = interp(tops, x)
    fun bottom(x: Double) = interp(bottoms, x)
    val midY: Double get() = (top((x0 + x1) / 2.0) + bottom((x0 + x1) / 2.0)) / 2
}

/**
 * Nota sayfalarında (dijital PDF ya da telefon fotoğrafı) porteleri bulur:
 * ışık dengeleme → eğiklik düzeltme → şeritler hâlinde çizgi arama → şeritleri birleştirme.
 */
object StaffFinder {
    const val PAGE_WIDTH = 1920
    /** İnce, döndürülmüş ya da bulanık çizgiler griye döner; çizgi aramada daha cömert eşik. */
    private const val LINE_DARK = 215

    /** Sayfayı modele hazırlar: genişlik 1920'ye, ışık dengelenir, eğiklik düzeltilir. */
    fun preparePage(input: GrayImage): GrayImage {
        val img = if (input.width != PAGE_WIDTH) input.toWidth(PAGE_WIDTH) else input
        val flat = flattenIllumination(img)
        val angle = estimateSkew(flat)
        return if (abs(angle) < 0.05) flat else rotate(flat, angle)
    }

    /** Arka planı (büyük kutu ortalaması) bölerek gölge ve ışık farkını giderir. */
    fun flattenIllumination(img: GrayImage, radius: Int = 25): GrayImage {
        val w = img.width; val h = img.height
        // Arka plan: kapanma benzeri — önce yerel en açık değer (max) ile mürekkebi sil, sonra ortala
        val bright = boxMax(img, radius / 3)
        val bg = boxMean(bright, radius)
        val v0s = IntArray(w * h)
        val hist = IntArray(256)
        for (i in 0 until w * h) {
            val g = img.pixels[i].toInt() and 0xFF
            val b = maxOf(1, bg.pixels[i].toInt() and 0xFF)
            val v0 = minOf(255, g * 255 / b)
            v0s[i] = v0; hist[v0]++
        }
        // Kâğıt beyaz kalır, mürekkep koyulaşır (ince çizgiler kaybolmasın). Soluk fotoğraflarda (gri mürekkep,
        // bulanık çizgi) kazanç, en koyu %1'lik mürekkep siyaha yakın olacak kadar artırılır.
        var acc = 0L; var p1 = 0
        while (p1 < 255 && acc + hist[p1] < (w.toLong() * h) / 100) { acc += hist[p1]; p1++ }
        val gain = ((255.0 - 40) / maxOf(1, 255 - p1)).coerceIn(1.3, 3.0)
        val out = ByteArray(w * h)
        for (i in 0 until w * h) out[i] = (255 - ((255 - v0s[i]) * gain).toInt()).coerceIn(0, 255).toByte()
        return GrayImage(w, h, out)
    }

    private fun boxMean(img: GrayImage, r: Int): GrayImage {
        val w = img.width; val h = img.height
        val integral = LongArray((w + 1) * (h + 1))
        for (y in 0 until h) { var row = 0L; for (x in 0 until w) { row += img[x, y]; integral[(y + 1) * (w + 1) + x + 1] = integral[y * (w + 1) + x + 1] + row } }
        val out = ByteArray(w * h)
        for (y in 0 until h) {
            val y0 = maxOf(0, y - r); val y1 = minOf(h, y + r + 1)
            for (x in 0 until w) {
                val x0 = maxOf(0, x - r); val x1 = minOf(w, x + r + 1)
                val s = integral[y1 * (w + 1) + x1] - integral[y0 * (w + 1) + x1] - integral[y1 * (w + 1) + x0] + integral[y0 * (w + 1) + x0]
                out[y * w + x] = (s / ((x1 - x0) * (y1 - y0))).toInt().toByte()
            }
        }
        return GrayImage(w, h, out)
    }

    private fun boxMax(img: GrayImage, r: Int): GrayImage {
        val w = img.width; val h = img.height
        val tmp = ByteArray(w * h); val out = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) { var m = 0; for (k in maxOf(0, x - r)..minOf(w - 1, x + r)) m = maxOf(m, img[k, y]); tmp[y * w + x] = m.toByte() }
        for (y in 0 until h) for (x in 0 until w) { var m = 0; for (k in maxOf(0, y - r)..minOf(h - 1, y + r)) m = maxOf(m, tmp[k * w + x].toInt() and 0xFF); out[y * w + x] = m.toByte() }
        return GrayImage(w, h, out)
    }

    /** Yatay izdüşümün keskinliğini en büyüten açı (derece, ±8°). */
    fun estimateSkew(img: GrayImage): Double {
        val pts = ArrayList<Int>()
        val step = 2
        for (y in 0 until img.height step step) for (x in 0 until img.width step step) if (img[x, y] < 150) pts += y * img.width + x
        if (pts.size < 100) return 0.0
        val sample = if (pts.size > 120_000) pts.filterIndexed { i, _ -> i % (pts.size / 120_000 + 1) == 0 } else pts
        fun score(deg: Double): Double {
            val a = deg * PI / 180; val s = sin(a); val c = cos(a)
            val hist = IntArray(img.height * 2 + 10)
            val off = img.height / 2
            for (p in sample) { val x = p % img.width; val y = p / img.width; val yy = (-x * s + y * c).roundToInt() + off; if (yy in hist.indices) hist[yy]++ }
            var sq = 0.0; for (v in hist) sq += v.toDouble() * v; return sq
        }
        var best = 0.0; var bs = score(0.0)
        var d = -8.0
        while (d <= 8.0) { val sc = score(d); if (sc > bs) { bs = sc; best = d }; d += 0.5 }
        var fine = best - 0.5
        while (fine <= best + 0.5) { val sc = score(fine); if (sc > bs) { bs = sc; best = fine }; fine += 0.1 }
        return best
    }

    fun rotate(img: GrayImage, deg: Double): GrayImage {
        val a = deg * PI / 180; val s = sin(a); val c = cos(a)
        val cx = img.width / 2.0; val cy = img.height / 2.0
        val out = ByteArray(img.width * img.height)
        for (y in 0 until img.height) for (x in 0 until img.width) {
            val dx = x - cx; val dy = y - cy
            out[y * img.width + x] = img.sample(cx + dx * c - dy * s, cy + dx * s + dy * c).toByte()
        }
        return GrayImage(img.width, img.height, out)
    }

    private class StripStaff(val top: Double, val bottom: Double, val unit: Double) { val mid get() = (top + bottom) / 2 }

    private fun stavesInStrip(img: GrayImage, sx0: Int, sx1: Int): List<StripStaff> {
        val w = sx1 - sx0
        val lineRows = BooleanArray(img.height)
        for (y in 0 until img.height) {
            var dark = 0
            val row = y * img.width
            for (x in sx0 until sx1) if ((img.pixels[row + x].toInt() and 0xFF) < LINE_DARK) dark++
            // Porte çizgisi şeridin tamamını kaplar; yardımcı (ledger) çizgiler kısadır
            lineRows[y] = dark >= 0.75 * w
        }
        val lines = ArrayList<Double>()
        var y = 0
        while (y < img.height) {
            if (lineRows[y]) { val y0 = y; while (y < img.height && lineRows[y]) y++; if (y - y0 <= 8) lines += (y0 + y - 1) / 2.0 } else y++
        }
        val out = ArrayList<StripStaff>()
        var i = 0
        while (i + 4 < lines.size) {
            val gaps = (0 until 4).map { lines[i + it + 1] - lines[i + it] }
            val u = gaps.sorted().let { (it[1] + it[2]) / 2 }
            if (u in 4.0..60.0 && gaps.max() <= u * 1.3 && gaps.min() >= u * 0.7) { out += StripStaff(lines[i], lines[i + 4], u); i += 5 } else i++
        }
        return out
    }

    fun find(img: GrayImage, strips: Int = 24): List<Staff> {
        val sw = img.width / strips
        val perStrip = (0 until strips).map { k -> stavesInStrip(img, k * sw, (k + 1) * sw) }
        val centers = DoubleArray(strips) { k -> k * sw + sw / 2.0 }
        // Her şerit tohum olabilir (kısa son sistemler ve bazı şeritlerde kaçan porteler için);
        // en çok porte bulunan ve ortaya yakın şeritlerden başlanır.
        val order = (0 until strips).sortedWith(compareByDescending<Int> { perStrip[it].size }.thenBy { abs(it - strips / 2) })
        val used = HashSet<Pair<Int, Int>>()
        val result = ArrayList<Staff>()
        for (ref in order) for ((si, seed) in perStrip[ref].withIndex()) {
            if ((ref to si) in used) continue
            val found = arrayOfNulls<StripStaff>(strips)
            val foundIdx = IntArray(strips) { -1 }
            found[ref] = seed; foundIdx[ref] = si
            for (dir in listOf(-1, 1)) {
                var last = seed
                var k = ref + dir
                var misses = 0
                while (k in 0 until strips && misses < 4) {
                    val ci = perStrip[k].indices.filter { (k to it) !in used }.minByOrNull { abs(perStrip[k][it].mid - last.mid) }
                    val cand = ci?.let { perStrip[k][it] }
                    if (cand != null && abs(cand.mid - last.mid) < 2.5 * seed.unit && abs(cand.unit - last.unit) < 0.3 * seed.unit) {
                        found[k] = cand; foundIdx[k] = ci; last = cand; misses = 0
                    } else misses++
                    k += dir
                }
            }
            val idx = (0 until strips).filter { found[it] != null }
            if (idx.size < 2) continue
            idx.forEach { used += it to foundIdx[it] }
            val xs = idx.map { centers[it] }.toDoubleArray()
            val tops = idx.map { found[it]!!.top }.toDoubleArray()
            val bottoms = idx.map { found[it]!!.bottom }.toDoubleArray()
            val unit = idx.map { found[it]!!.unit }.sorted()[idx.size / 2]
            val st = traced(img, Staff(xs, tops, bottoms, unit, 0, img.width - 1))
            // Aynı porteyi ikinci kez ekleme (farklı şeritlerden tohumlanmış olabilir)
            if (result.none { o -> abs(o.midY - st.midY) < 2 * unit && o.x0 < st.x1 && st.x0 < o.x1 }) result += st
        }
        return result.sortedBy { it.midY }
    }

    /**
     * Beş çizginin çoğunun koyu olduğu bölgeyi ölçülmüş en dış şeritlerden iki yana takip eder.
     * Kıvrık sayfada çizgiler kenara doğru eğilebilir: yürürken dikey kayma her adımda yeniden
     * ölçülür ve izlenen noktalar porteye eklenir (sonraki düzleştirme de bu noktaları kullanır).
     */
    private fun traced(img: GrayImage, s: Staff): Staff {
        fun hits(x: Int, off: Double): Int {
            val t = s.top(x.toDouble()) + off; val b = s.bottom(x.toDouble()) + off
            var h = 0
            for (k in 0..4) {
                val ly = t + (b - t) * k / 4
                var dark = false
                for (dy in -1..1) { val yy = (ly + dy).roundToInt(); if (yy in 0 until img.height && img[x, yy] < LINE_DARK) { dark = true; break } }
                if (dark) h++
            }
            return h
        }
        val maxGap = (2 * s.unit).toInt().coerceAtLeast(4)
        val step = 40
        val extra = ArrayList<Triple<Double, Double, Double>>()
        fun walk(dir: Int): Int {
            var x = (if (dir < 0) s.xs.first() else s.xs.last()).toInt().coerceIn(0, img.width - 1)
            var lastGood = x; var gap = 0; var off = 0.0; var sinceMark = 0
            while (x in 0 until img.width) {
                var best = -1; var bestOff = off
                for (d in -2..2) { val o = off + d * 0.5; val h = hits(x, o); if (h > best || (h == best && abs(o - off) < abs(bestOff - off))) { best = h; bestOff = o } }
                if (best >= 4) {
                    off = bestOff; lastGood = x; gap = 0
                    if (++sinceMark >= step && abs(off) >= 1.0) {
                        extra += Triple(x.toDouble(), s.top(x.toDouble()) + off, s.bottom(x.toDouble()) + off); sinceMark = 0
                    }
                } else if (++gap > maxGap) break
                x += dir
            }
            return lastGood
        }
        val x0 = walk(-1); val x1 = walk(1)
        if (extra.isEmpty()) return Staff(s.xs, s.tops, s.bottoms, s.unit, x0, x1)
        val pts = (s.xs.indices.map { Triple(s.xs[it], s.tops[it], s.bottoms[it]) } + extra).sortedBy { it.first }
        return Staff(pts.map { it.first }.toDoubleArray(), pts.map { it.second }.toDoubleArray(), pts.map { it.third }.toDoubleArray(), s.unit, x0, x1)
    }

    /** İki porte sol kenarlarında dikey çizgiyle bağlıysa aynı sistemdedir. */
    fun systems(img: GrayImage, staves: List<Staff>): List<List<Staff>> {
        if (staves.isEmpty()) return emptyList()
        val out = ArrayList<MutableList<Staff>>()
        out += mutableListOf(staves[0])
        for (k in 1 until staves.size) {
            if (connected(img, staves[k - 1], staves[k])) out.last() += staves[k] else out += mutableListOf(staves[k])
        }
        if (staves.size >= 4) {
            // Sistem çizgisi her yerde görülemeyebilir (soluk fotoğraf): bağlantıyla bulunan gruplar
            // tutarsızsa boşluklara göre gruplama denenir
            val sizes = out.map { it.size }.toSet()
            if (out.all { it.size == 1 } || sizes.size > 1) {
                val g = byGaps(staves)
                if (g != null && (g.map { it.size }.toSet().size < sizes.size || out.all { it.size == 1 })) return g
            }
        }
        return out
    }

    /**
     * Yedek yöntem: sistem çizgisi görülemezse porteler arası boşluklara bakılır. Sistem
     * içindeki boşluklar sistemler arasındakilerden belirgin biçimde küçüktür.
     */
    private fun byGaps(staves: List<Staff>): List<List<Staff>>? {
        val gaps = (1 until staves.size).map { k ->
            val x = (staves[k].x0 + staves[k].x1) / 2.0
            (staves[k].top(x) - staves[k - 1].bottom(x)) / staves[k].unit
        }
        val small = gaps.min(); val large = gaps.max()
        if (large < small * 1.4 || large - small < 2.0) return null
        val threshold = (small + large) / 2
        val out = ArrayList<MutableList<Staff>>()
        out += mutableListOf(staves[0])
        for (k in 1 until staves.size) if (gaps[k - 1] < threshold) out.last() += staves[k] else out += mutableListOf(staves[k])
        // Tutarlılık: sistemlerin çoğu aynı sayıda porte içermeli
        val common = out.groupingBy { it.size }.eachCount().maxBy { it.value }
        return if (common.key >= 2 && common.value * 2 >= out.size) out else null
    }

    private fun connected(img: GrayImage, a: Staff, b: Staff): Boolean {
        if (abs(a.x0 - b.x0) > 4 * a.unit) return false
        val xFrom = maxOf(0, minOf(a.x0, b.x0) - (2 * a.unit).toInt())
        val xTo = minOf(img.width - 1, maxOf(a.x0, b.x0) + (2 * a.unit).toInt())
        for (x in xFrom..xTo) {
            val y0 = a.bottom(x.toDouble()).toInt() + 2; val y1 = b.top(x.toDouble()).toInt() - 2
            if (y1 <= y0) return true
            // Fotoğrafta çizgi hafif eğik olabilir: her eğim için ayrı dene
            for (slope in doubleArrayOf(-0.03, -0.015, 0.0, 0.015, 0.03)) {
                var dark = 0
                for (yy in y0..y1) {
                    val cx = x + slope * (yy - y0)
                    var d = false
                    for (dx in -1..1) { val xx = (cx + dx).roundToInt(); if (xx in 0 until img.width && img[xx, yy] < LINE_DARK) d = true }
                    if (d) dark++
                }
                if (dark >= 0.85 * (y1 - y0 + 1)) return true
            }
        }
        return false
    }

    /**
     * homr'un girişi: porte düzleştirilerek kırpılır (kıvrık sayfalarda her sütun ayrı
     * ölçeklenir) ve 256x1280 tuvale sığdırılır (sol hizalı, dikeyde ortalı).
     */
    fun modelInput(img: GrayImage, staves: List<Staff>, index: Int, maxH: Int = 256, maxW: Int = 1280): GrayImage {
        val s = staves[index]
        val u = s.unit
        val staffH = 4 * u
        // Komşu portelere taşmayacak kenar payları (homr: 4 birim)
        fun gapAbove(x: Double) = if (index > 0) s.top(x) - (staves[index - 1].bottom(x) + u) else Double.MAX_VALUE
        fun gapBelow(x: Double) = if (index + 1 < staves.size) (staves[index + 1].top(x) - u) - s.bottom(x) else Double.MAX_VALUE
        val xa = maxOf(0.0, s.x0 - 2 * u); val xb = minOf(img.width - 1.0, s.x1 + 2 * u)
        var mTop = 4 * u; var mBot = 4 * u
        var x = xa
        while (x <= xb) {
            val scale = (s.bottom(x) - s.top(x)) / staffH
            mTop = minOf(mTop, gapAbove(x) / scale); mBot = minOf(mBot, gapBelow(x) / scale); x += 8
        }
        mTop = mTop.coerceAtLeast(u); mBot = mBot.coerceAtLeast(u)
        val outW = (xb - xa).roundToInt().coerceAtLeast(1)
        val outH = (mTop + staffH + mBot).roundToInt().coerceAtLeast(1)
        val flat = ByteArray(outW * outH)
        for (cx in 0 until outW) {
            val sx = xa + cx
            val t = s.top(sx); val scale = (s.bottom(sx) - t) / staffH
            for (ry in 0 until outH) flat[ry * outW + cx] = img.sample(sx, t + (ry - mTop) * scale).toByte()
        }
        val crop = GrayImage(outW, outH, flat)
        val (nw, nh) = if (crop.height.toDouble() / crop.width > maxH.toDouble() / maxW)
            (crop.width.toDouble() / crop.height * maxH).toInt() to maxH
        else maxW to (crop.height.toDouble() / crop.width * maxW).toInt()
        val r = crop.resized(maxOf(1, nw), maxOf(1, nh))
        val canvas = ByteArray(maxH * maxW) { 255.toByte() }
        val yo = (maxH - r.height) / 2
        for (yy in 0 until r.height) System.arraycopy(r.pixels, yy * r.width, canvas, (yo + yy) * maxW, r.width)
        return GrayImage(maxW, maxH, canvas)
    }
}
