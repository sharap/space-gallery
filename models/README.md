# ONNX-модели Space Gallery

Весь AI работает локально через [ONNX Runtime Mobile](https://onnxruntime.ai/docs/tutorials/mobile/).
Приложение запускается и без моделей — соответствующие функции просто отключаются
(дубликаты по dHash работают всегда, без нейросети).

## Где лежат модели

`ModelProvider` ищет файлы в порядке приоритета:

1. `filesDir/models/<file>` — скачанные/подложенные после установки (не раздувают APK);
2. `app/src/main/assets/models/<file>` — встроенные в APK (удобно для разработки).

| Файл                     | Назначение                              | Вход                         | Выход              |
|--------------------------|-----------------------------------------|------------------------------|--------------------|
| `clip_image.onnx`        | эмбеддинг изображения (поиск, похожие)  | `[1,3,224,224]` float32      | `[1,512]`          |
| `clip_text.onnx`         | эмбеддинг текстового запроса            | `input_ids [1,77]` int64     | `[1,512]`          |
| `clip_tokenizer/vocab.json`, `merges.txt` | BPE-словарь CLIP       | —                            | —                  |
| `nsfw.onnx`              | классификатор деликатного контента      | `[1,3,224,224]` float32      | `[1,2]` logits     |

Параметры препроцессинга — в `ml/onnx/ModelSpec.kt`. При смене модели обновите их и
увеличьте `MediaAnalyzer.PIPELINE_VERSION`, чтобы переиндексировать медиатеку.

## Экспорт (пример)

```bash
pip install "optimum[exporters]" onnxruntime transformers

# CLIP ViT-B/32: визуальная и текстовая части
python export_clip.py            # см. ниже

# NSFW-классификатор (ViT, labels = [normal, nsfw])
optimum-cli export onnx --model Falconsai/nsfw_image_detection --task image-classification nsfw_out/
cp nsfw_out/model.onnx ../app/src/main/assets/models/nsfw.onnx
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

out = "../app/src/main/assets/models"
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

- **Русские запросы.** Оригинальный CLIP понимает только английский. Варианты:
  мультиязычный текстовый энкодер (`sentence-transformers/clip-ViT-B-32-multilingual-v1`,
  совместим с визуальной частью CLIP ViT-B/32) или SigLIP 2 / multilingual MobileCLIP.
  Под новый токенизатор нужна своя реализация `ml/text/Tokenizer`.
- **Скорость/размер.** MobileCLIP-S0/S1 заметно быстрее ViT-B/32 на телефоне при сравнимом качестве.
- **Лицензии.** Проверьте лицензию каждой модели перед публикацией приложения.
