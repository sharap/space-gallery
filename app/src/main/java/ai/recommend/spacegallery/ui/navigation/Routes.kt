package ai.recommend.spacegallery.ui.navigation

import kotlinx.serialization.Serializable

// --- вкладки нижней навигации ---
@Serializable data object GalleryRoute
@Serializable data object AlbumsRoute
@Serializable data object SearchRoute
@Serializable data object MoreRoute

// --- вложенные экраны ---
@Serializable data class AlbumRoute(val albumId: Long, val name: String)

/** Просмотрщик листает ленту альбома [albumId] (или всю, если null), начиная с [mediaId]. */
@Serializable data class ViewerRoute(val mediaId: Long, val albumId: Long? = null)

@Serializable data class SimilarRoute(val mediaId: Long)
@Serializable data object DuplicatesRoute
@Serializable data object FavoritesRoute
@Serializable data object HiddenRoute
@Serializable data object SettingsRoute
