package ai.recommend.spacegallery.ml.text

import ai.onnxruntime.OnnxTensor
import ai.recommend.spacegallery.ml.VectorMath
import ai.recommend.spacegallery.ml.onnx.ModelId
import ai.recommend.spacegallery.ml.onnx.ModelProvider
import ai.recommend.spacegallery.ml.onnx.floatOutput
import java.nio.LongBuffer

/** Текстовая модель + её токенизатор. Все бэкенды выдают векторы в пространстве CLIP ViT-B/32. */
class TextEncoderBackend(val modelId: ModelId, val tokenizer: TokenizerProvider)

/**
 * Запрос -> эмбеддинг в том же пространстве, что и изображения.
 *
 * Для запросов на латинице предпочитается оригинальный CLIP (лучше на английском),
 * для остальных языков — многоязычный энкодер. Если нужной модели нет — берётся любая доступная.
 */
class TextEmbedder(
    private val models: ModelProvider,
    private val english: TextEncoderBackend,
    private val multilingual: TextEncoderBackend,
) {
    val isAvailable: Boolean
        get() = models.isAvailable(english.modelId) || models.isAvailable(multilingual.modelId)

    suspend fun embed(text: String): FloatArray? {
        val order = if (isLatinOnly(text)) listOf(english, multilingual) else listOf(multilingual, english)
        for (backend in order) {
            if (!models.isAvailable(backend.modelId)) continue
            embedWith(backend, text)?.let { return it }
        }
        return null
    }

    private suspend fun embedWith(backend: TextEncoderBackend, text: String): FloatArray? {
        val session = models.session(backend.modelId) ?: return null
        val encoding = backend.tokenizer.get()?.encode(text) ?: return null
        val shape = longArrayOf(1, encoding.inputIds.size.toLong())
        val env = models.env

        val idsName = if (INPUT_IDS in session.inputNames) INPUT_IDS else session.inputNames.first()
        val inputs = buildMap {
            put(idsName, OnnxTensor.createTensor(env, LongBuffer.wrap(encoding.inputIds), shape))
            // Xenova CLIP принимает только input_ids, многоязычный BERT — ещё и маску.
            if (ATTENTION_MASK in session.inputNames) {
                put(ATTENTION_MASK, OnnxTensor.createTensor(env, LongBuffer.wrap(encoding.attentionMask), shape))
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

        fun isLatinOnly(text: String): Boolean = text.all { !it.isLetter() || it.code < 0x250 }
    }
}
