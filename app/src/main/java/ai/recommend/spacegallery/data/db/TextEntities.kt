package ai.recommend.spacegallery.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** Весь распознанный на снимке текст одной строкой — для поиска. */
@Entity(
    tableName = "media_text",
    foreignKeys = [
        ForeignKey(entity = MediaEntity::class, parentColumns = ["id"], childColumns = ["mediaId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class MediaTextEntity(
    @PrimaryKey val mediaId: Long,
    val text: String,
    val lineCount: Int,
)

/** Полнотекстовый индекс над [MediaTextEntity] (Room держит его в синхронном состоянии). */
@Entity(tableName = "media_text_fts")
@Fts4(contentEntity = MediaTextEntity::class)
data class MediaTextFts(val text: String)

/** Отдельная строка текста с рамкой — для показа и копирования по частям. */
@Entity(
    tableName = "text_line",
    indices = [Index("mediaId")],
    foreignKeys = [
        ForeignKey(entity = MediaEntity::class, parentColumns = ["id"], childColumns = ["mediaId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class TextLineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mediaId: Long,
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val confidence: Float,
)

/** QR-код или штрихкод, найденный на снимке. */
@Entity(
    tableName = "media_code",
    indices = [Index("mediaId")],
    foreignKeys = [
        ForeignKey(entity = MediaEntity::class, parentColumns = ["id"], childColumns = ["mediaId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class MediaCodeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mediaId: Long,
    val value: String,
    val format: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

@Dao
interface TextDao {

    /**
     * Снимки, которые нужно обработать: либо на них ещё не читали текст (и модели для этого
     * есть), либо ещё не искали коды. Два условия раздельны — без моделей текста проход
     * всё равно имеет смысл ради кодов, но распознанное раньше он трогать не должен.
     */
    @Query(
        """
        SELECT m.* FROM media m
        JOIN media_analysis a ON a.mediaId = m.id
        WHERE m.mediaType = 0 AND a.isUnreadable = 0
          AND ((:canReadText AND a.textVersion < :textVersion) OR a.codesVersion < :codesVersion)
          AND (m.dateTaken < :afterDateTaken OR (m.dateTaken = :afterDateTaken AND m.id < :afterId))
        ORDER BY m.dateTaken DESC, m.id DESC
        LIMIT :limit
        """
    )
    suspend fun getPendingPage(
        textVersion: Int,
        codesVersion: Int,
        canReadText: Boolean,
        afterDateTaken: Long,
        afterId: Long,
        limit: Int,
    ): List<MediaEntity>

    @Query(
        """
        SELECT COUNT(*) FROM media m JOIN media_analysis a ON a.mediaId = m.id
        WHERE m.mediaType = 0 AND a.isUnreadable = 0
          AND ((:canReadText AND a.textVersion < :textVersion) OR a.codesVersion < :codesVersion)
        """
    )
    suspend fun countPending(textVersion: Int, codesVersion: Int, canReadText: Boolean): Int

    @Query("UPDATE media_analysis SET textVersion = :version WHERE mediaId IN (:mediaIds)")
    suspend fun markTextDone(mediaIds: List<Long>, version: Int)

    @Query("UPDATE media_analysis SET codesVersion = :version WHERE mediaId IN (:mediaIds)")
    suspend fun markCodesDone(mediaIds: List<Long>, version: Int)

    /** Сменился набор языков — весь текст читается заново. */
    @Query("UPDATE media_analysis SET textVersion = 0")
    suspend fun resetVersions()

    @Insert
    suspend fun insertText(text: MediaTextEntity)

    @Insert
    suspend fun insertLines(lines: List<TextLineEntity>)

    @Insert
    suspend fun insertCodes(codes: List<MediaCodeEntity>)

    @Query("DELETE FROM media_text WHERE mediaId IN (:mediaIds)")
    suspend fun deleteText(mediaIds: List<Long>)

    @Query("DELETE FROM text_line WHERE mediaId IN (:mediaIds)")
    suspend fun deleteLines(mediaIds: List<Long>)

    @Query("DELETE FROM media_code WHERE mediaId IN (:mediaIds)")
    suspend fun deleteCodes(mediaIds: List<Long>)

    /**
     * Результат пачки снимков: старое заменяется, снимки помечаются обработанными.
     *
     * [textRead] = false — проход шёл без моделей текста, только ради кодов: тогда
     * распознанный раньше текст остаётся нетронутым, а отметка о версии текста не ставится,
     * иначе такие снимки считались бы прочитанными.
     */
    @Transaction
    suspend fun saveBatch(
        mediaIds: List<Long>,
        texts: List<MediaTextEntity>,
        lines: List<TextLineEntity>,
        codes: List<MediaCodeEntity>,
        textVersion: Int,
        codesVersion: Int,
        textRead: Boolean,
    ) {
        if (textRead) {
            deleteText(mediaIds)
            deleteLines(mediaIds)
            texts.forEach { insertText(it) }
            if (lines.isNotEmpty()) insertLines(lines)
            markTextDone(mediaIds, textVersion)
        }
        deleteCodes(mediaIds)
        if (codes.isNotEmpty()) insertCodes(codes)
        markCodesDone(mediaIds, codesVersion)
    }

    // --- Просмотр и поиск ---

    @Query("SELECT * FROM text_line WHERE mediaId = :mediaId ORDER BY top, left")
    fun observeLines(mediaId: Long): Flow<List<TextLineEntity>>

    @Query("SELECT * FROM media_code WHERE mediaId = :mediaId")
    fun observeCodes(mediaId: Long): Flow<List<MediaCodeEntity>>

    /**
     * Снимки, где встречается запрос. FTS ищет по словам: к запросу добавляется `*`,
     * чтобы находить и по началу слова («парол» -> «пароль»).
     */
    @Query(
        """
        SELECT t.mediaId FROM media_text t
        JOIN media_text_fts f ON f.docid = t.rowid
        WHERE media_text_fts MATCH :query
        LIMIT :limit
        """
    )
    suspend fun search(query: String, limit: Int): List<Long>

    /** Снимки, где в распознанном тексте есть подстрока — для отладочных выборок. */
    @Query("SELECT mediaId FROM media_text WHERE text LIKE '%' || :needle || '%' LIMIT :limit")
    suspend fun mediaWithTextLike(needle: String, limit: Int): List<Long>

    @Query("SELECT COUNT(*) FROM media_text")
    suspend fun countWithText(): Int

    @Query("SELECT COUNT(*) FROM media_code")
    suspend fun countCodes(): Int
}
