package ai.recommend.spacegallery.domain

import android.net.Uri

enum class MediaType { IMAGE, VIDEO }

data class MediaItem(
    val id: Long,
    val uri: Uri,
    val type: MediaType,
    val mimeType: String,
    val displayName: String,
    /** Время съёмки (ms), при отсутствии — время добавления. */
    val dateTaken: Long,
    val dateModified: Long,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val albumId: Long,
    val albumName: String,
    val isFavorite: Boolean,
    val isHiddenByUser: Boolean,
    /** Вероятность деликатного контента 0..1; null — ещё не проанализировано. */
    val sensitiveScore: Float?,
)

data class Album(
    val id: Long,
    val name: String,
    /** Папка альбома, например `DCIM/Camera/`; пусто на Android 9. */
    val relativePath: String,
    val coverUri: Uri,
    val itemCount: Int,
)

/** Результат AI-поиска / подбора похожих. */
data class ScoredMedia(val item: MediaItem, val score: Float)

/** Группа дубликатов или почти-дубликатов. [suggestedKeep] — лучший кандидат на сохранение. */
data class DuplicateGroup(
    val items: List<MediaItem>,
    val suggestedKeep: MediaItem,
)

/** Этап фоновой индексации (подпись в уведомлении и в ленте). */
enum class IndexingPhase { ANALYSIS, GROUPING, LOCATION, QUALITY, FACES }

data class IndexingProgress(
    val isRunning: Boolean,
    val processed: Int = 0,
    val total: Int = 0,
    val phase: IndexingPhase = IndexingPhase.ANALYSIS,
)
