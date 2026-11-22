package com.farhan.aienhancer

import android.graphics.Matrix
import kotlin.math.sqrt

/** 2x3 affine transform: x' = a*x + b*y + tx, y' = c*x + d*y + ty. */
class Affine(
    val a: Double, val b: Double, val tx: Double,
    val c: Double, val d: Double, val ty: Double
) {
    fun mapX(x: Double, y: Double) = a * x + b * y + tx
    fun mapY(x: Double, y: Double) = c * x + d * y + ty

    /** this(inner(p)): pehle [inner] lagta hai, phir this. */
    fun compose(inner: Affine) = Affine(
        a * inner.a + b * inner.c, a * inner.b + b * inner.d, a * inner.tx + b * inner.ty + tx,
        c * inner.a + d * inner.c, c * inner.b + d * inner.d, c * inner.tx + d * inner.ty + ty
    )

    fun inverse(): Affine {
        val det = a * d - b * c
        val ia = d / det
        val ib = -b / det
        val ic = -c / det
        val id = a / det
        return Affine(ia, ib, -(ia * tx + ib * ty), ic, id, -(ic * tx + id * ty))
    }

    /** Length of the transformed x-axis unit vector (similarity transform mein = scale). */
    val scale: Double get() = sqrt(a * a + c * c)

    fun toMatrix(): Matrix = Matrix().apply {
        setValues(
            floatArrayOf(
                a.toFloat(), b.toFloat(), tx.toFloat(),
                c.toFloat(), d.toFloat(), ty.toFloat(),
                0f, 0f, 1f
            )
        )
    }

    companion object {
        fun scale(sx: Double, sy: Double = sx) = Affine(sx, 0.0, 0.0, 0.0, sy, 0.0)
        fun translate(tx: Double, ty: Double) = Affine(1.0, 0.0, tx, 0.0, 1.0, ty)
    }
}

/**
 * Face alignment: FFHQ 512x512 template (GFPGAN/facexlib wala wahi template).
 * Chaar points istemal hote hain: left eye, right eye, mouth left, mouth right.
 */
object FaceAlign {
    const val SIZE = 512

    /** Template points (x, y) 512x512 par: eyeL, eyeR, mouthL, mouthR. */
    internal val TEMPLATE = doubleArrayOf(
        192.98138, 239.94708,
        318.90277, 240.19360,
        201.26117, 371.41043,
        313.08905, 371.15118
    )

    /**
     * [lm] = 8 floats (x,y) x 4: eye A, eye B, mouth A, mouth B (kisi bhi order mein, left/right khud sort hota hai).
     * @return source image -> 512 crop ka similarity transform, ya null agar landmarks kharab hon.
     */
    fun fit(lm: FloatArray): Affine? {
        if (lm.size < 8) return null
        // left/right: image mein chhoti x pehle
        val eyes = if (lm[0] <= lm[2]) intArrayOf(0, 1) else intArrayOf(1, 0)
        val mouth = if (lm[4] <= lm[6]) intArrayOf(2, 3) else intArrayOf(3, 2)
        val order = intArrayOf(eyes[0], eyes[1], mouth[0], mouth[1])
        val px = DoubleArray(4) { lm[order[it] * 2].toDouble() }
        val py = DoubleArray(4) { lm[order[it] * 2 + 1].toDouble() }
        val qx = DoubleArray(4) { TEMPLATE[it * 2] }
        val qy = DoubleArray(4) { TEMPLATE[it * 2 + 1] }

        val pmx = px.average()
        val pmy = py.average()
        val qmx = qx.average()
        val qmy = qy.average()

        // Complex similarity: q = w * p  (w = s * e^{i*theta}), least squares
        var numRe = 0.0
        var numIm = 0.0
        var den = 0.0
        for (i in 0 until 4) {
            val x = px[i] - pmx
            val y = py[i] - pmy
            val u = qx[i] - qmx
            val v = qy[i] - qmy
            numRe += x * u + y * v      // Re(conj(p) * q)
            numIm += x * v - y * u      // Im(conj(p) * q)
            den += x * x + y * y
        }
        if (den < 16.0) return null     // points bahut qareeb: face bohat chhota ya landmarks kharab
        val wr = numRe / den
        val wi = numIm / den
        val s = sqrt(wr * wr + wi * wi)
        if (s <= 0.0) return null

        val tx = qmx - (wr * pmx - wi * pmy)
        val ty = qmy - (wi * pmx + wr * pmy)
        return Affine(wr, -wi, tx, wi, wr, ty)
    }
}
