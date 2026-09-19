package ai.recommend.spacegallery.ml.image

import ai.recommend.spacegallery.ml.onnx.ImageInputSpec
import android.graphics.Bitmap
import androidx.core.graphics.scale
import java.nio.FloatBuffer
import kotlin.math.min

/** Bitmap -> нормализованный тензор NCHW [1,3,S,S] (resize короткой стороны + center crop). */
object ImageTensorizer {

    fun toNchw(bitmap: Bitmap, spec: ImageInputSpec): FloatBuffer {
        val s = spec.size
        val square = centerCropSquare(bitmap).let { if (it.width == s) it else it.scale(s, s) }
        val pixels = IntArray(s * s)
        square.getPixels(pixels, 0, s, 0, 0, s, s)

        val plane = s * s
        val buffer = FloatBuffer.allocate(3 * plane)
        for (i in 0 until plane) {
            val p = pixels[i]
            val r = ((p shr 16) and 0xFF) / 255f
            val g = ((p shr 8) and 0xFF) / 255f
            val b = (p and 0xFF) / 255f
            buffer.put(i, (r - spec.mean[0]) / spec.std[0])
            buffer.put(plane + i, (g - spec.mean[1]) / spec.std[1])
            buffer.put(2 * plane + i, (b - spec.mean[2]) / spec.std[2])
        }
        return buffer
    }

    fun shape(spec: ImageInputSpec) = longArrayOf(1, 3, spec.size.toLong(), spec.size.toLong())

    private fun centerCropSquare(bitmap: Bitmap): Bitmap {
        val side = min(bitmap.width, bitmap.height)
        if (bitmap.width == bitmap.height) return bitmap
        return Bitmap.createBitmap(
            bitmap,
            (bitmap.width - side) / 2,
            (bitmap.height - side) / 2,
            side,
            side,
        )
    }
}
