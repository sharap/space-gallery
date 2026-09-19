package ai.recommend.spacegallery.work

import ai.recommend.spacegallery.data.db.MediaAnalysisEntity
import ai.recommend.spacegallery.data.db.MediaEntity
import ai.recommend.spacegallery.domain.MediaType
import ai.recommend.spacegallery.ml.VectorMath
import ai.recommend.spacegallery.ml.hash.PerceptualHasher
import ai.recommend.spacegallery.ml.image.BitmapLoader
import ai.recommend.spacegallery.ml.image.ImageEmbedder
import ai.recommend.spacegallery.ml.image.SensitiveContentClassifier
import androidx.core.net.toUri

/** Полный локальный анализ одного медиафайла: хэш + эмбеддинг + оценка деликатности. */
class MediaAnalyzer(
    private val bitmapLoader: BitmapLoader,
    private val hasher: PerceptualHasher,
    private val embedder: ImageEmbedder,
    private val classifier: SensitiveContentClassifier,
) {
    suspend fun analyze(media: MediaEntity): MediaAnalysisEntity {
        val type = if (media.mediaType == 1) MediaType.VIDEO else MediaType.IMAGE
        val bitmap = bitmapLoader.load(media.uri.toUri(), type)
            ?: return MediaAnalysisEntity(
                mediaId = media.id,
                sourceModified = media.dateModified,
                pipelineVersion = PIPELINE_VERSION,
                embedding = null,
                perceptualHash = null,
                sensitiveScore = null,
                isUnreadable = true,
            )
        return MediaAnalysisEntity(
            mediaId = media.id,
            sourceModified = media.dateModified,
            pipelineVersion = PIPELINE_VERSION,
            embedding = embedder.embed(bitmap)?.let(VectorMath::toBytes),
            perceptualHash = hasher.dHash(bitmap),
            sensitiveScore = classifier.score(bitmap),
        )
    }

    companion object {
        /** Увеличить при смене моделей/препроцессинга — всё медиа будет переиндексировано. */
        const val PIPELINE_VERSION = 1
    }
}
