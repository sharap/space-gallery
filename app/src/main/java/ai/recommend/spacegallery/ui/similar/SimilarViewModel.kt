package ai.recommend.spacegallery.ui.similar

import ai.recommend.spacegallery.data.repository.MediaRepository
import ai.recommend.spacegallery.domain.ScoredMedia
import ai.recommend.spacegallery.ui.components.observeScored
import ai.recommend.spacegallery.search.SimilarMediaFinder
import ai.recommend.spacegallery.ui.navigation.SimilarRoute
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface SimilarUiState {
    data object Loading : SimilarUiState
    data object NotIndexed : SimilarUiState
    data class Loaded(val items: List<ScoredMedia>) : SimilarUiState
}

class SimilarViewModel(
    finder: SimilarMediaFinder,
    repository: MediaRepository,
    handle: SavedStateHandle,
) : ViewModel() {
    private val mediaId = handle.toRoute<SimilarRoute>().mediaId

    private val _state = MutableStateFlow<SimilarUiState>(SimilarUiState.Loading)
    val state: StateFlow<SimilarUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val similar = finder.findSimilar(mediaId)
            if (similar == null) {
                _state.value = SimilarUiState.NotIndexed
                return@launch
            }
            repository.observeScored(similar).collect { _state.value = SimilarUiState.Loaded(it) }
        }
    }
}
