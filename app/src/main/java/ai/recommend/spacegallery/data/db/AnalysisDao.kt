package ai.recommend.spacegallery.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface AnalysisDao {

    /**
     * Медиа, требующие (пере)анализа: новые, изменённые, проанализированные старой версией
     * пайплайна, либо без результата модели, которая теперь доступна.
     */
    @Query(
        """
        SELECT m.* FROM media m
        LEFT JOIN media_analysis a ON a.mediaId = m.id
        WHERE a.mediaId IS NULL
           OR a.pipelineVersion < :pipelineVersion
           OR a.sourceModified != m.dateModified
           OR (a.isUnreadable = 0 AND :embedderAvailable AND a.embedding IS NULL)
           OR (a.isUnreadable = 0 AND :classifierAvailable AND a.sensitiveScore IS NULL)
        ORDER BY m.dateTaken DESC
        LIMIT :limit
        """
    )
    suspend fun getPending(
        pipelineVersion: Int,
        embedderAvailable: Boolean,
        classifierAvailable: Boolean,
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
