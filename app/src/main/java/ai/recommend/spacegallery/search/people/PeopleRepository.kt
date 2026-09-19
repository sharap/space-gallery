package ai.recommend.spacegallery.search.people

import ai.recommend.spacegallery.data.db.FaceDao
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
    private val dao: FaceDao,
    private val media: MediaRepository,
) {
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

    suspend fun rename(personId: Long, name: String?) = dao.rename(personId, name?.trim()?.takeIf { it.isNotEmpty() })
}
