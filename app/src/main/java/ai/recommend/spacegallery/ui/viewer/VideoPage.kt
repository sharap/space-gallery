package ai.recommend.spacegallery.ui.viewer

import ai.recommend.spacegallery.domain.MediaItem
import ai.recommend.spacegallery.domain.MediaType
import android.content.Context
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.Player
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import androidx.media3.ui.compose.material3.buttons.MuteButton
import androidx.media3.ui.compose.material3.buttons.PlayPauseButton
import androidx.media3.ui.compose.material3.indicator.PositionAndDurationText
import androidx.media3.ui.compose.material3.indicator.ProgressSlider
import coil3.compose.AsyncImage
import androidx.media3.common.MediaItem as ExoMediaItem

/** Один плеер на весь просмотрщик: освобождается при уходе с экрана, пауза при сворачивании. */
@Composable
fun rememberViewerPlayer(): ExoPlayer {
    val context = LocalContext.current
    val player = remember { buildPlayer(context) }
    DisposableEffect(player) { onDispose { player.release() } }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { player.pause() }
    return player
}

private fun buildPlayer(context: Context): ExoPlayer =
    ExoPlayer.Builder(context).build().apply { repeatMode = Player.REPEAT_MODE_OFF }

/** Загрузить в плеер видео текущей страницы (или остановить, если на странице фото). */
fun ExoPlayer.bindTo(item: MediaItem?) {
    if (item?.type == MediaType.VIDEO) {
        setMediaItem(ExoMediaItem.fromUri(item.uri))
        prepare()
        playWhenReady = true
    } else {
        stop()
        clearMediaItems()
    }
}

/**
 * Страница видео. Картинку показывает только текущая страница (плеер один на все),
 * соседние — превью. Пока не отрисован первый кадр, превью служит «шторкой».
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoPage(
    item: MediaItem,
    player: Player?,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onTap),
        contentAlignment = Alignment.Center,
    ) {
        val thumbnail = @Composable {
            AsyncImage(
                model = item.uri,
                contentDescription = item.displayName,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (player != null) {
            // TextureView — без артефактов при листании pager, в отличие от SurfaceView.
            ContentFrame(
                player = player,
                surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
                modifier = Modifier.fillMaxSize(),
                shutter = thumbnail,
            )
        } else {
            thumbnail()
            Icon(
                Icons.Filled.PlayCircle,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.size(64.dp),
            )
        }
    }
}

/** Панель управления видео: пауза, перемотка, время, звук. */
@OptIn(UnstableApi::class, ExperimentalApi::class)
@Composable
fun VideoControls(player: Player, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.4f))
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayPauseButton(player, tint = Color.White)
        ProgressSlider(
            player,
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = Color.White.copy(alpha = 0.3f),
            ),
        )
        PositionAndDurationText(player, color = Color.White)
        MuteButton(player, tint = Color.White)
    }
}
