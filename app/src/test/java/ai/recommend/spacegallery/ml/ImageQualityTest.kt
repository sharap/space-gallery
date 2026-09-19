package ai.recommend.spacegallery.ml

import ai.recommend.spacegallery.ml.quality.ImageQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageQualityTest {

    private fun gray(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    /** Шахматка с клеткой [cell] px; [blur] — размытие box-фильтром радиуса blur. */
    private fun checker(w: Int, h: Int, cell: Int, blur: Int = 0, region: (Int, Int) -> Boolean = { _, _ -> true }): IntArray {
        val base = Array(h) { y -> FloatArray(w) { x -> if (((x / cell) + (y / cell)) % 2 == 0) 230f else 20f } }
        return IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val v = if (blur == 0 || !region(x, y)) base[y][x] else {
                var s = 0f
                var c = 0
                for (dy in -blur..blur) for (dx in -blur..blur) {
                    val yy = (y + dy).coerceIn(0, h - 1)
                    val xx = (x + dx).coerceIn(0, w - 1)
                    s += base[yy][xx]; c++
                }
                s / c
            }
            gray(v.toInt())
        }
    }

    @Test
    fun blurredIsLessSharp() {
        val sharp = ImageQuality.measure(checker(128, 96, 8), 128, 96)
        val blurred = ImageQuality.measure(checker(128, 96, 8, blur = 3), 128, 96)
        assertTrue("sharp=${sharp.sharpness} blurred=${blurred.sharpness}", sharp.sharpness > blurred.sharpness * 5)
    }

    @Test
    fun sharpSubjectWithBlurredBackgroundStaysSharp() {
        val w = 128
        val h = 96
        val full = ImageQuality.measure(checker(w, h, 8), w, h)
        // Резок только левый верхний участок сетки — как объект на размытом фоне.
        val bokeh = ImageQuality.measure(checker(w, h, 8, blur = 3) { x, y -> x >= w / 4 || y >= h / 4 }, w, h)
        assertTrue(bokeh.sharpness > full.sharpness * 0.5f)
    }

    @Test
    fun brightness() {
        val q = ImageQuality.measure(IntArray(100) { gray(51) }, 10, 10)
        assertEquals(0.2f, q.brightness, 0.01f)
        assertEquals(0f, q.sharpness, 1e-6f)
    }
}
