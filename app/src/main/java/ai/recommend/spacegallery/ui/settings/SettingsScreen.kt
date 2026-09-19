package ai.recommend.spacegallery.ui.settings

import ai.recommend.spacegallery.R
import ai.recommend.spacegallery.ui.appViewModelFactory
import ai.recommend.spacegallery.ui.components.BackTopBar
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(
        factory = appViewModelFactory { c, _ ->
            SettingsViewModel(c.settings, c.indexingScheduler, c.database.analysisDao(), c.embeddingIndex, c.models)
        },
    ),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(topBar = { BackTopBar(stringResource(R.string.settings_title), onBack) }) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            SectionHeader(stringResource(R.string.settings_section_privacy))
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_hide_sensitive)) },
                supportingContent = { Text(stringResource(R.string.settings_hide_sensitive_desc)) },
                trailingContent = { Switch(checked = s.hideSensitive, onCheckedChange = viewModel::setHideSensitive) },
            )
            SliderSetting(
                title = stringResource(R.string.settings_sensitive_threshold),
                value = s.sensitiveThreshold,
                range = 0.3f..0.95f,
                onChange = viewModel::setSensitiveThreshold,
            )

            HorizontalDivider()
            SectionHeader(stringResource(R.string.settings_section_ai))
            SliderSetting(
                title = stringResource(R.string.settings_similarity_threshold),
                value = s.similarityThreshold,
                range = 0.5f..0.95f,
                onChange = viewModel::setSimilarityThreshold,
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_only_charging)) },
                trailingContent = { Switch(checked = s.indexOnlyWhileCharging, onCheckedChange = viewModel::setOnlyWhileCharging) },
            )
            viewModel.modelStatus.forEach { (model, available) ->
                ListItem(
                    headlineContent = { Text(model.fileName) },
                    supportingContent = {
                        Text(stringResource(if (available) R.string.settings_model_ok else R.string.settings_model_missing))
                    },
                )
            }
            OutlinedButton(onClick = viewModel::reindex, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text(stringResource(R.string.settings_reindex))
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun SliderSetting(title: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("$title: ${(value * 100).roundToInt()}%", style = MaterialTheme.typography.bodyLarge)
        // TODO: сохранять значение по onValueChangeFinished, чтобы не писать в DataStore на каждый кадр.
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}
