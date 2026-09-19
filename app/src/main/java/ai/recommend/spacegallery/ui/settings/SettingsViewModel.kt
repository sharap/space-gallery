package ai.recommend.spacegallery.ui.settings

import ai.recommend.spacegallery.data.db.AnalysisDao
import ai.recommend.spacegallery.data.settings.GallerySettings
import ai.recommend.spacegallery.data.settings.SettingsRepository
import ai.recommend.spacegallery.ml.onnx.ModelId
import ai.recommend.spacegallery.ml.onnx.ModelProvider
import ai.recommend.spacegallery.search.EmbeddingIndex
import ai.recommend.spacegallery.search.smart.SmartAlbumBuilder
import kotlinx.coroutines.CoroutineScope
import ai.recommend.spacegallery.work.IndexingScheduler
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val settings: SettingsRepository,
    private val scheduler: IndexingScheduler,
    private val analysisDao: AnalysisDao,
    private val index: EmbeddingIndex,
    models: ModelProvider,
    private val smartAlbums: SmartAlbumBuilder,
    /** Скоуп приложения: пересчёт умных альбомов доживает до конца, даже если уйти с экрана. */
    private val appScope: CoroutineScope,
) : ViewModel() {

    val isRebuildingSmartAlbums: StateFlow<Boolean> = smartAlbums.isRebuilding

    /** Сохранить eps и пересобрать умные альбомы (~3 с). */
    fun setSmartAlbumEps(v: Float) {
        appScope.launch {
            settings.setSmartAlbumEps(v)
            smartAlbums.rebuild()
        }
    }

    val state: StateFlow<GallerySettings> = settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GallerySettings())

    /** Какие ONNX-модели найдены на устройстве. */
    val modelStatus: Map<ModelId, Boolean> = ModelId.entries.associateWith { models.isAvailable(it) }

    fun setHideSensitive(v: Boolean) = viewModelScope.launch { settings.setHideSensitive(v) }
    fun setSensitiveThreshold(v: Float) = viewModelScope.launch { settings.setSensitiveThreshold(v) }
    fun setSimilarityThreshold(v: Float) = viewModelScope.launch { settings.setSimilarityThreshold(v) }
    fun setOnlyWhileCharging(v: Boolean) = viewModelScope.launch {
        settings.setIndexOnlyWhileCharging(v)
        scheduler.requestIndexing(onlyWhileCharging = v, restart = true)
    }

    /** Сбросить результаты анализа и проиндексировать всё заново. */
    fun reindex() = viewModelScope.launch {
        analysisDao.clear()
        index.invalidate()
        scheduler.requestIndexing(onlyWhileCharging = state.value.indexOnlyWhileCharging, restart = true)
    }
}
