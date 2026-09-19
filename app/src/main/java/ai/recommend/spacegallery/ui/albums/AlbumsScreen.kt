package ai.recommend.spacegallery.ui.albums

import ai.recommend.spacegallery.R
import ai.recommend.spacegallery.domain.Album
import ai.recommend.spacegallery.domain.MediaItem
import ai.recommend.spacegallery.ui.appViewModelFactory
import ai.recommend.spacegallery.ui.components.BackTopBar
import ai.recommend.spacegallery.ui.components.CenteredMessage
import ai.recommend.spacegallery.ui.components.MediaGrid
import ai.recommend.spacegallery.ui.components.SelectionTopBar
import ai.recommend.spacegallery.ui.components.rememberSelectionState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumsScreen(
    onOpenAlbum: (Album) -> Unit,
    viewModel: AlbumsViewModel = viewModel(factory = appViewModelFactory { c, _ -> AlbumsViewModel(c.mediaRepository) }),
) {
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.tab_albums)) }) }) { padding ->
        val list = albums
        when {
            list == null -> CenteredMessage(stringResource(R.string.loading), Modifier.padding(padding), loading = true)
            list.isEmpty() -> CenteredMessage(stringResource(R.string.empty_gallery), Modifier.padding(padding))
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(padding),
            ) {
                items(list, key = { it.id }) { album -> AlbumCard(album, onClick = { onOpenAlbum(album) }) }
            }
        }
    }
}

@Composable
private fun AlbumCard(album: Album, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        AsyncImage(
            model = album.coverUri,
            contentDescription = album.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)),
        )
        Text(
            album.name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            pluralStringResource(R.plurals.items_count, album.itemCount, album.itemCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun AlbumDetailScreen(
    title: String,
    onBack: () -> Unit,
    onOpen: (MediaItem) -> Unit,
    viewModel: AlbumDetailViewModel = viewModel(
        factory = appViewModelFactory { c, handle -> AlbumDetailViewModel(c.mediaRepository, handle) },
    ),
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val selection = rememberSelectionState()
    Scaffold(
        topBar = {
            if (selection.isActive) SelectionTopBar(selection, items.orEmpty()) else BackTopBar(title, onBack)
        },
    ) { padding ->
        val list = items
        if (list == null) {
            CenteredMessage(stringResource(R.string.loading), Modifier.padding(padding), loading = true)
        } else {
            MediaGrid(
                list,
                onClick = onOpen,
                groupByDate = true,
                selection = selection,
                modifier = Modifier.padding(padding),
            )
        }
    }
}
