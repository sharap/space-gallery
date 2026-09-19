package ai.recommend.spacegallery.search

import ai.recommend.spacegallery.data.repository.MediaRepository
import ai.recommend.spacegallery.data.settings.SettingsRepository
import ai.recommend.spacegallery.domain.ScoredMedia

/** Подбор визуально/семантически похожих медиа к выбранному. */
class SimilarMediaFinder(
    private val index: EmbeddingIndex,
    private val repository: MediaRepository,
    private val settings: SettingsRepository,
) {
    /** null — медиа ещё не проиндексировано. */
    suspend fun findSimilar(mediaId: Long, limit: Int = 60): List<ScoredMedia>? {
        val vector = index.vectorOf(mediaId) ?: return null
        val threshold = settings.current().similarityThreshold
        val hits = index.search(vector, limit, minScore = threshold, exclude = setOf(mediaId))
        val scores = hits.toMap()
        return repository.getVisibleByIds(hits.map { it.first })
            .map { ScoredMedia(it, scores.getValue(it.id)) }
    }
}
