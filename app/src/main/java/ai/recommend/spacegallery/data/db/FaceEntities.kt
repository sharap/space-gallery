package ai.recommend.spacegallery.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Найденное лицо. Рамка — в долях размера кадра (0..1), не зависит от разрешения превью. */
@Entity(
    tableName = "face",
    indices = [Index("mediaId"), Index("personId")],
    foreignKeys = [
        ForeignKey(entity = MediaEntity::class, parentColumns = ["id"], childColumns = ["mediaId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = PersonEntity::class, parentColumns = ["id"], childColumns = ["personId"], onDelete = ForeignKey.SET_NULL),
    ],
)
data class FaceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mediaId: Long,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val score: Float,
    /** Вектор лица (float32 LE, L2-нормализован); длина зависит от модели. */
    val embedding: ByteArray,
    /** Версия модели, которой посчитан [embedding] (см. FaceEmbedder.EMBED_VERSION). */
    @ColumnInfo(defaultValue = "1") val embedVersion: Int = 1,
    /** 5 ключевых точек в долях кадра (x,y × 5, float32 LE) — выравнивание без повторного поиска. */
    val landmarks: ByteArray? = null,
    /** Миниатюра лица для аватара (JPEG ~128 px); удаляется вместе со строкой. */
    val thumbnail: ByteArray,
    val personId: Long? = null,
    /**
     * Лицо подтверждено пользователем как этот человек (дал имя, объединил людей). Такие лица
     * при пересчёте всегда остаются вместе, а разные подтверждённые люди не сливаются.
     */
    val lockedPersonId: Long? = null,
    /**
     * Пользователь пометил: это не лицо (узор, кружка…). Не участвует в людях и служит образцом:
     * похожие новые срабатывания тоже не попадут в людей.
     */
    @ColumnInfo(defaultValue = "0") val isArtifact: Boolean = false,
    /** Неуверенное срабатывание (score < 0.8) уже проверено CLIP «это лицо?». */
    @ColumnInfo(defaultValue = "0") val checked: Boolean = false,
)

/** «Это не он»: лицо не должно попасть к человеку (ограничение для кластеризации). */
@Entity(
    tableName = "face_rejection",
    primaryKeys = ["faceId", "personId"],
    indices = [Index("personId")],
    foreignKeys = [
        ForeignKey(entity = FaceEntity::class, parentColumns = ["id"], childColumns = ["faceId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = PersonEntity::class, parentColumns = ["id"], childColumns = ["personId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class FaceRejectionEntity(val faceId: Long, val personId: Long)

/** Подсказка «это тоже он?» для пары людей отклонена — больше не показывать. */
@Entity(
    tableName = "person_pair_dismissal",
    primaryKeys = ["personA", "personB"],
    indices = [Index("personB")],
    foreignKeys = [
        ForeignKey(entity = PersonEntity::class, parentColumns = ["id"], childColumns = ["personA"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = PersonEntity::class, parentColumns = ["id"], childColumns = ["personB"], onDelete = ForeignKey.CASCADE),
    ],
)
data class PersonPairDismissalEntity(val personA: Long, val personB: Long)

/**
 * Ручная отметка «этот человек есть на фото» без рамки лица — когда детектор лица не нашёл
 * (далеко, в профиль, со спины). В распознавании не участвует: вектора лица нет.
 */
@Entity(
    tableName = "media_person_tag",
    primaryKeys = ["mediaId", "personId"],
    indices = [Index("personId")],
    foreignKeys = [
        ForeignKey(entity = MediaEntity::class, parentColumns = ["id"], childColumns = ["mediaId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = PersonEntity::class, parentColumns = ["id"], childColumns = ["personId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class MediaPersonTagEntity(val mediaId: Long, val personId: Long)

/** Человек — группа лиц (DBSCAN). Имя задаёт пользователь; сохраняется при пересчёте групп. */
@Entity(tableName = "person")
data class PersonEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String? = null,
    val coverFaceId: Long? = null,
    /** Порядок на экране (по числу фото). */
    @ColumnInfo(defaultValue = "0") val position: Int = 0,
    /** Аватар 256 px, вырезанный из оригинала (а не из превью 640 px) — чёткий даже для мелких лиц. */
    val avatar: ByteArray? = null,
    /** Лицо, из которого сделан [avatar]: если выбор не изменился, аватар не перерисовывается. */
    val avatarFaceId: Long? = null,
)

data class PersonRow(
    val id: Long,
    val name: String?,
    val mediaCount: Int,
)

/** Лицо для кластеризации + данные для выбора обложки (качество, размер в оригинале). */
data class FaceClusterRow(
    val id: Long,
    val mediaId: Long,
    val embedding: ByteArray,
    val embedVersion: Int,
    val personId: Long?,
    val lockedPersonId: Long?,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val score: Float,
    val uri: String,
    val width: Int,
    val height: Int,
)

/** Узнанный человек на конкретном фото: рамка его лица + данные для чипа в просмотрщике. */
data class PersonFaceRow(
    val personId: Long,
    val name: String?,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/** Лицо с сохранёнными ключевыми точками — для пересчёта вектора без повторного поиска лиц. */
data class FaceEmbedRow(
    val id: Long,
    val mediaId: Long,
    val uri: String,
    val landmarks: ByteArray,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/** Лицо с фото и человеком — для проверки срабатываний детектора. */
data class FaceCheckRow(
    val id: Long,
    val mediaId: Long,
    val uri: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val score: Float,
    val personId: Long?,
)

/** Рамка лица человека на фото (в долях кадра) — подсветка в просмотрщике. */
data class FaceBoxRow(val mediaId: Long, val left: Float, val top: Float, val right: Float, val bottom: Float)

/** Лицо на фото для ручной отметки: миниатюра и к кому сейчас отнесено. */
data class FaceOnMediaRow(
    val id: Long,
    val thumbnail: ByteArray,
    val personId: Long?,
    val name: String?,
    /** Рамка в долях кадра — по ней лицо обводится на фото. */
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/** Человек, отмеченный на фото вручную без рамки лица. */
data class TaggedPersonRow(val personId: Long, val name: String?)

data class FaceOwner(val personId: Long?, val lockedPersonId: Long?)

data class FaceAssignment(val faceId: Long, val personId: Long)

@Dao
interface FaceDao {

    /** Фото, на которых ещё не искали лица (только изображения, читаемые). */
    @Query(
        """
        SELECT m.* FROM media m
        JOIN media_analysis a ON a.mediaId = m.id
        WHERE m.mediaType = 0 AND a.isUnreadable = 0 AND a.facesVersion < :version
          AND (m.dateTaken < :afterDateTaken OR (m.dateTaken = :afterDateTaken AND m.id < :afterId))
        ORDER BY m.dateTaken DESC, m.id DESC
        LIMIT :limit
        """
    )
    suspend fun getPendingPage(version: Int, afterDateTaken: Long, afterId: Long, limit: Int): List<MediaEntity>

    /** Конкретные кадры для повторного поиска лиц (нечитаемые пропускаем, как и в обычном проходе). */
    @Query(
        """
        SELECT m.* FROM media m
        JOIN media_analysis a ON a.mediaId = m.id
        WHERE m.id IN (:mediaIds) AND m.mediaType = 0 AND a.isUnreadable = 0
        """
    )
    suspend fun getMediaByIds(mediaIds: List<Long>): List<MediaEntity>

    @Query(
        """
        SELECT COUNT(*) FROM media m JOIN media_analysis a ON a.mediaId = m.id
        WHERE m.mediaType = 0 AND a.isUnreadable = 0 AND a.facesVersion < :version
        """
    )
    suspend fun countPending(version: Int): Int

    // --- Артефакты («это не лицо») ---

    @Query("SELECT embedding FROM face WHERE isArtifact = 1 AND embedVersion = :embedVersion")
    suspend fun getArtifactEmbeddings(embedVersion: Int): List<ByteArray>

    @Query("UPDATE face SET isArtifact = 1, personId = NULL, lockedPersonId = NULL WHERE personId = :personId OR lockedPersonId = :personId")
    suspend fun markPersonAsArtifacts(personId: Long)

    @Query("UPDATE face SET isArtifact = 1, personId = NULL, lockedPersonId = NULL WHERE id IN (:faceIds)")
    suspend fun markArtifacts(faceIds: List<Long>)

    /** Неуверенные срабатывания, ещё не проверенные CLIP (с их фото). */
    @Query(
        """
        SELECT f.id, f.mediaId, m.uri, f.left, f.top, f.right, f.bottom, f.score, f.personId
        FROM face f JOIN media m ON m.id = f.mediaId
        WHERE f.checked = 0 AND f.isArtifact = 0 AND f.score < :below AND f.lockedPersonId IS NULL
        """
    )
    suspend fun getUnchecked(below: Float): List<FaceCheckRow>

    @Query("SELECT COUNT(*) FROM face WHERE checked = 0 AND isArtifact = 0 AND score < :below AND lockedPersonId IS NULL")
    suspend fun countUnchecked(below: Float): Int

    @Query("UPDATE face SET checked = 1 WHERE id IN (:faceIds)")
    suspend fun markChecked(faceIds: List<Long>)

    @Query("DELETE FROM face WHERE id IN (:faceIds)")
    suspend fun deleteFaces(faceIds: List<Long>)

    // --- Ручные правки людей ---

    @Query("SELECT * FROM face_rejection")
    suspend fun getRejections(): List<FaceRejectionEntity>

    @Insert(onConflict = androidx.room.OnConflictStrategy.IGNORE)
    suspend fun insertRejections(rejections: List<FaceRejectionEntity>)

    @Query("UPDATE face SET lockedPersonId = NULL WHERE lockedPersonId = :personId")
    suspend fun unlockFacesOf(personId: Long)

    @Query("UPDATE face SET lockedPersonId = NULL WHERE lockedPersonId IS NOT NULL")
    suspend fun unlockAll()

    @Query("DELETE FROM face_rejection")
    suspend fun deleteAllRejections()

    @Query("DELETE FROM person_pair_dismissal")
    suspend fun deleteAllDismissals()

    /** Закрепить все текущие лица человека за ним (подтверждение группы). */
    @Query("UPDATE face SET lockedPersonId = :personId WHERE personId = :personId")
    suspend fun lockFacesOf(personId: Long)

    /** Объединение: лица источников — к целевому человеку, закреплены. */
    @Query("UPDATE face SET personId = :target, lockedPersonId = :target WHERE personId IN (:sources) OR lockedPersonId IN (:sources) OR personId = :target")
    suspend fun moveAndLock(sources: List<Long>, target: Long)

    @Query("UPDATE OR IGNORE face_rejection SET personId = :target WHERE personId IN (:sources)")
    suspend fun moveRejections(sources: List<Long>, target: Long)

    @Query("DELETE FROM person WHERE id IN (:ids)")
    suspend fun deletePersons(ids: List<Long>)

    @Query("SELECT id FROM face WHERE personId = :personId AND mediaId IN (:mediaIds)")
    suspend fun faceIdsOf(personId: Long, mediaIds: List<Long>): List<Long>

    @Query("UPDATE face SET personId = NULL, lockedPersonId = NULL WHERE id IN (:faceIds)")
    suspend fun detach(faceIds: List<Long>)

    @Insert(onConflict = androidx.room.OnConflictStrategy.IGNORE)
    suspend fun insertDismissal(dismissal: PersonPairDismissalEntity)

    @Query("SELECT * FROM person_pair_dismissal WHERE personA = :personId OR personB = :personId")
    suspend fun getDismissals(personId: Long): List<PersonPairDismissalEntity>

    /** Векторы лиц по людям — для подсказок «это тоже он?». */
    @Query("SELECT id, mediaId, embedding, embedVersion, personId, lockedPersonId, left, top, right, bottom, score, '' AS uri, 0 AS width, 0 AS height FROM face WHERE personId IS NOT NULL")
    suspend fun getAssignedFaces(): List<FaceClusterRow>

    @Query(
        """
        SELECT f.id, f.mediaId, m.uri, f.left, f.top, f.right, f.bottom, f.score, f.personId
        FROM face f JOIN media m ON m.id = f.mediaId
        """
    )
    suspend fun getAllForCheck(): List<FaceCheckRow>

    @Query("SELECT * FROM face WHERE mediaId IN (:mediaIds)")
    suspend fun getFacesForMedia(mediaIds: List<Long>): List<FaceEntity>

    @Query("DELETE FROM face WHERE mediaId IN (:mediaIds)")
    suspend fun deleteForMedia(mediaIds: List<Long>)

    @Insert
    suspend fun insertFaces(faces: List<FaceEntity>): List<Long>

    /** Кадры, где лиц уже нашлось много: их стоит пересмотреть по частям. */
    @Query(
        """
        SELECT mediaId FROM face WHERE isArtifact = 0
        GROUP BY mediaId HAVING COUNT(*) >= :minFaces
        """
    )
    suspend fun crowdedMedia(minFaces: Int): List<Long>

    @Query("SELECT * FROM face_rejection WHERE faceId IN (:faceIds)")
    suspend fun getRejectionsFor(faceIds: List<Long>): List<FaceRejectionEntity>

    @Query("UPDATE media_analysis SET facesVersion = :version WHERE mediaId IN (:mediaIds)")
    suspend fun markDone(mediaIds: List<Long>, version: Int)

    /**
     * Результат поиска лиц для пачки фото: старые лица заменяются, фото помечаются обработанными.
     * [rejections] — перенесённые «это не он» как (номер лица в [faces], человек): их строки
     * удаляются вместе со старым лицом, поэтому создаются заново.
     */
    @Transaction
    suspend fun saveBatch(
        mediaIds: List<Long>,
        faces: List<FaceEntity>,
        version: Int,
        rejections: List<Pair<Int, Long>> = emptyList(),
    ) {
        deleteForMedia(mediaIds)
        val ids = if (faces.isNotEmpty()) insertFaces(faces) else emptyList()
        if (rejections.isNotEmpty()) {
            insertRejections(
                rejections.mapNotNull { (index, personId) -> ids.getOrNull(index)?.let { FaceRejectionEntity(it, personId) } }
            )
        }
        markDone(mediaIds, version)
    }

    @Query("SELECT COUNT(*) FROM face")
    suspend fun countFaces(): Int

    // --- Пересчёт векторов при смене модели (лица не ищутся заново) ---

    @Query("SELECT COUNT(*) FROM face WHERE embedVersion <> :version AND landmarks IS NOT NULL")
    suspend fun countToReembed(version: Int): Int

    @Query(
        """
        SELECT f.id, f.mediaId, m.uri, f.landmarks AS landmarks, f.left, f.top, f.right, f.bottom
        FROM face f JOIN media m ON m.id = f.mediaId
        WHERE f.embedVersion <> :version AND f.landmarks IS NOT NULL
        ORDER BY f.mediaId
        LIMIT :limit
        """
    )
    suspend fun getToReembed(version: Int, limit: Int): List<FaceEmbedRow>

    @Query("UPDATE face SET embedding = :embedding, embedVersion = :version WHERE id = :faceId")
    suspend fun setEmbedding(faceId: Long, embedding: ByteArray, version: Int)

    /** Лицо без ключевых точек пересчитать нельзя — помечаем, чтобы не выбирать его снова. */
    @Query("UPDATE face SET embedVersion = :version WHERE id IN (:faceIds)")
    suspend fun markEmbedVersion(faceIds: List<Long>, version: Int)

    // --- Люди ---

    /** Лица для кластеризации: только с видимых фото (не скрытых и не деликатных). */
    @Query(
        """
        SELECT f.id, f.mediaId, f.embedding, f.embedVersion, f.personId, f.lockedPersonId, f.left, f.top, f.right, f.bottom, f.score,
               m.uri, m.width, m.height
        FROM face f
        JOIN media m ON m.id = f.mediaId
        LEFT JOIN media_analysis a ON a.mediaId = m.id
        WHERE m.isHiddenByUser = 0 AND f.isArtifact = 0 AND f.embedVersion = :embedVersion
          AND (NOT :hideSensitive OR a.sensitiveScore IS NULL OR a.sensitiveScore < :threshold)
        """
    )
    suspend fun getForClustering(hideSensitive: Boolean, threshold: Float, embedVersion: Int): List<FaceClusterRow>

    /**
     * То же постранично: у 512-мерных векторов строка весит ~2 КБ, и вся выборка не помещается
     * в окно курсора (особенно когда индексация параллельно пишет в базу).
     */
    @Query(
        """
        SELECT f.id, f.mediaId, f.embedding, f.embedVersion, f.personId, f.lockedPersonId, f.left, f.top, f.right, f.bottom, f.score,
               m.uri, m.width, m.height
        FROM face f
        JOIN media m ON m.id = f.mediaId
        LEFT JOIN media_analysis a ON a.mediaId = m.id
        WHERE m.isHiddenByUser = 0 AND f.isArtifact = 0 AND f.embedVersion = :embedVersion AND f.id > :afterId
          AND (NOT :hideSensitive OR a.sensitiveScore IS NULL OR a.sensitiveScore < :threshold)
        ORDER BY f.id
        LIMIT :limit
        """
    )
    suspend fun getForClusteringPage(
        hideSensitive: Boolean,
        threshold: Float,
        embedVersion: Int,
        afterId: Long,
        limit: Int,
    ): List<FaceClusterRow>

    @Query("SELECT * FROM person")
    suspend fun getPersons(): List<PersonEntity>

    /** Люди только с ручными отметками (без лиц) аватара не имеют — их не считаем. */
    @Query("SELECT COUNT(*) FROM person p WHERE p.avatar IS NULL AND EXISTS (SELECT 1 FROM face f WHERE f.personId = p.id)")
    suspend fun countPersonsWithoutAvatar(): Int

    @Query("UPDATE face SET personId = NULL")
    suspend fun clearAssignments()

    @Query("UPDATE face SET personId = :personId WHERE id IN (:faceIds)")
    suspend fun assign(faceIds: List<Long>, personId: Long)

    @Insert
    suspend fun insertPerson(person: PersonEntity): Long

    /** Создать или обновить человека: строку могли удалить, пока шёл пересчёт. */
    @androidx.room.Upsert
    suspend fun upsertPerson(person: PersonEntity)

    @Update
    suspend fun updatePersons(persons: List<PersonEntity>)

    @Query("DELETE FROM person")
    suspend fun deleteAllPersons()

    /** Люди, отмеченные на фото вручную (без лиц), при пересчёте групп не удаляются. */
    @Query("DELETE FROM person WHERE id NOT IN (:keepIds) AND id NOT IN (SELECT personId FROM media_person_tag)")
    suspend fun deletePersonsExcept(keepIds: List<Long>)

    @Query("UPDATE person SET name = :name WHERE id = :personId")
    suspend fun rename(personId: Long, name: String?)

    /** Люди с аватаром и числом видимых фото; сначала названные. */
    @Query(
        """
        SELECT p.id, p.name,
               (SELECT COUNT(*) FROM media m WHERE m.isHiddenByUser = 0 AND m.id IN
                (SELECT mediaId FROM face WHERE personId = p.id UNION SELECT mediaId FROM media_person_tag WHERE personId = p.id)) AS mediaCount
        FROM person p
        ORDER BY (p.name IS NULL), p.position
        """
    )
    fun observePersons(): Flow<List<PersonRow>>

    @Query("SELECT mediaId, left, top, right, bottom FROM face WHERE personId = :personId")
    fun observePersonFaceBoxes(personId: Long): Flow<List<FaceBoxRow>>

    /** Люди на фото (только лица, отнесённые к человеку), слева направо по кадру. */
    @Query(
        """
        SELECT f.personId, p.name, f.left, f.top, f.right, f.bottom
        FROM face f JOIN person p ON p.id = f.personId
        WHERE f.mediaId = :mediaId
        ORDER BY f.left
        """
    )
    fun observePeopleOnMedia(mediaId: Long): Flow<List<PersonFaceRow>>

    @Query(
        """
        SELECT p.id, p.name,
               (SELECT COUNT(*) FROM media m WHERE m.isHiddenByUser = 0 AND m.id IN
                (SELECT mediaId FROM face WHERE personId = p.id UNION SELECT mediaId FROM media_person_tag WHERE personId = p.id)) AS mediaCount
        FROM person p
        """
    )
    suspend fun getPersonRows(): List<PersonRow>

    /** Аватар одного человека: запрашивается для видимых строк, а не для всего списка сразу. */
    @Query("SELECT COALESCE(p.avatar, (SELECT f.thumbnail FROM face f WHERE f.id = p.coverFaceId)) FROM person p WHERE p.id = :personId")
    suspend fun getPersonAvatar(personId: Long): ByteArray?

    // --- Ручная отметка на фото ---

    /** Все лица на фото (кроме помеченных «не лицо»), слева направо. */
    @Query(
        """
        SELECT f.id, f.thumbnail, f.personId, p.name, f.left, f.top, f.right, f.bottom
        FROM face f LEFT JOIN person p ON p.id = f.personId
        WHERE f.mediaId = :mediaId AND f.isArtifact = 0
        ORDER BY f.left
        """
    )
    fun observeFacesOnMedia(mediaId: Long): Flow<List<FaceOnMediaRow>>

    @Query(
        """
        SELECT t.personId, p.name
        FROM media_person_tag t JOIN person p ON p.id = t.personId
        WHERE t.mediaId = :mediaId
        """
    )
    fun observeTaggedOnMedia(mediaId: Long): Flow<List<TaggedPersonRow>>

    @Query("SELECT personId, lockedPersonId FROM face WHERE id = :faceId")
    suspend fun getFaceOwner(faceId: Long): FaceOwner?

    /** Лица — к человеку, закреплены (ручная отметка); «не лицо» снимается. */
    @Query("UPDATE face SET personId = :personId, lockedPersonId = :personId, isArtifact = 0 WHERE id IN (:faceIds)")
    suspend fun assignAndLock(faceIds: List<Long>, personId: Long)

    @Query("DELETE FROM face_rejection WHERE personId = :personId AND faceId IN (:faceIds)")
    suspend fun deleteRejections(faceIds: List<Long>, personId: Long)

    @Insert(onConflict = androidx.room.OnConflictStrategy.IGNORE)
    suspend fun insertTags(tags: List<MediaPersonTagEntity>)

    @Query("DELETE FROM media_person_tag WHERE personId = :personId AND mediaId IN (:mediaIds)")
    suspend fun deleteTags(personId: Long, mediaIds: List<Long>)

    @Query("SELECT mediaId FROM media_person_tag WHERE personId = :personId AND mediaId IN (:mediaIds)")
    suspend fun taggedMediaOf(personId: Long, mediaIds: List<Long>): List<Long>

    @Query("UPDATE OR IGNORE media_person_tag SET personId = :target WHERE personId IN (:sources)")
    suspend fun moveTags(sources: List<Long>, target: Long)

    /** Все фото человека: по лицам и ручным отметкам. */
    @Query("SELECT mediaId FROM face WHERE personId = :personId UNION SELECT mediaId FROM media_person_tag WHERE personId = :personId")
    suspend fun getPersonMediaIds(personId: Long): List<Long>

    @Query("SELECT name FROM person WHERE id = :personId")
    fun observeName(personId: Long): Flow<String?>

    @Query(
        """
        SELECT m.*, a.sensitiveScore AS sensitiveScore FROM media m
        LEFT JOIN media_analysis a ON a.mediaId = m.id
        WHERE m.id IN (SELECT mediaId FROM face WHERE personId = :personId
                       UNION SELECT mediaId FROM media_person_tag WHERE personId = :personId)
          AND m.isHiddenByUser = 0
        ORDER BY m.dateTaken DESC
        """
    )
    fun observePersonMedia(personId: Long): Flow<List<MediaWithAnalysis>>
}
