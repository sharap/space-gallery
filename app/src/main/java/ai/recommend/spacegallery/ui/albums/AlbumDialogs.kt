package ai.recommend.spacegallery.ui.albums

import ai.recommend.spacegallery.R
import ai.recommend.spacegallery.data.media.AlbumNames
import ai.recommend.spacegallery.domain.Album
import ai.recommend.spacegallery.domain.MediaType
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.Flow

/**
 * Ввод названия альбома (создание / переименование) с проверкой:
 * недопустимые символы и уже занятое имя ([isTaken]).
 */
@Composable
fun AlbumNameDialog(
    title: String,
    confirmLabel: String,
    initialName: String,
    isTaken: (String) -> Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(TextFieldValue(initialName, TextRange(0, initialName.length))) }
    val name = AlbumNames.normalize(value.text)
    val error = when {
        name.isEmpty() -> null
        !AlbumNames.isValid(name) -> stringResource(R.string.album_name_invalid)
        name != AlbumNames.normalize(initialName) && isTaken(name) -> stringResource(R.string.album_name_taken)
        else -> null
    }
    val canConfirm = name.isNotEmpty() && error == null && name != AlbumNames.normalize(initialName)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(stringResource(R.string.album_name_hint)) },
                singleLine = true,
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                modifier = Modifier.focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = canConfirm) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Подтверждение удаления альбомов: все их файлы уйдут в корзину. */
@Composable
fun DeleteAlbumsDialog(albumCount: Int, fileCount: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(pluralStringResource(R.plurals.album_delete_title, albumCount, albumCount)) },
        text = { Text(pluralStringResource(R.plurals.album_delete_text, fileCount, fileCount)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_delete)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Выбор альбома для «Отправить в альбом»: новый альбом или один из существующих. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumPickerSheet(
    albums: List<Album>,
    /** Типы переносимых файлов: от них зависит, какие папки могут их принять. */
    types: Set<MediaType>,
    onPick: (MoveTarget) -> Unit,
    onDismiss: () -> Unit,
) {
    var creating by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            stringResource(R.string.action_send_to_album),
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        LazyColumn {
            item {
                ListItem(
                    leadingContent = { Icon(Icons.Outlined.CreateNewFolder, contentDescription = null, modifier = Modifier.size(48.dp)) },
                    headlineContent = { Text(stringResource(R.string.album_new)) },
                    modifier = Modifier.clickable { creating = true },
                )
            }
            items(albums, key = { it.id }) { album ->
                val allowed = AlbumNames.canHold(album.relativePath, types)
                ListItem(
                    leadingContent = {
                        AsyncImage(
                            model = album.coverUri,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(48.dp).aspectRatio(1f).clip(RoundedCornerShape(8.dp)),
                        )
                    },
                    headlineContent = { Text(album.name) },
                    supportingContent = {
                        Text(
                            if (allowed) {
                                pluralStringResource(R.plurals.items_count, album.itemCount, album.itemCount)
                            } else {
                                stringResource(R.string.album_move_not_allowed)
                            }
                        )
                    },
                    modifier = Modifier
                        .alpha(if (allowed) 1f else 0.38f)
                        .clickable(enabled = allowed) { onPick(MoveTarget.Existing(album)) },
                )
            }
        }
    }
    if (creating) {
        AlbumNameDialog(
            title = stringResource(R.string.album_new),
            confirmLabel = stringResource(R.string.action_create),
            initialName = "",
            isTaken = { name -> albums.any { it.relativePath.equals(AlbumNames.newAlbumPath(name), ignoreCase = true) } },
            onConfirm = { name ->
                creating = false
                onPick(MoveTarget.New(name))
            },
            onDismiss = { creating = false },
        )
    }
}

/**
 * Показывает итоги файловых операций короткими сообщениями и сообщает экрану о переименовании/удалении.
 */
@Composable
fun AlbumEventEffect(
    events: Flow<AlbumEvent>,
    onRenamed: (newAlbumId: Long?) -> Unit = {},
    onDeleted: () -> Unit = {},
    onMoved: () -> Unit = {},
) {
    val context = LocalContext.current
    val res = LocalResources.current
    LaunchedEffect(events) {
        events.collect { event ->
            val message = when (event) {
                is AlbumEvent.Moved -> buildString {
                    onMoved()
                    if (event.moved > 0) append(res.getQuantityString(R.plurals.album_moved, event.moved, event.moved))
                    if (event.failed > 0) {
                        if (isNotEmpty()) append(". ")
                        append(res.getString(R.string.album_move_failed, event.failed))
                    }
                }
                is AlbumEvent.Renamed -> {
                    onRenamed(event.newAlbumId)
                    if (event.failed > 0) res.getString(R.string.album_move_failed, event.failed)
                    else res.getString(R.string.album_renamed)
                }
                AlbumEvent.Deleted -> {
                    onDeleted()
                    res.getString(R.string.album_deleted)
                }
            }
            if (message.isNotEmpty()) Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
}
