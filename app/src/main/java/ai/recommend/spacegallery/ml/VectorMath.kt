package ai.recommend.spacegallery.ml

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.sqrt

object VectorMath {

    fun l2Normalize(v: FloatArray): FloatArray {
        var sum = 0f
        for (x in v) sum += x * x
        val norm = sqrt(sum).takeIf { it > 1e-12f } ?: return v
        return FloatArray(v.size) { v[it] / norm }
    }

    /** Для L2-нормализованных векторов скалярное произведение = косинусная близость. */
    fun dot(a: FloatArray, b: FloatArray, bOffset: Int = 0): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[bOffset + i]
        return s
    }

    fun softmax(logits: FloatArray): FloatArray {
        val max = logits.max()
        val exps = FloatArray(logits.size) { exp(logits[it] - max) }
        val sum = exps.sum()
        return FloatArray(exps.size) { exps[it] / sum }
    }

    fun toBytes(v: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.asFloatBuffer().put(v)
        return buf.array()
    }

    fun fromBytes(bytes: ByteArray): FloatArray {
        val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(fb.remaining()).also { fb.get(it) }
    }
}
