package ai.recommend.spacegallery.ml.image

import ai.onnxruntime.OnnxTensor
import ai.recommend.spacegallery.ml.VectorMath
import ai.recommend.spacegallery.ml.onnx.ModelId
import ai.recommend.spacegallery.ml.onnx.ModelProvider
import ai.recommend.spacegallery.ml.onnx.ModelSpecs
import ai.recommend.spacegallery.ml.onnx.floatOutput
import android.graphics.Bitmap

/**
 * Визуальный энкодер CLIP: изображение -> L2-нормализованный эмбеддинг.
 * Основа для AI-поиска по тексту, «похожих» и семантических дубликатов.
 */
class ImageEmbedder(private val models: ModelProvider) {

    val isAvailable: Boolean get() = models.isAvailable(ModelId.CLIP_IMAGE)

    suspend fun embed(bitmap: Bitmap): FloatArray? {
        val session = models.session(ModelId.CLIP_IMAGE) ?: return null
        val spec = ModelSpecs.CLIP_IMAGE
        val env = models.env
        // TODO: батчинг [N,3,S,S] заметно ускоряет индексацию на CPU.
        return OnnxTensor.createTensor(env, ImageTensorizer.toNchw(bitmap, spec), ImageTensorizer.shape(spec)).use { input ->
            session.run(mapOf(session.inputNames.first() to input)).use { result ->
                VectorMath.l2Normalize(result.floatOutput(preferredName = "image_embeds"))
            }
        }
    }
}
