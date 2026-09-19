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
                dao.deletePersonsExcept(listOf(-1L))
            }
            return
        }
        val dim = faces.first().embedding.size / 4
        // Плотная матрица сходства n² — при очень больших медиатеках кластеризуем самые чёткие
        // лица, остальные привязываем к ближайшему человеку по строгому порогу.
        // Лица, похожие на помеченные пользователем артефакты («это не лицо»), в людей не попадают
        // (кроме подтверждённых самим пользователем).
        val artifacts = dao.getArtifactEmbeddings().filter { it.size == dim * 4 }.map { VectorMath.fromBytes(it) }
        val looksLikeArtifact = { row: FaceClusterRow ->
            row.lockedPersonId == null && artifacts.isNotEmpty() && artifactSimilarity(VectorMath.fromBytes(row.embedding), artifacts) >= ARTIFACT_SIMILARITY
        }
        // Подтверждённые лица — первыми: они всегда попадают в плотную часть кластеризации.
        val valid = faces.filter { it.embedding.size == dim * 4 && !looksLikeArtifact(it) }
            .sortedWith(compareBy<FaceClusterRow> { it.lockedPersonId == null }.thenByDescending { it.pixelSize() * it.score })
        val n = valid.size
        val vectors = FloatArray(n * dim)
        valid.forEachIndexed { i, row -> VectorMath.fromBytes(row.embedding).copyInto(vectors, i * dim) }

        val minSimilarity = 1f - s.faceEps
        val core = minOf(n, MAX_DENSE_FACES)

        // Ручные правки как ограничения: подтверждённые лица — заранее вместе, «это не он» — запрет.
        val anchorOf = valid.map { it.lockedPersonId }
        val anchors = IntArray(core) { i -> anchorOf[i]?.toInt() ?: -1 }
        val indexOfFace = valid.withIndex().associate { (i, f) -> f.id to i }
        val rejected = dao.getRejections().groupBy({ it.personId }) { it.faceId }
        val lockedIndices = (0 until core).filter { anchorOf[it] != null }.groupBy { anchorOf[it]!! }
        val cannotLink = rejected.flatMap { (personId, faceIds) ->
            val anchorsOfPerson = lockedIndices[personId].orEmpty()
            faceIds.mapNotNull { indexOfFace[it] }.filter { it < core }.flatMap { f -> anchorsOfPerson.map { f to it } }
        }

        val coreLabels = averageLinkage(vectors, core, dim, minSimilarity, anchors, cannotLink)
        val clusters = (0 until core).groupBy { coreLabels[it] }.values
            // Подтверждённый человек остаётся, даже если у него меньше MIN_PTS лиц.
            .filter { members -> members.size >= MIN_PTS || members.any { anchorOf[it] != null } }
            .mapTo(ArrayList()) { it.toMutableList() }
        if (core < n) {
            attachRemaining(clusters, core until n, vectors, dim, minSimilarity + ATTACH_MARGIN) { face, cluster ->
                // Лицо «это не он» не привязываем к этому человеку.
                val person = cluster.firstNotNullOfOrNull { anchorOf[it] }
                person == null || valid[face].id !in rejected[person].orEmpty()
            }
        }
        knnReassign(clusters, valid, anchorOf, vectors, dim, rejected)
        clusters.removeAll { members -> members.size < MIN_PTS && members.none { anchorOf[it] != null } }
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
            // Подтверждённые люди закреплены за своими группами — их не может «унаследовать» другая.
            val used = clusters.mapNotNullTo(HashSet()) { members -> members.firstNotNullOfOrNull { anchorOf[it] } }
            val result = ArrayList<PersonEntity>()
            clusters.forEachIndexed { position, members ->
                val coverFace = covers[position]
                val cover = coverFace.id
                val avatar = avatarsByFace[cover]
                val faceIds = members.map { valid[it].id }
                val anchored = members.firstNotNullOfOrNull { anchorOf[it] }
                // Иначе — кому раньше принадлежало большинство лиц группы (его имя сохраняется).
                val previous = anchored ?: members.mapNotNull { valid[it].personId }.groupingBy { it }.eachCount()
                    .filter { (id, count) -> id !in used && count >= members.size * INHERIT_FRACTION }
                    .maxByOrNull { it.value }?.key
                val person = if (previous != null && previous in oldPersons) {
                    oldPersons.getValue(previous).copy(coverFaceId = cover, position = position, avatar = avatar, avatarFaceId = cover)
                } else {
                    // Подтверждённый человек, которого нет в таблице, восстанавливается с тем же id.
                    val fresh = PersonEntity(id = anchored ?: 0, coverFaceId = cover, position = position, avatar = avatar, avatarFaceId = cover)
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

    /**
     * Второй шаг после кластеризации: незакреплённые лица, уверенно похожие на подтверждённые
     * лица какого-то человека (k-NN), переходят в его группу — даже если средняя связь положила
     * их в другую, неподтверждённую группу или оставила одиночками.
     */
    private fun knnReassign(
        clusters: MutableList<MutableList<Int>>,
        valid: List<FaceClusterRow>,
        anchorOf: List<Long?>,
        vectors: FloatArray,
        dim: Int,
        rejected: Map<Long, List<Long>>,
    ) {
        val allExamples = valid.indices.filter { anchorOf[it] != null }.groupBy { anchorOf[it]!! }
            .mapValues { it.value.toIntArray() }
        if (allExamples.isEmpty()) return
        // Образцы — только согласованные подтверждённые лица (ошибочно подтверждённые чужие
        // лица иначе притягивали бы к человеку новых чужих — «снежный ком»).
        val (examples, dropped) = consistentExamples(vectors, dim, allExamples)
        for ((person, count) in dropped) {
            if (count > 0) Log.i(TAG, "k-NN: человек $person — подтверждено ${allExamples.getValue(person).size} лиц, несогласованных (не образцы) $count")
        }
        val rejectedSets = rejected.mapValues { it.value.toHashSet() }
        val candidates = valid.indices.filter { anchorOf[it] == null }.toIntArray()
        val assigned = knnAssign(vectors, dim, candidates, examples, isRejected = { face, person ->
            valid[face].id in rejectedSets[person].orEmpty()
        })
        if (assigned.isNotEmpty()) {
            val clusterOfPerson = HashMap<Long, MutableList<Int>>()
            for (cluster in clusters) cluster.firstNotNullOfOrNull { anchorOf[it] }?.let { clusterOfPerson[it] = cluster }
            val inCluster = HashMap<Int, MutableList<Int>>()
            for (cluster in clusters) for (i in cluster) inCluster[i] = cluster
            var moved = 0
            for ((face, person) in assigned) {
                val target = clusterOfPerson[person] ?: continue
                val current = inCluster[face]
                if (current === target) continue
                current?.remove(face)
                target += face
                moved++
            }
            Log.i(TAG, "k-NN: перенесено $moved лиц к подтверждённым людям (кандидатов ${assigned.size})")
        }
        val (correct, wrong, none) = knnSelfCheck(vectors, dim, examples)
        Log.i(TAG, "k-NN самопроверка на подтверждённых лицах: верно $correct, к другому $wrong, не привязано $none")
    }

    /** Среднее сходство лица с [KNN_K] самыми похожими помеченными артефактами. */
    private fun artifactSimilarity(face: FloatArray, artifacts: List<FloatArray>): Float =
        artifacts.map { VectorMath.dot(face, it) }.sortedDescending().take(KNN_K).average().toFloat()

    /** Лица сверх [MAX_DENSE_FACES] — к человеку с самым похожим центром, если сходство ≥ [threshold]. */
    private fun attachRemaining(
        clusters: List<MutableList<Int>>,
        rest: IntRange,
        vectors: FloatArray,
        dim: Int,
        threshold: Float,
        allowed: (face: Int, cluster: List<Int>) -> Boolean,
    ) {
        val centroids = clusters.map { members ->
            VectorMath.l2Normalize(FloatArray(dim) { k -> members.sumOf { vectors[it * dim + k].toDouble() }.toFloat() })
        }
        for (i in rest) {
            val face = vectors.copyOfRange(i * dim, (i + 1) * dim)
            val best = centroids.indices.filter { allowed(i, clusters[it]) }
                .maxByOrNull { VectorMath.dot(face, centroids[it]) } ?: continue
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

        /**
         * 1 — DBSCAN, 2 — средняя связь, 3 — + k-NN к подтверждённым, 4–5 — образцы k-NN только
         * из основной части подтверждённой группы. Увеличить при смене — люди пересоберутся.
         */
        const val ALGORITHM_VERSION = 5
        const val EDGE = 0.01f

        /** Матрица 3000² float — 36 МБ; дальше — привязка к готовым группам. */
        const val MAX_DENSE_FACES = 3000
        /** Привязка без участия в кластеризации — строже, чем сама группировка. */
        const val ATTACH_MARGIN = 0.1f

        /**
         * Похоже на помеченные артефакты (k-NN ≥ 0.5) — не лицо. На разметке пользователя:
         * отсекает 24/39 артефактов, теряет 1/710 настоящих лиц.
         */
        const val ARTIFACT_SIMILARITY = 0.5f
        const val MIN_PTS = 3
        /** Группа наследует прежнего человека, если к нему относилось ≥ 40% её лиц. */
        const val INHERIT_FRACTION = 0.4
    }
}
