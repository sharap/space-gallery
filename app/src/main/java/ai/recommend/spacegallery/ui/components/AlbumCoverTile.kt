package ai.recommend.spacegallery.ui.components

import ai.recommend.spacegallery.R
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import java.text.NumberFormat

/**
 * Обложка альбома (обычного или умного): название слева внизу, количество файлов справа внизу
 * или по центру. Поддерживает оформление мультивыбора.
 */
@Composable
fun AlbumCoverTile(
    name: String,
    itemCount: Int,
    coverUri: Uri,
    modifier: Modifier = Modifier,
    selectionMode: Boolean = false,
    selected: Boolean = false,
) {
    val description = name + ", " + pluralStringResource(R.plurals.items_count, itemCount, itemCount)
    SelectableTile(
        modifier.semantics(mergeDescendants = true) { contentDescription = description },
        selectionMode,
        selected,
    ) {
        AsyncImage(
            model = coverUri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        AlbumLabels(
            name = name,
            count = remember(itemCount) { NumberFormat.getIntegerInstance().format(itemCount) },
            modifier = Modifier.fillMaxSize().padding(4.dp),
        )
    }
}

/**
 * Подписи обложки в стиле длительности видео. Количество стоит справа внизу, если рядом
 * остаётся место хотя бы на начало названия ([MIN_NAME_WIDTH]; длинное название обрезается);
 * иначе (мелкая сетка) количество переезжает в центр плитки, а название занимает всю ширину.
 */
@Composable
private fun AlbumLabels(name: String, count: String, modifier: Modifier = Modifier) {
    Layout(
        contents = listOf({ ThumbnailLabel(name) }, { ThumbnailLabel(count) }),
        modifier = modifier,
    ) { (nameMeasurables, countMeasurables), constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val nameMeasurable = nameMeasurables.single()
        val countPlaceable = countMeasurables.single().measure(loose)

        val gap = LABEL_GAP.roundToPx()
        val minName = minOf(nameMeasurable.maxIntrinsicWidth(height), MIN_NAME_WIDTH.roundToPx())
        val countInline = countPlaceable.width + gap + minName <= width
        val namePlaceable = nameMeasurable.measure(
            loose.copy(maxWidth = if (countInline) width - countPlaceable.width - gap else width),
        )

        layout(width, height) {
            namePlaceable.place(0, height - namePlaceable.height)
            if (countInline) {
                countPlaceable.place(width - countPlaceable.width, height - countPlaceable.height)
            } else {
                countPlaceable.place((width - countPlaceable.width) / 2, (height - countPlaceable.height) / 2)
            }
        }
    }
}

private val LABEL_GAP = 6.dp
private val MIN_NAME_WIDTH = 40.dp
