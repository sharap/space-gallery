package ai.recommend.spacegallery.ml.onnx

import ai.recommend.spacegallery.R
import java.util.Locale

/**
 * Группа моделей — это одна функция приложения. Весь набор весит около 600 МБ, поэтому
 * пользователь выбирает, что ему нужно: обязательное ядро (поиск, лица, текст, деликатное)
 * и необязательные добавки.
 *
 * Ключи совпадают с полем `group` в манифесте (см. models/build_manifest.py).
 */
enum class ModelGroup(val key: String, val required: Boolean, val title: Int) {
    /** CLIP: поиск по смыслу и похожие фото — без этого нет главной функции. */
    SEARCH("search", required = true, title = R.string.models_group_search),

    /** NSFW-классификатор: скрытие деликатного. */
    SENSITIVE("sensitive", required = true, title = R.string.models_group_sensitive),

    /** Детектор лиц и быстрая модель векторов. */
    FACES("faces", required = true, title = R.string.models_group_faces),

    /** Распознавание текста и QR-кодов (кириллица и английский). */
    TEXT("text", required = true, title = R.string.models_group_text),

    /** Многоязычный текстовый энкодер CLIP: поиск запросами на русском. */
    RUSSIAN("russian", required = false, title = R.string.models_group_russian),

    /** ArcFace: точная модель лиц, включается в настройках. */
    FACES_HQ("faces_hq", required = false, title = R.string.models_group_faces_hq),

    /** Корейский распознаватель текста. */
    TEXT_KO("text_ko", required = false, title = R.string.models_group_text_ko);

    companion object {
        fun of(key: String): ModelGroup? = entries.firstOrNull { it.key == key }

        val required: Set<String> get() = entries.filter { it.required }.map { it.key }.toSet()

        /**
         * Что выбрано до первого захода в настройки: ядро плюс поиск на русском, если
         * телефон русскоязычный — иначе пользователю пришлось бы догадываться, почему
         * поиск не понимает его язык.
         */
        fun defaults(locale: Locale = Locale.getDefault()): Set<String> =
            if (locale.language in RUSSIAN_LANGUAGES) required + RUSSIAN.key else required

        private val RUSSIAN_LANGUAGES = setOf("ru", "uk", "be", "bg")
    }
}
