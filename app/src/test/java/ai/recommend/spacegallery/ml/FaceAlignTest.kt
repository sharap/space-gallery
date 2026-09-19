package ai.recommend.spacegallery.ml

import ai.recommend.spacegallery.ml.face.FaceEmbedder
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class FaceAlignTest {

    /** Эталон — cv2.estimateAffinePartial2D на точках YuNet для тестового фото lena.jpg из OpenCV. */
    @Test
    fun similarityMatchesOpenCv() {
        val landmarks = floatArrayOf(271.636f, 269.448f, 328.473f, 276.099f, 309.694f, 312.879f, 270.927f, 342.156f, 311.312f, 348.407f)
        val template = floatArrayOf(38.2946f, 51.6963f, 73.5318f, 51.5014f, 56.0252f, 71.7366f, 41.5493f, 92.3655f, 70.7299f, 92.2041f)
        val params = FaceEmbedder.similarityParams(landmarks, template)
        assertArrayEquals(floatArrayOf(0.568481f, -0.075112f, -136.8826f, -81.7992f), params, 1e-3f)
    }

    @Test
    fun identityWhenPointsMatch() {
        val pts = floatArrayOf(10f, 10f, 50f, 12f, 30f, 40f, 15f, 60f, 45f, 61f)
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 0f), FaceEmbedder.similarityParams(pts, pts), 1e-5f)
    }
}
