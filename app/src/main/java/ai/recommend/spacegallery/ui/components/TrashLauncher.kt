package ai.recommend.spacegallery.ui.components

import ai.recommend.spacegallery.data.media.DeleteResult
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable

/**
 * Обёртка над системным диалогом подтверждения удаления (MediaStore.createTrashRequest).
 * Возвращает функцию, которую нужно вызвать с результатом [DeleteResult].
 */
@Composable
fun rememberTrashConfirmation(onConfirmed: () -> Unit): (DeleteResult) -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) onConfirmed()
    }
    return { result ->
        when (result) {
            is DeleteResult.NeedsConfirmation ->
                launcher.launch(IntentSenderRequest.Builder(result.intentSender).build())
            DeleteResult.Done -> onConfirmed()
            is DeleteResult.Failed -> Unit // TODO: показать ошибку (snackbar)
        }
    }
}

/**
 * Системный диалог разрешения на изменение файлов (MediaStore.createWriteRequest):
 * [onGranted] вызывается, если пользователь согласился.
 */
@Composable
fun rememberWriteRequestLauncher(onGranted: () -> Unit): (android.content.IntentSender) -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) onGranted()
    }
    return { sender -> launcher.launch(IntentSenderRequest.Builder(sender).build()) }
}
