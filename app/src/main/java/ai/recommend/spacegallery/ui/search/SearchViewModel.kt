package ai.recommend.spacegallery.ui.search

import ai.recommend.spacegallery.data.repository.MediaRepository
import ai.recommend.spacegallery.domain.MediaType
import ai.recommend.spacegallery.domain.ScoredMedia
import ai.recommend.spacegallery.search.FilteredSearch
import ai.recommend.spacegallery.search.ParsedQuery
import ai.recommend.spacegallery.search.PlaceName
import ai.recommend.spacegallery.search.places.Place
import ai.recommend.spacegallery.search.places.PlacesRepository
import ai.recommend.spacegallery.search.QueryParser
import ai.recommend.spacegallery.search.SearchCriteria
import ai.recommend.spacegallery.search.SearchOutcome
import ai.recommend.spacegallery.search.people.PeopleRepository
import ai.recommend.spacegallery.search.people.Person
import ai.recommend.spacegallery.search.smart.SmartAlbum
import ai.recommend.spacegallery.search.smart.SmartAlbumRepository
import ai.recommend.spacegallery.ui.components.observeScored
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar

sealed interface SearchUiState {
    data object Idle : SearchUiState
    data object Searching : SearchUiState
    data class Results(val items: List<ScoredMedia>) : SearchUiState
    data object ModelUnavailable : SearchUiState
    data object IndexNotReady : SearchUiState
}

/** Период в фильтре «Дата». */
sealed interface DatePreset {
    data object ThisMonth : DatePreset
    data object ThisYear : DatePreset
    data class Year(val year: Int) : DatePreset
    /** Свой период: [from] включительно, [to] не включая. */
    data class Range(val from: Long, val to: Long) : DatePreset
}

/** Фильтры, выбранные чипами (не из текста запроса). */
data class SearchFilters(
    val personIds: Set<Long> = emptySet(),
    val date: DatePreset? = null,
    val type: MediaType? = null,
    val favoritesOnly: Boolean = false,
    /** Выбранные места: города (id GeoNames) и подпись чипа. */
    val placeCityIds: Set<Long> = emptySet(),
    val placeLabel: String? = null,
) {
    val isEmpty: Boolean get() = personIds.isEmpty() && date == null && type == null && !favoritesOnly && placeCityIds.isEmpty()
}

@OptIn(FlowPreview::class)
class SearchViewModel(
    private val search: FilteredSearch,
    private val repository: MediaRepository,
    smartAlbums: SmartAlbumRepository,
    people: PeopleRepository,
    places: PlacesRepository,
) : ViewModel() {

    /** Места съёмки в медиатеке — выбор в фильтре и распознавание в запросе. */
    val placeList: StateFlow<List<Place>> = places.observePlaces()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Люди — ряд над умными альбомами и выбор в фильтре. */
    val peopleList: StateFlow<List<Person>> = people.observePeople()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Умные альбомы — показываются, пока строка поиска пуста. null — ещё загружаются. */
    val smartAlbumList: StateFlow<List<SmartAlbum>?> = smartAlbums.observeAlbums()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Годы, за которые есть фото, — быстрый выбор в фильтре «Дата». */
    val years: StateFlow<List<Int>> = repository.observeTimeline()
        .map { items ->
            val calendar = Calendar.getInstance()
            items.mapTo(sortedSetOf(reverseOrder())) { calendar.apply { timeInMillis = it.dateTaken }.get(Calendar.YEAR) }.toList()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _filters = MutableStateFlow(SearchFilters())
    val filters: StateFlow<SearchFilters> = _filters.asStateFlow()

    /** Слова, распознавание которых отключено (ищутся как текст). */
    private val ignored = MutableStateFlow(emptySet<String>())

    /** Что распознано в тексте запроса (люди, год, сезон) — чипы под строкой поиска. */
    val parsed: StateFlow<ParsedQuery> = combine(_query, peopleList, ignored, placeList) { q, list, ign, pl ->
        QueryParser.parse(q, list.mapNotNull { p -> p.name?.let { p.id to it } }.toMap(), ign, placeNames(pl))
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ParsedQuery(""))

    private val _state = MutableStateFlow<SearchUiState>(SearchUiState.Idle)
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(parsed.debounce(400), _filters) { p, f -> p.text.trim() to criteria(p, f) }
                .distinctUntilChanged()
                .collectLatest { (text, criteria) ->
                    if (text.length < 2 && criteria.isEmpty) {
                        _state.value = SearchUiState.Idle
                        return@collectLatest
                    }
                    _state.value = SearchUiState.Searching
                    // Одна буква не ищется по смыслу — только фильтры.
                    when (val outcome = search.search(if (text.length < 2) "" else text, criteria)) {
                        // Живой список до следующего запроса (collectLatest отменит подписку).
                        is SearchOutcome.Results -> repository.observeScored(outcome.items).collect {
                            _state.value = SearchUiState.Results(it)
                        }
                        SearchOutcome.ModelUnavailable -> _state.value = SearchUiState.ModelUnavailable
                        SearchOutcome.IndexNotReady -> _state.value = SearchUiState.IndexNotReady
                    }
                }
        }
    }

    fun onQueryChange(value: String) {
        _query.value = value
        // Для нового запроса распознавание снова включено.
        if (value.isBlank()) ignored.value = emptySet()
    }

    fun ignoreWord(word: String) {
        ignored.value = ignored.value + word.lowercase()
    }

    fun setPeople(ids: Set<Long>) = _filters.update { it.copy(personIds = ids) }
    fun setDate(date: DatePreset?) = _filters.update { it.copy(date = date) }
    fun setType(type: MediaType?) = _filters.update { it.copy(type = type) }
    fun setFavoritesOnly(value: Boolean) = _filters.update { it.copy(favoritesOnly = value) }
    fun setPlaces(cityIds: Set<Long>, label: String?) = _filters.update { it.copy(placeCityIds = cityIds, placeLabel = label) }
    fun clearFilters() = _filters.update { SearchFilters() }

    private inline fun MutableStateFlow<SearchFilters>.update(block: (SearchFilters) -> SearchFilters) {
        value = block(value)
    }

    /** Фильтры чипов + распознанное в тексте. Явно выбранная дата важнее года из текста. */
    private fun criteria(p: ParsedQuery, f: SearchFilters): SearchCriteria {
        val (from, to) = f.date?.let(::range) ?: p.year?.let { range(DatePreset.Year(it)) } ?: (null to null)
        return SearchCriteria(
            personIds = f.personIds + p.personIds,
            from = from,
            to = to,
            months = p.months,
            type = f.type,
            favoritesOnly = f.favoritesOnly,
            placeCityIds = f.placeCityIds + p.placeCityIds,
        )
    }

    /** Города и страны медиатеки для распознавания: «в Сочи», «Турция». */
    private fun placeNames(places: List<Place>): List<PlaceName> =
        places.map { PlaceName(it.city.name, setOf(it.city.id)) } +
            places.groupBy { it.countryName }.map { (country, list) -> PlaceName(country, list.mapTo(HashSet()) { it.city.id }) }

    private fun range(preset: DatePreset): Pair<Long, Long> {
        val c = Calendar.getInstance()
        fun startOf(year: Int, month: Int): Long {
            c.clear()
            c.set(year, month, 1)
            return c.timeInMillis
        }
        val now = Calendar.getInstance()
        val year = now.get(Calendar.YEAR)
        return when (preset) {
            DatePreset.ThisMonth -> startOf(year, now.get(Calendar.MONTH)) to startOf(year, now.get(Calendar.MONTH) + 1)
            DatePreset.ThisYear -> startOf(year, 0) to startOf(year + 1, 0)
            is DatePreset.Year -> startOf(preset.year, 0) to startOf(preset.year + 1, 0)
            is DatePreset.Range -> preset.from to preset.to
        }
    }
}
