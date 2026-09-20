package ai.recommend.spacegallery.ml.image

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Вырезанное из оригинала лицо и перевод координат кадра в координаты этого куска:
 * точка (x, y) исходного снимка -> ((x − left) * scale, (y − top) * scale).
 */
class FaceCrop(
    val bitmap: Bitmap,
    private val left: Float,
    private val top: Float,
    private val scale: Float,
    private val sourceWidth: Int,
    private val sourceHeight: Int,
) {
    /** Координаты приходят в долях кадра (так их хранит детектор и база). */
    fun mapX(fraction: Float): Float = (fraction * sourceWidth - left) * scale

    fun mapY(fraction: Float): Float = (fraction * sourceHeight - top) * scale
}

/**
 * Декодирует из оригинала только область вокруг лица.
 *
 * Зачем: лица ищутся на превью 640 px, и там медианное лицо занимает всего ~22 пикселя
 * (замер на реальной медиатеке, 2026-09-20). Вектор такого лица совпадает с вектором того же
 * лица из оригинала лишь на 0.73 — это уровень «другого человека», то есть распознавание идёт
 * по испорченным данным. Полное декодирование оригинала ради этого не нужно: достаточно куска
 * вокруг лица, а JPEG умеет отдавать его построчно.
 *
 * Снимки часто повёрнуты флагом EXIF: координаты лица приходят в «экранной» системе, а декодер
 * области работает в исходной, поэтому область пересчитывается в исходные координаты, а
 * вырезанный кусок затем поворачивается обратно.
 */
class FaceCropLoader(private val resolver: ContentResolver) {

    suspend fun load(uri: Uri, box: RectF, targetFaceSize: Int = FACE_SIZE): FaceCrop? = withContext(Dispatchers.IO) {
        runCatching { decode(uri, box, targetFaceSize) }.getOrNull()
    }

    private fun decode(uri: Uri, box: RectF, targetFaceSize: Int): FaceCrop? {
        val rotation = rotationOf(uri)
        resolver.openInputStream(uri).use { stream ->
            if (stream == null) return null
            val decoder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                BitmapRegionDecoder.newInstance(stream)
            } else {
                @Suppress("DEPRECATION")
                BitmapRegionDecoder.newInstance(stream, false)
            } ?: return null
            try {
                // Размеры в «экранной» системе: при повороте на 90° стороны меняются местами.
                val swap = rotation == 90 || rotation == 270
                val width = if (swap) decoder.height else decoder.width
                val height = if (swap) decoder.width else decoder.height
                val face = RectF(box.left * width, box.top * height, box.right * width, box.bottom * height)
                val region = expand(face, width, height)
                val sample = sampleSize(face.width(), targetFaceSize)
                val options = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val raw = decoder.decodeRegion(toSource(region, rotation, width, height), options) ?: return null
                val bitmap = if (rotation == 0) raw else rotate(raw, rotation)
                // Реальный масштаб берём из результата: декодер округляет inSampleSize.
                val scale = bitmap.width.toFloat() / region.width()
                return FaceCrop(bitmap, region.left.toFloat(), region.top.toFloat(), scale, width, height)
            } finally {
                decoder.recycle()
            }
        }
    }

    /** Область с запасом вокруг лица: выравниванию нужны поля за рамкой. */
    private fun expand(face: RectF, width: Int, height: Int): Rect {
        val margin = max(face.width(), face.height()) * MARGIN
        return Rect(
            max(0f, face.left - margin).roundToInt(),
            max(0f, face.top - margin).roundToInt(),
            min(width.toFloat(), face.right + margin).roundToInt(),
            min(height.toFloat(), face.bottom + margin).roundToInt(),
        )
    }

    /** Степень двойки, после которой лицо остаётся не мельче [target]. */
    private fun sampleSize(faceWidth: Float, target: Int): Int {
        var sample = 1
        while (faceWidth / (sample * 2) >= target) sample *= 2
        return sample
    }

    private fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    private fun rotationOf(uri: Uri): Int = runCatching {
        resolver.openInputStream(uri)?.use { ExifInterface(it) }?.rotationDegrees ?: 0
    }.getOrDefault(0)

    private companion object {
        /** Сколько пикселей должно остаться на лицо: модель распознавания работает со 112×112. */
        const val FACE_SIZE = 160
        const val MARGIN = 0.6f

        /** Область из «экранных» координат — в координаты файла (до поворота по EXIF). */
        fun toSource(region: Rect, rotation: Int, width: Int, height: Int): Rect = when (rotation) {
            90 -> Rect(region.top, width - region.right, region.bottom, width - region.left)
            180 -> Rect(width - region.right, height - region.bottom, width - region.left, height - region.top)
            270 -> Rect(height - region.bottom, region.left, height - region.top, region.right)
            else -> Rect(region)
        }
    }
}
