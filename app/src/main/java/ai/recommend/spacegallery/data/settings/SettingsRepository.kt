package ai.recommend.spacegallery.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
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
) {
    fun columns(kind: GridKind): Int = when (kind) {
        GridKind.MEDIA -> gridColumns
        GridKind.ALBUMS -> albumGridColumns
    }
}

/** Какая сетка: у фото и у альбомов масштаб независимый. */
enum class GridKind { MEDIA, ALBUMS }

/** Допустимые размеры сетки — шаги щипка. */
val GRID_COLUMN_LEVELS = listOf(2, 3, 4, 5, 7)

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    val settings: Flow<GallerySettings> = context.dataStore.data.map { p ->
        val d = GallerySettings()
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
        )
    }

    suspend fun current(): GallerySettings = settings.first()

    suspend fun setHideSensitive(value: Boolean) = context.dataStore.edit { it[HIDE_SENSITIVE] = value }
    suspend fun setSensitiveThreshold(value: Float) = context.dataStore.edit { it[SENSITIVE_THRESHOLD] = value }
    suspend fun setSimilarityThreshold(value: Float) = context.dataStore.edit { it[SIMILARITY_THRESHOLD] = value }
    suspend fun setIndexOnlyWhileCharging(value: Boolean) = context.dataStore.edit { it[ONLY_CHARGING] = value }
    suspend fun setGridColumns(kind: GridKind, value: Int) = context.dataStore.edit {
        it[if (kind == GridKind.MEDIA) GRID_COLUMNS else ALBUM_GRID_COLUMNS] = value
    }

    private companion object {
        val HIDE_SENSITIVE = booleanPreferencesKey("hide_sensitive")
        val SENSITIVE_THRESHOLD = floatPreferencesKey("sensitive_threshold")
        val SIMILARITY_THRESHOLD = floatPreferencesKey("similarity_threshold")
        val ONLY_CHARGING = booleanPreferencesKey("index_only_charging")
        val GRID_COLUMNS = intPreferencesKey("grid_columns")
        val ALBUM_GRID_COLUMNS = intPreferencesKey("album_grid_columns")
    }
}
