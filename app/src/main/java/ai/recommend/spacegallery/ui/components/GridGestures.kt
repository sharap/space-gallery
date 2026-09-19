package ai.recommend.spacegallery.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Щипок меняет число столбцов по шагам [levels]: разведение пальцев — крупнее (меньше столбцов),
 * сведение — мельче. За один жест можно пройти несколько шагов.
 * Пока на экране два пальца, события поглощаются: сетка не прокручивается и тапы не срабатывают.
 */
fun Modifier.pinchToChangeColumns(
    levels: List<Int>,
    currentColumns: () -> Int,
    onColumnsChange: (Int) -> Unit,
): Modifier = pointerInput(levels) {
    awaitEachGesture {
        var zoom = 1f
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.count { it.pressed } >= 2) {
                zoom *= event.calculateZoom()
                event.changes.forEach { it.consume() }
                val index = levels.indexOf(currentColumns()).coerceAtLeast(0)
                if (zoom > PINCH_STEP && index > 0) {
                    onColumnsChange(levels[index - 1])
                    zoom = 1f
                } else if (zoom < 1f / PINCH_STEP && index < levels.lastIndex) {
                    onColumnsChange(levels[index + 1])
                    zoom = 1f
                }
            }
        } while (event.changes.any { it.pressed })
    }
}

private const val PINCH_STEP = 1.3f

/**
 * Тапы, долгое нажатие и выделение протягиванием для всей сетки одним обработчиком
 * (обработчики на плитках конфликтовали бы с протягиванием):
 * - тап -> [onTap] с ключом элемента под пальцем (id медиа или ключ заголовка);
 * - долгое нажатие -> [onLongPress] и, если вести палец, выделение диапазона от начального
 *   элемента до элемента под пальцем (как в Google Photos). Если начать с уже выбранного
 *   элемента — диапазон снимается. У края сетка прокручивается сама.
 * До долгого нажатия события не поглощаются — обычная прокрутка сетки работает как раньше.
 *
 * @param orderedIds id медиа в порядке сетки (без заголовков).
 */
fun Modifier.mediaGridGestures(
    gridState: LazyGridState,
    selection: SelectionState,
    orderedIds: () -> List<Long>,
    scope: CoroutineScope,
    autoScrollEdgePx: Float,
    onTap: (key: Any) -> Unit,
    onLongPress: () -> Unit,
): Modifier = pointerInput(gridState, selection) {
    fun keyAt(position: Offset): Any? = gridState.layoutInfo.visibleItemsInfo.firstOrNull { info ->
        position.x >= info.offset.x && position.x < info.offset.x + info.size.width &&
            position.y >= info.offset.y && position.y < info.offset.y + info.size.height
    }?.key

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)

        // Фаза 1: тап, прокрутка или долгое нажатие? Смотрим в Initial-проходе, ничего не поглощая.
        var outcome = PressOutcome.LONG_PRESS // если истёк таймаут — это долгое нажатие
        withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id }
                if (change == null || event.changes.size > 1) { // палец потерян или щипок
                    outcome = PressOutcome.NONE
                    break
                }
                if (change.changedToUp()) {
                    outcome = if (change.isConsumed) PressOutcome.NONE else PressOutcome.TAP
                    break
                }
                if (change.isConsumed || (change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                    outcome = PressOutcome.NONE // это прокрутка
                    break
                }
            }
        }
        when (outcome) {
            PressOutcome.NONE -> return@awaitEachGesture
            PressOutcome.TAP -> {
                keyAt(down.position)?.let(onTap)
                return@awaitEachGesture
            }
            PressOutcome.LONG_PRESS -> Unit
        }

        // Фаза 2: долгое нажатие — выделение протягиванием, события поглощаем (сетка не скроллится).
        val startId = keyAt(down.position) as? Long ?: return@awaitEachGesture
        val ids = orderedIds()
        val anchorIndex = ids.indexOf(startId).takeIf { it >= 0 } ?: return@awaitEachGesture
        val initial = selection.selected
        val selecting = startId !in initial
        var pointer = down.position
        var autoScroll: Job? = null
        onLongPress()

        fun updateRange() {
            val current = orderedIds()
            val currentIndex = (keyAt(pointer) as? Long)?.let(current::indexOf)?.takeIf { it >= 0 } ?: return
            val range = current.subList(minOf(anchorIndex, currentIndex), maxOf(anchorIndex, currentIndex) + 1)
            selection.set(if (selecting) initial + range else initial - range.toSet())
        }

        fun edgeSpeed(): Float = when {
            pointer.y < autoScrollEdgePx ->
                -AUTO_SCROLL_MAX * (1f - pointer.y / autoScrollEdgePx).coerceIn(0f, 1f)
            pointer.y > size.height - autoScrollEdgePx ->
                AUTO_SCROLL_MAX * (1f - (size.height - pointer.y) / autoScrollEdgePx).coerceIn(0f, 1f)
            else -> 0f
        }

        updateRange()
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                event.changes.forEach { it.consume() }
                if (!change.pressed) break
                pointer = change.position
                updateRange()
                if (autoScroll?.isActive != true && edgeSpeed() != 0f) {
                    autoScroll = scope.launch {
                        while (isActive) {
                            val speed = edgeSpeed()
                            if (speed == 0f) break
                            gridState.scrollBy(speed)
                            updateRange()
                            delay(16)
                        }
                    }
                }
            }
        } finally {
            autoScroll?.cancel()
        }
    }
}

private enum class PressOutcome { NONE, TAP, LONG_PRESS }

/** Максимальная скорость автопрокрутки, px за кадр. */
private const val AUTO_SCROLL_MAX = 40f
