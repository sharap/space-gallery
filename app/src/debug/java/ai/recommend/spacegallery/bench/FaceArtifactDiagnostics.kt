package ai.recommend.spacegallery.bench

import ai.recommend.spacegallery.di.AppContainer
import ai.recommend.spacegallery.domain.MediaType
import ai.recommend.spacegallery.ml.VectorMath
import ai.recommend.spacegallery.search.people.topKMean
import android.graphics.RectF
import androidx.core.net.toUri
import java.util.Locale
import kotlin.math.max

/**
 * Что отличает артефакты (лица «человека» с именем [artifactName], собранные пользователем)
 * от настоящих лиц (остальные названные люди): уверенность детектора, CLIP «это лицо», размер,
 * сходство SFace с артефактами (leave-one-out k-NN). В лог — только числа.
 */
class FaceArtifactDiagnostics(private val c: AppContainer) {

    suspend fun run(artifactName: String) {
        val dao = c.database.faceDao()
        val persons = dao.getPersons()
        val artifactIds = persons.filter { it.name.equals(artifactName, ignoreCase = true) }.map { it.id }.toSet()
        val namedIds = persons.filter { it.name != null && it.id !in artifactIds }.map { it.id }.toSet()
        val all = dao.getAllForCheck()
        val artifacts = all.filter { it.personId in artifactIds }
        val real = all.filter { it.personId in namedIds }
        OrtBenchmark.log("=== faceartifact: артефактов ${artifacts.size}, настоящих (названные люди) ${real.size}")
        if (artifacts.isEmpty()) {
            OrtBenchmark.log("=== faceartifact done: нет человека «$artifactName»")
            return
        }

        // Векторы SFace из БД.
        val embeddings = dao.getAssignedFaces().associate { it.id to VectorMath.fromBytes(it.embedding) }
        val dim = embeddings.values.first().size
        val artifactVecs = artifacts.mapNotNull { embeddings[it.id] }
        val pool = FloatArray(artifactVecs.size * dim).also { buf -> artifactVecs.forEachIndexed { i, v -> v.copyInto(buf, i * dim) } }

        class Row(val score: Float, val side: Float, val p: Float, val knnArtifact: Float)
        suspend fun features(rows: List<ai.recommend.spacegallery.data.db.FaceCheckRow>, isArtifact: Boolean): List<Row> {
            val out = ArrayList<Row>()
            for ((_, group) in rows.groupBy { it.mediaId }) {
                val bitmap = c.bitmapLoader.load(group.first().uri.toUri(), MediaType.IMAGE, 640) ?: continue
                for (f in group) {
                    val box = RectF(f.left * bitmap.width, f.top * bitmap.height, f.right * bitmap.width, f.bottom * bitmap.height)
                    val p = c.faceVerifier.faceProbability(bitmap, box) ?: continue
                    val e = embeddings[f.id] ?: continue
                    // Сходство с артефактами: среднее 3 ближайших (у самого артефакта — без него самого).
                    val tmp = FloatArray(pool.size + dim).also { pool.copyInto(it); e.copyInto(it, pool.size) }
                    val selfIndex = artifactVecs.size
                    val others = artifactVecs.indices.filter { !isArtifact || !artifactVecs[it].contentEquals(e) }.toIntArray()
                    val knn = topKMean(tmp, dim, selfIndex, others, FloatArray(3))
                    out += Row(f.score, max(box.width(), box.height()), p, knn)
                }
            }
            return out
        }
        val a = features(artifacts, true)
        val r = features(real, false)

        fun stat(name: String, get: (Row) -> Float) {
            fun q(rows: List<Row>): String {
                val v = rows.map(get).sorted()
                if (v.isEmpty()) return "—"
                return String.format(Locale.ROOT, "p10=%.2f p50=%.2f p90=%.2f", v[(v.size - 1) / 10], v[(v.size - 1) / 2], v[(v.size - 1) * 9 / 10])
            }
            OrtBenchmark.log("$name: артефакты ${q(a)} | настоящие ${q(r)}")
        }
        stat("уверенность детектора", Row::score)
        stat("размер лица, px@640  ", Row::side)
        stat("CLIP P(лицо)         ", Row::p)
        stat("SFace k-NN к артефактам", Row::knnArtifact)

        // Правила-кандидаты: сколько артефактов отсекают и сколько настоящих лиц теряют.
        fun rule(name: String, reject: (Row) -> Boolean) {
            OrtBenchmark.log(String.format(Locale.ROOT, "%-44s отсекает артефактов %3d/%d, теряет настоящих %3d/%d",
                name, a.count(reject), a.size, r.count(reject), r.size))
        }
        for (t in listOf(0.7f, 0.8f)) rule(String.format(Locale.ROOT, "уверенность < %.1f", t)) { it.score < t }
        for (t in listOf(0.2f, 0.3f, 0.5f)) rule(String.format(Locale.ROOT, "CLIP P(лицо) < %.1f", t)) { it.p < t }
        for (t in listOf(0.3f, 0.4f, 0.5f)) rule(String.format(Locale.ROOT, "SFace k-NN к артефактам ≥ %.1f", t)) { it.knnArtifact >= t }
        for (t in listOf(0.3f, 0.5f)) rule(String.format(Locale.ROOT, "уверенность < 0.8 и CLIP < %.1f", t)) { it.score < 0.8f && it.p < t }
        OrtBenchmark.log("=== faceartifact done")
    }
}
