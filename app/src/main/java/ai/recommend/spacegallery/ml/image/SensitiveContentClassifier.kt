package ai.recommend.spacegallery.ml.image

import ai.onnxruntime.OnnxTensor
import ai.recommend.spacegallery.ml.VectorMath
import ai.recommend.spacegallery.ml.onnx.ModelId
import ai.recommend.spacegallery.ml.onnx.ModelProvider
import ai.recommend.spacegallery.ml.onnx.ModelSpecs
import ai.recommend.spacegallery.ml.onnx.floatOutput
import ai.recommend.spacegallery.perf.PerfStats
import android.graphics.Bitmap
import java.nio.FloatBuffer

/**
 * Классификатор деликатного (NSFW) контента: возвращает вероятность 0..1.
 *
 * Гибрид из двух моделей:
 * 1. [ModelId.NSFW_CLIP] — MLP поверх уже посчитанного CLIP-эмбеддинга (~0 мс);
 * 2. [ModelId.NSFW] — полный ViT по пикселям (~700 мс на кадр).
 * ViT запускается только если префильтр не уверен, что кадр безопасен
 * (см. [ModelSpecs.NSFW_CLIP_PREFILTER]). Если одной из моделей нет — работает другая.
 */
class SensitiveContentClassifier(private val models: ModelProvider) {

    val isAvailable: Boolean
        get() = models.isAvailable(ModelId.NSFW) ||
            (models.isAvailable(ModelId.NSFW_CLIP) && models.isAvailable(ModelId.CLIP_IMAGE))

    /** @param clipEmbedding L2-нормализованный эмбеддинг CLIP ViT-B/32 этого кадра, если он есть. */
    suspend fun score(bitmap: Bitmap, clipEmbedding: FloatArray?): Float? {
        val prefilter = clipEmbedding?.let { scoreFromEmbedding(it) }
        if (prefilter != null && prefilter < ModelSpecs.NSFW_CLIP_PREFILTER) return prefilter
        return scoreFromPixels(bitmap) ?: prefilter
    }

    private suspend fun scoreFromEmbedding(embedding: FloatArray): Float? {
        return models.use(ModelId.NSFW_CLIP) { session ->
            PerfStats.measure("nsfw.clip") {
                OnnxTensor.createTensor(models.env, FloatBuffer.wrap(embedding), longArrayOf(1, embedding.size.toLong())).use { input ->
                    session.run(mapOf(session.inputNames.first() to input)).use { it.floatOutput()[0] }
                }
            }
        }
    }

    private suspend fun scoreFromPixels(bitmap: Bitmap): Float? {
        val spec = ModelSpecs.NSFW
        val pixels = PerfStats.measure("nsfw.preprocess") { ImageTensorizer.toNchw(bitmap, spec) }
        return models.use(ModelId.NSFW) { session ->
            OnnxTensor.createTensor(models.env, pixels, ImageTensorizer.shape(spec)).use { input ->
                PerfStats.measure("nsfw.run") { session.run(mapOf(session.inputNames.first() to input)) }.use { result ->
                    VectorMath.softmax(result.floatOutput())[ModelSpecs.NSFW_POSITIVE_INDEX]
                }
            }
        }
    }
}
