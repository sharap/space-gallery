package ai.recommend.spacegallery.search.cleanup

import ai.recommend.spacegallery.data.repository.MediaRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

/**
 * Отчёт очистки, общий для обзора и экранов категорий. Пересчитывается, когда меняется лента
 * (удаление, скрытие, новые фото), пока экраны очистки открыты.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class CleanupRepository(finder: CleanupFinder, media: MediaRepository, appScope: CoroutineScope) {
    val report: StateFlow<CleanupReport?> = media.observeTimeline()
        .distinctUntilChanged()
        .debounce(DEBOUNCE_MS)
        .mapLatest { finder.find() }
        .stateIn(appScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private companion object {
        const val DEBOUNCE_MS = 300L
        /** Переход обзор -> категория не должен пересчитывать отчёт заново. */
        const val STOP_TIMEOUT_MS = 30_000L
    }
}
