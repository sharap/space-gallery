# Правила R8 для релиза. Всё, что вызывается не из Java-кода — через JNI, рефлексию или по
# имени класса, — R8 считает неиспользуемым и выбрасывает. Ниже только такие случаи.

# ONNX Runtime: нативная библиотека сама находит эти классы и поля по именам и вызывает их
# обратно (тензоры, коды ошибок, настройки сессии). Переименование ломает инференс во время
# работы, а не при сборке, поэтому оставляем пакет целиком.
-keep class ai.onnxruntime.** { *; }
-keepclassmembers class ai.onnxruntime.** { native <methods>; }
-dontwarn ai.onnxruntime.**

# WorkManager создаёт воркеры по имени класса, записанному в своей базе: имя должно пережить
# не только эту сборку, но и обновление приложения — иначе задачи, поставленные старой
# версией, не запустятся.
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Room обращается к сущностям и DAO из сгенерированного кода, но конструкторы и поля читает
# по именам колонок.
-keep class * extends androidx.room.RoomDatabase { *; }
-keepclassmembers class * {
    @androidx.room.* <methods>;
}

# kotlinx.serialization: сериализаторы генерируются компилятором и достаются рефлексией.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class ai.recommend.spacegallery.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Имена наших классов остаются в стектрейсах: без этого разбирать отчёты о падениях
# невозможно, а выигрыша в размере переименование почти не даёт.
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
