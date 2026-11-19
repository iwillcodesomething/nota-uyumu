package com.notauyumu.core.transcribe

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.nio.FloatBuffer

/**
 * Basic Pitch ONNX modelini (nmp.onnx, Apache-2.0, Spotify) ONNX Runtime ile çalıştırır.
 * Android'de `onnxruntime-android`, JVM testlerinde `onnxruntime` aynı API'yi sağlar.
 */
class OrtBasicPitchModel(modelBytes: ByteArray) : BasicPitch.Model, Closeable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(modelBytes, OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(2)
    })
    private val inputName = session.inputNames.first()

    // Özgün koddaki eşleme: note = StatefulPartitionedCall:1, onset = :2, contour = :0
    private val noteName = "StatefulPartitionedCall:1"
    private val onsetName = "StatefulPartitionedCall:2"

    override fun run(window: FloatArray): BasicPitch.WindowOutput {
        OnnxTensor.createTensor(env, FloatBuffer.wrap(window), longArrayOf(1, BasicPitch.AUDIO_N_SAMPLES.toLong(), 1)).use { input ->
            session.run(mapOf(inputName to input), setOf(noteName, onsetName)).use { res ->
                return BasicPitch.WindowOutput(read(res, noteName), read(res, onsetName))
            }
        }
    }

    private fun read(res: OrtSession.Result, name: String): FloatArray {
        val t = res.get(name).orElseThrow { IllegalStateException("Model çıktısı yok: $name") } as OnnxTensor
        val buf = t.floatBuffer
        val arr = FloatArray(buf.remaining())
        buf.get(arr)
        return arr
    }

    override fun close() {
        session.close()
    }
}
