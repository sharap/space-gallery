# ONNX-модели Space Gallery

Весь AI работает локально через [ONNX Runtime Mobile](https://onnxruntime.ai/docs/tutorials/mobile/).

> **Релиз:** модели не входят в APK — приложение скачивает их отдельно в `filesDir/models/`.
> **Разработка:** модели временно лежат в `app/src/debug/assets/models/` и попадают только в debug-APK.
Приложение запускается и без моделей — соответствующие функции просто отключаются
(дубликаты по dHash работают всегда, без нейросети).

## Загрузка моделей в релизе

1. `./models/install_models.sh` — модели из зеркала в debug-assets;
2. `python3 models/build_manifest.py` — `app/src/main/assets/models_manifest.json` (пути, размеры, SHA-256);
3. выложить файлы на Hugging Face: `HF_TOKEN=hf_xxx ./models/upload_hf.sh аккаунт/репозиторий`
   (нужен `git-lfs`, скрипт сам создаёт репозиторий и заливает то, что перечислено в манифесте);
4. собрать релиз с адресом, который напечатает скрипт:
   `./gradlew assembleRelease -PmodelsBaseUrl=https://huggingface.co/аккаунт/репозиторий/resolve/main/`.

Приложение (экран «Настройки → AI-модели», плашка в ленте) качает недостающие файлы через
WorkManager: докачка после обрыва (HTTP Range), проверка SHA-256, «только по Wi-Fi» по умолчанию.
Скачанный файл помечается `<файл>.sha256`; если хэш в манифесте поменялся — файл скачается заново.

Адреса `…/resolve/main/…` у Hugging Face отвечают перенаправлением на их CDN. `HttpURLConnection`
идёт по нему сам и переносит заголовок `Range`, так что докачка работает — но при первой
выкладке это стоит проверить на устройстве.

### Группы: что качать, а что нет

Весь набор — около 600 МБ, поэтому манифест разбит на группы функций (`models/build_manifest.py`),
а в приложении они описаны в `ModelGroup`. Обязательные — поиск, деликатное, лица, текст
(~266 МБ); по выбору — поиск на русском (135 МБ), точные лица ArcFace (174 МБ) и корейский
текст (13 МБ). На русскоязычном телефоне «поиск на русском» выбран заранее.

Необязательная группа скачивается и сама, когда пользователь включает её функцию в настройках
(корейский язык, точная модель лиц) — см. `SettingsViewModel.fetchIfMissing`.

Проверка на устройстве (debug): локальный сервер с поддержкой Range + `adb reverse tcp:8765 tcp:8765`,
сборка с `-PmodelsBaseUrl=http://127.0.0.1:8765/`, затем
`adb shell am broadcast -n ai.recommend.spacegallery/.bench.BenchmarkReceiver --es action modeldl`
(`--es groups "text_ko"` — только одна группа, 13 МБ вместо 600; `modeldl-cancel`,
`modeldl-clear` — прервать и удалить скачанное).

Проверено 25.09.2026: группа качается отдельно от остальных, обрыв продолжается запросом
`Range: bytes=…` (ответ 206), хэш сверяется до переименования `.part` в рабочий файл.

## Быстрая установка из локального зеркала

```bash
./models/install_models.sh                    # /mnt/mirror/ai/onnx, int8 (~363 МБ)
./models/install_models.sh /path/to/mirror fp16
```

| Файл в assets     | Источник в зеркале                                                  |
|-------------------|---------------------------------------------------------------------|
| `clip_image.onnx` | `clip-vit-base-patch32/onnx/vision_model_quantized.onnx` (89 МБ)    |
| `clip_text.onnx`  | `clip-vit-base-patch32/onnx/text_model_quantized.onnx` (64 МБ)      |
| `clip_tokenizer/` | `clip-vit-base-patch32/{vocab.json,merges.txt}`                     |
| `clip_text_multilingual.onnx` | `clip-ViT-B-32-multilingual-v1-ONNX/onnx/model_quantized.onnx` (136 МБ) |
| `mclip_tokenizer/vocab.txt`   | `clip-ViT-B-32-multilingual-v1-ONNX/vocab.txt`          |
| `nsfw.onnx`       | `vit-base-nsfw-detector-ONNX/onnx/model_quantized.onnx` (88 МБ, вход 384×384) |
| `nsfw_clip.onnx`  | `clip-based-nsfw-detector-b32-ONNX/onnx/model.onnx` (75 КБ, вход — CLIP-эмбеддинг) |
| `face_detect.onnx` | `face_detection_yunet/onnx/face_detection_yunet_2023mar.onnx` (230 КБ, MIT) |
| `face_embed.onnx`  | `insightface-buffalo_l/w600k_r50.onnx` (174 МБ, MIT) |

**Русский и другие языки.** `clip_text_multilingual.onnx` — экспорт
`sentence-transformers/clip-ViT-B-32-multilingual-v1` (скрипт `export.py` лежит рядом с моделью
в зеркале). Он обучен в пространство визуальной части CLIP ViT-B/32, поэтому работает с тем же
индексом изображений без переиндексации. `TextEmbedder` выбирает энкодер по запросу:
латиница → `clip_text.onnx`, иначе → многоязычный; при отсутствии одной модели берёт другую.

Альтернатива на будущее — `siglip2-base-patch16-512-ONNX` (лучше качество, но токенизатор
Gemma 256k, text int8 ≈ 283 МБ, вход 512×512 и полная переиндексация).

**Распознавание лиц.** `face_embed.onnx` — ArcFace ResNet50 из пакета insightface buffalo_l
(обучен на WebFace600K, 512 чисел, вход 112×112 RGB, (x − 127.5) / 127.5). Заменил SFace
(128 чисел) после замера на реальной медиатеке (448 лиц, 2026-09-20): при пороге, отсекающем
99% пар «разные люди», теряется 1% пар «тот же человек» вместо 7%. MobileFaceNet из buffalo_s
(13,6 МБ) дал лишь 5% — разница с SFace невелика, поэтому взят r50.
Скачать: `https://huggingface.co/public-data/insightface/resolve/main/models/buffalo_l/w600k_r50.onnx`.

**Деликатный контент — гибрид.** `nsfw_clip.onnx` (LAION CLIP-based-NSFW-Detector, MLP поверх
CLIP-эмбеддинга, ~0 мс) работает как префильтр; тяжёлый ViT `nsfw.onnx` (~700 мс на кадр)
запускается только когда префильтр ≥ 1e-4 — примерно на 13% кадров. Порог и замеры —
`ModelSpecs.NSFW_CLIP_PREFILTER`.

## Где лежат модели

`ModelProvider` ищет файлы в порядке приоритета:

1. `filesDir/models/<file>` — скачанные/подложенные после установки (не раздувают APK);
2. `assets/models/<file>` — только debug-сборка (`app/src/debug/assets/models/`).

| Файл                     | Назначение                              | Вход                         | Выход              |
|--------------------------|-----------------------------------------|------------------------------|--------------------|
| `clip_image.onnx`        | эмбеддинг изображения (поиск, похожие)  | `[1,3,224,224]` float32      | `[1,512]`          |
| `clip_text.onnx`         | эмбеддинг запроса (английский)          | `input_ids [1,77]` int64     | `[1,512]`          |
| `clip_text_multilingual.onnx` | эмбеддинг запроса (50+ языков)     | `input_ids`, `attention_mask` `[1,seq]` int64 | `[1,512]` |
| `mclip_tokenizer/vocab.txt` | WordPiece-словарь (cased)            | —                            | —                  |
| `clip_tokenizer/vocab.json`, `merges.txt` | BPE-словарь CLIP       | —                            | —                  |
| `nsfw.onnx`              | классификатор деликатного контента      | `[1,3,384,384]` float32      | `[1,2]` logits     |

Параметры препроцессинга — в `ml/onnx/ModelSpec.kt`. При смене модели обновите их и
увеличьте `MediaAnalyzer.PIPELINE_VERSION`, чтобы переиндексировать медиатеку.

## Экспорт (пример)

```bash
pip install "optimum[exporters]" onnxruntime transformers

# CLIP ViT-B/32: визуальная и текстовая части
python export_clip.py            # см. ниже

# NSFW-классификатор (ViT, labels = [sfw, nsfw])
optimum-cli export onnx --model AdamCodd/vit-base-nsfw-detector --task image-classification nsfw_out/
cp nsfw_out/model.onnx ../app/src/debug/assets/models/nsfw.onnx
```

`export_clip.py`:

```python
import torch
from transformers import CLIPModel, CLIPTokenizer

name = "openai/clip-vit-base-patch32"
model = CLIPModel.from_pretrained(name).eval()

class Vision(torch.nn.Module):
    def __init__(s, m): super().__init__(); s.m = m
    def forward(s, pixel_values): return s.m.get_image_features(pixel_values=pixel_values)

class Text(torch.nn.Module):
    def __init__(s, m): super().__init__(); s.m = m
    def forward(s, input_ids): return s.m.get_text_features(input_ids=input_ids)

out = "../app/src/debug/assets/models"
torch.onnx.export(Vision(model), torch.randn(1, 3, 224, 224), f"{out}/clip_image.onnx",
                  input_names=["pixel_values"], output_names=["image_embeds"], opset_version=17)
torch.onnx.export(Text(model), torch.zeros(1, 77, dtype=torch.long), f"{out}/clip_text.onnx",
                  input_names=["input_ids"], output_names=["text_embeds"], opset_version=17)
CLIPTokenizer.from_pretrained(name).save_pretrained(f"{out}/clip_tokenizer")
```

Затем имеет смысл квантовать (в ~4 раза меньше, быстрее на CPU):

```python
from onnxruntime.quantization import quantize_dynamic, QuantType
quantize_dynamic("clip_image.onnx", "clip_image.int8.onnx", weight_type=QuantType.QUInt8)
```

## Выбор моделей — на что обратить внимание

- **Скорость/размер.** MobileCLIP-S0/S1 заметно быстрее ViT-B/32 на телефоне при сравнимом качестве.
- **Лицензии.** Проверьте лицензию каждой модели перед публикацией приложения.
