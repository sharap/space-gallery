package ai.recommend.spacegallery.ui.components

import ai.recommend.spacegallery.SpaceGalleryApp
import ai.recommend.spacegallery.data.settings.GRID_COLUMN_LEVELS
import ai.recommend.spacegallery.data.settings.GridKind
import ai.recommend.spacegallery.domain.MediaItem
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Сетка медиа.
 * - Щипок меняет число столбцов (настройка общая для всех экранов).
 * - [groupByDate]: заголовки по дням, в мелкой сетке (≥ 5 столбцов) — по месяцам.
 * - [selection] != null включает мультивыбор: долгое нажатие + протягивание, тап в режиме
 *   выбора отмечает элемент, тап по заголовку — весь день/месяц.
 *
 * - Длинная сетка — быстрая прокрутка ползунком с подписью месяца.
 *
 * TODO: Paging 3 для очень больших медиатек.
 */
@Composable
fun MediaGrid(
    items: List<MediaItem>,
    onClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    groupByDate: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    selection: SelectionState? = null,
    blurred: (MediaItem) -> Boolean = { false },
    badge: (MediaItem) -> String? = { null },
) {
    val (columns, setColumns) = rememberGridColumns(GridKind.MEDIA)
    val byMonth = columns >= MONTH_HEADERS_FROM_COLUMNS
    val sections = remember(items, groupByDate, byMonth) {
        if (groupByDate) {
            items.groupBy { if (byMonth) monthKey(it.dateTaken) else dayKey(it.dateTaken) }.toList()
        } else {
            listOf(0L to items)
        }
    }
    val orderedIds = remember(sections) { sections.flatMap { (_, section) -> section.map { it.id } } }
    val byId = remember(items) { items.associateBy { it.id } }
    val sectionIds = remember(sections) { sections.associate { (key, section) -> headerKey(key) to section.map { it.id } } }

    // Удалённые/скрытые элементы выпадают из выбора.
    LaunchedEffect(byId.keys) { selection?.retainOnly(byId.keys) }

    val gridState = rememberLazyGridState()
    val noSelection = remember { SelectionState() }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val edgePx = with(LocalDensity.current) { 72.dp.toPx() }
    val currentColumns by rememberUpdatedState(columns)
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOrderedIds by rememberUpdatedState(orderedIds)
    val currentById by rememberUpdatedState(byId)
    val currentSectionIds by rememberUpdatedState(sectionIds)

    val gestures = Modifier
        .pinchToChangeColumns(
            levels = GRID_COLUMN_LEVELS,
            currentColumns = { currentColumns },
            onColumnsChange = {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                setColumns(it)
            },
        )
        .then(
            if (selection == null) {
                Modifier.mediaGridGestures(
                    gridState, noSelection, { emptyList() }, scope, edgePx,
                    onTap = { key -> (key as? Long)?.let(currentById::get)?.let(currentOnClick) },
                    onLongPress = {},
                )
            } else {
                Modifier.mediaGridGestures(
                    gridState, selection, { currentOrderedIds }, scope, edgePx,
                    onTap = { key ->
                        when {
                            key is Long && selection.isActive -> selection.toggle(key)
                            key is Long -> currentById[key]?.let(currentOnClick)
                            selection.isActive -> currentSectionIds[key]?.let(selection::toggleAll)
                        }
                    },
                    onLongPress = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                )
            }
        )

    // Дата каждой позиции сетки (заголовки и фото) — подпись быстрой прокрутки.
    val positionDates = remember(sections, groupByDate) {
        if (!groupByDate) null else sections.flatMap { (key, section) -> listOf(key) + section.map { it.dateTaken } }.toLongArray()
    }
    Box(modifier) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = gridState,
            modifier = Modifier.fillMaxSize().then(gestures),
            contentPadding = contentPadding,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            sections.forEach { (sectionKey, sectionItems) ->
                if (groupByDate) {
                    item(key = headerKey(sectionKey), span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
                        val allSelected = selection != null && sectionItems.all { it.id in selection }
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (byMonth) formatMonth(sectionKey) else formatDay(sectionKey),
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f),
                            )
                            if (selection?.isActive == true) {
                                Icon(
                                    if (allSelected) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                                    contentDescription = null,
                                    tint = if (allSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                        }
                    }
                }
                items(sectionItems, key = { it.id }, contentType = { "media" }) { item ->
                    val selected = selection != null && item.id in selection
                    MediaThumbnail(
                        item = item,
                        blurred = blurred(item),
                        badge = badge(item),
                        selectionMode = selection?.isActive == true,
                        selected = selected,
                        modifier = Modifier
                            .animateItem()
                            // Жесты обрабатывает сетка целиком; здесь — только семантика для доступности.
                            .semantics {
                                this.selected = selected
                                onClick {
                                    if (selection?.isActive == true) selection.toggle(item.id) else onClick(item)
                                    true
                                }
                                if (selection != null) {
                                    onLongClick {
                                        selection.toggle(item.id)
                                        true
                                    }
                                }
                            },
                    )
                }
            }
        }
        if (items.size >= FAST_SCROLL_MIN_ITEMS) {
            FastScroller(
                gridState,
                label = { index -> positionDates?.getOrNull(index)?.let { formatMonth(monthKey(it)) } },
                contentPadding = contentPadding,
            )
        }
    }
}

/**
 * Число столбцов сетки, меняется щипком. У фото и альбомов настройки раздельные;
 * все сетки фото (лента, альбом, поиск...) синхронны между собой.
 */
@Composable
fun rememberGridColumns(kind: GridKind): Pair<Int, (Int) -> Unit> {
    val settings = (LocalContext.current.applicationContext as SpaceGalleryApp).container.settings
    val scope = rememberCoroutineScope()
    val flow = remember(settings, kind) { settings.settings.map { it.columns(kind) } }
    val columns by flow.collectAsStateWithLifecycle(initialValue = DEFAULT_COLUMNS)
    return columns to { value -> scope.launch { settings.setGridColumns(kind, value) } }
}

private const val DEFAULT_COLUMNS = 4
private const val MONTH_HEADERS_FROM_COLUMNS = 5
/** Быстрая прокрутка — для сеток длиннее нескольких экранов. */
private const val FAST_SCROLL_MIN_ITEMS = 120

private fun headerKey(sectionKey: Long) = "header-$sectionKey"

private fun dayKey(millis: Long): Long = Calendar.getInstance().run {
    timeInMillis = millis
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
    timeInMillis
}

private fun monthKey(millis: Long): Long = Calendar.getInstance().run {
    timeInMillis = dayKey(millis)
    set(Calendar.DAY_OF_MONTH, 1)
    timeInMillis
}

private fun formatDay(millis: Long): String = DateFormat.getDateInstance(DateFormat.LONG).format(Date(millis))

/** «Сентябрь 2026»; локаль берётся при каждом вызове — пользователь может сменить язык на ходу. */
private fun formatMonth(millis: Long): String {
    val locale = Locale.getDefault()
    return DateTimeFormatter.ofPattern("LLLL yyyy", locale)
        .format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
        .replaceFirstChar { it.titlecase(locale) }
}
