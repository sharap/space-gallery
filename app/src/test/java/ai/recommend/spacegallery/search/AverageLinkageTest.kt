package ai.recommend.spacegallery.search

import ai.recommend.spacegallery.search.people.averageLinkage
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt
import kotlin.random.Random

class AverageLinkageTest {

    private fun normalizedRandom(n: Int, dim: Int, seed: Int): FloatArray {
        val rnd = Random(seed)
        val v = FloatArray(n * dim) { rnd.nextFloat() * 2 - 1 }
        for (i in 0 until n) {
            var s = 0f
            for (k in 0 until dim) s += v[i * dim + k] * v[i * dim + k]
            val norm = sqrt(s)
            for (k in 0 until dim) v[i * dim + k] /= norm
        }
        return v
    }

    /** Эталон: наивная средняя связь — каждый шаг сливает самую похожую пару, пока среднее ≥ порога. */
    private fun naive(v: FloatArray, n: Int, dim: Int, threshold: Float): List<Set<Int>> {
        val clusters = (0 until n).map { mutableSetOf(it) }.toMutableList()
        fun dot(i: Int, j: Int) = (0 until dim).sumOf { (v[i * dim + it] * v[j * dim + it]).toDouble() }
        while (true) {
            var best = -1.0
            var bi = -1
            var bj = -1
            for (i in clusters.indices) for (j in i + 1 until clusters.size) {
                val avg = clusters[i].sumOf { a -> clusters[j].sumOf { b -> dot(a, b) } } / (clusters[i].size * clusters[j].size)
                if (avg > best) { best = avg; bi = i; bj = j }
            }
            if (bi < 0 || best < threshold) break
            clusters[bi].addAll(clusters[bj])
            clusters.removeAt(bj)
        }
        return clusters
    }

    private fun partition(labels: IntArray): Set<Set<Int>> =
        labels.indices.groupBy { labels[it] }.values.map { it.toSet() }.toSet()

    @Test
    fun matchesNaiveAverageLinkage() {
        // Кластерная структура: 5 центров + шум вокруг них, низкая размерность — есть неоднозначные слияния.
        val dim = 8
        val centers = normalizedRandom(5, dim, seed = 1)
        val n = 60
        val rnd = Random(2)
        val v = FloatArray(n * dim) { i -> centers[(i / dim % 5) * dim + i % dim] + (rnd.nextFloat() - 0.5f) * 0.6f }
        for (i in 0 until n) {
            val norm = sqrt((0 until dim).sumOf { (v[i * dim + it] * v[i * dim + it]).toDouble() }).toFloat()
            for (k in 0 until dim) v[i * dim + k] /= norm
        }
        for (threshold in listOf(0.2f, 0.5f, 0.8f)) {
            val expected = naive(v, n, dim, threshold).map { it.toSet() }.toSet()
            assertEquals("threshold $threshold", expected, partition(averageLinkage(v, n, dim, threshold)))
        }
    }

    /**
     * Сверка со scikit-learn (AgglomerativeClustering, average, cosine, distance_threshold = 0.65)
     * на реальных векторах лиц — не хранятся в репозитории:
     * SPACEGALLERY_FACE_FIXTURE=/path с E.f32 (n×128) и sklearn_labels.txt.
     */
    @Test
    fun matchesScikitLearnOnRealFaces() {
        val dir = System.getenv("SPACEGALLERY_FACE_FIXTURE")?.let(::File) ?: return
        val buf = ByteBuffer.wrap(File(dir, "E.f32").readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val v = FloatArray(buf.remaining()).also { buf.get(it) }
        val n = v.size / 128
        val expected = File(dir, "sklearn_labels.txt").readLines().map { it.trim().toInt() }.toIntArray()
        assertEquals(partition(expected), partition(averageLinkage(v, n, 128, minSimilarity = 0.35f)))
    }

    /** Точки на окружности (dim = 2) под углами [degrees]. */
    private fun circle(vararg degrees: Double): FloatArray = FloatArray(degrees.size * 2) { i ->
        val rad = Math.toRadians(degrees[i / 2])
        (if (i % 2 == 0) kotlin.math.cos(rad) else kotlin.math.sin(rad)).toFloat()
    }

    @Test
    fun anchorsStayTogetherEvenWhenDissimilar() {
        // 0 и 1 далеко (90°), но подтверждены как один человек.
        val v = circle(0.0, 90.0, 180.0)
        val labels = averageLinkage(v, 3, 2, minSimilarity = 0.9f, anchors = intArrayOf(7, 7, -1))
        assertEquals(labels[0], labels[1])
        assert(labels[2] != labels[0])
    }

    @Test
    fun cannotLinkPreventsMergeAndSpreads() {
        // 0,1,2 очень близко; 2 — «это не он» для подтверждённых 0,1.
        val v = circle(0.0, 1.0, 2.0, 3.0)
        val labels = averageLinkage(
            v, 4, 2, minSimilarity = 0.5f,
            anchors = intArrayOf(1, 1, -1, -1),
            cannotLink = listOf(2 to 0, 2 to 1),
        )
        assertEquals(labels[0], labels[1])
        assert(labels[2] != labels[0]) { "запрет нарушен" }
        // 3 похожа на обоих — может уйти к любой группе, но 2 и группа 0/1 не встретятся.
    }

    @Test
    fun differentConfirmedPeopleNeverMerge() {
        val v = circle(0.0, 1.0, 2.0, 3.0)
        val labels = averageLinkage(v, 4, 2, minSimilarity = 0.1f, anchors = intArrayOf(1, 1, 2, 2))
        assertEquals(labels[0], labels[1])
        assertEquals(labels[2], labels[3])
        assert(labels[0] != labels[2])
    }
}
