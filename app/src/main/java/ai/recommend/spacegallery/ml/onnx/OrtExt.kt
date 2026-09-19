package ai.recommend.spacegallery.ml.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession

/** Первый выход модели (или выход с именем [preferredName]) как плоский FloatArray. */
fun OrtSession.Result.floatOutput(preferredName: String? = null): FloatArray {
    val value = preferredName?.let { name -> get(name).orElse(null) } ?: get(0)
    val buffer = (value as OnnxTensor).floatBuffer
    return FloatArray(buffer.remaining()).also { buffer.get(it) }
}
