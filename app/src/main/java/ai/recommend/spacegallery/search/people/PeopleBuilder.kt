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

    /**
     * Счётчик ручных правок. Пересчёт длится десятки секунд, и правки, сделанные за это время,
     * затирались его результатом (он читает лица в начале). Теперь такой результат не
     * записывается, а пересчёт повторяется на свежих данных.
     */
    private val edits = java.util.concurrent.atomic.AtomicLong()

    /** Ждёт ли своей очереди ещё один пересчёт: подряд идущие правки не копят очередь. */
    private val queued = java.util.concurrent.atomic.AtomicBoolean()

    fun onUserEdit() {
        edits.incrementAndGet()
    }
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

    suspend fun rebuild() {
        // Пока один пересчёт идёт, а другой уже ждёт, третий не нужен: данные он прочитает те же.
        if (!queued.compareAndSet(false, true)) return
        mutex.withLock {
            queued.set(false)
            _isRebuilding.value = true
            try {
                var attempt = 0
                while (true) {
                    val version = edits.get()
                    if (rebuildLocked(version) || ++attempt >= MAX_ATTEMPTS) break
                    Log.i(TAG, "Правки во время пересчёта — считаем заново (попытка $attempt)")
                }
            } finally {
                _isRebuilding.value = false
            }
        }
    }

    /** @return false, если во время пересчёта были правки и результат не записан. */
    private suspend fun rebuildLocked(version: Long): Boolean {
        val s = settings.current()
        // Только лица текущей модели: при её смене остальные ждут пересчёта вектора.
        val faces = loadFaces(s.hideSensitive, s.sensitiveThreshold, s.faceModel.embedVersion)
        val oldPersons = dao.getPersons().associateBy { it.id }
        if (faces.size < MIN_PTS) {
            db.withTransaction {
                dao.clearAssignments()
                dao.deletePersonsExcept(listOf(-1L))
            }
            return true
        }
        val dim = faces.first().embedding.size / 4
        // Лица, похожие на помеченные пользователем артефакты («это не лицо»), в людей не попадают
        // (кроме подтверждённых самим пользователем).
        val artifacts = dao.getArtifactEmbeddings(s.faceModel.embedVersion).filter { it.size == dim * 4 }.map { VectorMath.fromBytes(it) }
        val looksLikeArtifact = { row: FaceClusterRow ->
            row.lockedPersonId == null && artifacts.isNotEmpty() && artifactSimilarity(VectorMath.fromBytes(row.embedding), artifacts) >= ARTIFACT_SIMILARITY
        }
        val valid = faces.filter { it.embedding.size == dim * 4 && !looksLikeArtifact(it) }
            .sortedWith(compareBy<FaceClusterRow> { it.lockedPersonId == null }.thenByDescending { it.pixelSize() * it.score })
        val n = valid.size
        val vectors = FloatArray(n * dim)
        valid.forEachIndexed { i, row -> VectorMath.fromBytes(row.embedding).copyInto(vectors, i * dim) }

        val minSimilarity = 1f - s.faceEps

        // Ручные правки как ограничения: подтверждённые лица — заранее вместе, «это не он» — запрет.
        val anchorOf = valid.map { it.lockedPersonId }
        val anchors = IntArray(n) { i -> anchorOf[i]?.toInt() ?: -1 }
        val indexOfFace = valid.withIndex().associate { (i, f) -> f.id to i }
        val rejected = dao.getRejections().groupBy({ it.personId }) { it.faceId }
        val rejectedPersons = HashMap<Int, MutableSet<Int>>()
        for ((personId, faceIds) in rejected) {
            for (faceId in faceIds) {
                val index = indexOfFace[faceId] ?: continue
                rejectedPersons.getOrPut(index) { HashSet() } += personId.toInt()
            }
        }
        // Коллаж: на снимке есть почти одинаковые лица (один человек на разных кадрах). Правило
        // «два лица с одного снимка — разные люди» там неверно, поэтому снимок из него исключаем:
        // даём каждому его лицу свой «снимок», чтобы пары не штрафовались.
        val mediaOf = LongArray(n) { valid[it].mediaId }
        val collages = collageMedia(valid, vectors, dim)
        if (collages.isNotEmpty()) {
            for (i in 0 until n) if (mediaOf[i] in collages) mediaOf[i] = -(i + 1).toLong()
            Log.i(TAG, "Коллажей (снимки с повторяющимися лицами): ${collages.size}")
        }

        val labels = sparseAverageLinkage(vectors, n, dim, minSimilarity, mediaOf, anchors, rejectedPersons)
        val clusters = (0 until n).groupBy { labels[it] }.values
            // Подтверждённый человек остаётся, даже если у него меньше MIN_PTS лиц.
            .filter { members -> members.size >= MIN_PTS || members.any { anchorOf[it] != null } }
            .mapTo(ArrayList()) { it.toMutableList() }
        knnReassign(clusters, valid, anchorOf, vectors, dim, rejected)
        attachLeftovers(clusters, valid, anchorOf, vectors, dim, rejected)
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

        var applied = true
        db.withTransaction {
            // Правка пользователя во время пересчёта делает результат устаревшим: он бы вернул
            // фото на прежние места. Ничего не пишем — пересчёт повторится на свежих данных.
            if (edits.get() != version) {
                applied = false
                return@withTransaction
            }
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
                    // Строку могли удалить, пока шёл пересчёт (сброс людей) — создаём заново.
                    oldPersons.getValue(previous).copy(coverFaceId = cover, position = position, avatar = avatar, avatarFaceId = cover)
                        .also { dao.upsertPerson(it) }
                } else {
                    // Подтверждённый человек, которого нет в таблице, восстанавливается с тем же id.
                    val fresh = PersonEntity(id = anchored ?: 0, coverFaceId = cover, position = position, avatar = avatar, avatarFaceId = cover)
                    fresh.copy(id = dao.insertPerson(fresh))
                }
                used += person.id
                result += person
                // Лица привязываются только после того, как строка человека точно есть в базе.
                faceIds.chunked(900).forEach { dao.assign(it, person.id) }
            }
            dao.updatePersons(result)
            dao.deletePersonsExcept(result.map { it.id }.ifEmpty { listOf(-1L) })
        }
        if (!applied) return false
        settings.setPeopleAlgorithmVersion(ALGORITHM_VERSION)
        Log.i(TAG, "Люди: ${clusters.size} из $n лиц, в группах ${clusters.sumOf { it.size }}")
        return true
    }

    /**
     * Второй шаг после кластеризации: незакреплённые лица, уверенно похожие на подтверждённые
     * лица какого-то человека (k-NN), переходят в его группу — даже если средняя связь положила
     * их в другую, неподтверждённую группу или оставила одиночками.
     */
    /**
     * Лица, не попавшие ни в одну группу, — к подходящей уже собранной группе.
     *
     * [knnReassign] выше опирается только на подтверждённые пользователем лица, а их мало:
     * на медиатеке 2026-09-24 так привязывается 386 ничейных лиц из 4012, тогда как по всем
     * лицам собранных групп — 906. Правило то же (среднее по трём самым похожим лицам группы
     * плюс отрыв от второй по похожести группы): на уже размеченных лицах оно попадает верно
     * в 98.3% случаев.
     *
     * Лицо не привязывается к группе, в которой уже есть лицо с этого же снимка: два лица на
     * одном кадре — разные люди (коллажи из этого правила исключены раньше).
     */
    private fun attachLeftovers(
        clusters: MutableList<MutableList<Int>>,
        valid: List<FaceClusterRow>,
        anchorOf: List<Long?>,
        vectors: FloatArray,
        dim: Int,
        rejected: Map<Long, List<Long>>,
    ) {
        if (clusters.isEmpty()) return
        val inCluster = BooleanArray(valid.size)
        for (cluster in clusters) for (face in cluster) inCluster[face] = true
        val candidates = valid.indices.filter { !inCluster[it] }.toIntArray()
        if (candidates.isEmpty()) return

        val examples = clusters.indices.associate { it.toLong() to clusters[it].toIntArray() }
        val anchorOfCluster = clusters.map { members -> members.firstNotNullOfOrNull { anchorOf[it] } }
        val rejectedSets = rejected.mapValues { it.value.toHashSet() }
        val mediaOfCluster = clusters.map { members -> members.mapTo(HashSet()) { valid[it].mediaId } }

        val assigned = knnAssign(vectors, dim, candidates, examples, isRejected = { face, cluster ->
            val index = cluster.toInt()
            valid[face].mediaId in mediaOfCluster[index] ||
                anchorOfCluster[index]?.let { valid[face].id in rejectedSets[it].orEmpty() } == true
        })
        for ((face, cluster) in assigned) clusters[cluster.toInt()] += face
        if (assigned.isNotEmpty()) Log.i(TAG, "Ничейных лиц привязано к группам: ${assigned.size}")
    }

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

    /** Лица для группировки читаются страницами: одной выборкой 13 тысяч строк курсор не тянет. */
    private suspend fun loadFaces(hideSensitive: Boolean, threshold: Float, embedVersion: Int): List<FaceClusterRow> {
        val all = ArrayList<FaceClusterRow>()
        var afterId = 0L
        while (true) {
            val page = dao.getForClusteringPage(hideSensitive, threshold, embedVersion, afterId, FACE_PAGE)
            if (page.isEmpty()) break
            all += page
            afterId = page.last().id
        }
        return all
    }

    /**
     * Снимки, где два лица почти одинаковы: коллажи, фото фотографий, отражения. На реальной
     * медиатеке (13 250 лиц, 2026-09-20) таких снимков 50 — 236 пар, из них ни одной с
     * пересечением рамок, то есть это не двойные срабатывания детектора.
     */
    private fun collageMedia(valid: List<FaceClusterRow>, vectors: FloatArray, dim: Int): Set<Long> {
        val result = HashSet<Long>()
        val byMedia = valid.indices.groupBy { valid[it].mediaId }
        for ((mediaId, faces) in byMedia) {
            if (faces.size < 2) continue
            outer@ for (a in faces.indices) {
                for (b in a + 1 until faces.size) {
                    if (VectorMath.dot(vectors, faces[a] * dim, vectors, faces[b] * dim, dim) >= COLLAGE_SIMILARITY) {
                        result += mediaId
                        break@outer
                    }
                }
            }
        }
        return result
    }

    /** Среднее сходство лица с [KNN_K] самыми похожими помеченными артефактами. */
    private fun artifactSimilarity(face: FloatArray, artifacts: List<FloatArray>): Float =
        artifacts.map { VectorMath.dot(face, it) }.sortedDescending().take(KNN_K).average().toFloat()

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
         * из основной части подтверждённой группы, 6 — все лица участвуют в группировке
         * (без предела в 3000) и штраф за пару лиц с одного снимка, 7 — группа от двух лиц
         * и привязка ничейных лиц ко всем собранным группам, а не только к подтверждённым.
         * Увеличить при смене — люди пересоберутся.
         */
        const val ALGORITHM_VERSION = 7
        const val EDGE = 0.01f

        /**
         * Похоже на помеченные артефакты (k-NN ≥ 0.5) — не лицо. На разметке пользователя:
         * отсекает 24/39 артефактов, теряет 1/710 настоящих лиц.
         */
        const val ARTIFACT_SIMILARITY = 0.5f
        /** Почти одинаковые лица на одном снимке — это коллаж, а не двое разных людей. */
        const val COLLAGE_SIMILARITY = 0.8f
        /** Сколько раз повторять пересчёт, если пользователь продолжает править. */
        const val MAX_ATTEMPTS = 5
        const val FACE_PAGE = 2000
        /**
         * Сколько лиц нужно, чтобы считать группу человеком.
         *
         * Было 3, и на групповых снимках это заметно: человек, попавший в медиатеку дважды,
         * не получал группы, а его лица оставались ничейными — на фото у них не было ни рамки,
         * ни подписи. На медиатеке 2026-09-24 таких лиц (ровно один похожий сосед) — 973 из
         * 4012 ничейных. Разные люди похожи друг на друга сильнее 0.35 лишь в 0.2% пар,
         * так что пара — это почти всегда действительно один человек.
         */
        const val MIN_PTS = 2
        /** Группа наследует прежнего человека, если к нему относилось ≥ 40% её лиц. */
        const val INHERIT_FRACTION = 0.4
    }
}
