package ai.recommend.spacegallery.search

import ai.recommend.spacegallery.data.repository.MediaRepository
import ai.recommend.spacegallery.domain.ScoredMedia
import ai.recommend.spacegallery.ml.text.TextEmbedder

sealed interface SearchOutcome {
    data class Results(val items: List<ScoredMedia>) : SearchOutcome

    /** Нет модели текстового энкодера на устройстве. */
    data object ModelUnavailable : SearchOutcome

    /** Индекс ещё не построен (идёт первая индексация). */
    data object IndexNotReady : SearchOutcome
}

/** AI-поиск по текстовому описанию: «кот на подоконнике», «закат на море». */
class SemanticSearchEngine(
    private val textEmbedder: TextEmbedder,
    private val index: EmbeddingIndex,
    private val repository: MediaRepository,
) {
    suspend fun search(query: String, limit: Int = 200): SearchOutcome {
        if (!textEmbedder.isAvailable) return SearchOutcome.ModelUnavailable
        if (index.size() == 0) return SearchOutcome.IndexNotReady
        val q = textEmbedder.embed(query) ?: return SearchOutcome.ModelUnavailable

        // Косинус текст↔картинка у CLIP невелик (~0.2–0.35), поэтому важен ранжированный порядок,
        // а порог — лишь отсечка явного шума.
        val hits = index.search(q, limit, minScore = MIN_TEXT_IMAGE_SCORE)
        val scores = hits.toMap()
        val items = repository.getVisibleByIds(hits.map { it.first })
        return SearchOutcome.Results(items.map { ScoredMedia(it, scores.getValue(it.id)) })
    }

    private companion object {
        const val MIN_TEXT_IMAGE_SCORE = 0.15f
    }
}
