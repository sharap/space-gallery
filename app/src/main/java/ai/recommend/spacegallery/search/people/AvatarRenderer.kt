package ai.recommend.spacegallery.search.people

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.net.Uri
import android.util.Log
import androidx.core.graphics.scale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Аватар человека из оригинального файла: ImageDecoder декодирует сразу в нужном масштабе
 * и с обрезкой по области лица (для JPEG — частичное декодирование), учитывая поворот из EXIF.
 * Рамка лица хранится в долях кадра, поэтому не зависит от разрешения превью.
 */
class AvatarRenderer(private val resolver: ContentResolver) {

    /** [left]..[bottom] — рамка лица в долях кадра. null — файл не читается. */
    suspend fun render(uri: Uri, left: Float, top: Float, right: Float, bottom: Float): ByteArray? =
        withContext(Dispatchers.IO) {
            runCatching {
                val source = ImageDecoder.createSource(resolver, uri)
                val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val w = info.size.width
                    val h = info.size.height
                    // Квадрат вокруг лица с запасом — в пикселях оригинала.
                    val side = max((right - left) * w, (bottom - top) * h) * MARGIN
                    // Масштаб, при котором квадрат станет AVATAR px; увеличивать оригинал нет смысла.
                    val scale = min(1f, AVATAR / side)
                    val tw = (w * scale).roundToInt().coerceAtLeast(1)
                    val th = (h * scale).roundToInt().coerceAtLeast(1)
                    decoder.setTargetSize(tw, th)
                    val cx = (left + right) / 2 * tw
                    val cy = (top + bottom) / 2 * th
                    val half = side * scale / 2
                    val crop = Rect((cx - half).roundToInt(), (cy - half).roundToInt(), (cx + half).roundToInt(), (cy + half).roundToInt())
                    if (!crop.intersect(0, 0, tw, th)) crop.set(0, 0, tw, th)
                    decoder.crop = crop
                }
                val out = if (bitmap.width > AVATAR || bitmap.height > AVATAR) {
                    bitmap.scale(AVATAR.toInt(), (AVATAR * bitmap.height / bitmap.width).toInt().coerceAtLeast(1))
                } else {
                    bitmap
                }
                ByteArrayOutputStream().use { stream ->
                    out.compress(Bitmap.CompressFormat.JPEG, 90, stream)
                    stream.toByteArray()
                }
            }.onFailure { Log.w(TAG, "Не удалось сделать аватар из $uri", it) }.getOrNull()
        }

    private companion object {
        const val TAG = "AvatarRenderer"
        const val AVATAR = 256f
        const val MARGIN = 1.5f
    }
}
