package ai.recommend.spacegallery.ml

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class VectorMathTest {

    @Test
    fun l2NormalizeProducesUnitVector() {
        val v = VectorMath.l2Normalize(floatArrayOf(3f, 4f))
        assertArrayEquals(floatArrayOf(0.6f, 0.8f), v, 1e-6f)
        assertEquals(1f, VectorMath.dot(v, v), 1e-6f)
    }

    @Test
    fun bytesRoundTrip() {
        val v = floatArrayOf(0.1f, -2.5f, 3.75f)
        assertArrayEquals(v, VectorMath.fromBytes(VectorMath.toBytes(v)), 0f)
    }

    @Test
    fun softmaxSumsToOne() {
        val p = VectorMath.softmax(floatArrayOf(1f, 2f, 3f))
        assertEquals(1f, p.sum(), 1e-6f)
        assertEquals(true, p[2] > p[1] && p[1] > p[0])
    }
}
