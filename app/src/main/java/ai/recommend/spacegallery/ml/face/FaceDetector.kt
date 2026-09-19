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
 * Детектор лиц YuNet. Декодирование повторяет cv::FaceDetectorYN (сверено с OpenCV на
 * тестовых фото): для ячейки (row, col) шага s — score = sqrt(cls·obj), центр = (col + dx)·s,
 * размер = exp(dw)·s, точки = (kps + col|row)·s; затем NMS.
 */
class FaceDetector(private val models: ModelProvider) {

    val isAvailable: Boolean get() = models.isAvailable(ModelId.FACE_DETECT)

    suspend fun detect(bitmap: Bitmap, minScore: Float = MIN_SCORE): List<DetectedFace> {
        val session = models.session(ModelId.FACE_DETECT) ?: return emptyList()
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
        return nms(candidates).map { it.clippedTo(bitmap.width, bitmap.height) }
    }

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

        /** Порог уверенности: у OpenCV по умолчанию 0.9; 0.8 находит больше лиц в профиль без мусора. */
        const val MIN_SCORE = 0.8f
    }
}
