package ai.recommend.spacegallery.search.people

import ai.recommend.spacegallery.data.db.AppDatabase
import ai.recommend.spacegallery.data.db.FaceRejectionEntity
import ai.recommend.spacegallery.data.db.PersonPairDismissalEntity
import ai.recommend.spacegallery.ml.VectorMath
import androidx.room.withTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ai.recommend.spacegallery.data.repository.MediaRepository
import ai.recommend.spacegallery.domain.MediaItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Рамка лица в долях кадра (0..1); [label] — подпись под рамкой (имя человека). */
data class FaceBox(val left: Float, val top: Float, val right: Float, val bottom: Float, val label: String? = null)

/** Человек на фото: он сам и рамки его лиц (обычно одна). */
class PersonOnPhoto(val person: Person, val boxes: List<FaceBox>)

/** Человек на экране: имя (если задано), аватар — миниатюра типичного лица, число фото. */
class Person(val id: Long, val name: String?, val avatar: ByteArray?, val mediaCount: Int)

class PeopleRepository(
    private val db: AppDatabase,
    private val media: MediaRepository,
    private val builder: PeopleBuilder,
    /** Пересборка после правок доживает до конца, даже если уйти с экрана. */
    private val appScope: CoroutineScope,
) {
    private val dao = db.faceDao()

    val isRebuilding: StateFlow<Boolean> = builder.isRebuilding
    /** Люди, у которых остались видимые фото; сначала названные, дальше по числу фото. */
    fun observePeople(): Flow<List<Person>> = dao.observePersons().map { rows ->
        rows.filter { it.mediaCount > 0 }.map { Person(it.id, it.name, it.thumbnail, it.mediaCount) }
    }

    fun observeName(personId: Long): Flow<String?> = dao.observeName(personId)

    fun observeMedia(personId: Long): Flow<List<MediaItem>> = media.observePersonMedia(personId)

    /** Узнанные люди на фото (рамки их лиц и данные для чипов). */
    fun observePeopleOnMedia(mediaId: Long): Flow<List<PersonOnPhoto>> =
        dao.observePeopleOnMedia(mediaId).map { rows ->
            rows.groupBy { it.personId }.map { (personId, faces) ->
                val first = faces.first()
                PersonOnPhoto(
                    person = Person(personId, first.name, first.avatar, mediaCount = 0),
                    boxes = faces.map { FaceBox(it.left, it.top, it.right, it.bottom) },
                )
            }
        }

    /** Рамки лиц человека по фото (в долях кадра) — подсветка в просмотрщике. */
    fun observeFaceBoxes(personId: Long): Flow<Map<Long, List<FaceBox>>> =
        dao.observePersonFaceBoxes(personId).map { rows ->
            rows.groupBy({ it.mediaId }) { FaceBox(it.left, it.top, it.right, it.bottom) }
        }

    /** Имя подтверждает группу: текущие лица закрепляются за человеком (ошибки — через «это не он»). */
    suspend fun rename(personId: Long, name: String?) {
        val clean = name?.trim()?.takeIf { it.isNotEmpty() }
        db.withTransaction {
            dao.rename(personId, clean)
            if (clean != null) dao.lockFacesOf(personId)
        }
    }

    // --- Ручная настройка ---

    /**
     * «Это один человек»: лица всех [personIds] переходят к [target] и закрепляются за ним,
     * запреты «это не он» тоже переносятся. Остальные люди удаляются.
     */
    suspend fun merge(target: Long, personIds: Collection<Long>, name: String?) {
        val sources = personIds.filter { it != target }
        if (sources.isEmpty()) return
        db.withTransaction {
            dao.moveAndLock(sources, target)
            dao.moveRejections(sources, target)
            dao.deletePersons(sources)
            dao.rename(target, name?.trim()?.takeIf { it.isNotEmpty() })
        }
        rebuildInBackground()
    }

    /**
     * «Это не он»: лица человека на этих фото отвязываются и больше к нему не попадут;
     * остальные его лица закрепляются (группа подтверждена).
     */
    suspend fun removeFromPerson(personId: Long, mediaIds: Collection<Long>) {
        db.withTransaction {
            val faceIds = mediaIds.chunked(900).flatMap { dao.faceIdsOf(personId, it) }
            dao.insertRejections(faceIds.map { FaceRejectionEntity(it, personId) })
            faceIds.chunked(900).forEach { dao.detach(it) }
            dao.lockFacesOf(personId)
        }
        rebuildInBackground()
    }

    /** Подсказку «это тоже он?» для пары больше не показывать. */
    suspend fun dismissSuggestion(personId: Long, otherId: Long) {
        dao.insertDismissal(PersonPairDismissalEntity(minOf(personId, otherId), maxOf(personId, otherId)))
    }

    /**
     * Похожие на [personId] люди (близкий центр группы лиц) — возможно, тот же человек,
     * разделённый на группы (очки, возраст, свет). Отклонённые пары не предлагаются.
     */
    suspend fun suggestions(personId: Long, limit: Int = 3): List<Person> = withContext(Dispatchers.Default) {
        val byPerson = dao.getAssignedFaces().groupBy { it.personId!! }
        val centroids = byPerson.mapValues { (_, faces) ->
            val vs = faces.map { VectorMath.fromBytes(it.embedding) }
            VectorMath.l2Normalize(FloatArray(vs.first().size) { k -> vs.sumOf { it[k].toDouble() }.toFloat() })
        }
        val own = centroids[personId] ?: return@withContext emptyList()
        val dismissed = dao.getDismissals(personId).map { if (it.personA == personId) it.personB else it.personA }.toSet()
        val candidates = centroids
            .filter { (id, c) -> id != personId && id !in dismissed && VectorMath.dot(own, c) >= SUGGEST_MIN_SIMILARITY }
            .entries.sortedByDescending { VectorMath.dot(own, it.value) }
            .take(limit)
            .map { it.key }
        val people = dao.getPersonRows().associateBy { it.id }
        candidates.mapNotNull { id -> people[id]?.let { Person(it.id, it.name, it.thumbnail, it.mediaCount) } }
    }

    private fun rebuildInBackground() {
        appScope.launch { builder.rebuild() }
    }

    private companion object {
        /**
         * Центры групп одного человека в разных условиях похожи ≥ ~0.55; разные люди — ~0.1–0.3
         * (замерено на реальных лицах). 0.5 — с запасом, это лишь подсказка.
         */
        const val SUGGEST_MIN_SIMILARITY = 0.5f
    }
}
