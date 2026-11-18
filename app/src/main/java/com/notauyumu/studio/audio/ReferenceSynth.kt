package com.notauyumu.studio.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.notauyumu.studio.model.NoteEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

class ReferenceSynth {
    private val sampleRate = 48000
    private var playJob: Job? = null
    private var guideJob: Job? = null

    fun stopAll() {
        playJob?.cancel()
        playJob = null
        guideJob?.cancel()
        guideJob = null
    }

    fun playTone(midi: Int, durationSec: Float = 1.2f) {
        playJob?.cancel()
        playJob = CoroutineScope(Dispatchers.IO).launch {
            playPcmStream(YinDetector.midiToFrequency(midi), durationSec, amplitude = 0.40)
        }
    }

    fun playMetronomeClick(accent: Boolean) {
        CoroutineScope(Dispatchers.IO).launch {
            val freq = if (accent) 1100.0 else 820.0
            playPcmStream(freq, 0.06f, amplitude = if (accent) 0.35 else 0.22)
        }
    }

    fun playFirstNote(notes: List<NoteEvent>, transposeOctaves: Int) {
        val first = notes.firstOrNull { !it.isRest && it.midi != null } ?: return
        val midi = (first.midi ?: return) + transposeOctaves * 12
        playTone(midi, 1.3f)
    }

    fun playPart(
        notes: List<NoteEvent>,
        bpm: Int,
        transposeOctaves: Int,
        amplitude: Double = 0.38,
        onBeatProgress: ((Float) -> Unit)? = null,
        onComplete: (() -> Unit)? = null
    ) {
        stopAll()
        val secPerBeat = 60f / bpm
        playJob = CoroutineScope(Dispatchers.IO).launch {
            for (n in notes) {
                if (!isActive) break
                onBeatProgress?.invoke(n.startBeat)
                val durSec = (n.durationBeats * secPerBeat).coerceAtLeast(0.12f)
                if (!n.isRest && n.midi != null) {
                    playPcmStream(
                        YinDetector.midiToFrequency(n.midi + transposeOctaves * 12),
                        durSec * 0.92f,
                        amplitude = amplitude
                    )
                    val gapMs = (durSec * 0.08f * 1000).toLong()
                    if (gapMs > 0) delay(gapMs)
                } else {
                    delay((durSec * 1000).toLong())
                }
            }
            if (isActive) {
                onBeatProgress?.invoke(0f)
                onComplete?.invoke()
            }
        }
    }

    fun startGuideDuringRecording(notes: List<NoteEvent>, bpm: Int, transposeOctaves: Int) {
        guideJob?.cancel()
        val secPerBeat = 60f / bpm
        guideJob = CoroutineScope(Dispatchers.IO).launch {
            for (n in notes) {
                if (!isActive) break
                val durSec = (n.durationBeats * secPerBeat).coerceAtLeast(0.12f)
                if (!n.isRest && n.midi != null) {
                    playPcmStream(
                        YinDetector.midiToFrequency(n.midi + transposeOctaves * 12),
                        durSec * 0.90f,
                        amplitude = 0.16
                    )
                    val gapMs = (durSec * 0.10f * 1000).toLong()
                    if (gapMs > 0) delay(gapMs)
                } else {
                    delay((durSec * 1000).toLong())
                }
            }
        }
    }

    /**
     * 48 kHz MODE_STREAM AudioTrack ile tüm Android cihazlarda kaymasız ve temiz frekans üretimi.
     */
    private suspend fun playPcmStream(freq: Double, durationSec: Float, amplitude: Double) {
        val totalSamples = (sampleRate * durationSec).toInt().coerceAtLeast(1)
        val pcm = ShortArray(totalSamples)
        val attackSamples = min(totalSamples / 4, (sampleRate * 0.020).toInt())
        val releaseSamples = min(totalSamples / 3, (sampleRate * 0.060).toInt())

        for (i in 0 until totalSamples) {
            val env = when {
                i < attackSamples -> i.toDouble() / attackSamples
                i > totalSamples - releaseSamples -> (totalSamples - i).toDouble() / releaseSamples
                else -> 1.0
            }
            val phase = 2.0 * PI * freq * i / sampleRate
            // Saf sinüs ağırlıklı net referans tonu (perde karışıklığı yapmaz)
            val sample = sin(phase) * amplitude * env
            pcm[i] = (sample * Short.MAX_VALUE).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }

        val minBufBytes = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufBytes = maxOf(minBufBytes, 4096)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        try {
            track.play()
            var offset = 0
            val chunkSize = 2048
            while (offset < totalSamples) {
                val count = min(chunkSize, totalSamples - offset)
                val written = track.write(pcm, offset, count)
                if (written <= 0) break
                offset += written
            }
            delay(40L)
        } finally {
            try {
                track.stop()
                track.release()
            } catch (_: Exception) {
            }
        }
    }
}
