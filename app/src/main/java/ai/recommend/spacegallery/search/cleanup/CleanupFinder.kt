package ai.recommend.spacegallery.search.cleanup

import ai.recommend.spacegallery.data.db.AnalysisDao
import ai.recommend.spacegallery.data.repository.MediaRepository
import ai.recommend.spacegallery.domain.DuplicateGroup
import ai.recommend.spacegallery.domain.MediaItem
import ai.recommend.spacegallery.domain.MediaType
import ai.recommend.spacegallery.ml.VectorMath
import ai.recommend.spacegallery.ml.hash.PerceptualHasher
import ai.recommend.spacegallery.search.EmbeddingIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Что можно удалить: группы (оставить лучшее) и списки для просмотра. */
class CleanupReport(
    /** Копии одного кадра (пересжатые, пересланные, сохранённые дважды). */
    val copies: List<DuplicateGroup>,
    /** Серии почти одинаковых снимков, снятых подряд. */
    val series: List<DuplicateGroup>,
    /** Размытые и очень тёмные фото — от худших к лучшим. */
    val poor: List<MediaItem>,
    val screenshots: List<MediaItem>,
    /** Большие видео — от самых больших. */
    val largeVideos: List<MediaItem>,
)

/**
 * Поиск кандидатов на удаление среди видимых медиа (без скрытых и деликатных).
 *
 * Прежний поиск дубликатов (объединение по dHash ≤ 6 и цепочкам серий) на реальной медиатеке
 * (11.7k фото, 2026-09-20) склеивал до 121 фото в группу: 690 из 941 пар «похожих хэшей» были
 * сняты с разницей больше суток — однотонные и тёмные кадры с почти одинаковым хэшем.
 */
class CleanupFinder(
    private val analysisDao: AnalysisDao,
    private val index: EmbeddingIndex,
    private val repository: MediaRepository,
) {
    suspend fun find(): CleanupReport = withContext(Dispatchers.Default) {
        val items = repository.observeTimeline().first()
        val screenshots = items.filter(::isScreenshot)
        val shotIds = screenshots.mapTo(HashSet()) { it.id }
        val quality = analysisDao.getAllQuality().associateBy { it.mediaId }
        val vectors = HashMap<Long, FloatArray?>()
        suspend fun vec(item: MediaItem) = vectors.getOrPut(item.id) { index.vectorOf(item.id) }

        val copies = findCopies(items, ::vec)
        val photos = items.filter { it.type == MediaType.IMAGE && it.id !in shotIds }
        val series = findSeries(photos, ::vec) { quality[it.id]?.sharpness }
        val poor = photos
            .filter { item -> quality[item.id]?.let { it.sharpness < BLURRY_SHARPNESS || it.brightness < DARK_BRIGHTNESS } == true }
            .sortedBy { quality.getValue(it.id).sharpness }
        val largeVideos = items.filter { it.type == MediaType.VIDEO && it.sizeBytes >= LARGE_VIDEO_BYTES }
            .sortedByDescending { it.sizeBytes }
        CleanupReport(copies, series, poor, screenshots, largeVideos)
    }

    /**
     * Копии: dHash отличается не более чем на [COPY_HAMMING] бит, те же пропорции и тип, а
     * эмбеддинги CLIP почти совпадают — это отсекает однотонные кадры с похожим хэшем.
     * Кандидаты — по совпадению хотя бы одной 16-битной четверти хэша (при ≤ 3 отличающихся
     * битах одна из четырёх четвертей обязательно совпадает): без перебора всех пар.
     */
    private suspend fun findCopies(items: List<MediaItem>, vec: suspend (MediaItem) -> FloatArray?): List<DuplicateGroup> {
        val byId = items.associateBy { it.id }
        val hashes = analysisDao.getAllHashes()
            .filter { it.mediaId in byId && java.lang.Long.bitCount(it.perceptualHash) in FEATURE_BITS }
        val uf = UnionFind(hashes.size)
        val buckets = HashMap<Long, MutableList<Int>>()
        for ((i, h) in hashes.withIndex()) {
            for (q in 0 until 4) buckets.getOrPut((q.toLong() shl 16) or ((h.perceptualHash ushr (16 * q)) and 0xFFFF)) { ArrayList() } += i
        }
        for (bucket in buckets.values) {
            if (bucket.size < 2) continue
            for (x in bucket.indices) for (y in x + 1 until bucket.size) {
                val i = bucket[x]
                val j = bucket[y]
                if (uf.find(i) == uf.find(j)) continue
                if (PerceptualHasher.hammingDistance(hashes[i].perceptualHash, hashes[j].perceptualHash) > COPY_HAMMING) continue
                val a = byId.getValue(hashes[i].mediaId)
                val b = byId.getValue(hashes[j].mediaId)
                if (!sameShape(a, b)) continue
                val va = vec(a)
                val vb = vec(b)
                if (va != null && vb != null && VectorMath.dot(va, vb) < COPY_SIMILARITY) continue
                uf.union(i, j)
            }
        }
        return hashes.indices.groupBy { uf.find(it) }.values
            .filter { it.size > 1 }
            .map { group ->
                val groupItems = group.map { byId.getValue(hashes[it].mediaId) }.sortedByDescending { it.dateTaken }
                DuplicateGroup(groupItems, suggestedKeep = groupItems.maxWith(copyPreference))
            }
            .sortedByDescending { g -> g.items.sumOf { it.sizeBytes } - g.suggestedKeep.sizeBytes }
    }

    /**
     * Серии: снимки подряд (не дальше [SERIES_GAP_MS] от предыдущего), каждый похож и на
     * предыдущий, и на первый кадр серии — поэтому серия не «уползает» цепочкой.
     * Оставить предлагается самый резкий кадр.
     */
    private suspend fun findSeries(
        photos: List<MediaItem>,
        vec: suspend (MediaItem) -> FloatArray?,
        sharpness: (MediaItem) -> Float?,
    ): List<DuplicateGroup> {
        val result = ArrayList<DuplicateGroup>()
        var current = ArrayList<MediaItem>()
        var leader: FloatArray? = null
        var previous: FloatArray? = null
        fun close() {
            if (current.size > 1) {
                val best = current.maxWith(
                    compareBy<MediaItem> { it.isFavorite }.thenBy { sharpness(it) ?: 0f }.thenBy { it.width.toLong() * it.height }
                )
                result += DuplicateGroup(current.sortedByDescending { it.dateTaken }, best)
            }
            current = ArrayList()
        }
        for (item in photos.sortedBy { it.dateTaken }) {
            val v = vec(item) ?: continue
            val last = current.lastOrNull()
            val joins = last != null && item.dateTaken - last.dateTaken <= SERIES_GAP_MS &&
                VectorMath.dot(v, previous!!) >= SERIES_SIMILARITY && VectorMath.dot(v, leader!!) >= SERIES_SIMILARITY
            if (!joins) {
                close()
                leader = v
            }
            current += item
            previous = v
        }
        close()
        return result.sortedByDescending { it.items.first().dateTaken }
    }

    private fun sameShape(a: MediaItem, b: MediaItem): Boolean {
        if (a.type != b.type) return false
        if (a.type == MediaType.VIDEO && abs(a.durationMs - b.durationMs) > 1_000) return false
        if (a.width <= 0 || a.height <= 0 || b.width <= 0 || b.height <= 0) return true
        // Поворот (EXIF) может поменять стороны местами — сравниваем соотношение длинной к короткой.
        val ra = max(a.width, a.height).toFloat() / min(a.width, a.height)
        val rb = max(b.width, b.height).toFloat() / min(b.width, b.height)
        return abs(ra - rb) / ra <= SHAPE_TOLERANCE
    }

    /** Оставить: избранное, затем большее разрешение, затем больший файл, затем более раннее. */
    private val copyPreference = compareBy<MediaItem> { it.isFavorite }
        .thenBy { it.width.toLong() * it.height }
        .thenBy { it.sizeBytes }
        .thenByDescending { it.dateTaken }

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

    companion object {
        const val COPY_HAMMING = 3
        const val COPY_SIMILARITY = 0.95f
        const val SHAPE_TOLERANCE = 0.03f
        /** Хэш почти из одних нулей или единиц — однотонный кадр без деталей: не сравниваем. */
        val FEATURE_BITS = 5..59

        const val SERIES_GAP_MS = 60_000L
        const val SERIES_SIMILARITY = 0.92f

        /**
         * Пороги по распределению на реальной медиатеке (11k фото, 2026-09-20): резкость
         * p1 = 98, p5 = 255, медиана 2260; яркость p1 = 0.063. Берём ~1% худших по резкости и
         * почти чёрные кадры — это список для просмотра, заранее ничего не отмечается.
         */
        const val BLURRY_SHARPNESS = 100f
        const val DARK_BRIGHTNESS = 0.05f
        const val LARGE_VIDEO_BYTES = 100L * 1024 * 1024

        private val SCREENSHOT_MARKERS = listOf("screenshot", "screen_record", "screenrecord", "screen record", "скриншот")

        fun isScreenshot(item: MediaItem): Boolean =
            SCREENSHOT_MARKERS.any { item.albumName.contains(it, ignoreCase = true) || item.displayName.contains(it, ignoreCase = true) }
    }
}
