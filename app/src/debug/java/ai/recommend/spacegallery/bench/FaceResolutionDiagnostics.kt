package ai.recommend.spacegallery.bench

import ai.recommend.spacegallery.di.AppContainer
import ai.recommend.spacegallery.domain.MediaType
import ai.recommend.spacegallery.ml.VectorMath
import ai.recommend.spacegallery.ml.face.DetectedFace
import android.graphics.RectF
import androidx.core.net.toUri
import kotlinx.coroutines.flow.first
import java.util.Locale
import kotlin.math.max

/**
 * Влияет ли разрешение на распознавание (debug): лица вырезаются из превью 640 px (как сейчас)
 * и из оригинала в масштабе, где лицо занимает ~[FACE_SIZE] px. Сравниваются пары «разные люди»
 * (лица с одного снимка) — и, если есть подтверждённые люди, пары «тот же человек».
 * В лог идут только числа.
 */
class FaceResolutionDiagnostics(private val c: AppContainer) {

    private class Face(val mediaId: Long, val personId: Long?, val small: FloatArray, val large: FloatArray)

    suspend fun run(limitPhotos: Int) {
        val s = c.settings.current()
        val model = s.faceModel
        val rows = c.database.faceDao().getForClustering(s.hideSensitive, s.sensitiveThreshold, model.embedVersion)
        val byMedia = rows.groupBy { it.mediaId }
        val picked = byMedia.entries
            .sortedByDescending { (_, f) -> f.count { it.lockedPersonId != null } * 10 + f.size }
            .take(limitPhotos)
        OrtBenchmark.log("=== faceres: фото ${picked.size}, модель $model")

        val faces = ArrayList<Face>()
        var smallMs = 0L
        var largeMs = 0L
        for ((mediaId, group) in picked) {
            val uri = group.first().uri.toUri()
            val preview = c.bitmapLoader.load(uri, MediaType.IMAGE, 640) ?: continue
            val detected = c.faceDetector.detect(preview)
            // Крупный кадр декодируется один раз на снимок — под самое мелкое лицо на нём.
            val smallest = group.minOf { max(it.right - it.left, it.bottom - it.top) }
            val large = c.bitmapLoader.decodeForFace(uri, smallest, FACE_SIZE, MAX_SIDE)
            for (row in group) {
                val box = RectF(row.left * preview.width, row.top * preview.height, row.right * preview.width, row.bottom * preview.height)
                val match = detected.maxByOrNull { iou(it.box, box) }?.takeIf { iou(it.box, box) >= 0.5f } ?: continue
                val t0 = System.nanoTime()
                val small = c.faceEmbedder.embed(preview, match, model) ?: continue
                smallMs += (System.nanoTime() - t0) / 1_000_000

                // Те же ключевые точки, но на снимке в большем разрешении.
                val t1 = System.nanoTime()
                val big = large?.let { bitmap ->
                    val scaled = FloatArray(match.landmarks.size) { i ->
                        if (i % 2 == 0) match.landmarks[i] / preview.width * bitmap.width else match.landmarks[i] / preview.height * bitmap.height
                    }
                    c.faceEmbedder.embed(bitmap, DetectedFace(RectF(), scaled, match.score), model)
                }
                largeMs += (System.nanoTime() - t1) / 1_000_000
                if (big != null) faces += Face(mediaId, row.lockedPersonId, small, big)
            }
            preview.recycle()
            large?.recycle()
            if (faces.size >= MAX_FACES) break
        }
        preview(faces, smallMs, largeMs)
    }

    private fun preview(faces: List<Face>, smallMs: Long, largeMs: Long) {
        if (faces.isEmpty()) {
            OrtBenchmark.log("=== faceres: лиц не набралось")
            return
        }
        val drift = faces.map { VectorMath.dot(it.small, it.large) }.sorted()
        OrtBenchmark.log(
            "лиц ${faces.size}; превью ${smallMs / faces.size} мс/лицо, оригинал ${largeMs / faces.size} мс/лицо; " +
                String.format(Locale.ROOT, "сходство вектора превью и оригинала: медиана %.3f p10 %.3f", drift[drift.size / 2], drift[drift.size / 10])
        )
        val negSmall = ArrayList<Float>()
        val negLarge = ArrayList<Float>()
        val posSmall = ArrayList<Float>()
        val posLarge = ArrayList<Float>()
        for (i in faces.indices) for (j in i + 1 until faces.size) {
            val a = faces[i]
            val b = faces[j]
            when {
                a.personId != null && a.personId == b.personId -> {
                    posSmall += VectorMath.dot(a.small, b.small)
                    posLarge += VectorMath.dot(a.large, b.large)
                }
                a.mediaId == b.mediaId -> {
                    negSmall += VectorMath.dot(a.small, b.small)
                    negLarge += VectorMath.dot(a.large, b.large)
                }
            }
        }
        report("превью 640", posSmall, negSmall)
        report("оригинал", posLarge, negLarge)
        OrtBenchmark.log("=== faceres done")
    }

    private fun report(name: String, pos: List<Float>, neg: List<Float>) {
        if (neg.isEmpty()) return
        val q = neg.sorted()
        fun pct(list: List<Float>, x: Double) = list[((list.size - 1) * x).toInt()]
        val tail = String.format(Locale.ROOT, "разные (одно фото): медиана %.3f p95 %.3f p99 %.3f", pct(q, 0.5), pct(q, 0.95), pct(q, 0.99))
        if (pos.isEmpty()) {
            OrtBenchmark.log("$name: $tail; подтверждённых пар нет")
            return
        }
        val p = pos.sorted()
        val threshold = pct(q, 0.99)
        val missed = pos.count { it < threshold }.toDouble() / pos.size
        OrtBenchmark.log(
            String.format(
                Locale.ROOT, "%s: %s | тот же p5 %.3f медиана %.3f | при пороге p99 чужих теряется %.0f%% своих (пар %d/%d)",
                name, tail, pct(p, 0.05), pct(p, 0.5), missed * 100, pos.size, neg.size,
            )
        )
    }

    private fun iou(a: RectF, b: RectF): Float {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)
        if (right <= left || bottom <= top) return 0f
        val inter = (right - left) * (bottom - top)
        return inter / (a.width() * a.height() + b.width() * b.height() - inter)
    }

    private companion object {
        /** Сколько пикселей должно занимать лицо в декодированном кадре. */
        const val FACE_SIZE = 224
        const val MAX_SIDE = 2048
        /** Хватит для оценки: прогон должен уложиться в лимит воркера. */
        const val MAX_FACES = 400
    }
}
