package ai.recommend.spacegallery.ui.navigation

import ai.recommend.spacegallery.R
import ai.recommend.spacegallery.domain.MediaItem
import ai.recommend.spacegallery.ui.albums.AlbumDetailScreen
import ai.recommend.spacegallery.ui.albums.AlbumsScreen
import ai.recommend.spacegallery.ui.duplicates.DuplicatesScreen
import ai.recommend.spacegallery.ui.gallery.FavoritesScreen
import ai.recommend.spacegallery.ui.gallery.GalleryScreen
import ai.recommend.spacegallery.ui.hidden.HiddenScreen
import ai.recommend.spacegallery.ui.more.MoreScreen
import ai.recommend.spacegallery.ui.search.SearchScreen
import ai.recommend.spacegallery.ui.settings.SettingsScreen
import ai.recommend.spacegallery.ui.similar.SimilarScreen
import ai.recommend.spacegallery.ui.viewer.ViewerScreen
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

private data class TopLevelTab(val route: Any, @StringRes val label: Int, val icon: ImageVector)

private val tabs = listOf(
    TopLevelTab(GalleryRoute, R.string.tab_photos, Icons.Outlined.PhotoLibrary),
    TopLevelTab(AlbumsRoute, R.string.tab_albums, Icons.Outlined.Collections),
    TopLevelTab(SearchRoute, R.string.tab_search, Icons.Outlined.AutoAwesome),
    TopLevelTab(MoreRoute, R.string.tab_more, Icons.Outlined.MoreHoriz),
)

@Composable
fun SpaceGalleryNavHost() {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val destination = backStack?.destination
    val showBottomBar = tabs.any { tab -> destination?.hasRoute(tab.route::class) == true }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = destination?.hierarchy?.any { it.hasRoute(tab.route::class) } == true,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(stringResource(tab.label)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = GalleryRoute,
            modifier = Modifier.padding(bottom = padding.calculateBottomPadding()).consumeWindowInsets(padding),
        ) {
            fun openViewer(item: MediaItem, queue: ViewerQueue, albumId: Long = 0) =
                navController.navigate(ViewerRoute(item.id, queue, albumId))

            /** Очередь просмотра — ровно этот список (поиск, похожие, дубликаты). */
            fun openViewerList(item: MediaItem, queue: List<MediaItem>) =
                navController.navigate(ViewerRoute(item.id, ViewerQueue.LIST, ids = queue.map { it.id }))

            composable<GalleryRoute> {
                GalleryScreen(onOpen = { openViewer(it, ViewerQueue.TIMELINE) })
            }
            composable<AlbumsRoute> {
                AlbumsScreen(onOpenAlbum = { navController.navigate(AlbumRoute(it.id, it.name)) })
            }
            composable<AlbumRoute> {
                AlbumDetailScreen(
                    onBack = navController::popBackStack,
                    onOpen = { item, albumId -> openViewer(item, ViewerQueue.ALBUM, albumId) },
                )
            }
            composable<SearchRoute> {
                SearchScreen(onOpen = ::openViewerList)
            }
            composable<MoreRoute> {
                MoreScreen(
                    onFavorites = { navController.navigate(FavoritesRoute) },
                    onDuplicates = { navController.navigate(DuplicatesRoute) },
                    onHidden = { navController.navigate(HiddenRoute) },
                    onSettings = { navController.navigate(SettingsRoute) },
                )
            }
            composable<ViewerRoute> {
                ViewerScreen(
                    onBack = navController::popBackStack,
                    onShowSimilar = { navController.navigate(SimilarRoute(it)) },
                )
            }
            composable<SimilarRoute> {
                SimilarScreen(onBack = navController::popBackStack, onOpen = ::openViewerList)
            }
            composable<FavoritesRoute> {
                FavoritesScreen(onBack = navController::popBackStack, onOpen = { openViewer(it, ViewerQueue.FAVORITES) })
            }
            composable<DuplicatesRoute> {
                DuplicatesScreen(onBack = navController::popBackStack, onOpen = ::openViewerList)
            }
            composable<HiddenRoute> {
                HiddenScreen(onBack = navController::popBackStack, onOpen = { openViewer(it, ViewerQueue.HIDDEN) })
            }
            composable<SettingsRoute> {
                SettingsScreen(onBack = navController::popBackStack)
            }
        }
    }
}
