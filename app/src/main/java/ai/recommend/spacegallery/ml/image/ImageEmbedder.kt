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
 * Визуальный энкодер CLIP: изображение -> L2-нормализованный эмбеддинг.
 * Основа для AI-поиска по тексту, «похожих» и семантических дубликатов.
 */
class ImageEmbedder(private val models: ModelProvider) {

    val isAvailable: Boolean get() = models.isAvailable(ModelId.CLIP_IMAGE)

    suspend fun embed(bitmap: Bitmap): FloatArray? = embedPreprocessed(preprocess(bitmap))

    /** Подготовка тензора — не требует модели, её можно делать заранее в другом потоке. */
    fun preprocess(bitmap: Bitmap): FloatBuffer =
        PerfStats.measure("clip.preprocess") { ImageTensorizer.toNchw(bitmap, ModelSpecs.CLIP_IMAGE) }

    suspend fun embedPreprocessed(pixels: FloatBuffer): FloatArray? {
        return models.use(ModelId.CLIP_IMAGE) { session ->
            OnnxTensor.createTensor(models.env, pixels, ImageTensorizer.shape(ModelSpecs.CLIP_IMAGE)).use { input ->
                PerfStats.measure("clip.run") { session.run(mapOf(session.inputNames.first() to input)) }.use { result ->
                    VectorMath.l2Normalize(result.floatOutput(preferredName = "image_embeds"))
                }
            }
        }
    }
}
