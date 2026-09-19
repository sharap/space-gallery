package ai.recommend.spacegallery.bench

import ai.recommend.spacegallery.di.AppContainer
import ai.recommend.spacegallery.domain.DuplicateGroup
import ai.recommend.spacegallery.ml.VectorMath
import android.os.SystemClock
import kotlinx.coroutines.flow.first
import java.util.Locale
import kotlin.math.exp

/**
 * Диагностика очистки (debug): только числа — группы копий и серий, распределение резкости и
 * яркости, сверка резкости с CLIP zero-shot «размытое фото». Лог: `adb logcat -v raw -s OrtBench:I`.
 */
class CleanupDiagnostics(private val c: AppContainer) {

    suspend fun run() {
        val t0 = SystemClock.elapsedRealtime()
        val report = c.cleanupFinder.find()
        OrtBenchmark.log("=== cleanup: поиск за ${SystemClock.elapsedRealtime() - t0} мс")
        logGroups("копии", report.copies)
        logGroups("серии", report.series)
        OrtBenchmark.log("размытые/тёмные: ${report.poor.size} (${mb(report.poor.sumOf { it.sizeBytes })} МБ)")
        OrtBenchmark.log("скриншоты: ${report.screenshots.size} (${mb(report.screenshots.sumOf { it.sizeBytes })} МБ)")
        OrtBenchmark.log("большие видео: ${report.largeVideos.size} (${mb(report.largeVideos.sumOf { it.sizeBytes })} МБ)")

        val visible = c.mediaRepository.observeTimeline().first().mapTo(HashSet()) { it.id }
        val quality = c.database.analysisDao().getAllQuality().filter { it.mediaId in visible }
        OrtBenchmark.log("оценено качество: ${quality.size}")
        if (quality.isEmpty()) return
        val sharp = quality.map { it.sharpness }.sorted()
        val bright = quality.map { it.brightness }.sorted()
        fun pct(list: List<Float>) = listOf(1, 2, 5, 10, 25, 50, 75).joinToString { p ->
            String.format(Locale.ROOT, "p%d=%.3g", p, list[(list.size - 1) * p / 100])
        }
        OrtBenchmark.log("резкость: ${pct(sharp)}")
        OrtBenchmark.log("яркость: ${pct(bright)}")
        for (t in listOf(10f, 20f, 40f, 80f, 150f)) {
            OrtBenchmark.log("  резкость < $t: ${sharp.count { it < t }}")
        }
        for (t in listOf(0.03f, 0.06f, 0.1f)) {
            OrtBenchmark.log("  яркость < $t: ${bright.count { it < t }}")
        }

        // Сверка: у нерезких по метрике кадров CLIP должен чаще видеть «размытое фото».
        val blurText = c.textEmbedder.embedEnglish("a blurry photo") ?: return
        val sharpText = c.textEmbedder.embedEnglish("a sharp photo") ?: return
        val pBlur = HashMap<Long, Double>()
        for (row in c.database.analysisDao().getAllEmbeddings()) {
            val v = VectorMath.fromBytes(row.embedding)
            val a = 100.0 * VectorMath.dot(v, blurText)
            val b = 100.0 * VectorMath.dot(v, sharpText)
            pBlur[row.mediaId] = 1.0 / (1.0 + exp(b - a))
        }
        val bySharp = quality.filter { it.mediaId in pBlur }.sortedBy { it.sharpness }
        val deciles = (0 until 10).joinToString { d ->
            val part = bySharp.subList(bySharp.size * d / 10, bySharp.size * (d + 1) / 10)
            String.format(Locale.ROOT, "%.2f", part.map { pBlur.getValue(it.mediaId) }.average())
        }
        OrtBenchmark.log("CLIP P(размыто) по децилям резкости (от нерезких): $deciles")
        val lowest = bySharp.take(bySharp.size / 100)
        OrtBenchmark.log(String.format(Locale.ROOT, "  нижний 1%%: P(размыто)=%.2f", lowest.map { pBlur.getValue(it.mediaId) }.average()))
        OrtBenchmark.log("=== cleanup done за ${SystemClock.elapsedRealtime() - t0} мс")
    }

    private fun logGroups(name: String, groups: List<DuplicateGroup>) {
        val sizes = groups.map { it.items.size }
        val extra = groups.sumOf { g -> g.items.filter { it.id != g.suggestedKeep.id }.sumOf { it.sizeBytes } }
        val span = groups.count { g -> g.items.maxOf { it.dateTaken } - g.items.minOf { it.dateTaken } > 3_600_000 }
        OrtBenchmark.log(
            "$name: ${groups.size} групп, лишних ${sizes.sum() - groups.size} (${mb(extra)} МБ); размеры 2:${sizes.count { it == 2 }} " +
                "3-5:${sizes.count { it in 3..5 }} 6-10:${sizes.count { it in 6..10 }} >10:${sizes.count { it > 10 }} max:${sizes.maxOrNull() ?: 0}; разброс >1ч: $span"
        )
    }

    private fun mb(bytes: Long) = bytes / 1_048_576
}
