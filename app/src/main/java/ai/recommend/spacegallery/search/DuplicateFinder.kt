package ai.recommend.spacegallery.search

import ai.recommend.spacegallery.data.db.AnalysisDao
import ai.recommend.spacegallery.data.repository.MediaRepository
import ai.recommend.spacegallery.domain.DuplicateGroup
import ai.recommend.spacegallery.domain.MediaItem
import ai.recommend.spacegallery.ml.VectorMath
import ai.recommend.spacegallery.ml.hash.PerceptualHasher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Поиск дубликатов в два прохода:
 * 1. dHash — точные копии и пересжатые/уменьшенные версии (расстояние Хэмминга ≤ [maxHamming]);
 * 2. эмбеддинги CLIP — почти одинаковые кадры из серии (снятые в пределах [burstWindowMs]).
 */
class DuplicateFinder(
    private val analysisDao: AnalysisDao,
    private val index: EmbeddingIndex,
    private val repository: MediaRepository,
) {
    suspend fun findGroups(
        maxHamming: Int = 6,
        burstSimilarity: Float = 0.93f,
        burstWindowMs: Long = 2 * 60_000L,
    ): List<DuplicateGroup> = withContext(Dispatchers.Default) {
        val hashes = analysisDao.getAllHashes()
        val items = repository.getVisibleByIds(hashes.map { it.mediaId })
        val pos = items.withIndex().associate { (i, it) -> it.id to i }
        val uf = UnionFind(items.size)

        // Проход 1: O(n²) по 64-битным хэшам — дёшево даже для десятков тысяч.
        // TODO: BK-tree / multi-index hashing для очень больших коллекций.
        val hashList = hashes.filter { it.mediaId in pos }
        for (i in hashList.indices) {
            for (j in i + 1 until hashList.size) {
                if (PerceptualHasher.hammingDistance(hashList[i].perceptualHash, hashList[j].perceptualHash) <= maxHamming) {
                    uf.union(pos.getValue(hashList[i].mediaId), pos.getValue(hashList[j].mediaId))
                }
            }
        }

        // Проход 2: серии снимков — сравниваем только соседей по времени.
        val byTime = items.indices.sortedBy { items[it].dateTaken }
        val vectors = HashMap<Long, FloatArray?>()
        suspend fun vec(item: MediaItem) = vectors.getOrPut(item.id) { index.vectorOf(item.id) }
        for (a in byTime.indices) {
            val ia = items[byTime[a]]
            val va = vec(ia) ?: continue
            var b = a + 1
            while (b < byTime.size && items[byTime[b]].dateTaken - ia.dateTaken <= burstWindowMs) {
                val vb = vec(items[byTime[b]])
                if (vb != null && VectorMath.dot(va, vb) >= burstSimilarity) uf.union(byTime[a], byTime[b])
                b++
            }
        }

        items.indices.groupBy { uf.find(it) }.values
            .filter { it.size > 1 }
            .map { group ->
                val groupItems = group.map { items[it] }.sortedByDescending { it.dateTaken }
                DuplicateGroup(groupItems, suggestedKeep = groupItems.maxBy(::qualityScore))
            }
            .sortedByDescending { it.items.size }
    }

    /** Эвристика «лучшей копии»: большее разрешение, затем больший файл. */
    private fun qualityScore(item: MediaItem): Double =
        item.width.toDouble() * item.height + item.sizeBytes / 1e9

    private class UnionFind(n: Int) {
        private val parent = IntArray(n) { it }
        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) {
                parent[r] = parent[parent[r]]
                r = parent[r]
            }
            return r
        }
        fun union(a: Int, b: Int) {
            parent[find(a)] = find(b)
        }
    }
}
