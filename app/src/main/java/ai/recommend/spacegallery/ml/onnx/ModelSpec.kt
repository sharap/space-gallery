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

    /**
     * Быстрый NSFW-классификатор поверх эмбеддинга CLIP ViT-B/32 (LAION CLIP-based-NSFW-Detector):
     * clip_embeds [1,512] -> nsfw_prob [1,1]. Используется как префильтр перед [NSFW].
     */
    NSFW_CLIP("nsfw_clip.onnx"),

    /**
     * Детектор лиц YuNet (OpenCV Zoo, MIT): input [1,3,640,640] BGR 0..255 ->
     * cls/obj/bbox/kps для шагов 8/16/32 (рамка + 5 ключевых точек).
     */
    FACE_DETECT("face_detect.onnx"),

    /** Распознавание лиц SFace (OpenCV Zoo, Apache 2.0): data [1,3,112,112] RGB 0..255 -> fc1 [1,128]. */
    FACE_EMBED("face_embed.onnx"),
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

    /**
     * Порог префильтра [ModelId.NSFW_CLIP]: ниже — кадр считается безопасным без запуска ViT.
     * Подобран на 2606 реальных фото (2026-09-19): ViT запускается на ~13% кадров и находит
     * 14/14 кадров, которые сам ViT помечает при пороге 0.7 (20/21 при 0.5).
     */
    const val NSFW_CLIP_PREFILTER = 1e-4f
}
