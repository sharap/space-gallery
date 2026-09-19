package ai.recommend.spacegallery.ui.people

import ai.recommend.spacegallery.R
import ai.recommend.spacegallery.domain.MediaItem
import ai.recommend.spacegallery.search.people.PeopleRepository
import ai.recommend.spacegallery.search.people.Person
import ai.recommend.spacegallery.ui.appViewModelFactory
import ai.recommend.spacegallery.ui.components.BackTopBar
import ai.recommend.spacegallery.ui.components.CenteredMessage
import ai.recommend.spacegallery.ui.components.MediaGrid
import ai.recommend.spacegallery.ui.components.SelectionState
import ai.recommend.spacegallery.ui.components.SelectionTopBar
import ai.recommend.spacegallery.ui.components.mediaGridGestures
import ai.recommend.spacegallery.ui.components.rememberSelectionState
import ai.recommend.spacegallery.ui.navigation.PersonRoute
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Merge
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.toRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// ---------------------------------------------------------------- Все люди

class PeopleViewModel(private val repository: PeopleRepository) : ViewModel() {
    val people: StateFlow<List<Person>?> = repository.observePeople()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val isRebuilding: StateFlow<Boolean> = repository.isRebuilding

    /** Объединить выбранных в того, у кого больше фото. */
    fun merge(selected: List<Person>, name: String) = viewModelScope.launch {
        val target = selected.maxByOrNull { it.mediaCount } ?: return@launch
        repository.merge(target.id, selected.map { it.id }, name)
    }
}

/** Все люди: сетка аватаров; мультивыбор (долгое нажатие) -> «Объединить». */
@Composable
fun PeopleScreen(
    onBack: () -> Unit,
    onOpenPerson: (Person) -> Unit,
    viewModel: PeopleViewModel = viewModel(factory = appViewModelFactory { c, _ -> PeopleViewModel(c.people) }),
) {
    val people by viewModel.people.collectAsStateWithLifecycle()
    val rebuilding by viewModel.isRebuilding.collectAsStateWithLifecycle()
    val selection = rememberSelectionState()
    var merging by remember { mutableStateOf(false) }
    val selected = people.orEmpty().filter { it.id in selection }

    Scaffold(
        topBar = {
            Column {
                if (selection.isActive) {
                    PeopleSelectionTopBar(selection, selected.size, onMerge = { merging = true })
                } else {
                    BackTopBar(stringResource(R.string.people_title), onBack)
                }
                if (rebuilding) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
    ) { padding ->
        val list = people
        when {
            list == null -> CenteredMessage(stringResource(R.string.loading), Modifier.padding(padding), loading = true)
            list.isEmpty() -> CenteredMessage(stringResource(R.string.people_empty), Modifier.padding(padding))
            else -> PeopleGrid(list, selection, onOpenPerson, Modifier.padding(padding))
        }
    }

    if (merging && selected.size >= 2) {
        MergePeopleDialog(
            people = selected,
            onDismiss = { merging = false },
            onConfirm = { name ->
                merging = false
                viewModel.merge(selected, name)
                selection.clear()
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeopleSelectionTopBar(selection: SelectionState, count: Int, onMerge: () -> Unit) {
    BackHandler(enabled = selection.isActive) { selection.clear() }
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        navigationIcon = {
            IconButton(onClick = selection::clear) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.action_clear_selection))
            }
        },
        title = { Text(pluralStringResource(R.plurals.selected_count, count, count)) },
        actions = {
            TextButton(onClick = onMerge, enabled = count >= 2) {
                Icon(Icons.Outlined.Merge, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                Text(stringResource(R.string.people_merge))
            }
        },
    )
}

/** Сетка людей: те же жесты, что у сеток фото (тап, долгое нажатие + протягивание). */
@Composable
private fun PeopleGrid(people: List<Person>, selection: SelectionState, onOpen: (Person) -> Unit, modifier: Modifier) {
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val edgePx = with(LocalDensity.current) { 72.dp.toPx() }
    val currentPeople by rememberUpdatedState(people)
    LazyVerticalGrid(
        columns = GridCells.Adaptive(96.dp),
        state = gridState,
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.mediaGridGestures(
            gridState, selection, { currentPeople.map { it.id } }, scope, edgePx,
            onTap = { key ->
                val id = key as? Long ?: return@mediaGridGestures
                if (selection.isActive) selection.toggle(id) else currentPeople.firstOrNull { it.id == id }?.let(onOpen)
            },
            onLongPress = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
        ),
    ) {
        items(people, key = { it.id }) { person ->
            val selected = person.id in selection
            PersonTile(
                person,
                selectionMode = selection.isActive,
                selected = selected,
                modifier = Modifier.animateItem().semantics {
                    this.selected = selected
                    onClick {
                        if (selection.isActive) selection.toggle(person.id) else onOpen(person)
                        true
                    }
                    onLongClick {
                        selection.toggle(person.id)
                        true
                    }
                },
            )
        }
    }
}

@Composable
private fun PersonTile(person: Person, selectionMode: Boolean, selected: Boolean, modifier: Modifier = Modifier) {
    val scale by animateFloatAsState(if (selected) 0.85f else 1f, label = "personSelected")
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            PersonAvatar(person, size = 88.dp, modifier = Modifier.scale(scale))
            if (selectionMode && selected) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.TopStart).size(24.dp).background(Color.White, CircleShape),
                )
            }
        }
        Text(
            person.name ?: stringResource(R.string.person_add_name),
            style = MaterialTheme.typography.labelLarge,
            color = if (person.name == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            pluralStringResource(R.plurals.items_count, person.mediaCount, person.mediaCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Объединение: одно имя на всех (по умолчанию — имя того, у кого больше фото). */
@Composable
private fun MergePeopleDialog(people: List<Person>, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val initial = people.sortedByDescending { it.mediaCount }.firstNotNullOfOrNull { it.name }.orEmpty()
    NameDialog(
        title = stringResource(R.string.people_merge_title, people.size),
        text = stringResource(R.string.people_merge_text),
        initial = initial,
        confirmLabel = stringResource(R.string.people_merge),
        onDismiss = onDismiss,
        onConfirm = onConfirm,
    )
}

// ---------------------------------------------------------------- Человек

class PersonViewModel(private val repository: PeopleRepository, handle: SavedStateHandle) : ViewModel() {
    val personId: Long = handle.toRoute<PersonRoute>().personId

    val items: StateFlow<List<MediaItem>?> = repository.observeMedia(personId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val name: StateFlow<String?> = repository.observeName(personId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val isRebuilding: StateFlow<Boolean> = repository.isRebuilding

    private val _suggestions = MutableStateFlow<List<Person>>(emptyList())

    /** «Это тоже он?» — похожие группы, возможно тот же человек. */
    val suggestions: StateFlow<List<Person>> = _suggestions.asStateFlow()

    init {
        refreshSuggestions()
    }

    fun refreshSuggestions() = viewModelScope.launch { _suggestions.value = repository.suggestions(personId) }

    fun rename(name: String) = viewModelScope.launch { repository.rename(personId, name) }

    fun acceptSuggestion(other: Person) = viewModelScope.launch {
        _suggestions.value = _suggestions.value - other
        repository.merge(personId, listOf(personId, other.id), name.value ?: other.name)
    }

    fun rejectSuggestion(other: Person) = viewModelScope.launch {
        _suggestions.value = _suggestions.value - other
        repository.dismissSuggestion(personId, other.id)
    }

    fun resetConfirmations() = viewModelScope.launch { repository.resetConfirmations(personId) }

    fun markAllNotFaces() = viewModelScope.launch { repository.markPersonAsNotFaces(personId) }

    fun markNotFaces(mediaIds: Collection<Long>) = viewModelScope.launch { repository.markNotFaces(personId, mediaIds) }

    fun removePhotos(mediaIds: Collection<Long>) = viewModelScope.launch {
        repository.removeFromPerson(personId, mediaIds)
    }

    /** Все люди — для выбора, к кому перенести фото. */
    val people: StateFlow<List<Person>> = repository.observePeople()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun moveTo(mediaIds: Collection<Long>, choice: PersonChoice) = viewModelScope.launch {
        val target = when (choice) {
            is PersonChoice.Existing -> choice.person.id
            is PersonChoice.New -> repository.createPerson(choice.name)
        }
        repository.moveToPerson(personId, mediaIds, target)
    }
}

/** Фото человека по датам + имя, «это не он» (мультивыбор) и подсказки «это тоже он?». */
@Composable
fun PersonScreen(
    onBack: () -> Unit,
    onOpen: (item: MediaItem, personId: Long) -> Unit,
    viewModel: PersonViewModel = viewModel(factory = appViewModelFactory { c, handle -> PersonViewModel(c.people, handle) }),
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val name by viewModel.name.collectAsStateWithLifecycle()
    val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()
    val rebuilding by viewModel.isRebuilding.collectAsStateWithLifecycle()
    val selection = rememberSelectionState()
    var renaming by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmNotFaces by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    val people by viewModel.people.collectAsStateWithLifecycle()
    val displayName = name ?: stringResource(R.string.person_unnamed)

    // После пересборки (объединение/«это не он») подсказки пересчитываются.
    LaunchedEffect(rebuilding) { if (!rebuilding) viewModel.refreshSuggestions() }

    Scaffold(
        topBar = {
            Column {
                if (selection.isActive) {
                    SelectionTopBar(
                        selection,
                        items.orEmpty(),
                        extraMenuActions = listOf(
                            stringResource(R.string.person_move_to) to { moving = true },
                            stringResource(R.string.person_not_this, displayName) to {
                                viewModel.removePhotos(selection.selected)
                                selection.clear()
                            },
                            stringResource(R.string.person_not_a_face) to {
                                viewModel.markNotFaces(selection.selected)
                                selection.clear()
                            },
                        ),
                    )
                } else {
                    BackTopBar(displayName, onBack) {
                        IconButton(onClick = { renaming = true }) {
                            Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.person_rename))
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.action_more))
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(stringResource(R.string.person_reset_confirmations))
                                            Text(
                                                stringResource(R.string.person_reset_confirmations_desc),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    },
                                    onClick = {
                                        menuOpen = false
                                        viewModel.resetConfirmations()
                                    },
                                )
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(stringResource(R.string.person_not_faces))
                                            Text(
                                                stringResource(R.string.person_not_faces_desc),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    },
                                    onClick = {
                                        menuOpen = false
                                        confirmNotFaces = true
                                    },
                                )
                            }
                        }
                    }
                }
                if (rebuilding) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            if (suggestions.isNotEmpty() && !selection.isActive) {
                SuggestionsCard(displayName, suggestions, viewModel::acceptSuggestion, viewModel::rejectSuggestion)
            }
            val list = items
            when {
                list == null -> CenteredMessage(stringResource(R.string.loading), loading = true)
                list.isEmpty() -> CenteredMessage(stringResource(R.string.smart_album_gone))
                else -> MediaGrid(
                    list,
                    onClick = { onOpen(it, viewModel.personId) },
                    groupByDate = true,
                    selection = selection,
                )
            }
        }
    }

    if (moving) {
        PersonPickerSheet(
            title = stringResource(R.string.person_move_to_title, selection.selected.size),
            people = people,
            exclude = setOf(viewModel.personId),
            onDismiss = { moving = false },
            onPick = { choice ->
                moving = false
                viewModel.moveTo(selection.selected.toList(), choice)
                selection.clear()
            },
        )
    }

    if (confirmNotFaces) {
        AlertDialog(
            onDismissRequest = { confirmNotFaces = false },
            title = { Text(stringResource(R.string.person_not_faces)) },
            text = { Text(stringResource(R.string.person_not_faces_desc)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmNotFaces = false
                    viewModel.markAllNotFaces()
                    onBack()
                }) { Text(stringResource(R.string.person_not_faces_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmNotFaces = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    if (renaming) {
        NameDialog(
            title = stringResource(R.string.person_rename),
            text = stringResource(R.string.person_rename_text),
            initial = name.orEmpty(),
            confirmLabel = stringResource(R.string.action_save),
            onDismiss = { renaming = false },
            onConfirm = {
                renaming = false
                viewModel.rename(it)
            },
        )
    }
}

/** «Это тоже {имя}?» — аватары похожих групп с кнопками «Да» (объединить) и «Нет». */
@Composable
private fun SuggestionsCard(
    name: String,
    suggestions: List<Person>,
    onAccept: (Person) -> Unit,
    onReject: (Person) -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(
            stringResource(R.string.person_suggestion_title, name),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(suggestions, key = { it.id }) { other ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    PersonAvatar(other, size = 56.dp)
                    Text(
                        other.name ?: pluralStringResource(R.plurals.items_count, other.mediaCount, other.mediaCount),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Row {
                        TextButton(onClick = { onAccept(other) }) { Text(stringResource(R.string.action_yes)) }
                        TextButton(onClick = { onReject(other) }) { Text(stringResource(R.string.action_no)) }
                    }
                }
            }
        }
    }
}

@Composable
internal fun NameDialog(
    title: String,
    text: String,
    initial: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 12.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(stringResource(R.string.person_name_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                    modifier = Modifier.focusRequester(focus),
                )
            }
        },
        // Пустое имя — снять имя (человек снова «Без имени»).
        confirmButton = { TextButton(onClick = { onConfirm(value.text) }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
