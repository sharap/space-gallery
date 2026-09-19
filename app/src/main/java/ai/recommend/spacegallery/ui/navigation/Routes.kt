package ai.recommend.spacegallery.ui.navigation

import androidx.annotation.Keep
import kotlinx.serialization.Serializable

// --- вкладки нижней навигации ---
@Serializable data object GalleryRoute
@Serializable data object AlbumsRoute
@Serializable data object SearchRoute
@Serializable data object MoreRoute

// --- вложенные экраны ---
@Serializable data class AlbumRoute(val albumId: Long, val name: String)

/** Из чего состоит очередь пролистывания в просмотрщике. */
@Keep
@Serializable enum class ViewerQueue {
    /** Вся лента «Фото». */
    TIMELINE,

    /** Альбом [ViewerRoute.albumId]. */
    ALBUM,
    FAVORITES,
    HIDDEN,

    /** Явный список [ViewerRoute.ids] в заданном порядке: результаты поиска, похожие, группа дубликатов. */
    LIST,
}

/** Просмотрщик: открывается на [mediaId] и листает очередь [queue]. */
@Serializable data class ViewerRoute(
    val mediaId: Long,
    val queue: ViewerQueue = ViewerQueue.TIMELINE,
    val albumId: Long = 0,
    val ids: List<Long> = emptyList(),
)

@Serializable data class SimilarRoute(val mediaId: Long)
@Serializable data object DuplicatesRoute
@Serializable data object FavoritesRoute
@Serializable data object HiddenRoute
@Serializable data object SettingsRoute
