package ai.recommend.spacegallery.ui.gallery

import ai.recommend.spacegallery.R
import ai.recommend.spacegallery.domain.IndexingProgress
import ai.recommend.spacegallery.domain.MediaItem
import ai.recommend.spacegallery.ui.appViewModelFactory
import ai.recommend.spacegallery.ui.components.BackTopBar
import ai.recommend.spacegallery.ui.components.CenteredMessage
import ai.recommend.spacegallery.ui.components.MediaGrid
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    onOpen: (MediaItem) -> Unit,
    viewModel: GalleryViewModel = viewModel(
        factory = appViewModelFactory { c, _ -> GalleryViewModel(c.mediaRepository, c.indexingScheduler) },
    ),
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val indexing by viewModel.indexing.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            Column {
                TopAppBar(title = { Text(stringResource(R.string.tab_photos)) })
                IndexingBanner(indexing)
            }
        },
    ) { padding ->
        val list = items
        when {
            list == null -> CenteredMessage(stringResource(R.string.loading), Modifier.padding(padding), loading = true)
            list.isEmpty() -> CenteredMessage(stringResource(R.string.empty_gallery), Modifier.padding(padding))
            else -> MediaGrid(list, onClick = onOpen, groupByDay = true, modifier = Modifier.padding(padding))
        }
    }
}

@Composable
fun FavoritesScreen(
    onBack: () -> Unit,
    onOpen: (MediaItem) -> Unit,
    viewModel: GalleryViewModel = viewModel(
        factory = appViewModelFactory { c, _ ->
            GalleryViewModel(c.mediaRepository, c.indexingScheduler, favoritesOnly = true)
        },
    ),
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    Scaffold(topBar = { BackTopBar(stringResource(R.string.favorites), onBack) }) { padding ->
        val list = items
        when {
            list == null -> CenteredMessage(stringResource(R.string.loading), Modifier.padding(padding), loading = true)
            list.isEmpty() -> CenteredMessage(stringResource(R.string.empty_favorites), Modifier.padding(padding))
            else -> MediaGrid(list, onClick = onOpen, modifier = Modifier.padding(padding))
        }
    }
}

@Composable
private fun IndexingBanner(progress: IndexingProgress) {
    if (!progress.isRunning) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            stringResource(R.string.indexing_progress, progress.processed, progress.total),
            style = MaterialTheme.typography.labelMedium,
        )
        if (progress.total > 0) {
            LinearProgressIndicator(
                progress = { progress.processed.toFloat() / progress.total },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
        }
    }
}
