package ai.recommend.spacegallery.work

import ai.recommend.spacegallery.data.db.MediaAnalysisEntity
import ai.recommend.spacegallery.data.db.MediaEntity
import ai.recommend.spacegallery.domain.MediaType
import ai.recommend.spacegallery.ml.VectorMath
import ai.recommend.spacegallery.ml.hash.PerceptualHasher
import ai.recommend.spacegallery.ml.image.BitmapLoader
import ai.recommend.spacegallery.ml.image.ImageEmbedder
import ai.recommend.spacegallery.ml.image.SensitiveContentClassifier
import ai.recommend.spacegallery.perf.PerfStats
import android.graphics.Bitmap
import androidx.core.net.toUri
import java.nio.FloatBuffer

/** Результат подготовительного этапа [MediaAnalyzer.prepare]; bitmap = null — файл не читается. */
class PreparedMedia(
    val media: MediaEntity,
    val bitmap: Bitmap?,
    val perceptualHash: Long?,
    val clipPixels: FloatBuffer?,
)

/** Полный локальный анализ одного медиафайла: хэш + эмбеддинг + оценка деликатности. */
class MediaAnalyzer(
    private val bitmapLoader: BitmapLoader,
    private val hasher: PerceptualHasher,
    private val embedder: ImageEmbedder,
    private val classifier: SensitiveContentClassifier,
) {
    /**
     * Этап 1 — всё, что не требует моделей: декодирование превью, dHash, тензор для CLIP.
     * Выполняется заранее в отдельной корутине, параллельно с инференсом предыдущих файлов.
     */
    suspend fun prepare(media: MediaEntity): PreparedMedia = PerfStats.measure(STAGE_PREPARE) {
        val type = if (media.mediaType == 1) MediaType.VIDEO else MediaType.IMAGE
        val bitmap = PerfStats.measure(if (type == MediaType.VIDEO) "load.video" else "load.image") {
            bitmapLoader.load(media.uri.toUri(), type)
        }
        PreparedMedia(
            media = media,
            bitmap = bitmap,
            perceptualHash = bitmap?.let { PerfStats.measure("dhash") { hasher.dHash(it) } },
            clipPixels = bitmap?.takeIf { embedder.isAvailable }?.let(embedder::preprocess),
        )
    }

    /** Этап 2 — инференс: CLIP -> быстрый NSFW-префильтр -> (редко) ViT. */
    suspend fun analyze(prepared: PreparedMedia): MediaAnalysisEntity = PerfStats.measure(STAGE_TOTAL) {
        val media = prepared.media
        val bitmap = prepared.bitmap
            ?: return@measure MediaAnalysisEntity(
                mediaId = media.id,
                sourceModified = media.dateModified,
                pipelineVersion = PIPELINE_VERSION,
                embedding = null,
                perceptualHash = null,
                sensitiveScore = null,
                isUnreadable = true,
            )
        // Эмбеддинг считается первым: он же вход быстрого NSFW-префильтра.
        val embedding = prepared.clipPixels?.let { embedder.embedPreprocessed(it) }
        MediaAnalysisEntity(
            mediaId = media.id,
            sourceModified = media.dateModified,
            pipelineVersion = PIPELINE_VERSION,
            embedding = embedding?.let(VectorMath::toBytes),
            perceptualHash = prepared.perceptualHash,
            sensitiveScore = classifier.score(bitmap, embedding),
        )
    }

    companion object {
        /** Увеличить при смене моделей/препроцессинга — всё медиа будет переиндексировано. */
        const val PIPELINE_VERSION = 1

        /** Время инференса одного файла — база для доли этапов в [PerfStats]. */
        const val STAGE_TOTAL = "analyze.total"

        /** Подготовка (идёт параллельно с инференсом, в стену времени не складывается). */
        const val STAGE_PREPARE = "prepare.total"
    }
}
