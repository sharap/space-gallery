package ai.recommend.spacegallery.data.media

import android.app.RecoverableSecurityException
import android.content.ContentResolver
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface DeleteResult {
    data object Done : DeleteResult

    /** Требуется подтверждение пользователя через системный диалог. */
    data class NeedsConfirmation(val intentSender: IntentSender) : DeleteResult

    data class Failed(val error: Throwable) : DeleteResult
}

class MediaDeleter(private val resolver: ContentResolver) {

    /**
     * Android 11+: перемещение в системную корзину (с подтверждением).
     * Android 10: RecoverableSecurityException для чужих файлов.
     * Android 9: прямое удаление по WRITE_EXTERNAL_STORAGE.
     */
    suspend fun moveToTrash(uris: List<Uri>): DeleteResult = withContext(Dispatchers.IO) {
        if (uris.isEmpty()) return@withContext DeleteResult.Done
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val pi = MediaStore.createTrashRequest(resolver, uris, true)
                DeleteResult.NeedsConfirmation(pi.intentSender)
            } else {
                uris.forEach { resolver.delete(it, null, null) }
                DeleteResult.Done
            }
        } catch (e: RecoverableSecurityException) {
            // TODO: на Android 10 системный диалог подтверждает только один файл за раз.
            DeleteResult.NeedsConfirmation(e.userAction.actionIntent.intentSender)
        } catch (e: Exception) {
            DeleteResult.Failed(e)
        }
    }
}
