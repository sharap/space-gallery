package ai.recommend.spacegallery.ui.albums

import ai.recommend.spacegallery.data.media.AlbumNames
import ai.recommend.spacegallery.data.media.DeleteResult
import ai.recommend.spacegallery.data.repository.MediaRepository
import ai.recommend.spacegallery.domain.Album
import ai.recommend.spacegallery.domain.MediaItem
import android.content.IntentSender
import android.os.Build
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/** Куда отправить фото. */
sealed interface MoveTarget {
    data class Existing(val album: Album) : MoveTarget

    /** Новый альбом: папка Pictures/<name>/. */
    data class New(val name: String) : MoveTarget
}

/** Итог файловой операции — показывается пользователю коротким сообщением. */
sealed interface AlbumEvent {
    data class Moved(val moved: Int, val failed: Int) : AlbumEvent

    /** [newAlbumId] — id альбома после переименования (он зависит от пути папки). */
    data class Renamed(val newAlbumId: Long?, val failed: Int) : AlbumEvent
    data object Deleted : AlbumEvent
}

/**
 * Операции над альбомами-папками, общие для экранов альбомов, страницы альбома и мультивыбора фото.
 * Каждая операция в два шага: запросить у системы разрешение (IntentSender), затем выполнить.
 * Между шагами операция хранится в [pending] (живёт в ViewModel).
 */
class AlbumOperations(private val repository: MediaRepository) {

    val isSupported: Boolean get() = repository.supportsAlbumManagement

    private var pending: (suspend () -> AlbumEvent)? = null
    private var pendingTrash: List<MediaItem> = emptyList()

    private val _events = Channel<AlbumEvent>(Channel.BUFFERED)
    val events: Flow<AlbumEvent> = _events.receiveAsFlow()

    /** Переименовать альбом. null — альбом пуст или операция недоступна. */
    suspend fun requestRename(album: Album, newName: String): IntentSender? {
        if (!AlbumNames.canRename(album.relativePath)) return null
        val items = repository.getAlbumItems(listOf(album.id))
        return requestWrite(items) {
            val (result, newId) = repository.renameAlbum(items, album.relativePath, AlbumNames.normalize(newName))
            AlbumEvent.Renamed(newId, result.failed)
        }
    }

    /** Отправить фото в альбом; фото, которые уже в нём, пропускаются. */
    suspend fun requestMove(items: List<MediaItem>, target: MoveTarget): IntentSender? {
        val (toMove, path) = when (target) {
            is MoveTarget.Existing -> items.filter { it.albumId != target.album.id } to target.album.relativePath
            is MoveTarget.New -> items to AlbumNames.newAlbumPath(target.name)
        }
        // Страховка: UI не даёт выбрать такую папку, но до системного диалога дойти не должно.
        if (!AlbumNames.canHold(path, toMove.mapTo(HashSet()) { it.type })) return null
        return requestWrite(toMove) {
            val result = repository.moveToFolder(toMove, path)
            AlbumEvent.Moved(result.moved, result.failed)
        }
    }

    /** Удалить альбомы = все их файлы в корзину (с системным подтверждением). */
    suspend fun requestDelete(albums: List<Album>): DeleteResult {
        pendingTrash = repository.getAlbumItems(albums.map { it.id })
        val result = repository.moveToTrash(pendingTrash)
        if (result is DeleteResult.Done) _events.send(AlbumEvent.Deleted)
        return result
    }

    suspend fun onTrashConfirmed() {
        repository.onTrashed(pendingTrash)
        pendingTrash = emptyList()
        _events.send(AlbumEvent.Deleted)
    }

    /** Пользователь разрешил изменение файлов — выполнить отложенную операцию. */
    suspend fun onWriteGranted(): AlbumEvent? {
        val operation = pending ?: return null
        pending = null
        return operation().also { _events.send(it) }
    }

    private fun requestWrite(items: List<MediaItem>, operation: suspend () -> AlbumEvent): IntentSender? {
        if (items.isEmpty() || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        pending = operation
        return repository.createWriteRequest(items)
    }
}
