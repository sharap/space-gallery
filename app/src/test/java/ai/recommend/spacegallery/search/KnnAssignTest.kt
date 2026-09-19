package ai.recommend.spacegallery.search

import ai.recommend.spacegallery.search.people.consistentExamples
import ai.recommend.spacegallery.search.people.knnAssign
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class KnnAssignTest {

    /** Точки на окружности (dim = 2) под углами [degrees]. */
    private fun circle(vararg degrees: Double): FloatArray = FloatArray(degrees.size * 2) { i ->
        val rad = Math.toRadians(degrees[i / 2])
        (if (i % 2 == 0) cos(rad) else sin(rad)).toFloat()
    }

    @Test
    fun multiModalPersonAttractsFaceNearOneMode() {
        // Человек 1 — две «моды»: около 0° и около 100° (в очках / без). Новое лицо на 102°.
        // По центру группы (≈50°) сходство cos(52°) ≈ 0.62, но ближайшие примеры — cos(2°) ≈ 1.
        val v = circle(0.0, 2.0, 4.0, 100.0, 104.0, 102.0)
        val assigned = knnAssign(v, 2, intArrayOf(5), mapOf(1L to intArrayOf(0, 1, 2, 3, 4)), { _, _ -> false }, k = 2, threshold = 0.9f)
        assertEquals(1L, assigned[5])
    }

    @Test
    fun ambiguousBetweenTwoPeopleIsNotAssigned() {
        // Лицо ровно между двумя людьми — отрыв меньше margin.
        val v = circle(0.0, 1.0, 40.0, 41.0, 20.5)
        val assigned = knnAssign(v, 2, intArrayOf(4), mapOf(1L to intArrayOf(0, 1), 2L to intArrayOf(2, 3)), { _, _ -> false }, k = 2, threshold = 0.5f, margin = 0.05f)
        assertNull(assigned[4])
    }

    @Test
    fun rejectedPersonIsSkipped() {
        val v = circle(0.0, 1.0, 30.0, 31.0, 0.5)
        val assigned = knnAssign(v, 2, intArrayOf(4), mapOf(1L to intArrayOf(0, 1), 2L to intArrayOf(2, 3)), { _, person -> person == 1L }, k = 2, threshold = 0.5f)
        assertEquals(2L, assigned[4])
    }

    @Test
    fun wronglyConfirmedSubgroupIsNotUsedAsExample() {
        // Человек 1: основная группа около 0°, та же персона «в очках» около 40° (другая мода),
        // и 3 лица другого человека около 100°, по ошибке подтверждённые (похожи друг на друга!).
        val v = circle(0.0, 2.0, 4.0, 6.0, 40.0, 42.0, 100.0, 101.0, 102.0, 101.5)
        val (examples, dropped) = consistentExamples(
            v, 2, mapOf(1L to intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8)),
            modeLinkSimilarity = 0.99f, // подгруппы — в пределах ~8°
            modeSimilarity = 0.7f, // cos(38°) ≈ 0.79 — та же персона; cos(97°) < 0 — чужой
        )
        assertEquals(listOf(0, 1, 2, 3, 4, 5), examples.getValue(1L).toList())
        assertEquals(3, dropped[1L])
        // Новое лицо около чужой подгруппы (101.5°) больше не притягивается к человеку 1.
        assertNull(knnAssign(v, 2, intArrayOf(9), examples, { _, _ -> false }, k = 2, threshold = 0.9f)[9])
    }
}
