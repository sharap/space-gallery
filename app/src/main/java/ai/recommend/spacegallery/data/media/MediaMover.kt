package ai.recommend.spacegallery.data.media

import ai.recommend.spacegallery.domain.MediaType
import android.content.ContentResolver
import android.content.ContentValues
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Перенос медиа между папками (альбомами) через MediaStore.RELATIVE_PATH.
 * Android 11+: чужие файлы можно менять только после согласия пользователя —
 * один системный диалог на весь набор ([createWriteRequest]).
 */
class MediaMover(private val resolver: ContentResolver) {

    val isSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    @RequiresApi(Build.VERSION_CODES.R)
    fun createWriteRequest(uris: List<Uri>): IntentSender =
        MediaStore.createWriteRequest(resolver, uris).intentSender

    /** Переносит файлы в [relativePath] (например `Pictures/Отпуск/`). */
    suspend fun moveTo(uris: List<Uri>, relativePath: String): MoveResult = withContext(Dispatchers.IO) {
        var moved = 0
        var failed = 0
        val values = ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath) }
        for (uri in uris) {
            try {
                if (resolver.update(uri, values, null, null) > 0) moved++ else failed++
            } catch (e: Exception) {
                // Например, папка другого приложения (Android/media/...) или недопустимый каталог.
                Log.w(TAG, "Не удалось перенести $uri в $relativePath", e)
                failed++
            }
        }
        MoveResult(moved, failed)
    }

    /** BUCKET_ID файла после переноса — id альбома в новой папке. */
    suspend fun bucketIdOf(uri: Uri): Long? = withContext(Dispatchers.IO) {
        resolver.query(uri, arrayOf(COL_BUCKET_ID), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getLong(0) else null
        }
    }

    private companion object {
        const val TAG = "MediaMover"
        const val COL_BUCKET_ID = "bucket_id"
    }
}

data class MoveResult(val moved: Int, val failed: Int)

/** Имя альбома = имя папки: без разделителей и символов, запрещённых в FAT/exFAT. */
object AlbumNames {
    private val FORBIDDEN = Regex("""[/\\:*?"<>|\x00-\x1F]""")
    const val MAX_LENGTH = 100

    fun normalize(name: String): String = name.trim().replace(Regex("\\s+"), " ")

    fun isValid(name: String): Boolean {
        val n = normalize(name)
        return n.isNotEmpty() && n.length <= MAX_LENGTH && !FORBIDDEN.containsMatchIn(n) && n != "." && n != ".."
    }

    /** `DCIM/Camera/` + «Море» -> `DCIM/Море/`; файлы в корне тома -> `Pictures/Море/`. */
    fun renamedPath(currentPath: String, newName: String): String {
        val parent = currentPath.trimEnd('/').substringBeforeLast('/', missingDelimiterValue = "")
        return if (parent.isEmpty()) newAlbumPath(newName) else "$parent/${normalize(newName)}/"
    }

    /** Новые альбомы создаются в Pictures/ — туда MediaStore разрешает класть и фото, и видео. */
    fun newAlbumPath(name: String): String = "Pictures/${normalize(name)}/"

    /**
     * MediaStore разрешает класть медиа только в стандартные корневые папки:
     * фото — DCIM и Pictures, видео — DCIM, Movies и Pictures. Папки вроде `sync/`, `Download/`
     * или `Android/media/...` принимать файлы через MediaStore не могут.
     */
    fun canHold(relativePath: String, types: Set<MediaType>): Boolean {
        val root = relativePath.substringBefore('/')
        return types.all { type -> allowedRoots(type).any { it.equals(root, ignoreCase = true) } }
    }

    /**
     * Переименование = перенос в соседнюю папку того же корня, поэтому корень альбома должен
     * принимать файлы. Типы содержимого альбома заранее неизвестны: Movies/ допускаем
     * (там обычно видео), фото в нём при переносе попадут в «не удалось».
     */
    fun canRename(relativePath: String): Boolean {
        val root = relativePath.substringBefore('/')
        return RENAMABLE_ROOTS.any { it.equals(root, ignoreCase = true) }
    }

    private fun allowedRoots(type: MediaType) = when (type) {
        MediaType.IMAGE -> IMAGE_ROOTS
        MediaType.VIDEO -> VIDEO_ROOTS
    }

    private val IMAGE_ROOTS = listOf("DCIM", "Pictures")
    private val VIDEO_ROOTS = listOf("DCIM", "Movies", "Pictures")
    private val RENAMABLE_ROOTS = listOf("DCIM", "Movies", "Pictures")
}
