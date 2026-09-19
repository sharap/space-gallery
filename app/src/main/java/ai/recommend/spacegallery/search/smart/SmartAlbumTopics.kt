package ai.recommend.spacegallery.search.smart

/**
 * Словарь тем для названий умных альбомов (zero-shot через CLIP): название на экране
 * и англоязычное описание для текстового энкодера.
 * Метки проверены на реальной медиатеке (2026-09-19): совпадают с папками вроде Screenshots.
 */
data class Topic(val title: String, val prompt: String)

object SmartAlbumTopics {
    /** Шаблоны запроса: эмбеддинг темы — среднее по ним (prompt ensembling, как в статье CLIP). */
    val TEMPLATES = listOf("a photo of {}.", "a picture of {}.", "{}")

    val ALL = listOf(
        Topic("Закаты", "a sunset"),
        Topic("Пляж", "a beach"),
        Topic("Море", "the sea"),
        Topic("Горы", "mountains"),
        Topic("Лес", "a forest"),
        Topic("Снег и зима", "snow in winter"),
        Topic("Небо и облака", "clouds in the sky"),
        Topic("Вода", "a river or a lake"),
        Topic("Поля и природа", "a green field in nature"),
        Topic("Цветы", "flowers"),
        Topic("Растения", "plants"),
        Topic("Город", "a city street"),
        Topic("Ночной город", "a city at night"),
        Topic("Архитектура", "a beautiful building"),
        Topic("Дома", "a house"),
        Topic("Интерьер", "a room interior"),
        Topic("Еда", "food on a plate"),
        Topic("Кофе и напитки", "a cup of coffee"),
        Topic("Десерты", "a cake or dessert"),
        Topic("Кошки", "a cat"),
        Topic("Собаки", "a dog"),
        Topic("Животные", "an animal"),
        Topic("Птицы", "a bird"),
        Topic("Дети", "a child"),
        Topic("Селфи", "a selfie"),
        Topic("Портреты", "a portrait of a person"),
        Topic("Люди", "a group of people"),
        Topic("Праздники", "a party celebration"),
        Topic("Новый год", "a christmas tree"),
        Topic("Концерты", "a concert"),
        Topic("Спорт", "sports"),
        Topic("Машины", "a car"),
        Topic("Мотоциклы", "a motorcycle"),
        Topic("Транспорт", "a train or an airplane"),
        Topic("Документы", "a document with text"),
        Topic("Чеки", "a receipt"),
        Topic("Скриншоты", "a screenshot of a phone screen"),
        Topic("Переписки", "a screenshot of a chat conversation"),
        Topic("Мемы", "a meme with text"),
        Topic("Рисунки", "a drawing or a sketch"),
        Topic("Аниме", "anime art"),
        Topic("Игры", "a video game screenshot"),
        Topic("Компьютер", "a computer on a desk"),
        Topic("Код", "source code on a computer screen"),
        Topic("Техника", "electronics and gadgets"),
        Topic("Одежда", "clothes"),
        Topic("Обувь", "shoes"),
        Topic("Книги", "books"),
        Topic("Музыка", "a musical instrument"),
        Topic("Фейерверки", "fireworks"),
        Topic("Луна", "the moon at night"),
        Topic("Карты", "a map"),
        Topic("Схемы", "a diagram or a chart"),
        Topic("QR-коды", "a qr code"),
        Topic("Искусство", "a painting in a museum"),
        Topic("Путешествия", "a travel landmark"),
        Topic("Дорога", "a road"),
    )
}
