package ai.recommend.spacegallery.bench

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.util.Log
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.Locale
import kotlin.random.Random

/**
 * Микробенчмарк ONNX-моделей на устройстве (только debug-сборка).
 * Входы генерируются по сигнатуре модели: картинки [N,3,H,W], input_ids/attention_mask [N,77], векторы [N,D].
 */
object OrtBenchmark {

    const val TAG = "OrtBench"

    data class Config(val model: File, val threads: Int, val ep: String, val batch: Int)

    data class Result(val config: Config, val createMs: Long, val perItemMs: List<Double>) {
        private val sorted = perItemMs.sorted()
        val median get() = sorted[sorted.size / 2]
        val p90 get() = sorted[(sorted.size * 9 / 10).coerceAtMost(sorted.lastIndex)]

        override fun toString(): String = String.format(
            Locale.ROOT, "%-34s ep=%-7s thr=%d batch=%d | create %5d ms | per item: median %7.1f  p90 %7.1f ms",
            config.model.name, config.ep, config.threads, config.batch, createMs, median, p90,
        )
    }

    fun run(config: Config, warmup: Int, iters: Int): Result {
        val env = OrtEnvironment.getEnvironment()
        val options = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            when (config.ep) {
                "cpu" -> setIntraOpNumThreads(config.threads)
                // Рекомендация ORT: при XNNPACK пул потоков ORT = 1, потоки отдаются XNNPACK.
                "xnnpack" -> {
                    setIntraOpNumThreads(1)
                    addConfigEntry("session.intra_op.allow_spinning", "0")
                    addXnnpack(mapOf("intra_op_num_threads" to config.threads.toString()))
                }
                "nnapi" -> {
                    setIntraOpNumThreads(config.threads)
                    addNnapi()
                }
                // Проверка ускорителя: с запретом CPU узлы либо берёт NPU, либо не берёт никто
                // и всё откатывается на CPU (подробный журнал показывает, что именно).
                // На Poco F6 ускорителя нет вовсе — см. docs/performance.md.
                "nnapi_npu" -> {
                    setIntraOpNumThreads(config.threads)
                    setSessionLogLevel(ai.onnxruntime.OrtLoggingLevel.ORT_LOGGING_LEVEL_VERBOSE)
                    addNnapi(java.util.EnumSet.of(ai.onnxruntime.providers.NNAPIFlags.CPU_DISABLED))
                }
                else -> error("unknown ep ${config.ep}")
            }
        }
        val t0 = System.nanoTime()
        val session = env.createSession(config.model.absolutePath, options)
        val createMs = (System.nanoTime() - t0) / 1_000_000
        try {
            val inputs = session.inputInfo.mapValues { (name, info) ->
                makeInput(env, name, info.info as TensorInfo, config.batch, imageSize(config.model))
            }
            try {
                repeat(warmup) { session.run(inputs).close() }
                val times = List(iters) {
                    val s = System.nanoTime()
                    session.run(inputs).close()
                    (System.nanoTime() - s) / 1e6 / config.batch
                }
                return Result(config, createMs, times)
            } finally {
                inputs.values.forEach { it.close() }
            }
        } finally {
            session.close()
            options.close()
        }
    }

    /** Размер картинки для динамических H/W: суффикс в имени файла (`nsfw_int8_384.onnx`), иначе 224. */
    private fun imageSize(model: File): Long =
        Regex("""_(\d{3})\.onnx$""").find(model.name)?.groupValues?.get(1)?.toLong() ?: 224L

    private fun makeInput(env: OrtEnvironment, name: String, info: TensorInfo, batch: Int, imageSize: Long): OnnxTensor {
        val shape = info.shape.copyOf()
        shape[0] = batch.toLong()
        return when (info.type) {
            OnnxJavaType.FLOAT -> {
                if (shape.size == 4) {
                    shape[1] = 3
                    for (i in 2..3) if (shape[i] <= 0) shape[i] = imageSize
                }
                val n = shape.fold(1L) { a, b -> a * b }.toInt()
                val rnd = Random(42)
                OnnxTensor.createTensor(env, FloatBuffer.wrap(FloatArray(n) { rnd.nextFloat() * 2 - 1 }), shape)
            }
            OnnxJavaType.INT64 -> {
                if (shape.size == 2 && shape[1] <= 0) shape[1] = 77
                val len = shape[1].toInt()
                val data = LongArray(batch * len) { i ->
                    val pos = i % len
                    when {
                        name.contains("mask") -> if (pos < 12) 1L else 0L
                        pos == 0 -> 101L
                        pos < 11 -> 1000L + pos
                        pos == 11 -> 102L
                        else -> 0L
                    }
                }
                OnnxTensor.createTensor(env, LongBuffer.wrap(data), shape)
            }
            else -> error("Unsupported input $name: ${info.type}")
        }
    }

    fun log(line: String) = Log.i(TAG, line)
}
