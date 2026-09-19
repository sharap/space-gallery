package ai.recommend.spacegallery.ui.viewer

import ai.recommend.spacegallery.domain.MediaItem
import ai.recommend.spacegallery.search.people.FaceBox
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import me.saket.telephoto.ExperimentalTelephotoApi
import me.saket.telephoto.zoomable.ZoomableState
import me.saket.telephoto.zoomable.Viewport
import me.saket.telephoto.zoomable.spatial.CoordinateSpace
import me.saket.telephoto.zoomable.spatial.isUnspecified
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
    /** Рамки лиц (в долях кадра), показываются вместе с кнопками — например, лицо человека. */
    faceBoxes: List<FaceBox> = emptyList(),
    showFaceBoxes: Boolean = false,
) {
    val zoomableState = rememberZoomableState(zoomSpec = ZoomSpec(maxZoomFactor = MAX_ZOOM))
    val imageState = rememberZoomableImageState(zoomableState)

    // Ушли со страницы — при возврате фото снова целиком.
    LaunchedEffect(isCurrentPage) {
        if (!isCurrentPage) zoomableState.resetZoom(animationSpec = snap())
    }

    Box(modifier.fillMaxSize()) {
        ZoomableAsyncImage(
            model = item.uri,
            contentDescription = item.displayName,
            state = imageState,
            onClick = { onTap() },
            modifier = Modifier.fillMaxSize(),
        )
        if (faceBoxes.isNotEmpty()) {
            val alpha by animateFloatAsState(if (showFaceBoxes) 1f else 0f, label = "faceBoxes")
            FaceBoxesOverlay(faceBoxes, zoomableState, Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha })
        }
    }
}

/**
 * Квадраты вокруг лиц поверх фото. Рамка переводится из долей кадра в экранные координаты
 * картинки с учётом зума и сдвига (Telephoto coordinateSystem), поэтому следует за жестами.
 */
@OptIn(ExperimentalTelephotoApi::class) // coordinateSystem: стабильного аналога для границ контента нет
@Composable
private fun FaceBoxesOverlay(boxes: List<FaceBox>, zoomableState: ZoomableState, modifier: Modifier) {
    val stroke = with(LocalDensity.current) { 2.dp.toPx() }
    val corner = with(LocalDensity.current) { 6.dp.toPx() }
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelMedium.copy(
        color = Color.White,
        shadow = Shadow(Color.Black.copy(alpha = 0.8f), Offset(0f, 1f), blurRadius = 4f),
    )
    Canvas(modifier) {
        val bounds = zoomableState.coordinateSystem.contentBounds(clipToViewport = false)
        if (bounds.isUnspecified) return@Canvas
        val content = with(zoomableState.coordinateSystem) { bounds.rectIn(CoordinateSpace.Viewport) }
        for (box in boxes) {
            // Лицо — квадрат по большей стороне рамки, с небольшим запасом.
            val cx = content.left + (box.left + box.right) / 2 * content.width
            val cy = content.top + (box.top + box.bottom) / 2 * content.height
            val half = maxOf((box.right - box.left) * content.width, (box.bottom - box.top) * content.height) * 0.6f
            val topLeft = Offset(cx - half, cy - half)
            val size = Size(half * 2, half * 2)
            // Тёмная подложка под белой линией — видно и на светлом, и на тёмном фоне.
            drawRoundRect(Color.Black.copy(alpha = 0.45f), topLeft, size, CornerRadius(corner), style = Stroke(stroke * 2.5f))
            drawRoundRect(Color.White, topLeft, size, CornerRadius(corner), style = Stroke(stroke))
            box.label?.let { label ->
                val text = textMeasurer.measure(label, labelStyle, maxLines = 1)
                drawText(text, topLeft = Offset(cx - text.size.width / 2f, cy + half + stroke * 2))
            }
        }
    }
}

private const val MAX_ZOOM = 5f
