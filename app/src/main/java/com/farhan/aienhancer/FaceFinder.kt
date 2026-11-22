package com.farhan.aienhancer

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import kotlin.math.max

/**
 * Google ML Kit (bundled model, offline) se chehre aur unke landmarks dhoondta hai.
 * Photo device se bahar nahi jati. Worker thread par chalayein (blocking).
 */
class FaceFinder : AutoCloseable {

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.03f)
            .build()
    )

    /**
     * @return har chehre ke 8 floats (eye, eye, mouth corner, mouth corner) [bmp] ke coordinates mein,
     *         bade chehre pehle, aur sirf [MAX_FACES] tak.
     */
    fun find(bmp: Bitmap): List<FloatArray> {
        val longest = max(bmp.width, bmp.height)
        val f = if (longest > DETECT_SIDE) DETECT_SIDE.toFloat() / longest else 1f
        val det = if (f < 1f) {
            Bitmap.createScaledBitmap(bmp, max(1, (bmp.width * f).toInt()), max(1, (bmp.height * f).toInt()), true)
        } else {
            bmp
        }
        try {
            val faces = Tasks.await(detector.process(InputImage.fromBitmap(det, 0)))
            val found = ArrayList<Pair<Float, FloatArray>>()
            for (face in faces) {
                val le = face.getLandmark(FaceLandmark.LEFT_EYE)?.position ?: continue
                val re = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position ?: continue
                val ml = face.getLandmark(FaceLandmark.MOUTH_LEFT)?.position ?: continue
                val mr = face.getLandmark(FaceLandmark.MOUTH_RIGHT)?.position ?: continue
                val w = face.boundingBox.width() / f
                if (w < MIN_FACE_PX) continue
                found.add(
                    w to floatArrayOf(
                        le.x / f, le.y / f, re.x / f, re.y / f,
                        ml.x / f, ml.y / f, mr.x / f, mr.y / f
                    )
                )
            }
            return found.sortedByDescending { it.first }.take(MAX_FACES).map { it.second }
        } finally {
            if (det !== bmp) det.recycle()
        }
    }

    override fun close() {
        detector.close()
    }

    companion object {
        private const val DETECT_SIDE = 2048
        const val MAX_FACES = 8

        /** Isse chhote chehre (input pixels mein) par restore nahi hota: GFPGAN wahan detail banata (invent karta) hai. */
        private const val MIN_FACE_PX = 24f
    }
}
