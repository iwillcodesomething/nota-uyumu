package com.notauyumu.studio.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.notauyumu.studio.model.ReferencePitchPoint
import java.nio.ByteOrder
import kotlin.math.abs

object ReferenceAudioAnalyzer {

    /**
     * Yüklenen referans ses kaydını (MP3, WAV, M4A, OGG) native MediaExtractor + MediaCodec ile çözer
     * ve YIN algoritmasıyla zaman-eksenli referans perde eğrisini (pitch contour) çıkarır.
     */
    fun analyzeReferenceAudio(
        context: Context,
        uri: Uri,
        rmsThreshold: Float = 0.012f
    ): List<ReferencePitchPoint> {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)

        var trackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) {
                trackIndex = i
                format = f
                break
            }
        }

        if (trackIndex == -1 || format == null) {
            extractor.release()
            throw IllegalStateException("Dosyada ses kanalı bulunamadı.")
        }

        extractor.selectTrack(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channelCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        } else 1

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val monoSamples = ArrayList<Float>(sampleRate * 30)
        val bufferInfo = MediaCodec.BufferInfo()
        var sawInputEOS = false
        var sawOutputEOS = false

        try {
            while (!sawOutputEOS) {
                if (!sawInputEOS) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val inputBuf = codec.getInputBuffer(inIdx)!!
                        val sampleSize = extractor.readSampleData(inputBuf, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEOS = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                if (outIdx >= 0) {
                    val outBuf = codec.getOutputBuffer(outIdx)!!
                    outBuf.order(ByteOrder.LITTLE_ENDIAN)
                    val shortCount = bufferInfo.size / 2
                    val shorts = ShortArray(shortCount)
                    outBuf.asShortBuffer().get(shorts)

                    var i = 0
                    while (i + channelCount <= shortCount) {
                        var sum = 0f
                        for (c in 0 until channelCount) {
                            sum += shorts[i + c] / 32768f
                        }
                        monoSamples.add(sum / channelCount)
                        i += channelCount
                    }

                    codec.releaseOutputBuffer(outIdx, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        sawOutputEOS = true
                    }
                }
            }
        } finally {
            codec.stop()
            codec.release()
            extractor.release()
        }

        // Kayan pencere (2048 örneklem, her 25ms'de bir adım) ile YIN perde analizi
        val windowSize = 2048
        val hopSize = (sampleRate * 0.025f).toInt().coerceAtLeast(256)
        val contour = mutableListOf<ReferencePitchPoint>()
        val windowBuf = FloatArray(windowSize)

        var pos = 0
        while (pos + windowSize <= monoSamples.size) {
            for (k in 0 until windowSize) {
                windowBuf[k] = monoSamples[pos + k]
            }
            val yin = YinDetector.detectPitch(windowBuf, sampleRate, rmsThreshold)
            val timeSec = pos.toFloat() / sampleRate
            val midiFloat = yin.frequency?.let { YinDetector.frequencyToMidiFloat(it) }
            contour.add(
                ReferencePitchPoint(
                    timeSec = timeSec,
                    frequency = yin.frequency,
                    midiFloat = midiFloat
                )
            )
            pos += hopSize
        }

        return contour
    }

    /**
     * Belirli bir saniyedeki referans perdeyi (varsa) en yakın komşudan döndürür.
     */
    fun getReferenceMidiAtTime(contour: List<ReferencePitchPoint>, timeSec: Float): Float? {
        if (contour.isEmpty()) return null
        var low = 0
        var high = contour.size - 1
        while (low < high) {
            val mid = (low + high) / 2
            if (contour[mid].timeSec < timeSec) low = mid + 1 else high = mid
        }
        val cand = contour[low]
        return if (abs(cand.timeSec - timeSec) <= 0.08f) cand.midiFloat else null
    }
}
