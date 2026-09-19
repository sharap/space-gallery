package ai.recommend.spacegallery.ui.viewer

import ai.recommend.spacegallery.domain.MediaItem
import androidx.compose.animation.core.snap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState

/**
 * Фото с зумом: pinch, двойной тап (1× → 2.5× → 1×), панорамирование.
 * Большие фото подгружаются тайлами (субсэмплинг Telephoto), поэтому зум не упирается в память.
 * Жесты корректно делятся с HorizontalPager: листание работает, когда фото не увеличено
 * или упёрлось в край.
 */
@Composable
fun PhotoPage(
    item: MediaItem,
    isCurrentPage: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val zoomableState = rememberZoomableState(zoomSpec = ZoomSpec(maxZoomFactor = MAX_ZOOM))
    val imageState = rememberZoomableImageState(zoomableState)

    // Ушли со страницы — при возврате фото снова целиком.
    LaunchedEffect(isCurrentPage) {
        if (!isCurrentPage) zoomableState.resetZoom(animationSpec = snap())
    }

    ZoomableAsyncImage(
        model = item.uri,
        contentDescription = item.displayName,
        state = imageState,
        onClick = { onTap() },
        modifier = modifier.fillMaxSize(),
    )
}

private const val MAX_ZOOM = 5f
