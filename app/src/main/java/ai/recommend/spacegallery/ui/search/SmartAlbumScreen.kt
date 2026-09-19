package ai.recommend.spacegallery.ui.search

import ai.recommend.spacegallery.R
import ai.recommend.spacegallery.domain.MediaItem
import ai.recommend.spacegallery.search.smart.SmartAlbumRepository
import ai.recommend.spacegallery.ui.appViewModelFactory
import ai.recommend.spacegallery.ui.components.BackTopBar
import ai.recommend.spacegallery.ui.components.CenteredMessage
import ai.recommend.spacegallery.ui.components.MediaGrid
import ai.recommend.spacegallery.ui.components.SelectionTopBar
import ai.recommend.spacegallery.ui.components.rememberSelectionState
import ai.recommend.spacegallery.ui.navigation.SmartAlbumRoute
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.toRoute
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class SmartAlbumViewModel(repository: SmartAlbumRepository, handle: SavedStateHandle) : ViewModel() {
    private val route = handle.toRoute<SmartAlbumRoute>()
    val albumId: Long = route.albumId

    val items: StateFlow<List<MediaItem>?> = repository.observeItems(albumId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Название может смениться после пересчёта; до загрузки — из маршрута. */
    val name: StateFlow<String?> = repository.observeName(albumId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), route.name)
}

/** Умный альбом: фото группы по датам, мультивыбор, очередь просмотра — эта группа. */
@Composable
fun SmartAlbumScreen(
    onBack: () -> Unit,
    onOpen: (item: MediaItem, albumId: Long) -> Unit,
    viewModel: SmartAlbumViewModel = viewModel(
        factory = appViewModelFactory { c, handle -> SmartAlbumViewModel(c.smartAlbums, handle) },
    ),
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val name by viewModel.name.collectAsStateWithLifecycle()
    val selection = rememberSelectionState()
    Scaffold(
        topBar = {
            if (selection.isActive) {
                SelectionTopBar(selection, items.orEmpty())
            } else {
                BackTopBar(name.orEmpty(), onBack)
            }
        },
    ) { padding ->
        val list = items
        when {
            list == null -> CenteredMessage(stringResource(R.string.loading), Modifier.padding(padding), loading = true)
            // Альбом исчез после пересчёта или все файлы скрыты/удалены.
            list.isEmpty() -> CenteredMessage(stringResource(R.string.smart_album_gone), Modifier.padding(padding))
            else -> MediaGrid(
                list,
                onClick = { onOpen(it, viewModel.albumId) },
                groupByDate = true,
                selection = selection,
                modifier = Modifier.padding(padding),
            )
        }
    }
}
