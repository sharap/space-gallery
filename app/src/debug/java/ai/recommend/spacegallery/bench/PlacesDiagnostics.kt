package ai.recommend.spacegallery.bench

import ai.recommend.spacegallery.di.AppContainer
import ai.recommend.spacegallery.domain.MediaType
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.flow.first

/**
 * Диагностика геометок (debug): сколько файлов обработано, у скольких есть координаты, что
 * отдаёт EXIF при прямом чтении. В лог идут только числа и признак «есть/нет координат».
 */
class PlacesDiagnostics(private val c: AppContainer) {

    suspend fun run(sample: Int) {
        val dao = c.database.analysisDao()
        OrtBenchmark.log(
            "=== places: разрешение=${c.locationIndexer.hasPermission}, ждут чтения=${dao.countLocationPending(1)}, " +
                "с координатами=${dao.getAllLocations().size}"
        )

        // Прямое чтение EXIF у последних фото — мимо базы и воркера.
        val items = c.mediaRepository.observeTimeline().first().filter { it.type == MediaType.IMAGE }.take(sample)
        var withGps = 0
        var redacted = 0
        for (item in items) {
            val uri = item.uri
            val original = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.setRequireOriginal(uri) else uri
            val direct = runCatching {
                c.appContext.contentResolver.openInputStream(original)?.use { ExifInterface(it).latLong }
            }
            val plain = runCatching {
                c.appContext.contentResolver.openInputStream(uri)?.use { ExifInterface(it).latLong }
            }.getOrNull()
            if (direct.getOrNull() != null) withGps++
            if (direct.getOrNull() == null && plain != null) redacted++
            direct.exceptionOrNull()?.let { OrtBenchmark.log("  ошибка чтения: $it") }
        }
        OrtBenchmark.log("EXIF напрямую: из ${items.size} фото с координатами $withGps (без setRequireOriginal нашлось бы больше на $redacted)")

        val start = SystemClock.elapsedRealtime()
        val processed = c.locationIndexer.run(isStopped = { false }) {}
        OrtBenchmark.log("проход: обработано $processed за ${SystemClock.elapsedRealtime() - start} мс, с координатами=${dao.getAllLocations().size}")

        val places = c.places.observePlaces().first()
        OrtBenchmark.log("места: городов ${places.size}, база городов доступна=${c.placeIndex.isAvailable}, всего снимков в местах ${places.sumOf { it.count }}")
        for (p in places.take(5)) OrtBenchmark.log("  ${p.countryName} / ${p.city.name}: ${p.count}")
        OrtBenchmark.log("=== places done")
    }
}
