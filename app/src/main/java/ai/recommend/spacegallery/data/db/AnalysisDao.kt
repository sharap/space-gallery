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

    @Query("DELETE FROM media_analysis")
    suspend fun clear()
}
