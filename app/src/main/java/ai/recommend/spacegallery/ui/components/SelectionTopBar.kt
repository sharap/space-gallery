package ai.recommend.spacegallery.ui.components

import ai.recommend.spacegallery.R
import ai.recommend.spacegallery.domain.MediaItem
import ai.recommend.spacegallery.domain.MediaType
import ai.recommend.spacegallery.ui.albums.AlbumEventEffect
import ai.recommend.spacegallery.ui.albums.AlbumPickerSheet
import ai.recommend.spacegallery.ui.appViewModelFactory
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

/**
 * Контекстная панель мультивыбора: заменяет обычную верхнюю панель, пока что-то выбрано.
 * «Назад» снимает выделение.
 *
 * @param items все элементы экрана (для «Выбрать все» и сопоставления id -> MediaItem).
 * @param hiddenMode экран «Скрытое»: вместо «Скрыть» — «Вернуть в ленту».
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionTopBar(
    selection: SelectionState,
    items: List<MediaItem>,
    hiddenMode: Boolean = false,
    actions: MediaActionsViewModel = viewModel(factory = appViewModelFactory { c, _ -> MediaActionsViewModel(c.mediaRepository) }),
) {
    BackHandler(enabled = selection.isActive) { selection.clear() }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val selected = items.filter { it.id in selection }
    val allFavorite = selected.isNotEmpty() && selected.all { it.isFavorite }
    var menuOpen by remember { mutableStateOf(false) }
    var pickingAlbum by remember { mutableStateOf(false) }
    val albums by actions.albums.collectAsStateWithLifecycle()
    val launchWrite = rememberWriteRequestLauncher(onGranted = { actions.onWriteGranted() })
    AlbumEventEffect(actions.albumOperations.events, onMoved = { selection.clear() })
    val confirmTrash = rememberTrashConfirmation {
        actions.onTrashConfirmed()
        selection.clear()
    }

    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        navigationIcon = {
            IconButton(onClick = selection::clear) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.action_clear_selection))
            }
        },
        title = { Text(pluralStringResource(R.plurals.selected_count, selected.size, selected.size)) },
        actions = {
            IconButton(onClick = { share(context, selected) }) {
                Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.action_share))
            }
            IconButton(onClick = { actions.setFavorite(selected, favorite = !allFavorite) }) {
                Icon(
                    if (allFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = stringResource(R.string.action_favorite),
                )
            }
            IconButton(onClick = { scope.launch { confirmTrash(actions.trash(selected)) } }) {
                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.action_delete))
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.action_more))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_select_all)) },
                        onClick = {
                            menuOpen = false
                            selection.set(items.map { it.id }.toSet())
                        },
                    )
                    if (actions.albumOperations.isSupported) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_send_to_album)) },
                            onClick = {
                                menuOpen = false
                                pickingAlbum = true
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(if (hiddenMode) R.string.action_unhide else R.string.action_hide)) },
                        onClick = {
                            menuOpen = false
                            actions.setHidden(selected, hidden = !hiddenMode)
                            selection.clear()
                        },
                    )
                }
            }
        },
    )

    if (pickingAlbum) {
        AlbumPickerSheet(
            albums = albums,
            types = selected.mapTo(HashSet()) { it.type },
            onDismiss = { pickingAlbum = false },
            onPick = { target ->
                pickingAlbum = false
                scope.launch { actions.albumOperations.requestMove(selected, target)?.let(launchWrite) }
            },
        )
    }
}

private fun share(context: Context, items: List<MediaItem>) {
    if (items.isEmpty()) return
    val uris = ArrayList<Uri>(items.map { it.uri })
    val mime = when {
        items.all { it.type == MediaType.IMAGE } -> "image/*"
        items.all { it.type == MediaType.VIDEO } -> "video/*"
        else -> "*/*"
    }
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
    }
        .setType(mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    // ClipData нужен, чтобы право чтения выдалось на все URI, а не только на первый.
    intent.clipData = ClipData.newRawUri(null, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
    context.startActivity(Intent.createChooser(intent, null))
}
