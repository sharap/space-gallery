package ai.recommend.spacegallery.search.people

import ai.recommend.spacegallery.data.db.AppDatabase
import ai.recommend.spacegallery.data.db.PersonEntity
import ai.recommend.spacegallery.data.settings.SettingsRepository
import ai.recommend.spacegallery.ml.VectorMath
import ai.recommend.spacegallery.data.db.FaceClusterRow
import android.util.Log
import androidx.core.net.toUri
import androidx.room.withTransaction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Люди: агломеративная кластеризация со средней связью по векторам лиц SFace.
 *
 * DBSCAN для лиц не подошёл (проверено на реальной медиатеке, 1189 лиц, 2026-09-19): через
 * цепочки похожих лиц он склеивал разных людей — крупнейшая группа 766 лиц, из них 270 с
 * сходством с центром группы ниже порога «тот же человек». Средняя связь (сходство ≥ 0.35):
 * 87 человек, 0 таких лиц.
 *
 * Имена, данные пользователем, переживают пересчёт: новая группа наследует человека,
 * которому раньше принадлежало большинство её лиц.
 */
class PeopleBuilder(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val avatars: AvatarRenderer,
) {
    private val dao = db.faceDao()
    private val mutex = Mutex()
    private val _isRebuilding = MutableStateFlow(false)
    val isRebuilding: StateFlow<Boolean> = _isRebuilding.asStateFlow()

    /**
     * Людей ещё нет (а лица есть), у кого-то нет аватара из оригинала или сменился алгоритм
     * группировки (после обновления приложения).
     */
    suspend fun needsRebuild(): Boolean {
        if (dao.countFaces() < MIN_PTS) return false
        return dao.getPersons().isEmpty() ||
            dao.countPersonsWithoutAvatar() > 0 ||
            settings.peopleAlgorithmVersion() < ALGORITHM_VERSION
    }

    suspend fun rebuild() = mutex.withLock {
        _isRebuilding.value = true
        try {
            rebuildLocked()
        } finally {
            _isRebuilding.value = false
        }
    }

    private suspend fun rebuildLocked() {
        val s = settings.current()
        val faces = dao.getForClustering(s.hideSensitive, s.sensitiveThreshold)
        val oldPersons = dao.getPersons().associateBy { it.id }
        if (faces.size < MIN_PTS) {
            db.withTransaction {
                dao.clearAssignments()
                dao.deleteAllPersons()
            }
            return
        }
        val dim = faces.first().embedding.size / 4
        // Плотная матрица сходства n² — при очень больших медиатеках кластеризуем самые чёткие
        // лица, остальные привязываем к ближайшему человеку по строгому порогу.
        val valid = faces.filter { it.embedding.size == dim * 4 }
            .sortedByDescending { it.pixelSize() * it.score }
        val n = valid.size
        val vectors = FloatArray(n * dim)
        valid.forEachIndexed { i, row -> VectorMath.fromBytes(row.embedding).copyInto(vectors, i * dim) }

        val minSimilarity = 1f - s.faceEps
        val core = minOf(n, MAX_DENSE_FACES)
        val coreLabels = averageLinkage(vectors, core, dim, minSimilarity)
        val clusters = (0 until core).groupBy { coreLabels[it] }.values
            .filter { it.size >= MIN_PTS }
            .mapTo(ArrayList()) { it.toMutableList() }
        if (core < n) attachRemaining(clusters, core until n, vectors, dim, minSimilarity + ATTACH_MARGIN)
        clusters.sortByDescending { it.size }

        // Обложки выбираются и рисуются до транзакции: декодирование оригиналов — долгое.
        val covers = clusters.map { members -> chooseCover(members.map { valid[it] }, members, vectors, dim) }
        val avatarsByFace = HashMap<Long, ByteArray?>()
        for (cover in covers) {
            val reusable = oldPersons.values.firstOrNull { it.avatarFaceId == cover.id && it.avatar != null }
            avatarsByFace[cover.id] = reusable?.avatar
                ?: avatars.render(cover.uri.toUri(), cover.left, cover.top, cover.right, cover.bottom)
        }

        db.withTransaction {
            dao.clearAssignments()
            val used = HashSet<Long>()
            val result = ArrayList<PersonEntity>()
            clusters.forEachIndexed { position, members ->
                val coverFace = covers[position]
                val cover = coverFace.id
                val avatar = avatarsByFace[cover]
                val faceIds = members.map { valid[it].id }
                // Кому раньше принадлежало большинство лиц группы — тот человек (и его имя) сохраняется.
                val previous = members.mapNotNull { valid[it].personId }.groupingBy { it }.eachCount()
                    .filter { (id, count) -> id !in used && count >= members.size * INHERIT_FRACTION }
                    .maxByOrNull { it.value }?.key
                val person = if (previous != null && previous in oldPersons) {
                    oldPersons.getValue(previous).copy(coverFaceId = cover, position = position, avatar = avatar, avatarFaceId = cover)
                } else {
                    val fresh = PersonEntity(coverFaceId = cover, position = position, avatar = avatar, avatarFaceId = cover)
                    fresh.copy(id = dao.insertPerson(fresh))
                }
                used += person.id
                result += person
                faceIds.chunked(900).forEach { dao.assign(it, person.id) }
            }
            dao.updatePersons(result)
            dao.deletePersonsExcept(result.map { it.id }.ifEmpty { listOf(-1L) })
        }
        settings.setPeopleAlgorithmVersion(ALGORITHM_VERSION)
        Log.i(TAG, "Люди: ${clusters.size} из $n лиц, в группах ${clusters.sumOf { it.size }}")
    }

    /** Лица сверх [MAX_DENSE_FACES] — к человеку с самым похожим центром, если сходство ≥ [threshold]. */
    private fun attachRemaining(clusters: List<MutableList<Int>>, rest: IntRange, vectors: FloatArray, dim: Int, threshold: Float) {
        val centroids = clusters.map { members ->
            VectorMath.l2Normalize(FloatArray(dim) { k -> members.sumOf { vectors[it * dim + k].toDouble() }.toFloat() })
        }
        for (i in rest) {
            val face = vectors.copyOfRange(i * dim, (i + 1) * dim)
            val best = centroids.indices.maxByOrNull { VectorMath.dot(face, centroids[it]) } ?: return
            if (VectorMath.dot(face, centroids[best]) >= threshold) clusters[best] += i
        }
    }

    /**
     * Обложка человека: из более типичной половины лиц группы (чтобы это точно был он) —
     * самое крупное в оригинальном разрешении с учётом уверенности детектора. Лица,
     * обрезанные краем кадра, — только если других нет.
     */
    private fun chooseCover(faces: List<FaceClusterRow>, members: List<Int>, vectors: FloatArray, dim: Int): FaceClusterRow {
        val centroid = VectorMath.l2Normalize(FloatArray(dim) { k -> members.sumOf { vectors[it * dim + k].toDouble() }.toFloat() })
        val typicality = members.map { VectorMath.dot(centroid, vectors, it * dim) }
        val threshold = typicality.sorted()[typicality.size / 2]
        val candidates = faces.indices.filter { typicality[it] >= threshold }.map { faces[it] }
        val inside = candidates.filterNot { it.touchesEdge() }.ifEmpty { candidates }
        return inside.maxBy { it.pixelSize() * it.score }
    }

    /** Сторона лица в пикселях оригинала (по MediaStore; без размеров — в долях кадра). */
    private fun FaceClusterRow.pixelSize(): Float {
        val w = if (width > 0) width else 1
        val h = if (height > 0) height else 1
        return kotlin.math.sqrt((right - left) * w * (bottom - top) * h)
    }

    private fun FaceClusterRow.touchesEdge() =
        left < EDGE || top < EDGE || right > 1f - EDGE || bottom > 1f - EDGE

    private companion object {
        const val TAG = "People"

        /** 1 — DBSCAN, 2 — средняя связь. Увеличить при смене алгоритма — люди пересоберутся. */
        const val ALGORITHM_VERSION = 2
        const val EDGE = 0.01f

        /** Матрица 3000² float — 36 МБ; дальше — привязка к готовым группам. */
        const val MAX_DENSE_FACES = 3000
        /** Привязка без участия в кластеризации — строже, чем сама группировка. */
        const val ATTACH_MARGIN = 0.1f
        const val MIN_PTS = 3
        /** Группа наследует прежнего человека, если к нему относилось ≥ 40% её лиц. */
        const val INHERIT_FRACTION = 0.4
    }
}
