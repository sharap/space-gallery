package ai.recommend.spacegallery.ui.gallery

import ai.recommend.spacegallery.data.repository.MediaRepository
import ai.recommend.spacegallery.domain.IndexingProgress
import ai.recommend.spacegallery.domain.MediaItem
import ai.recommend.spacegallery.work.IndexingScheduler
import ai.recommend.spacegallery.data.settings.SettingsRepository
import ai.recommend.spacegallery.ml.onnx.ModelCatalog
import ai.recommend.spacegallery.work.ModelDownloadState
import ai.recommend.spacegallery.work.ModelDownloads
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class GalleryViewModel(
    repository: MediaRepository,
    scheduler: IndexingScheduler,
    favoritesOnly: Boolean = false,
    downloads: ModelDownloads? = null,
    catalog: ModelCatalog? = null,
    settings: SettingsRepository? = null,
) : ViewModel() {

    /**
     * Моделей нет и они не качаются — показать плашку «Скачать» (в релизе до первой загрузки).
     *
     * Считаем недостающими только те группы, которые пользователь согласился скачать: от
     * корейского текста и точных лиц можно отказаться, и тогда плашка «AI-функции не
     * установлены» не должна висеть вечно, хотя всё нужное на месте.
     */
    val modelsNeeded: StateFlow<Boolean> =
        (if (downloads == null || catalog == null || settings == null || !downloads.isConfigured) flowOf(false)
        else combine(downloads.observe(), settings.settings) { state, current ->
            (state is ModelDownloadState.Idle || state is ModelDownloadState.Failed) &&
                catalog.missing(current.modelGroups).isNotEmpty()
        }).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** null — ещё загружается. */
    val items: StateFlow<List<MediaItem>?> =
        (if (favoritesOnly) repository.observeFavorites() else repository.observeTimeline())
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val indexing: StateFlow<IndexingProgress> = scheduler.observeProgress()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), IndexingProgress(false))
}
