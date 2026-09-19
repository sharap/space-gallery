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
    /** SFace, 128 float32 LE, L2-нормализован. */
    val embedding: ByteArray,
    /** Миниатюра лица для аватара (JPEG ~128 px); удаляется вместе со строкой. */
    val thumbnail: ByteArray,
    val personId: Long? = null,
)

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
    val thumbnail: ByteArray?,
    val mediaCount: Int,
)

/** Лицо для кластеризации + данные для выбора обложки (качество, размер в оригинале). */
data class FaceClusterRow(
    val id: Long,
    val embedding: ByteArray,
    val personId: Long?,
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
    val avatar: ByteArray?,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/** Рамка лица человека на фото (в долях кадра) — подсветка в просмотрщике. */
data class FaceBoxRow(val mediaId: Long, val left: Float, val top: Float, val right: Float, val bottom: Float)

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

    @Query(
        """
        SELECT COUNT(*) FROM media m JOIN media_analysis a ON a.mediaId = m.id
        WHERE m.mediaType = 0 AND a.isUnreadable = 0 AND a.facesVersion < :version
        """
    )
    suspend fun countPending(version: Int): Int

    @Query("DELETE FROM face WHERE mediaId IN (:mediaIds)")
    suspend fun deleteForMedia(mediaIds: List<Long>)

    @Insert
    suspend fun insertFaces(faces: List<FaceEntity>)

    @Query("UPDATE media_analysis SET facesVersion = :version WHERE mediaId IN (:mediaIds)")
    suspend fun markDone(mediaIds: List<Long>, version: Int)

    /** Результат поиска лиц для пачки фото: старые лица заменяются, фото помечаются обработанными. */
    @Transaction
    suspend fun saveBatch(mediaIds: List<Long>, faces: List<FaceEntity>, version: Int) {
        deleteForMedia(mediaIds)
        if (faces.isNotEmpty()) insertFaces(faces)
        markDone(mediaIds, version)
    }

    @Query("SELECT COUNT(*) FROM face")
    suspend fun countFaces(): Int

    // --- Люди ---

    /** Лица для кластеризации: только с видимых фото (не скрытых и не деликатных). */
    @Query(
        """
        SELECT f.id, f.embedding, f.personId, f.left, f.top, f.right, f.bottom, f.score,
               m.uri, m.width, m.height
        FROM face f
        JOIN media m ON m.id = f.mediaId
        LEFT JOIN media_analysis a ON a.mediaId = m.id
        WHERE m.isHiddenByUser = 0
          AND (NOT :hideSensitive OR a.sensitiveScore IS NULL OR a.sensitiveScore < :threshold)
        """
    )
    suspend fun getForClustering(hideSensitive: Boolean, threshold: Float): List<FaceClusterRow>

    @Query("SELECT * FROM person")
    suspend fun getPersons(): List<PersonEntity>

    @Query("SELECT COUNT(*) FROM person WHERE avatar IS NULL")
    suspend fun countPersonsWithoutAvatar(): Int

    @Query("UPDATE face SET personId = NULL")
    suspend fun clearAssignments()

    @Query("UPDATE face SET personId = :personId WHERE id IN (:faceIds)")
    suspend fun assign(faceIds: List<Long>, personId: Long)

    @Insert
    suspend fun insertPerson(person: PersonEntity): Long

    @Update
    suspend fun updatePersons(persons: List<PersonEntity>)

    @Query("DELETE FROM person WHERE id NOT IN (:keepIds)")
    suspend fun deletePersonsExcept(keepIds: List<Long>)

    @Query("DELETE FROM person")
    suspend fun deleteAllPersons()

    @Query("UPDATE person SET name = :name WHERE id = :personId")
    suspend fun rename(personId: Long, name: String?)

    /** Люди с аватаром и числом видимых фото; сначала названные. */
    @Query(
        """
        SELECT p.id, p.name,
               COALESCE(p.avatar, (SELECT f.thumbnail FROM face f WHERE f.id = p.coverFaceId)) AS thumbnail,
               (SELECT COUNT(DISTINCT f.mediaId) FROM face f JOIN media m ON m.id = f.mediaId
                WHERE f.personId = p.id AND m.isHiddenByUser = 0) AS mediaCount
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
        SELECT f.personId, p.name,
               COALESCE(p.avatar, (SELECT c.thumbnail FROM face c WHERE c.id = p.coverFaceId)) AS avatar,
               f.left, f.top, f.right, f.bottom
        FROM face f JOIN person p ON p.id = f.personId
        WHERE f.mediaId = :mediaId
        ORDER BY f.left
        """
    )
    fun observePeopleOnMedia(mediaId: Long): Flow<List<PersonFaceRow>>

    @Query("SELECT name FROM person WHERE id = :personId")
    fun observeName(personId: Long): Flow<String?>

    @Query(
        """
        SELECT m.*, a.sensitiveScore AS sensitiveScore FROM media m
        LEFT JOIN media_analysis a ON a.mediaId = m.id
        WHERE m.id IN (SELECT mediaId FROM face WHERE personId = :personId) AND m.isHiddenByUser = 0
        ORDER BY m.dateTaken DESC
        """
    )
    fun observePersonMedia(personId: Long): Flow<List<MediaWithAnalysis>>
}
