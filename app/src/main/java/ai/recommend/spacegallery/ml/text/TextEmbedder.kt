package ai.recommend.spacegallery.ml.text

import ai.onnxruntime.OnnxTensor
import ai.recommend.spacegallery.ml.VectorMath
import ai.recommend.spacegallery.ml.onnx.ModelId
import ai.recommend.spacegallery.ml.onnx.ModelProvider
import ai.recommend.spacegallery.ml.onnx.floatOutput
import java.nio.LongBuffer

/** Текстовый энкодер CLIP: запрос -> эмбеддинг в том же пространстве, что и изображения. */
class TextEmbedder(
    private val models: ModelProvider,
    private val tokenizer: TokenizerProvider,
) {
    val isAvailable: Boolean get() = models.isAvailable(ModelId.CLIP_TEXT)

    suspend fun embed(text: String): FloatArray? {
        val session = models.session(ModelId.CLIP_TEXT) ?: return null
        val tok = tokenizer.get() ?: return null
        val ids = tok.encode(text)
        val shape = longArrayOf(1, ids.size.toLong())
        val env = models.env

        val idsName = if (INPUT_IDS in session.inputNames) INPUT_IDS else session.inputNames.first()
        val inputs = buildMap {
            put(idsName, OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape))
            // HF-экспорт CLIP требует attention_mask, open_clip-экспорт — нет.
            if (ATTENTION_MASK in session.inputNames) {
                val mask = LongArray(ids.size) { if (it == 0 || ids[it] != 0L) 1L else 0L }
                put(ATTENTION_MASK, OnnxTensor.createTensor(env, LongBuffer.wrap(mask), shape))
            }
        }
        return try {
            session.run(inputs).use { result ->
                VectorMath.l2Normalize(result.floatOutput(preferredName = "text_embeds"))
            }
        } finally {
            inputs.values.forEach { it.close() }
        }
    }

    private companion object {
        const val INPUT_IDS = "input_ids"
        const val ATTENTION_MASK = "attention_mask"
    }
}
