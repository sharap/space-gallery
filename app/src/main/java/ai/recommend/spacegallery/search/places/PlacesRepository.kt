package ai.recommend.spacegallery.search.places

import ai.recommend.spacegallery.data.db.AnalysisDao
import ai.recommend.spacegallery.data.repository.MediaRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Место в медиатеке: город и сколько видимых фото/видео там снято. */
data class Place(val city: City, val countryName: String, val count: Int)

/** Места съёмки: геометки медиа, привязанные к ближайшему городу из офлайн-базы. */
class PlacesRepository(
    private val analysis: AnalysisDao,
    private val index: PlaceIndex,
    private val media: MediaRepository,
) {
    private val mutex = Mutex()
    private var cachedFor = -1
    private var cityByMedia: Map<Long, City> = emptyMap()

    /** Город для каждого медиа с геометкой. Пересчитывается, когда меняется число геометок. */
    suspend fun cityByMedia(): Map<Long, City> = mutex.withLock {
        val rows = analysis.getAllLocations()
        if (rows.size != cachedFor) {
            val result = HashMap<Long, City>(rows.size)
            for (row in rows) index.nearest(row.latitude, row.longitude)?.let { result[row.mediaId] = it }
            cityByMedia = result
            cachedFor = rows.size
        }
        cityByMedia
    }

    /** Города с видимыми медиа — от тех, где снято больше всего. */
    fun observePlaces(): Flow<List<Place>> = media.observeTimeline().map { items ->
        if (!index.isAvailable) return@map emptyList()
        val cities = cityByMedia()
        items.mapNotNull { cities[it.id] }.groupingBy { it }.eachCount()
            .map { (city, count) -> Place(city, index.countryName(city.countryCode), count) }
            .sortedByDescending { it.count }
    }

    /** Видимые медиа, снятые в этих городах. */
    suspend fun mediaIdsIn(cityIds: Set<Long>): Set<Long> {
        val cities = cityByMedia()
        return media.observeTimeline().first()
            .filter { item -> cities[item.id]?.let { it.id in cityIds } == true }
            .mapTo(HashSet()) { it.id }
    }
}
