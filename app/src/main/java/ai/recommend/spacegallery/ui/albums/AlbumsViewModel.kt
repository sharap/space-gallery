package ai.recommend.spacegallery.ui.albums

import ai.recommend.spacegallery.data.repository.MediaRepository
import ai.recommend.spacegallery.domain.Album
import ai.recommend.spacegallery.domain.MediaItem
import ai.recommend.spacegallery.ui.navigation.AlbumRoute
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class AlbumsViewModel(repository: MediaRepository) : ViewModel() {
    val albums: StateFlow<List<Album>?> = repository.observeAlbums()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

class AlbumDetailViewModel(repository: MediaRepository, handle: SavedStateHandle) : ViewModel() {
    private val route = handle.toRoute<AlbumRoute>()

    val items: StateFlow<List<MediaItem>?> = repository.observeTimeline(route.albumId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
