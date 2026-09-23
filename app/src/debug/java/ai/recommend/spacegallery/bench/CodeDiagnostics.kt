package ai.recommend.spacegallery.bench

import ai.recommend.spacegallery.di.AppContainer
import ai.recommend.spacegallery.domain.MediaType
import android.os.SystemClock
import kotlinx.coroutines.flow.first

/**
 * Сколько кодов находится при разном размере кадра (debug). В лог идут только числа: сколько
 * снимков, сколько кодов и сколько это стоит по времени — содержимое кодов не выводится.
 */
class CodeDiagnostics(private val c: AppContainer) {

    suspend fun run(sample: Int, needle: String = "") {
        val dao = c.database.textDao()
        val images = if (needle.isEmpty()) {
            c.mediaRepository.observeTimeline().first().filter { it.type == MediaType.IMAGE }.take(sample)
        } else {
            // Прицельно: снимки, где в тексте упоминается код, — там он почти наверняка есть.
            val ids = dao.mediaWithTextLike(needle, sample)
            c.mediaRepository.getByIds(ids)
        }
        OrtBenchmark.log("=== codes: снимков ${images.size}; в базе кодов ${dao.countCodes()}, снимков с текстом ${dao.countWithText()}")
        // Каждый снимок декодируется по одному разу на размер, варианты разбора считаются на нём.
        for (size in listOf(1280, 2048)) {
            var wholePhotos = 0
            var wholeCodes = 0
            var tiledPhotos = 0
            var tiledCodes = 0
            var decodeMs = 0L
            var wholeMs = 0L
            var tiledMs = 0L
            for (item in images) {
                var t = SystemClock.elapsedRealtime()
                val bitmap = c.bitmapLoader.load(item.uri, MediaType.IMAGE, size) ?: continue
                decodeMs += SystemClock.elapsedRealtime() - t

                t = SystemClock.elapsedRealtime()
                val whole = c.codeScanner.scan(bitmap, tiles = 0)
                wholeMs += SystemClock.elapsedRealtime() - t
                if (whole.isNotEmpty()) {
                    wholePhotos++
                    wholeCodes += whole.size
                }

                t = SystemClock.elapsedRealtime()
                val tiled = c.codeScanner.scan(bitmap, tiles = 2)
                tiledMs += SystemClock.elapsedRealtime() - t
                if (tiled.isNotEmpty()) {
                    tiledPhotos++
                    tiledCodes += tiled.size
                }
                bitmap.recycle()
            }
            val n = images.size.coerceAtLeast(1)
            OrtBenchmark.log(
                "кадр $size: декодирование ${decodeMs / n} мс/снимок | весь кадр: снимков $wholePhotos, кодов $wholeCodes, " +
                    "${wholeMs / n} мс | с кусками: снимков $tiledPhotos, кодов $tiledCodes, ${tiledMs / n} мс"
            )
        }
        OrtBenchmark.log("=== codes done")
    }
}
