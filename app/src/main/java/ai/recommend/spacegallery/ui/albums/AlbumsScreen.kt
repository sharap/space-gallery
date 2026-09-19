package ai.recommend.spacegallery.ui.albums

import ai.recommend.spacegallery.R
import ai.recommend.spacegallery.data.media.AlbumNames
import ai.recommend.spacegallery.data.settings.GRID_COLUMN_LEVELS
import ai.recommend.spacegallery.domain.Album
import ai.recommend.spacegallery.domain.MediaItem
import ai.recommend.spacegallery.ui.appViewModelFactory
import ai.recommend.spacegallery.ui.components.BackTopBar
import ai.recommend.spacegallery.ui.components.CenteredMessage
import ai.recommend.spacegallery.ui.components.MediaGrid
import ai.recommend.spacegallery.ui.components.SelectableTile
import ai.recommend.spacegallery.ui.components.SelectionState
import ai.recommend.spacegallery.ui.components.SelectionTopBar
import ai.recommend.spacegallery.ui.components.ThumbnailLabel
import ai.recommend.spacegallery.ui.components.mediaGridGestures
import ai.recommend.spacegallery.ui.components.pinchToChangeColumns
import ai.recommend.spacegallery.ui.components.rememberGridColumns
import ai.recommend.spacegallery.ui.components.rememberSelectionState
import ai.recommend.spacegallery.ui.components.rememberTrashConfirmation
import ai.recommend.spacegallery.ui.components.rememberWriteRequestLauncher
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumsScreen(
    onOpenAlbum: (Album) -> Unit,
    viewModel: AlbumsViewModel = viewModel(factory = appViewModelFactory { c, _ -> AlbumsViewModel(c.mediaRepository) }),
) {
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val selection = rememberSelectionState()
    AlbumEventEffect(viewModel.operations.events, onRenamed = { selection.clear() }, onDeleted = { selection.clear() })

    Scaffold(
        topBar = {
            if (selection.isActive) {
                AlbumSelectionTopBar(selection, albums.orEmpty(), viewModel)
            } else {
                TopAppBar(title = { Text(stringResource(R.string.tab_albums)) })
            }
        },
    ) { padding ->
        val list = albums
        when {
            list == null -> CenteredMessage(stringResource(R.string.loading), Modifier.padding(padding), loading = true)
            list.isEmpty() -> CenteredMessage(stringResource(R.string.empty_gallery), Modifier.padding(padding))
            else -> AlbumGrid(list, onOpenAlbum, selection, Modifier.padding(padding))
        }
    }
}

/**
 * Альбомы той же сеткой, что и фото: те же столбцы (общая настройка), тот же щипок
 * и тот же мультивыбор (долгое нажатие + протягивание).
 */
@Composable
private fun AlbumGrid(
    albums: List<Album>,
    onOpenAlbum: (Album) -> Unit,
    selection: SelectionState,
    modifier: Modifier = Modifier,
) {
    val (columns, setColumns) = rememberGridColumns()
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val edgePx = with(LocalDensity.current) { 72.dp.toPx() }
    val currentColumns by rememberUpdatedState(columns)
    val currentAlbums by rememberUpdatedState(albums)
    val currentOnOpen by rememberUpdatedState(onOpenAlbum)

    // Удалённые альбомы выпадают из выбора.
    LaunchedEffect(albums) { selection.retainOnly(albums.map { it.id }.toSet()) }

    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = gridState,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = modifier
            .pinchToChangeColumns(
                levels = GRID_COLUMN_LEVELS,
                currentColumns = { currentColumns },
                onColumnsChange = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    setColumns(it)
                },
            )
            .mediaGridGestures(
                gridState, selection, { currentAlbums.map { it.id } }, scope, edgePx,
                onTap = { key ->
                    val id = key as? Long
                    when {
                        id == null -> Unit
                        selection.isActive -> selection.toggle(id)
                        else -> currentAlbums.firstOrNull { it.id == id }?.let(currentOnOpen)
                    }
                },
                onLongPress = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
            ),
    ) {
        items(albums, key = { it.id }) { album ->
            val selected = album.id in selection
            AlbumTile(
                album,
                selectionMode = selection.isActive,
                selected = selected,
                modifier = Modifier
                    .animateItem()
                    // Жесты обрабатывает сетка целиком; здесь — семантика для доступности.
                    .semantics {
                        this.selected = selected
                        onClick {
                            if (selection.isActive) selection.toggle(album.id) else onOpenAlbum(album)
                            true
                        }
                        onLongClick {
                            selection.toggle(album.id)
                            true
                        }
                    },
            )
        }
    }
}

/** Обложка альбома с названием в левом нижнем углу — в стиле длительности видео. */
@Composable
private fun AlbumTile(album: Album, selectionMode: Boolean, selected: Boolean, modifier: Modifier = Modifier) {
    val description = album.name + ", " +
        pluralStringResource(R.plurals.items_count, album.itemCount, album.itemCount)
    SelectableTile(
        modifier.semantics(mergeDescendants = true) { contentDescription = description },
        selectionMode,
        selected,
    ) {
        AsyncImage(
            model = album.coverUri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        ThumbnailLabel(album.name, modifier = Modifier.align(Alignment.BottomStart).padding(4.dp))
    }
}

/** Контекстная панель выбора альбомов: переименовать (если выбран один), удалить, выбрать все. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlbumSelectionTopBar(selection: SelectionState, albums: List<Album>, viewModel: AlbumsViewModel) {
    BackHandler(enabled = selection.isActive) { selection.clear() }
    val scope = rememberCoroutineScope()
    val selected = albums.filter { it.id in selection }
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Album?>(null) }
    var confirmingDelete by remember { mutableStateOf(false) }
    val launchWrite = rememberWriteRequestLauncher(onGranted = { viewModel.onWriteGranted() })
    val confirmTrash = rememberTrashConfirmation(onConfirmed = { viewModel.onTrashConfirmed() })

    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        navigationIcon = {
            IconButton(onClick = selection::clear) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.action_clear_selection))
            }
        },
        title = { Text(pluralStringResource(R.plurals.selected_count, selected.size, selected.size)) },
        actions = {
            if (selected.size == 1 && viewModel.operations.isSupported && AlbumNames.canRename(selected.single().relativePath)) {
                IconButton(onClick = { renaming = selected.single() }) {
                    Icon(Icons.Outlined.DriveFileRenameOutline, contentDescription = stringResource(R.string.action_rename))
                }
            }
            IconButton(onClick = { confirmingDelete = true }) {
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
                            selection.set(albums.map { it.id }.toSet())
                        },
                    )
                }
            }
        },
    )

    renaming?.let { album ->
        RenameAlbumDialog(album, albums, onDismiss = { renaming = null }) { name ->
            renaming = null
            scope.launch { viewModel.operations.requestRename(album, name)?.let(launchWrite) }
        }
    }
    if (confirmingDelete) {
        DeleteAlbumsDialog(
            albumCount = selected.size,
            fileCount = selected.sumOf { it.itemCount },
            onDismiss = { confirmingDelete = false },
            onConfirm = {
                confirmingDelete = false
                scope.launch { confirmTrash(viewModel.operations.requestDelete(selected)) }
            },
        )
    }
}

@Composable
private fun RenameAlbumDialog(album: Album, albums: List<Album>, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    AlbumNameDialog(
        title = stringResource(R.string.album_rename_title),
        confirmLabel = stringResource(R.string.action_rename),
        initialName = album.name,
        isTaken = { name ->
            val target = AlbumNames.renamedPath(album.relativePath, name)
            albums.any { it.id != album.id && it.relativePath.equals(target, ignoreCase = true) }
        },
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/** Страница альбома: фото альбома + переименование и удаление самого альбома в меню. */
@Composable
fun AlbumDetailScreen(
    onBack: () -> Unit,
    /** Открыть фото; очередь просмотра — этот альбом (id актуален и после переименования). */
    onOpen: (item: MediaItem, albumId: Long) -> Unit,
    viewModel: AlbumDetailViewModel = viewModel(
        factory = appViewModelFactory { c, handle -> AlbumDetailViewModel(c.mediaRepository, handle) },
    ),
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val album by viewModel.album.collectAsStateWithLifecycle()
    val selection = rememberSelectionState()
    AlbumEventEffect(viewModel.operations.events, onDeleted = onBack)

    Scaffold(
        topBar = {
            if (selection.isActive) {
                SelectionTopBar(selection, items.orEmpty())
            } else {
                BackTopBar(album?.name ?: viewModel.initialName, onBack) {
                    album?.let { AlbumMenu(it, viewModel) }
                }
            }
        },
    ) { padding ->
        val list = items
        if (list == null) {
            CenteredMessage(stringResource(R.string.loading), Modifier.padding(padding), loading = true)
        } else {
            MediaGrid(
                list,
                onClick = { onOpen(it, viewModel.currentAlbumId) },
                groupByDate = true,
                selection = selection,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

/** Меню страницы альбома: «Переименовать» и «Удалить альбом». */
@Composable
private fun AlbumMenu(album: Album, viewModel: AlbumDetailViewModel) {
    val scope = rememberCoroutineScope()
    val albums by viewModel.albumsForValidation.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    val launchWrite = rememberWriteRequestLauncher(onGranted = { viewModel.onWriteGranted() })
    val confirmTrash = rememberTrashConfirmation(onConfirmed = { viewModel.onTrashConfirmed() })

    Box {
        IconButton(onClick = { menuOpen = true }) {
            Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.action_more))
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            if (viewModel.operations.isSupported) {
                val canRename = AlbumNames.canRename(album.relativePath)
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(stringResource(R.string.action_rename))
                            if (!canRename) {
                                Text(
                                    stringResource(R.string.album_rename_not_allowed),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    },
                    leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, contentDescription = null) },
                    enabled = canRename,
                    onClick = {
                        menuOpen = false
                        renaming = true
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.album_delete)) },
                leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    confirmingDelete = true
                },
            )
        }
    }

    if (renaming) {
        RenameAlbumDialog(album, albums, onDismiss = { renaming = false }) { name ->
            renaming = false
            scope.launch { viewModel.operations.requestRename(album, name)?.let(launchWrite) }
        }
    }
    if (confirmingDelete) {
        DeleteAlbumsDialog(
            albumCount = 1,
            fileCount = album.itemCount,
            onDismiss = { confirmingDelete = false },
            onConfirm = {
                confirmingDelete = false
                scope.launch { confirmTrash(viewModel.operations.requestDelete(listOf(album))) }
            },
        )
    }
}
