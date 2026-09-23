package ai.recommend.spacegallery.search

import ai.recommend.spacegallery.data.db.FaceDao
import ai.recommend.spacegallery.data.repository.MediaRepository
import ai.recommend.spacegallery.domain.MediaType
import ai.recommend.spacegallery.domain.ScoredMedia
import ai.recommend.spacegallery.search.places.PlacesRepository
import ai.recommend.spacegallery.search.text.TextRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.Calendar

/** Условия поиска помимо текста. Пустые условия ничего не отсекают. */
data class SearchCriteria(
    /** Все эти люди должны быть на фото. */
    val personIds: Set<Long> = emptySet(),
    /** Начало периода (включительно), мс. */
    val from: Long? = null,
    /** Конец периода (не включая), мс. */
    val to: Long? = null,
    /** Месяцы года (Calendar.JANUARY = 0 …) — «летом», «в мае» любого года. */
    val months: Set<Int> = emptySet(),
    val type: MediaType? = null,
    val favoritesOnly: Boolean = false,
    /** Снято в одном из этих городов (id GeoNames). */
    val placeCityIds: Set<Long> = emptySet(),
) {
    val isEmpty: Boolean
        get() = personIds.isEmpty() && from == null && to == null && months.isEmpty() && type == null && !favoritesOnly &&
            placeCityIds.isEmpty()
}

/**
 * Поиск с фильтрами: сначала отбор по условиям (люди, даты, тип, избранное), затем — если есть
 * текст — поиск по смыслу только среди отобранного. Без текста — всё отобранное по дате.
 */
class FilteredSearch(
    private val engine: SemanticSearchEngine,
    private val repository: MediaRepository,
    private val faces: FaceDao,
    private val places: PlacesRepository,
    private val textSearch: TextRepository,
) {
    suspend fun search(text: String, criteria: SearchCriteria): SearchOutcome {
        val allowed = if (criteria.isEmpty) null else candidates(criteria).mapTo(HashSet()) { it.id }
        if (text.isBlank()) {
            val items = if (allowed == null) emptyList() else repository.observeTimeline().first().filter { it.id in allowed }
            return SearchOutcome.Results(items.map { ScoredMedia(it, 0f) })
        }
        // Снимки, где такой текст прямо написан, — это точное попадание, они идут первыми.
        val byText = textSearch.search(text).let { ids -> if (allowed == null) ids else ids.filter { it in allowed } }
        val outcome = engine.search(text, include = allowed)
        if (byText.isEmpty()) return outcome
        val exact = repository.getVisibleByIds(byText).map { ScoredMedia(it, TEXT_MATCH_SCORE) }
        val rest = (outcome as? SearchOutcome.Results)?.items.orEmpty().filterNot { it.item.id in byText.toHashSet() }
        return SearchOutcome.Results(exact + rest)
    }

    private companion object {
        /** Текст на снимке — совпадение точное, выше любой смысловой близости CLIP. */
        const val TEXT_MATCH_SCORE = 1f
    }

    private suspend fun candidates(c: SearchCriteria) = withContext(Dispatchers.Default) {
        val people = c.personIds.map { faces.getPersonMediaIds(it).toHashSet() }
        val inPlaces = if (c.placeCityIds.isEmpty()) null else places.mediaIdsIn(c.placeCityIds)
        val calendar = Calendar.getInstance()
        repository.observeTimeline().first().filter { item ->
            (c.type == null || item.type == c.type) &&
                (!c.favoritesOnly || item.isFavorite) &&
                (c.from == null || item.dateTaken >= c.from) &&
                (c.to == null || item.dateTaken < c.to) &&
                people.all { item.id in it } &&
                (inPlaces == null || item.id in inPlaces) &&
                (c.months.isEmpty() || calendar.apply { timeInMillis = item.dateTaken }.get(Calendar.MONTH) in c.months)
        }
    }
}
