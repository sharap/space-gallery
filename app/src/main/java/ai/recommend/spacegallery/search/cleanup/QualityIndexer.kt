package ai.recommend.spacegallery.search.cleanup

import ai.recommend.spacegallery.data.db.AnalysisDao
import ai.recommend.spacegallery.ml.image.BitmapLoader
import ai.recommend.spacegallery.ml.quality.ImageQuality
import ai.recommend.spacegallery.perf.PerfStats
import androidx.core.net.toUri
import androidx.room.withTransaction
import ai.recommend.spacegallery.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Оценка качества фото (резкость, яркость) для очистки: быстрый проход без нейросетей,
 * ~10–30 мс на фото. Как и поиск лиц, выполняется отдельно от основного анализа.
 */
class QualityIndexer(private val db: AppDatabase, private val loader: BitmapLoader) {
    private val dao: AnalysisDao = db.analysisDao()

    suspend fun countPending(): Int = dao.countQualityPending(VERSION)

    suspend fun run(isStopped: () -> Boolean, onProgress: suspend (Int) -> Unit): Int {
        var processed = 0
        var afterDate = Long.MAX_VALUE
        var afterId = Long.MAX_VALUE
        while (!isStopped()) {
            val page = dao.getQualityPendingPage(VERSION, afterDate, afterId, BATCH)
            if (page.isEmpty()) break
            // Декодирование — в основном ожидание ввода-вывода и аппаратного JPEG: параллельно.
            val results = coroutineScope {
                page.map { media ->
                    async(Dispatchers.Default) {
                        PerfStats.measure("quality.total") {
                            val bitmap = loader.decode(media.uri.toUri(), SIZE)
                            media.id to bitmap?.let { ImageQuality.measure(it).also { _ -> it.recycle() } }
                        }
                    }
                }.awaitAll()
            }
            db.withTransaction {
                // Не удалось декодировать — отмечаем версией без оценки, чтобы не повторять.
                for ((id, q) in results) dao.setQuality(id, q?.sharpness, q?.brightness, VERSION)
            }
            processed += results.size
            onProgress(processed)
            afterDate = page.last().dateTaken
            afterId = page.last().id
        }
        return processed
    }

    companion object {
        const val VERSION = 1
        /** Длинная сторона кадра для оценки: значения резкости сопоставимы между фото. */
        const val SIZE = 512
        private const val BATCH = 16
    }
}
