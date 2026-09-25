"""
Лаборатория лиц на ПК: та же цепочка, что в приложении (YuNet -> выравнивание -> ArcFace),
но на файлах из каталога пользователя. Нужна, чтобы проверять идеи без телефона.

Запуск: PYTHONPATH=pylibs python3 facelab.py <каталог> [сколько фото]
"""
import sys, os, math, random
import os
import numpy as np
from PIL import Image
import onnxruntime as ort

# Модели берём из отладочных ассетов проекта; каталог можно переопределить переменной
# окружения SPACE_GALLERY_MODELS.
MODELS = os.environ.get(
    "SPACE_GALLERY_MODELS",
    os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "app", "src", "debug", "assets", "models"),
)
INPUT = 640
STRIDES = (8, 16, 32)
MIN_SCORE = 0.6
MIN_FACE_FRACTION = 0.02
TEMPLATE = np.array([[38.2946, 51.6963], [73.5318, 51.5014], [56.0252, 71.7366],
                     [41.5493, 92.3655], [70.7299, 92.2041]], np.float32)


def sessions():
    opts = ort.SessionOptions()
    opts.intra_op_num_threads = os.cpu_count() or 4
    det = ort.InferenceSession(f"{MODELS}/face_detect.onnx", opts, providers=["CPUExecutionProvider"])
    emb = ort.InferenceSession(f"{MODELS}/face_embed_hq.onnx", opts, providers=["CPUExecutionProvider"])
    return det, emb


def letterbox_bgr(img):
    """Кадр вписывается в 640×640 (поля справа/снизу чёрные), порядок каналов BGR, 0..255."""
    scale = INPUT / max(img.size)
    w, h = int(img.width * scale), int(img.height * scale)
    small = img.resize((w, h), Image.BILINEAR)
    canvas = Image.new("RGB", (INPUT, INPUT), (0, 0, 0))
    canvas.paste(small, (0, 0))
    a = np.asarray(canvas, np.float32)
    return np.transpose(a[:, :, ::-1], (2, 0, 1))[None], scale


def detect(det, img, min_score=MIN_SCORE):
    blob, scale = letterbox_bgr(img)
    out = {o.name: v for o, v in zip(det.get_outputs(), det.run(None, {det.get_inputs()[0].name: blob}))}
    faces = []
    for stride in STRIDES:
        cls = out[f"cls_{stride}"].reshape(-1)
        obj = out[f"obj_{stride}"].reshape(-1)
        bbox = out[f"bbox_{stride}"].reshape(-1, 4)
        kps = out[f"kps_{stride}"].reshape(-1, 10)
        cols = INPUT // stride
        score = np.sqrt(np.clip(cls, 0, 1) * np.clip(obj, 0, 1))
        for i in np.where(score >= min_score)[0]:
            row, col = divmod(int(i), cols)
            cx = (col + bbox[i, 0]) * stride
            cy = (row + bbox[i, 1]) * stride
            w = math.exp(bbox[i, 2]) * stride
            h = math.exp(bbox[i, 3]) * stride
            box = np.array([(cx - w / 2) / scale, (cy - h / 2) / scale, (cx + w / 2) / scale, (cy + h / 2) / scale])
            pts = np.array([[(kps[i, k] + col) * stride / scale, (kps[i, k + 1] + row) * stride / scale]
                            for k in range(0, 10, 2)], np.float32)
            faces.append((float(score[i]), box, pts))
    return nms(faces)


def nms(faces, threshold=0.3):
    faces = sorted(faces, key=lambda f: -f[0])
    kept = []
    for f in faces:
        if all(iou(f[1], k[1]) < threshold for k in kept):
            kept.append(f)
    return kept


def iou(a, b):
    x1, y1 = max(a[0], b[0]), max(a[1], b[1])
    x2, y2 = min(a[2], b[2]), min(a[3], b[3])
    if x2 <= x1 or y2 <= y1:
        return 0.0
    inter = (x2 - x1) * (y2 - y1)
    return inter / ((a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter)


def similarity_transform(src, dst=TEMPLATE):
    """Преобразование подобия по 5 точкам (как FaceRecognizerSF::alignCrop)."""
    src_mean, dst_mean = src.mean(0), dst.mean(0)
    s, d = src - src_mean, dst - dst_mean
    var = (s ** 2).sum()
    cov = (d * s).sum() / var
    rot = ((d[:, 1] * s[:, 0] - d[:, 0] * s[:, 1]).sum()) / var
    a = np.array([[cov, rot], [-rot, cov]])
    # Матрица 2×3: поворот+масштаб и перенос.
    m = np.zeros((2, 3), np.float32)
    m[:, :2] = a.T
    m[:, 2] = dst_mean - a.T @ src_mean
    return m


def align(img, pts, size=112):
    m = similarity_transform(np.asarray(pts, np.float32))
    inv = np.linalg.inv(np.vstack([m, [0, 0, 1]]))[:2]
    return img.transform((size, size), Image.AFFINE, tuple(inv.reshape(-1)), Image.BILINEAR)


def embed(emb, face_img):
    a = np.asarray(face_img.convert("RGB"), np.float32)
    blob = np.transpose((a - 127.5) / 127.5, (2, 0, 1))[None]
    v = emb.run(None, {emb.get_inputs()[0].name: blob})[0][0]
    return v / np.linalg.norm(v)


def photos(root, limit, seed=7):
    files = []
    for dirpath, _, names in os.walk(root):
        for n in names:
            if n.lower().endswith((".jpg", ".jpeg", ".png")):
                files.append(os.path.join(dirpath, n))
    random.Random(seed).shuffle(files)
    return files[:limit]


if __name__ == "__main__":
    # Папка со снимками для прогона: аргумент командной строки или PHOTOS.
    root = sys.argv[1] if len(sys.argv) > 1 else os.environ.get("PHOTOS", ".")
    limit = int(sys.argv[2]) if len(sys.argv) > 2 else 50
    det, emb = sessions()
    found = 0
    for path in photos(root, limit):
        try:
            img = Image.open(path).convert("RGB")
        except Exception:
            continue
        faces = detect(det, img)
        min_side = max(img.size) * MIN_FACE_FRACTION
        faces = [f for f in faces if f[1][2] - f[1][0] >= min_side and f[1][3] - f[1][1] >= min_side]
        found += len(faces)
    print(f"фото {limit}, найдено лиц {found}")
