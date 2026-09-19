package ai.recommend.spacegallery.ui.components

import ai.recommend.spacegallery.data.media.DeleteResult
import ai.recommend.spacegallery.data.repository.MediaRepository
import ai.recommend.spacegallery.domain.MediaItem
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

/** Групповые действия над выбранными медиа (контекстная панель мультивыбора). */
class MediaActionsViewModel(private val repository: MediaRepository) : ViewModel() {

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
