package ai.recommend.spacegallery.search.people

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.math.min
import kotlin.random.Random

/**
 * Средняя связь без плотной матрицы сходства — для десятков тысяч лиц.
 *
 * Для косинуса среднее попарное сходство двух групп равно (ΣA · ΣB) / (|A|·|B|), где ΣX — сумма
 * векторов группы. Поэтому хранить n² сходств не нужно: достаточно сумм и списка кандидатов на
 * слияние (ближайшие соседи каждого лица). Результат совпадает с плотным [averageLinkage]:
 * проверено на реальной медиатеке (12 251 лицо — 1274 человека против 1275 у плотного варианта),
 * тогда как плотная матрица на этом объёме заняла бы 576 МБ.
 *
 * Ближайшие соседи ищутся по случайной проекции векторов в [PROJECTION_DIM] измерений (лемма
 * Джонсона — Линденштрауса: скалярные произведения сохраняются с небольшой ошибкой), а затем
 * кандидаты пересчитываются точно. Для 512-мерных векторов ArcFace это в несколько раз быстрее,
 * чем перебор «в лоб», который на телефоне занимал минуты.
 *
 * Ограничения пользователя:
 * - [anchors] — подтверждённые лица (метка ≥ 0) заранее в одной группе, разные метки не сливаются;
 * - [rejectedPersons] — «это не он»: лицо не должно попасть к этому подтверждённому человеку;
 * - [mediaOf] — два лица с одного снимка почти наверняка разные люди: такая пара при слиянии
 *   учитывается как [SAME_PHOTO_PENALTY] (мягкий штраф, а не запрет). Коллажи исключает
 *   вызывающий код, давая их лицам разные значения [mediaOf].
 *
 * @return метка группы для каждого лица (индекс её представителя).
 */
suspend fun sparseAverageLinkage(
    vectors: FloatArray,
    n: Int,
    dim: Int,
    minSimilarity: Float,
    mediaOf: LongArray,
    anchors: IntArray? = null,
    rejectedPersons: Map<Int, Set<Int>> = emptyMap(),
    neighbors: Int = NEIGHBORS,
): IntArray {
    if (n == 0) return IntArray(0)
    val parent = IntArray(n) { it }

    fun find(x: Int): Int {
        var r = x
        while (parent[r] != r) {
            parent[r] = parent[parent[r]]
            r = parent[r]
        }
        return r
    }

    // Состояние группы: сумма векторов, размер, сколько лиц с каждого снимка, метка
    // подтверждённого человека и люди, к которым её лица попасть не должны.
    val sums = vectors.copyOf(n * dim)
    val size = IntArray(n) { 1 }
    val mediaCount = Array(n) { hashMapOf(mediaOf[it] to 1) }
    val anchorOf = IntArray(n) { anchors?.getOrNull(it) ?: -1 }
    val rejected = arrayOfNulls<MutableSet<Int>>(n)
    for ((face, persons) in rejectedPersons) rejected[face] = persons.toMutableSet()
    val candidates = Array(n) { IntArray(0) }

    fun dotSums(a: Int, b: Int): Float {
        var s = 0f
        val ao = a * dim
        val bo = b * dim
        for (k in 0 until dim) s += sums[ao + k] * sums[bo + k]
        return s
    }

    /** Среднее сходство групп со штрафом за лица с одного снимка. */
    fun similarity(a: Int, b: Int): Float {
        var s = dotSums(a, b)
        val small = if (mediaCount[a].size <= mediaCount[b].size) a else b
        val large = if (small == a) b else a
        for ((media, count) in mediaCount[small]) {
            val other = mediaCount[large][media] ?: continue
            // Вклад таких пар заменяется штрафом (их собственное сходство обычно около нуля).
            s += count * other * (SAME_PHOTO_PENALTY - TYPICAL_SAME_PHOTO)
        }
        return s / (size[a].toFloat() * size[b])
    }

    fun allowed(a: Int, b: Int): Boolean = when {
        anchorOf[a] >= 0 && anchorOf[b] >= 0 -> anchorOf[a] == anchorOf[b] // разные подтверждённые — никогда
        anchorOf[a] >= 0 -> rejected[b]?.contains(anchorOf[a]) != true
        anchorOf[b] >= 0 -> rejected[a]?.contains(anchorOf[b]) != true
        else -> true
    }

    fun addCandidates(target: Int, extra: IntArray) {
        if (extra.isEmpty()) return
        val current = candidates[target]
        val merged = IntArray(min(current.size + extra.size, MAX_CANDIDATES))
        var size = 0
        for (source in arrayOf(current, extra)) {
            for (c in source) {
                if (size == merged.size) break
                var duplicate = false
                for (i in 0 until size) if (merged[i] == c) { duplicate = true; break }
                if (!duplicate) merged[size++] = c
            }
        }
        candidates[target] = if (size == merged.size) merged else merged.copyOf(size)
    }

    fun merge(a: Int, b: Int) {
        parent[b] = a
        val ao = a * dim
        val bo = b * dim
        for (k in 0 until dim) sums[ao + k] += sums[bo + k]
        size[a] += size[b]
        for ((media, count) in mediaCount[b]) mediaCount[a][media] = (mediaCount[a][media] ?: 0) + count
        mediaCount[b] = HashMap()
        if (anchorOf[a] < 0) anchorOf[a] = anchorOf[b]
        rejected[b]?.let { other -> rejected[a] = (rejected[a] ?: HashSet()).also { it += other } }
        addCandidates(a, candidates[b])
        candidates[b] = IntArray(0)
    }

    // Подтверждённые лица — сразу в одну группу.
    if (anchors != null) {
        val byAnchor = HashMap<Int, Int>()
        for (i in 0 until n) {
            val a = anchors.getOrNull(i) ?: -1
            if (a < 0) continue
            val root = byAnchor[a]
            if (root == null) byAnchor[a] = i else merge(root, find(i))
        }
    }

    val started = System.nanoTime()
    val nearest = nearestNeighbors(vectors, n, dim, neighbors, minSimilarity)
    val knnMs = (System.nanoTime() - started) / 1_000_000

    val queue = EdgeQueue(n * 4)
    for (i in 0 until n) {
        val list = nearest[i]
        if (list.isEmpty()) continue
        addCandidates(i, list)
        for (j in list) {
            addCandidates(j, intArrayOf(i))
            val a = find(i)
            val b = find(j)
            if (a != b) queue.push(similarity(a, b), a, b)
        }
    }

    var merges = 0
    while (queue.isNotEmpty()) {
        val cached = queue.topSimilarity()
        val x = queue.topFirst()
        val y = queue.topSecond()
        queue.pop()
        val a = find(x)
        val b = find(y)
        if (a == b || !allowed(a, b)) continue
        val s = similarity(a, b)
        if (s < minSimilarity) continue
        // Значение могло устареть после слияний — возвращаем пару с пересчитанным сходством.
        if (cached > s + EPSILON) {
            queue.push(s, a, b)
            continue
        }
        merge(a, b)
        merges++
        for (c in candidates[a]) {
            val root = find(c)
            if (root == a || !allowed(a, root)) continue
            queue.push(similarity(a, root), a, root)
        }
    }
    Log.i(
        TAG,
        "средняя связь: лиц $n (dim $dim), соседи за $knnMs мс, слияний $merges за " +
            "${(System.nanoTime() - started) / 1_000_000 - knnMs} мс",
    )
    return IntArray(n) { find(it) }
}

/**
 * Очередь пар на слияние на примитивных массивах: объектов здесь миллионы, и обёртки
 * (Triple, PriorityQueue) съедали десятки мегабайт кучи.
 */
private class EdgeQueue(capacity: Int) {
    private var sim = FloatArray(capacity.coerceAtLeast(16))
    private var first = IntArray(sim.size)
    private var second = IntArray(sim.size)
    private var count = 0

    fun isNotEmpty() = count > 0
    fun topSimilarity() = sim[0]
    fun topFirst() = first[0]
    fun topSecond() = second[0]

    fun push(value: Float, a: Int, b: Int) {
        if (count == sim.size) grow()
        var i = count++
        sim[i] = value
        first[i] = a
        second[i] = b
        while (i > 0) {
            val parent = (i - 1) / 2
            if (sim[parent] >= sim[i]) break
            swap(i, parent)
            i = parent
        }
    }

    fun pop() {
        count--
        if (count > 0) {
            sim[0] = sim[count]
            first[0] = first[count]
            second[0] = second[count]
            var i = 0
            while (true) {
                val left = 2 * i + 1
                val right = left + 1
                var best = i
                if (left < count && sim[left] > sim[best]) best = left
                if (right < count && sim[right] > sim[best]) best = right
                if (best == i) break
                swap(i, best)
                i = best
            }
        }
    }

    private fun swap(i: Int, j: Int) {
        val s = sim[i]; sim[i] = sim[j]; sim[j] = s
        val a = first[i]; first[i] = first[j]; first[j] = a
        val b = second[i]; second[i] = second[j]; second[j] = b
    }

    private fun grow() {
        sim = sim.copyOf(sim.size * 2)
        first = first.copyOf(sim.size)
        second = second.copyOf(sim.size)
    }
}

/**
 * Ближайшие соседи каждого вектора со сходством ≥ [minSimilarity]. Кандидаты ищутся по
 * случайной проекции (быстро), затем их сходство уточняется по исходным векторам.
 */
private suspend fun nearestNeighbors(
    vectors: FloatArray,
    n: Int,
    dim: Int,
    k: Int,
    minSimilarity: Float,
): Array<IntArray> = coroutineScope {
    val useProjection = dim > PROJECTION_DIM
    val searchDim = if (useProjection) PROJECTION_DIM else dim
    val search = if (useProjection) project(vectors, n, dim) else vectors
    // Проекция слегка занижает или завышает сходство — берём кандидатов с запасом.
    val searchThreshold = if (useProjection) minSimilarity - PROJECTION_MARGIN else minSimilarity
    val candidates = if (useProjection) k * 2 else k

    val blocks = (0 until n step BLOCK).map { start ->
        async(Dispatchers.Default) {
            val end = min(start + BLOCK, n)
            val query = FloatArray(searchDim)
            val heapSim = FloatArray(candidates)
            val heapIdx = IntArray(candidates)
            Array(end - start) { row ->
                val i = start + row
                System.arraycopy(search, i * searchDim, query, 0, searchDim)
                var size = 0
                for (j in 0 until n) {
                    if (j == i) continue
                    var s = 0f
                    val offset = j * searchDim
                    for (t in 0 until searchDim) s += query[t] * search[offset + t]
                    if (s < searchThreshold) continue
                    if (size < candidates) {
                        heapSim[size] = s
                        heapIdx[size] = j
                        size++
                        if (size == candidates) heapify(heapSim, heapIdx, size)
                    } else if (s > heapSim[0]) {
                        heapSim[0] = s
                        heapIdx[0] = j
                        siftDown(heapSim, heapIdx, size, 0)
                    }
                }
                if (size == 0) {
                    IntArray(0)
                } else {
                    val found = heapIdx.copyOf(size)
                    if (!useProjection) found else exactTop(vectors, dim, i, found, k, minSimilarity)
                }
            }
        }
    }
    val parts = blocks.awaitAll()
    Array(n) { i -> parts[i / BLOCK][i % BLOCK] }
}

/** Пересчёт кандидатов по исходным векторам: остаются [k] лучших со сходством ≥ [minSimilarity]. */
private fun exactTop(vectors: FloatArray, dim: Int, i: Int, found: IntArray, k: Int, minSimilarity: Float): IntArray {
    val sims = FloatArray(found.size)
    for ((index, j) in found.withIndex()) {
        var s = 0f
        val a = i * dim
        val b = j * dim
        for (t in 0 until dim) s += vectors[a + t] * vectors[b + t]
        sims[index] = s
    }
    val order = found.indices.sortedByDescending { sims[it] }
    val result = ArrayList<Int>(k)
    for (index in order) {
        if (sims[index] < minSimilarity || result.size == k) break
        result += found[index]
    }
    return result.toIntArray()
}

/** Случайная проекция в [PROJECTION_DIM] измерений (одна и та же при каждом запуске). */
private fun project(vectors: FloatArray, n: Int, dim: Int): FloatArray {
    val random = Random(PROJECTION_SEED)
    val scale = 1f / kotlin.math.sqrt(PROJECTION_DIM.toFloat())
    val matrix = FloatArray(dim * PROJECTION_DIM) { if (random.nextBoolean()) scale else -scale }
    val result = FloatArray(n * PROJECTION_DIM)
    for (i in 0 until n) {
        val source = i * dim
        val target = i * PROJECTION_DIM
        for (t in 0 until dim) {
            val v = vectors[source + t]
            if (v == 0f) continue
            val row = t * PROJECTION_DIM
            for (p in 0 until PROJECTION_DIM) result[target + p] += v * matrix[row + p]
        }
    }
    return result
}

private fun heapify(sim: FloatArray, idx: IntArray, size: Int) {
    for (i in size / 2 - 1 downTo 0) siftDown(sim, idx, size, i)
}

/** Куча минимумом вверх: в корне — худший из отобранных кандидатов. */
private fun siftDown(sim: FloatArray, idx: IntArray, size: Int, from: Int) {
    var i = from
    while (true) {
        val left = 2 * i + 1
        val right = left + 1
        var best = i
        if (left < size && sim[left] < sim[best]) best = left
        if (right < size && sim[right] < sim[best]) best = right
        if (best == i) return
        val s = sim[i]; sim[i] = sim[best]; sim[best] = s
        val j = idx[i]; idx[i] = idx[best]; idx[best] = j
        i = best
    }
}

private const val TAG = "People"

/** Сколько соседей держать как кандидатов на слияние: больше — точнее, но медленнее. */
const val NEIGHBORS = 50

/** Предел кандидатов у группы: без него у крупных групп список растёт неограниченно. */
private const val MAX_CANDIDATES = 96
private const val BLOCK = 256
private const val EPSILON = 1e-6f

/** Размерность случайной проекции для поиска соседей (у ArcFace векторы 512-мерные). */
private const val PROJECTION_DIM = 64
private const val PROJECTION_SEED = 20260920L
/** Запас на погрешность проекции при отборе кандидатов. */
private const val PROJECTION_MARGIN = 0.12f

/**
 * Чем заменяется сходство пары лиц с одного снимка. Отрицательное значение «перевешивает»
 * несколько похожих пар, но не запрещает слияние полностью.
 * На реальной медиатеке (12 251 лицо) штраф убрал 3/4 склеек таких пар без потери полноты.
 */
private const val SAME_PHOTO_PENALTY = -1f

/** Типичное сходство пары лиц с одного снимка — его вклад и заменяется штрафом. */
private const val TYPICAL_SAME_PHOTO = 0.1f
