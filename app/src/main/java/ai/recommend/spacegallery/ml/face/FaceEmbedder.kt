package ai.recommend.spacegallery.ml.face

import ai.onnxruntime.OnnxTensor
import ai.recommend.spacegallery.data.settings.FaceModel
import ai.recommend.spacegallery.ml.VectorMath
import ai.recommend.spacegallery.ml.onnx.ModelProvider
import ai.recommend.spacegallery.ml.onnx.floatOutput
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import androidx.core.graphics.createBitmap
import java.nio.FloatBuffer

/**
 * Вектор лица ArcFace (512 чисел, L2-нормализованный): вход 112×112 RGB, (x − 127.5) / 127.5,
 * NCHW. Модель выбирает пользователь ([FaceModel]): MobileFaceNet (быстро) или ResNet50 (точно) —
 * предобработка у них одинаковая, различаются размер и скорость. Лицо выравнивается по 5 точкам преобразованием подобия на шаблон ArcFace 112×112 —
 * как FaceRecognizerSF::alignCrop (сверено с OpenCV: совпадение векторов 1.0 при тех же точках).
 *
 * Замена SFace (128 чисел) на buffalo_l r50 проверена на реальной медиатеке (448 лиц,
 * 2026-09-20): при пороге, отсекающем 99% пар «разные люди», теряется 1% пар «тот же человек»
 * вместо 7% у SFace. Цена — 231 мс на лицо вместо 41 и 174 МБ модели.
 */
class FaceEmbedder(private val models: ModelProvider) {

    fun isAvailable(model: FaceModel): Boolean = models.isAvailable(model.modelId)

    suspend fun embed(bitmap: Bitmap, face: DetectedFace, model: FaceModel): FloatArray? {
        val aligned = alignCrop(bitmap, face.landmarks)
        val input = rgbTensor(aligned)
        aligned.recycle()
        return models.use(model.modelId) { session ->
            OnnxTensor.createTensor(models.env, input, longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong())).use { tensor ->
                session.run(mapOf(session.inputNames.first() to tensor)).use { VectorMath.l2Normalize(it.floatOutput()) }
            }
        }
    }

    /** Выровненный кроп лица — для отладочного сравнения моделей (bench/FaceModelDiagnostics). */
    fun alignForDiagnostics(bitmap: Bitmap, landmarks: FloatArray): Bitmap = alignCrop(bitmap, landmarks)

    private fun alignCrop(bitmap: Bitmap, landmarks: FloatArray): Bitmap {
        val out = createBitmap(SIZE, SIZE)
        Canvas(out).drawBitmap(bitmap, similarityTransform(landmarks, TEMPLATE), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    private fun rgbTensor(bitmap: Bitmap): FloatBuffer {
        val pixels = IntArray(SIZE * SIZE)
        bitmap.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        val plane = SIZE * SIZE
        val buffer = FloatBuffer.allocate(3 * plane)
        for (i in 0 until plane) {
            val p = pixels[i]
            buffer.put(i, (((p shr 16) and 0xFF) - MEAN) / STD) // R
            buffer.put(plane + i, (((p shr 8) and 0xFF) - MEAN) / STD) // G
            buffer.put(2 * plane + i, ((p and 0xFF) - MEAN) / STD) // B
        }
        return buffer
    }

    companion object {
        private const val SIZE = 112
        private const val MEAN = 127.5f
        private const val STD = 127.5f


        /** Шаблон ArcFace для 112×112: глаза, нос, углы рта. */
        private val TEMPLATE = floatArrayOf(
            38.2946f, 51.6963f, 73.5318f, 51.5014f, 56.0252f, 71.7366f, 41.5493f, 92.3655f, 70.7299f, 92.2041f,
        )

        /** Порог «тот же человек» для SFace (косинус), по OpenCV. */
        const val SAME_PERSON_SIMILARITY = 0.363f

        /**
         * Преобразование подобия (поворот + масштаб + сдвиг) src -> dst по методу наименьших квадратов:
         * x' = a·x − b·y + tx, y' = b·x + a·y + ty (закрытая форма, как Umeyama без отражения).
         */
        fun similarityTransform(src: FloatArray, dst: FloatArray): Matrix {
            val (a, b, tx, ty) = similarityParams(src, dst)
            return Matrix().apply { setValues(floatArrayOf(a, -b, tx, b, a, ty, 0f, 0f, 1f)) }
        }

        /** Параметры [a, b, tx, ty] преобразования подобия — чистая функция (тестируется без Android). */
        fun similarityParams(src: FloatArray, dst: FloatArray): FloatArray {
            val n = src.size / 2
            var msx = 0f; var msy = 0f; var mdx = 0f; var mdy = 0f
            for (i in 0 until n) {
                msx += src[2 * i]; msy += src[2 * i + 1]; mdx += dst[2 * i]; mdy += dst[2 * i + 1]
            }
            msx /= n; msy /= n; mdx /= n; mdy /= n
            var num1 = 0f; var num2 = 0f; var den = 0f
            for (i in 0 until n) {
                val sx = src[2 * i] - msx
                val sy = src[2 * i + 1] - msy
                val dx = dst[2 * i] - mdx
                val dy = dst[2 * i + 1] - mdy
                num1 += sx * dx + sy * dy
                num2 += sx * dy - sy * dx
                den += sx * sx + sy * sy
            }
            val a = num1 / den
            val b = num2 / den
            val tx = mdx - (a * msx - b * msy)
            val ty = mdy - (b * msx + a * msy)
            return floatArrayOf(a, b, tx, ty)
        }
    }
}
