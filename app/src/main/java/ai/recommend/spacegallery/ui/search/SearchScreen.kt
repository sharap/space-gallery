package ai.recommend.spacegallery.ui.search

import ai.recommend.spacegallery.R
import ai.recommend.spacegallery.domain.MediaItem
import ai.recommend.spacegallery.ui.appViewModelFactory
import ai.recommend.spacegallery.ui.components.CenteredMessage
import ai.recommend.spacegallery.ui.components.MediaGrid
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun SearchScreen(
    /** Открыть элемент; очередь просмотра — все результаты поиска. */
    onOpen: (item: MediaItem, queue: List<MediaItem>) -> Unit,
    viewModel: SearchViewModel = viewModel(factory = appViewModelFactory { c, _ -> SearchViewModel(c.semanticSearch) }),
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        OutlinedTextField(
            value = query,
            onValueChange = viewModel::onQueryChange,
            placeholder = { Text(stringResource(R.string.search_hint)) },
            leadingIcon = { Icon(Icons.Outlined.AutoAwesome, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { viewModel.onQueryChange("") }) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.action_clear))
                    }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.fillMaxWidth().padding(12.dp),
        )
        when (val s = state) {
            SearchUiState.Idle -> CenteredMessage(stringResource(R.string.search_idle))
            SearchUiState.Searching -> CenteredMessage(stringResource(R.string.search_in_progress), loading = true)
            SearchUiState.ModelUnavailable -> CenteredMessage(stringResource(R.string.search_model_missing))
            SearchUiState.IndexNotReady -> CenteredMessage(stringResource(R.string.search_index_not_ready))
            is SearchUiState.Results ->
                if (s.items.isEmpty()) {
                    CenteredMessage(stringResource(R.string.search_no_results))
                } else {
                    val results = s.items.map { it.item }
                    MediaGrid(results, onClick = { onOpen(it, results) })
                }
        }
    }
}
