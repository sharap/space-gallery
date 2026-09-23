package ai.recommend.spacegallery.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Зеркало записи MediaStore + локальные флаги приложения. */
@Entity(
    tableName = "media",
    indices = [Index("bucketId"), Index("dateTaken")],
)
data class MediaEntity(
    /** MediaStore._ID */
    @PrimaryKey val id: Long,
    val uri: String,
    /** 0 — изображение, 1 — видео (см. [ai.recommend.spacegallery.domain.MediaType]). */
    val mediaType: Int,
    val mimeType: String,
    val displayName: String,
    val dateTaken: Long,
    val dateModified: Long,
    val size: Long,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val bucketId: Long,
    val bucketName: String,
    /** Папка файла относительно тома, например `DCIM/Camera/` (MediaStore.RELATIVE_PATH, API 29+). */
    @ColumnInfo(defaultValue = "") val relativePath: String = "",
    @ColumnInfo(defaultValue = "0") val isFavorite: Boolean = false,
    @ColumnInfo(defaultValue = "0") val isHiddenByUser: Boolean = false,
)

/**
 * Результаты локального AI-анализа одного медиафайла.
 * Любое поле может быть null, если соответствующая модель отсутствует на устройстве.
 */
@Entity(
    tableName = "media_analysis",
    foreignKeys = [
        ForeignKey(
            entity = MediaEntity::class,
            parentColumns = ["id"],
            childColumns = ["mediaId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class MediaAnalysisEntity(
    @PrimaryKey val mediaId: Long,
    /** dateModified файла на момент анализа — для инвалидации при изменении. */
    val sourceModified: Long,
    val pipelineVersion: Int,
    /** L2-нормализованный вектор CLIP (float32 little-endian). */
    val embedding: ByteArray?,
    /** 64-битный dHash для поиска (почти) точных дубликатов. */
    val perceptualHash: Long?,
    /** Вероятность NSFW 0..1. */
    val sensitiveScore: Float?,
    /** Файл не удалось декодировать — не пытаемся повторно, пока он не изменится. */
    @ColumnInfo(defaultValue = "0") val isUnreadable: Boolean = false,
    /**
     * Версия поиска лиц, которой обработан файл (0 — ещё не искали). Отдельный проход:
     * лица находятся и на уже проиндексированных фото без пересчёта CLIP/NSFW.
     * При переанализе файла строка перезаписывается и поиск лиц повторяется.
     */
    @ColumnInfo(defaultValue = "0") val facesVersion: Int = 0,
    /** Резкость самого чёткого участка кадра (дисперсия лапласиана), null — не оценивалась. */
    val sharpness: Float? = null,
    /** Средняя яркость 0..1. */
    val brightness: Float? = null,
    /** Версия оценки качества (отдельный быстрый проход, как и поиск лиц). */
    @ColumnInfo(defaultValue = "0") val qualityVersion: Int = 0,
    /** Версия распознавания текста и поиска кодов (0 — ещё не искали). */
    @ColumnInfo(defaultValue = "0") val textVersion: Int = 0,
    /** Координаты съёмки из EXIF/метаданных видео; null — нет геометки. */
    val latitude: Double? = null,
    val longitude: Double? = null,
    /** Версия чтения геометки (0 — ещё не читали). */
    @ColumnInfo(defaultValue = "0") val locationVersion: Int = 0,
) {
    override fun equals(other: Any?): Boolean =
        other is MediaAnalysisEntity && other.mediaId == mediaId &&
            other.sourceModified == sourceModified && other.pipelineVersion == pipelineVersion

    override fun hashCode(): Int = mediaId.hashCode()
}

/** media + оценка деликатности из LEFT JOIN. */
data class MediaWithAnalysis(
    @Embedded val media: MediaEntity,
    val sensitiveScore: Float?,
)

data class EmbeddingRow(val mediaId: Long, val embedding: ByteArray)

data class HashRow(val mediaId: Long, val perceptualHash: Long)

data class LocationRow(val mediaId: Long, val latitude: Double, val longitude: Double)

data class QualityRow(val mediaId: Long, val sharpness: Float, val brightness: Float)

data class AlbumRow(
    val bucketId: Long,
    val bucketName: String,
    val relativePath: String,
    val coverUri: String,
    val itemCount: Int,
)
