package ai.recommend.spacegallery.bench

import ai.recommend.spacegallery.di.AppContainer
import ai.recommend.spacegallery.domain.MediaType
import android.graphics.RectF
import androidx.core.net.toUri
import java.util.Locale

/**
 * Диагностика проверки лиц через CLIP (debug): для найденных лиц считает вероятность «это лицо»
 * и сравнивает её у «узнанных» (попали к человеку — почти наверняка настоящие) и «неузнанных»
 * (среди них — ложные срабатывания). В лог — только числа.
 */
class FaceVerifyDiagnostics(private val c: AppContainer) {

    suspend fun run(names: List<String>) {
        val all = c.database.faceDao().getAllForCheck()
        val nameById = if (names.isEmpty()) emptyMap() else
            c.mediaRepository.getByIds(all.map { it.mediaId }.distinct()).associate { it.id to it.displayName }
        val faces = if (names.isEmpty()) all else all.filter { nameById[it.mediaId] in names }
        OrtBenchmark.log("=== faceverify: ${faces.size} лиц")

        class Row(val score: Float, val known: Boolean, val p: Float)
        val rows = ArrayList<Row>()
        for ((mediaId, group) in faces.groupBy { it.mediaId }) {
            val bitmap = c.bitmapLoader.load(group.first().uri.toUri(), MediaType.IMAGE, 640) ?: continue
            for (f in group) {
                val box = RectF(f.left * bitmap.width, f.top * bitmap.height, f.right * bitmap.width, f.bottom * bitmap.height)
                val p = c.faceVerifier.faceProbability(bitmap, box) ?: continue
                rows += Row(f.score, f.personId != null, p)
                if (names.isNotEmpty()) {
                    OrtBenchmark.log(String.format(Locale.ROOT, "%s: лицо score=%.2f человек=%s P(лицо)=%.3f", nameById[mediaId], f.score, f.personId ?: "—", p))
                }
            }
        }
        fun report(label: String, subset: List<Row>) {
            if (subset.isEmpty()) return
            val ps = subset.map { it.p }.sorted()
            fun pct(q: Double) = ps[((ps.size - 1) * q).toInt()]
            val below = listOf(0.1f, 0.3f, 0.5f).joinToString { t -> String.format(Locale.ROOT, "<%.1f: %d", t, ps.count { it < t }) }
            OrtBenchmark.log(String.format(Locale.ROOT, "%-34s n=%4d  P(лицо) p10=%.2f p50=%.2f p90=%.2f | %s", label, ps.size, pct(0.1), pct(0.5), pct(0.9), below))
        }
        for ((lo, hi) in listOf(0.6f to 0.7f, 0.7f to 0.8f, 0.8f to 1.01f)) {
            val band = rows.filter { it.score >= lo && it.score < hi }
            report(String.format(Locale.ROOT, "score %.1f–%.1f узнанные", lo, minOf(hi, 1f)), band.filter { it.known })
            report(String.format(Locale.ROOT, "score %.1f–%.1f неузнанные", lo, minOf(hi, 1f)), band.filter { !it.known })
        }
        OrtBenchmark.log("=== faceverify done")
    }
}
