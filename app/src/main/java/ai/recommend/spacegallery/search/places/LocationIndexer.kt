package ai.recommend.spacegallery.search.places

import ai.recommend.spacegallery.data.db.AppDatabase
import ai.recommend.spacegallery.data.db.MediaEntity
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.math.abs

/**
 * Чтение геометок: EXIF фото и метаданные видео. На Android 10+ MediaStore вырезает координаты,
 * если у приложения нет ACCESS_MEDIA_LOCATION, — без разрешения проход не выполняется (и
 * повторится, когда разрешение появится). Только чтение метаданных: ~2–5 мс на файл.
 */
class LocationIndexer(private val context: Context, private val db: AppDatabase) {
    private val dao = db.analysisDao()

    val hasPermission: Boolean
        get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED

    suspend fun countPending(): Int = if (hasPermission) dao.countLocationPending(VERSION) else 0

    suspend fun run(isStopped: () -> Boolean, onProgress: suspend (Int) -> Unit): Int {
        if (!hasPermission) return 0
        var processed = 0
        var afterDate = Long.MAX_VALUE
        var afterId = Long.MAX_VALUE
        while (!isStopped()) {
            val page = dao.getLocationPendingPage(VERSION, afterDate, afterId, BATCH)
            if (page.isEmpty()) break
            val results = coroutineScope {
                page.map { media -> async(Dispatchers.IO) { media.id to runCatching { read(media) }.getOrNull() } }.awaitAll()
            }
            db.withTransaction {
                for ((id, point) in results) dao.setLocation(id, point?.first, point?.second, VERSION)
            }
            processed += results.size
            onProgress(processed)
            afterDate = page.last().dateTaken
            afterId = page.last().id
        }
        return processed
    }

    private fun read(media: MediaEntity): Pair<Double, Double>? {
        val uri = media.uri.toUri()
        val point = if (media.mediaType == 0) {
            val original = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.setRequireOriginal(uri) else uri
            context.contentResolver.openInputStream(original)?.use { ExifInterface(it).latLong }?.let { it[0] to it[1] }
        } else {
            val retriever = MediaMetadataRetriever()
            try {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    retriever.setDataSource(pfd.fileDescriptor)
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)?.let(::parseIso6709)
                }
            } finally {
                retriever.release()
            }
        }
        // (0, 0) — типичный мусор в метаданных, а не точка в Гвинейском заливе.
        return point?.takeIf { (lat, lon) -> abs(lat) <= 90 && abs(lon) <= 180 && (abs(lat) > 1e-6 || abs(lon) > 1e-6) }
    }

    companion object {
        const val VERSION = 1
        private const val BATCH = 32
        private val ISO6709 = Regex("""([+-]\d+(?:\.\d+)?)([+-]\d+(?:\.\d+)?)""")

        /** «+55.7558+037.6173/» (ISO 6709, как пишут камеры телефонов) -> (55.7558, 37.6173). */
        fun parseIso6709(value: String): Pair<Double, Double>? =
            ISO6709.find(value)?.destructured?.let { (lat, lon) -> lat.toDouble() to lon.toDouble() }
    }
}
