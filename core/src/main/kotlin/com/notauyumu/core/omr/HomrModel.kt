package com.notauyumu.core.omr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxValue
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.nio.FloatBuffer
import java.nio.LongBuffer

/** homr'un bir porteden çıkardığı tek sembol (ör. rhythm="note_4.", pitch="C4", lift="#"). */
data class OmrSymbol(val rhythm: String, val pitch: String, val lift: String, val position: String)

/** homr sözlüğü (homr/transformer/vocabulary.py'den dışa aktarıldı). */
class HomrVocab(json: String) {
    val rhythm: List<String>
    val pitch: List<String>
    val lift: List<String>
    val position: List<String>
    val maxHeight: Int
    val maxWidth: Int
    val maxSeqLen: Int
    val eos: Int
    val depth: Int
    val heads: Int
    val dim: Int
    val mean: Float
    val std: Float

    init {
        fun list(key: String): List<String> {
            val m = Regex("\"$key\"\\s*:\\s*\\[(.*?)]", RegexOption.DOT_MATCHES_ALL).find(json) ?: error("sözlükte $key yok")
            return Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(m.groupValues[1]).map { it.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\") }.toList()
        }
        fun num(key: String): Double = Regex("\"$key\"\\s*:\\s*([-0-9.eE]+)").find(json)!!.groupValues[1].toDouble()
        rhythm = list("rhythm"); pitch = list("pitch"); lift = list("lift"); position = list("position")
        maxHeight = num("max_height").toInt(); maxWidth = num("max_width").toInt(); maxSeqLen = num("max_seq_len").toInt()
        eos = num("eos").toInt(); depth = num("decoder_depth").toInt(); heads = num("decoder_heads").toInt(); dim = num("decoder_dim").toInt()
        mean = num("mean").toFloat(); std = num("std").toFloat()
    }

    companion object {
        fun bundled(): HomrVocab = HomrVocab(HomrVocab::class.java.getResourceAsStream("/homr/vocab.json")!!.bufferedReader().readText())
    }
}

/**
 * homr (AGPL-3.0, https://github.com/liebharc/homr) porte okuma modeli: ConvNeXt kodlayıcı +
 * transformer kod çözücü. `homr/transformer/staff2score.py` ve `decoder_inference.py`
 * dosyalarının Kotlin aktarımıdır (açgözlü çözümleme, anahtar/değer önbelleği).
 */
class HomrModel(encoderPath: String, decoderPath: String, val vocab: HomrVocab = HomrVocab.bundled()) : Closeable {
    private val env = OrtEnvironment.getEnvironment()
    private val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(4) }
    private val encoder = env.createSession(encoderPath, opts)
    private val decoder = env.createSession(decoderPath, opts)
    private val outNames = listOf("out_rhythms", "out_pitchs", "out_lifts", "out_positions", "out_articulations", "out_slurs")
    private val nCache = vocab.depth * 4

    fun read(staffImage: GrayImage): List<OmrSymbol> {
        require(staffImage.width == vocab.maxWidth && staffImage.height == vocab.maxHeight)
        val x = FloatArray(staffImage.pixels.size) { i -> ((staffImage.pixels[i].toInt() and 0xFF) / 255f - vocab.mean) / vocab.std }
        val context: FloatArray
        val ctxLen: Int
        OnnxTensor.createTensor(env, FloatBuffer.wrap(x), longArrayOf(1, 1, vocab.maxHeight.toLong(), vocab.maxWidth.toLong())).use { input ->
            encoder.run(mapOf("input" to input)).use { r ->
                val t = r.get(0) as OnnxTensor
                val shape = t.info.shape
                ctxLen = shape[1].toInt()
                val fb = t.floatBuffer
                context = FloatArray(fb.remaining()).also { fb.get(it) }
            }
        }
        val dimL = vocab.dim.toLong()
        val fullCtx = OnnxTensor.createTensor(env, FloatBuffer.wrap(context), longArrayOf(1, ctxLen.toLong(), dimL))
        val firstCtx = OnnxTensor.createTensor(env, FloatBuffer.wrap(context.copyOf(vocab.dim)), longArrayOf(1, 1, dimL))
        val headDim = (vocab.dim / vocab.heads).toLong()
        var caches: List<OnnxTensor> = List(nCache) { OnnxTensor.createTensor(env, FloatBuffer.allocate(0), longArrayOf(1, vocab.heads.toLong(), 0, headDim)) }
        var prevResult: OrtSession.Result? = null
        val symbols = ArrayList<OmrSymbol>()
        var r = 1L; var p = 0L; var l = 0L; var a = 0L; var s = 0L
        val outputs = outNames.toSet() + (0 until nCache).map { "cache_out$it" }
        try {
            for (step in 0 until vocab.maxSeqLen) {
                val small = listOf(r, p, l, a, s).map { v -> OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(v)), longArrayOf(1, 1)) }
                val len = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(step.toLong())), longArrayOf(1))
                val feed = HashMap<String, OnnxTensor>()
                feed["rhythms"] = small[0]; feed["pitchs"] = small[1]; feed["lifts"] = small[2]; feed["articulations"] = small[3]; feed["slurs"] = small[4]
                feed["context"] = if (step == 0) fullCtx else firstCtx
                feed["cache_len"] = len
                caches.forEachIndexed { i, c -> feed["cache_in$i"] = c }
                val res = decoder.run(feed, outputs)
                small.forEach { it.close() }; len.close()
                if (prevResult == null) caches.forEach { it.close() } // ilk boş önbellekler bizim
                prevResult?.close()
                prevResult = res
                caches = (0 until nCache).map { res.get("cache_out$it").get() as OnnxTensor }
                fun argmax(name: String): Long {
                    val fb = (res.get(name).get() as OnnxTensor).floatBuffer
                    var best = 0; var bv = Float.NEGATIVE_INFINITY
                    for (i in 0 until fb.remaining()) { val v = fb.get(i); if (v > bv) { bv = v; best = i } }
                    return best.toLong()
                }
                r = argmax("out_rhythms"); p = argmax("out_pitchs"); l = argmax("out_lifts")
                val pos = argmax("out_positions"); a = argmax("out_articulations"); s = argmax("out_slurs")
                if (r.toInt() == vocab.eos) break
                symbols += OmrSymbol(vocab.rhythm[r.toInt()], vocab.pitch[p.toInt()], vocab.lift[l.toInt()], vocab.position[pos.toInt()])
            }
        } finally {
            prevResult?.close()
            fullCtx.close(); firstCtx.close()
        }
        return symbols
    }

    override fun close() { encoder.close(); decoder.close() }

    @Suppress("unused") private fun OnnxValue.asTensor() = this as OnnxTensor
}
