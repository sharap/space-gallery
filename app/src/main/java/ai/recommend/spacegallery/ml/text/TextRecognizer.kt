package ai.recommend.spacegallery.ml.text

import ai.onnxruntime.OnnxTensor
import ai.recommend.spacegallery.data.settings.TextLanguage
import ai.recommend.spacegallery.ml.onnx.ModelProvider
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import androidx.core.graphics.scale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Распознанная строка: текст, рамка в долях кадра и уверенность (0..1). */
data class TextLine(val text: String, val box: RectF, val confidence: Float)

/**
 * Распознавание строки текста (PaddleOCR PP-OCRv5, восточнославянские языки и английский).
 *
 * Строка приводится к высоте 48 пикселей, ширина — пропорционально; строки считаются пачками,
 * короткие дополняются справа. Выход модели — последовательность распределений по классам,
 * которая сворачивается по правилу CTC: подряд идущие одинаковые классы схлопываются, нулевой
 * класс — «пусто». Классы: 0 — пусто, 1..N — символы словаря, последний — пробел.
 */
class TextRecognizer(private val context: Context, private val models: ModelProvider) {

    private val dictionaries = HashMap<TextLanguage, List<String>>()

    fun isAvailable(language: TextLanguage): Boolean = models.isAvailable(language.modelId)

    suspend fun recognize(bitmap: Bitmap, boxes: List<RectF>, language: TextLanguage): List<TextLine> {
        val dictionary = dictionary(language)
        if (boxes.isEmpty() || dictionary.isEmpty()) return emptyList()
        val ordered = boxes.sortedWith(compareBy({ it.top }, { it.left }))
        val result = ArrayList<TextLine>(ordered.size)
        for (chunk in ordered.chunked(BATCH)) {
            val crops = chunk.mapNotNull { box -> crop(bitmap, box)?.let { box to it } }
            if (crops.isEmpty()) continue
            result += runBatch(crops, language, dictionary)
            crops.forEach { it.second.recycle() }
        }
        return result
    }

    private fun crop(bitmap: Bitmap, box: RectF): Bitmap? {
        val rect = Rect(
            (box.left * bitmap.width).roundToInt().coerceIn(0, bitmap.width - 1),
            (box.top * bitmap.height).roundToInt().coerceIn(0, bitmap.height - 1),
            (box.right * bitmap.width).roundToInt().coerceIn(1, bitmap.width),
            (box.bottom * bitmap.height).roundToInt().coerceIn(1, bitmap.height),
        )
        if (rect.width() < 4 || rect.height() < 4) return null
        val piece = Bitmap.createBitmap(bitmap, rect.left, rect.top, rect.width(), rect.height())
        val width = (piece.width * HEIGHT / piece.height).coerceIn(MIN_WIDTH, MAX_WIDTH)
        val scaled = piece.scale(width, HEIGHT)
        if (scaled !== piece) piece.recycle()
        return scaled
    }

    private suspend fun runBatch(crops: List<Pair<RectF, Bitmap>>, language: TextLanguage, dictionary: List<String>): List<TextLine> {
        val width = crops.maxOf { it.second.width }
        val buffer = FloatBuffer.allocate(crops.size * 3 * HEIGHT * width)
        val plane = HEIGHT * width
        for ((index, entry) in crops.withIndex()) {
            val image = entry.second
            val pixels = IntArray(image.width * image.height)
            image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
            val base = index * 3 * plane
            for (y in 0 until HEIGHT) {
                for (x in 0 until image.width) {
                    val p = pixels[y * image.width + x]
                    val offset = y * width + x
                    buffer.put(base + offset, (((p shr 16) and 0xFF) / 255f - 0.5f) / 0.5f)
                    buffer.put(base + plane + offset, (((p shr 8) and 0xFF) / 255f - 0.5f) / 0.5f)
                    buffer.put(base + 2 * plane + offset, ((p and 0xFF) / 255f - 0.5f) / 0.5f)
                }
            }
        }
        val shape = longArrayOf(crops.size.toLong(), 3, HEIGHT.toLong(), width.toLong())
        return models.use(language.modelId) { session ->
            OnnxTensor.createTensor(models.env, buffer, shape).use { tensor ->
                session.run(mapOf(session.inputNames.first() to tensor)).use { output ->
                    val tensorOut = output.get(0) as OnnxTensor
                    val dims = tensorOut.info.shape // [N, T, C]
                    val steps = dims[1].toInt()
                    val classes = dims[2].toInt()
                    val data = tensorOut.floatBuffer
                    val values = FloatArray(data.remaining()).also { data.get(it) }
                    crops.indices.mapNotNull { i -> decode(values, i, steps, classes, crops[i].first, dictionary) }
                }
            }
        } ?: emptyList()
    }

    /** Свёртка CTC: повторы схлопываются, нулевой класс пропускается. */
    private fun decode(values: FloatArray, index: Int, steps: Int, classes: Int, box: RectF, dictionary: List<String>): TextLine? {
        val builder = StringBuilder()
        var confidence = 0f
        var counted = 0
        var previous = -1
        for (t in 0 until steps) {
            val offset = (index * steps + t) * classes
            var best = 0
            var bestValue = values[offset]
            for (c in 1 until classes) {
                val value = values[offset + c]
                if (value > bestValue) {
                    bestValue = value
                    best = c
                }
            }
            if (best != 0 && best != previous) {
                builder.append(symbol(best, dictionary))
                confidence += bestValue
                counted++
            }
            previous = best
        }
        val text = builder.toString().trim()
        if (text.isEmpty() || counted == 0) return null
        return TextLine(text, box, confidence / counted)
    }

    private fun symbol(classIndex: Int, dictionary: List<String>): String =
        if (classIndex - 1 < dictionary.size) dictionary[classIndex - 1] else " "

    /** Словарь языка: символы по одному в строке; пробел модель выдаёт последним классом. */
    private suspend fun dictionary(language: TextLanguage): List<String> = withContext(Dispatchers.IO) {
        dictionaries.getOrPut(language) {
            runCatching {
                val file = File(File(context.filesDir, MODELS_DIR), language.dictionary)
                val lines = if (file.exists()) {
                    file.readLines()
                } else {
                    context.assets.open("$MODELS_DIR/${language.dictionary}").bufferedReader().readLines()
                }
                lines.filter { it.isNotEmpty() }
            }.getOrElse { emptyList() }
        }
    }

    /** Прогрев словарей вне главного потока. */
    suspend fun prepare(languages: Set<TextLanguage>) {
        languages.forEach { dictionary(it) }
    }

    private companion object {
        const val HEIGHT = 48
        const val MIN_WIDTH = 16
        const val MAX_WIDTH = 1200
        const val BATCH = 8
        const val MODELS_DIR = "models"
    }
}
