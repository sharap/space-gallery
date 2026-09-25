#!/usr/bin/env bash
# Выкладка моделей на Hugging Face — оттуда их качает релизная сборка.
#
# Что нужно один раз:
#   sudo apt install git-lfs && git lfs install     # без LFS большие файлы не примут
#   токен с правом write: https://huggingface.co/settings/tokens
#
# Запуск:
#   HF_TOKEN=hf_xxx ./models/upload_hf.sh <аккаунт>/<репозиторий>
#
# Скрипт создаёт репозиторий (если его нет), заливает всё из app/src/debug/assets/models
# теми же относительными путями, что в манифесте, и печатает адрес для сборки.
set -euo pipefail

REPO=${1:?Укажите репозиторий: ./models/upload_hf.sh имя/репозиторий}
: "${HF_TOKEN:?Нужен токен: HF_TOKEN=hf_xxx ./models/upload_hf.sh $REPO}"

ROOT=$(cd "$(dirname "$0")/.." && pwd)
SRC="$ROOT/app/src/debug/assets/models"
MANIFEST="$ROOT/app/src/main/assets/models_manifest.json"
[ -d "$SRC" ] || { echo "Нет моделей в $SRC — сначала ./models/install_models.sh"; exit 1; }
[ -f "$MANIFEST" ] || { echo "Нет манифеста — сначала python3 models/build_manifest.py"; exit 1; }

command -v git-lfs >/dev/null || { echo "Нет git-lfs: sudo apt install git-lfs && git lfs install"; exit 1; }

# Заливаем ровно то, что перечислено в манифесте: лишние файлы из зеркала на сервер не нужны.
FILES=$(python3 - "$MANIFEST" <<'PY'
import json, sys
print("\n".join(f["path"] for f in json.load(open(sys.argv[1]))["files"]))
PY
)

echo "== Создаю репозиторий $REPO (если его ещё нет)"
curl -sS -X POST https://huggingface.co/api/repos/create \
  -H "Authorization: Bearer $HF_TOKEN" -H "Content-Type: application/json" \
  -d "{\"name\": \"${REPO#*/}\", \"organization\": \"${REPO%/*}\", \"type\": \"model\", \"private\": false}" \
  | head -c 300; echo

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
echo "== Клонирую во временный каталог"
git -c credential.helper= clone --depth 1 "https://user:$HF_TOKEN@huggingface.co/$REPO" "$WORK/repo" 2>&1 | tail -2

cd "$WORK/repo"
git lfs install --local
# Модели — бинарники; словари и словари токенизатора мелкие, но пусть тоже идут через LFS,
# чтобы не зависеть от лимита на размер обычного файла.
cat > .gitattributes <<'ATTR'
*.onnx filter=lfs diff=lfs merge=lfs -text
*.json filter=lfs diff=lfs merge=lfs -text
*.txt filter=lfs diff=lfs merge=lfs -text
ATTR

echo "== Копирую файлы из манифеста"
while IFS= read -r rel; do
  [ -n "$rel" ] || continue
  mkdir -p "$(dirname "$rel")"
  cp "$SRC/$rel" "$rel"
done <<< "$FILES"

git add -A
git -c user.email=models@localhost -c user.name="model upload" commit -q -m "Модели для Space Gallery" || echo "(нечего коммитить — файлы уже такие же)"
echo "== Отправляю (это сотни мегабайт, займёт время)"
git push origin HEAD 2>&1 | tail -3

echo
echo "Готово. Собирать релиз так:"
echo "  ./gradlew assembleRelease -PmodelsBaseUrl=https://huggingface.co/$REPO/resolve/main/"
