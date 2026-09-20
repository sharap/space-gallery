package ai.recommend.spacegallery.search.people

import ai.recommend.spacegallery.data.db.AppDatabase
import ai.recommend.spacegallery.data.settings.SettingsRepository
import ai.recommend.spacegallery.ml.VectorMath
import ai.recommend.spacegallery.ml.face.DetectedFace
import ai.recommend.spacegallery.ml.face.FaceDetector
import ai.recommend.spacegallery.ml.face.FaceEmbedder
import ai.recommend.spacegallery.ml.image.FaceCropLoader
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
    private val faceCrops: FaceCropLoader,
    private val detector: FaceDetector,
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
            for (row in page) {
                if (isStopped()) break
                // Лицо вырезается из оригинала по сохранённой рамке и точкам — кадр целиком не нужен.
                val box = RectF(row.left, row.top, row.right, row.bottom)
                val crop = PerfStats.measure("faces.crop") { faceCrops.load(row.uri.toUri(), box) }
                if (crop == null) {
                    failed += row.id
                    continue
                }
                val points = VectorMath.fromBytes(row.landmarks)
                val pixels = FloatArray(points.size) { i ->
                    if (i % 2 == 0) crop.mapX(points[i]) else crop.mapY(points[i])
                }
                val mappedBox = RectF(crop.mapX(box.left), crop.mapY(box.top), crop.mapX(box.right), crop.mapY(box.bottom))
                // Точки из базы сняты на превью — уточняем их внутри кропа (см. FaceIndexer).
                val refined = detector.detect(crop.bitmap, REDETECT_SCORE)
                    .maxByOrNull { iou(it.box, mappedBox) }
                    ?.takeIf { iou(it.box, mappedBox) >= REDETECT_IOU }
                val face = DetectedFace(mappedBox, refined?.landmarks ?: pixels, score = 1f)
                val embedding = PerfStats.measure("faces.embed") { embedder.embed(crop.bitmap, face, model) }
                crop.bitmap.recycle()
                if (embedding == null) failed += row.id else results += row.id to VectorMath.toBytes(embedding)
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

    private fun iou(a: RectF, b: RectF): Float {
        val width = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val height = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        if (width <= 0f || height <= 0f) return 0f
        val inter = width * height
        return inter / (a.width() * a.height() + b.width() * b.height() - inter)
    }

    private companion object {
        const val BATCH = 32
        const val REDETECT_SCORE = 0.3f
        const val REDETECT_IOU = 0.3f
    }
}
