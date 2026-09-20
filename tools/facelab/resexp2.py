"""Три варианта вектора лица: превью 640; кроп из оригинала по точкам превью; кроп с
повторным поиском точек внутри кропа."""
import sys, time, pickle
import numpy as np
from PIL import Image
from facelab import sessions, detect, align, embed, photos, MIN_FACE_FRACTION, iou

root, limit, out = sys.argv[1], int(sys.argv[2]), sys.argv[3]
FACE_PX, MAX_SIDE, MIN_ORIGINAL, MARGIN = 224, 2560, 1600, 0.6

det, emb = sessions()
records = []
started = time.time()
for k, path in enumerate(photos(root, limit)):
    try:
        img = Image.open(path)
        if max(img.size) < MIN_ORIGINAL:
            continue
        img = img.convert("RGB")
    except Exception:
        continue
    preview_scale = 640 / max(img.size)
    preview = img.resize((max(1, int(img.width * preview_scale)), max(1, int(img.height * preview_scale))), Image.BILINEAR)
    faces = detect(det, img)
    min_side = max(img.size) * MIN_FACE_FRACTION
    faces = [f for f in faces if f[1][2] - f[1][0] >= min_side and f[1][3] - f[1][1] >= min_side]
    for score, box, pts in faces:
        side = max(box[2] - box[0], box[3] - box[1])
        # Кроп вокруг лица из оригинала, масштаб — чтобы лицо было ~FACE_PX.
        m = side * MARGIN
        left, top = max(0, box[0] - m), max(0, box[1] - m)
        right, bottom = min(img.width, box[2] + m), min(img.height, box[3] + m)
        crop = img.crop((int(left), int(top), int(right), int(bottom)))
        scale = min(1.0, FACE_PX / max(side, 1e-6))
        if scale < 1.0:
            crop = crop.resize((max(1, int(crop.width * scale)), max(1, int(crop.height * scale))), Image.BILINEAR)
        else:
            scale = 1.0
        pts_crop = (pts - np.array([left, top], np.float32)) * scale
        lo = embed(emb, align(preview, pts * preview_scale))
        hi = embed(emb, align(crop, pts_crop))
        # Повторный поиск точек внутри кропа: в превью лицо мелкое и точки неточные.
        redet = detect(det, crop, min_score=0.3)
        box_crop = (np.array([box[0] - left, box[1] - top, box[2] - left, box[3] - top]) * scale)
        best = max(redet, key=lambda f: iou(f[1], box_crop), default=None)
        hi2 = embed(emb, align(crop, best[2])) if best is not None and iou(best[1], box_crop) >= 0.3 else None
        records.append(dict(path=path, score=score, lo=lo, hi=hi, hi2=hi2,
                            preview_px=(box[2] - box[0]) * preview_scale, face_px=(box[2] - box[0]) * scale))
    if (k + 1) % 500 == 0:
        print(f"  {k+1}/{limit}, лиц {len(records)}, {time.time()-started:.0f} с", flush=True)
pickle.dump(records, open(out, "wb"))
print(f"лиц {len(records)}, без повторных точек {sum(1 for x in records if x['hi2'] is None)}, время {time.time()-started:.0f} с")
