package com.notauyumu.studio.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class LivePitchSample(
    val frequency: Float?,
    val midiFloat: Float?,
    val detectedNote: String?,
    val rms: Float,
    val probability: Float
)

class AudioEngine {
    private val sampleRate = 44100
    private val frameSize = 2048
    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null

    @Volatile
    var rmsThreshold: Float = 0.015f

    @SuppressLint("MissingPermission")
    fun start(onSample: (LivePitchSample) -> Unit) {
        stop()

        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(minBuf, frameSize * 2)

        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("Mikrofon başlatılamadı.")
        }

        audioRecord = record
        record.startRecording()

        captureJob = CoroutineScope(Dispatchers.Default).launch {
            val shortBuf = ShortArray(frameSize)
            val floatBuf = FloatArray(frameSize)

            while (isActive) {
                val read = record.read(shortBuf, 0, frameSize)
                if (read == frameSize) {
                    for (i in 0 until frameSize) {
                        floatBuf[i] = shortBuf[i] / 32768f
                    }
                    val yin = YinDetector.detectPitch(floatBuf, sampleRate, rmsThreshold)
                    val midiFloat = yin.frequency?.let { YinDetector.frequencyToMidiFloat(it) }
                    val noteName = midiFloat?.let { YinDetector.midiToNoteName(it) }

                    onSample(
                        LivePitchSample(
                            frequency = yin.frequency,
                            midiFloat = midiFloat,
                            detectedNote = noteName,
                            rms = yin.rms,
                            probability = yin.probability
                        )
                    )
                }
            }
        }
    }

    fun stop() {
        captureJob?.cancel()
        captureJob = null
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null
    }
}
