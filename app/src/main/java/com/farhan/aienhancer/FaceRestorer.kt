package com.farhan.aienhancer

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * GFPGAN v1.4 (face restoration) ONNX Runtime par, poori tarah on-device (CPU).
 * Input/output: 1x3x512x512, RGB, range [-1, 1]. Model ~170 MB (fp16 weights), RAM ~1 GB chalte waqt.
 * Kaam khatam hone par [close] zaroor karein taake RAM wapas aa jaye.
 */
class FaceRestorer(modelPath: String, threads: Int) : AutoCloseable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String

    @Volatile
    private var runOptions: OrtSession.RunOptions? = null

    @Volatile
    private var cancelled = false

    init {
        val opts = OrtSession.SessionOptions()
        try {
            opts.setIntraOpNumThreads(threads.coerceIn(1, 6))
            opts.setInterOpNumThreads(1)
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            session = env.createSession(modelPath, opts)
        } finally {
            opts.close()
        }
        inputName = session.inputNames.first()
    }

    /** Chal rahi inference ko rok deta hai ([restore] mein exception aayegi). */
    fun cancel() {
        cancelled = true
        try {
            runOptions?.setTerminate(true)
        } catch (_: Throwable) {
        }
    }

    /**
     * [face] = 512x512 aligned crop. @return 512x512 restored pixels (ARGB int array, alpha 255).
     * Cancel hone par null.
     */
    fun restore(face: IntArray): IntArray? {
        if (cancelled) return null
        val n = S * S
        val buf = ByteBuffer.allocateDirect(4 * 3 * n).order(ByteOrder.nativeOrder()).asFloatBuffer()
        // NCHW, RGB, (x/255 - 0.5) / 0.5
        for (i in 0 until n) {
            val p = face[i]
            buf.put(i, (((p shr 16) and 255) / 127.5f) - 1f)
            buf.put(n + i, (((p shr 8) and 255) / 127.5f) - 1f)
            buf.put(2 * n + i, ((p and 255) / 127.5f) - 1f)
        }
        buf.rewind()

        val ro = OrtSession.RunOptions()
        runOptions = ro
        val tensor = OnnxTensor.createTensor(env, buf, longArrayOf(1, 3, S.toLong(), S.toLong()))
        try {
            if (cancelled) return null
            session.run(mapOf(inputName to tensor), ro).use { result ->
                val out = (result[0] as OnnxTensor).floatBuffer
                val arr = FloatArray(3 * n)
                out.rewind()
                out.get(arr)
                val px = IntArray(n)
                for (i in 0 until n) {
                    px[i] = (0xFF shl 24) or
                        (toByte(arr[i]) shl 16) or
                        (toByte(arr[n + i]) shl 8) or
                        toByte(arr[2 * n + i])
                }
                return px
            }
        } catch (t: Throwable) {
            if (cancelled) return null
            throw t
        } finally {
            tensor.close()
            ro.close()
            runOptions = null
        }
    }

    override fun close() {
        try {
            session.close()
        } catch (_: Throwable) {
        }
    }

    private fun toByte(v: Float): Int {
        val x = (v * 0.5f + 0.5f) * 255f + 0.5f
        return if (!(x > 0f)) 0 else if (x >= 255f) 255 else x.toInt()
    }

    companion object {
        const val S = FaceAlign.SIZE

        /** Bitmap -> ARGB int array (512x512). */
        fun pixelsOf(bmp: Bitmap): IntArray {
            val px = IntArray(bmp.width * bmp.height)
            bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            return px
        }
    }
}
