package ai.recommend.spacegallery.data.media

import ai.recommend.spacegallery.data.db.MediaEntity
import android.content.ContentResolver
import android.content.ContentUris
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Читает все фото и видео из MediaStore одним запросом к таблице Files. */
class MediaStoreSource(private val resolver: ContentResolver) {

    suspend fun queryAll(): List<MediaEntity> = withContext(Dispatchers.IO) {
        val result = ArrayList<MediaEntity>()
        resolver.query(
            MediaStore.Files.getContentUri("external"),
            PROJECTION,
            "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)",
            arrayOf(
                MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
                MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
            ),
            null,
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val typeCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val takenCol = c.getColumnIndexOrThrow(COL_DATE_TAKEN)
            val addedCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val modifiedCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val widthCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
            val heightCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
            val durationCol = c.getColumnIndexOrThrow(COL_DURATION)
            val bucketIdCol = c.getColumnIndexOrThrow(COL_BUCKET_ID)
            val bucketNameCol = c.getColumnIndexOrThrow(COL_BUCKET_NAME)
            val relativePathCol = c.getColumnIndex(COL_RELATIVE_PATH) // нет на Android 9

            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val isVideo = c.getInt(typeCol) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                val baseUri = if (isVideo) {
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                } else {
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                }
                val taken = c.getLong(takenCol).takeIf { it > 0 } ?: (c.getLong(addedCol) * 1000)
                result += MediaEntity(
                    id = id,
                    uri = ContentUris.withAppendedId(baseUri, id).toString(),
                    mediaType = if (isVideo) 1 else 0,
                    mimeType = c.getString(mimeCol).orEmpty(),
                    displayName = c.getString(nameCol).orEmpty(),
                    dateTaken = taken,
                    dateModified = c.getLong(modifiedCol),
                    size = c.getLong(sizeCol),
                    width = c.getInt(widthCol),
                    height = c.getInt(heightCol),
                    durationMs = c.getLong(durationCol),
                    bucketId = c.getLong(bucketIdCol),
                    bucketName = c.getString(bucketNameCol).orEmpty(),
                    relativePath = if (relativePathCol >= 0) c.getString(relativePathCol).orEmpty() else "",
                )
            }
        }
        result
    }

    private companion object {
        // Строковые имена колонок: присутствуют в таблице Files начиная с API 28,
        // но константы MediaColumns.* для них появились только в API 29.
        const val COL_DATE_TAKEN = "datetaken"
        const val COL_DURATION = "duration"
        const val COL_BUCKET_ID = "bucket_id"
        const val COL_BUCKET_NAME = "bucket_display_name"
        const val COL_RELATIVE_PATH = "relative_path"

        val PROJECTION = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DISPLAY_NAME,
            COL_DATE_TAKEN,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            COL_DURATION,
            COL_BUCKET_ID,
            COL_BUCKET_NAME,
        ) + if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) arrayOf(COL_RELATIVE_PATH) else emptyArray()
    }
}
