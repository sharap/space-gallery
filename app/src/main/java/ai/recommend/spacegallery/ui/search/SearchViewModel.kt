package ai.recommend.spacegallery.ui.search

import ai.recommend.spacegallery.data.repository.MediaRepository
import ai.recommend.spacegallery.domain.ScoredMedia
import ai.recommend.spacegallery.ui.components.observeScored
import ai.recommend.spacegallery.search.SearchOutcome
import ai.recommend.spacegallery.search.SemanticSearchEngine
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

sealed interface SearchUiState {
    data object Idle : SearchUiState
    data object Searching : SearchUiState
    data class Results(val items: List<ScoredMedia>) : SearchUiState
    data object ModelUnavailable : SearchUiState
    data object IndexNotReady : SearchUiState
}

@OptIn(FlowPreview::class)
class SearchViewModel(
    private val engine: SemanticSearchEngine,
    private val repository: MediaRepository,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _state = MutableStateFlow<SearchUiState>(SearchUiState.Idle)
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _query
                .map { it.trim() }
                .debounce(400)
                .distinctUntilChanged()
                .collectLatest { q ->
                    if (q.length < 2) {
                        _state.value = SearchUiState.Idle
                        return@collectLatest
                    }
                    _state.value = SearchUiState.Searching
                    when (val outcome = engine.search(q)) {
                        // Живой список до следующего запроса (collectLatest отменит подписку).
                        is SearchOutcome.Results -> repository.observeScored(outcome.items).collect {
                            _state.value = SearchUiState.Results(it)
                        }
                        SearchOutcome.ModelUnavailable -> _state.value = SearchUiState.ModelUnavailable
                        SearchOutcome.IndexNotReady -> _state.value = SearchUiState.IndexNotReady
                    }
                }
        }
    }

    fun onQueryChange(value: String) {
        _query.value = value
    }
}
