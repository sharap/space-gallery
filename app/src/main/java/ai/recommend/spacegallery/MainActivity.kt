package ai.recommend.spacegallery

import ai.recommend.spacegallery.ui.navigation.SpaceGalleryNavHost
import ai.recommend.spacegallery.ui.permission.MediaPermissionGate
import ai.recommend.spacegallery.ui.theme.SpaceGalleryTheme
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Surface
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as SpaceGalleryApp).container
        setContent {
            SpaceGalleryTheme {
                Surface {
                    MediaPermissionGate(
                        onGranted = {
                            lifecycleScope.launch {
                                // Быстрая синхронизация сразу, AI-анализ — в фоне через WorkManager.
                                container.mediaRepository.syncWithMediaStore()
                                val settings = container.settings.current()
                                container.indexingScheduler.ensureIndexingNow(settings.indexOnlyWhileCharging)
                                container.indexingScheduler.startWatchingMediaStore()
                            }
                        },
                    ) {
                        SpaceGalleryNavHost()
                    }
                }
            }
        }
    }
}
