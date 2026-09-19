package ai.recommend.spacegallery.ui.cleanup

import ai.recommend.spacegallery.data.media.DeleteResult
import ai.recommend.spacegallery.data.repository.MediaRepository
import ai.recommend.spacegallery.domain.DuplicateGroup
import ai.recommend.spacegallery.domain.MediaItem
import ai.recommend.spacegallery.search.cleanup.CleanupReport
import ai.recommend.spacegallery.search.cleanup.CleanupRepository
import ai.recommend.spacegallery.ui.navigation.CleanupCategory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Категория очистки в отчёте: группы «оставить лучшее» или простой список. */
sealed interface CleanupContent {
    val deletable: List<MediaItem>

    class Groups(val groups: List<DuplicateGroup>) : CleanupContent {
        override val deletable = groups.flatMap { g -> g.items.filter { it.id != g.suggestedKeep.id } }
    }

    class Items(val items: List<MediaItem>) : CleanupContent {
        override val deletable = items
    }
}

fun CleanupReport.content(category: CleanupCategory): CleanupContent = when (category) {
    CleanupCategory.COPIES -> CleanupContent.Groups(copies)
    CleanupCategory.SERIES -> CleanupContent.Groups(series)
    CleanupCategory.POOR -> CleanupContent.Items(poor)
    CleanupCategory.SCREENSHOTS -> CleanupContent.Items(screenshots)
    CleanupCategory.LARGE_VIDEOS -> CleanupContent.Items(largeVideos)
}

class CleanupViewModel(cleanup: CleanupRepository, private val media: MediaRepository) : ViewModel() {
    val report: StateFlow<CleanupReport?> = cleanup.report

    /** Отправленные в корзину и ожидающие подтверждения системой. */
    private var pending: List<MediaItem> = emptyList()

    suspend fun trash(items: List<MediaItem>): DeleteResult {
        pending = items
        return media.moveToTrash(items)
    }

    fun onTrashConfirmed() = viewModelScope.launch {
        media.onTrashed(pending)
        pending = emptyList()
    }
}
