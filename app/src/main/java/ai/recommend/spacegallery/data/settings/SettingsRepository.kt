package ai.recommend.spacegallery.data.settings

import ai.recommend.spacegallery.ml.onnx.ModelId
import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class GallerySettings(
    /** Скрывать деликатный контент из основной ленты. */
    val hideSensitive: Boolean = true,
    /** Порог NSFW-классификатора, выше которого контент считается деликатным. */
    val sensitiveThreshold: Float = 0.7f,
    /** Минимальная косинусная близость для «похожих». */
    val similarityThreshold: Float = 0.75f,
    /** Индексировать только на зарядке. */
    val indexOnlyWhileCharging: Boolean = false,
    /** Число столбцов сетки фото (меняется щипком), см. [GRID_COLUMN_LEVELS]. */
    val gridColumns: Int = 4,
    /** Число столбцов сетки альбомов — отдельная настройка. */
    val albumGridColumns: Int = 4,
    /** Радиус DBSCAN для умных альбомов (косинусное расстояние), см. [SMART_ALBUM_EPS_RANGE]. */
    val smartAlbumEps: Float = DEFAULT_SMART_ALBUM_EPS,
    /** Какой моделью считаются векторы лиц (у каждой свой радиус группировки). */
    val faceModel: FaceModel = FaceModel.FAST,
    /**
     * Радиус группировки лиц в людей: 1 − минимальное **среднее** сходство лиц двух групп
     * для объединения (средняя связь), см. [FaceModel.epsRange]. Своё значение у каждой модели.
     */
    val faceEps: Float = FaceModel.FAST.defaultEps,
    /** Скачивать AI-модели только по Wi-Fi (сотни мегабайт). */
    val modelsWifiOnly: Boolean = true,
) {
    fun columns(kind: GridKind): Int = when (kind) {
        GridKind.MEDIA -> gridColumns
        GridKind.ALBUMS -> albumGridColumns
    }
}

const val DEFAULT_SMART_ALBUM_EPS = 0.14f

/**
 * Разумные пределы eps: ниже 0.08 групп почти нет, выше 0.20 DBSCAN склеивает большую часть
 * медиатеки в один кластер (проверено на реальных CLIP-эмбеддингах).
 */
val SMART_ALBUM_EPS_RANGE = 0.08f..0.20f

/** Радиус группировки лиц у каждой модели свой — см. [FaceModel]. */
const val DEFAULT_FACE_EPS = 0.70f

/**
 * Модель распознавания лиц. Векторы разных моделей несравнимы, поэтому у каждой свой номер
 * версии (лица пересчитываются при смене) и свой радиус группировки.
 *
 * Замеры на реальной медиатеке (448 лиц, 2026-09-20): при пороге, отсекающем 99% пар
 * «разные люди», [FAST] теряет 5% пар «тот же человек», [ACCURATE] — 1%.
 */
enum class FaceModel(
    val modelId: ModelId,
    val embedVersion: Int,
    val defaultEps: Float,
    val epsRange: ClosedFloatingPointRange<Float>,
) {
    /** MobileFaceNet, 13,6 МБ, ~25 мс на лицо. */
    FAST(ModelId.FACE_EMBED, embedVersion = 4, defaultEps = 0.65f, epsRange = 0.50f..0.80f),

    /**
     * ResNet50, 174 МБ, ~230 мс на лицо. Версия 2 — ею помечены векторы, посчитанные этой же
     * моделью до появления выбора: при обновлении их не нужно считать заново.
     *
     * Радиус 0.70 (сходство 0.30) подобран на разметке пользователя (1977 подтверждённых лиц у
     * 24 человек, 2026-09-20): полнота 0.98 при нуле ошибочных склеек между названными людьми;
     * прежний 0.60 (от SFace) терял 15% пар одного человека.
     */
    ACCURATE(ModelId.FACE_EMBED_HQ, embedVersion = 2, defaultEps = 0.70f, epsRange = 0.55f..0.85f),
}

/** Какая сетка: у фото и у альбомов масштаб независимый. */
enum class GridKind { MEDIA, ALBUMS }

/** Допустимые размеры сетки — шаги щипка. */
val GRID_COLUMN_LEVELS = listOf(2, 3, 4, 5, 7)

private fun epsKeyOf(model: FaceModel) =
    if (model == FaceModel.FAST) floatPreferencesKey("face_link_eps") else floatPreferencesKey("face_link_eps_hq")

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    val settings: Flow<GallerySettings> = context.dataStore.data.map { p ->
        val d = GallerySettings()
        val model = p[FACE_MODEL]?.let { name -> FaceModel.entries.firstOrNull { it.name == name } } ?: d.faceModel
        GallerySettings(
            hideSensitive = p[HIDE_SENSITIVE] ?: d.hideSensitive,
            sensitiveThreshold = p[SENSITIVE_THRESHOLD] ?: d.sensitiveThreshold,
            similarityThreshold = p[SIMILARITY_THRESHOLD] ?: d.similarityThreshold,
            indexOnlyWhileCharging = p[ONLY_CHARGING] ?: d.indexOnlyWhileCharging,
            gridColumns = p[GRID_COLUMNS]?.takeIf { it in GRID_COLUMN_LEVELS } ?: d.gridColumns,
            // Пока сетку альбомов не меняли — как у фото (раньше настройка была общей).
            albumGridColumns = p[ALBUM_GRID_COLUMNS]?.takeIf { it in GRID_COLUMN_LEVELS }
                ?: p[GRID_COLUMNS]?.takeIf { it in GRID_COLUMN_LEVELS }
                ?: d.albumGridColumns,
            smartAlbumEps = p[SMART_EPS]?.coerceIn(SMART_ALBUM_EPS_RANGE) ?: d.smartAlbumEps,
            faceModel = model,
            faceEps = p[epsKeyOf(model)]?.coerceIn(model.epsRange) ?: model.defaultEps,
            modelsWifiOnly = p[MODELS_WIFI_ONLY] ?: d.modelsWifiOnly,
        )
    }

    suspend fun current(): GallerySettings = settings.first()

    suspend fun setHideSensitive(value: Boolean) = context.dataStore.edit { it[HIDE_SENSITIVE] = value }
    suspend fun setSensitiveThreshold(value: Float) = context.dataStore.edit { it[SENSITIVE_THRESHOLD] = value }
    suspend fun setSimilarityThreshold(value: Float) = context.dataStore.edit { it[SIMILARITY_THRESHOLD] = value }
    suspend fun setModelsWifiOnly(value: Boolean) = context.dataStore.edit { it[MODELS_WIFI_ONLY] = value }
    suspend fun setIndexOnlyWhileCharging(value: Boolean) = context.dataStore.edit { it[ONLY_CHARGING] = value }
    /** Радиус хранится отдельно для каждой модели: их шкалы сходства не совпадают. */
    suspend fun setFaceEps(value: Float) = context.dataStore.edit {
        val model = it[FACE_MODEL]?.let { name -> FaceModel.entries.firstOrNull { m -> m.name == name } } ?: FaceModel.FAST
        it[epsKeyOf(model)] = value.coerceIn(model.epsRange)
    }

    /** Смена модели: лица будут пересчитаны новой моделью (FaceReembedder), люди пересобраны. */
    suspend fun setFaceModel(model: FaceModel) = context.dataStore.edit { it[FACE_MODEL] = model.name }
    suspend fun setSmartAlbumEps(value: Float) = context.dataStore.edit { it[SMART_EPS] = value.coerceIn(SMART_ALBUM_EPS_RANGE) }
    suspend fun setGridColumns(kind: GridKind, value: Int) = context.dataStore.edit {
        it[if (kind == GridKind.MEDIA) GRID_COLUMNS else ALBUM_GRID_COLUMNS] = value
    }

    // --- Служебное состояние (не настройки пользователя) ---

    /** Когда последний раз пересчитывались умные альбомы и сколько файлов изменилось с тех пор. */
    suspend fun smartAlbumState(): Pair<Long, Int> = context.dataStore.data.first().let {
        (it[SMART_BUILT_AT] ?: 0L) to (it[SMART_PENDING] ?: 0)
    }

    suspend fun addSmartAlbumPendingChanges(count: Int) = context.dataStore.edit {
        it[SMART_PENDING] = (it[SMART_PENDING] ?: 0) + count
    }

    /** Версия алгоритма, которым последний раз группировали людей (0 — ещё не группировали). */
    suspend fun peopleAlgorithmVersion(): Int = context.dataStore.data.first()[PEOPLE_VERSION] ?: 0

    suspend fun setPeopleAlgorithmVersion(version: Int) = context.dataStore.edit { it[PEOPLE_VERSION] = version }

    suspend fun markSmartAlbumsBuilt(at: Long) = context.dataStore.edit {
        it[SMART_BUILT_AT] = at
        it[SMART_PENDING] = 0
    }

    private companion object {
        val HIDE_SENSITIVE = booleanPreferencesKey("hide_sensitive")
        val SENSITIVE_THRESHOLD = floatPreferencesKey("sensitive_threshold")
        val SIMILARITY_THRESHOLD = floatPreferencesKey("similarity_threshold")
        val ONLY_CHARGING = booleanPreferencesKey("index_only_charging")
        val MODELS_WIFI_ONLY = booleanPreferencesKey("models_wifi_only")
        val FACE_MODEL = stringPreferencesKey("face_model")
        val GRID_COLUMNS = intPreferencesKey("grid_columns")
        val ALBUM_GRID_COLUMNS = intPreferencesKey("album_grid_columns")
        val SMART_EPS = floatPreferencesKey("smart_albums_eps")
        // Новый ключ: у DBSCAN (прежний face_eps) смысл числа был другой.
        val FACE_EPS = floatPreferencesKey("face_link_eps")
        val SMART_BUILT_AT = longPreferencesKey("smart_albums_built_at")
        val PEOPLE_VERSION = intPreferencesKey("people_algorithm_version")
        val SMART_PENDING = intPreferencesKey("smart_albums_pending_changes")
    }
}
