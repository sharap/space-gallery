package ai.recommend.spacegallery.ml.onnx

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession

/** Единственный OrtEnvironment на процесс + фабрика сессий с общими настройками. */
class OnnxRuntimeHolder {

    val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

    companion object {
        private val defaultThreads: Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)

        /**
         * Сколько потоков отдавать инференсу. Индексация ставит это число по темпу
         * ([ai.recommend.spacegallery.work.IndexingPace]) перед загрузкой моделей: число
         * запоминается сессией при создании, поэтому смена темпа посреди этапа
         * действует только на паузы, а на потоки — со следующей загрузки модели.
         */
        @Volatile
        var intraOpThreads: Int = defaultThreads

        /** После индексации — обратно, чтобы поиск в открытом приложении считался быстро. */
        fun resetThreads() {
            intraOpThreads = defaultThreads
        }
    }

    fun createSession(modelPath: String): OrtSession {
        val options = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(intraOpThreads)
            // Предупакованные копии весов удваивают память крупных моделей (ArcFace ~174 МБ),
            // из-за чего система убивала процесс во время индексации.
            addConfigEntry("session.disable_prepacking", "1")
            // Можно попробовать ускорители — прирост сильно зависит от устройства и модели:
            //   addXnnpack(mapOf("intra_op_num_threads" to "4"))
            //   addNnapi()  // устарело в Android 15, но всё ещё работает
        }
        return env.createSession(modelPath, options)
    }
}
