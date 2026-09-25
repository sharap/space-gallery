package ai.recommend.spacegallery.data.settings

import ai.recommend.spacegallery.ml.onnx.ModelGroup
import ai.recommend.spacegallery.ml.onnx.ModelId
import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
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
    /**
     * Всегда работать тихо: меньше потоков и паузы между кадрами. По умолчанию выключено —
     * темп выбирается сам (ночью на зарядке полный, днём тихий), см. [ai.recommend.spacegallery.work.IndexingPace].
     */
    val quietIndexing: Boolean = false,
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
    /** Какие группы моделей пользователь согласился скачать (ключи [ai.recommend.spacegallery.ml.onnx.ModelGroup]). */
    val modelGroups: Set<String> = ModelGroup.defaults(),
    /** Языки, которые распознаются на снимках. */
    val textLanguages: Set<TextLanguage> = setOf(TextLanguage.CYRILLIC),
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
/**
 * Номер версии векторов меняется и при смене модели, и при смене способа подготовки лица
 * (версии 5 и 6 — лицо вырезается из оригинала с повторным поиском ключевых точек).
 * Лица с другой версией пересчитываются фоном, сами лица заново не ищутся.
 */
enum class FaceModel(
    val modelId: ModelId,
    val embedVersion: Int,
    val defaultEps: Float,
    val epsRange: ClosedFloatingPointRange<Float>,
) {
    /**
     * MobileFaceNet, 13,6 МБ, ~25 мс на лицо. Радиус 0.75 (сходство 0.25) подобран на разметке
     * пользователя (2997 лиц у 59 человек, 2026-09-20): полнота 0.93 при том же числе ошибочных
     * склеек, что у точной модели; человек чаще делится на два куска (медиана 2 против 1).
     */
    FAST(ModelId.FACE_EMBED, embedVersion = 5, defaultEps = 0.75f, epsRange = 0.60f..0.85f),

    /**
     * ResNet50, 174 МБ, ~230 мс на лицо.
     *
     * Радиус 0.70 (сходство 0.30) подобран на разметке пользователя (1977 подтверждённых лиц у
     * 24 человек, 2026-09-20): полнота 0.98 при нуле ошибочных склеек между названными людьми;
     * прежний 0.60 (от SFace) терял 15% пар одного человека.
     */
    ACCURATE(ModelId.FACE_EMBED_HQ, embedVersion = 6, defaultEps = 0.70f, epsRange = 0.55f..0.85f),
}

/**
 * Язык распознавания текста на снимках. У PaddleOCR своя модель на группу языков; если выбрано
 * несколько, язык снимка определяется по уверенности на первых строках.
 */
enum class TextLanguage(val modelId: ModelId, val dictionary: String) {
    /** Русский, украинский, белорусский, болгарский и английский. */
    CYRILLIC(ModelId.TEXT_RECOGNIZE, "text_dict.txt"),
    KOREAN(ModelId.TEXT_RECOGNIZE_KO, "text_dict_ko.txt"),
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
            quietIndexing = p[QUIET_INDEXING] ?: d.quietIndexing,
            gridColumns = p[GRID_COLUMNS]?.takeIf { it in GRID_COLUMN_LEVELS } ?: d.gridColumns,
            // Пока сетку альбомов не меняли — как у фото (раньше настройка была общей).
            albumGridColumns = p[ALBUM_GRID_COLUMNS]?.takeIf { it in GRID_COLUMN_LEVELS }
                ?: p[GRID_COLUMNS]?.takeIf { it in GRID_COLUMN_LEVELS }
                ?: d.albumGridColumns,
            smartAlbumEps = p[SMART_EPS]?.coerceIn(SMART_ALBUM_EPS_RANGE) ?: d.smartAlbumEps,
            faceModel = model,
            faceEps = p[epsKeyOf(model)]?.coerceIn(model.epsRange) ?: model.defaultEps,
            modelsWifiOnly = p[MODELS_WIFI_ONLY] ?: d.modelsWifiOnly,
            // Обязательные группы добавляются всегда: без них приложение — просто галерея.
            modelGroups = (p[MODEL_GROUPS] ?: d.modelGroups) + ModelGroup.required,
            textLanguages = p[TEXT_LANGUAGES]
                ?.mapNotNullTo(LinkedHashSet()) { name -> TextLanguage.entries.firstOrNull { it.name == name } }
                ?.takeIf { it.isNotEmpty() }
                ?: d.textLanguages,
        )
    }

    suspend fun current(): GallerySettings = settings.first()

    suspend fun setHideSensitive(value: Boolean) = context.dataStore.edit { it[HIDE_SENSITIVE] = value }
    suspend fun setSensitiveThreshold(value: Float) = context.dataStore.edit { it[SENSITIVE_THRESHOLD] = value }
    suspend fun setSimilarityThreshold(value: Float) = context.dataStore.edit { it[SIMILARITY_THRESHOLD] = value }
    suspend fun setModelsWifiOnly(value: Boolean) = context.dataStore.edit { it[MODELS_WIFI_ONLY] = value }
    suspend fun setModelGroups(value: Set<String>) = context.dataStore.edit { it[MODEL_GROUPS] = value + ModelGroup.required }

    /** Добавить группу к выбранным — когда пользователь включает функцию, которой нужна модель. */
    suspend fun addModelGroup(group: ModelGroup) = context.dataStore.edit {
        it[MODEL_GROUPS] = (it[MODEL_GROUPS] ?: ModelGroup.defaults()) + ModelGroup.required + group.key
    }
    suspend fun setIndexOnlyWhileCharging(value: Boolean) = context.dataStore.edit { it[ONLY_CHARGING] = value }
    suspend fun setQuietIndexing(value: Boolean) = context.dataStore.edit { it[QUIET_INDEXING] = value }
    /** Радиус хранится отдельно для каждой модели: их шкалы сходства не совпадают. */
    suspend fun setFaceEps(value: Float) = context.dataStore.edit {
        val model = it[FACE_MODEL]?.let { name -> FaceModel.entries.firstOrNull { m -> m.name == name } } ?: FaceModel.FAST
        it[epsKeyOf(model)] = value.coerceIn(model.epsRange)
    }

    /** Смена языков: текст на снимках будет прочитан заново (см. TextIndexer). */
    suspend fun setTextLanguages(languages: Set<TextLanguage>) = context.dataStore.edit {
        it[TEXT_LANGUAGES] = languages.map { language -> language.name }.toSet()
    }

    /** Набор языков, которым читали текст в прошлый раз. */
    suspend fun textLanguagesSignature(): String = context.dataStore.data.first()[TEXT_SIGNATURE] ?: ""

    suspend fun setTextLanguagesSignature(value: String) = context.dataStore.edit { it[TEXT_SIGNATURE] = value }

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

    /**
     * До какого времени не просить foreground-сервис: на Android 15+ у типа «обработка медиа»
     * есть суточный лимит, после которого система убивает сервис, и индексация зацикливается.
     */
    suspend fun foregroundBlockedUntil(): Long = context.dataStore.data.first()[FGS_BLOCKED_UNTIL] ?: 0L

    suspend fun blockForeground(until: Long) = context.dataStore.edit { it[FGS_BLOCKED_UNTIL] = until }

    /** Версия алгоритма, которым последний раз группировали людей (0 — ещё не группировали). */
    suspend fun peopleAlgorithmVersion(): Int = context.dataStore.data.first()[PEOPLE_VERSION] ?: 0

    suspend fun setPeopleAlgorithmVersion(version: Int) = context.dataStore.edit { it[PEOPLE_VERSION] = version }

    /**
     * Какой версией правил пересмотрены людные кадры (см. FaceIndexer.CROWD_PASS_VERSION).
     * Отдельно от общей версии поиска лиц: правило изменилось только для групповых снимков,
     * и гонять всю медиатеку заново незачем.
     */
    suspend fun crowdPassVersion(): Int = context.dataStore.data.first()[CROWD_PASS_VERSION] ?: 0

    /** Докуда дошёл пересмотр: пройденные кадры не повторяются после перезапуска. */
    suspend fun crowdPassCursor(): Long = context.dataStore.data.first()[CROWD_PASS_CURSOR] ?: 0L

    suspend fun setCrowdPassCursor(value: Long) = context.dataStore.edit { it[CROWD_PASS_CURSOR] = value }

    /** Пересмотр людных кадров закончен — курсор больше не нужен. */
    suspend fun setCrowdPassDone(version: Int) = context.dataStore.edit {
        it[CROWD_PASS_VERSION] = version
        it[CROWD_PASS_CURSOR] = 0L
    }

    suspend fun markSmartAlbumsBuilt(at: Long) = context.dataStore.edit {
        it[SMART_BUILT_AT] = at
        it[SMART_PENDING] = 0
    }

    private companion object {
        val HIDE_SENSITIVE = booleanPreferencesKey("hide_sensitive")
        val SENSITIVE_THRESHOLD = floatPreferencesKey("sensitive_threshold")
        val SIMILARITY_THRESHOLD = floatPreferencesKey("similarity_threshold")
        val ONLY_CHARGING = booleanPreferencesKey("index_only_charging")
        val QUIET_INDEXING = booleanPreferencesKey("index_quiet")
        val MODELS_WIFI_ONLY = booleanPreferencesKey("models_wifi_only")
        val MODEL_GROUPS = stringSetPreferencesKey("model_groups")
        val FACE_MODEL = stringPreferencesKey("face_model")
        val FGS_BLOCKED_UNTIL = longPreferencesKey("foreground_blocked_until")
        val TEXT_LANGUAGES = stringSetPreferencesKey("text_languages")
        val TEXT_SIGNATURE = stringPreferencesKey("text_languages_signature")
        val GRID_COLUMNS = intPreferencesKey("grid_columns")
        val ALBUM_GRID_COLUMNS = intPreferencesKey("album_grid_columns")
        val SMART_EPS = floatPreferencesKey("smart_albums_eps")
        // Новый ключ: у DBSCAN (прежний face_eps) смысл числа был другой.
        val FACE_EPS = floatPreferencesKey("face_link_eps")
        val SMART_BUILT_AT = longPreferencesKey("smart_albums_built_at")
        val PEOPLE_VERSION = intPreferencesKey("people_algorithm_version")
        val SMART_PENDING = intPreferencesKey("smart_albums_pending_changes")
        val CROWD_PASS_VERSION = intPreferencesKey("crowd_pass_version")
        val CROWD_PASS_CURSOR = longPreferencesKey("crowd_pass_cursor")
    }
}
