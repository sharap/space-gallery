package ai.recommend.spacegallery.ml.image

import ai.onnxruntime.OnnxTensor
import ai.recommend.spacegallery.ml.VectorMath
import ai.recommend.spacegallery.ml.onnx.ModelId
import ai.recommend.spacegallery.ml.onnx.ModelProvider
import ai.recommend.spacegallery.ml.onnx.ModelSpecs
import ai.recommend.spacegallery.ml.onnx.floatOutput
import ai.recommend.spacegallery.perf.PerfStats
import android.graphics.Bitmap

/** Классификатор деликатного (NSFW) контента: возвращает вероятность 0..1. */
class SensitiveContentClassifier(private val models: ModelProvider) {

    val isAvailable: Boolean get() = models.isAvailable(ModelId.NSFW)

    suspend fun score(bitmap: Bitmap): Float? {
        val session = PerfStats.measure("nsfw.session") { models.session(ModelId.NSFW) } ?: return null
        val spec = ModelSpecs.NSFW
        val pixels = PerfStats.measure("nsfw.preprocess") { ImageTensorizer.toNchw(bitmap, spec) }
        return OnnxTensor.createTensor(models.env, pixels, ImageTensorizer.shape(spec)).use { input ->
            PerfStats.measure("nsfw.run") { session.run(mapOf(session.inputNames.first() to input)) }.use { result ->
                VectorMath.softmax(result.floatOutput())[ModelSpecs.NSFW_POSITIVE_INDEX]
            }
        }
    }
}
