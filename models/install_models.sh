#!/usr/bin/env bash
# Копирует ONNX-модели из локального зеркала HF в debug-assets приложения под именами,
# которые ожидает ModelProvider (см. ml/onnx/ModelSpec.kt).
#
#   ./models/install_models.sh [SRC] [VARIANT]
#     SRC      — каталог зеркала (по умолчанию /mnt/mirror/ai/onnx)
#     VARIANT  — суффикс квантования: quantized (int8, по умолчанию), fp16, "" (fp32)
#
# Вместо assets модели можно положить на устройство в filesDir/models/ (не раздувает APK):
#   adb push <file> /data/local/tmp/ && adb shell run-as ai.recommend.spacegallery \
#     sh -c 'mkdir -p files/models && cp /data/local/tmp/<file> files/models/'
set -euo pipefail

SRC="${1:-/mnt/mirror/ai/onnx}"
VARIANT="${2-quantized}"
SUFFIX="${VARIANT:+_$VARIANT}"
DST="$(cd "$(dirname "$0")/.." && pwd)/app/src/debug/assets/models"

CLIP="$SRC/clip-vit-base-patch32"
MCLIP="$SRC/clip-ViT-B-32-multilingual-v1-ONNX"
NSFW="$SRC/vit-base-nsfw-detector-ONNX"
NSFW_CLIP="$SRC/clip-based-nsfw-detector-b32-ONNX"
YUNET="$SRC/face_detection_yunet"
SFACE="$SRC/face_recognition_sface"

# У многоязычного энкодера есть только fp32 и quantized — для прочих вариантов берём quantized.
MCLIP_MODEL="$MCLIP/onnx/model$SUFFIX.onnx"
[[ -f "$MCLIP_MODEL" ]] || MCLIP_MODEL="$MCLIP/onnx/model_quantized.onnx"

mkdir -p "$DST/clip_tokenizer" "$DST/mclip_tokenizer"
cp -v "$CLIP/onnx/vision_model$SUFFIX.onnx" "$DST/clip_image.onnx"
cp -v "$CLIP/onnx/text_model$SUFFIX.onnx"   "$DST/clip_text.onnx"
cp -v "$CLIP/vocab.json" "$CLIP/merges.txt" "$DST/clip_tokenizer/"
cp -v "$MCLIP_MODEL"                       "$DST/clip_text_multilingual.onnx"
cp -v "$MCLIP/vocab.txt"                    "$DST/mclip_tokenizer/"
cp -v "$NSFW/onnx/model$SUFFIX.onnx"        "$DST/nsfw.onnx"
cp -v "$NSFW_CLIP/onnx/model.onnx"          "$DST/nsfw_clip.onnx"   # 75 КБ, есть только fp32
cp -v "$YUNET/onnx/face_detection_yunet_2023mar.onnx" "$DST/face_detect.onnx"  # 230 КБ
cp -v "$SFACE/onnx/model.onnx"              "$DST/face_embed.onnx"  # 39 МБ fp32 (очищенный граф)

du -sh "$DST"
