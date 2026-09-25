# Сборка релиза

```bash
./gradlew assembleRelease -PmodelsBaseUrl=https://huggingface.co/аккаунт/репозиторий/resolve/main/
```

APK появляются в `app/build/outputs/apk/release/`.

## Отдельный APK на каждую архитектуру

`libonnxruntime.so` весит 33–39 МБ на каждую архитектуру, поэтому релиз разделён: телефон
получает только свою. Замеры 25.09.2026:

| Файл | Размер | versionCode |
|---|---|---|
| `app-arm64-v8a-release.apk` | 90 МБ | 13 |
| `app-armeabi-v7a-release.apk` | 81 МБ | 11 |
| `app-x86_64-release.apk` | 96 МБ | 12 |
| `app-universal-release.apk` (по флагу) | 187 МБ | 1 |

Номер версии собирается как «общий × 10 + код архитектуры», и 64-битная версия получает
номер больше 32-битной: если устройству подходят обе, магазин ставит ту, что новее по номеру.
Одинаковые номера выкладывать рядом нельзя — магазин считает такие APK одной сборкой.

Общий APK со всеми архитектурами (раздать одним файлом, без магазина):
`./gradlew assembleRelease -PuniversalApk=true`.

Разделение включается только для релизных задач: в debug-сборке лежат ещё и модели, и
несколько APK по 750 МБ из неё не нужны. AGP не разрешает держать `splits` и `abiFilters`
одновременно, поэтому признак `abiSplit` в `app/build.gradle.kts` управляет обоими местами.

## Подпись

Ключ описывается в `keystore.properties` рядом с проектом — файл в `.gitignore`, в репозиторий
и в APK не попадает:

```properties
storeFile=/путь/к/keystore.jks
storePassword=…
keyAlias=…
keyPassword=…
```

Пока файла нет, релиз подписывается **отладочным** ключом (`~/.android/debug.keystore`) —
иначе APK не поставить на телефон; сборка пишет об этом в лог.

Ключ создаётся один раз:

```bash
keytool -genkeypair -v -keystore ~/space-gallery.jks -alias space-gallery \
  -keyalg RSA -keysize 2048 -validity 10000
```

**Его нельзя терять.** Android ставит обновление поверх установленного приложения только
если подпись совпадает: с другим ключом пользователям придётся удалять приложение вместе
со всей разметкой людей и индексом. Копию keystore и пароли стоит хранить отдельно от
рабочей машины.

## Модели

В релизный APK модели не входят — приложение качает их при первом запуске
(см. `models/README.md`). Без `-PmodelsBaseUrl` сборка проходит, но печатает предупреждение:
такой APK не сможет скачать модели никогда, и AI-функции в нём не включатся.

Чтобы не передавать адрес каждый раз, его можно положить в `gradle.properties` проекта:

```properties
modelsBaseUrl=https://huggingface.co/аккаунт/репозиторий/resolve/main/
```

## Выпуск на GitHub

1. Поднять версию в `app/build.gradle.kts` (`versionCode`, `versionName`) и описать
   изменения в `CHANGELOG.md`.
2. Собрать: `./gradlew clean assembleRelease -PuniversalApk=true`.
3. Проверить подпись — сертификат должен быть ваш, а не `CN=Android Debug`:
   `apksigner verify --print-certs app/build/outputs/apk/release/app-arm64-v8a-release.apk`
4. Поставить тег: `git tag -a v1.0 -m "1.0"` и `git push origin v1.0`.
5. Создать релиз на GitHub, приложить APK из `app/build/outputs/apk/release/` и вставить
   раздел из `CHANGELOG.md`.

Если менялись модели — сначала выложить их (`models/upload_hf.sh`) и пересобрать манифест,
иначе свежая сборка будет качать файлы, которых нет.

## Что ещё не сделано

- Минификация (R8) выключена: понадобятся keep-правила для ONNX Runtime, Room и
  kotlinx.serialization.
- App Bundle (`bundleRelease`) не настраивался — для магазина он удобнее разделения вручную,
  но требует подписи настоящим ключом.
