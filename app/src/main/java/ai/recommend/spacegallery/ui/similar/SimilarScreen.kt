package ai.recommend.spacegallery.ui.similar

import ai.recommend.spacegallery.R
import ai.recommend.spacegallery.domain.MediaItem
import ai.recommend.spacegallery.ui.appViewModelFactory
import ai.recommend.spacegallery.ui.components.BackTopBar
import ai.recommend.spacegallery.ui.components.CenteredMessage
import ai.recommend.spacegallery.ui.components.MediaGrid
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.roundToInt

@Composable
fun SimilarScreen(
    onBack: () -> Unit,
    onOpen: (MediaItem) -> Unit,
    viewModel: SimilarViewModel = viewModel(
        factory = appViewModelFactory { c, handle -> SimilarViewModel(c.similarFinder, handle) },
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(topBar = { BackTopBar(stringResource(R.string.similar_title), onBack) }) { padding ->
        val modifier = Modifier.padding(padding)
        when (val s = state) {
            SimilarUiState.Loading -> CenteredMessage(stringResource(R.string.loading), modifier, loading = true)
            SimilarUiState.NotIndexed -> CenteredMessage(stringResource(R.string.similar_not_indexed), modifier)
            is SimilarUiState.Loaded ->
                if (s.items.isEmpty()) {
                    CenteredMessage(stringResource(R.string.similar_empty), modifier)
                } else {
                    val scores = s.items.associate { it.item.id to it.score }
                    MediaGrid(
                        s.items.map { it.item },
                        onClick = onOpen,
                        modifier = modifier,
                        badge = { item -> scores[item.id]?.let { "${(it * 100).roundToInt()}%" } },
                    )
                }
        }
    }
}
