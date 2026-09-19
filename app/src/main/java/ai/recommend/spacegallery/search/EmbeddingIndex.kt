package ai.recommend.spacegallery.search

import ai.recommend.spacegallery.data.db.AnalysisDao
import ai.recommend.spacegallery.ml.VectorMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.PriorityQueue

/**
 * In-memory индекс эмбеддингов с полным перебором (brute-force cosine).
 * Для галереи до ~50k элементов это ~10–30 мс на запрос и ~100 МБ RAM (float32, D=512).
 *
 * TODO: при росте коллекции — float16/int8-квантование векторов или HNSW.
 */
class EmbeddingIndex(private val dao: AnalysisDao) {

    private class Snapshot(val ids: LongArray, val dim: Int, val matrix: FloatArray) {
        val rowById: Map<Long, Int> = ids.withIndex().associate { (row, id) -> id to row }
    }

    private val mutex = Mutex()
    @Volatile private var snapshot: Snapshot? = null

    /** Сбросить кеш — вызывается после индексации новых медиа. */
    fun invalidate() {
        snapshot = null
    }

    suspend fun size(): Int = load().ids.size

    suspend fun vectorOf(mediaId: Long): FloatArray? {
        val s = load()
        val row = s.rowById[mediaId] ?: return null
        return s.matrix.copyOfRange(row * s.dim, (row + 1) * s.dim)
    }

    /** Top-K ближайших по косинусной близости. */
    suspend fun search(
        query: FloatArray,
        limit: Int,
        minScore: Float = Float.NEGATIVE_INFINITY,
        exclude: Set<Long> = emptySet(),
        /** Искать только среди этих id (фильтры поиска); null — везде. */
        include: Set<Long>? = null,
    ): List<Pair<Long, Float>> {
        val s = load()
        if (s.ids.isEmpty() || query.size != s.dim) return emptyList()
        return withContext(Dispatchers.Default) {
            val heap = PriorityQueue<Pair<Long, Float>>(limit + 1, compareBy { it.second })
            for (row in s.ids.indices) {
                val id = s.ids[row]
                if (id in exclude || (include != null && id !in include)) continue
                val score = VectorMath.dot(query, s.matrix, row * s.dim)
                if (score < minScore) continue
                if (heap.size < limit) {
                    heap += id to score
                } else if (score > heap.peek()!!.second) {
                    heap.poll()
                    heap += id to score
                }
            }
            heap.sortedByDescending { it.second }
        }
    }

    private suspend fun load(): Snapshot {
        snapshot?.let { return it }
        return mutex.withLock {
            snapshot ?: withContext(Dispatchers.IO) {
                val rows = dao.getAllEmbeddings()
                val dim = rows.firstOrNull()?.embedding?.size?.div(4) ?: 0
                val valid = rows.filter { it.embedding.size == dim * 4 }
                val matrix = FloatArray(valid.size * dim)
                valid.forEachIndexed { i, row ->
                    VectorMath.fromBytes(row.embedding).copyInto(matrix, i * dim)
                }
                Snapshot(LongArray(valid.size) { valid[it].mediaId }, dim, matrix)
            }.also { snapshot = it }
        }
    }
}
