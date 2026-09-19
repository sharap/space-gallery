package ai.recommend.spacegallery.ui.albums

import ai.recommend.spacegallery.data.repository.MediaRepository
import ai.recommend.spacegallery.domain.Album
import ai.recommend.spacegallery.domain.MediaItem
import ai.recommend.spacegallery.ui.navigation.AlbumRoute
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AlbumsViewModel(repository: MediaRepository) : ViewModel() {

    val albums: StateFlow<List<Album>?> = repository.observeAlbums()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val operations = AlbumOperations(repository)

    fun onWriteGranted() = viewModelScope.launch { operations.onWriteGranted() }
    fun onTrashConfirmed() = viewModelScope.launch { operations.onTrashConfirmed() }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AlbumDetailViewModel(repository: MediaRepository, handle: SavedStateHandle) : ViewModel() {
    private val route = handle.toRoute<AlbumRoute>()

    /** id альбома меняется при переименовании (BUCKET_ID зависит от пути папки). */
    private val albumId = MutableStateFlow(route.albumId)
    val currentAlbumId: Long get() = albumId.value

    val items: StateFlow<List<MediaItem>?> = albumId
        .flatMapLatest { repository.observeTimeline(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Актуальные данные альбома (название после переименования, путь); null — альбом пуст/удалён. */
    val album: StateFlow<Album?> = combine(albumId, repository.observeAlbums()) { id, albums ->
        albums.firstOrNull { it.id == id }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val initialName: String = route.name

    /** Все альбомы — для проверки, не занято ли новое имя. */
    val albumsForValidation: StateFlow<List<Album>> = repository.observeAlbums()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val operations = AlbumOperations(repository)

    fun onWriteGranted() = viewModelScope.launch {
        val event = operations.onWriteGranted()
        if (event is AlbumEvent.Renamed && event.newAlbumId != null) albumId.value = event.newAlbumId
    }

    fun onTrashConfirmed() = viewModelScope.launch { operations.onTrashConfirmed() }
}
