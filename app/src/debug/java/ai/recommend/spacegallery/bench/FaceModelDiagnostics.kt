package ai.recommend.spacegallery.bench

import ai.onnxruntime.OnnxTensor
import ai.recommend.spacegallery.di.AppContainer
import ai.recommend.spacegallery.domain.MediaType
import ai.recommend.spacegallery.ml.VectorMath
import ai.recommend.spacegallery.ml.face.DetectedFace
import ai.recommend.spacegallery.ml.onnx.ModelId
import ai.recommend.spacegallery.ml.onnx.floatOutput
import android.graphics.Bitmap
import android.graphics.RectF
import androidx.core.net.toUri
import java.nio.FloatBuffer
import java.util.Locale

/**
 * Сравнение моделей распознавания лиц (debug): SFace против ArcFace на разметке пользователя.
 * Положительные пары — подтверждённые лица одного человека, отрицательные — лица с одного
 * снимка (один человек не бывает дважды на кадре). В лог идут только числа.
 *
 * Модель кладётся в filesDir/models/face_embed_arc.onnx (см. models/README.md).
 */
class FaceModelDiagnostics(private val c: AppContainer) {

    private class Sample(
        val faceId: Long,
        val personId: Long?,
        val mediaId: Long,
        val sface: FloatArray,
        val arcRgb: FloatArray,
        val arcBgr: FloatArray,
    )

    suspend fun run(limitPhotos: Int, modelFile: String, std: Float, bothOrders: Boolean) {
        val dao = c.database.faceDao()
        val s = c.settings.current()
        val faces = dao.getForClustering(s.hideSensitive, s.sensitiveThreshold, s.faceModel.embedVersion)
        val byMedia = faces.groupBy { it.mediaId }
        // Нужны фото с подтверждёнными лицами (положительные пары) и с несколькими лицами (отрицательные).
        val wanted = byMedia.filter { (_, f) -> f.any { it.lockedPersonId != null } || f.size >= 2 }
            .entries.sortedByDescending { (_, f) -> f.count { it.lockedPersonId != null } }
            .take(limitPhotos)
        OrtBenchmark.log("=== facemodel: фото ${wanted.size}, лиц ${wanted.sumOf { it.value.size }}")

        val file = java.io.File(java.io.File(c.appContext.filesDir, "models"), modelFile)
        if (!file.exists()) {
            OrtBenchmark.log("=== facemodel: нет файла $modelFile")
            return
        }
        val arcSession = c.onnx.createSession(file.absolutePath)
        OrtBenchmark.log("модель $modelFile, нормализация (x − 127.5) / $std")
        val info = arcSession.inputInfo.entries.first()
        OrtBenchmark.log("вход ArcFace: ${info.key} ${info.value.info}")

        val samples = ArrayList<Sample>()
        var arcMs = 0L
        var sfaceMs = 0L
        for ((mediaId, rows) in wanted) {
            val uri = rows.first().uri.toUri()
            val bitmap = c.bitmapLoader.load(uri, MediaType.IMAGE, 640) ?: continue
            val detected = c.faceDetector.detect(bitmap, 0.6f)
            for (row in rows) {
                val box = RectF(row.left * bitmap.width, row.top * bitmap.height, row.right * bitmap.width, row.bottom * bitmap.height)
                val face = detected.maxByOrNull { iou(it.box, box) }?.takeIf { iou(it.box, box) >= 0.5f } ?: continue
                val t0 = System.nanoTime()
                val sface = c.faceEmbedder.embed(bitmap, face, s.faceModel) ?: continue
                sfaceMs += (System.nanoTime() - t0) / 1_000_000
                val t1 = System.nanoTime()
                val arcRgb = embedArc(arcSession, bitmap, face, bgr = false, std = std) ?: continue
                // Порядок каналов проверяется только по просьбе: он удваивает время прогона.
                val arcBgr = if (bothOrders) embedArc(arcSession, bitmap, face, bgr = true, std = std) ?: continue else arcRgb
                arcMs += (System.nanoTime() - t1) / 1_000_000
                samples += Sample(row.id, row.lockedPersonId, mediaId, sface, arcRgb, arcBgr)
            }
            bitmap.recycle()
        }
        arcSession.close()
        OrtBenchmark.log("посчитано лиц ${samples.size}; SFace ${sfaceMs / maxOf(samples.size, 1)} мс/лицо, ArcFace ${arcMs / maxOf(samples.size, 1)} мс/лицо")

        val models = listOfNotNull<Pair<String, (Sample) -> FloatArray>>(
            "SFace     " to { it.sface },
            "новая RGB " to { it.arcRgb },
            ("новая BGR " to { s: Sample -> s.arcBgr }).takeIf { bothOrders },
        )
        val pos = models.map { ArrayList<Float>() }
        val neg = models.map { ArrayList<Float>() }
        var samePhoto = 0
        for (i in samples.indices) for (j in i + 1 until samples.size) {
            val a = samples[i]
            val b = samples[j]
            val target = when {
                a.personId != null && a.personId == b.personId -> pos
                a.mediaId == b.mediaId -> neg.also { samePhoto++ }
                a.personId != null && b.personId != null -> neg
                else -> null
            } ?: continue
            models.forEachIndexed { k, (_, get) -> target[k] += VectorMath.dot(get(a), get(b)) }
        }
        OrtBenchmark.log(
            "подтверждённых лиц в выборке ${samples.count { it.personId != null }} у ${samples.mapNotNull { it.personId }.distinct().size} человек; " +
                "пар: тот же ${pos[0].size}, разные ${neg[0].size} (из них с одного фото $samePhoto)"
        )
        models.forEachIndexed { k, (name, _) -> report(name, pos[k], neg[k]) }
        OrtBenchmark.log("=== facemodel done")
    }

    private fun report(name: String, pos: List<Float>, neg: List<Float>) {
        if (pos.isEmpty() || neg.isEmpty()) return
        val p = pos.sorted()
        val q = neg.sorted()
        fun pct(list: List<Float>, x: Double) = list[((list.size - 1) * x).toInt()]
        // Доля ошибок при пороге, отсекающем 1% чужих пар.
        val threshold = pct(q, 0.99)
        val missed = pos.count { it < threshold }.toDouble() / pos.size
        OrtBenchmark.log(
            String.format(
                Locale.ROOT,
                "%s: тот же p5 %.3f медиана %.3f | разные медиана %.3f p99 %.3f | при пороге p99 чужих (%.3f) теряется %.0f%% своих | AUC %.4f",
                name, pct(p, 0.05), pct(p, 0.5), pct(q, 0.5), threshold, threshold, missed * 100, auc(pos, neg),
            )
        )
    }

    private fun auc(pos: List<Float>, neg: List<Float>): Double {
        val all = (pos.map { it to 1 } + neg.map { it to 0 }).sortedBy { it.first }
        var rank = 0L
        var sum = 0L
        for ((i, v) in all.withIndex()) if (v.second == 1) sum += (i + 1).toLong()
        rank = sum - pos.size.toLong() * (pos.size + 1) / 2
        return rank.toDouble() / (pos.size.toDouble() * neg.size)
    }

    private fun embedArc(session: ai.onnxruntime.OrtSession, bitmap: Bitmap, face: DetectedFace, bgr: Boolean, std: Float): FloatArray? {
        val aligned = c.faceEmbedder.alignForDiagnostics(bitmap, face.landmarks)
        val pixels = IntArray(SIZE * SIZE)
        aligned.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        aligned.recycle()
        // Формат входа берём из самой модели: [1,112,112,3] — NHWC, [1,3,112,112] — NCHW.
        val nhwc = session.inputInfo.values.first().info.toString().contains("112, 112, 3")
        val buffer = FloatBuffer.allocate(3 * SIZE * SIZE)
        val plane = SIZE * SIZE
        for (i in 0 until plane) {
            val p = pixels[i]
            val first = (((p shr (if (bgr) 0 else 16)) and 0xFF) - 127.5f) / std
            val g = (((p shr 8) and 0xFF) - 127.5f) / std
            val last = (((p shr (if (bgr) 16 else 0)) and 0xFF) - 127.5f) / std
            val r = first
            val b = last
            if (nhwc) {
                buffer.put(i * 3, r); buffer.put(i * 3 + 1, g); buffer.put(i * 3 + 2, b)
            } else {
                buffer.put(i, r); buffer.put(plane + i, g); buffer.put(2 * plane + i, b)
            }
        }
        val shape = if (nhwc) longArrayOf(1, SIZE.toLong(), SIZE.toLong(), 3) else longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong())
        return OnnxTensor.createTensor(c.models.env, buffer, shape).use { tensor ->
            session.run(mapOf(session.inputNames.first() to tensor)).use { VectorMath.l2Normalize(it.floatOutput()) }
        }
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
        const val SIZE = 112
    }
}
