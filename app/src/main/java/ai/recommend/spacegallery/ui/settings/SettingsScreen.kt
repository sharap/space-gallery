package ai.recommend.spacegallery.ui.settings

import ai.recommend.spacegallery.R
import ai.recommend.spacegallery.ui.appViewModelFactory
import ai.recommend.spacegallery.ui.components.BackTopBar
import ai.recommend.spacegallery.data.settings.DEFAULT_FACE_EPS
import ai.recommend.spacegallery.data.settings.DEFAULT_SMART_ALBUM_EPS
import ai.recommend.spacegallery.data.settings.FACE_EPS_RANGE
import ai.recommend.spacegallery.data.settings.SMART_ALBUM_EPS_RANGE
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import java.util.Locale
import kotlin.math.abs
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
            SettingsViewModel(
                c.settings,
                c.indexingScheduler,
                c.database.analysisDao(),
                c.embeddingIndex,
                c.models,
                c.smartAlbumBuilder,
                c.peopleBuilder,
                c.people,
                c.appScope,
            )
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

            HorizontalDivider()
            SectionHeader(stringResource(R.string.settings_section_smart_albums))
            val rebuilding by viewModel.isRebuildingSmartAlbums.collectAsStateWithLifecycle()
            SliderSetting(
                title = stringResource(R.string.settings_smart_eps),
                value = s.smartAlbumEps,
                range = SMART_ALBUM_EPS_RANGE,
                steps = 11, // шаг 0.01
                format = { String.format(Locale.getDefault(), "%.2f", it) },
                description = stringResource(R.string.settings_smart_eps_desc),
                trailing = { if (rebuilding) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) },
                onChange = viewModel::setSmartAlbumEps,
            )
            TextButton(
                onClick = { viewModel.setSmartAlbumEps(DEFAULT_SMART_ALBUM_EPS) },
                enabled = abs(s.smartAlbumEps - DEFAULT_SMART_ALBUM_EPS) > 0.001f,
                modifier = Modifier.padding(horizontal = 8.dp),
            ) { Text(stringResource(R.string.settings_reset_default)) }

            HorizontalDivider()
            SectionHeader(stringResource(R.string.people_title))
            val rebuildingPeople by viewModel.isRebuildingPeople.collectAsStateWithLifecycle()
            SliderSetting(
                title = stringResource(R.string.settings_face_eps),
                value = s.faceEps,
                range = FACE_EPS_RANGE,
                steps = 24, // шаг 0.01
                format = { String.format(Locale.getDefault(), "%.2f", it) },
                description = stringResource(R.string.settings_face_eps_desc),
                trailing = { if (rebuildingPeople) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) },
                onChange = viewModel::setFaceEps,
            )
            TextButton(
                onClick = { viewModel.setFaceEps(DEFAULT_FACE_EPS) },
                enabled = abs(s.faceEps - DEFAULT_FACE_EPS) > 0.001f,
                modifier = Modifier.padding(horizontal = 8.dp),
            ) { Text(stringResource(R.string.settings_reset_default)) }
            var confirmReset by remember { mutableStateOf(false) }
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_reset_people_edits)) },
                supportingContent = { Text(stringResource(R.string.settings_reset_people_edits_desc)) },
                modifier = Modifier.clickable { confirmReset = true },
            )
            if (confirmReset) {
                AlertDialog(
                    onDismissRequest = { confirmReset = false },
                    title = { Text(stringResource(R.string.settings_reset_people_edits)) },
                    text = { Text(stringResource(R.string.settings_reset_people_edits_desc)) },
                    confirmButton = {
                        TextButton(onClick = {
                            confirmReset = false
                            viewModel.resetPeopleEdits()
                        }) { Text(stringResource(R.string.action_reset)) }
                    },
                    dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.action_cancel)) } },
                )
            }

            HorizontalDivider()
            SectionHeader(stringResource(R.string.settings_section_indexing))
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

/**
 * Ползунок настройки: пока тянут — меняется только локальное значение, сохраняется при отпускании
 * (не пишем в DataStore на каждый кадр и не запускаем дорогие пересчёты по ходу движения).
 */
@Composable
private fun SliderSetting(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    steps: Int = 0,
    format: (Float) -> String = { "${(it * 100).roundToInt()}%" },
    description: String? = null,
    trailing: @Composable () -> Unit = {},
) {
    // Сбрасывается на сохранённое значение, когда оно приходит из DataStore.
    var local by remember(value) { mutableFloatStateOf(value) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$title: ${format(local)}", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            trailing()
        }
        if (description != null) {
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { if (local != value) onChange(local) },
            valueRange = range,
            steps = steps,
        )
    }
}
