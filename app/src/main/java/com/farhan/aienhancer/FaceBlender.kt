package com.farhan.aienhancer

import android.graphics.Bitmap
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Restore kiye hue chehre ko target image mein wapas milata hai.
 * Target koi bhi size ho sakta hai (preview ya full) kyunke mapping source coordinates se chalti hai.
 *
 * Soft elliptical mask (chehra + baal, background nahi) use hota hai taake koi square seam/halo na aaye;
 * ellipse ke bahar target pixels bilkul untouched rehte hain.
 */
object FaceBlender {

    // Template (512) space mein mask ellipse; reference Python pipeline mein test ki hui values.
    private const val CX = 256.0
    private const val CY = 290.0
    private const val RX = 218.0
    private const val RY = 236.0
    private const val FEATHER = 0.28

    /**
     * @param target mutable ARGB_8888 bitmap, jis par chehre paste honge (in-place)
     * @param targetPerSrc target pixels / source pixels (e.g. result.width / input.width)
     * @param weight 0..1: restored chehre ka hissa (1 = poora GFPGAN, 0 = kuch nahi)
     */
    fun blend(target: Bitmap, targetPerSrc: Double, faces: List<RestoredFace>, weight: Float) {
        if (weight <= 0f || faces.isEmpty()) return
        for (f in faces) blendOne(target, targetPerSrc, f, weight)
    }

    private fun blendOne(target: Bitmap, f: Double, face: RestoredFace, weight: Float) {
        val toTarget = Affine.scale(f).compose(face.toSrc)    // template -> target
        val inv = toTarget.inverse()                            // target -> template

        // Ellipse ke bounding box ke corners se target par ROI
        val cx = doubleArrayOf(CX - RX, CX + RX, CX + RX, CX - RX)
        val cy = doubleArrayOf(CY - RY, CY - RY, CY + RY, CY + RY)
        var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        for (i in 0 until 4) {
            val x = toTarget.mapX(cx[i], cy[i])
            val y = toTarget.mapY(cx[i], cy[i])
            minX = min(minX, x); maxX = max(maxX, x)
            minY = min(minY, y); maxY = max(maxY, y)
        }
        val x0 = max(0, floor(minX).toInt() - 1)
        val y0 = max(0, floor(minY).toInt() - 1)
        val x1 = min(target.width, floor(maxX).toInt() + 2)
        val y1 = min(target.height, floor(maxY).toInt() + 2)
        val rw = x1 - x0
        val rh = y1 - y0
        if (rw <= 0 || rh <= 0) return

        // Chehre ko downscale target ke liye pehle 2x2 average se chhota karte hain (aliasing nahi hoti)
        var level = 0
        var k = toTarget.scale
        while (k < 0.75 && level < 4) {
            level++
            k *= 2.0
        }
        val src = face.level(level)
        val ss = FaceAlign.SIZE shr level
        val lscale = 1.0 / (1 shl level)

        val px = IntArray(rw * rh)
        target.getPixels(px, 0, rw, x0, y0, rw, rh)

        for (y in 0 until rh) {
            val ty = y0 + y + 0.5
            var u = inv.mapX(x0 + 0.5, ty)
            var v = inv.mapY(x0 + 0.5, ty)
            val du = inv.a
            val dv = inv.c
            val row = y * rw
            for (x in 0 until rw) {
                val ex = (u - CX) / RX
                val ey = (v - CY) / RY
                val r2 = ex * ex + ey * ey
                if (r2 < 1.0) {
                    var m = ((1.0 - sqrt(r2)) / FEATHER).coerceIn(0.0, 1.0)
                    m = m * m * (3.0 - 2.0 * m)
                    val a = (m * weight).toFloat()
                    if (a > 0.002f) {
                        // bilinear sample (pixel centres: coord - 0.5)
                        val fx = (u * lscale - 0.5).coerceIn(0.0, (ss - 1).toDouble())
                        val fy = (v * lscale - 0.5).coerceIn(0.0, (ss - 1).toDouble())
                        val ix = fx.toInt(); val iy = fy.toInt()
                        val wx = (fx - ix).toFloat(); val wy = (fy - iy).toFloat()
                        val ix1 = min(ix + 1, ss - 1); val iy1 = min(iy + 1, ss - 1)
                        val p00 = src[iy * ss + ix]; val p01 = src[iy * ss + ix1]
                        val p10 = src[iy1 * ss + ix]; val p11 = src[iy1 * ss + ix1]
                        val t = px[row + x]
                        var res = 0xFF shl 24
                        var shift = 16
                        while (shift >= 0) {
                            val c00 = (p00 shr shift) and 255; val c01 = (p01 shr shift) and 255
                            val c10 = (p10 shr shift) and 255; val c11 = (p11 shr shift) and 255
                            val sv = (c00 * (1 - wx) + c01 * wx) * (1 - wy) + (c10 * (1 - wx) + c11 * wx) * wy
                            val tv = (t shr shift) and 255
                            val nv = (tv + (sv - tv) * a + 0.5f).toInt().coerceIn(0, 255)
                            res = res or (nv shl shift)
                            shift -= 8
                        }
                        px[row + x] = res
                    }
                }
                u += du
                v += dv
            }
        }
        target.setPixels(px, 0, rw, x0, y0, rw, rh)
    }
}
