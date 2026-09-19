package ai.recommend.spacegallery.ui.hidden

import ai.recommend.spacegallery.data.repository.MediaRepository
import ai.recommend.spacegallery.domain.MediaItem
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/** Скрытое вручную + автоматически помеченное AI как деликатное. */
class HiddenViewModel(repository: MediaRepository) : ViewModel() {

    val items: StateFlow<List<MediaItem>?> = repository.observeHidden()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
