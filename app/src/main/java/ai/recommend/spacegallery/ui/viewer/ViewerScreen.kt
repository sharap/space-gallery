package ai.recommend.spacegallery.ui.viewer

import ai.recommend.spacegallery.R
import ai.recommend.spacegallery.domain.MediaItem
import ai.recommend.spacegallery.domain.MediaType
import ai.recommend.spacegallery.ui.appViewModelFactory
import ai.recommend.spacegallery.ui.components.CenteredMessage
import ai.recommend.spacegallery.ui.components.rememberTrashConfirmation
import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

@Composable
fun ViewerScreen(
    onBack: () -> Unit,
    onShowSimilar: (mediaId: Long) -> Unit,
    /** Открыть страницу человека (все фото с ним) — из чипа «кто на фото». */
    onOpenPerson: (personId: Long) -> Unit,
    viewModel: ViewerViewModel = viewModel(
        factory = appViewModelFactory { c, handle -> ViewerViewModel(c.mediaRepository, c.smartAlbums, c.people, c.textRepository, handle) },
    ),
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val list = items
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when {
            list == null -> CenteredMessage(stringResource(R.string.loading), loading = true)
            list.isEmpty() -> CenteredMessage(stringResource(R.string.item_not_found))
            else -> ViewerPager(list, viewModel, onBack, onShowSimilar, onOpenPerson)
        }
    }
}

@Composable
private fun ViewerPager(
    items: List<MediaItem>,
    viewModel: ViewerViewModel,
    onBack: () -> Unit,
    onShowSimilar: (Long) -> Unit,
    onOpenPerson: (Long) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val initialPage = remember { items.indexOfFirst { it.id == viewModel.initialMediaId }.coerceAtLeast(0) }
    val pagerState = rememberPagerState(initialPage = initialPage) { items.size }
    var chromeVisible by rememberSaveable { mutableStateOf(true) }
    val toggleChrome = { chromeVisible = !chromeVisible }
    // settledPage — страница, на которой пейджер остановился (не меняется во время свайпа).
    val current = items.getOrNull(pagerState.settledPage)

    val faceBoxes by viewModel.faceBoxes.collectAsStateWithLifecycle()
    val peopleOnCurrent by viewModel.peopleOnCurrent.collectAsStateWithLifecycle()
    LaunchedEffect(current?.id) { viewModel.onCurrentMedia(current?.id) }
    val tagsOnCurrent by viewModel.tagsOnCurrent.collectAsStateWithLifecycle()
    // Рамки всех найденных лиц на текущем кадре: узнанные — с подписью, ничейные — тоньше
    // и без неё. Без ничейных на групповом снимке кажется, что человека вовсе не нашли.
    // Со страницы человека показываем только его лицо.
    val currentBoxes = remember(tagsOnCurrent) { tagsOnCurrent.faces.map { it.box } }
    val player = rememberViewerPlayer()
    LaunchedEffect(current?.id) { player.bindTo(current) }

    ImmersiveMode(enabled = !chromeVisible)

    var tagging by remember { mutableStateOf(false) }
    var showingText by remember { mutableStateOf(false) }
    val photoText by viewModel.textOnCurrent.collectAsStateWithLifecycle()
    var pendingTrash by remember { mutableStateOf<MediaItem?>(null) }
    val confirmTrash = rememberTrashConfirmation {
        pendingTrash?.let(viewModel::onTrashConfirmed)
        pendingTrash = null
    }

    HorizontalPager(
        state = pagerState,
        key = { items[it].id },
        beyondViewportPageCount = 1,
        modifier = Modifier.fillMaxSize(),
    ) { page ->
        val item = items[page]
        val isCurrent = page == pagerState.settledPage
        when (item.type) {
            MediaType.IMAGE -> PhotoPage(
                item,
                isCurrentPage = isCurrent,
                onTap = toggleChrome,
                faceBoxes = when {
                    viewModel.highlightsOnlyPerson -> faceBoxes[item.id].orEmpty()
                    isCurrent -> currentBoxes
                    else -> emptyList()
                },
                showFaceBoxes = chromeVisible,
            )
            MediaType.VIDEO -> VideoPage(item, player = player.takeIf { isCurrent }, onTap = toggleChrome)
        }
    }

    AnimatedVisibility(chromeVisible, enter = fadeIn(), exit = fadeOut()) {
        Box(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.4f))
                    .statusBarsPadding()
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), tint = Color.White)
                }
                Text(
                    current?.displayName.orEmpty(),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (current != null) {
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                    if (current.type == MediaType.VIDEO) VideoControls(player)
                    if (current.type == MediaType.IMAGE && peopleOnCurrent.isNotEmpty()) {
                        PeopleOnPhotoRow(peopleOnCurrent.map { it.person }, onOpenPerson)
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.4f))
                            .navigationBarsPadding()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        ViewerAction(
                            if (current.isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                            stringResource(R.string.action_favorite),
                        ) { viewModel.toggleFavorite(current) }
                        ViewerAction(Icons.Outlined.Share, stringResource(R.string.action_share)) { share(context, current) }
                        ViewerAction(Icons.Outlined.AutoAwesome, stringResource(R.string.action_similar)) {
                            onShowSimilar(current.id)
                        }
                        if (current.type == MediaType.IMAGE) {
                            ViewerAction(Icons.Outlined.PersonAdd, stringResource(R.string.photo_tags_title)) { tagging = true }
                            if (!photoText.isEmpty) {
                                ViewerAction(Icons.Outlined.TextFields, stringResource(R.string.photo_text_title)) { showingText = true }
                            }
                        }
                        ViewerAction(
                            if (current.isHiddenByUser) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                            stringResource(if (current.isHiddenByUser) R.string.action_unhide else R.string.action_hide),
                        ) { viewModel.toggleHidden(current) }
                        ViewerAction(Icons.Outlined.Delete, stringResource(R.string.action_delete)) {
                            pendingTrash = current
                            scope.launch { confirmTrash(viewModel.trash(current)) }
                        }
                    }
                }
            }
        }
    }

    if (showingText) {
        PhotoTextSheet(photoText, onDismiss = { showingText = false })
    }

    if (tagging && current?.type == MediaType.IMAGE) {
        PhotoTagsFor(current, viewModel, onDismiss = { tagging = false })
    }
}

/** Шторка ручной отметки людей на текущем фото. */
@Composable
private fun PhotoTagsFor(item: MediaItem, viewModel: ViewerViewModel, onDismiss: () -> Unit) {
    val tags by viewModel.tagsOnCurrent.collectAsStateWithLifecycle()
    val people by viewModel.allPeople.collectAsStateWithLifecycle()
    PhotoTagsSheet(
        tags = tags,
        people = people,
        onAssignFace = viewModel::assignFace,
        onNotAFace = viewModel::markNotAFace,
        onTag = { viewModel.tagPerson(item.id, it) },
        onUntag = { viewModel.untagPerson(item.id, it) },
        onDismiss = onDismiss,
    )
}

/** Скрывает статус-бар и навигацию, пока панели просмотрщика спрятаны; возвращает их при выходе. */
@Composable
private fun ImmersiveMode(enabled: Boolean) {
    val view = LocalView.current
    val window = (view.context as? Activity)?.window ?: return
    val controller = remember(window, view) { WindowCompat.getInsetsController(window, view) }
    DisposableEffect(enabled) {
        if (enabled) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

@Composable
private fun ViewerAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(icon, contentDescription = label, tint = Color.White) }
}

private fun share(context: Context, item: MediaItem) {
    val intent = Intent(Intent.ACTION_SEND)
        .setType(item.mimeType)
        .putExtra(Intent.EXTRA_STREAM, item.uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, null))
}
