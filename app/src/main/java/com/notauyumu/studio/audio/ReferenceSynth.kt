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
    private val sampleRate = 44100
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
        playJob = CoroutineScope(Dispatchers.Default).launch {
            playPcmTone(YinDetector.midiToFrequency(midi), durationSec)
        }
    }

    fun playMetronomeClick(accent: Boolean) {
        CoroutineScope(Dispatchers.Default).launch {
            val freq = if (accent) 1200.0 else 880.0
            playPcmTone(freq, 0.08f, amplitude = if (accent) 0.40 else 0.24)
        }
    }

    fun playFirstNote(notes: List<NoteEvent>, transposeOctaves: Int) {
        val first = notes.firstOrNull { !it.isRest && it.midi != null } ?: return
        val midi = (first.midi ?: return) + transposeOctaves * 12
        playTone(midi, 1.4f)
    }

    fun playPart(
        notes: List<NoteEvent>,
        bpm: Int,
        transposeOctaves: Int,
        amplitude: Double = 0.35,
        onBeatProgress: ((Float) -> Unit)? = null,
        onComplete: (() -> Unit)? = null
    ) {
        stopAll()
        val secPerBeat = 60f / bpm
        playJob = CoroutineScope(Dispatchers.Default).launch {
            for (n in notes) {
                if (!isActive) break
                onBeatProgress?.invoke(n.startBeat)
                val durSec = (n.durationBeats * secPerBeat).coerceAtLeast(0.12f)
                if (!n.isRest && n.midi != null) {
                    playPcmTone(
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

    /**
     * Kayıt sırasında kullanıcı söylerken eşzamanlı olarak seçili partiyi rehber ses olarak çalar.
     */
    fun startGuideDuringRecording(notes: List<NoteEvent>, bpm: Int, transposeOctaves: Int) {
        guideJob?.cancel()
        val secPerBeat = 60f / bpm
        guideJob = CoroutineScope(Dispatchers.Default).launch {
            for (n in notes) {
                if (!isActive) break
                val durSec = (n.durationBeats * secPerBeat).coerceAtLeast(0.12f)
                if (!n.isRest && n.midi != null) {
                    playPcmTone(
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

    private suspend fun playPcmTone(freq: Double, durationSec: Float, amplitude: Double = 0.35) {
        val numSamples = (sampleRate * durationSec).toInt().coerceAtLeast(1)
        val pcm = ShortArray(numSamples)
        val attackSamples = min(numSamples / 4, (sampleRate * 0.025).toInt())
        val releaseSamples = min(numSamples / 3, (sampleRate * 0.08).toInt())

        for (i in 0 until numSamples) {
            val env = when {
                i < attackSamples -> i.toDouble() / attackSamples
                i > numSamples - releaseSamples -> (numSamples - i).toDouble() / releaseSamples
                else -> 1.0
            }
            val phase = 2.0 * PI * freq * i / sampleRate
            val sample = (sin(phase) * 0.85 + sin(phase * 2.0) * 0.15) * amplitude * env
            pcm[i] = (sample * Short.MAX_VALUE).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }

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
            .setBufferSizeInBytes(pcm.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        try {
            track.write(pcm, 0, pcm.size)
            track.play()
            delay((durationSec * 1000).toLong() + 10L)
        } finally {
            try {
                track.stop()
                track.release()
            } catch (_: Exception) {
            }
        }
    }
}
