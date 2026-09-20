package ai.recommend.spacegallery.bench

import ai.recommend.spacegallery.di.AppContainer
import java.io.DataOutputStream
import java.io.File

/**
 * Выгрузка векторов лиц с разметкой для подбора алгоритма группировки на машине разработчика
 * (debug). Фото и миниатюры не выгружаются — только числа. Формат `faces.bin`:
 * `int32 count, int32 dim`, далее на каждое лицо: id, mediaId, personId, lockedPersonId (int64,
 * 0 — нет), score, left, top, right, bottom (float32), затем dim float32 вектора.
 * Рядом `persons.tsv`: id, имя (подтверждённые пользователем — разметка для оценки).
 */
class FaceExport(private val c: AppContainer) {

    suspend fun run(): String {
        val s = c.settings.current()
        val faces = c.database.faceDao().getForClustering(s.hideSensitive, s.sensitiveThreshold, s.faceModel.embedVersion)
        val dim = faces.firstOrNull()?.embedding?.size?.div(4) ?: 0
        val dir = File(c.appContext.filesDir, "bench").apply { mkdirs() }
        val bin = File(dir, "faces.bin")
        DataOutputStream(bin.outputStream().buffered()).use { out ->
            out.writeInt(faces.size)
            out.writeInt(dim)
            for (f in faces) {
                out.writeLong(f.id)
                out.writeLong(f.mediaId)
                out.writeLong(f.personId ?: 0L)
                out.writeLong(f.lockedPersonId ?: 0L)
                out.writeFloat(f.score)
                out.writeFloat(f.left)
                out.writeFloat(f.top)
                out.writeFloat(f.right)
                out.writeFloat(f.bottom)
                out.write(f.embedding)
            }
        }
        // «Это не он»: отрицательные примеры пользователя.
        val rejections = c.database.faceDao().getRejections()
        File(dir, "rejections.tsv").writeText(rejections.joinToString("\n") { "${it.faceId}\t${it.personId}" })
        // Имена файлов: по ним лаборатория на ПК находит те же снимки (см. tools/facelab).
        val mediaIds = faces.map { it.mediaId }.distinct()
        val media = mediaIds.chunked(900).flatMap { c.mediaRepository.getByIds(it) }
        File(dir, "media.tsv").writeText(
            media.joinToString("\n") { "${it.id}\t${it.displayName}\t${it.width}\t${it.height}" }
        )
        val persons = c.database.faceDao().getPersonRows()
        File(dir, "persons.tsv").writeText(persons.joinToString("\n") { "${it.id}\t${it.name.orEmpty()}\t${it.mediaCount}" })
        val named = persons.count { !it.name.isNullOrBlank() }
        val locked = faces.count { it.lockedPersonId != null }
        val pending = c.faceReembedder.countPending()
        val unchecked = c.database.faceDao().countUnchecked(0.8f)
        return "лиц ${faces.size} (модель ${s.faceModel} (v${s.faceModel.embedVersion}), ждут пересчёта $pending, непроверенных CLIP $unchecked), «это не он» ${rejections.size}, dim $dim, закреплённых $locked, людей ${persons.size} (с именем $named), файл ${bin.length() / 1024} КБ, снимков ${media.size}"
    }
}
