package ai.recommend.spacegallery.ml.onnx

/**
 * Описание ONNX-модели и её препроцессинга.
 * Файлы ищутся в `filesDir/models/` (скачанные/подложенные), затем в `assets/models/`.
 * Как получить модели — см. `models/README.md` в корне проекта.
 */
enum class ModelId(val fileName: String) {
    /** Визуальный энкодер CLIP-подобной модели: [1,3,H,W] -> [1,D]. */
    CLIP_IMAGE("clip_image.onnx"),

    /** Текстовый энкодер той же модели: input_ids [1,77] -> [1,D]. */
    CLIP_TEXT("clip_text.onnx"),

    /** Бинарный классификатор деликатного контента: [1,3,H,W] -> [1,2] (logits). */
    NSFW("nsfw.onnx"),
}

data class ImageInputSpec(
    val size: Int,
    val mean: FloatArray,
    val std: FloatArray,
)

object ModelSpecs {
    /** Препроцессинг OpenAI CLIP ViT-B/32 (для MobileCLIP / SigLIP значения другие). */
    val CLIP_IMAGE = ImageInputSpec(
        size = 224,
        mean = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f),
        std = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f),
    )

    /** Длина контекста текстового энкодера CLIP. */
    const val CLIP_CONTEXT_LENGTH = 77

    /** Falconsai/nsfw_image_detection (ViT): labels = [normal, nsfw]. */
    val NSFW = ImageInputSpec(
        size = 224,
        mean = floatArrayOf(0.5f, 0.5f, 0.5f),
        std = floatArrayOf(0.5f, 0.5f, 0.5f),
    )
    const val NSFW_POSITIVE_INDEX = 1
}
