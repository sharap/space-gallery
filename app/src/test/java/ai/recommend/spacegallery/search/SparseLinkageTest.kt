package ai.recommend.spacegallery.search

import ai.recommend.spacegallery.ml.VectorMath
import ai.recommend.spacegallery.search.people.averageLinkage
import ai.recommend.spacegallery.search.people.sparseAverageLinkage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SparseLinkageTest {

    /** Точки вокруг [groups] центров: имитация лиц нескольких людей. */
    private fun sample(groups: Int, perGroup: Int, dim: Int, spread: Float, seed: Int = 1): Pair<FloatArray, IntArray> {
        val random = Random(seed)
        val centers = List(groups) { FloatArray(dim) { random.nextFloat() * 2 - 1 }.let(VectorMath::l2Normalize) }
        val n = groups * perGroup
        val vectors = FloatArray(n * dim)
        val truth = IntArray(n)
        var i = 0
        for ((g, center) in centers.withIndex()) {
            repeat(perGroup) {
                val v = FloatArray(dim) { k -> center[k] + (random.nextFloat() * 2 - 1) * spread }
                VectorMath.l2Normalize(v).copyInto(vectors, i * dim)
                truth[i] = g
                i++
            }
        }
        return vectors to truth
    }

    /** Разбиение как множество групп — метки могут называться по-разному. */
    private fun partition(labels: IntArray): Set<Set<Int>> =
        labels.indices.groupBy { labels[it] }.values.map { it.toSet() }.toSet()

    @Test
    fun matchesDenseLinkage() = runTest {
        val dim = 16
        val (vectors, _) = sample(groups = 6, perGroup = 12, dim = dim, spread = 0.25f)
        val n = 72
        val media = LongArray(n) { it.toLong() } // каждое лицо со своего снимка — штраф не работает
        for (threshold in listOf(0.5f, 0.7f, 0.85f)) {
            val dense = averageLinkage(vectors, n, dim, threshold)
            val sparse = sparseAverageLinkage(vectors, n, dim, threshold, media, neighbors = n - 1)
            assertEquals("порог $threshold", partition(dense), partition(sparse))
        }
    }

    @Test
    fun findsPlantedGroups() = runTest {
        val dim = 32
        val (vectors, truth) = sample(groups = 5, perGroup = 20, dim = dim, spread = 0.12f, seed = 7)
        val n = 100
        val labels = sparseAverageLinkage(vectors, n, dim, 0.8f, LongArray(n) { it.toLong() })
        assertEquals(partition(truth), partition(labels))
    }

    @Test
    fun sameMediaFacesAreKeptApart() = runTest {
        val dim = 8
        // Два почти одинаковых вектора: без штрафа сливаются, с одного снимка — нет.
        val a = VectorMath.l2Normalize(FloatArray(dim) { if (it == 0) 1f else 0.02f })
        val b = VectorMath.l2Normalize(FloatArray(dim) { if (it == 0) 1f else 0.03f })
        val vectors = FloatArray(2 * dim)
        a.copyInto(vectors, 0)
        b.copyInto(vectors, dim)
        val apart = sparseAverageLinkage(vectors, 2, dim, 0.9f, longArrayOf(5, 5))
        assertNotEquals("лица с одного снимка не объединяются", apart[0], apart[1])
        val together = sparseAverageLinkage(vectors, 2, dim, 0.9f, longArrayOf(5, 6))
        assertEquals(together[0], together[1])
    }

    @Test
    fun respectsAnchorsAndRejections() = runTest {
        val dim = 16
        val (vectors, truth) = sample(groups = 3, perGroup = 10, dim = dim, spread = 0.15f, seed = 3)
        val n = 30
        val media = LongArray(n) { it.toLong() }
        // Подтверждены по одному лицу из двух разных групп — они не должны слиться.
        val anchors = IntArray(n) { -1 }
        val first = truth.indexOfFirst { it == 0 }
        val second = truth.indexOfFirst { it == 1 }
        anchors[first] = 100
        anchors[second] = 200
        val labels = sparseAverageLinkage(vectors, n, dim, 0.1f, media, anchors)
        assertNotEquals("разные подтверждённые люди не сливаются", labels[first], labels[second])

        // «Это не он»: лицо не попадает к подтверждённому человеку.
        val other = truth.indices.first { truth[it] == 0 && it != first }
        val rejected = sparseAverageLinkage(
            vectors, n, dim, 0.1f, media, anchors, rejectedPersons = mapOf(other to setOf(100)),
        )
        assertNotEquals(rejected[first], rejected[other])
        assertTrue("остальные лица группы остаются с подтверждённым", truth.indices.any { it != other && truth[it] == 0 && rejected[it] == rejected[first] })
    }
}
