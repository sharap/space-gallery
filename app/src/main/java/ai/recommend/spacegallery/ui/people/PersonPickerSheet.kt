package ai.recommend.spacegallery.ui.people

import ai.recommend.spacegallery.R
import ai.recommend.spacegallery.search.people.Person
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/** Кого выбрали: существующего человека или нового (с именем). */
sealed interface PersonChoice {
    data class Existing(val person: Person) : PersonChoice
    data class New(val name: String) : PersonChoice
}

/**
 * Выбор человека: «Новый человек» и список людей (сначала названные). [exclude] — кого не
 * предлагать (например, текущего человека при переносе фото).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonPickerSheet(
    title: String,
    people: List<Person>,
    onPick: (PersonChoice) -> Unit,
    onDismiss: () -> Unit,
    exclude: Set<Long> = emptySet(),
) {
    var creating by remember { mutableStateOf(false) }
    val shown = people.filter { it.id !in exclude }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(title, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        LazyColumn {
            item {
                ListItem(
                    leadingContent = { Icon(Icons.Outlined.PersonAdd, contentDescription = null, modifier = Modifier.size(40.dp).padding(6.dp)) },
                    headlineContent = { Text(stringResource(R.string.person_new)) },
                    modifier = Modifier.clickable { creating = true },
                )
            }
            items(shown, key = { it.id }) { person ->
                ListItem(
                    leadingContent = { PersonAvatar(person, size = 40.dp) },
                    headlineContent = { Text(person.name ?: stringResource(R.string.person_unnamed)) },
                    supportingContent = { Text(pluralStringResource(R.plurals.items_count, person.mediaCount, person.mediaCount)) },
                    modifier = Modifier.clickable { onPick(PersonChoice.Existing(person)) },
                )
            }
        }
    }
    if (creating) {
        NameDialog(
            title = stringResource(R.string.person_new),
            text = stringResource(R.string.person_new_text),
            initial = "",
            confirmLabel = stringResource(R.string.action_save),
            onDismiss = { creating = false },
            onConfirm = { name ->
                creating = false
                if (name.isNotBlank()) onPick(PersonChoice.New(name))
            },
        )
    }
}
