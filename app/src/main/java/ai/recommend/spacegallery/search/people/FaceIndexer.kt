package ai.recommend.spacegallery.search.people

import ai.recommend.spacegallery.data.db.FaceDao
import ai.recommend.spacegallery.data.db.FaceEntity
import ai.recommend.spacegallery.data.db.MediaEntity
import ai.recommend.spacegallery.domain.MediaType
import ai.recommend.spacegallery.ml.VectorMath
import ai.recommend.spacegallery.ml.face.FaceDetector
import ai.recommend.spacegallery.ml.face.FaceEmbedder
import ai.recommend.spacegallery.ml.image.BitmapLoader
import ai.recommend.spacegallery.perf.PerfStats
import android.graphics.Bitmap
import android.graphics.Rect
import androidx.core.graphics.scale
import androidx.core.net.toUri
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Поиск лиц — отдельный проход после основного анализа: YuNet находит лица на превью 640 px,
 * SFace строит вектор, для аватара сохраняется миниатюра. Видео пропускаются.
 */
class FaceIndexer(
    private val bitmapLoader: BitmapLoader,
    private val detector: FaceDetector,
    private val embedder: FaceEmbedder,
    private val dao: FaceDao,
) {
    val isAvailable: Boolean get() = detector.isAvailable && embedder.isAvailable

    suspend fun countPending(): Int = dao.countPending(FACES_VERSION)

    /**
     * Обрабатывает все фото без поиска лиц. [onProgress] вызывается после каждой пачки
     * с числом обработанных фото. Возвращает (обработано фото, найдено лиц).
     */
    suspend fun run(isStopped: () -> Boolean, onProgress: suspend (Int) -> Unit): Pair<Int, Int> {
        var processed = 0
        var found = 0
        var afterDate = Long.MAX_VALUE
        var afterId = Long.MAX_VALUE
        while (!isStopped()) {
            val page = dao.getPendingPage(FACES_VERSION, afterDate, afterId, BATCH)
            if (page.isEmpty()) break
            val faces = ArrayList<FaceEntity>()
            val done = ArrayList<Long>(page.size)
            for (media in page) {
                if (isStopped()) break
                faces += PerfStats.measure("faces.total") { findFaces(media) }
                done += media.id
            }
            dao.saveBatch(done, inheritPersons(done, faces), FACES_VERSION)
            processed += done.size
            found += faces.size
            onProgress(processed)
            afterDate = page.last().dateTaken
            afterId = page.last().id
        }
        return processed to found
    }

    /**
     * При повторном поиске (новая версия) старые лица фото заменяются новыми — чтобы люди и их
     * имена не потерялись, новое лицо получает человека старого лица с тем же местом в кадре.
     */
    private suspend fun inheritPersons(mediaIds: List<Long>, faces: List<FaceEntity>): List<FaceEntity> {
        val old = dao.getFacesForMedia(mediaIds).filter { it.personId != null }.groupBy { it.mediaId }
        if (old.isEmpty()) return faces
        return faces.map { face ->
            val match = old[face.mediaId]?.maxByOrNull { iou(it, face) }
            if (match != null && iou(match, face) >= INHERIT_IOU) face.copy(personId = match.personId) else face
        }
    }

    private fun iou(a: FaceEntity, b: FaceEntity): Float {
        val w = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val h = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        if (w <= 0f || h <= 0f) return 0f
        val inter = w * h
        return inter / ((a.right - a.left) * (a.bottom - a.top) + (b.right - b.left) * (b.bottom - b.top) - inter)
    }

    private suspend fun findFaces(media: MediaEntity): List<FaceEntity> {
        val bitmap = PerfStats.measure("faces.load") {
            bitmapLoader.load(media.uri.toUri(), MediaType.IMAGE, targetSize = SOURCE_SIZE)
        } ?: return emptyList()
        val detected = PerfStats.measure("faces.detect") { detector.detect(bitmap) }
        val minSide = max(bitmap.width, bitmap.height) * MIN_FACE_FRACTION
        return detected
            .filter { it.box.width() >= minSide && it.box.height() >= minSide }
            .take(MAX_FACES_PER_PHOTO)
            .mapNotNull { face ->
                val embedding = PerfStats.measure("faces.embed") { embedder.embed(bitmap, face) } ?: return@mapNotNull null
                FaceEntity(
                    mediaId = media.id,
                    left = face.box.left / bitmap.width,
                    top = face.box.top / bitmap.height,
                    right = face.box.right / bitmap.width,
                    bottom = face.box.bottom / bitmap.height,
                    score = face.score,
                    embedding = VectorMath.toBytes(embedding),
                    thumbnail = thumbnail(bitmap, face.box.centerX(), face.box.centerY(), max(face.box.width(), face.box.height())),
                )
            }
    }

    /** Квадрат вокруг лица с запасом (для круглого аватара) -> JPEG. */
    private fun thumbnail(bitmap: Bitmap, cx: Float, cy: Float, size: Float): ByteArray {
        val half = (size * THUMB_MARGIN / 2).roundToInt()
        val rect = Rect(cx.roundToInt() - half, cy.roundToInt() - half, cx.roundToInt() + half, cy.roundToInt() + half)
        // Центр лица всегда внутри кадра, но на всякий случай: без пересечения — весь кадр.
        if (!rect.intersect(0, 0, bitmap.width, bitmap.height)) rect.set(0, 0, bitmap.width, bitmap.height)
        val crop = Bitmap.createBitmap(bitmap, rect.left, rect.top, rect.width().coerceAtLeast(1), rect.height().coerceAtLeast(1))
        val scaled = if (crop.width > THUMB_SIZE) crop.scale(THUMB_SIZE, THUMB_SIZE * crop.height / crop.width) else crop
        return ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
            out.toByteArray()
        }
    }

    companion object {
        /**
         * Увеличить при смене моделей/параметров — лица будут найдены заново
         * (люди и имена сохраняются: новое лицо наследует человека старого по пересечению рамок).
         * 2 — порог детектора 0.6 и мин. размер лица 2%.
         */
        const val FACES_VERSION = 2

        private const val BATCH = 16
        /** Превью для поиска лиц крупнее, чем для CLIP: иначе мелкие лица не распознать. */
        private const val SOURCE_SIZE = 640
        /** Лица меньше 2% длинной стороны (≈ 13 px на 640) — шум; порог подобран на реальных фото. */
        private const val MIN_FACE_FRACTION = 0.02f
        /** Групповые фото: хватит самых крупных/уверенных лиц. */
        private const val MAX_FACES_PER_PHOTO = 20
        private const val THUMB_SIZE = 128
        private const val THUMB_MARGIN = 1.4f
        private const val INHERIT_IOU = 0.5f
    }
}
