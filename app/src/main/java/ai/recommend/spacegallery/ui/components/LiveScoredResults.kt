package ai.recommend.spacegallery.ui.components

import ai.recommend.spacegallery.data.repository.MediaRepository
import ai.recommend.spacegallery.domain.ScoredMedia
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Результаты поиска/похожих как живой список: изменения избранного приходят сразу,
 * удалённые и скрытые вручную элементы пропадают. Порядок и оценки — из исходной выдачи.
 */
fun MediaRepository.observeScored(results: List<ScoredMedia>): Flow<List<ScoredMedia>> {
    val scores = results.associate { it.item.id to it.score }
    return observeByIds(results.map { it.item.id }).map { items ->
        items.filterNot { it.isHiddenByUser }.map { ScoredMedia(it, scores.getValue(it.id)) }
    }
}
