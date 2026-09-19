package ai.recommend.spacegallery.search.smart

import ai.recommend.spacegallery.data.db.SmartAlbumDao
import ai.recommend.spacegallery.data.db.SmartAlbumEntity
import ai.recommend.spacegallery.data.db.SmartAlbumMemberEntity
import ai.recommend.spacegallery.data.settings.SettingsRepository
import ai.recommend.spacegallery.ml.VectorMath
import ai.recommend.spacegallery.ml.onnx.ModelId
import ai.recommend.spacegallery.ml.onnx.ModelProvider
import ai.recommend.spacegallery.ml.text.TextEmbedder
import ai.recommend.spacegallery.perf.PerfStats
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Умные альбомы: DBSCAN по CLIP-эмбеддингам + название темы zero-shot.
 *
 * eps задаётся в настройках (по умолчанию 0.14 — подобрано на реальной медиатеке ~6300 файлов,
 * 2026-09-19: ~60 плотных групп без слипания; при eps ≥ 0.16 DBSCAN «цепочками» собирает
 * 35–70% медиатеки в один кластер). Слишком крупные кластеры дробятся повторно с меньшим eps.
 */
class SmartAlbumBuilder(
    private val dao: SmartAlbumDao,
    private val settings: SettingsRepository,
    private val textEmbedder: TextEmbedder,
    private val models: ModelProvider,
    /** Кеш эмбеддингов тем: без него каждый пересчёт загружает текстовую модель (~5 с). */
    private val topicCacheFile: File,
) {
    /**
     * Нужен ли пересчёт после индексации: альбомов ещё нет, накопилось [REBUILD_AFTER_CHANGES]
     * изменений или прошли сутки с изменениями. Иначе каждое новое фото с камеры стоило бы
     * ~10–20 с работы процессора.
     */
    suspend fun shouldRebuild(newlyAnalyzed: Int, now: Long = System.currentTimeMillis()): Boolean {
        if (newlyAnalyzed > 0) settings.addSmartAlbumPendingChanges(newlyAnalyzed)
        if (dao.count() == 0) return true
        val (builtAt, pending) = settings.smartAlbumState()
        return pending >= REBUILD_AFTER_CHANGES || (pending > 0 && now - builtAt >= REBUILD_INTERVAL_MS)
    }
    private val mutex = Mutex()
    private val _isRebuilding = MutableStateFlow(false)

    /** Идёт пересчёт — для индикатора в настройках. */
    val isRebuilding: StateFlow<Boolean> = _isRebuilding.asStateFlow()

    /** Пересчитать умные альбомы; параллельные вызовы выполняются по очереди. */
    suspend fun rebuild() = mutex.withLock {
        _isRebuilding.value = true
        try {
            rebuildLocked()
        } finally {
            _isRebuilding.value = false
        }
    }

    private suspend fun rebuildLocked() {
        val s = settings.current()
        val eps = s.smartAlbumEps
        val rows = dao.getClusterInput(s.hideSensitive, s.sensitiveThreshold)
        if (rows.size < MIN_PTS * 2) {
            dao.replaceAll(emptyList(), emptyList())
            return
        }
        val dim = rows.first().embedding.size / 4
        val valid = rows.filter { it.embedding.size == dim * 4 }
        val n = valid.size
        val vectors = FloatArray(n * dim)
        valid.forEachIndexed { i, row -> VectorMath.fromBytes(row.embedding).copyInto(vectors, i * dim) }

        val neighbors = PerfStats.measure("smart.neighbors") { cosineNeighbors(vectors, n, dim, eps) }
        val clusters = PerfStats.measure("smart.dbscan") {
            cluster(neighbors, IntArray(n) { it }, eps, depth = 0, maxSize = maxOf(REFINE_MIN_SIZE, n * REFINE_FRACTION / 100))
        }.sortedByDescending { it.size }

        val topics = PerfStats.measure("smart.topics") { topicEmbeddings() }
        val albums = ArrayList<SmartAlbumEntity>(clusters.size)
        val members = ArrayList<SmartAlbumMemberEntity>()
        val names = HashMap<String, Int>()
        clusters.forEachIndexed { index, cluster ->
            val centroid = VectorMath.l2Normalize(FloatArray(dim) { k -> cluster.sumOf { vectors[it * dim + k].toDouble() }.toFloat() })
            // Члены по близости к центру: первый — медоид (обложка).
            val ordered = cluster.sortedByDescending { VectorMath.dot(centroid, vectors, it * dim) }
            val dates = cluster.map { valid[it].dateTaken }
            val id = index + 1L
            albums += SmartAlbumEntity(
                id = id,
                name = uniqueName(topicTitle(centroid, topics), dates.min(), dates.max(), names),
                coverMediaId = valid[ordered.first()].mediaId,
                position = index,
            )
            ordered.forEachIndexed { pos, i -> members += SmartAlbumMemberEntity(id, valid[i].mediaId, pos) }
        }
        dao.replaceAll(albums, members)
        settings.markSmartAlbumsBuilt(System.currentTimeMillis())
        Log.i(TAG, "Умные альбомы: ${albums.size} из $n медиа, в группах ${members.size}")
    }

    /** DBSCAN с повторным дроблением слишком крупных кластеров (меньший eps, до 2 уровней). */
    private fun cluster(neighbors: Array<Neighbors>, subset: IntArray, eps: Float, depth: Int, maxSize: Int): List<IntArray> {
        val labels = dbscan(neighbors, MIN_PTS, subset, minSimilarity = 1f - eps)
        return subset.filter { labels[it] != NOISE }.groupBy { labels[it] }.values.flatMap { group ->
            val points = group.toIntArray()
            if (points.size > maxSize && depth < MAX_REFINE_DEPTH && eps - REFINE_EPS_STEP >= MIN_REFINE_EPS) {
                cluster(neighbors, points, eps - REFINE_EPS_STEP, depth + 1, maxSize).ifEmpty { listOf(points) }
            } else {
                listOf(points)
            }
        }
    }

    /** Эмбеддинги тем из кеша или заново (ключ — словарь + отпечаток текстовой модели). */
    private suspend fun topicEmbeddings(): List<Pair<Topic, FloatArray>> {
        val model = models.fingerprint(ModelId.CLIP_TEXT)?.let { "en:$it" }
            ?: models.fingerprint(ModelId.CLIP_TEXT_MULTILINGUAL)?.let { "multi:$it" }
            ?: return emptyList()
        val key = "$model|${SmartAlbumTopics.TEMPLATES}|${SmartAlbumTopics.ALL}".hashCode().toString()
        readTopicCache(key)?.let { return it }
        return computeTopicEmbeddings().also { writeTopicCache(key, it) }
    }

    private fun readTopicCache(key: String): List<Pair<Topic, FloatArray>>? = runCatching {
        DataInputStream(topicCacheFile.inputStream().buffered()).use { input ->
            if (input.readUTF() != key) return null
            val count = input.readInt()
            val dim = input.readInt()
            val byTitle = SmartAlbumTopics.ALL.associateBy { it.title }
            List(count) {
                val topic = byTitle.getValue(input.readUTF())
                topic to FloatArray(dim) { input.readFloat() }
            }
        }
    }.getOrNull()

    private fun writeTopicCache(key: String, topics: List<Pair<Topic, FloatArray>>) {
        if (topics.isEmpty()) return
        runCatching {
            DataOutputStream(topicCacheFile.outputStream().buffered()).use { out ->
                out.writeUTF(key)
                out.writeInt(topics.size)
                out.writeInt(topics.first().second.size)
                topics.forEach { (topic, v) ->
                    out.writeUTF(topic.title)
                    v.forEach(out::writeFloat)
                }
            }
        }.onFailure { Log.w(TAG, "Не удалось сохранить кеш тем", it) }
    }

    /** Английский CLIP с ансамблем шаблонов, иначе многоязычный энкодер по русским названиям. */
    private suspend fun computeTopicEmbeddings(): List<Pair<Topic, FloatArray>> = SmartAlbumTopics.ALL.mapNotNull { topic ->
        val english = SmartAlbumTopics.TEMPLATES.mapNotNull { textEmbedder.embedEnglish(it.replace("{}", topic.prompt)) }
        val vector = if (english.isNotEmpty()) {
            VectorMath.l2Normalize(FloatArray(english.first().size) { k -> english.sumOf { it[k].toDouble() }.toFloat() })
        } else {
            textEmbedder.embedMultilingual(topic.title)
        }
        vector?.let { topic to it }
    }

    private fun topicTitle(centroid: FloatArray, topics: List<Pair<Topic, FloatArray>>): String? {
        val best = topics.maxByOrNull { (_, v) -> VectorMath.dot(v, centroid) } ?: return null
        return best.first.title.takeIf { VectorMath.dot(best.second, centroid) >= MIN_TOPIC_SCORE }
    }

    /** «Дорога», при повторе — «Дорога · 2023–2024» / «Дорога · авг 2025»; без темы — «Похожие фото · …». */
    private fun uniqueName(title: String?, from: Long, to: Long, used: HashMap<String, Int>): String {
        val period = formatPeriod(from, to)
        val base = title ?: "$GENERIC_TITLE · $period"
        val count = used.merge(base, 1, Int::plus)!!
        return when {
            count == 1 -> base
            title != null && used.merge("$base · $period", 1, Int::plus) == 1 -> "$base · $period"
            else -> "$base · $count"
        }
    }

    private fun formatPeriod(from: Long, to: Long): String {
        val zone = ZoneId.systemDefault()
        val start = Instant.ofEpochMilli(from).atZone(zone)
        val end = Instant.ofEpochMilli(to).atZone(zone)
        val locale = Locale.getDefault()
        return when {
            start.year == end.year && start.monthValue == end.monthValue ->
                DateTimeFormatter.ofPattern("LLL yyyy", locale).format(start)
            start.year == end.year -> start.year.toString()
            else -> "${start.year}–${end.year}"
        }
    }

    private companion object {
        const val TAG = "SmartAlbums"
        const val MIN_PTS = 6
        const val REFINE_EPS_STEP = 0.02f
        const val MIN_REFINE_EPS = 0.04f
        const val MAX_REFINE_DEPTH = 2
        const val REFINE_MIN_SIZE = 200
        const val REFINE_FRACTION = 5 // % медиатеки

        /** Ниже этой близости центр кластера не похож ни на одну тему. */
        const val MIN_TOPIC_SCORE = 0.25f
        const val GENERIC_TITLE = "Похожие фото"

        const val REBUILD_AFTER_CHANGES = 50
        const val REBUILD_INTERVAL_MS = 24 * 60 * 60 * 1000L
    }
}
