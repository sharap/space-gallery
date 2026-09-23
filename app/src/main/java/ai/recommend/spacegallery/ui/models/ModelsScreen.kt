package ai.recommend.spacegallery.ui.models

import ai.recommend.spacegallery.R
import ai.recommend.spacegallery.data.settings.SettingsRepository
import ai.recommend.spacegallery.ml.onnx.ModelCatalog
import ai.recommend.spacegallery.ml.onnx.ModelFile
import ai.recommend.spacegallery.ml.onnx.ModelFileState
import ai.recommend.spacegallery.ui.appViewModelFactory
import ai.recommend.spacegallery.ui.components.BackTopBar
import ai.recommend.spacegallery.work.ModelDownloadState
import ai.recommend.spacegallery.work.ModelDownloadWorker
import ai.recommend.spacegallery.work.ModelDownloads
import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ModelsViewModel(
    private val catalog: ModelCatalog,
    private val downloads: ModelDownloads,
    private val settings: SettingsRepository,
) : ViewModel() {
    val isConfigured: Boolean = downloads.isConfigured

    val download: StateFlow<ModelDownloadState> = downloads.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ModelDownloadState.Idle)

    /** Состояние файлов пересчитывается при каждой смене состояния загрузки. */
    val files: StateFlow<List<Pair<ModelFile, ModelFileState>>> = download
        .map { catalog.manifest.files.map { it to catalog.state(it) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val wifiOnly: StateFlow<Boolean> = settings.settings.map { it.modelsWifiOnly }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun start() = downloads.start(wifiOnly.value)

    fun cancel() = downloads.cancel()

    fun setWifiOnly(value: Boolean) = viewModelScope.launch {
        settings.setModelsWifiOnly(value)
        // Идущая загрузка перезапускается с новым условием сети (докачка продолжится).
        if (download.value !is ModelDownloadState.Idle && download.value !is ModelDownloadState.Failed) downloads.start(value)
    }
}

/** «AI-модели»: что установлено, скачать недостающее, прогресс и «только по Wi-Fi». */
@Composable
fun ModelsScreen(
    onBack: () -> Unit,
    viewModel: ModelsViewModel = viewModel(
        factory = appViewModelFactory { c, _ -> ModelsViewModel(c.modelCatalog, c.modelDownloads, c.settings) },
    ),
) {
    val context = LocalContext.current
    val download by viewModel.download.collectAsStateWithLifecycle()
    val files by viewModel.files.collectAsStateWithLifecycle()
    val wifiOnly by viewModel.wifiOnly.collectAsStateWithLifecycle()
    val missing = files.filter { it.second == ModelFileState.MISSING }
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)

    Scaffold(topBar = { BackTopBar(stringResource(R.string.models_title), onBack) }) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            item {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.models_desc), style = MaterialTheme.typography.bodyMedium)
                    when (val d = download) {
                        is ModelDownloadState.Running -> {
                            LinearProgressIndicator(
                                progress = { if (d.total > 0) d.done.toFloat() / d.total else 0f },
                                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                            )
                            Text(
                                stringResource(R.string.models_running, size(d.done), size(d.total), d.file.orEmpty()),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                            OutlinedButton(onClick = viewModel::cancel, modifier = Modifier.padding(top = 8.dp)) {
                                Text(stringResource(R.string.action_cancel))
                            }
                        }
                        ModelDownloadState.Waiting -> {
                            Text(
                                stringResource(if (wifiOnly) R.string.models_waiting_wifi else R.string.models_waiting),
                                modifier = Modifier.padding(top = 16.dp),
                            )
                            OutlinedButton(onClick = viewModel::cancel, modifier = Modifier.padding(top = 8.dp)) {
                                Text(stringResource(R.string.action_cancel))
                            }
                        }
                        is ModelDownloadState.Failed, ModelDownloadState.Idle -> {
                            if (d is ModelDownloadState.Failed) {
                                Text(
                                    stringResource(
                                        when (d.error) {
                                            ModelDownloadWorker.ERROR_CHECKSUM -> R.string.models_error_checksum
                                            ModelDownloadWorker.ERROR_NO_SOURCE -> R.string.models_not_configured
                                            else -> R.string.models_error_network
                                        }
                                    ),
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(top = 16.dp),
                                )
                            }
                            when {
                                missing.isEmpty() -> Text(stringResource(R.string.models_all_installed), modifier = Modifier.padding(top = 16.dp))
                                !viewModel.isConfigured -> Text(
                                    stringResource(R.string.models_not_configured),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 16.dp),
                                )
                                else -> Button(onClick = viewModel::start, modifier = Modifier.padding(top = 16.dp)) {
                                    Text(stringResource(R.string.models_download, size(missing.sumOf { it.first.size })))
                                }
                            }
                        }
                    }
                }
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.models_wifi_only)) },
                    trailingContent = { Switch(checked = wifiOnly, onCheckedChange = viewModel::setWifiOnly) },
                )
            }
            files.groupBy { it.first.group }.forEach { (group, entries) ->
                item(key = group) {
                    Text(
                        stringResource(groupTitle(group)),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                    )
                }
                items(entries, key = { it.first.path }) { (file, state) ->
                    ListItem(
                        leadingContent = {
                            Icon(
                                when (state) {
                                    ModelFileState.DOWNLOADED -> Icons.Filled.CheckCircle
                                    ModelFileState.BUNDLED -> Icons.Outlined.Inventory2
                                    ModelFileState.MISSING -> Icons.Outlined.CloudDownload
                                },
                                contentDescription = null,
                                tint = if (state == ModelFileState.MISSING) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
                            )
                        },
                        headlineContent = { Text(file.path) },
                        supportingContent = {
                            Text(
                                size(file.size) + " · " + stringResource(
                                    when (state) {
                                        ModelFileState.DOWNLOADED -> R.string.models_state_downloaded
                                        ModelFileState.BUNDLED -> R.string.models_state_bundled
                                        ModelFileState.MISSING -> R.string.models_state_missing
                                    }
                                )
                            )
                        },
                    )
                }
            }
        }
    }
}

private fun groupTitle(group: String): Int = when (group) {
    "search" -> R.string.models_group_search
    "russian" -> R.string.models_group_russian
    "sensitive" -> R.string.models_group_sensitive
    "faces" -> R.string.models_group_faces
    "faces_hq" -> R.string.models_group_faces_hq
    "text" -> R.string.models_group_text
    "text_ko" -> R.string.models_group_text_ko
    else -> R.string.models_group_other
}
