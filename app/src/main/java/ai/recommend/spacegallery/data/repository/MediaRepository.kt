package ai.recommend.spacegallery.data.repository

import ai.recommend.spacegallery.data.db.AlbumRow
import ai.recommend.spacegallery.data.db.AppDatabase
import ai.recommend.spacegallery.data.db.MediaDao
import ai.recommend.spacegallery.data.db.MediaWithAnalysis
import ai.recommend.spacegallery.data.media.DeleteResult
import ai.recommend.spacegallery.data.media.AlbumNames
import ai.recommend.spacegallery.data.media.MediaDeleter
import ai.recommend.spacegallery.data.media.MediaMover
import ai.recommend.spacegallery.data.media.MoveResult
import android.content.IntentSender
import android.os.Build
import androidx.annotation.RequiresApi
import ai.recommend.spacegallery.data.media.MediaStoreSource
import ai.recommend.spacegallery.data.settings.SettingsRepository
import ai.recommend.spacegallery.domain.Album
import ai.recommend.spacegallery.domain.MediaItem
import ai.recommend.spacegallery.domain.MediaType
import androidx.core.net.toUri
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

@OptIn(ExperimentalCoroutinesApi::class)
class MediaRepository(
    db: AppDatabase,
    private val source: MediaStoreSource,
    private val deleter: MediaDeleter,
    private val mover: MediaMover,
    private val settings: SettingsRepository,
) {
    private val dao = db.mediaDao()
    private val smartAlbumDao = db.smartAlbumDao()

    /** Лента с учётом политики скрытия деликатного контента. [albumId] = null — все медиа. */
    fun observeTimeline(albumId: Long? = null): Flow<List<MediaItem>> =
        settings.settings
            .map { it.hideSensitive to it.sensitiveThreshold }
            .distinctUntilChanged()
            .flatMapLatest { (hide, threshold) -> dao.observeTimeline(albumId, !hide, threshold) }
            .map { rows -> rows.map { it.toDomain() } }

    fun observeFavorites(): Flow<List<MediaItem>> =
        dao.observeFavorites().map { rows -> rows.map { it.toDomain() } }

    fun observeHidden(): Flow<List<MediaItem>> =
        settings.settings
            .map { it.sensitiveThreshold }
            .distinctUntilChanged()
            .flatMapLatest { dao.observeHidden(it) }
            .map { rows -> rows.map { it.toDomain() } }

    fun observeAlbums(): Flow<List<Album>> =
        dao.observeAlbums().map { rows -> rows.map { it.toDomain() } }

    /**
     * Живой список в порядке [ids] (очередь просмотрщика из поиска/похожих/дубликатов):
     * изменения избранного и скрытия приходят сразу, удалённые файлы пропадают.
     */
    fun observeByIds(ids: List<Long>): Flow<List<MediaItem>> {
        val limited = ids.take(MediaDao.SQLITE_MAX_ARGS)
        return dao.observeByIds(limited).map { rows ->
            val byId = rows.associateBy { it.media.id }
            limited.mapNotNull { byId[it]?.toDomain() }
        }
    }

    /** Файлы умного альбома по дате, без скрытых вручную. */
    fun observeSmartAlbumItems(albumId: Long): Flow<List<MediaItem>> =
        smartAlbumDao.observeItems(albumId).map { rows -> rows.map { it.toDomain() } }

    /** Возвращает элементы в порядке [ids]. */
    suspend fun getByIds(ids: List<Long>): List<MediaItem> {
        if (ids.isEmpty()) return emptyList()
        val byId = ids.chunked(MediaDao.SQLITE_MAX_ARGS)
            .flatMap { dao.getByIds(it) }
            .associateBy { it.media.id }
        return ids.mapNotNull { byId[it]?.toDomain() }
    }

    /**
     * То же, что [getByIds], но с учётом скрытых вручную и политики деликатного контента —
     * для выдачи AI-поиска, похожих и дубликатов.
     */
    suspend fun getVisibleByIds(ids: List<Long>): List<MediaItem> {
        val s = settings.current()
        return getByIds(ids).filter { item ->
            !item.isHiddenByUser &&
                (!s.hideSensitive || (item.sensitiveScore ?: 0f) < s.sensitiveThreshold)
        }
    }

    // --- Управление альбомами (альбом = папка) ---

    /** Перенос файлов между папками требует Android 11+ (MediaStore.createWriteRequest). */
    val supportsAlbumManagement: Boolean get() = mover.isSupported

    /** Системный диалог «Разрешить изменить N файлов?» для [items]. */
    @RequiresApi(Build.VERSION_CODES.R)
    fun createWriteRequest(items: List<MediaItem>): IntentSender = mover.createWriteRequest(items.map { it.uri })

    /** Все файлы альбомов, включая скрытые вручную (операции над папкой целиком). */
    suspend fun getAlbumItems(albumIds: List<Long>): List<MediaItem> =
        albumIds.chunked(MediaDao.SQLITE_MAX_ARGS)
            .flatMap { dao.getByBuckets(it) }
            .map { MediaWithAnalysis(it, sensitiveScore = null).toDomain() }

    /** Перенести файлы в папку [relativePath]; вызывать после согласия пользователя. */
    suspend fun moveToFolder(items: List<MediaItem>, relativePath: String): MoveResult {
        val result = mover.moveTo(items.map { it.uri }, relativePath)
        syncWithMediaStore()
        return result
    }

    /**
     * Переименование альбома = перенос всех его файлов в соседнюю папку с новым именем.
     * Возвращает результат и новый id альбома (BUCKET_ID зависит от пути).
     */
    suspend fun renameAlbum(items: List<MediaItem>, currentPath: String, newName: String): Pair<MoveResult, Long?> {
        val result = mover.moveTo(items.map { it.uri }, AlbumNames.renamedPath(currentPath, newName))
        val newId = items.firstOrNull()?.let { mover.bucketIdOf(it.uri) }
        syncWithMediaStore()
        return result to newId
    }

    /** Полная синхронизация локальной БД с MediaStore. */
    suspend fun syncWithMediaStore() {
        dao.replaceFromMediaStore(source.queryAll())
    }

    suspend fun setFavorite(id: Long, favorite: Boolean) = dao.setFavorite(id, favorite)

    suspend fun setFavorite(ids: List<Long>, favorite: Boolean) =
        ids.chunked(MediaDao.SQLITE_MAX_ARGS).forEach { dao.setFavorite(it, favorite) }

    suspend fun setHidden(ids: List<Long>, hidden: Boolean) =
        ids.chunked(MediaDao.SQLITE_MAX_ARGS).forEach { dao.setHidden(it, hidden) }

    /**
     * Если результат [DeleteResult.NeedsConfirmation] — после подтверждения пользователем
     * нужно вызвать [onTrashed].
     */
    suspend fun moveToTrash(items: List<MediaItem>): DeleteResult {
        val result = deleter.moveToTrash(items.map { it.uri })
        if (result is DeleteResult.Done) onTrashed(items)
        return result
    }

    suspend fun onTrashed(items: List<MediaItem>) =
        items.map { it.id }.chunked(MediaDao.SQLITE_MAX_ARGS).forEach { dao.deleteByIds(it) }
}

private fun MediaWithAnalysis.toDomain(): MediaItem = MediaItem(
    id = media.id,
    uri = media.uri.toUri(),
    type = if (media.mediaType == 1) MediaType.VIDEO else MediaType.IMAGE,
    mimeType = media.mimeType,
    displayName = media.displayName,
    dateTaken = media.dateTaken,
    dateModified = media.dateModified,
    sizeBytes = media.size,
    width = media.width,
    height = media.height,
    durationMs = media.durationMs,
    albumId = media.bucketId,
    albumName = media.bucketName,
    isFavorite = media.isFavorite,
    isHiddenByUser = media.isHiddenByUser,
    sensitiveScore = sensitiveScore,
)

private fun AlbumRow.toDomain() = Album(
    id = bucketId,
    name = bucketName,
    relativePath = relativePath,
    coverUri = coverUri.toUri(),
    itemCount = itemCount,
)
