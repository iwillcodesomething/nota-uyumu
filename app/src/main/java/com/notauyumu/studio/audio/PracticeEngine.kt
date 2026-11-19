package com.notauyumu.studio.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Process
import com.notauyumu.core.Arrangement
import com.notauyumu.core.MedianSmoother
import com.notauyumu.core.MixSettings
import com.notauyumu.core.Pitch
import com.notauyumu.core.PitchSample
import com.notauyumu.core.SynthRenderer
import com.notauyumu.core.YinDetector
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Bir çalışma oturumu: sentezlenmiş eşliği çalar, istenirse aynı anda mikrofonu dinler.
 *
 * Saat kaynağı AudioTrack'in oynatma kafasıdır; mikrofon ölçümleri bu saate göre
 * zaman damgası alır, böylece ses ve görüntü aynı zaman çizgisinde kalır.
 */
class PracticeEngine(
    private val arrangement: Arrangement,
    private val mix: MixSettings,
    private val listenToMic: Boolean,
    /** Mikrofon + çıkış gecikmesi telafisi. */
    private val latencySec: Double,
) {
    companion object {
        const val OUT_RATE = 44100
        const val IN_RATE = 44100
        private const val ANALYSIS_RATE = IN_RATE / 2
        private const val WINDOW = 1024 // 22.05 kHz'te ≈46 ms
        private const val HOP = 512
    }

    val samples = ConcurrentLinkedQueue<PitchSample>()

    @Volatile var failure: String? = null; private set
    @Volatile var finished = false; private set
    @Volatile private var running = false

    private var track: AudioTrack? = null
    private var playThread: Thread? = null
    private var micThread: Thread? = null
    @Volatile private var framesWritten = 0L
    @Volatile private var lastHead = 0L

    /** Eser zamanı (saniye). Ön sayım sırasında başlangıçtan küçüktür. */
    fun scoreTimeSec(): Double = playbackSec() + arrangement.scoreOffsetSec

    private fun playbackSec(): Double {
        val t = track ?: return 0.0
        val head = runCatching { t.playbackHeadPosition.toLong() and 0xFFFFFFFFL }.getOrDefault(lastHead)
        lastHead = head
        return head.toDouble() / OUT_RATE
    }

    fun start() {
        running = true
        val minBuf = AudioTrack.getMinBufferSize(OUT_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val t = try {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(OUT_RATE).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(maxOf(minBuf, 4096 * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            failure = "Ses çıkışı açılamadı."
            finished = true
            return
        }
        track = t
        playThread = Thread({ playLoop(t) }, "nu-play").apply { start() }
        if (listenToMic) micThread = Thread({ micLoop() }, "nu-mic").apply { start() }
    }

    fun stop() {
        running = false
        runCatching { playThread?.join(500) }
        runCatching { micThread?.join(500) }
        runCatching { track?.pause(); track?.flush(); track?.release() }
        track = null
    }

    private fun playLoop(t: AudioTrack) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val renderer = SynthRenderer(OUT_RATE, arrangement, mix)
        val buf = FloatArray(1024)
        var more = true
        try {
            t.play()
            while (running && more) {
                more = renderer.render(buf)
                var off = 0
                while (off < buf.size && running) {
                    val w = t.write(buf, off, buf.size - off, AudioTrack.WRITE_BLOCKING)
                    if (w < 0) throw IllegalStateException("write $w")
                    off += w
                }
                framesWritten += buf.size
            }
            // Arabellekteki son seslerin çalınmasını bekle
            while (running && (t.playbackHeadPosition.toLong() and 0xFFFFFFFFL) < framesWritten - 256) Thread.sleep(15)
        } catch (e: Exception) {
            if (running) failure = "Ses çalma sırasında hata oluştu."
        } finally {
            finished = true
        }
    }

    @SuppressLint("MissingPermission") // İzin, oturum başlamadan önce arayüzde alınır.
    private fun micLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val minBuf = AudioRecord.getMinBufferSize(IN_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, IN_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, 8192))
        } catch (e: Exception) { null }
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            failure = "Mikrofon açılamadı. Başka bir uygulama kullanıyor olabilir."
            rec?.release()
            return
        }
        val yin = YinDetector(ANALYSIS_RATE, WINDOW)
        val smoother = MedianSmoother(5)
        val raw = ShortArray(HOP * 2)
        val window = FloatArray(WINDOW)
        val windowSec = WINDOW.toDouble() / ANALYSIS_RATE
        try {
            rec.startRecording()
            while (running && !finished) {
                var got = 0
                while (got < raw.size && running) {
                    val r = rec.read(raw, got, raw.size - got)
                    if (r <= 0) break
                    got += r
                }
                if (got < raw.size) continue
                // Kaydır + 2'ye seyrelt (44.1 → 22.05 kHz, basit alçak geçiren ortalama)
                System.arraycopy(window, HOP, window, 0, WINDOW - HOP)
                for (i in 0 until HOP) window[WINDOW - HOP + i] = (raw[2 * i] + raw[2 * i + 1]) / 65536f
                val res = yin.detect(window)
                val midi = if (res.freq > 0 && res.confidence > 0.75) Pitch.freqToMidi(res.freq) else null
                val smoothed = smoother.push(midi)
                // Pencerenin ortası ≈ şimdi - pencere/2; bir de cihaz gecikmesi
                val t = scoreTimeSec() - windowSec / 2 - latencySec
                samples.add(PitchSample(t, smoothed))
            }
        } catch (e: Exception) {
            if (running) failure = "Mikrofon okunurken hata oluştu."
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
    }
}
