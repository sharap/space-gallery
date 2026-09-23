package ai.recommend.spacegallery.ml.text

import android.graphics.Bitmap
import android.graphics.RectF
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.GenericMultipleBarcodeReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/** Найденный код: содержимое, вид (QR, EAN-13…) и рамка в долях кадра. */
data class ScannedCode(val value: String, val format: String, val box: RectF)

/**
 * Поиск QR-кодов и штрихкодов на снимке (ZXing, Apache 2.0) — без нейросети и без сервисов
 * Google. Ищутся все коды кадра: на скриншотах и афишах их бывает несколько.
 */
class CodeScanner {

    /** Несколько кодов на кадре — только при разборе всего кадра: по кускам это слишком дорого. */
    private val multiple = GenericMultipleBarcodeReader(MultiFormatReader())
    private val single = MultiFormatReader()

    /**
     * Ищет коды сначала по всему кадру, затем — если ничего не нашлось — по перекрывающимся
     * кускам [TILES]×[TILES]. Порог яркости ZXing считает по окрестности, и на большом кадре
     * мелкий код «тонет»; на куске он занимает заметную часть и находится.
     */
    suspend fun scan(bitmap: Bitmap, tiles: Int = TILES, tryHarder: Boolean = true): List<ScannedCode> = withContext(Dispatchers.Default) {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val whole = decode(pixels, bitmap.width, bitmap.height, 0, 0, bitmap.width, bitmap.height, tryHarder, multi = true)
        if (whole.isNotEmpty() || tiles < 2 || min(bitmap.width, bitmap.height) < MIN_TILED_SIDE) return@withContext whole

        val found = LinkedHashMap<String, ScannedCode>()
        val stepX = bitmap.width / tiles
        val stepY = bitmap.height / tiles
        val overlapX = (stepX * OVERLAP).toInt()
        val overlapY = (stepY * OVERLAP).toInt()
        for (ty in 0 until tiles) {
            for (tx in 0 until tiles) {
                val left = max(0, tx * stepX - overlapX)
                val top = max(0, ty * stepY - overlapY)
                val right = min(bitmap.width, (tx + 1) * stepX + overlapX)
                val bottom = min(bitmap.height, (ty + 1) * stepY + overlapY)
                // По кускам — быстрый разбор: «тщательный» режим здесь съедает секунды на кадр.
                for (code in decode(pixels, bitmap.width, bitmap.height, left, top, right, bottom, tryHarder = false, multi = false)) {
                    found.putIfAbsent(code.value, code)
                }
            }
        }
        found.values.toList()
    }

    private fun decode(
        pixels: IntArray,
        width: Int,
        height: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        tryHarder: Boolean,
        multi: Boolean,
    ): List<ScannedCode> {
        val source = RGBLuminanceSource(width, height, pixels).crop(left, top, right - left, bottom - top)
        val image = BinaryBitmap(HybridBinarizer(source))
        // TRY_HARDER: коды на фото бывают под углом и не в фокусе, но разбор заметно дороже.
        val hints = buildMap<DecodeHintType, Any> {
            if (tryHarder) put(DecodeHintType.TRY_HARDER, true)
            put(DecodeHintType.POSSIBLE_FORMATS, FORMATS)
        }
        val results = runCatching {
            if (multi) multiple.decodeMultiple(image, hints).toList() else listOf(single.decode(image, hints))
        }.getOrNull().orEmpty()
        single.reset()
        return results.mapNotNull { result ->
            if (result.text.isNullOrBlank()) return@mapNotNull null
            val points = result.resultPoints?.filterNotNull().orEmpty()
            val box = if (points.isEmpty()) {
                RectF(left.toFloat() / width, top.toFloat() / height, right.toFloat() / width, bottom.toFloat() / height)
            } else {
                RectF(
                    (left + points.minOf { it.x }) / width,
                    (top + points.minOf { it.y }) / height,
                    (left + points.maxOf { it.x }) / width,
                    (top + points.maxOf { it.y }) / height,
                )
            }
            ScannedCode(result.text, result.barcodeFormat.name, box)
        }
    }

    private companion object {
        const val TILES = 2
        const val OVERLAP = 0.15f
        /** На маленьких картинках деление на куски бессмысленно. */
        const val MIN_TILED_SIDE = 400

        val FORMATS = listOf(
            BarcodeFormat.QR_CODE,
            BarcodeFormat.DATA_MATRIX,
            BarcodeFormat.AZTEC,
            BarcodeFormat.EAN_13,
            BarcodeFormat.EAN_8,
            BarcodeFormat.CODE_128,
            BarcodeFormat.CODE_39,
            BarcodeFormat.UPC_A,
        )
    }
}
