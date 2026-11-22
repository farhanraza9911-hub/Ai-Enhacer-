package com.farhan.aienhancer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Reference Python pipeline ke saath wahi checks: similarity fit scale/rotation wapas nikalta hai. */
class FaceMathTest {

    /** Template points ko (scale, angle, offset) se image coordinates mein le jata hai: img = A * template. */
    private fun scene(scale: Double, deg: Double, ox: Double, oy: Double, order: IntArray = intArrayOf(0, 1, 2, 3)): FloatArray {
        val a = deg * PI / 180
        val out = FloatArray(8)
        for ((k, i) in order.withIndex()) {
            val qx = FaceAlign.TEMPLATE[i * 2]
            val qy = FaceAlign.TEMPLATE[i * 2 + 1]
            out[k * 2] = (scale * (cos(a) * qx - sin(a) * qy) + ox).toFloat()
            out[k * 2 + 1] = (scale * (sin(a) * qx + cos(a) * qy) + oy).toFloat()
        }
        return out
    }

    @Test
    fun fitRecoversScaleAndRotation() {
        // face template ka 0.42x, 14 degree rotate hua, image mein kahin rakha hua
        val m = FaceAlign.fit(scene(0.42, 14.0, 420.0, 330.0))
        assertNotNull(m)
        assertEquals(1 / 0.42, m!!.scale, 1e-3)
        assertEquals(-14.0, Math.toDegrees(atan2(m.c, m.a)), 0.05)
    }

    @Test
    fun fitMapsLandmarksOntoTemplate() {
        val lm = scene(0.3, -20.0, 100.0, 80.0)
        val m = FaceAlign.fit(lm)!!
        // eyes: pehle do points, mouth: baqi do (left/right sort hone ke baad template ke qareeb)
        for (i in 0 until 4) {
            val tx = FaceAlign.TEMPLATE[i * 2]
            val ty = FaceAlign.TEMPLATE[i * 2 + 1]
            assertEquals(tx, m.mapX(lm[i * 2].toDouble(), lm[i * 2 + 1].toDouble()), 0.01)
            assertEquals(ty, m.mapY(lm[i * 2].toDouble(), lm[i * 2 + 1].toDouble()), 0.01)
        }
    }

    @Test
    fun fitIsOrderIndependent() {
        // ML Kit ke "left/right" labels ulte ho sakte hain: eyes aur mouth swap
        val a = FaceAlign.fit(scene(0.5, 5.0, 10.0, 20.0))!!
        val b = FaceAlign.fit(scene(0.5, 5.0, 10.0, 20.0, intArrayOf(1, 0, 3, 2)))!!
        assertEquals(a.a, b.a, 1e-6)
        assertEquals(a.tx, b.tx, 1e-4)
        assertEquals(a.ty, b.ty, 1e-4)
    }

    @Test
    fun inverseRoundTrip() {
        val m = FaceAlign.fit(scene(0.37, 33.0, 5.0, 9.0))!!
        val id = m.compose(m.inverse())
        assertEquals(1.0, id.a, 1e-9)
        assertEquals(0.0, id.b, 1e-9)
        assertEquals(0.0, id.tx, 1e-6)
        assertEquals(0.0, id.ty, 1e-6)
    }

    @Test
    fun degeneratePointsRejected() {
        assertNull(FaceAlign.fit(floatArrayOf(5f, 5f, 5f, 5f, 5f, 5f, 5f, 5f)))
        assertNull(FaceAlign.fit(floatArrayOf(1f, 2f)))
    }
}
