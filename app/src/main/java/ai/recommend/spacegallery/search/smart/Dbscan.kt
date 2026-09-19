package ai.recommend.spacegallery.search.smart

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Соседи точки в пределах eps: индексы и косинусная близость (для повторного прохода
 * с меньшим eps без пересчёта).
 */
class Neighbors(val indices: IntArray, val similarities: FloatArray)

/**
 * Граф соседей по косинусному расстоянию (1 - dot) для L2-нормализованных векторов [vectors]
 * (n × dim, построчно). Полный перебор, но каждая пара считается один раз (расстояние
 * симметрично), скалярное произведение развёрнуто на 4 аккумулятора; работа распределена по ядрам.
 */
suspend fun cosineNeighbors(vectors: FloatArray, n: Int, dim: Int, eps: Float): Array<Neighbors> =
    withContext(Dispatchers.Default) {
        val minSimilarity = 1f - eps
        val chunks = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
        // Каждый поток собирает свои пары (i < j); строки чередуются, чтобы уравнять нагрузку
        // (у ранних строк больше j).
        val pairs = coroutineScope {
            (0 until chunks).map { chunk ->
                async {
                    val left = IntArrayBuilder()
                    val right = IntArrayBuilder()
                    val sims = FloatArrayBuilder()
                    var i = chunk
                    while (i < n) {
                        val a = i * dim
                        for (j in i + 1 until n) {
                            val dot = dot(vectors, a, j * dim, dim)
                            if (dot >= minSimilarity) {
                                left.add(i)
                                right.add(j)
                                sims.add(dot)
                            }
                        }
                        i += chunks
                    }
                    Triple(left.toArray(), right.toArray(), sims.toArray())
                }
            }.awaitAll()
        }
        // Списки смежности: пара (i, j) даёт соседа обеим точкам.
        val degree = IntArray(n)
        for ((l, r, _) in pairs) {
            for (k in l.indices) {
                degree[l[k]]++
                degree[r[k]]++
            }
        }
        val indices = Array(n) { IntArray(degree[it]) }
        val similarities = Array(n) { FloatArray(degree[it]) }
        val fill = IntArray(n)
        for ((l, r, sim) in pairs) {
            for (k in l.indices) {
                val i = l[k]
                val j = r[k]
                indices[i][fill[i]] = j
                similarities[i][fill[i]++] = sim[k]
                indices[j][fill[j]] = i
                similarities[j][fill[j]++] = sim[k]
            }
        }
        Array(n) { Neighbors(indices[it], similarities[it]) }
    }

private fun dot(v: FloatArray, a: Int, b: Int, dim: Int): Float {
    var s0 = 0f
    var s1 = 0f
    var s2 = 0f
    var s3 = 0f
    var k = 0
    while (k + 3 < dim) {
        s0 += v[a + k] * v[b + k]
        s1 += v[a + k + 1] * v[b + k + 1]
        s2 += v[a + k + 2] * v[b + k + 2]
        s3 += v[a + k + 3] * v[b + k + 3]
        k += 4
    }
    while (k < dim) {
        s0 += v[a + k] * v[b + k]
        k++
    }
    return (s0 + s1) + (s2 + s3)
}

/**
 * DBSCAN по готовому графу соседей. [minPts] считает и саму точку (как min_samples в scikit-learn).
 * Возвращает метку кластера для каждой точки, -1 — шум.
 *
 * @param members подмножество точек, на котором запускается алгоритм (для дробления кластера);
 * @param minSimilarity соседи с меньшей близостью игнорируются (эффективный eps для прохода).
 */
fun dbscan(
    neighbors: Array<Neighbors>,
    minPts: Int,
    members: IntArray = IntArray(neighbors.size) { it },
    minSimilarity: Float = -1f,
): IntArray {
    val inSubset = BooleanArray(neighbors.size).also { flags -> members.forEach { flags[it] = true } }
    fun neighborsOf(p: Int): IntArray {
        val nb = neighbors[p]
        val out = IntArrayBuilder()
        for (k in nb.indices.indices) {
            val q = nb.indices[k]
            if (inSubset[q] && nb.similarities[k] >= minSimilarity) out.add(q)
        }
        return out.toArray()
    }

    val labels = IntArray(neighbors.size) { UNVISITED }
    var cluster = 0
    for (p in members) {
        if (labels[p] != UNVISITED) continue
        val seeds = neighborsOf(p)
        if (seeds.size + 1 < minPts) {
            labels[p] = NOISE
            continue
        }
        labels[p] = cluster
        val queue = ArrayDeque<Int>().apply { seeds.forEach(::addLast) }
        while (queue.isNotEmpty()) {
            val q = queue.removeFirst()
            if (labels[q] == NOISE) labels[q] = cluster // граничная точка
            if (labels[q] != UNVISITED) continue
            labels[q] = cluster
            val qNeighbors = neighborsOf(q)
            if (qNeighbors.size + 1 >= minPts) qNeighbors.forEach(queue::addLast) // ядровая точка
        }
        cluster++
    }
    // Точки вне подмножества — шум для вызывающего кода.
    for (i in labels.indices) if (labels[i] == UNVISITED) labels[i] = NOISE
    return labels
}

const val NOISE = -1
private const val UNVISITED = -2

private class IntArrayBuilder {
    private var data = IntArray(16)
    private var size = 0
    fun add(v: Int) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = v
    }
    fun clear() { size = 0 }
    fun toArray(): IntArray = data.copyOf(size)
}

private class FloatArrayBuilder {
    private var data = FloatArray(16)
    private var size = 0
    fun add(v: Float) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = v
    }
    fun clear() { size = 0 }
    fun toArray(): FloatArray = data.copyOf(size)
}
