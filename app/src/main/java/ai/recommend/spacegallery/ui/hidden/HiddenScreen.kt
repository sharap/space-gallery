package ai.recommend.spacegallery.ui.hidden

import ai.recommend.spacegallery.R
import ai.recommend.spacegallery.domain.MediaItem
import ai.recommend.spacegallery.ui.appViewModelFactory
import ai.recommend.spacegallery.ui.components.BackTopBar
import ai.recommend.spacegallery.ui.components.CenteredMessage
import ai.recommend.spacegallery.ui.components.MediaGrid
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * TODO: закрыть экран биометрией (androidx.biometric) и FLAG_SECURE от скриншотов.
 */
@Composable
fun HiddenScreen(
    onBack: () -> Unit,
    onOpen: (MediaItem) -> Unit,
    viewModel: HiddenViewModel = viewModel(factory = appViewModelFactory { c, _ -> HiddenViewModel(c.mediaRepository) }),
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    var revealed by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            BackTopBar(stringResource(R.string.hidden_title), onBack) {
                IconButton(onClick = { revealed = !revealed }) {
                    Icon(
                        if (revealed) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = stringResource(R.string.hidden_toggle_blur),
                    )
                }
            }
        },
    ) { padding ->
        val list = items
        when {
            list == null -> CenteredMessage(stringResource(R.string.loading), Modifier.padding(padding), loading = true)
            list.isEmpty() -> CenteredMessage(stringResource(R.string.hidden_empty), Modifier.padding(padding))
            else -> MediaGrid(
                list,
                onClick = onOpen,
                modifier = Modifier.padding(padding),
                blurred = { !revealed },
            )
        }
    }
}
