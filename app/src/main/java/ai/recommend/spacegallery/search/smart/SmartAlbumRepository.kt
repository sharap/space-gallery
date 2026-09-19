package ai.recommend.spacegallery.search.smart

import ai.recommend.spacegallery.data.db.SmartAlbumDao
import ai.recommend.spacegallery.data.repository.MediaRepository
import ai.recommend.spacegallery.domain.MediaItem
import androidx.core.net.toUri
import android.net.Uri
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class SmartAlbum(val id: Long, val name: String, val coverUri: Uri, val itemCount: Int)

class SmartAlbumRepository(
    private val dao: SmartAlbumDao,
    private val media: MediaRepository,
) {
    /** Альбомы, в которых остались видимые файлы. */
    fun observeAlbums(): Flow<List<SmartAlbum>> = dao.observeAlbums().map { rows ->
        rows.mapNotNull { row ->
            val cover = row.coverUri ?: return@mapNotNull null
            SmartAlbum(row.id, row.name, cover.toUri(), row.itemCount).takeIf { it.itemCount > 0 }
        }
    }

    fun observeName(albumId: Long): Flow<String?> = dao.observeName(albumId)

    /** Файлы альбома по дате, без скрытых вручную. */
    fun observeItems(albumId: Long): Flow<List<MediaItem>> = media.observeSmartAlbumItems(albumId)
}
