"""Векторы быстрой модели (MobileFaceNet) для лиц, размеченных пользователем.

Берём те же снимки (по имени файла), ту же цепочку: кроп из оригинала -> поиск ключевых точек
внутри кропа -> вектор. Заодно считаем и точной моделью, чтобы сверить лабораторию с телефоном.
"""
import os, pickle, sys, time
import numpy as np
from PIL import Image
import onnxruntime as ort
from analyze import load
from facelab import detect, align, embed, iou, MODELS

S = os.path.dirname(os.path.abspath(__file__))
ROOT = os.environ.get("PHOTOS", ".")  # папка со снимками для прогона
FACE_PX, MARGIN = 224, 0.6

ids, media, person, locked, score, box, emb = load(f"{S}/faces.bin")
names = {}
for line in open(f"{S}/media.tsv"):
    parts = line.rstrip("\n").split("\t")
    if len(parts) >= 2:
        names[int(parts[0])] = parts[1]

index = {}
for dirpath, _, files in os.walk(ROOT):
    for f in files:
        index.setdefault(f, os.path.join(dirpath, f))

opts = ort.SessionOptions()
opts.intra_op_num_threads = os.cpu_count() or 4
det = ort.InferenceSession(f"{MODELS}/face_detect.onnx", opts, providers=["CPUExecutionProvider"])
fast = ort.InferenceSession(f"{MODELS}/face_embed.onnx", opts, providers=["CPUExecutionProvider"])
slow = ort.InferenceSession(f"{MODELS}/face_embed_hq.onnx", opts, providers=["CPUExecutionProvider"])

want = np.where(locked > 0)[0]
print(f"подтверждённых лиц {len(want)}, снимков в индексе {len(index)}")
out, missing, nocrop = [], 0, 0
started = time.time()
for k, i in enumerate(want):
    name = names.get(int(media[i]))
    path = index.get(name) if name else None
    if path is None:
        missing += 1
        continue
    try:
        img = Image.open(path).convert("RGB")
    except Exception:
        missing += 1
        continue
    b = box[i] * max(img.size)  # доли кадра -> пиксели (длинная сторона)
    bx = np.array([box[i][0] * img.width, box[i][1] * img.height, box[i][2] * img.width, box[i][3] * img.height])
    side = max(bx[2] - bx[0], bx[3] - bx[1])
    m = side * MARGIN
    left, top = max(0, bx[0] - m), max(0, bx[1] - m)
    right, bottom = min(img.width, bx[2] + m), min(img.height, bx[3] + m)
    crop = img.crop((int(left), int(top), int(right), int(bottom)))
    scale = min(1.0, FACE_PX / max(side, 1e-6))
    if scale < 1.0:
        crop = crop.resize((max(1, int(crop.width * scale)), max(1, int(crop.height * scale))), Image.BILINEAR)
    else:
        scale = 1.0
    box_crop = (np.array([bx[0] - left, bx[1] - top, bx[2] - left, bx[3] - top]) * scale)
    found = detect(det, crop, min_score=0.3)
    best = max(found, key=lambda f: iou(f[1], box_crop), default=None)
    if best is None or iou(best[1], box_crop) < 0.3:
        nocrop += 1
        continue
    face = align(crop, best[2])
    out.append(dict(face=int(i), person=int(locked[i]), fast=embed(fast, face), slow=embed(slow, face)))
    if (k + 1) % 500 == 0:
        print(f"  {k+1}/{len(want)}, готово {len(out)}, {time.time()-started:.0f} с", flush=True)
pickle.dump(out, open(f"{S}/fastcal.pkl", "wb"))
print(f"посчитано {len(out)}; файл не найден {missing}, лицо в кропе не найдено {nocrop}; {time.time()-started:.0f} с")
