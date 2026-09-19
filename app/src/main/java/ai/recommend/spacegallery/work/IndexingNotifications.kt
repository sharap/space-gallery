package ai.recommend.spacegallery.work

import ai.recommend.spacegallery.MainActivity
import ai.recommend.spacegallery.R
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import ai.recommend.spacegallery.domain.IndexingPhase
import java.util.UUID

/** Уведомление foreground-сервиса индексации: прогресс + кнопка «Остановить». */
object IndexingNotifications {

    private const val CHANNEL_ID = "indexing"
    const val NOTIFICATION_ID = 1001

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_indexing),
            NotificationManager.IMPORTANCE_LOW, // без звука и всплывания
        ).apply { setShowBadge(false) }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun foregroundInfo(context: Context, workId: UUID, phase: IndexingPhase, processed: Int, total: Int): ForegroundInfo {
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_indexing)
            .setContentTitle(context.getString(phase.titleRes()))
            .setContentText(
                if (total > 0) context.getString(R.string.notification_indexing_progress, processed, total) else null
            )
            .setProgress(total, processed, total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp)
            .addAction(
                0,
                context.getString(R.string.notification_indexing_stop),
                WorkManager.getInstance(context).createCancelPendingIntent(workId),
            )
            .build()

        return when {
            // Android 15+: специальный тип для обработки медиа.
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM ->
                ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else -> ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }
}

/** Подпись этапа индексации — в уведомлении и в ленте. */
fun IndexingPhase.titleRes(): Int = when (this) {
    IndexingPhase.ANALYSIS -> R.string.notification_indexing_title
    IndexingPhase.GROUPING -> R.string.indexing_phase_grouping
    IndexingPhase.LOCATION -> R.string.indexing_phase_location
    IndexingPhase.QUALITY -> R.string.indexing_phase_quality
    IndexingPhase.FACES -> R.string.indexing_phase_faces
}
