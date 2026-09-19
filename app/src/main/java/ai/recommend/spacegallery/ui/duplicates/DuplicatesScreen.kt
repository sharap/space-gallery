package ai.recommend.spacegallery.ui.duplicates

import ai.recommend.spacegallery.R
import ai.recommend.spacegallery.domain.DuplicateGroup
import ai.recommend.spacegallery.domain.MediaItem
import ai.recommend.spacegallery.ui.appViewModelFactory
import ai.recommend.spacegallery.ui.components.BackTopBar
import ai.recommend.spacegallery.ui.components.CenteredMessage
import ai.recommend.spacegallery.ui.components.MediaThumbnail
import ai.recommend.spacegallery.ui.components.rememberTrashConfirmation
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

@Composable
fun DuplicatesScreen(
    onBack: () -> Unit,
    onOpen: (MediaItem) -> Unit,
    viewModel: DuplicatesViewModel = viewModel(
        factory = appViewModelFactory { c, _ -> DuplicatesViewModel(c.duplicateFinder, c.mediaRepository) },
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val confirmTrash = rememberTrashConfirmation(onConfirmed = { viewModel.onTrashConfirmed() })
    val trash = { groups: List<DuplicateGroup> -> scope.launch { confirmTrash(viewModel.trashExtras(groups)) } }

    Scaffold(
        topBar = {
            BackTopBar(stringResource(R.string.duplicates_title), onBack) {
                val groups = (state as? DuplicatesUiState.Loaded)?.groups.orEmpty()
                if (groups.isNotEmpty()) {
                    TextButton(onClick = { trash(groups) }) { Text(stringResource(R.string.duplicates_clean_all)) }
                }
            }
        },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (val s = state) {
            DuplicatesUiState.Scanning -> CenteredMessage(stringResource(R.string.duplicates_scanning), modifier, loading = true)
            is DuplicatesUiState.Loaded ->
                if (s.groups.isEmpty()) {
                    CenteredMessage(stringResource(R.string.duplicates_empty), modifier)
                } else {
                    LazyColumn(modifier, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(s.groups, key = { it.suggestedKeep.id }) { group ->
                            DuplicateGroupCard(group, onOpen = onOpen, onClean = { trash(listOf(group)) })
                        }
                    }
                }
        }
    }
}

@Composable
private fun DuplicateGroupCard(group: DuplicateGroup, onOpen: (MediaItem) -> Unit, onClean: () -> Unit) {
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    pluralStringResource(R.plurals.items_count, group.items.size, group.items.size),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onClean) { Text(stringResource(R.string.duplicates_keep_best)) }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(group.items, key = { it.id }) { item ->
                    val isKeep = item.id == group.suggestedKeep.id
                    MediaThumbnail(
                        item = item,
                        badge = if (isKeep) stringResource(R.string.duplicates_keep_badge) else null,
                        modifier = Modifier
                            .size(96.dp)
                            .then(if (isKeep) Modifier.border(2.dp, MaterialTheme.colorScheme.primary) else Modifier)
                            .clickable { onOpen(item) },
                    )
                }
            }
        }
    }
}
