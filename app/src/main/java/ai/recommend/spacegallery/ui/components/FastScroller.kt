package ai.recommend.spacegallery.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Быстрая прокрутка длинной сетки: ползунок у правого края появляется при прокрутке, его можно
 * тянуть; пока тянешь — рядом подпись ([label] элемента под ползунком, например «Май 2024»).
 * Сама подложка касания не перехватывает — только ползунок, пока он виден.
 */
@Composable
fun FastScroller(
    state: LazyGridState,
    label: (index: Int) -> String?,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val haptics = LocalHapticFeedback.current
    val currentLabel by rememberUpdatedState(label)
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var target by remember { mutableStateOf<Int?>(null) }

    val scrollFraction by remember {
        derivedStateOf {
            val info = state.layoutInfo
            val scrollable = info.totalItemsCount - info.visibleItemsInfo.size
            if (scrollable <= 0) 0f else (state.firstVisibleItemIndex.toFloat() / scrollable).coerceIn(0f, 1f)
        }
    }
    val active by remember { derivedStateOf { dragging || state.isScrollInProgress } }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(active) {
        if (active) {
            shown = true
        } else {
            delay(HIDE_DELAY_MS)
            shown = false
        }
    }
    val alpha by animateFloatAsState(if (shown) 1f else 0f, label = "fastScrollAlpha")

    // Прыжки к позиции ползунка: только последняя цель, без очереди из корутин.
    LaunchedEffect(state) {
        snapshotFlow { target }.collect { index -> if (index != null) state.scrollToItem(index) }
    }
    val bubble = if (dragging) target?.let { currentLabel(it) } else null
    LaunchedEffect(bubble) { if (bubble != null) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }

    BoxWithConstraints(modifier.fillMaxSize().padding(contentPadding)) {
        val density = LocalDensity.current
        val thumbHeightPx = with(density) { THUMB_HEIGHT.toPx() }
        val trackPx = (constraints.maxHeight - thumbHeightPx).coerceAtLeast(1f)
        val fraction = if (dragging) dragFraction else scrollFraction
        val thumbY = (fraction * trackPx).roundToInt()

        if (bubble != null) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                shadowElevation = 4.dp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(-(THUMB_WIDTH + 12.dp).roundToPx(), thumbY + ((THUMB_HEIGHT - 40.dp) / 2).roundToPx()) },
            ) {
                Text(bubble, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
            }
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(0, thumbY) }
                .size(THUMB_WIDTH, THUMB_HEIGHT)
                .alpha(alpha)
                .then(
                    if (!shown) Modifier else Modifier.pointerInput(state) {
                        detectVerticalDragGestures(
                            onDragStart = {
                                dragging = true
                                dragFraction = scrollFraction
                            },
                            onDragEnd = { dragging = false },
                            onDragCancel = { dragging = false },
                        ) { change, dy ->
                            change.consume()
                            dragFraction = (dragFraction + dy / trackPx).coerceIn(0f, 1f)
                            val total = state.layoutInfo.totalItemsCount
                            if (total > 0) target = (dragFraction * (total - 1)).roundToInt()
                        }
                    }
                ),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Surface(
                shape = RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                shadowElevation = 2.dp,
            ) {
                Icon(
                    Icons.Outlined.UnfoldMore,
                    contentDescription = null,
                    modifier = Modifier.padding(start = 8.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
                )
            }
        }
    }
}

private val THUMB_WIDTH = 48.dp
private val THUMB_HEIGHT = 56.dp
private const val HIDE_DELAY_MS = 1_500L
