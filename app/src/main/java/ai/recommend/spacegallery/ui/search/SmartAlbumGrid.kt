package ai.recommend.spacegallery.ui.search

import ai.recommend.spacegallery.R
import ai.recommend.spacegallery.data.settings.GRID_COLUMN_LEVELS
import ai.recommend.spacegallery.data.settings.GridKind
import ai.recommend.spacegallery.search.smart.SmartAlbum
import ai.recommend.spacegallery.ui.components.AlbumCoverTile
import ai.recommend.spacegallery.ui.components.pinchToChangeColumns
import ai.recommend.spacegallery.ui.components.rememberGridColumns
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * Умные альбомы (кластеры DBSCAN) на вкладке AI-поиска при пустом запросе.
 * Та же плитка, что у обычных альбомов, и тот же масштаб сетки альбомов (щипок).
 */
@Composable
fun SmartAlbumGrid(
    albums: List<SmartAlbum>,
    onOpen: (SmartAlbum) -> Unit,
    modifier: Modifier = Modifier,
    /** Блок над альбомами на всю ширину (ряд «Люди»). */
    header: @Composable () -> Unit = {},
) {
    val (columns, setColumns) = rememberGridColumns(GridKind.ALBUMS)
    val currentColumns by rememberUpdatedState(columns)
    val haptics = LocalHapticFeedback.current
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = modifier.pinchToChangeColumns(
            levels = GRID_COLUMN_LEVELS,
            currentColumns = { currentColumns },
            onColumnsChange = {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                setColumns(it)
            },
        ),
    ) {
        item(key = "people", span = { GridItemSpan(maxLineSpan) }) { header() }
        if (albums.isNotEmpty()) item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.smart_albums_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.smart_albums_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(albums, key = { it.id }) { album ->
            AlbumCoverTile(
                name = album.name,
                itemCount = album.itemCount,
                coverUri = album.coverUri,
                modifier = Modifier.animateItem().clickable { onOpen(album) },
            )
        }
    }
}
