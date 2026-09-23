package ai.recommend.spacegallery.search.text

import ai.recommend.spacegallery.data.db.MediaCodeEntity
import ai.recommend.spacegallery.data.db.MediaEntity
import ai.recommend.spacegallery.data.db.MediaTextEntity
import ai.recommend.spacegallery.data.db.TextDao
import ai.recommend.spacegallery.data.db.TextLineEntity
import ai.recommend.spacegallery.data.settings.SettingsRepository
import ai.recommend.spacegallery.data.settings.TextLanguage
import ai.recommend.spacegallery.domain.MediaType
import ai.recommend.spacegallery.ml.image.BitmapLoader
import ai.recommend.spacegallery.ml.text.CodeScanner
import ai.recommend.spacegallery.ml.text.TextDetector
import ai.recommend.spacegallery.ml.text.TextLine
import ai.recommend.spacegallery.ml.text.TextRecognizer
import ai.recommend.spacegallery.perf.PerfStats
import android.graphics.Bitmap
import android.graphics.RectF
import androidx.core.net.toUri

/**
 * Распознавание текста и поиск кодов на снимках — отдельный проход после основного анализа.
 *
 * Сначала работает детектор строк (дёшево): на реальной медиатеке текста нет у двух третей
 * снимков, и тогда распознавание не запускается вовсе. Видео пропускаются.
 */
class TextIndexer(
    private val bitmapLoader: BitmapLoader,
    private val detector: TextDetector,
    private val recognizer: TextRecognizer,
    private val codes: CodeScanner,
    private val dao: TextDao,
    private val settings: SettingsRepository,
) {
    /** Коды ищутся и без моделей текста, поэтому проход имеет смысл всегда. */
    val isAvailable: Boolean get() = true

    private suspend fun canReadText(): Boolean =
        detector.isAvailable && languages().any { recognizer.isAvailable(it) }

    suspend fun countPending(): Int {
        // Проверка до подсчёта: иначе смена языков не заметна, пока все снимки уже обработаны.
        syncLanguages()
        return dao.countPending(TEXT_VERSION)
    }

    /** Сменился набор языков — весь текст читается заново. */
    private suspend fun syncLanguages() {
        val signature = languages().filter { recognizer.isAvailable(it) }.joinToString(",") { it.name }
        if (settings.textLanguagesSignature() == signature) return
        dao.resetVersions()
        settings.setTextLanguagesSignature(signature)
    }

    private suspend fun languages(): Set<TextLanguage> = settings.current().textLanguages

    /** @return сколько снимков обработано и на скольких нашёлся текст или код. */
    suspend fun run(isStopped: () -> Boolean, onProgress: suspend (Int) -> Unit): Pair<Int, Int> {
        val readText = canReadText()
        val languages = languages().filter { recognizer.isAvailable(it) }
        if (readText) recognizer.prepare(languages.toSet())
        var processed = 0
        var found = 0
        var afterDate = Long.MAX_VALUE
        var afterId = Long.MAX_VALUE
        while (!isStopped()) {
            val page = dao.getPendingPage(TEXT_VERSION, afterDate, afterId, BATCH)
            if (page.isEmpty()) break
            val texts = ArrayList<MediaTextEntity>()
            val lines = ArrayList<TextLineEntity>()
            val scanned = ArrayList<MediaCodeEntity>()
            val done = ArrayList<Long>(page.size)
            for (media in page) {
                if (isStopped()) break
                val result = PerfStats.measure("text.total") { read(media, languages.takeIf { readText }.orEmpty()) }
                done += media.id
                if (result == null) continue
                if (result.lines.isNotEmpty()) {
                    texts += MediaTextEntity(media.id, result.lines.joinToString("\n") { it.text }, result.lines.size)
                    lines += result.lines
                }
                scanned += result.codes
                if (result.lines.isNotEmpty() || result.codes.isNotEmpty()) found++
            }
            dao.saveBatch(done, texts, lines, scanned, TEXT_VERSION)
            processed += done.size
            onProgress(processed)
            afterDate = page.last().dateTaken
            afterId = page.last().id
        }
        return processed to found
    }

    private class Result(val lines: List<TextLineEntity>, val codes: List<MediaCodeEntity>)

    private suspend fun read(media: MediaEntity, languages: List<TextLanguage>): Result? {
        val bitmap = PerfStats.measure("text.load") {
            bitmapLoader.load(media.uri.toUri(), MediaType.IMAGE, targetSize = SOURCE_SIZE)
        } ?: return null
        return try {
            val boxes = if (languages.isEmpty()) emptyList() else PerfStats.measure("text.detect") { detector.detect(bitmap) }
            val recognized = if (boxes.isEmpty()) {
                emptyList()
            } else {
                PerfStats.measure("text.recognize") { recognize(bitmap, boxes.take(MAX_LINES), languages) }
            }
            val lines = recognized
                .filter { it.confidence >= MIN_CONFIDENCE && it.text.length >= MIN_LENGTH }
                .map {
                    TextLineEntity(
                        mediaId = media.id,
                        text = it.text,
                        left = it.box.left,
                        top = it.box.top,
                        right = it.box.right,
                        bottom = it.box.bottom,
                        confidence = it.confidence,
                    )
                }
            val found = PerfStats.measure("text.codes") { codes.scan(bitmap) }
                .map {
                    MediaCodeEntity(
                        mediaId = media.id,
                        value = it.value,
                        format = it.format,
                        left = it.box.left,
                        top = it.box.top,
                        right = it.box.right,
                        bottom = it.box.bottom,
                    )
                }
            Result(lines, found)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Языков может быть несколько, но снимок обычно на одном. Чтобы не читать всё дважды:
     * пробуем модели на самых длинных строках (короткие — время, цифры, иконки — язык не
     * определяют), выбираем уверенную и дочитываем ею. Строки, которые победившая модель
     * прочла неуверенно, перечитываются остальными — так смешанный текст не теряется.
     */
    private suspend fun recognize(bitmap: Bitmap, boxes: List<RectF>, languages: List<TextLanguage>): List<TextLine> {
        if (languages.size == 1) return recognizer.recognize(bitmap, boxes, languages.first())
        val probe = boxes.sortedByDescending { (it.right - it.left) * (it.bottom - it.top) }.take(PROBE_LINES)
        val probes = languages.associateWith { recognizer.recognize(bitmap, probe, it) }
        // Счёт языка: уверенность, взвешенная длиной текста — длинная строка весомее короткой.
        val best = probes.maxByOrNull { (_, lines) -> lines.sumOf { it.confidence.toDouble() * it.text.length } }
            ?: return emptyList()
        val rest = boxes.filter { box -> probe.none { it == box } }
        val lines = best.value + if (rest.isEmpty()) emptyList() else recognizer.recognize(bitmap, rest, best.key)

        // Если победившая модель уверена, перечитывать нечего: это экономит целый прогон.
        val mean = lines.map { it.confidence }.average()
        val weak = lines.filter { it.confidence < RETRY_CONFIDENCE }.take(MAX_RETRY_LINES)
        if (weak.isEmpty() || mean >= CONFIDENT_MEAN) return lines
        val others = languages - best.key
        val improved = HashMap<RectF, TextLine>()
        for (language in others) {
            for (line in recognizer.recognize(bitmap, weak.map { it.box }, language)) {
                val current = improved[line.box]
                if (current == null || line.confidence > current.confidence) improved[line.box] = line
            }
        }
        return lines.map { line ->
            val other = improved[line.box]
            if (other != null && other.confidence > line.confidence + RETRY_MARGIN) other else line
        }
    }

    companion object {
        /**
         * Увеличить при смене моделей или правил разбора — текст будет прочитан заново.
         * 1 — первый проход; 2 — язык выбирается по самым длинным строкам, неуверенные строки
         * перечитываются другой моделью, коды ищутся и по кускам кадра.
         */
        const val TEXT_VERSION = 2

        private const val BATCH = 8

        /** Крупнее превью для лиц: мелкий текст на 640 px уже не читается. */
        private const val SOURCE_SIZE = 1280

        /** Строк на снимок: на плотных скриншотах их бывают сотни, а польза от хвоста мала. */
        private const val MAX_LINES = 120
        private const val MIN_CONFIDENCE = 0.5f
        private const val MIN_LENGTH = 2

        /** Сколько самых длинных строк прочитать каждой моделью, чтобы понять язык снимка. */
        private const val PROBE_LINES = 3

        /** Ниже этой уверенности строку стоит перечитать другой моделью. */
        private const val RETRY_CONFIDENCE = 0.6f

        /** Сколько неуверенных строк перечитывать: остальные почти всегда мусор (иконки, рамки). */
        private const val MAX_RETRY_LINES = 12

        /** Снимок уверенно прочитан — второй язык на нём искать незачем. */
        private const val CONFIDENT_MEAN = 0.85

        /** Замена принимается, только если другая модель заметно увереннее. */
        private const val RETRY_MARGIN = 0.1f
    }
}
