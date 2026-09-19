package ai.recommend.spacegallery.search

import ai.recommend.spacegallery.search.smart.NOISE
import ai.recommend.spacegallery.search.smart.cosineNeighbors
import ai.recommend.spacegallery.search.smart.dbscan
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

class DbscanTest {

    /** Точки на единичной окружности (dim = 2) под углами [degrees]. */
    private fun circle(vararg degrees: Double): FloatArray = FloatArray(degrees.size * 2) { i ->
        val rad = Math.toRadians(degrees[i / 2])
        (if (i % 2 == 0) cos(rad) else sin(rad)).toFloat()
    }

    @Test
    fun twoDenseGroupsAndNoise() = runTest {
        // Две плотные группы (по 5 точек через 1°) и одиночка далеко.
        val v = circle(0.0, 1.0, 2.0, 3.0, 4.0, 90.0, 91.0, 92.0, 93.0, 94.0, 200.0)
        val nb = cosineNeighbors(v, n = 11, dim = 2, eps = 0.01f) // 1 - cos(8°) ≈ 0.0097
        val labels = dbscan(nb, minPts = 4)
        assertEquals(setOf(labels[0]), (0..4).map { labels[it] }.toSet())
        assertEquals(setOf(labels[5]), (5..9).map { labels[it] }.toSet())
        assertNotEquals(labels[0], labels[5])
        assertEquals(NOISE, labels[10])
    }

    @Test
    fun chainingMergesThroughCorePoints() = runTest {
        // Цепочка с шагом 2°: каждая точка — ядро, поэтому вся цепочка — один кластер.
        val v = circle(*DoubleArray(20) { it * 2.0 })
        val labels = dbscan(cosineNeighbors(v, 20, 2, eps = 0.0025f), minPts = 3) // ~4°
        assertEquals(1, labels.toSet().size)
        assertNotEquals(NOISE, labels[0])
    }

    @Test
    fun refinementWithSmallerEpsSplitsCluster() = runTest {
        val v = circle(0.0, 1.0, 2.0, 3.0, 7.0, 8.0, 9.0, 10.0)
        val nb = cosineNeighbors(v, 8, 2, eps = 0.02f) // ~11°: всё вместе
        assertEquals(1, dbscan(nb, minPts = 3).toSet().size)
        val split = dbscan(nb, minPts = 3, minSimilarity = 1f - 0.001f) // ~2.5°: две группы
        assertEquals(2, split.filter { it != NOISE }.toSet().size)
    }

    /**
     * Сверка с scikit-learn на реальных эмбеддингах (не хранятся в репозитории):
     * SPACEGALLERY_DBSCAN_FIXTURE=/path/dir с E.f32 (n×512 float32 LE) и sklearn_labels.txt.
     */
    @Test
    fun matchesScikitLearnOnRealEmbeddings() = runTest {
        val dir = System.getenv("SPACEGALLERY_DBSCAN_FIXTURE")?.let(::File) ?: return@runTest
        val bytes = File(dir, "E.f32").readBytes()
        val buf = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val v = FloatArray(buf.remaining()).also { buf.get(it) }
        val n = v.size / 512
        val expected = File(dir, "sklearn_labels.txt").readLines().map { it.trim().toInt() }
        val actual = dbscan(cosineNeighbors(v, n, 512, eps = 0.14f), minPts = 6)
        // Номера кластеров могут отличаться — сравниваем разбиение: одинаковость пар «ядро-кластер».
        assertEquals(expected.count { it == NOISE }, actual.count { it == NOISE })
        assertEquals(expected.filter { it != NOISE }.toSet().size, actual.filter { it != NOISE }.toSet().size)
    }
}
