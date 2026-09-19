package ai.recommend.spacegallery.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Insert
import kotlinx.coroutines.flow.Flow

/** Умный альбом — кластер DBSCAN по CLIP-эмбеддингам (пересчитывается целиком). */
@Entity(tableName = "smart_album")
data class SmartAlbumEntity(
    @PrimaryKey val id: Long,
    val name: String,
    /** Медоид кластера — самое «типичное» фото. */
    val coverMediaId: Long,
    /** Порядок на экране (по размеру). */
    val position: Int,
)

@Entity(
    tableName = "smart_album_member",
    primaryKeys = ["albumId", "mediaId"],
    indices = [Index("mediaId")],
    foreignKeys = [
        ForeignKey(entity = SmartAlbumEntity::class, parentColumns = ["id"], childColumns = ["albumId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = MediaEntity::class, parentColumns = ["id"], childColumns = ["mediaId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class SmartAlbumMemberEntity(
    val albumId: Long,
    val mediaId: Long,
    /** Порядок внутри альбома (от ближайших к центру кластера). */
    val position: Int,
)

data class SmartAlbumRow(
    val id: Long,
    val name: String,
    val coverUri: String?,
    val itemCount: Int,
)

/** Вход кластеризации: эмбеддинги видимых (не скрытых и не деликатных) медиа. */
data class ClusterInputRow(val mediaId: Long, val embedding: ByteArray, val dateTaken: Long)

@Dao
interface SmartAlbumDao {

    /** Альбомы с обложкой и числом видимых файлов (скрытые после расчёта — не считаются). */
    @Query(
        """
        SELECT s.id, s.name,
               COALESCE(
                 (SELECT m.uri FROM media m WHERE m.id = s.coverMediaId AND m.isHiddenByUser = 0),
                 (SELECT m.uri FROM smart_album_member sm JOIN media m ON m.id = sm.mediaId
                  WHERE sm.albumId = s.id AND m.isHiddenByUser = 0 ORDER BY sm.position LIMIT 1)
               ) AS coverUri,
               (SELECT COUNT(*) FROM smart_album_member sm JOIN media m ON m.id = sm.mediaId
                WHERE sm.albumId = s.id AND m.isHiddenByUser = 0) AS itemCount
        FROM smart_album s
        ORDER BY s.position
        """
    )
    fun observeAlbums(): Flow<List<SmartAlbumRow>>

    /** Файлы альбома по дате (как в обычных альбомах); JOIN вместо списка id — без лимита параметров SQLite. */
    @Query(
        """
        SELECT m.*, a.sensitiveScore AS sensitiveScore FROM smart_album_member sm
        JOIN media m ON m.id = sm.mediaId
        LEFT JOIN media_analysis a ON a.mediaId = m.id
        WHERE sm.albumId = :albumId AND m.isHiddenByUser = 0
        ORDER BY m.dateTaken DESC
        """
    )
    fun observeItems(albumId: Long): Flow<List<MediaWithAnalysis>>

    @Query("SELECT name FROM smart_album WHERE id = :albumId")
    fun observeName(albumId: Long): Flow<String?>

    @Query("SELECT COUNT(*) FROM smart_album")
    suspend fun count(): Int

    @Query(
        """
        SELECT a.mediaId, a.embedding, m.dateTaken FROM media_analysis a
        JOIN media m ON m.id = a.mediaId
        WHERE a.embedding IS NOT NULL AND m.isHiddenByUser = 0
          AND (NOT :hideSensitive OR a.sensitiveScore IS NULL OR a.sensitiveScore < :threshold)
        """
    )
    suspend fun getClusterInput(hideSensitive: Boolean, threshold: Float): List<ClusterInputRow>

    @Query("DELETE FROM smart_album")
    suspend fun clear()

    @Insert
    suspend fun insertAlbums(albums: List<SmartAlbumEntity>)

    @Insert
    suspend fun insertMembers(members: List<SmartAlbumMemberEntity>)

    /** Атомарная замена результата кластеризации. */
    @Transaction
    suspend fun replaceAll(albums: List<SmartAlbumEntity>, members: List<SmartAlbumMemberEntity>) {
        clear()
        insertAlbums(albums)
        members.chunked(500).forEach { insertMembers(it) }
    }
}
