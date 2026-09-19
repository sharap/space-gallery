package ai.recommend.spacegallery.ui.viewer

import ai.recommend.spacegallery.data.media.DeleteResult
import ai.recommend.spacegallery.data.repository.MediaRepository
import ai.recommend.spacegallery.domain.MediaItem
import ai.recommend.spacegallery.search.smart.SmartAlbumRepository
import ai.recommend.spacegallery.ui.navigation.ViewerQueue
import ai.recommend.spacegallery.ui.navigation.ViewerRoute
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ViewerViewModel(
    private val repository: MediaRepository,
    private val smartAlbums: SmartAlbumRepository,
    handle: SavedStateHandle,
) : ViewModel() {

    private val route = handle.toRoute<ViewerRoute>()
    val initialMediaId: Long = route.mediaId

    /**
     * Очередь пролистывания — та, из которой открыли просмотрщик (лента, альбом, результаты
     * поиска, похожие...). Если открытого элемента в ней изначально нет (например, деликатное
     * фото при включённом фильтре) — показываем только его. Решение принимается один раз:
     * если элемент потом скрыть или удалить, очередь не схлопывается.
     */
    val items: StateFlow<List<MediaItem>?> = flow {
        val queue = queueFlow()
        if (queue.first().any { it.id == route.mediaId }) {
            emitAll(queue)
        } else {
            emitAll(repository.observeByIds(listOf(route.mediaId)))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun queueFlow(): Flow<List<MediaItem>> = when (route.queue) {
        ViewerQueue.TIMELINE -> repository.observeTimeline()
        ViewerQueue.ALBUM -> repository.observeTimeline(route.albumId)
        ViewerQueue.FAVORITES -> repository.observeFavorites()
        ViewerQueue.HIDDEN -> repository.observeHidden()
        ViewerQueue.LIST -> repository.observeByIds(route.ids)
        ViewerQueue.SMART_ALBUM -> smartAlbums.observeItems(route.albumId)
    }

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
