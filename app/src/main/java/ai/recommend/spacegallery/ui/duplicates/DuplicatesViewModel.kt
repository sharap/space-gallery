package ai.recommend.spacegallery.ui.duplicates

import ai.recommend.spacegallery.data.media.DeleteResult
import ai.recommend.spacegallery.data.repository.MediaRepository
import ai.recommend.spacegallery.domain.DuplicateGroup
import ai.recommend.spacegallery.domain.MediaItem
import ai.recommend.spacegallery.search.DuplicateFinder
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface DuplicatesUiState {
    data object Scanning : DuplicatesUiState
    data class Loaded(val groups: List<DuplicateGroup>) : DuplicatesUiState
}

class DuplicatesViewModel(
    private val finder: DuplicateFinder,
    private val repository: MediaRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<DuplicatesUiState>(DuplicatesUiState.Scanning)
    val state: StateFlow<DuplicatesUiState> = _state.asStateFlow()

    /** Элементы, отправленные на удаление и ожидающие подтверждения системой. */
    private var pending: List<MediaItem> = emptyList()

    init {
        rescan()
    }

    fun rescan() = viewModelScope.launch {
        _state.value = DuplicatesUiState.Scanning
        _state.value = DuplicatesUiState.Loaded(finder.findGroups())
    }

    /** Удалить всё, кроме рекомендованной копии, в указанных группах. */
    suspend fun trashExtras(groups: List<DuplicateGroup>): DeleteResult {
        pending = groups.flatMap { g -> g.items.filter { it.id != g.suggestedKeep.id } }
        return repository.moveToTrash(pending)
    }

    fun onTrashConfirmed() = viewModelScope.launch {
        repository.onTrashed(pending)
        pending = emptyList()
        rescan()
    }
}
