package ai.recommend.spacegallery.bench

import ai.recommend.spacegallery.di.AppContainer
import ai.recommend.spacegallery.domain.MediaType
import ai.recommend.spacegallery.ml.VectorMath
import ai.recommend.spacegallery.ml.face.DetectedFace
import android.os.SystemClock
import kotlinx.coroutines.flow.first
import java.util.Locale
import kotlin.math.max

/**
 * Диагностика детекции лиц (debug): сравнивает варианты на выборке фото. Результаты на 600 фото
 * (2026-09-19) — в комментарии к FaceDetector.MIN_SCORE; фрагменты 1280 px не дали выигрыша.
 * Лог может вытесняться из маленького буфера logcat — писать его в файл потоково:
 *   adb logcat -T 1 -v raw -s OrtBench:I > facediag.txt
 *
 * В лог пишутся только числа — сами фото не покидают устройство. Точность оценивается узнаванием: найденное лицо,
 * похожее на уже известного человека (сходство с центром его группы ≥ 0.45), — точно настоящее.
 */
class FaceDiagnostics(private val c: AppContainer) {

    /** [minFraction] — минимальная сторона лица в долях длинной стороны кадра (не зависит от разрешения). */
    private class Variant(val name: String, val source: Int, val minScore: Float, val minFraction: Float)

    private val variants = listOf(
        Variant("V0 прежний: 640, порог 0.8, мин. 4%", 640, 0.8f, 0.04f),
        Variant("V1 порог 0.7, мин. 2%", 640, 0.7f, 0.02f),
        Variant("V2 текущий: порог 0.6, мин. 2%", 640, 0.6f, 0.02f),
        Variant("V3 порог 0.5, мин. 2%", 640, 0.5f, 0.02f),
    )

    private class Stat {
        var photos = 0
        var faces = 0
        var known = 0
        var ms = 0L
        val unknownScores = ArrayList<Float>()
    }

    /** [names] — конкретные файлы (подробный лог по каждому); иначе [sample] последних фото. */
    suspend fun run(sample: Int, names: List<String>) {
        val model = c.settings.current().faceModel
        val centroids = personCentroids()
        val images = c.mediaRepository.observeTimeline().first().filter { it.type == MediaType.IMAGE }
        val picked = if (names.isNotEmpty()) images.filter { it.displayName in names } else images.take(sample)
        OrtBenchmark.log("=== facediag: ${picked.size} фото, известных людей ${centroids.size}")

        val stats = variants.associateWith { Stat() }
        for (item in picked) {
            val detail = StringBuilder()
            for (v in variants) {
                val start = SystemClock.elapsedRealtime()
                val bitmap = c.bitmapLoader.load(item.uri, MediaType.IMAGE, v.source) ?: continue
                val minSide = max(bitmap.width, bitmap.height) * v.minFraction
                val faces = c.faceDetector.detect(bitmap, v.minScore)
                    .filter { it.box.width() >= minSide && it.box.height() >= minSide }
                val st = stats.getValue(v)
                val known = faces.count { face ->
                    val e = c.faceEmbedder.embed(bitmap, face, model)
                    val isKnown = e != null && centroids.any { VectorMath.dot(it, e) >= KNOWN_SIMILARITY }
                    if (!isKnown) st.unknownScores += face.score
                    isKnown
                }
                st.ms += SystemClock.elapsedRealtime() - start
                if (faces.isNotEmpty()) st.photos++
                st.faces += faces.size
                st.known += known
                if (names.isNotEmpty()) {
                    detail.append("\n   ${v.name}: превью ${bitmap.width}x${bitmap.height}, ${describe(faces)}, узнано $known")
                }
            }
            if (names.isNotEmpty()) OrtBenchmark.log("${item.displayName}:$detail")
        }
        for ((v, st) in stats) {
            OrtBenchmark.log(
                String.format(
                    Locale.ROOT, "%-40s фото с лицами=%4d лиц=%5d узнано=%5d | %4.0f мс/фото",
                    v.name, st.photos, st.faces, st.known, st.ms.toDouble() / picked.size.coerceAtLeast(1),
                ) + " | неузнанные по уверенности: " + scoreBuckets(st.unknownScores)
            )
        }
        OrtBenchmark.log("=== facediag done")
    }

    private fun scoreBuckets(scores: List<Float>): String =
        listOf(0.5f to 0.6f, 0.6f to 0.7f, 0.7f to 0.8f, 0.8f to 1.01f).joinToString { (lo, hi) ->
            String.format(Locale.ROOT, "%.1f–%.1f: %d", lo, minOf(hi, 1f), scores.count { it >= lo && it < hi })
        }

    private fun describe(faces: List<DetectedFace>) = if (faces.isEmpty()) "лиц нет" else
        faces.joinToString(prefix = "лица ") { String.format(Locale.ROOT, "[%.2f, %dpx]", it.score, it.box.width().toInt()) }

    /** Центры групп известных людей. */
    private suspend fun personCentroids(): List<FloatArray> {
        val s = c.settings.current()
        return c.database.faceDao().getForClustering(s.hideSensitive, s.sensitiveThreshold, s.faceModel.embedVersion)
            .filter { it.personId != null }
            .groupBy { it.personId }
            .values.map { faces ->
                val vs = faces.map { VectorMath.fromBytes(it.embedding) }
                VectorMath.l2Normalize(FloatArray(vs.first().size) { k -> vs.sumOf { it[k].toDouble() }.toFloat() })
            }
    }

    private companion object {
        const val KNOWN_SIMILARITY = 0.45f
    }
}
