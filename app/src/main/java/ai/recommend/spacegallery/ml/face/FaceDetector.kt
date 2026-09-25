package ai.recommend.spacegallery.ml.face

import ai.onnxruntime.OnnxTensor
import ai.recommend.spacegallery.ml.onnx.ModelId
import ai.recommend.spacegallery.ml.onnx.ModelProvider
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.graphics.createBitmap
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Найденное лицо в координатах исходного битмапа.
 * [landmarks] — 5 точек (x, y): глаза, нос, углы рта — слева направо на изображении.
 */
class DetectedFace(val box: RectF, val landmarks: FloatArray, val score: Float)

/**
 * Часть кадра в пикселях. Свой тип, а не android Rect: так геометрию проверяет обычный
 * юнит-тест, без эмулятора.
 */
data class Tile(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    fun contains(x: Int, y: Int): Boolean = x >= left && x < right && y >= top && y < bottom
    fun contains(other: Tile): Boolean =
        other.left >= left && other.top >= top && other.right <= right && other.bottom <= bottom
}

/**
 * Детектор лиц YuNet. Декодирование повторяет cv::FaceDetectorYN (сверено с OpenCV на
 * тестовых фото): для ячейки (row, col) шага s — score = sqrt(cls·obj), центр = (col + dx)·s,
 * размер = exp(dw)·s, точки = (kps + col|row)·s; затем NMS.
 */
class FaceDetector(private val models: ModelProvider) {

    val isAvailable: Boolean get() = models.isAvailable(ModelId.FACE_DETECT)

    suspend fun detect(bitmap: Bitmap, minScore: Float = MIN_SCORE): List<DetectedFace> = models.use(ModelId.FACE_DETECT) { session ->
        val scale = INPUT / max(bitmap.width, bitmap.height).toFloat()
        val input = letterboxBgr(bitmap, scale)
        val shape = longArrayOf(1, 3, INPUT.toLong(), INPUT.toLong())
        val candidates = ArrayList<DetectedFace>()
        OnnxTensor.createTensor(models.env, input, shape).use { tensor ->
            session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
                fun output(name: String): FloatArray {
                    val buffer = (result.get(name).get() as OnnxTensor).floatBuffer
                    return FloatArray(buffer.remaining()).also { buffer.get(it) }
                }
                for (stride in STRIDES) {
                    val cls = output("cls_$stride")
                    val obj = output("obj_$stride")
                    val bbox = output("bbox_$stride")
                    val kps = output("kps_$stride")
                    val cols = INPUT / stride
                    for (i in cls.indices) {
                        val score = sqrt(cls[i].coerceIn(0f, 1f) * obj[i].coerceIn(0f, 1f))
                        if (score < minScore) continue
                        val row = i / cols
                        val col = i % cols
                        val cx = (col + bbox[i * 4]) * stride
                        val cy = (row + bbox[i * 4 + 1]) * stride
                        val w = exp(bbox[i * 4 + 2]) * stride
                        val h = exp(bbox[i * 4 + 3]) * stride
                        // Обратно в координаты исходного битмапа (поля справа/снизу не сдвигают начало).
                        val box = RectF((cx - w / 2) / scale, (cy - h / 2) / scale, (cx + w / 2) / scale, (cy + h / 2) / scale)
                        val landmarks = FloatArray(10) { k ->
                            val offset = if (k % 2 == 0) col else row
                            (kps[i * 10 + k] + offset) * stride / scale
                        }
                        candidates += DetectedFace(box, landmarks, score)
                    }
                }
            }
        }
        nms(candidates).map { it.clippedTo(bitmap.width, bitmap.height) }
    } ?: emptyList()

    /** Вписать кадр в 640×640 (поля справа/снизу чёрные) -> NCHW float в порядке BGR, 0..255. */
    private fun letterboxBgr(bitmap: Bitmap, scale: Float): FloatBuffer {
        val canvasBitmap = createBitmap(INPUT, INPUT)
        Canvas(canvasBitmap).apply {
            drawColor(Color.BLACK)
            scale(scale, scale)
            drawBitmap(bitmap, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        val pixels = IntArray(INPUT * INPUT)
        canvasBitmap.getPixels(pixels, 0, INPUT, 0, 0, INPUT, INPUT)
        canvasBitmap.recycle()
        val plane = INPUT * INPUT
        val buffer = FloatBuffer.allocate(3 * plane)
        for (i in 0 until plane) {
            val p = pixels[i]
            buffer.put(i, (p and 0xFF).toFloat()) // B
            buffer.put(plane + i, ((p shr 8) and 0xFF).toFloat()) // G
            buffer.put(2 * plane + i, ((p shr 16) and 0xFF).toFloat()) // R
        }
        return buffer
    }

    /**
     * Поиск по частям кадра: сетка [grid]×[grid] с перекрытием, каждая часть уходит в модель
     * отдельно и потому занимает те же 640 px целиком.
     *
     * Нужно для групповых снимков: детектор всегда вписывает кадр в 640×640, и лицо в толпе
     * приходит к нему размером с десяток пикселей. На реальных кадрах (замер 2026-09-24,
     * 15 «людных» снимков) один проход находит 384 лица, проход по частям — 467.
     *
     * Координаты возвращаются в пикселях [bitmap]; одно лицо, попавшее в соседние части,
     * остаётся в единственном экземпляре. Части — отдельные битмапы, поэтому каждая
     * освобождается сразу после разбора: на телефоне память кончается быстро.
     */
    suspend fun detectTiled(
        bitmap: Bitmap,
        grid: Int,
        minScore: Float = MIN_SCORE,
        minFaceFraction: Float = 0f,
    ): List<DetectedFace> {
        if (grid <= 1) return detect(bitmap, minScore)
        val found = ArrayList<DetectedFace>()
        for (tile in tileRects(bitmap.width, bitmap.height, grid)) {
            val part = Bitmap.createBitmap(bitmap, tile.left, tile.top, tile.width, tile.height)
            try {
                val minSide = max(tile.width, tile.height) * minFaceFraction
                for (face in detect(part, minScore)) {
                    if (max(face.box.width(), face.box.height()) < minSide) continue
                    found += face.movedBy(tile.left.toFloat(), tile.top.toFloat())
                }
            } finally {
                if (part != bitmap) part.recycle()
            }
        }
        return nms(found)
    }

    private fun DetectedFace.movedBy(dx: Float, dy: Float) = DetectedFace(
        RectF(box.left + dx, box.top + dy, box.right + dx, box.bottom + dy),
        FloatArray(landmarks.size) { i -> if (i % 2 == 0) landmarks[i] + dx else landmarks[i] + dy },
        score,
    )

    /** Слить находки разных проходов: одно лицо остаётся в одном экземпляре. */
    fun mergeOverlapping(faces: List<DetectedFace>): List<DetectedFace> = nms(faces)

    private fun nms(faces: List<DetectedFace>): List<DetectedFace> {
        val sorted = faces.sortedByDescending { it.score }
        val kept = ArrayList<DetectedFace>()
        for (face in sorted) {
            if (kept.none { iou(it.box, face.box) > NMS_IOU }) kept += face
        }
        return kept
    }

    private fun iou(a: RectF, b: RectF): Float {
        val w = min(a.right, b.right) - max(a.left, b.left)
        val h = min(a.bottom, b.bottom) - max(a.top, b.top)
        if (w <= 0f || h <= 0f) return 0f
        val inter = w * h
        return inter / (a.width() * a.height() + b.width() * b.height() - inter)
    }

    private fun DetectedFace.clippedTo(width: Int, height: Int) = DetectedFace(
        RectF(box.left.coerceAtLeast(0f), box.top.coerceAtLeast(0f), box.right.coerceAtMost(width.toFloat()), box.bottom.coerceAtMost(height.toFloat())),
        landmarks,
        score,
    )

    companion object {
        private const val INPUT = 640
        private val STRIDES = intArrayOf(8, 16, 32)
        private const val NMS_IOU = 0.3f

        /** Части кадра перекрываются, иначе лицо на стыке разрезается пополам. */
        private const val TILE_OVERLAP = 0.15f

        /** Совсем мелкие куски в модель не отправляем — смысла нет. */
        private const val MIN_TILE = 64

        /**
         * Части кадра для сетки [grid]×[grid] с перекрытием. Перекрытие обязательно: лицо
         * на стыке иначе попадает в обе части половинками и не находится ни в одной.
         */
        fun tileRects(width: Int, height: Int, grid: Int): List<Tile> {
            if (grid <= 1) return listOf(Tile(0, 0, width, height))
            val stepX = width / grid
            val stepY = height / grid
            val overlapX = (stepX * TILE_OVERLAP).toInt()
            val overlapY = (stepY * TILE_OVERLAP).toInt()
            val tiles = ArrayList<Tile>(grid * grid)
            for (row in 0 until grid) for (col in 0 until grid) {
                val left = (col * stepX - overlapX).coerceAtLeast(0)
                val top = (row * stepY - overlapY).coerceAtLeast(0)
                // Крайние части дотягиваются до самого края кадра: остаток от деления не теряем.
                val right = (if (col == grid - 1) width else (col + 1) * stepX + overlapX).coerceAtMost(width)
                val bottom = (if (row == grid - 1) height else (row + 1) * stepY + overlapY).coerceAtMost(height)
                val tile = Tile(left, top, right, bottom)
                if (tile.width >= MIN_TILE && tile.height >= MIN_TILE) tiles += tile
            }
            return tiles
        }

        /**
         * Порог уверенности (у OpenCV по умолчанию 0.9). Подобран на 600 реальных фото (2026-09-19):
         * 0.8 -> 0.6 даёт на 58% больше фото с лицами, новые лица в основном узнаются как уже
         * известные люди; 0.5 добавляет почти только неузнаваемые срабатывания.
         */
        const val MIN_SCORE = 0.6f
    }
}
