package ai.recommend.spacegallery.ui.people

import ai.recommend.spacegallery.R
import ai.recommend.spacegallery.domain.MediaItem
import ai.recommend.spacegallery.search.people.PeopleRepository
import ai.recommend.spacegallery.search.people.Person
import ai.recommend.spacegallery.ui.appViewModelFactory
import ai.recommend.spacegallery.ui.components.BackTopBar
import ai.recommend.spacegallery.ui.components.CenteredMessage
import ai.recommend.spacegallery.ui.components.MediaGrid
import ai.recommend.spacegallery.ui.components.SelectionTopBar
import ai.recommend.spacegallery.ui.components.rememberSelectionState
import ai.recommend.spacegallery.ui.navigation.PersonRoute
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PeopleViewModel(repository: PeopleRepository) : ViewModel() {
    val people: StateFlow<List<Person>?> = repository.observePeople()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

/** Все люди: сетка аватаров с именами и числом фото. */
@Composable
fun PeopleScreen(
    onBack: () -> Unit,
    onOpenPerson: (Person) -> Unit,
    viewModel: PeopleViewModel = viewModel(factory = appViewModelFactory { c, _ -> PeopleViewModel(c.people) }),
) {
    val people by viewModel.people.collectAsStateWithLifecycle()
    Scaffold(topBar = { BackTopBar(stringResource(R.string.people_title), onBack) }) { padding ->
        val list = people
        when {
            list == null -> CenteredMessage(stringResource(R.string.loading), Modifier.padding(padding), loading = true)
            list.isEmpty() -> CenteredMessage(stringResource(R.string.people_empty), Modifier.padding(padding))
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(96.dp),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(padding),
            ) {
                items(list, key = { it.id }) { person ->
                    Column(
                        Modifier.clickable { onOpenPerson(person) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        PersonAvatar(person, size = 88.dp)
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
            }
        }
    }
}

class PersonViewModel(private val repository: PeopleRepository, handle: SavedStateHandle) : ViewModel() {
    val personId: Long = handle.toRoute<PersonRoute>().personId

    val items: StateFlow<List<MediaItem>?> = repository.observeMedia(personId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val name: StateFlow<String?> = repository.observeName(personId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun rename(name: String) = viewModelScope.launch { repository.rename(personId, name) }
}

/** Фото человека по датам + имя (переименование в верхней панели). */
@Composable
fun PersonScreen(
    onBack: () -> Unit,
    onOpen: (item: MediaItem, personId: Long) -> Unit,
    viewModel: PersonViewModel = viewModel(factory = appViewModelFactory { c, handle -> PersonViewModel(c.people, handle) }),
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val name by viewModel.name.collectAsStateWithLifecycle()
    val selection = rememberSelectionState()
    var renaming by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            if (selection.isActive) {
                SelectionTopBar(selection, items.orEmpty())
            } else {
                BackTopBar(name ?: stringResource(R.string.person_unnamed), onBack) {
                    IconButton(onClick = { renaming = true }) {
                        Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.person_rename))
                    }
                }
            }
        },
    ) { padding ->
        val list = items
        when {
            list == null -> CenteredMessage(stringResource(R.string.loading), Modifier.padding(padding), loading = true)
            list.isEmpty() -> CenteredMessage(stringResource(R.string.smart_album_gone), Modifier.padding(padding))
            else -> MediaGrid(
                list,
                onClick = { onOpen(it, viewModel.personId) },
                groupByDate = true,
                selection = selection,
                modifier = Modifier.padding(padding),
            )
        }
    }

    if (renaming) {
        PersonNameDialog(
            initial = name.orEmpty(),
            onDismiss = { renaming = false },
            onConfirm = {
                renaming = false
                viewModel.rename(it)
            },
        )
    }
}

@Composable
private fun PersonNameDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.person_rename)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(stringResource(R.string.person_name_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                modifier = Modifier.focusRequester(focus),
            )
        },
        // Пустое имя — снять имя (человек снова «Без имени»).
        confirmButton = { TextButton(onClick = { onConfirm(value.text) }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
