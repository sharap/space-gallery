package ai.recommend.spacegallery.ml.image

import ai.recommend.spacegallery.domain.MediaType
import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Size
import androidx.core.graphics.scale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/** Загружает уменьшенные software-битмапы для ML (для видео — кадр-превью). */
class BitmapLoader(private val resolver: ContentResolver) {

    suspend fun load(uri: Uri, type: MediaType, targetSize: Int = DEFAULT_SIZE): Bitmap? =
        withContext(Dispatchers.IO) {
            runCatching {
                val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Системные превью закешированы MediaStore — это самый быстрый путь.
                    resolver.loadThumbnail(uri, Size(targetSize, targetSize), null)
                } else when (type) {
                    MediaType.IMAGE -> decodeImage(uri, targetSize)
                    MediaType.VIDEO -> decodeVideoFrame(uri, targetSize)
                }
                bitmap?.ensureSoftware()
            }.getOrNull()
        }

    private fun decodeImage(uri: Uri, targetSize: Int): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longest = max(info.size.width, info.size.height)
            if (longest > targetSize) {
                val ratio = targetSize.toFloat() / longest
                decoder.setTargetSize(
                    (info.size.width * ratio).toInt().coerceAtLeast(1),
                    (info.size.height * ratio).toInt().coerceAtLeast(1),
                )
            }
        }

    private fun decodeVideoFrame(uri: Uri, targetSize: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            resolver.openFileDescriptor(uri, "r")?.use { pfd ->
                retriever.setDataSource(pfd.fileDescriptor)
                retriever.getFrameAtTime(0)?.let { frame ->
                    val ratio = targetSize.toFloat() / max(frame.width, frame.height)
                    if (ratio >= 1f) frame else frame.scale(
                        (frame.width * ratio).toInt().coerceAtLeast(1),
                        (frame.height * ratio).toInt().coerceAtLeast(1),
                    )
                }
            }
        } finally {
            retriever.release()
        }
    }

    private fun Bitmap.ensureSoftware(): Bitmap =
        if (config == Bitmap.Config.HARDWARE) copy(Bitmap.Config.ARGB_8888, false) else this

    companion object {
        const val DEFAULT_SIZE = 384
    }
}
