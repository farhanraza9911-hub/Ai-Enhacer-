package com.farhan.aienhancer

import android.content.res.AssetManager
import android.graphics.Bitmap

/** Native code har tile ke baad ye call karta hai (worker thread par, UI thread par nahi). */
fun interface ProgressListener {
    fun onProgress(done: Int, total: Int)
}

/** Real-ESRGAN (ncnn + Vulkan) ka JNI bridge. Implementation: cpp/enhancer_jni.cpp */
class NativeEnhancer {

    /** @return 1 = GPU (Vulkan), 0 = CPU, -1 = model load nahi hua */
    external fun load(assets: AssetManager, model: String, scale: Int, useGpu: Boolean): Int

    /**
     * [input] aur [output] dono ARGB_8888 hon, aur output ki size = input ki size x scale.
     * @return 0 = ok, 1 = cancelled, negative = error
     */
    external fun process(input: Bitmap, output: Bitmap, tile: Int, listener: ProgressListener?): Int

    /**
     * "Look" layer (sab 0..100): [strength] = AI result kitna (baqi [orig] se mix), [smooth] = skin smoothing,
     * [vivid] = colour/contrast, [clarity] = local contrast.
     * [enh] aur [out] alag bitmaps hon, same size. [orig] chhota original ho sakta hai (ya null).
     * @return 0 = ok
     */
    external fun adjust(
        enh: Bitmap, orig: Bitmap?, out: Bitmap,
        strength: Int, smooth: Int, vivid: Int, clarity: Int
    ): Int

    external fun cancel()

    external fun isGpuActive(): Boolean

    external fun release()

    companion object {
        init {
            System.loadLibrary("enhancer")
        }
    }
}
