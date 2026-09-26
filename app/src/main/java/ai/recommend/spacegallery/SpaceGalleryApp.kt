package ai.recommend.spacegallery

import ai.recommend.spacegallery.di.AppContainer
import ai.recommend.spacegallery.work.IndexingNotifications
import android.app.Application
import android.content.Intent
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.video.VideoFrameDecoder

class SpaceGalleryApp : Application(), SingletonImageLoader.Factory {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        IndexingNotifications.createChannel(this)
        watchForeground()
        watchScreen()
    }

    /**
     * Когда экран погас: индексации это нужно, чтобы понять, что телефон отложили.
     * Ждать системного простоя (`isDeviceIdleMode`) мало — он наступает не всегда и не скоро,
     * а ночь на зарядке хочется использовать.
     */
    private fun watchScreen() {
        screenOffSince = if (getSystemService(android.os.PowerManager::class.java)?.isInteractive != false) 0L
        else android.os.SystemClock.elapsedRealtime()
        val filter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context, intent: Intent) {
                screenOffSince = if (intent.action == Intent.ACTION_SCREEN_OFF) android.os.SystemClock.elapsedRealtime() else 0L
            }
        }
        // Системные широковещательные события: наружу приёмник не открываем.
        androidx.core.content.ContextCompat.registerReceiver(
            this, receiver, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    /**
     * Приложение на экране или нет — это нужно индексации: пока пользователь смотрит на
     * прогресс, она работает в полный темп (см. [ai.recommend.spacegallery.work.IndexingPace]).
     * Судить по `ActivityManager.getMyMemoryState` нельзя: как только воркер поднимает
     * foreground service, важность процесса становится «сервисной» и признак теряется.
     */
    private fun watchForeground() {
        registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            private var started = 0

            override fun onActivityStarted(activity: android.app.Activity) {
                started++
                isOnScreen = true
            }

            override fun onActivityStopped(activity: android.app.Activity) {
                started--
                if (started <= 0) isOnScreen = false
            }

            override fun onActivityCreated(activity: android.app.Activity, state: android.os.Bundle?) = Unit
            override fun onActivityResumed(activity: android.app.Activity) = Unit
            override fun onActivityPaused(activity: android.app.Activity) = Unit
            override fun onActivitySaveInstanceState(activity: android.app.Activity, out: android.os.Bundle) = Unit
            override fun onActivityDestroyed(activity: android.app.Activity) = Unit
        })
    }

    companion object {
        /** Видит ли пользователь приложение прямо сейчас. */
        @Volatile
        var isOnScreen: Boolean = false
            private set

        /** Когда погас экран (`SystemClock.elapsedRealtime`), 0 — экран включён. */
        @Volatile
        var screenOffSince: Long = 0L
            private set
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(VideoFrameDecoder.Factory()) }
            .build()
}
