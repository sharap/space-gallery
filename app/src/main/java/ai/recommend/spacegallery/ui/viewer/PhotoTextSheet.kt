package ai.recommend.spacegallery.ui.viewer

import ai.recommend.spacegallery.R
import ai.recommend.spacegallery.search.text.PhotoText
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.QrCode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * Текст, распознанный на снимке, и найденные коды: строку можно скопировать нажатием,
 * ссылку из QR-кода — открыть.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoTextSheet(content: PhotoText, onDismiss: () -> Unit) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Row(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(stringResource(R.string.photo_text_title), style = MaterialTheme.typography.titleMedium)
            if (content.lines.isNotEmpty()) {
                TextButton(onClick = { copy(context, content.text) }) {
                    Text(stringResource(R.string.photo_text_copy_all))
                }
            }
        }
        LazyColumn {
            items(content.codes, key = { "code${it.id}" }) { code ->
                val link = code.value.startsWith("http://") || code.value.startsWith("https://")
                ListItem(
                    leadingContent = { Icon(Icons.Outlined.QrCode, contentDescription = null) },
                    headlineContent = { Text(code.value) },
                    supportingContent = { Text(code.format) },
                    trailingContent = {
                        if (link) {
                            TextButton(onClick = { open(context, code.value) }) {
                                Icon(Icons.Outlined.OpenInNew, contentDescription = stringResource(R.string.photo_text_open))
                            }
                        }
                    },
                    modifier = Modifier.clickable { copy(context, code.value) },
                )
            }
            items(content.lines, key = { "line${it.id}" }) { line ->
                ListItem(
                    headlineContent = { Text(line.text) },
                    trailingContent = { Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.action_copy)) },
                    modifier = Modifier.clickable { copy(context, line.text) },
                )
            }
            if (content.isEmpty) {
                item {
                    Text(
                        stringResource(R.string.photo_text_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
        }
    }
}

private fun copy(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.photo_text_title), text))
}

private fun open(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
        .onFailure { if (it !is ActivityNotFoundException) throw it }
}
