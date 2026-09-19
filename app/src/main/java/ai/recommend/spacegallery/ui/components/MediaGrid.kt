package ai.recommend.spacegallery.ui.components

import ai.recommend.spacegallery.domain.MediaItem
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/**
 * Сетка медиа. При [groupByDay] = true вставляет заголовки дат (лента «Фото»).
 * TODO: Paging 3 для очень больших медиатек, мультивыбор долгим нажатием, pinch-to-zoom сетки.
 */
@Composable
fun MediaGrid(
    items: List<MediaItem>,
    onClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    groupByDay: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    blurred: (MediaItem) -> Boolean = { false },
    badge: (MediaItem) -> String? = { null },
) {
    val sections = remember(items, groupByDay) {
        if (groupByDay) items.groupBy { dayKey(it.dateTaken) }.toList() else listOf(0L to items)
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 96.dp),
        modifier = modifier,
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        sections.forEach { (day, dayItems) ->
            if (groupByDay) {
                item(key = "header-$day", span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
                    Text(
                        DateFormat.getDateInstance(DateFormat.LONG).format(Date(day)),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
            }
            items(dayItems, key = { it.id }, contentType = { "media" }) { item ->
                MediaThumbnail(
                    item = item,
                    blurred = blurred(item),
                    badge = badge(item),
                    modifier = Modifier.clickable { onClick(item) },
                )
            }
        }
    }
}

private fun dayKey(millis: Long): Long = Calendar.getInstance().run {
    timeInMillis = millis
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
    timeInMillis
}
