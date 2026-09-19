package ai.recommend.spacegallery.ml.hash

import android.graphics.Bitmap
import androidx.core.graphics.scale

/**
 * dHash (difference hash): устойчив к ресайзу, перекодированию и лёгкой цветокоррекции.
 * Используется для поиска точных и почти точных дубликатов без нейросети.
 */
class PerceptualHasher {

    fun dHash(bitmap: Bitmap): Long {
        val small = bitmap.scale(9, 8, filter = true)
        val px = IntArray(9 * 8)
        small.getPixels(px, 0, 9, 0, 0, 9, 8)
        var hash = 0L
        var bit = 0
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                if (luma(px[y * 9 + x]) > luma(px[y * 9 + x + 1])) hash = hash or (1L shl bit)
                bit++
            }
        }
        return hash
    }

    private fun luma(p: Int): Int {
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }

    companion object {
        fun hammingDistance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)
    }
}
