package com.notauyumu.studio.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.notauyumu.studio.model.NoteEvent
import com.notauyumu.studio.model.ReferencePitchPoint
import com.notauyumu.studio.model.ReferenceVoiceFilter
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object ReferenceAudioAnalyzer {

    /**
     * Yüklenen referans ses kaydını (MP3, WAV, M4A, OGG) çözer.
     * voiceFilter == SCORE_GUIDED ise seçili PDF/MusicXML partisinin o andaki beklenen notası
     * etrafında (±3.5 yarım ton) dinamik Biquad bant geçiren filtre uygulayarak o partiyi kayıttan ayırır.
     */
    fun analyzeReferenceAudio(
        context: Context,
        uri: Uri,
        rmsThreshold: Float = 0.012f,
        voiceFilter: ReferenceVoiceFilter = ReferenceVoiceFilter.ALL,
        guideNotes: List<NoteEvent> = emptyList(),
        bpm: Int = 80,
        transposeOctaves: Int = 0
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

        var startIdx = 0
        val trimThresh = (rmsThreshold * 0.9f).coerceAtLeast(0.008f)
        while (startIdx < monoSamples.size - 1024) {
            var s = 0f
            for (k in 0 until 512) {
                val v = monoSamples[startIdx + k]
                s += v * v
            }
            if (kotlin.math.sqrt(s / 512f) >= trimThresh) break
            startIdx += 256
        }
        val trimmedSize = (monoSamples.size - startIdx).coerceAtLeast(1)
        val rawSignal = FloatArray(trimmedSize) { monoSamples[startIdx + it] }
        val filteredSignal = rawSignal.copyOf()

        if (voiceFilter != ReferenceVoiceFilter.ALL && voiceFilter != ReferenceVoiceFilter.SCORE_GUIDED) {
            applyBiquadHighPass(filteredSignal, sampleRate.toDouble(), voiceFilter.minFreq.toDouble())
            applyBiquadLowPass(filteredSignal, sampleRate.toDouble(), voiceFilter.maxFreq.toDouble())
            applyBiquadLowPass(filteredSignal, sampleRate.toDouble(), voiceFilter.maxFreq.toDouble())
        }

        val windowSize = 2048
        val hopSize = (sampleRate * 0.025f).toInt().coerceAtLeast(256)
        val contour = mutableListOf<ReferencePitchPoint>()
        val windowBuf = FloatArray(windowSize)

        var pos = 0
        while (pos + windowSize <= rawSignal.size) {
            val timeSec = pos.toFloat() / sampleRate
            var minF = voiceFilter.minFreq
            var maxF = voiceFilter.maxFreq

            if (voiceFilter == ReferenceVoiceFilter.SCORE_GUIDED && guideNotes.isNotEmpty()) {
                val totalScoreBeats = guideNotes.lastOrNull()?.let { it.startBeat + it.durationBeats } ?: 16f
                val totalAudioSec = (rawSignal.size.toFloat() / sampleRate).coerceAtLeast(1f)
                // Tutti kaydın hızı PDF temposundan farklı olsa bile oransal hizalama + BPM hibrit eşleme
                val effectiveBpm = if (kotlin.math.abs((totalScoreBeats / (totalAudioSec / 60f)) - bpm) <= 35f) {
                    (totalScoreBeats / (totalAudioSec / 60f))
                } else bpm.toFloat()
                val beat = timeSec * (effectiveBpm / 60f)
                val note = guideNotes.find { beat >= it.startBeat && beat < it.startBeat + it.durationBeats }
                if (note == null || note.isRest || note.midi == null) {
                    contour.add(ReferencePitchPoint(timeSec, null, null))
                    pos += hopSize
                    continue
                }
                val expectedFreq = YinDetector.midiToFrequency(note.midi + transposeOctaves * 12).toFloat()
                // ±3.5 yarım tonluk dinamik bant geçiren pencere
                minF = (expectedFreq * 0.81f).coerceAtLeast(55f)
                maxF = (expectedFreq * 1.23f).coerceAtMost(1500f)

                for (k in 0 until windowSize) {
                    windowBuf[k] = rawSignal[pos + k]
                }
                applyBiquadHighPass(windowBuf, sampleRate.toDouble(), minF.toDouble())
                applyBiquadLowPass(windowBuf, sampleRate.toDouble(), maxF.toDouble())
                applyBiquadLowPass(windowBuf, sampleRate.toDouble(), maxF.toDouble())
            } else {
                for (k in 0 until windowSize) {
                    windowBuf[k] = filteredSignal[pos + k]
                }
            }

            val yin = YinDetector.detectPitch(
                buffer = windowBuf,
                sampleRate = sampleRate,
                rmsThreshold = rmsThreshold * 0.65f,
                minFreq = minF,
                maxFreq = maxF
            )
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

        // 5-noktalı medyan filtre ile anlık oktav sıçramalarını ve nefes gürültülerini temizle
        return contour.mapIndexed { idx, pt ->
            if (pt.midiFloat == null) pt
            else {
                val neighborMidis = (-2..2).mapNotNull { d ->
                    contour.getOrNull(idx + d)?.midiFloat
                }.sorted()
                if (neighborMidis.size >= 3) {
                    val medianMidi = neighborMidis[neighborMidis.size / 2]
                    pt.copy(midiFloat = medianMidi)
                } else pt
            }
        }
    }

    private fun applyBiquadLowPass(samples: FloatArray, sampleRate: Double, cutoffHz: Double) {
        val w0 = 2.0 * PI * cutoffHz / sampleRate
        val cosW0 = cos(w0)
        val alpha = sin(w0) / sqrt(2.0)
        val a0 = 1.0 + alpha
        val b0 = ((1.0 - cosW0) / 2.0) / a0
        val b1 = (1.0 - cosW0) / a0
        val b2 = b0
        val a1 = (-2.0 * cosW0) / a0
        val a2 = (1.0 - alpha) / a0
        runBiquad(samples, b0, b1, b2, a1, a2)
    }

    private fun applyBiquadHighPass(samples: FloatArray, sampleRate: Double, cutoffHz: Double) {
        val w0 = 2.0 * PI * cutoffHz / sampleRate
        val cosW0 = cos(w0)
        val alpha = sin(w0) / sqrt(2.0)
        val a0 = 1.0 + alpha
        val b0 = ((1.0 + cosW0) / 2.0) / a0
        val b1 = (-(1.0 + cosW0)) / a0
        val b2 = b0
        val a1 = (-2.0 * cosW0) / a0
        val a2 = (1.0 - alpha) / a0
        runBiquad(samples, b0, b1, b2, a1, a2)
    }

    private fun runBiquad(samples: FloatArray, b0: Double, b1: Double, b2: Double, a1: Double, a2: Double) {
        var x1 = 0.0
        var x2 = 0.0
        var y1 = 0.0
        var y2 = 0.0
        for (i in samples.indices) {
            val x0 = samples[i].toDouble()
            val y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1
            x1 = x0
            y2 = y1
            y1 = y0
            samples[i] = y0.toFloat()
        }
    }

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
