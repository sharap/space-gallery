package ai.recommend.spacegallery.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaDao {

    /**
     * Основная лента. Деликатный контент отфильтровывается, если [showSensitive] = false
     * и оценка модели выше порога.
     */
    @Query(
        """
        SELECT m.*, a.sensitiveScore AS sensitiveScore FROM media m
        LEFT JOIN media_analysis a ON a.mediaId = m.id
        WHERE m.isHiddenByUser = 0
          AND (:bucketId IS NULL OR m.bucketId = :bucketId)
          AND (:showSensitive OR a.sensitiveScore IS NULL OR a.sensitiveScore < :threshold)
        ORDER BY m.dateTaken DESC
        """
    )
    fun observeTimeline(bucketId: Long?, showSensitive: Boolean, threshold: Float): Flow<List<MediaWithAnalysis>>

    @Query(
        """
        SELECT m.*, a.sensitiveScore AS sensitiveScore FROM media m
        LEFT JOIN media_analysis a ON a.mediaId = m.id
        WHERE m.isFavorite = 1 AND m.isHiddenByUser = 0
        ORDER BY m.dateTaken DESC
        """
    )
    fun observeFavorites(): Flow<List<MediaWithAnalysis>>

    /** Скрытое вручную + автоматически помеченное как деликатное. */
    @Query(
        """
        SELECT m.*, a.sensitiveScore AS sensitiveScore FROM media m
        LEFT JOIN media_analysis a ON a.mediaId = m.id
        WHERE m.isHiddenByUser = 1 OR a.sensitiveScore >= :threshold
        ORDER BY m.dateTaken DESC
        """
    )
    fun observeHidden(threshold: Float): Flow<List<MediaWithAnalysis>>

    @Query(
        """
        SELECT bucketId, bucketName,
               (SELECT uri FROM media m2 WHERE m2.bucketId = m.bucketId AND m2.isHiddenByUser = 0
                ORDER BY dateTaken DESC LIMIT 1) AS coverUri,
               COUNT(*) AS itemCount
        FROM media m
        WHERE isHiddenByUser = 0
        GROUP BY bucketId
        ORDER BY MAX(dateTaken) DESC
        """
    )
    fun observeAlbums(): Flow<List<AlbumRow>>

    @Query(
        """
        SELECT m.*, a.sensitiveScore AS sensitiveScore FROM media m
        LEFT JOIN media_analysis a ON a.mediaId = m.id
        WHERE m.id IN (:ids)
        """
    )
    suspend fun getByIds(ids: List<Long>): List<MediaWithAnalysis>

    @Upsert
    suspend fun upsertAll(items: List<MediaEntity>)

    @Query("DELETE FROM media WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("UPDATE media SET isFavorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: Long, favorite: Boolean)

    @Query("UPDATE media SET isHiddenByUser = :hidden WHERE id IN (:ids)")
    suspend fun setHidden(ids: List<Long>, hidden: Boolean)

    @Query("SELECT id, isFavorite, isHiddenByUser FROM media")
    suspend fun getLocalFlags(): List<LocalFlagsRow>

    /**
     * Синхронизация с MediaStore с сохранением локальных флагов (избранное/скрытое),
     * которых в MediaStore нет.
     */
    @Transaction
    suspend fun replaceFromMediaStore(fresh: List<MediaEntity>) {
        val flags = getLocalFlags().associateBy { it.id }
        val merged = fresh.map { e ->
            val f = flags[e.id] ?: return@map e
            e.copy(isFavorite = f.isFavorite, isHiddenByUser = f.isHiddenByUser)
        }
        val freshIds = fresh.mapTo(HashSet()) { it.id }
        val removed = flags.keys.filterNot { it in freshIds }
        removed.chunked(SQLITE_MAX_ARGS).forEach { deleteByIds(it) }
        merged.chunked(500).forEach { upsertAll(it) }
    }

    data class LocalFlagsRow(val id: Long, val isFavorite: Boolean, val isHiddenByUser: Boolean)

    companion object {
        const val SQLITE_MAX_ARGS = 900
    }
}
