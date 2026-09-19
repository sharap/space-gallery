package ai.recommend.spacegallery.ui.viewer

import ai.recommend.spacegallery.R
import ai.recommend.spacegallery.search.people.Person
import ai.recommend.spacegallery.search.people.PhotoTags
import ai.recommend.spacegallery.ui.people.PersonAvatar
import ai.recommend.spacegallery.ui.people.PersonChoice
import ai.recommend.spacegallery.ui.people.PersonPickerSheet
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

/** Что отмечаем в выборе человека: конкретное лицо или человека без лица. */
private sealed interface TagTarget {
    data class Face(val faceId: Long, val currentPersonId: Long?) : TagTarget
    data object WithoutFace : TagTarget
}

/**
 * Ручная отметка людей на фото: найденные лица (тап — «кто это?», в том числе исправить чужую
 * отметку) и люди без рамки лица — когда детектор лица не нашёл.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoTagsSheet(
    tags: PhotoTags,
    people: List<Person>,
    onAssignFace: (faceId: Long, PersonChoice) -> Unit,
    onNotAFace: (faceId: Long) -> Unit,
    onTag: (PersonChoice) -> Unit,
    onUntag: (personId: Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var picking by remember { mutableStateOf<TagTarget?>(null) }
    when (val target = picking) {
        is TagTarget.Face -> PersonPickerSheet(
            title = stringResource(R.string.photo_tags_who),
            people = people,
            exclude = setOfNotNull(target.currentPersonId),
            onDismiss = { picking = null },
            onPick = { choice ->
                picking = null
                onAssignFace(target.faceId, choice)
            },
        )
        TagTarget.WithoutFace -> PersonPickerSheet(
            title = stringResource(R.string.photo_tags_without_face),
            people = people,
            // Кто уже есть на фото (по лицу или отметке) — не предлагаем.
            exclude = tags.faces.mapNotNullTo(HashSet()) { it.personId } + tags.taggedPeople.map { it.id },
            onDismiss = { picking = null },
            onPick = { choice ->
                picking = null
                onTag(choice)
            },
        )
        null -> ModalBottomSheet(onDismissRequest = onDismiss) {
            Text(
                stringResource(R.string.photo_tags_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            LazyColumn {
                if (tags.faces.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.photo_tags_no_faces),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                        )
                    }
                }
                items(tags.faces, key = { "face${it.id}" }) { face ->
                    ListItem(
                        leadingContent = {
                            AsyncImage(
                                model = face.thumbnail,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(48.dp).clip(CircleShape),
                            )
                        },
                        headlineContent = {
                            Text(face.personName ?: stringResource(if (face.personId == null) R.string.photo_tags_unknown else R.string.person_unnamed))
                        },
                        supportingContent = { Text(stringResource(R.string.photo_tags_face_hint)) },
                        trailingContent = {
                            IconButton(onClick = { onNotAFace(face.id) }) {
                                Icon(Icons.Outlined.Block, contentDescription = stringResource(R.string.person_not_a_face))
                            }
                        },
                        modifier = Modifier.clickable { picking = TagTarget.Face(face.id, face.personId) },
                    )
                }
                items(tags.taggedPeople, key = { "tag${it.id}" }) { person ->
                    ListItem(
                        leadingContent = { PersonAvatar(person, size = 48.dp) },
                        headlineContent = { Text(person.name ?: stringResource(R.string.person_unnamed)) },
                        supportingContent = { Text(stringResource(R.string.photo_tags_manual)) },
                        trailingContent = {
                            IconButton(onClick = { onUntag(person.id) }) {
                                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.photo_tags_remove))
                            }
                        },
                    )
                }
                item {
                    ListItem(
                        leadingContent = { Icon(Icons.Outlined.PersonAdd, contentDescription = null, modifier = Modifier.size(48.dp).padding(10.dp)) },
                        headlineContent = { Text(stringResource(R.string.photo_tags_without_face)) },
                        supportingContent = { Text(stringResource(R.string.photo_tags_without_face_hint)) },
                        modifier = Modifier.clickable { picking = TagTarget.WithoutFace },
                    )
                }
            }
        }
    }
}
