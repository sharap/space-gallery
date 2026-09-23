package ai.recommend.spacegallery.ml.text

import ai.onnxruntime.OnnxTensor
import ai.recommend.spacegallery.ml.onnx.ModelId
import ai.recommend.spacegallery.ml.onnx.ModelProvider
import ai.recommend.spacegallery.ml.onnx.floatOutput
import android.graphics.Bitmap
import android.graphics.RectF
import androidx.core.graphics.scale
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Поиск строк текста на снимке (PaddleOCR DB). Модель отдаёт карту вероятностей «здесь текст»,
 * из неё собираются прямоугольники строк.
 *
 * Длинная сторона кадра уменьшается до [INPUT_LIMIT]: на реальных снимках (2026-09-20) это
 * находит 91% снимков с текстом против входа 960 px и работает в 1.6 раза быстрее.
 */
class TextDetector(private val models: ModelProvider) {

    val isAvailable: Boolean get() = models.isAvailable(ModelId.TEXT_DETECT)

    /** Прямоугольники строк в долях кадра. */
    suspend fun detect(bitmap: Bitmap): List<RectF> = models.use(ModelId.TEXT_DETECT) { session ->
        val scale = min(1f, INPUT_LIMIT.toFloat() / max(bitmap.width, bitmap.height))
        // Стороны входа должны делиться на 32.
        val width = max(32, ((bitmap.width * scale / 32).roundToInt()) * 32)
        val height = max(32, ((bitmap.height * scale / 32).roundToInt()) * 32)
        val resized = bitmap.scale(width, height)
        val input = normalize(resized)
        if (resized !== bitmap) resized.recycle()
        val probability = OnnxTensor.createTensor(models.env, input, longArrayOf(1, 3, height.toLong(), width.toLong()))
            .use { tensor -> session.run(mapOf(session.inputNames.first() to tensor)).use { it.floatOutput() } }
        boxes(probability, width, height)
    } ?: emptyList()

    /** Нормализация ImageNet, как в PaddleOCR. */
    private fun normalize(bitmap: Bitmap): FloatBuffer {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val plane = pixels.size
        val buffer = FloatBuffer.allocate(3 * plane)
        for (i in 0 until plane) {
            val p = pixels[i]
            buffer.put(i, (((p shr 16) and 0xFF) / 255f - MEAN[0]) / STD[0])
            buffer.put(plane + i, (((p shr 8) and 0xFF) / 255f - MEAN[1]) / STD[1])
            buffer.put(2 * plane + i, ((p and 0xFF) / 255f - MEAN[2]) / STD[2])
        }
        return buffer
    }

    /**
     * Связные области карты вероятностей — строки текста. Области ищутся по горизонтальным
     * отрезкам: для каждой строки пикселей берутся непрерывные куски выше порога, соседние по
     * вертикали объединяются. Затем прямоугольник «раздувается» (аналог unclip в PaddleOCR),
     * иначе рамка режет буквы по краям.
     */
    private fun boxes(probability: FloatArray, width: Int, height: Int): List<RectF> {
        val parent = HashMap<Int, Int>()
        fun find(x: Int): Int {
            var r = x
            while (parent.getValue(r) != r) {
                parent[r] = parent.getValue(parent.getValue(r))
                r = parent.getValue(r)
            }
            return r
        }

        fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[rb] = ra
        }

        // Отрезок: (начало, конец, ключ) — ключ уникален по строке и началу.
        var previous = ArrayList<Triple<Int, Int, Int>>()
        val bounds = HashMap<Int, IntArray>() // ключ корня -> [x1, y1, x2, y2]
        val keys = HashMap<Int, IntArray>()
        for (y in 0 until height) {
            val current = ArrayList<Triple<Int, Int, Int>>()
            var x = 0
            while (x < width) {
                if (probability[y * width + x] <= THRESHOLD) {
                    x++
                    continue
                }
                val start = x
                while (x < width && probability[y * width + x] > THRESHOLD) x++
                val key = y * width + start
                parent[key] = key
                keys[key] = intArrayOf(start, y, x, y + 1)
                current += Triple(start, x, key)
            }
            for ((start, end, key) in current) {
                for ((ps, pe, pkey) in previous) {
                    if (ps < end && start < pe) union(pkey, key)
                }
            }
            previous = current
        }
        for ((key, box) in keys) {
            val root = find(key)
            val existing = bounds[root]
            if (existing == null) {
                bounds[root] = box.copyOf()
            } else {
                existing[0] = min(existing[0], box[0])
                existing[1] = min(existing[1], box[1])
                existing[2] = max(existing[2], box[2])
                existing[3] = max(existing[3], box[3])
            }
        }

        val result = ArrayList<RectF>()
        for (box in bounds.values) {
            val w = box[2] - box[0]
            val h = box[3] - box[1]
            if (w < MIN_SIDE || h < MIN_SIDE) continue
            if (meanProbability(probability, width, box) < BOX_THRESHOLD) continue
            val pad = (w.toFloat() * h) * UNCLIP / (2f * (w + h))
            result += RectF(
                ((box[0] - pad) / width).coerceIn(0f, 1f),
                ((box[1] - pad) / height).coerceIn(0f, 1f),
                ((box[2] + pad) / width).coerceIn(0f, 1f),
                ((box[3] + pad) / height).coerceIn(0f, 1f),
            )
        }
        return result
    }

    private fun meanProbability(probability: FloatArray, width: Int, box: IntArray): Float {
        var sum = 0f
        var count = 0
        for (y in box[1] until box[3]) {
            for (x in box[0] until box[2]) {
                sum += probability[y * width + x]
                count++
            }
        }
        return if (count == 0) 0f else sum / count
    }

    private companion object {
        const val INPUT_LIMIT = 736
        const val THRESHOLD = 0.3f
        /** Средняя вероятность внутри области: отсекает размытые пятна, похожие на текст. */
        const val BOX_THRESHOLD = 0.6f
        const val UNCLIP = 1.6f
        const val MIN_SIDE = 3
        val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        val STD = floatArrayOf(0.229f, 0.224f, 0.225f)
    }
}
