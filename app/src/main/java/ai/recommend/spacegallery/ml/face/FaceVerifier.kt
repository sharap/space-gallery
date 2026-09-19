package ai.recommend.spacegallery.ml.face

import ai.recommend.spacegallery.ml.VectorMath
import ai.recommend.spacegallery.ml.image.ImageEmbedder
import ai.recommend.spacegallery.ml.text.TextEmbedder
import android.graphics.Bitmap
import android.graphics.RectF
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Проверка неуверенных срабатываний детектора через CLIP (zero-shot): вырезанная область
 * сравнивается с описаниями «лицо человека» и «не лицо» (предмет, пейзаж, кружка, еда, узор…).
 * YuNet при пониженном пороге иногда находит «лица» в узорах и предметах — CLIP их различает.
 */
class FaceVerifier(
    private val imageEmbedder: ImageEmbedder,
    private val textEmbedder: TextEmbedder,
) {
    val isAvailable: Boolean get() = imageEmbedder.isAvailable && textEmbedder.isAvailable

    private val mutex = Mutex()
    private var prompts: List<Pair<Boolean, FloatArray>>? = null

    /** Вероятность, что в рамке [box] (пиксели [bitmap]) — лицо человека; null — CLIP недоступен. */
    suspend fun faceProbability(bitmap: Bitmap, box: RectF): Float? {
        val texts = promptEmbeddings() ?: return null
        val crop = cropAround(bitmap, box)
        val image = imageEmbedder.embed(crop) ?: return null
        if (crop !== bitmap) crop.recycle()
        // Softmax по всем описаниям с масштабом CLIP (logit_scale = 100).
        val logits = texts.map { (_, t) -> LOGIT_SCALE * VectorMath.dot(image, t) }
        val maxLogit = logits.max()
        val weights = logits.map { exp(it - maxLogit) }
        val total = weights.sum()
        return texts.indices.filter { texts[it].first }.sumOf { weights[it].toDouble() }.toFloat() / total
    }

    private suspend fun promptEmbeddings(): List<Pair<Boolean, FloatArray>>? = mutex.withLock {
        prompts ?: buildList {
            for (p in FACE_PROMPTS) add(true to (textEmbedder.embedEnglish(p) ?: return@withLock null))
            for (p in OTHER_PROMPTS) add(false to (textEmbedder.embedEnglish(p) ?: return@withLock null))
        }.also { prompts = it }
    }

    /** Квадрат вокруг рамки с запасом (CLIP нужен контекст: голова, волосы, плечи). */
    private fun cropAround(bitmap: Bitmap, box: RectF): Bitmap {
        val side = max(box.width(), box.height()) * CROP_MARGIN
        val left = (box.centerX() - side / 2).roundToInt().coerceIn(0, bitmap.width - 1)
        val top = (box.centerY() - side / 2).roundToInt().coerceIn(0, bitmap.height - 1)
        val w = side.roundToInt().coerceIn(1, bitmap.width - left)
        val h = side.roundToInt().coerceIn(1, bitmap.height - top)
        return Bitmap.createBitmap(bitmap, left, top, w, h)
    }

    private companion object {
        const val LOGIT_SCALE = 100f
        const val CROP_MARGIN = 1.6f
        val FACE_PROMPTS = listOf(
            "a photo of a human face",
            "a close-up photo of a person's face",
            "a photo of a person",
        )
        val OTHER_PROMPTS = listOf(
            "a photo of an object",
            "a photo of a landscape",
            "a photo of a cup",
            "a photo of food",
            "a photo of an animal",
            "a photo of text",
            "a photo of a pattern or texture",
            "a photo of a building",
            "a photo of a plant",
            "a photo of a toy",
        )
    }
}
