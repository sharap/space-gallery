package ai.recommend.spacegallery.search.people

/**
 * Агломеративная кластеризация со средней связью (average linkage / UPGMA) по косинусному
 * сходству L2-нормализованных векторов: группы сливаются, только если их элементы похожи
 * **в среднем** ≥ [minSimilarity]. В отличие от DBSCAN, одна похожая пара не склеивает двух
 * разных людей цепочкой.
 *
 * Алгоритм nearest-neighbor chain: O(n²) времени и памяти (плотная матрица сходства),
 * результат совпадает с полным перебором (средняя связь «сводима» — reducible). Так как
 * высоты слияний монотонны, разрез на пороге = компоненты слияний со сходством ≥ порога.
 *
 * Ручные правки пользователя — ограничения:
 * - [anchors]: точки с одинаковой меткой ≥ 0 (подтверждённые лица человека) изначально в одной
 *   группе независимо от сходства; группы разных меток никогда не сливаются;
 * - [cannotLink]: пары точек, которые не должны оказаться вместе (лицо «это не он» и
 *   подтверждённые лица того человека). Их сходство = −∞; средняя связь это сохраняет:
 *   (na·(−∞) + nb·x)/(na+nb) = −∞, так что запрет переходит на все объединения.
 *
 * @return метка кластера для каждой точки (0 until число кластеров), одиночки — свои кластеры.
 */
fun averageLinkage(
    vectors: FloatArray,
    n: Int,
    dim: Int,
    minSimilarity: Float,
    anchors: IntArray? = null,
    cannotLink: List<Pair<Int, Int>> = emptyList(),
): IntArray {
    if (n == 0) return IntArray(0)
    // Матрица сходства; строка i после слияний хранит сходство кластера с корнем i.
    val sim = FloatArray(n * n)
    for (i in 0 until n) {
        for (j in i + 1 until n) {
            var dot = 0f
            val a = i * dim
            val b = j * dim
            for (k in 0 until dim) dot += vectors[a + k] * vectors[b + k]
            sim[i * n + j] = dot
            sim[j * n + i] = dot
        }
    }
    val size = IntArray(n) { 1 }
    val active = BooleanArray(n) { true }
    var activeCount = n
    val parent = IntArray(n) { it } // union-find по слияниям выше порога
    fun find(x: Int): Int {
        var r = x
        while (parent[r] != r) {
            parent[r] = parent[parent[r]]
            r = parent[r]
        }
        return r
    }

    fun merge(a: Int, b: Int, union: Boolean) {
        if (union) parent[find(b)] = find(a)
        val na = size[a].toFloat()
        val nb = size[b].toFloat()
        for (c in 0 until n) {
            if (!active[c] || c == a || c == b) continue
            // Ланс–Уильямс для средней связи.
            val merged = (na * sim[a * n + c] + nb * sim[b * n + c]) / (na + nb)
            sim[a * n + c] = merged
            sim[c * n + a] = merged
        }
        size[a] += size[b]
        active[b] = false
        activeCount--
    }

    for ((i, j) in cannotLink) {
        sim[i * n + j] = Float.NEGATIVE_INFINITY
        sim[j * n + i] = Float.NEGATIVE_INFINITY
    }
    if (anchors != null) {
        // Подтверждённые группы собираются заранее (представитель — первая точка группы)…
        val representative = HashMap<Int, Int>()
        for (i in 0 until n) {
            val group = anchors[i]
            if (group < 0) continue
            val rep = representative[group]
            if (rep == null) representative[group] = i else merge(rep, i, union = true)
        }
        // …и разные подтверждённые люди никогда не сливаются автоматически.
        val reps = representative.values.toList()
        for (x in reps.indices) for (y in x + 1 until reps.size) {
            sim[reps[x] * n + reps[y]] = Float.NEGATIVE_INFINITY
            sim[reps[y] * n + reps[x]] = Float.NEGATIVE_INFINITY
        }
    }

    val chain = IntArray(n)
    var chainSize = 0
    while (activeCount > 1) {
        if (chainSize == 0) {
            chain[chainSize++] = (0 until n).first { active[it] }
        }
        if (activeCount <= 1) break
        val a = chain[chainSize - 1]
        val prev = if (chainSize >= 2) chain[chainSize - 2] else -1
        // Ближайший (самый похожий) активный кластер; при равенстве предпочитаем предыдущий в цепочке.
        var best = -1
        var bestSim = Float.NEGATIVE_INFINITY
        for (c in 0 until n) {
            if (c == a || !active[c]) continue
            val s = sim[a * n + c]
            // best == -1: при запретах все сходства могут быть −∞ — сосед всё равно нужен.
            if (best == -1 || s > bestSim || (s == bestSim && c == prev)) {
                best = c
                bestSim = s
            }
        }
        if (best == prev) {
            // Взаимные ближайшие соседи — сливаем prev в a (строка a становится новым кластером).
            chainSize -= 2
            merge(a, prev, union = bestSim >= minSimilarity)
        } else {
            chain[chainSize++] = best
        }
    }

    val labels = IntArray(n)
    val ids = HashMap<Int, Int>()
    for (i in 0 until n) labels[i] = ids.getOrPut(find(i)) { ids.size }
    return labels
}
