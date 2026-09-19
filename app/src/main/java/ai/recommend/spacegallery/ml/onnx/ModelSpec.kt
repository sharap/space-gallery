package ai.recommend.spacegallery.ml.onnx

/**
 * Описание ONNX-модели и её препроцессинга.
 * Файлы ищутся в `filesDir/models/` (в релизе модели скачиваются туда отдельно от APK),
 * затем в `assets/models/` — только debug-сборка, см. `app/src/debug/assets`.
 * Как получить модели — см. `models/README.md` в корне проекта.
 */
enum class ModelId(val fileName: String) {
    /** Визуальный энкодер CLIP: pixel_values [1,3,224,224] -> image_embeds [1,512]. */
    CLIP_IMAGE("clip_image.onnx"),

    /** Текстовый энкодер той же модели (английский): input_ids [1,77] int64 -> text_embeds [1,512]. */
    CLIP_TEXT("clip_text.onnx"),

    /**
     * Многоязычный (в т.ч. русский) текстовый энкодер clip-ViT-B-32-multilingual-v1,
     * обученный в пространство визуальной части CLIP ViT-B/32:
     * input_ids + attention_mask [1,seq] int64 -> text_embeds [1,512].
     */
    CLIP_TEXT_MULTILINGUAL("clip_text_multilingual.onnx"),

    /** Классификатор деликатного контента: pixel_values [1,3,384,384] -> logits [1,2]. */
    NSFW("nsfw.onnx"),
}

data class ImageInputSpec(
    val size: Int,
    val mean: FloatArray,
    val std: FloatArray,
    /** true — resize + center crop (CLIP); false — растянуть до квадрата (ViTFeatureExtractor). */
    val centerCrop: Boolean = true,
)

object ModelSpecs {
    /** Xenova/clip-vit-base-patch32 (preprocessor_config.json). Для SigLIP / MobileCLIP значения другие. */
    val CLIP_IMAGE = ImageInputSpec(
        size = 224,
        mean = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f),
        std = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f),
    )

    /** Длина контекста текстового энкодера CLIP. */
    const val CLIP_CONTEXT_LENGTH = 77

    /** onnx-community/vit-base-nsfw-detector-ONNX (AdamCodd): labels = [sfw, nsfw]. */
    val NSFW = ImageInputSpec(
        size = 384,
        mean = floatArrayOf(0.5f, 0.5f, 0.5f),
        std = floatArrayOf(0.5f, 0.5f, 0.5f),
        centerCrop = false,
    )
    const val NSFW_POSITIVE_INDEX = 1
}
