package com.farhan.aienhancer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.max
import kotlin.math.min

/** Ek restore hua chehra: GFPGAN ka 512x512 output + template -> source image coordinates ka transform. */
class RestoredFace(val toSrc: Affine, val pixels: IntArray) {
    private val levels = HashMap<Int, IntArray>()

    /** 2^[level] se chhota kiya hua (2x2 average) version; level 0 = original 512. */
    fun level(level: Int): IntArray {
        if (level <= 0) return pixels
        return levels.getOrPut(level) {
            var cur = pixels
            var s = FaceAlign.SIZE
            repeat(level) {
                cur = halve(cur, s)
                s /= 2
            }
            cur
        }
    }

    private fun halve(src: IntArray, s: Int): IntArray {
        val h = s / 2
        val out = IntArray(h * h)
        for (y in 0 until h) {
            for (x in 0 until h) {
                val p0 = src[(2 * y) * s + 2 * x]
                val p1 = src[(2 * y) * s + 2 * x + 1]
                val p2 = src[(2 * y + 1) * s + 2 * x]
                val p3 = src[(2 * y + 1) * s + 2 * x + 1]
                val r = (((p0 shr 16) and 255) + ((p1 shr 16) and 255) + ((p2 shr 16) and 255) + ((p3 shr 16) and 255) + 2) shr 2
                val g = (((p0 shr 8) and 255) + ((p1 shr 8) and 255) + ((p2 shr 8) and 255) + ((p3 shr 8) and 255) + 2) shr 2
                val b = ((p0 and 255) + (p1 and 255) + (p2 and 255) + (p3 and 255) + 2) shr 2
                out[y * h + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return out
    }
}

/**
 * Pipeline: landmarks -> align + crop (512) -> GFPGAN -> low-frequency colour match.
 * (Paste-back [FaceBlender] mein hota hai, taake "face fidelity" slider live chal sake.)
 */
object FacePipeline {

    /**
     * @param src  asli (input) photo, jis ke coordinates mein [landmarks] hain
     * @param onFace har chehra khatam hone par (done, total)
     * @param isCancelled true ho to baqi chehre chhor kar jo ho chuke wo return
     */
    fun restoreAll(
        src: Bitmap,
        landmarks: List<FloatArray>,
        restorer: FaceRestorer,
        isCancelled: () -> Boolean,
        onFace: (Int, Int) -> Unit
    ): List<RestoredFace> {
        val out = ArrayList<RestoredFace>()
        landmarks.forEachIndexed { i, lm ->
            if (isCancelled()) return out
            val toCrop = FaceAlign.fit(lm)
            if (toCrop != null) {
                val crop = warpCrop(src, toCrop)
                try {
                    val cropPx = FaceRestorer.pixelsOf(crop)
                    val restored = restorer.restore(cropPx)
                    if (restored != null) {
                        matchTone(restored, cropPx)
                        out.add(RestoredFace(toCrop.inverse(), restored))
                    }
                } finally {
                    crop.recycle()
                }
            }
            onFace(i + 1, landmarks.size)
        }
        return out
    }

    /**
     * Chehre ko 512x512 template par warp karta hai. Bade chehre pehle 2x2 average se chhote kiye jate hain
     * (aliasing se bachne ke liye), border facexlib/GFPGAN wala gray (135,133,132) hai.
     */
    fun warpCrop(src: Bitmap, toCrop: Affine): Bitmap {
        val s = FaceAlign.SIZE.toDouble()
        val inv = toCrop.inverse()
        val xs = doubleArrayOf(inv.mapX(0.0, 0.0), inv.mapX(s, 0.0), inv.mapX(s, s), inv.mapX(0.0, s))
        val ys = doubleArrayOf(inv.mapY(0.0, 0.0), inv.mapY(s, 0.0), inv.mapY(s, s), inv.mapY(0.0, s))
        val rx0 = max(0, xs.min().toInt() - 2)
        val ry0 = max(0, ys.min().toInt() - 2)
        val rx1 = min(src.width, xs.max().toInt() + 3)
        val ry1 = min(src.height, ys.max().toInt() + 3)

        val crop = Bitmap.createBitmap(FaceAlign.SIZE, FaceAlign.SIZE, Bitmap.Config.ARGB_8888)
        crop.eraseColor(Color.rgb(135, 133, 132))
        if (rx1 - rx0 < 2 || ry1 - ry0 < 2) return crop

        var region = Bitmap.createBitmap(src, rx0, ry0, rx1 - rx0, ry1 - ry0)
        var owned = region !== src
        // region pixel -> crop pixel
        var m = toCrop.compose(Affine.translate(rx0.toDouble(), ry0.toDouble()))
        try {
            while (m.scale < 0.5 && region.width >= 8 && region.height >= 8) {
                val nw = region.width / 2
                val nh = region.height / 2
                val half = Bitmap.createScaledBitmap(region, nw, nh, true)
                m = m.compose(Affine.scale(region.width.toDouble() / nw, region.height.toDouble() / nh))
                if (owned) region.recycle()
                region = half
                owned = true
            }
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            Canvas(crop).drawBitmap(region, m.toMatrix(), paint)
        } finally {
            if (owned) region.recycle()
        }
        return crop
    }

    /**
     * GFPGAN ka output aksar halka sa colour/brightness shift karta hai. Sirf low-frequency (16x16 grid)
     * farq original crop ke mutabiq kar dete hain: tone/lighting original jaisi, detail GFPGAN ki.
     */
    fun matchTone(restored: IntArray, original: IntArray) {
        val s = FaceAlign.SIZE
        val g = 16
        val blk = s / g
        val diff = FloatArray(g * g * 3)
        val inv = 1f / (blk * blk)
        for (by in 0 until g) {
            for (bx in 0 until g) {
                var rr = 0L; var rg = 0L; var rb = 0L
                var or_ = 0L; var og = 0L; var ob = 0L
                for (y in by * blk until (by + 1) * blk) {
                    val row = y * s
                    for (x in bx * blk until (bx + 1) * blk) {
                        val a = restored[row + x]
                        val b = original[row + x]
                        rr += (a shr 16) and 255; rg += (a shr 8) and 255; rb += a and 255
                        or_ += (b shr 16) and 255; og += (b shr 8) and 255; ob += b and 255
                    }
                }
                val i = (by * g + bx) * 3
                diff[i] = (or_ - rr) * inv
                diff[i + 1] = (og - rg) * inv
                diff[i + 2] = (ob - rb) * inv
            }
        }
        // bilinear upsample of diff grid
        val gx0 = IntArray(s); val gx1 = IntArray(s); val gxf = FloatArray(s)
        for (x in 0 until s) {
            val u = ((x + 0.5f) / blk - 0.5f).coerceIn(0f, (g - 1).toFloat())
            gx0[x] = u.toInt(); gx1[x] = min(gx0[x] + 1, g - 1); gxf[x] = u - gx0[x]
        }
        for (y in 0 until s) {
            val v = ((y + 0.5f) / blk - 0.5f).coerceIn(0f, (g - 1).toFloat())
            val y0 = v.toInt(); val y1 = min(y0 + 1, g - 1); val fy = v - y0
            for (x in 0 until s) {
                val fx = gxf[x]
                var rgb = 0xFF shl 24
                for (c in 0 until 3) {
                    val d00 = diff[(y0 * g + gx0[x]) * 3 + c]
                    val d01 = diff[(y0 * g + gx1[x]) * 3 + c]
                    val d10 = diff[(y1 * g + gx0[x]) * 3 + c]
                    val d11 = diff[(y1 * g + gx1[x]) * 3 + c]
                    val d = (d00 * (1 - fx) + d01 * fx) * (1 - fy) + (d10 * (1 - fx) + d11 * fx) * fy
                    val shift = 16 - 8 * c
                    val v0 = (restored[y * s + x] shr shift) and 255
                    val nv = (v0 + d + 0.5f).toInt().coerceIn(0, 255)
                    rgb = rgb or (nv shl shift)
                }
                restored[y * s + x] = rgb
            }
        }
    }
}
