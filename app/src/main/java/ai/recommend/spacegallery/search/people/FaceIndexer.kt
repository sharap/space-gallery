package ai.recommend.spacegallery.search.people

import ai.recommend.spacegallery.data.db.FaceDao
import ai.recommend.spacegallery.data.settings.FaceModel
import ai.recommend.spacegallery.data.settings.SettingsRepository
import ai.recommend.spacegallery.data.db.FaceEntity
import ai.recommend.spacegallery.data.db.MediaEntity
import ai.recommend.spacegallery.domain.MediaType
import ai.recommend.spacegallery.ml.VectorMath
import ai.recommend.spacegallery.ml.face.DetectedFace
import ai.recommend.spacegallery.ml.face.FaceDetector
import ai.recommend.spacegallery.ml.face.FaceEmbedder
import ai.recommend.spacegallery.ml.image.BitmapLoader
import ai.recommend.spacegallery.ml.image.FaceCrop
import ai.recommend.spacegallery.ml.image.FaceCropLoader
import ai.recommend.spacegallery.perf.PerfStats
import android.util.Log
import android.graphics.Bitmap
import ai.recommend.spacegallery.ml.face.FaceVerifier
import android.graphics.Rect
import android.graphics.RectF
import androidx.core.graphics.scale
import androidx.core.net.toUri
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Поиск лиц — отдельный проход после основного анализа: YuNet находит лица на превью 640 px,
 * ArcFace строит вектор, для аватара сохраняется миниатюра. Видео пропускаются.
 *
 * При повторном проходе (новая версия детектора или модели векторов) лица ищутся заново, а
 * ручные правки пользователя переносятся на новые лица по совпадению места в кадре.
 */
class FaceIndexer(
    private val bitmapLoader: BitmapLoader,
    private val faceCrops: FaceCropLoader,
    private val detector: FaceDetector,
    private val embedder: FaceEmbedder,
    private val verifier: FaceVerifier,
    private val dao: FaceDao,
    private val settings: SettingsRepository,
) {
    suspend fun isAvailable(): Boolean = detector.isAvailable && embedder.isAvailable(settings.current().faceModel)

    suspend fun countPending(): Int = dao.countPending(FACES_VERSION)

    /**
     * Обрабатывает все фото без поиска лиц. [onProgress] вызывается после каждой пачки
     * с числом обработанных фото. Возвращает (обработано фото, найдено лиц).
     */
    suspend fun run(isStopped: () -> Boolean, onProgress: suspend (Int) -> Unit): Pair<Int, Int> {
        val model = settings.current().faceModel
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
                faces += PerfStats.measure("faces.total") { findFaces(media, model) }
                done += media.id
            }
            val (inherited, rejections) = inheritEdits(done, faces)
            dao.saveBatch(done, inherited, FACES_VERSION, rejections)
            processed += done.size
            found += faces.size
            onProgress(processed)
            afterDate = page.last().dateTaken
            afterId = page.last().id
        }
        return processed to found
    }

    /** Кадры, на которых уже нашлось много лиц: кандидаты на пересмотр по частям. */
    suspend fun crowdedMedia(): List<Long> = dao.crowdedMedia(CROWD_FACES)

    /**
     * Повторный поиск лиц на выбранных кадрах — когда изменились правила поиска, а гонять
     * всю медиатеку заново незачем. Ручные правки переносятся на новые лица по месту в кадре,
     * как и при обычном проходе.
     *
     * Возвращает (обработано кадров, найдено лиц).
     */
    suspend fun rescan(
        mediaIds: List<Long>,
        isStopped: () -> Boolean,
        /** Сколько кадров пройдено и какой id пройден последним — по нему двигают курсор. */
        onProgress: suspend (processed: Int, lastId: Long) -> Unit,
    ): Pair<Int, Int> {
        val model = settings.current().faceModel
        var processed = 0
        var found = 0
        for (chunk in mediaIds.chunked(BATCH)) {
            if (isStopped()) break
            val page = dao.getMediaByIds(chunk)
            val faces = ArrayList<FaceEntity>()
            val done = ArrayList<Long>(page.size)
            for (media in page) {
                if (isStopped()) break
                faces += PerfStats.measure("faces.total") { findFaces(media, model) }
                done += media.id
            }
            val (inherited, rejections) = inheritEdits(done, faces)
            dao.saveBatch(done, inherited, FACES_VERSION, rejections)
            processed += done.size
            found += faces.size
            // Нечитаемые кадры из выборки выпадают, поэтому курсор ведём по входным id.
            onProgress(processed, chunk.last())
        }
        return processed to found
    }

    /**
     * Догоняет лица на людном кадре: кадр читается крупнее и просматривается по частям
     * (2×2, а для больших снимков ещё и 3×3). Лица приходят в долях кадра, так что
     * разрешение проходов значения не имеет и находки просто сливаются.
     *
     * Крупный битмап освобождается сразу: на телефоне он весит десятки мегабайт.
     */
    private suspend fun crowdFaces(media: MediaEntity, single: List<DetectedFace>): List<DetectedFace> {
        // Именно decode, а не load: системное превью MediaStore бывает куда мельче
        // запрошенного, а частям кадра нужны настоящие пиксели — иначе в них нет деталей.
        val big = PerfStats.measure("faces.load.crowd") {
            bitmapLoader.decode(media.uri.toUri(), targetSize = CROWD_SOURCE)
        } ?: return single
        try {
            val tiled = ArrayList<DetectedFace>()
            PerfStats.measure("faces.detect.crowd") {
                tiled += detector.detectTiled(big, grid = 2, minFaceFraction = MIN_FACE_FRACTION)
                if (max(big.width, big.height) >= DENSE_GRID_SIDE) {
                    tiled += detector.detectTiled(big, grid = 3, minFaceFraction = MIN_FACE_FRACTION)
                }
            }
            // Находки одного прохода уже слиты; порядок важен: своё, проверенное, идёт первым.
            return detector.mergeOverlapping(single + tiled.map { it.toFractions(big.width, big.height) })
        } catch (e: OutOfMemoryError) {
            // Кадр 2560 px и его части — это десятки мегабайт поверх загруженных моделей.
            // На тесном устройстве отказываемся от прохода по частям, а не падаем: лица
            // первого прохода уже есть.
            Log.w(TAG, "Не хватило памяти на разбор людного кадра ${media.id}", e)
            return single
        } finally {
            big.recycle()
        }
    }

    /** Координаты в доли кадра: только так находки разных проходов сравнимы между собой. */
    private fun DetectedFace.toFractions(width: Int, height: Int) = DetectedFace(
        RectF(box.left / width, box.top / height, box.right / width, box.bottom / height),
        FloatArray(landmarks.size) { i -> if (i % 2 == 0) landmarks[i] / width else landmarks[i] / height },
        score,
    )

    /** Обратно в пиксели кадра — для запасного пути, когда лицо режется из превью. */
    private fun DetectedFace.toPixels(width: Int, height: Int) = DetectedFace(
        RectF(box.left * width, box.top * height, box.right * width, box.bottom * height),
        FloatArray(landmarks.size) { i -> if (i % 2 == 0) landmarks[i] * width else landmarks[i] * height },
        score,
    )

    /**
     * Уточняет лицо внутри вырезанного куска: ключевые точки, снятые на превью (лицо там
     * медианно ~29 px), слишком грубы, и выравнивание крупного кропа по ним съедает весь
     * выигрыш от детализации. Повторный поиск внутри кропа при равной ошибке даёт +8…12
     * процентных пунктов лиц в группах (замер на 1252 лицах, 2026-09-20).
     */
    private suspend fun preciseFace(crop: FaceCrop, box: RectF, points: FloatArray, score: Float): DetectedFace {
        val mapped = DetectedFace(
            RectF(crop.mapX(box.left), crop.mapY(box.top), crop.mapX(box.right), crop.mapY(box.bottom)),
            FloatArray(points.size) { i -> if (i % 2 == 0) crop.mapX(points[i]) else crop.mapY(points[i]) },
            score,
        )
        val found = PerfStats.measure("faces.redetect") { detector.detect(crop.bitmap, REDETECT_SCORE) }
            .maxByOrNull { iou(it.box, mapped.box) }
            ?.takeIf { iou(it.box, mapped.box) >= REDETECT_IOU }
        return found?.let { DetectedFace(mapped.box, it.landmarks, score) } ?: mapped
    }

    private fun iou(a: RectF, b: RectF): Float {
        val width = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val height = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        if (width <= 0f || height <= 0f) return 0f
        val inter = width * height
        return inter / (a.width() * a.height() + b.width() * b.height() - inter)
    }

    /**
     * Переносит правки пользователя со старых лиц на новые по совпадению места в кадре:
     * человека и подтверждение, пометку «это не лицо», проверку CLIP и запреты «это не он»
     * (их строки удаляются вместе со старым лицом, поэтому возвращаются для пересоздания).
     */
    private suspend fun inheritEdits(
        mediaIds: List<Long>,
        faces: List<FaceEntity>,
    ): Pair<List<FaceEntity>, List<Pair<Int, Long>>> {
        val old = dao.getFacesForMedia(mediaIds)
        if (old.isEmpty()) return faces to emptyList()
        val rejectionsOf = dao.getRejectionsFor(old.map { it.id }).groupBy({ it.faceId }) { it.personId }
        val byMedia = old.groupBy { it.mediaId }
        val restored = ArrayList<Pair<Int, Long>>()
        val result = faces.mapIndexed { index, face ->
            val match = byMedia[face.mediaId]?.maxByOrNull { iou(it, face) }?.takeIf { iou(it, face) >= INHERIT_IOU }
                ?: return@mapIndexed face
            for (personId in rejectionsOf[match.id].orEmpty()) restored += index to personId
            face.copy(
                personId = match.personId,
                lockedPersonId = match.lockedPersonId,
                isArtifact = match.isArtifact,
                checked = face.checked || match.checked,
            )
        }
        return result to restored
    }

    private fun iou(a: FaceEntity, b: FaceEntity): Float {
        val w = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val h = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        if (w <= 0f || h <= 0f) return 0f
        val inter = w * h
        return inter / ((a.right - a.left) * (a.bottom - a.top) + (b.right - b.left) * (b.bottom - b.top) - inter)
    }

    private suspend fun findFaces(media: MediaEntity, model: FaceModel): List<FaceEntity> {
        val bitmap = PerfStats.measure("faces.load") {
            bitmapLoader.load(media.uri.toUri(), MediaType.IMAGE, targetSize = SOURCE_SIZE)
        } ?: return emptyList()
        val minSide = max(bitmap.width, bitmap.height) * MIN_FACE_FRACTION
        val single = PerfStats.measure("faces.detect") { detector.detect(bitmap) }
            .filter { it.box.width() >= minSide && it.box.height() >= minSide }
            .map { it.toFractions(bitmap.width, bitmap.height) }
        // Людный кадр — ищем ещё и по частям: в один проход лица в толпе слишком мелкие.
        val found = if (single.size < CROWD_FACES) single else crowdFaces(media, single)

        return found
            .take(MAX_FACES_PER_PHOTO)
            .mapNotNull { face ->
                val box = face.box
                val points = face.landmarks
                // Лицо вырезается из оригинала: в превью 640 оно занимает ~22 px, и вектор
                // получается шумным (см. FaceCropLoader).
                val crop = PerfStats.measure("faces.crop") { faceCrops.load(media.uri.toUri(), box) }
                // Оригинал не прочитался — довольствуемся превью, но тогда и координаты
                // нужны в его пикселях: дальше они идут в выравнивание.
                val source = crop?.bitmap ?: bitmap
                val detected = if (crop == null) {
                    face.toPixels(bitmap.width, bitmap.height)
                } else {
                    preciseFace(crop, box, points, face.score)
                }
                val embedding = PerfStats.measure("faces.embed") { embedder.embed(source, detected, model) }
                val thumbnail = thumbnail(
                    source,
                    detected.box.centerX(),
                    detected.box.centerY(),
                    max(detected.box.width(), detected.box.height()),
                )
                crop?.bitmap?.recycle()
                if (embedding == null) return@mapNotNull null
                FaceEntity(
                    mediaId = media.id,
                    left = box.left,
                    top = box.top,
                    right = box.right,
                    bottom = box.bottom,
                    score = face.score,
                    embedding = VectorMath.toBytes(embedding),
                    embedVersion = model.embedVersion,
                    landmarks = VectorMath.toBytes(points),
                    thumbnail = thumbnail,
                    // Уверенные срабатывания проверять нечем: остальные проверит [verifyExisting].
                    checked = face.score >= VERIFY_BELOW,
                )
            }
    }

    /**
     * Проверка неуверенных срабатываний (score < [VERIFY_BELOW]) через CLIP: «лицо» или узор,
     * предмет. Отдельный проход после поиска лиц — так ArcFace и модели CLIP не занимают память
     * одновременно (процесс убивался системой). Подтверждённые пользователем лица не трогаются.
     *
     * Разметка пользователя (39 артефактов против 710 лиц названных людей): правило
     * «score < 0.8 и CLIP < 0.5» отсекает 72% артефактов и ни одного настоящего лица.
     * Возвращает число удалённых ложных срабатываний.
     */
    suspend fun verifyExisting(isStopped: () -> Boolean): Int {
        if (!verifier.isAvailable) return 0
        val unchecked = dao.getUnchecked(VERIFY_BELOW)
        var removed = 0
        for ((_, group) in unchecked.groupBy { it.mediaId }) {
            if (isStopped()) break
            val bitmap = bitmapLoader.load(group.first().uri.toUri(), MediaType.IMAGE, targetSize = SOURCE_SIZE)
            if (bitmap == null) {
                dao.markChecked(group.map { it.id })
                continue
            }
            val (keep, drop) = group.partition { f ->
                val box = RectF(f.left * bitmap.width, f.top * bitmap.height, f.right * bitmap.width, f.bottom * bitmap.height)
                // Без CLIP ничего не отбрасываем.
                (verifier.faceProbability(bitmap, box) ?: 1f) >= MIN_FACE_PROBABILITY
            }
            if (drop.isNotEmpty()) dao.deleteFaces(drop.map { it.id })
            if (keep.isNotEmpty()) dao.markChecked(keep.map { it.id })
            removed += drop.size
        }
        return removed
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
        private const val TAG = "FaceIndexer"
        /**
         * Увеличить при смене моделей/параметров — лица будут найдены заново
         * (люди и имена сохраняются: новое лицо наследует человека старого по пересечению рамок).
         * 2 — порог детектора 0.6 и мин. размер лица 2%;
         * 3 — векторы ArcFace r50 вместо SFace, сохраняются ключевые точки лица;
         * 4 — повтор: версия 3 успела частично посчитать векторы старой моделью (копия модели
         * в кеше не обновлялась при замене файла в assets).
         */
        const val FACES_VERSION = 4

        private const val BATCH = 16
        /** Превью для поиска лиц крупнее, чем для CLIP: иначе мелкие лица не распознать. */
        private const val SOURCE_SIZE = 640
        /** Лица меньше 2% длинной стороны (≈ 13 px на 640) — шум; порог подобран на реальных фото. */
        private const val MIN_FACE_FRACTION = 0.02f
        /**
         * Потолок на фото. Прежние 20 молча срезали групповые снимки: на «людных» кадрах
         * медиатеки детектор находит 25–48 лиц, а в базе лежало ровно 20 (замер 2026-09-24).
         */
        private const val MAX_FACES_PER_PHOTO = 60

        /** С такого числа лиц кадр считается людным и просматривается ещё и по частям. */
        const val CROWD_FACES = 6

        /** Для прохода по частям кадр читается крупнее: иначе в частях не прибавится деталей. */
        private const val CROWD_SOURCE = 2560

        /** Сетку 3×3 имеет смысл гонять только по действительно большому кадру. */
        private const val DENSE_GRID_SIDE = 1600

        /**
         * Версия правил для людных кадров. Рост версии запускает разовый пересмотр таких
         * кадров (см. MediaIndexWorker), а не переиндексацию всей медиатеки.
         *
         * 1 — поиск по частям кадра и потолок 60 лиц вместо 20.
         */
        const val CROWD_PASS_VERSION = 1
        private const val THUMB_SIZE = 128
        private const val THUMB_MARGIN = 1.4f
        private const val INHERIT_IOU = 0.5f

        /** Поиск того же лица внутри кропа: порог ниже обычного — лицо там заведомо есть. */
        private const val REDETECT_SCORE = 0.3f
        private const val REDETECT_IOU = 0.3f
        private const val VERIFY_BELOW = 0.8f
        private const val MIN_FACE_PROBABILITY = 0.5f
    }
}
