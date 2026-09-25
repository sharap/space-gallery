package ai.recommend.spacegallery

import ai.recommend.spacegallery.di.AppContainer
import ai.recommend.spacegallery.work.IndexingNotifications
import android.app.Application
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
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(VideoFrameDecoder.Factory()) }
            .build()
}
