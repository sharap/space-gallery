package ai.recommend.spacegallery.ui.more

import ai.recommend.spacegallery.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreScreen(
    onFavorites: () -> Unit,
    onCleanup: () -> Unit,
    onHidden: () -> Unit,
    onSettings: () -> Unit,
) {
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.tab_more)) }) }) { padding ->
        Column(Modifier.padding(padding)) {
            Entry(Icons.Outlined.FavoriteBorder, stringResource(R.string.favorites), null, onFavorites)
            Entry(Icons.Outlined.CleaningServices, stringResource(R.string.cleanup_title), stringResource(R.string.cleanup_desc), onCleanup)
            Entry(Icons.Outlined.VisibilityOff, stringResource(R.string.hidden_title), stringResource(R.string.hidden_desc), onHidden)
            Entry(Icons.Outlined.Settings, stringResource(R.string.settings_title), null, onSettings)
        }
    }
}

@Composable
private fun Entry(icon: ImageVector, title: String, subtitle: String?, onClick: () -> Unit) {
    ListItem(
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
