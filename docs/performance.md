# Производительность индексации

Замеры от 2026-09-19. Устройство: Snapdragon 8s Gen 3 (SM8635: 1×X4 + 4×A720 + 3×A520),
Android 16, ONNX Runtime 1.30 (CPU EP), медиатека ~6300 файлов.

## Итог

| Этап оптимизации | Время на файл (среднее) |
|---|---|
| Исходно: CLIP + ViT NSFW на каждом кадре | ~800 мс |
| Гибридный NSFW: LAION-префильтр поверх CLIP, ViT только на ~10% кадров | ~195–246 мс |
| + конвейер «подготовка ‖ инференс» | **~140 мс** (55–90 мс в окнах без ViT) |

Медиатека ~6000 файлов индексируется примерно за 15 минут (было ~1,5 часа).

## Разбивка по этапам (текущая версия, окно из 64 файлов)

| Этап | Среднее | Примечание |
|---|---|---|
| `clip.run` | 44–50 мс | CLIP ViT-B/32 int8 |
| `nsfw.run` | 500–700 мс | ViT NSFW 384×384 int8, только ~10% кадров → 50–70 мс на файл в среднем |
| `nsfw.clip` | 0,3 мс | LAION MLP поверх CLIP-эмбеддинга |
| `prepare.total` | 32–50 мс | декодирование превью + dHash + тензор CLIP; **идёт параллельно** с инференсом |
| БД (чтение страницы, upsert) | <1 мс на файл | |

Как снять: `adb logcat -s IndexPerf` — воркер печатает сводку каждые 64 файла (`perf/PerfStats.kt`).

## Микробенчмарк ONNX-моделей

Приложение на экране (cpuset `top-app`), телефон остывший (thermal headroom 0,40–0,48),
медиана на кадр.

**CLIP ViT-B/32 (224×224)**

| Вариант | Результат |
|---|---|
| int8, CPU, 2 / 3 / 4 / 5 / 6 потоков | 32,5 / 29,2 / 30,4 / 34,7 / 28,4 мс |
| int8, XNNPACK, 4 потока | 49,8 мс |
| fp16 / q4, CPU, 4 потока | 86,7 / 92,9 мс |
| int8, батч 4 / батч 8 | **21,3** / 32,4 мс на кадр |

**ViT NSFW (384×384)**

| Вариант | Результат |
|---|---|
| int8, CPU, 3 / 4 / 5 / 6 потоков | ~460 / ~410–460 / ~290–550 / ~300–420 мс (шум DVFS больше разницы) |
| q4 / fp16 / fp32, 4 потока | 973 / 1239 / 1251 мс |

Выводы: int8 на CPU — лучший вариант для обеих моделей; XNNPACK, fp16 и q4 медленнее;
число потоков 3–6 не влияет заметно (оставлено 4). Батч 4 для CLIP даёт −30% — не внедрено.

## Особенности замеров на устройстве

- **Нагрев.** После нескольких минут непрерывной нагрузки ядра доходят до 90–95 °C, скорость
  падает в разы. Бенчмарк ждёт остывания (`PowerManager.getThermalHeadroom`) перед каждой
  конфигурацией. `getThermalHeadroom` возвращает NaN при вызове чаще раза в секунду.
- **cpuset.** Процесс без экрана и без foreground service попадает в cpuset `background` —
  только ядра 0–2 (A520): модели в ~4 раза медленнее. Foreground service → `foreground` (все ядра),
  приложение на экране → `top-app` (все ядра + буст планировщика; инференс ещё в ~1,5 раза быстрее).
  Проверка: `adb shell cat /proc/$(adb shell pidof ai.recommend.spacegallery)/cpuset`.
- **WorkManager хранит задачи** между force-stop и переустановкой: забытые прогоны бенчмарка
  выполняются параллельно и искажают результаты.
- **`adb logcat -c`** на этом устройстве не очищает буфер — ориентироваться на уникальные маркеры.

## Бенчмарк (только debug-сборка)

Код: `app/src/debug/java/ai/recommend/spacegallery/bench/`. Модели кладутся во внутреннее
хранилище приложения (файлы, залитые adb в `Android/data`, приложению не читаются):

```bash
adb exec-in run-as ai.recommend.spacegallery sh -c 'mkdir -p files/bench && cat > files/bench/clip_int8_224.onnx' < vision_model_quantized.onnx
# суффикс _NNN в имени задаёт размер входной картинки (по умолчанию 224)

adb shell am broadcast -n ai.recommend.spacegallery/.bench.BenchmarkReceiver \
  --es run r1 --es models "clip_int8_224.onnx" --es threads "2,4" --es eps "cpu,xnnpack" \
  --es batches "1,4" --ei iters 30
adb logcat -s OrtBench            # прогон заканчивается строкой "=== done r1"

# отменить все прогоны / переиндексировать всю медиатеку
adb shell am broadcast -n ai.recommend.spacegallery/.bench.BenchmarkReceiver --es action cancel
adb shell am broadcast -n ai.recommend.spacegallery/.bench.BenchmarkReceiver --es action reindex
```

Приложение на время замеров должно быть на экране (`adb shell svc power stayon usb`),
иначе результаты отражают фоновые ядра.

## Дальнейшие возможности

1. ViT NSFW — основная оставшаяся статья (~50–70 мс на файл): NPU Hexagon через
   `onnxruntime-android-qnn` (нужна статически квантованная QDQ-модель).
2. Батч 4 для CLIP: около −9 мс на файл.
3. Паузы по thermal headroom при долгой индексации — стабильная скорость и холодный телефон.
