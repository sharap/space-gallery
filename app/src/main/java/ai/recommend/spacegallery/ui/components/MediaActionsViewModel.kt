package ai.recommend.spacegallery.ui.components

import ai.recommend.spacegallery.data.media.DeleteResult
import ai.recommend.spacegallery.data.repository.MediaRepository
import ai.recommend.spacegallery.domain.Album
import ai.recommend.spacegallery.domain.MediaItem
import ai.recommend.spacegallery.ui.albums.AlbumOperations
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

/** Групповые действия над выбранными медиа (контекстная панель мультивыбора). */
class MediaActionsViewModel(private val repository: MediaRepository) : ViewModel() {

    /** «Отправить в альбом»: перенос в папку альбома с системным разрешением. */
    val albumOperations = AlbumOperations(repository)

    val albums: StateFlow<List<Album>> = repository.observeAlbums()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun onWriteGranted() = viewModelScope.launch { albumOperations.onWriteGranted() }

    /** Ожидают подтверждения системного диалога удаления. */
    private var pendingTrash: List<MediaItem> = emptyList()

    fun setFavorite(items: List<MediaItem>, favorite: Boolean) = viewModelScope.launch {
        repository.setFavorite(items.map { it.id }, favorite)
    }

    fun setHidden(items: List<MediaItem>, hidden: Boolean) = viewModelScope.launch {
        repository.setHidden(items.map { it.id }, hidden)
    }

    suspend fun trash(items: List<MediaItem>): DeleteResult {
        pendingTrash = items
        return repository.moveToTrash(items)
    }

    fun onTrashConfirmed() = viewModelScope.launch {
        repository.onTrashed(pendingTrash)
        pendingTrash = emptyList()
    }
}
