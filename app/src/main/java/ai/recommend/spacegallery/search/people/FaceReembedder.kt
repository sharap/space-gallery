package ai.recommend.spacegallery.search.people

import ai.recommend.spacegallery.data.db.AppDatabase
import ai.recommend.spacegallery.data.settings.SettingsRepository
import ai.recommend.spacegallery.domain.MediaType
import ai.recommend.spacegallery.ml.VectorMath
import ai.recommend.spacegallery.ml.face.DetectedFace
import ai.recommend.spacegallery.ml.face.FaceEmbedder
import ai.recommend.spacegallery.ml.image.BitmapLoader
import ai.recommend.spacegallery.perf.PerfStats
import android.graphics.RectF
import androidx.core.net.toUri
import androidx.room.withTransaction

/**
 * Пересчёт векторов лиц при смене модели (пользователь выбирает быструю или точную): лица не ищутся заново — выравнивание берётся из
 * сохранённых ключевых точек. Так смена модели стоит только инференса (~0.2 с на лицо), а не
 * повторного поиска лиц, и ручные правки пользователя не затрагиваются вовсе.
 *
 * Сравнение версии — на неравенство: переключаться между моделями можно в обе стороны.
 * Лица, найденные до появления ключевых точек, пересчитать так нельзя — они обновляются
 * обычным поиском лиц при росте FaceIndexer.FACES_VERSION.
 */
class FaceReembedder(
    private val db: AppDatabase,
    private val bitmapLoader: BitmapLoader,
    private val embedder: FaceEmbedder,
    private val settings: SettingsRepository,
) {
    private val dao = db.faceDao()

    suspend fun countPending(): Int {
        val model = settings.current().faceModel
        return if (!embedder.isAvailable(model)) 0 else dao.countToReembed(model.embedVersion)
    }

    suspend fun run(isStopped: () -> Boolean, onProgress: suspend (Int) -> Unit): Int {
        val model = settings.current().faceModel
        if (!embedder.isAvailable(model)) return 0
        var processed = 0
        while (!isStopped()) {
            val page = dao.getToReembed(model.embedVersion, BATCH)
            if (page.isEmpty()) break
            val failed = ArrayList<Long>()
            val results = ArrayList<Pair<Long, ByteArray>>(page.size)
            for ((mediaId, rows) in page.groupBy { it.mediaId }) {
                if (isStopped()) break
                val bitmap = bitmapLoader.load(rows.first().uri.toUri(), MediaType.IMAGE, SOURCE_SIZE)
                if (bitmap == null) {
                    failed += rows.map { it.id }
                    continue
                }
                for (row in rows) {
                    val points = VectorMath.fromBytes(row.landmarks)
                    // Точки хранятся в долях кадра — переводим в пиксели текущего превью.
                    val pixels = FloatArray(points.size) { i ->
                        if (i % 2 == 0) points[i] * bitmap.width else points[i] * bitmap.height
                    }
                    val face = DetectedFace(RectF(), pixels, score = 1f)
                    val embedding = PerfStats.measure("faces.embed") { embedder.embed(bitmap, face, model) }
                    if (embedding == null) failed += row.id else results += row.id to VectorMath.toBytes(embedding)
                }
                bitmap.recycle()
            }
            db.withTransaction {
                for ((id, embedding) in results) dao.setEmbedding(id, embedding, model.embedVersion)
                if (failed.isNotEmpty()) dao.markEmbedVersion(failed, model.embedVersion)
            }
            processed += results.size + failed.size
            onProgress(processed)
        }
        return processed
    }

    private companion object {
        const val BATCH = 32
        const val SOURCE_SIZE = 640
    }
}
