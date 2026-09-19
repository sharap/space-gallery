package ai.recommend.spacegallery.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/** Выбранные элементы сетки (id медиа). Режим выбора активен, пока выбрано хоть что-то. */
@Stable
class SelectionState(initial: Set<Long> = emptySet()) {

    var selected: Set<Long> by mutableStateOf(initial)
        private set

    val isActive: Boolean get() = selected.isNotEmpty()

    operator fun contains(id: Long): Boolean = id in selected

    fun toggle(id: Long) {
        selected = if (id in selected) selected - id else selected + id
    }

    fun set(ids: Set<Long>) {
        selected = ids
    }

    /** Добавить все [ids]; если все они уже выбраны — снять выбор с них (тап по заголовку дня). */
    fun toggleAll(ids: Collection<Long>) {
        selected = if (selected.containsAll(ids)) selected - ids.toSet() else selected + ids
    }

    fun clear() {
        selected = emptySet()
    }

    /** Убрать из выбора то, чего больше нет на экране (удалено, скрыто). */
    fun retainOnly(existing: Set<Long>) {
        if (!existing.containsAll(selected)) selected = selected intersect existing
    }

    companion object {
        val Saver = listSaver<SelectionState, Long>(
            save = { it.selected.toList() },
            restore = { SelectionState(it.toSet()) },
        )
    }
}

@Composable
fun rememberSelectionState(): SelectionState = rememberSaveable(saver = SelectionState.Saver) { SelectionState() }
