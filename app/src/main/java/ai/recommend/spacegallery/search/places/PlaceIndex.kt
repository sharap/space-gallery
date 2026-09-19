package ai.recommend.spacegallery.search.places

import android.content.res.AssetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor

/** Город из офлайн-базы GeoNames. */
data class City(val id: Long, val name: String, val countryCode: String, val population: Int, val lat: Double, val lon: Double)

/**
 * Офлайн-геокодер: ближайший город (от 5000 жителей, GeoNames) к точке съёмки не дальше
 * [MAX_DISTANCE_KM]. Координаты никуда не отправляются. Индекс — сетка 1°×1°.
 */
class PlaceIndex(private val assets: AssetManager) {
    private class Data(val cities: List<City>, val grid: Map<Long, List<City>>, val countries: Map<String, String>)

    private val mutex = Mutex()
    @Volatile private var data: Data? = null

    val isAvailable: Boolean get() = runCatching { assets.open(CITIES).close() }.isSuccess

    suspend fun nearest(lat: Double, lon: Double): City? {
        val d = load()
        val cellLat = floor(lat).toInt()
        val cellLon = floor(lon).toInt()
        var best: City? = null
        var bestScore = Double.MAX_VALUE
        for (dy in -1..1) for (dx in -1..1) {
            for (city in d.grid[cell(cellLat + dy, wrap(cellLon + dx))].orEmpty()) {
                val km = distanceKm(lat, lon, city.lat, city.lon)
                if (km > MAX_DISTANCE_KM) continue
                // Крупный город чуть «притягивает»: окраина Москвы — это Москва, а не посёлок рядом.
                val score = km / (1.0 + city.population / BIG_CITY)
                if (score < bestScore) {
                    bestScore = score
                    best = city
                }
            }
        }
        return best
    }

    suspend fun countryName(code: String): String = load().countries[code] ?: code

    suspend fun city(id: Long): City? = load().cities.firstOrNull { it.id == id }

    private suspend fun load(): Data {
        data?.let { return it }
        return mutex.withLock {
            data ?: withContext(Dispatchers.IO) {
                val cities = assets.open(CITIES).bufferedReader().useLines { lines ->
                    lines.mapNotNull { line ->
                        val c = line.split('\t')
                        if (c.size < 6) null else City(c[0].toLong(), c[3], c[4], c[5].toIntOrNull() ?: 0, c[1].toDouble(), c[2].toDouble())
                    }.toList()
                }
                val countries = assets.open(COUNTRIES).bufferedReader().useLines { lines ->
                    lines.mapNotNull { line -> line.split('\t').takeIf { it.size >= 2 }?.let { it[0] to it[1] } }.toMap()
                }
                Data(cities, cities.groupBy { cell(floor(it.lat).toInt(), floor(it.lon).toInt()) }, countries)
            }.also { data = it }
        }
    }

    private fun cell(lat: Int, lon: Int): Long = (lat.toLong() shl 32) or (lon.toLong() and 0xFFFFFFFFL)

    private fun wrap(lon: Int): Int = ((lon + 180) % 360 + 360) % 360 - 180

    private fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        // Равнопромежуточная проекция — точности хватает на десятках километров.
        val x = (lon2 - lon1) * cos((lat1 + lat2) / 2 * PI / 180)
        val y = lat2 - lat1
        return kotlin.math.sqrt(x * x + y * y) * 111.2
    }

    private companion object {
        const val CITIES = "places/cities.tsv"
        const val COUNTRIES = "places/countries.tsv"
        const val MAX_DISTANCE_KM = 30.0
        /** Население, при котором город «притягивает» вдвое сильнее соседей. */
        const val BIG_CITY = 5_000_000.0
    }
}
