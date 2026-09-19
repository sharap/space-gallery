package ai.recommend.spacegallery.ui.gallery

import ai.recommend.spacegallery.data.repository.MediaRepository
import ai.recommend.spacegallery.domain.IndexingProgress
import ai.recommend.spacegallery.domain.MediaItem
import ai.recommend.spacegallery.work.IndexingScheduler
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class GalleryViewModel(
    repository: MediaRepository,
    scheduler: IndexingScheduler,
    favoritesOnly: Boolean = false,
) : ViewModel() {

    /** null — ещё загружается. */
    val items: StateFlow<List<MediaItem>?> =
        (if (favoritesOnly) repository.observeFavorites() else repository.observeTimeline())
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val indexing: StateFlow<IndexingProgress> = scheduler.observeProgress()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), IndexingProgress(false))
}
