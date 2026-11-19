package com.notauyumu.studio.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.notauyumu.core.ScoreFormatException
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Telefonun kendi çözücüleriyle (MP3, M4A/AAC, OGG, WAV, FLAC…) sesi mono 22.05 kHz'e çevirir. */
object AudioDecoder {
    const val TARGET_RATE = 22050
    private const val MAX_SECONDS = 12 * 60

    fun decode(context: Context, uri: Uri, progress: (Float) -> Unit = {}): FloatArray {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, uri, null)
        } catch (e: Exception) {
            throw ScoreFormatException("Ses dosyası açılamadı.")
        }
        try {
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: throw ScoreFormatException("Dosyada ses bulunamadı.")
            ex.selectTrack(track)
            val format = ex.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            if (durationUs > MAX_SECONDS * 1_000_000L) throw ScoreFormatException("Kayıt çok uzun (en fazla 12 dakika).")
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var floatPcm = false

            val codec = try { MediaCodec.createDecoderByType(mime) } catch (e: Exception) { throw ScoreFormatException("Bu ses biçimi telefonda çözülemiyor.") }
            codec.configure(format, null, null, 0)
            codec.start()
            val out = FloatList()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            try {
                while (!outputDone) {
                    if (!inputDone) {
                        val inIdx = codec.dequeueInputBuffer(10_000)
                        if (inIdx >= 0) {
                            val buf = codec.getInputBuffer(inIdx)!!
                            val n = ex.readSampleData(buf, 0)
                            if (n < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIdx, 0, n, ex.sampleTime, 0)
                                if (durationUs > 0) progress((ex.sampleTime.toDouble() / durationUs).toFloat().coerceIn(0f, 1f))
                                ex.advance()
                            }
                        }
                    }
                    val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = codec.outputFormat
                            rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        }
                        outIdx >= 0 -> {
                            val buf = codec.getOutputBuffer(outIdx)!!.order(ByteOrder.nativeOrder())
                            buf.position(info.offset); buf.limit(info.offset + info.size)
                            if (floatPcm) {
                                val fb = buf.asFloatBuffer()
                                while (fb.remaining() >= channels) { var s = 0f; repeat(channels) { s += fb.get() }; out.add(s / channels) }
                            } else {
                                val sb = buf.asShortBuffer()
                                while (sb.remaining() >= channels) { var s = 0f; repeat(channels) { s += sb.get() / 32768f }; out.add(s / channels) }
                            }
                            codec.releaseOutputBuffer(outIdx, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                            if (out.size > (MAX_SECONDS + 5) * rate) outputDone = true
                        }
                    }
                }
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
            if (out.size < rate) throw ScoreFormatException("Kayıt çok kısa.")
            return resample(out.toArray(), rate, TARGET_RATE)
        } finally {
            ex.release()
        }
    }

    /** Kutu süzgeci (örtüşme önleme) + doğrusal ara değerleme. */
    fun resample(x: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to) return x
        val src = if (from > to * 1.4) {
            val k = (from.toDouble() / to).roundToInt().coerceAtLeast(2)
            val y = FloatArray(x.size)
            var acc = 0f
            for (i in x.indices) {
                acc += x[i]
                if (i >= k) acc -= x[i - k]
                y[i] = acc / minOf(i + 1, k)
            }
            y
        } else x
        val n = (x.size.toLong() * to / from).toInt()
        val ratio = from.toDouble() / to
        return FloatArray(n) { i ->
            val p = i * ratio
            val a = p.toInt()
            val f = (p - a).toFloat()
            if (a + 1 < src.size) src[a] * (1 - f) + src[a + 1] * f else src[minOf(a, src.size - 1)]
        }
    }

    private class FloatList {
        private var data = FloatArray(1 shl 16)
        var size = 0; private set
        fun add(v: Float) { if (size == data.size) data = data.copyOf(size * 2); data[size++] = v }
        fun toArray(): FloatArray = data.copyOf(size)
    }
}
