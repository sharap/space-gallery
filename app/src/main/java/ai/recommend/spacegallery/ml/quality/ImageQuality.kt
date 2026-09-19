package ai.recommend.spacegallery.ml.quality

import android.graphics.Bitmap

/** Оценка кадра: [sharpness] — резкость самого чёткого участка, [brightness] — средняя яркость 0..1. */
data class Quality(val sharpness: Float, val brightness: Float)

/**
 * Резкость без нейросети: дисперсия лапласиана яркости по участкам сетки [GRID]×[GRID],
 * берётся самый резкий участок. Так портрет с размытым фоном остаётся «резким» (резок объект),
 * а смаз или промах фокуса по всему кадру — нет. Кадр заранее уменьшен до ~512 px, поэтому
 * значения сопоставимы между фото разного разрешения.
 */
object ImageQuality {
    const val GRID = 4

    fun measure(bitmap: Bitmap): Quality {
        val w = bitmap.width
        val h = bitmap.height
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
        return measure(px, w, h)
    }

    fun measure(argb: IntArray, w: Int, h: Int): Quality {
        val luma = FloatArray(w * h)
        var sum = 0.0
        for (i in luma.indices) {
            val p = argb[i]
            val y = (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000f
            luma[i] = y
            sum += y
        }
        val brightness = (sum / luma.size / 255.0).toFloat()
        if (w < 3 || h < 3) return Quality(0f, brightness)

        val n = LongArray(GRID * GRID)
        val s1 = DoubleArray(GRID * GRID)
        val s2 = DoubleArray(GRID * GRID)
        for (y in 1 until h - 1) {
            val ty = y * GRID / h
            val row = y * w
            for (x in 1 until w - 1) {
                val i = row + x
                val lap = 4 * luma[i] - luma[i - 1] - luma[i + 1] - luma[i - w] - luma[i + w]
                val t = ty * GRID + x * GRID / w
                n[t]++
                s1[t] += lap
                s2[t] += lap.toDouble() * lap
            }
        }
        var best = 0.0
        for (t in n.indices) {
            if (n[t] == 0L) continue
            val mean = s1[t] / n[t]
            best = maxOf(best, s2[t] / n[t] - mean * mean)
        }
        return Quality(best.toFloat(), brightness)
    }
}
