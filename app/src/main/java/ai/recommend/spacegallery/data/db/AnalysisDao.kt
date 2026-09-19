package ai.recommend.spacegallery.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface AnalysisDao {

    /**
     * Медиа, требующие (пере)анализа: новые, изменённые, проанализированные старой версией
     * пайплайна, либо без результата модели, которая теперь доступна.
     *
     * Читается страницами по курсору (dateTaken, id): следующую страницу можно брать,
     * пока предыдущая ещё обрабатывается и не записана в БД.
     * Первая страница: afterDateTaken = afterId = Long.MAX_VALUE.
     */
    @Query(
        """
        SELECT m.* FROM media m
        LEFT JOIN media_analysis a ON a.mediaId = m.id
        WHERE (m.dateTaken < :afterDateTaken OR (m.dateTaken = :afterDateTaken AND m.id < :afterId))
          AND (a.mediaId IS NULL
           OR a.pipelineVersion < :pipelineVersion
           OR a.sourceModified != m.dateModified
           OR (a.isUnreadable = 0 AND :embedderAvailable AND a.embedding IS NULL)
           OR (a.isUnreadable = 0 AND :classifierAvailable AND a.sensitiveScore IS NULL))
        ORDER BY m.dateTaken DESC, m.id DESC
        LIMIT :limit
        """
    )
    suspend fun getPendingPage(
        pipelineVersion: Int,
        embedderAvailable: Boolean,
        classifierAvailable: Boolean,
        afterDateTaken: Long,
        afterId: Long,
        limit: Int,
    ): List<MediaEntity>

    @Query(
        """
        SELECT COUNT(*) FROM media m
        LEFT JOIN media_analysis a ON a.mediaId = m.id
        WHERE a.mediaId IS NULL
           OR a.pipelineVersion < :pipelineVersion
           OR a.sourceModified != m.dateModified
           OR (a.isUnreadable = 0 AND :embedderAvailable AND a.embedding IS NULL)
           OR (a.isUnreadable = 0 AND :classifierAvailable AND a.sensitiveScore IS NULL)
        """
    )
    suspend fun countPending(
        pipelineVersion: Int,
        embedderAvailable: Boolean,
        classifierAvailable: Boolean,
    ): Int

    @Upsert
    suspend fun upsertAll(items: List<MediaAnalysisEntity>)

    @Query("SELECT mediaId, embedding FROM media_analysis WHERE embedding IS NOT NULL")
    suspend fun getAllEmbeddings(): List<EmbeddingRow>

    @Query("SELECT mediaId, perceptualHash FROM media_analysis WHERE perceptualHash IS NOT NULL")
    suspend fun getAllHashes(): List<HashRow>

    // --- Оценка качества (резкость/яркость) ---

    @Query(
        """
        SELECT m.* FROM media m JOIN media_analysis a ON a.mediaId = m.id
        WHERE m.mediaType = 0 AND a.isUnreadable = 0 AND a.qualityVersion < :version
          AND (m.dateTaken < :afterDateTaken OR (m.dateTaken = :afterDateTaken AND m.id < :afterId))
        ORDER BY m.dateTaken DESC, m.id DESC
        LIMIT :limit
        """
    )
    suspend fun getQualityPendingPage(version: Int, afterDateTaken: Long, afterId: Long, limit: Int): List<MediaEntity>

    @Query(
        """
        SELECT COUNT(*) FROM media m JOIN media_analysis a ON a.mediaId = m.id
        WHERE m.mediaType = 0 AND a.isUnreadable = 0 AND a.qualityVersion < :version
        """
    )
    suspend fun countQualityPending(version: Int): Int

    @Query("UPDATE media_analysis SET sharpness = :sharpness, brightness = :brightness, qualityVersion = :version WHERE mediaId = :mediaId")
    suspend fun setQuality(mediaId: Long, sharpness: Float?, brightness: Float?, version: Int)

    @Query("SELECT mediaId, sharpness, brightness FROM media_analysis WHERE sharpness IS NOT NULL AND brightness IS NOT NULL")
    suspend fun getAllQuality(): List<QualityRow>

    // --- Геометки ---

    @Query(
        """
        SELECT m.* FROM media m JOIN media_analysis a ON a.mediaId = m.id
        WHERE a.isUnreadable = 0 AND a.locationVersion < :version
          AND (m.dateTaken < :afterDateTaken OR (m.dateTaken = :afterDateTaken AND m.id < :afterId))
        ORDER BY m.dateTaken DESC, m.id DESC
        LIMIT :limit
        """
    )
    suspend fun getLocationPendingPage(version: Int, afterDateTaken: Long, afterId: Long, limit: Int): List<MediaEntity>

    @Query("SELECT COUNT(*) FROM media_analysis WHERE isUnreadable = 0 AND locationVersion < :version")
    suspend fun countLocationPending(version: Int): Int

    @Query("UPDATE media_analysis SET latitude = :latitude, longitude = :longitude, locationVersion = :version WHERE mediaId = :mediaId")
    suspend fun setLocation(mediaId: Long, latitude: Double?, longitude: Double?, version: Int)

    @Query("SELECT mediaId, latitude, longitude FROM media_analysis WHERE latitude IS NOT NULL AND longitude IS NOT NULL")
    suspend fun getAllLocations(): List<LocationRow>

    @Query("DELETE FROM media_analysis")
    suspend fun clear()
}
