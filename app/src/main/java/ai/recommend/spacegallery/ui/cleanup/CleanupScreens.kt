package ai.recommend.spacegallery.ui.cleanup

import ai.recommend.spacegallery.R
import ai.recommend.spacegallery.domain.DuplicateGroup
import ai.recommend.spacegallery.domain.MediaItem
import ai.recommend.spacegallery.ui.appViewModelFactory
import ai.recommend.spacegallery.ui.components.BackTopBar
import ai.recommend.spacegallery.ui.components.CenteredMessage
import ai.recommend.spacegallery.ui.components.MediaGrid
import ai.recommend.spacegallery.ui.components.MediaThumbnail
import ai.recommend.spacegallery.ui.components.SelectionTopBar
import ai.recommend.spacegallery.ui.components.rememberSelectionState
import ai.recommend.spacegallery.ui.components.rememberTrashConfirmation
import ai.recommend.spacegallery.ui.navigation.CleanupCategory
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BlurOn
import androidx.compose.material.icons.outlined.BurstMode
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Screenshot
import androidx.compose.material.icons.outlined.VideoFile
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

private fun cleanupViewModelFactory() = appViewModelFactory { c, _ -> CleanupViewModel(c.cleanup, c.mediaRepository) }

private val CleanupCategory.icon: ImageVector get() = when (this) {
    CleanupCategory.COPIES -> Icons.Outlined.ContentCopy
    CleanupCategory.SERIES -> Icons.Outlined.BurstMode
    CleanupCategory.POOR -> Icons.Outlined.BlurOn
    CleanupCategory.SCREENSHOTS -> Icons.Outlined.Screenshot
    CleanupCategory.LARGE_VIDEOS -> Icons.Outlined.VideoFile
}

private val CleanupCategory.titleRes: Int get() = when (this) {
    CleanupCategory.COPIES -> R.string.cleanup_copies
    CleanupCategory.SERIES -> R.string.cleanup_series
    CleanupCategory.POOR -> R.string.cleanup_poor
    CleanupCategory.SCREENSHOTS -> R.string.cleanup_screenshots
    CleanupCategory.LARGE_VIDEOS -> R.string.cleanup_large_videos
}

private val CleanupCategory.descRes: Int get() = when (this) {
    CleanupCategory.COPIES -> R.string.cleanup_copies_desc
    CleanupCategory.SERIES -> R.string.cleanup_series_desc
    CleanupCategory.POOR -> R.string.cleanup_poor_desc
    CleanupCategory.SCREENSHOTS -> R.string.cleanup_screenshots_desc
    CleanupCategory.LARGE_VIDEOS -> R.string.cleanup_large_videos_desc
}

@Composable
private fun sizeText(bytes: Long): String = Formatter.formatShortFileSize(LocalContext.current, bytes)

// ---------------------------------------------------------------- Обзор

/** Очистка: категории с числом кандидатов на удаление и объёмом, который можно освободить. */
@Composable
fun CleanupScreen(
    onBack: () -> Unit,
    onOpenCategory: (CleanupCategory) -> Unit,
    viewModel: CleanupViewModel = viewModel(factory = cleanupViewModelFactory()),
) {
    val report by viewModel.report.collectAsStateWithLifecycle()
    Scaffold(topBar = { BackTopBar(stringResource(R.string.cleanup_title), onBack) }) { padding ->
        val r = report
        if (r == null) {
            CenteredMessage(stringResource(R.string.cleanup_scanning), Modifier.padding(padding), loading = true)
            return@Scaffold
        }
        val contents = CleanupCategory.entries.associateWith { r.content(it) }
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                val total = contents.values.flatMap { it.deletable }.distinctBy { it.id }.sumOf { it.sizeBytes }
                Text(
                    stringResource(R.string.cleanup_total, sizeText(total)),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                )
            }
            items(CleanupCategory.entries) { category ->
                CategoryCard(category, contents.getValue(category)) { onOpenCategory(category) }
            }
        }
    }
}

@Composable
private fun CategoryCard(category: CleanupCategory, content: CleanupContent, onClick: () -> Unit) {
    val deletable = content.deletable
    Card(onClick = onClick, enabled = deletable.isNotEmpty()) {
        ListItem(
            leadingContent = { Icon(category.icon, contentDescription = null) },
            headlineContent = { Text(stringResource(category.titleRes)) },
            supportingContent = {
                Text(
                    if (deletable.isEmpty()) {
                        stringResource(R.string.cleanup_nothing)
                    } else {
                        stringResource(
                            R.string.cleanup_summary,
                            pluralStringResource(R.plurals.items_count, deletable.size, deletable.size),
                            sizeText(deletable.sumOf { it.sizeBytes }),
                        )
                    }
                )
            },
        )
        if (deletable.isNotEmpty()) {
            Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                deletable.take(4).forEach { MediaThumbnail(it, Modifier.size(64.dp)) }
            }
        }
    }
}

// ---------------------------------------------------------------- Категория

/** Экран категории: группы «оставить лучшее» или сетка с выбором. */
@Composable
fun CleanupCategoryScreen(
    category: CleanupCategory,
    onBack: () -> Unit,
    onOpen: (item: MediaItem, queue: List<MediaItem>) -> Unit,
    viewModel: CleanupViewModel = viewModel(factory = cleanupViewModelFactory()),
) {
    val report by viewModel.report.collectAsStateWithLifecycle()
    val title = stringResource(category.titleRes)
    val r = report
    if (r == null) {
        Scaffold(topBar = { BackTopBar(title, onBack) }) { padding ->
            CenteredMessage(stringResource(R.string.cleanup_scanning), Modifier.padding(padding), loading = true)
        }
        return
    }
    when (val content = r.content(category)) {
        is CleanupContent.Groups -> GroupsScreen(title, stringResource(category.descRes), content.groups, viewModel, onBack, onOpen)
        is CleanupContent.Items -> ItemsScreen(category, title, content.items, onBack, onOpen)
    }
}

/**
 * Группы: в каждой отмечены для удаления все, кроме лучшего кадра. Тап по фото — оставить или
 * удалить, долгое нажатие — открыть. Правки пользователя переживают пересчёт отчёта.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupsScreen(
    title: String,
    description: String,
    groups: List<DuplicateGroup>,
    viewModel: CleanupViewModel,
    onBack: () -> Unit,
    onOpen: (MediaItem, List<MediaItem>) -> Unit,
) {
    // Отклонения от предложения: оставить предложенное к удалению / удалить предложенное оставить.
    var keptByUser by rememberSaveable { mutableStateOf(emptyList<Long>()) }
    var deletedByUser by rememberSaveable { mutableStateOf(emptyList<Long>()) }
    val keepIds = remember(groups) { groups.mapTo(HashSet()) { it.suggestedKeep.id } }
    fun isMarked(item: MediaItem) = if (item.id in keepIds) item.id in deletedByUser else item.id !in keptByUser
    fun toggle(item: MediaItem) {
        if (item.id in keepIds) {
            deletedByUser = if (item.id in deletedByUser) deletedByUser - item.id else deletedByUser + item.id
        } else {
            keptByUser = if (item.id in keptByUser) keptByUser - item.id else keptByUser + item.id
        }
    }
    val marked = groups.flatMap { g -> g.items.filter(::isMarked) }
    val scope = rememberCoroutineScope()
    val confirmTrash = rememberTrashConfirmation { viewModel.onTrashConfirmed() }

    Scaffold(
        topBar = { BackTopBar(title, onBack) },
        bottomBar = {
            if (groups.isNotEmpty()) {
                Surface(tonalElevation = 3.dp) {
                    Button(
                        onClick = { scope.launch { confirmTrash(viewModel.trash(marked)) } },
                        enabled = marked.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text(stringResource(R.string.cleanup_delete, marked.size, sizeText(marked.sumOf { it.sizeBytes })))
                    }
                }
            }
        },
    ) { padding ->
        if (groups.isEmpty()) {
            CenteredMessage(stringResource(R.string.cleanup_nothing), Modifier.padding(padding))
            return@Scaffold
        }
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(
                    description + "\n" + stringResource(R.string.cleanup_groups_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            items(groups, key = { it.items.first().id }) { group ->
                Card {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            pluralStringResource(R.plurals.items_count, group.items.size, group.items.size) + " · " +
                                DateUtils.formatDateTime(
                                    LocalContext.current, group.items.first().dateTaken,
                                    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
                                ),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(group.items, key = { it.id }) { item ->
                                MediaThumbnail(
                                    item = item,
                                    badge = if (item.id == group.suggestedKeep.id) stringResource(R.string.duplicates_keep_badge) else null,
                                    selectionMode = true,
                                    selected = isMarked(item),
                                    modifier = Modifier
                                        .size(104.dp)
                                        .combinedClickable(onClick = { toggle(item) }, onLongClick = { onOpen(item, group.items) }),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Список: обычная сетка с выбором (долгое нажатие) и удалением из панели выбора. */
@Composable
private fun ItemsScreen(
    category: CleanupCategory,
    title: String,
    items: List<MediaItem>,
    onBack: () -> Unit,
    onOpen: (MediaItem, List<MediaItem>) -> Unit,
) {
    val selection = rememberSelectionState()
    val context = LocalContext.current
    Scaffold(
        topBar = {
            if (selection.isActive) {
                SelectionTopBar(selection, items)
            } else {
                BackTopBar(title, onBack) {
                    if (items.isNotEmpty()) {
                        TextButton(onClick = { selection.set(items.mapTo(HashSet()) { it.id }) }) {
                            Text(stringResource(R.string.cleanup_select_all))
                        }
                    }
                }
            }
        },
    ) { padding ->
        if (items.isEmpty()) {
            CenteredMessage(stringResource(R.string.cleanup_nothing), Modifier.padding(padding))
            return@Scaffold
        }
        Column(Modifier.padding(padding)) {
            Text(
                stringResource(category.descRes) + " · " + stringResource(
                    R.string.cleanup_summary,
                    pluralStringResource(R.plurals.items_count, items.size, items.size),
                    sizeText(items.sumOf { it.sizeBytes }),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).align(Alignment.Start),
            )
            MediaGrid(
                items,
                onClick = { onOpen(it, items) },
                selection = selection,
                badge = { if (category == CleanupCategory.LARGE_VIDEOS) Formatter.formatShortFileSize(context, it.sizeBytes) else null },
            )
        }
    }
}
