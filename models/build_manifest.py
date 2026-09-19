#!/usr/bin/env python3
"""
Манифест моделей для загрузки в релизе: app/src/main/assets/models_manifest.json.

Берёт файлы, установленные в debug-assets (./models/install_models.sh), считает размер и SHA-256.
Файлы выкладываются на сервер под теми же относительными путями; адрес сервера — параметр
сборки modelsBaseUrl (см. app/build.gradle.kts). После смены моделей — перезапустить скрипт.

Запуск: python3 models/build_manifest.py
"""
import hashlib
import json
import os

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
SRC = os.path.join(ROOT, "app", "src", "debug", "assets", "models")
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "models_manifest.json")

# Группа -> файлы: что включает каждая AI-функция (подписи групп — в приложении).
GROUPS = {
    "search": ["clip_image.onnx", "clip_text.onnx", "clip_tokenizer/vocab.json", "clip_tokenizer/merges.txt"],
    "russian": ["clip_text_multilingual.onnx", "mclip_tokenizer/vocab.txt"],
    "sensitive": ["nsfw_clip.onnx", "nsfw.onnx"],
    "faces": ["face_detect.onnx", "face_embed.onnx"],
}


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main():
    files = []
    for group, paths in GROUPS.items():
        for rel in paths:
            full = os.path.join(SRC, rel)
            files.append({"path": rel, "group": group, "size": os.path.getsize(full), "sha256": sha256(full)})
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump({"version": 1, "files": files}, f, ensure_ascii=False, indent=2)
        f.write("\n")
    total = sum(x["size"] for x in files)
    print(f"{len(files)} файлов, {total / 1_048_576:.0f} МБ -> {os.path.relpath(OUT, ROOT)}")


if __name__ == "__main__":
    main()
