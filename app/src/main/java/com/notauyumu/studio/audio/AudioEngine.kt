package com.notauyumu.studio.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioAttributes
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
    private var echoCanceler: AcousticEchoCanceler? = null
    private var captureJob: Job? = null

    private val recordedPcm = ByteArrayOutputStream()
    private val pcmLock = Any()

    @Volatile
    var rmsThreshold: Float = 0.015f

    @Volatile
    private var suppressUntilMs: Long = 0L

    fun suppressFor(durationMs: Long) {
        val target = System.currentTimeMillis() + durationMs
        if (target > suppressUntilMs) {
            suppressUntilMs = target
        }
    }

    private var playbackJob: Job? = null

    fun hasRecordedAudio(): Boolean = synchronized(pcmLock) { recordedPcm.size() > 0 }

    fun playRecordedAudio() {
        val bytes = synchronized(pcmLock) { recordedPcm.toByteArray() }
        if (bytes.isEmpty()) return
        playbackJob?.cancel()
        playbackJob = CoroutineScope(Dispatchers.IO).launch {
            val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
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
                .setBufferSizeInBytes(maxOf(minBuf, 4096))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            try {
                track.play()
                var offset = 0
                while (offset < bytes.size && isActive) {
                    val chunk = minOf(4096, bytes.size - offset)
                    val written = track.write(bytes, offset, chunk)
                    if (written <= 0) break
                    offset += written
                }
            } finally {
                try {
                    track.stop()
                    track.release()
                } catch (_: Exception) {
                }
            }
        }
    }

    /**
     * Kaydedilen ham sesi standart 44.1 kHz 16-bit mono WAV dosyası olarak dışa aktarır.
     */
    fun exportWavToUri(context: Context, uri: Uri) {
        val pcmBytes = synchronized(pcmLock) { recordedPcm.toByteArray() }
        if (pcmBytes.isEmpty()) throw IllegalStateException("Dışa aktarılacak kayıt bulunamadı.")

        context.contentResolver.openOutputStream(uri)?.use { out ->
            writeWavHeader(out, pcmBytes.size, sampleRate, 1, 16)
            out.write(pcmBytes)
        } ?: throw IllegalStateException("Hedef dosya açılamadı.")
    }

    private fun writeWavHeader(out: OutputStream, pcmDataSize: Int, sr: Int, channels: Int, bitsPerSample: Int) {
        val byteRate = sr * channels * bitsPerSample / 8
        val blockAlign = (channels * bitsPerSample / 8).toShort()
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + pcmDataSize)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1) // PCM
        header.putShort(channels.toShort())
        header.putInt(sr)
        header.putInt(byteRate)
        header.putShort(blockAlign)
        header.putShort(bitsPerSample.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(pcmDataSize)
        out.write(header.array())
    }

    @SuppressLint("MissingPermission")
    fun start(
        context: Context,
        forceBuiltInMic: Boolean = true,
        onSample: (LivePitchSample) -> Unit
    ) {
        stop()
        synchronized(pcmLock) {
            recordedPcm.reset()
        }

        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(minBuf, frameSize * 2)

        val audioSource = if (forceBuiltInMic) {
            MediaRecorder.AudioSource.MIC
        } else {
            MediaRecorder.AudioSource.VOICE_COMMUNICATION
        }

        val record = AudioRecord(
            audioSource,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("Mikrofon başlatılamadı.")
        }

        if (forceBuiltInMic) {
            try {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                val builtInMic = am?.getDevices(AudioManager.GET_DEVICES_INPUTS)
                    ?.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
                if (builtInMic != null) {
                    record.preferredDevice = builtInMic
                }
            } catch (_: Exception) {
            }
        } else if (AcousticEchoCanceler.isAvailable()) {
            try {
                echoCanceler = AcousticEchoCanceler.create(record.audioSessionId)?.apply {
                    enabled = true
                }
            } catch (_: Exception) {
            }
        }

        audioRecord = record
        record.startRecording()

        captureJob = CoroutineScope(Dispatchers.Default).launch {
            val shortBuf = ShortArray(frameSize)
            val floatBuf = FloatArray(frameSize)
            val byteBuf = ByteBuffer.allocate(frameSize * 2).order(ByteOrder.LITTLE_ENDIAN)

            while (isActive) {
                val read = record.read(shortBuf, 0, frameSize)
                if (read == frameSize) {
                    // Ham PCM kaydını dışa aktarma için biriktir
                    byteBuf.clear()
                    for (i in 0 until frameSize) {
                        byteBuf.putShort(shortBuf[i])
                    }
                    synchronized(pcmLock) {
                        if (recordedPcm.size() < 44100 * 2 * 300) { // Maks ~5 dk koruma
                            recordedPcm.write(byteBuf.array())
                        }
                    }

                    if (System.currentTimeMillis() < suppressUntilMs) {
                        onSample(LivePitchSample(null, null, null, 0f, 0f))
                        continue
                    }

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
        playbackJob?.cancel()
        playbackJob = null
        try {
            echoCanceler?.release()
        } catch (_: Exception) {
        }
        echoCanceler = null
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
