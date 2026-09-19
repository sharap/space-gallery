package ai.recommend.spacegallery.ui.viewer

import ai.recommend.spacegallery.data.media.DeleteResult
import ai.recommend.spacegallery.data.repository.MediaRepository
import ai.recommend.spacegallery.domain.MediaItem
import ai.recommend.spacegallery.ui.navigation.ViewerRoute
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ViewerViewModel(
    private val repository: MediaRepository,
    handle: SavedStateHandle,
) : ViewModel() {

    private val route = handle.toRoute<ViewerRoute>()
    val initialMediaId: Long = route.mediaId

    /**
     * Лента для пролистывания. Если открытого элемента в ней нет (например, он из «Скрытого»
     * или из результатов поиска по скрытому) — показываем только его.
     */
    val items: StateFlow<List<MediaItem>?> = repository.observeTimeline(route.albumId)
        .map { list ->
            if (list.any { it.id == route.mediaId }) list else repository.getByIds(listOf(route.mediaId))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun toggleFavorite(item: MediaItem) = viewModelScope.launch {
        repository.setFavorite(item.id, !item.isFavorite)
    }

    fun toggleHidden(item: MediaItem) = viewModelScope.launch {
        repository.setHidden(listOf(item.id), !item.isHiddenByUser)
    }

    suspend fun trash(item: MediaItem): DeleteResult = repository.moveToTrash(listOf(item))

    fun onTrashConfirmed(item: MediaItem) = viewModelScope.launch {
        repository.onTrashed(listOf(item))
    }
}
