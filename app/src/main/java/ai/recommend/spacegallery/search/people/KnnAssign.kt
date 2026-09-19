package ai.recommend.spacegallery.search.people

import ai.recommend.spacegallery.ml.VectorMath

/**
 * Привязка лиц к подтверждённым людям по ближайшим примерам (k-NN).
 *
 * Оценка лица для человека — среднее сходство с его [k] самыми похожими подтверждёнными лицами
 * (а не со всеми): группа «в очках / без очков / в разные годы» — это несколько «мод», и новое
 * фото достаточно похоже на одну из них. Чем больше подтверждённых фото в разных условиях,
 * тем лучше узнаются новые.
 *
 * Лицо относится к лучшему человеку, если оценка ≥ [threshold] и опережает второго по
 * оценке человека не меньше чем на [margin] (не угадываем между двумя похожими людьми).
 *
 * @param candidates индексы лиц, которые можно привязать (не подтверждённые);
 * @param examples подтверждённые лица по людям (индексы в [vectors]);
 * @param isRejected «это не он» — лицо нельзя привязывать к человеку;
 * @return индекс лица -> человек.
 */
fun knnAssign(
    vectors: FloatArray,
    dim: Int,
    candidates: IntArray,
    examples: Map<Long, IntArray>,
    isRejected: (face: Int, person: Long) -> Boolean,
    k: Int = KNN_K,
    threshold: Float = KNN_THRESHOLD,
    margin: Float = KNN_MARGIN,
): Map<Int, Long> {
    if (examples.isEmpty()) return emptyMap()
    val result = HashMap<Int, Long>()
    val top = FloatArray(k)
    for (face in candidates) {
        var best: Long? = null
        var bestScore = Float.NEGATIVE_INFINITY
        var secondScore = Float.NEGATIVE_INFINITY
        for ((person, faces) in examples) {
            if (faces.isEmpty() || isRejected(face, person)) continue
            val score = topKMean(vectors, dim, face, faces, top)
            if (score > bestScore) {
                secondScore = bestScore
                bestScore = score
                best = person
            } else if (score > secondScore) {
                secondScore = score
            }
        }
        if (best != null && bestScore >= threshold && bestScore - secondScore >= margin) result[face] = best
    }
    return result
}

/** Среднее k наибольших сходств лица [face] с лицами [others] (k = min(k, others.size)). */
fun topKMean(vectors: FloatArray, dim: Int, face: Int, others: IntArray, top: FloatArray): Float {
    val k = minOf(top.size, others.size)
    top.fill(Float.NEGATIVE_INFINITY)
    val a = vectors.copyOfRange(face * dim, (face + 1) * dim)
    for (o in others) {
        if (o == face) continue
        val s = VectorMath.dot(a, vectors, o * dim)
        // Вставка в отсортированный по убыванию буфер длины k.
        if (s <= top[k - 1]) continue
        var i = k - 1
        while (i > 0 && top[i - 1] < s) {
            top[i] = top[i - 1]
            i--
        }
        top[i] = s
    }
    var sum = 0f
    var count = 0
    for (i in 0 until k) if (top[i] != Float.NEGATIVE_INFINITY) { sum += top[i]; count++ }
    return if (count == 0) Float.NEGATIVE_INFINITY else sum / count
}

/**
 * Самопроверка на подтверждённых лицах (leave-one-out): каждое подтверждённое лицо «прячется»
 * и привязывается k-NN к остальным. Возвращает (узнано верно, отнесено к другому, не привязано).
 */
fun knnSelfCheck(vectors: FloatArray, dim: Int, examples: Map<Long, IntArray>, maxChecks: Int = 300): Triple<Int, Int, Int> {
    var correct = 0
    var wrong = 0
    var none = 0
    // Равномерная выборка лиц: полная проверка — O(L²) при тысячах подтверждённых лиц.
    val all = examples.flatMap { (person, faces) -> faces.map { person to it } }
    val step = maxOf(1, all.size / maxChecks)
    for ((person, face) in all.filterIndexed { i, _ -> i % step == 0 }) {
        val without = examples.mapValues { (p, fs) -> if (p == person) fs.filter { it != face }.toIntArray() else fs }
        when (knnAssign(vectors, dim, intArrayOf(face), without, isRejected = { _, _ -> false })[face]) {
            person -> correct++
            null -> none++
            else -> wrong++
        }
    }
    return Triple(correct, wrong, none)
}

/**
 * Образцы для k-NN — только лица, согласованные с **основной частью** подтверждённой группы.
 *
 * Внутри подтверждённых лиц человека — группировка средней связью ([modeLinkSimilarity]);
 * основная подгруппа — самая крупная. Подгруппы, чей центр похож на центр основной
 * ≥ [modeSimilarity], — тот же человек в других условиях (очки, возраст; на реальных лицах
 * ≈ 0.55–0.67) и остаются образцами. Остальные — вероятно, чужие лица, по ошибке оказавшиеся
 * подтверждёнными (центры разных людей ≈ 0.1–0.3): они не притягивают к человеку новых
 * чужих лиц. Проверка «похоже хотя бы на пару соседей» не работает: ошибочно подтверждённые
 * лица одного чужого человека похожи друг на друга.
 *
 * @return согласованные образцы и число отсеянных по людям.
 */
fun consistentExamples(
    vectors: FloatArray,
    dim: Int,
    examples: Map<Long, IntArray>,
    modeLinkSimilarity: Float = MODE_LINK_SIMILARITY,
    modeSimilarity: Float = MODE_SIMILARITY,
): Pair<Map<Long, IntArray>, Map<Long, Int>> {
    val dropped = HashMap<Long, Int>()
    val kept = examples.mapValues { (person, faces) ->
        if (faces.size <= 2) return@mapValues faces
        val sub = FloatArray(faces.size * dim)
        faces.forEachIndexed { i, f -> vectors.copyInto(sub, i * dim, f * dim, (f + 1) * dim) }
        val labels = averageLinkage(sub, faces.size, dim, modeLinkSimilarity)
        val groups = faces.indices.groupBy { labels[it] }.values
        fun centroid(members: List<Int>) =
            VectorMath.l2Normalize(FloatArray(dim) { k -> members.sumOf { sub[it * dim + k].toDouble() }.toFloat() })
        val main = centroid(groups.maxBy { it.size })
        val consistent = groups
            .filter { members -> VectorMath.dot(main, centroid(members)) >= modeSimilarity }
            .flatten()
            .map { faces[it] }
            .sorted()
            .toIntArray()
        dropped[person] = faces.size - consistent.size
        consistent
    }
    return kept to dropped
}

/**
 * Параметры k-NN: сходство SFace «тот же человек» ≥ 0.363 (порог OpenCV) — для привязки без
 * участия пользователя берём строже; качество видно в логе по самопроверке [knnSelfCheck].
 */
const val KNN_K = 3
const val KNN_THRESHOLD = 0.42f
const val KNN_MARGIN = 0.05f
/** Внутренние подгруппы подтверждённого человека — тем же порогом, что и основная группировка. */
const val MODE_LINK_SIMILARITY = 0.35f
/** Подгруппа — тот же человек, если её центр похож на центр основной подгруппы не меньше этого. */
const val MODE_SIMILARITY = 0.45f
