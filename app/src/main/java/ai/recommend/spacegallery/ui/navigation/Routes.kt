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

    /** Умный альбом [ViewerRoute.albumId] (может быть больше лимита явного списка). */
    SMART_ALBUM,

    /** Фото человека [ViewerRoute.albumId] (id человека). */
    PERSON,
}

/** Просмотрщик: открывается на [mediaId] и листает очередь [queue]. */
@Serializable data class ViewerRoute(
    val mediaId: Long,
    val queue: ViewerQueue = ViewerQueue.TIMELINE,
    val albumId: Long = 0,
    val ids: List<Long> = emptyList(),
)

@Serializable data class SimilarRoute(val mediaId: Long)
@Serializable data class SmartAlbumRoute(val albumId: Long, val name: String)
@Serializable data object PeopleRoute
@Serializable data class PersonRoute(val personId: Long)
@Serializable data object CleanupRoute

@Keep
@Serializable enum class CleanupCategory { COPIES, SERIES, POOR, SCREENSHOTS, LARGE_VIDEOS }

@Serializable data class CleanupCategoryRoute(val category: CleanupCategory)
@Serializable data object FavoritesRoute
@Serializable data object HiddenRoute
@Serializable data object SettingsRoute
@Serializable data object ModelsRoute
