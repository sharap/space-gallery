package ai.recommend.spacegallery.ui.settings

import ai.recommend.spacegallery.data.db.AnalysisDao
import ai.recommend.spacegallery.data.settings.GallerySettings
import ai.recommend.spacegallery.ml.onnx.ModelId
import ai.recommend.spacegallery.ml.onnx.ModelProvider
import ai.recommend.spacegallery.data.settings.FaceModel
import ai.recommend.spacegallery.data.settings.TextLanguage
import ai.recommend.spacegallery.data.settings.SettingsRepository
import ai.recommend.spacegallery.search.EmbeddingIndex
import ai.recommend.spacegallery.search.people.PeopleBuilder
import ai.recommend.spacegallery.search.people.PeopleRepository
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
    private val models: ModelProvider,
    private val scheduler: IndexingScheduler,
    private val analysisDao: AnalysisDao,
    private val index: EmbeddingIndex,
    private val smartAlbums: SmartAlbumBuilder,
    private val people: PeopleBuilder,
    private val peopleRepository: PeopleRepository,
    /** Скоуп приложения: пересчёт умных альбомов доживает до конца, даже если уйти с экрана. */
    private val appScope: CoroutineScope,
) : ViewModel() {

    val isRebuildingSmartAlbums: StateFlow<Boolean> = smartAlbums.isRebuilding
    val isRebuildingPeople: StateFlow<Boolean> = people.isRebuilding

    /** Сбросить все ручные правки людей (имена остаются). */
    fun resetPeopleEdits() {
        appScope.launch { peopleRepository.resetAllManualEdits() }
    }

    /** Сохранить радиус для лиц и пересобрать людей (имена сохраняются). */
    /** Модель лиц доступна (файл на устройстве): иначе её нельзя выбрать. */
    fun isModelAvailable(model: FaceModel): Boolean = models.isAvailable(model.modelId)

    fun isModelAvailable(id: ModelId): Boolean = models.isAvailable(id)

    /** Смена языков: текст на снимках будет прочитан заново (см. TextIndexer). */
    fun setTextLanguage(language: TextLanguage, enabled: Boolean) = viewModelScope.launch {
        val current = settings.current().textLanguages
        val next = if (enabled) current + language else current - language
        if (next.isEmpty()) return@launch
        settings.setTextLanguages(next)
        scheduler.ensureIndexingNow(settings.current().indexOnlyWhileCharging)
    }

    /** Смена модели: лица пересчитаются новой моделью, люди пересоберутся (см. MediaIndexWorker). */
    /** Полный сброс людей: имена и все ручные правки. */
    fun resetPeopleCompletely() = appScope.launch { peopleRepository.resetPeopleCompletely() }

    fun setFaceModel(model: FaceModel) = viewModelScope.launch {
        settings.setFaceModel(model)
        scheduler.ensureIndexingNow(settings.current().indexOnlyWhileCharging)
    }

    fun setFaceEps(v: Float) {
        appScope.launch {
            settings.setFaceEps(v)
            people.rebuild()
        }
    }

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

    fun setHideSensitive(v: Boolean) = viewModelScope.launch { settings.setHideSensitive(v) }
    fun setSensitiveThreshold(v: Float) = viewModelScope.launch { settings.setSensitiveThreshold(v) }
    fun setSimilarityThreshold(v: Float) = viewModelScope.launch { settings.setSimilarityThreshold(v) }
    fun setOnlyWhileCharging(v: Boolean) = viewModelScope.launch {
        settings.setIndexOnlyWhileCharging(v)
        scheduler.requestIndexing(onlyWhileCharging = v, restart = true)
    }

    /** Темп применяется со следующего прохода: текущий уже создал сессии со своим числом потоков. */
    fun setQuietIndexing(v: Boolean) = viewModelScope.launch { settings.setQuietIndexing(v) }

    /** Сбросить результаты анализа и проиндексировать всё заново. */
    fun reindex() = viewModelScope.launch {
        analysisDao.clear()
        index.invalidate()
        scheduler.requestIndexing(onlyWhileCharging = state.value.indexOnlyWhileCharging, restart = true)
    }
}
