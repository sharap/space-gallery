# Space Gallery

**English** · [Русский](README.ru.md)

An Android gallery that understands what is in your photos and sends none of them anywhere.
Semantic search, people, text on images, places, duplicate cleanup — every neural network
runs on the phone itself.

## What it does

**Semantic search.** "cat on a couch", "sunset by the sea", "screenshot of a chat" — CLIP
matches your words against the content of the photos. Queries work in Russian as well as
English. Next to it: "similar photos" for any shot, and smart albums that build themselves
out of whatever you take pictures of.

**People.** Faces are detected and grouped into people; you can name someone, merge groups,
tag a photo by hand and undo a mistake. A similarity slider decides how eagerly groups merge.

**Text and codes.** Text is recognised directly on the photo — copy it, or search your
gallery by it. Cyrillic, English and Korean. QR codes and barcodes are found on any photo,
even one with no text at all.

**Places.** EXIF coordinates turn into city names offline, from a bundled GeoNames database —
no map service is contacted.

**Cleanup.** Duplicates, bursts of near-identical shots, blurry photos, old screenshots and
large videos — found and deleted in batches.

**Sensitive content.** Photos you would rather not have on screen in front of a stranger are
detected on device and kept out of the main timeline.

## Privacy

Photos never leave the phone. The app needs no internet to work — the network is used exactly
once, to download the neural network files, and for nothing else. No analytics, no accounts,
no cloud.

## Install

Download an APK from [releases](../../releases) — the one matching your phone's processor:

| File | For |
|---|---|
| `app-arm64-v8a-release.apk` | almost every modern phone |
| `app-armeabi-v7a-release.apk` | older 32-bit devices |
| `app-universal-release.apk` | if you are not sure which to take |

Android 9 or newer. On first launch the app offers to download the models: about 266 MB are
required, plus optional Russian-language search (135 MB), accurate face recognition (174 MB)
and Korean text (13 MB). Downloads are Wi-Fi only by default.

The models live on [Hugging Face](https://huggingface.co/sharapsoftware/space-gallery-models).
Until they are downloaded the app works as a plain gallery and analyses nothing.

## How it works

Kotlin, Jetpack Compose, Room, WorkManager. Inference runs on ONNX Runtime Mobile:

| Task | Model |
|---|---|
| Search and similar photos | CLIP ViT-B/32 (int8), multilingual text encoder |
| Sensitive content | a light classifier on top of CLIP, ViT NSFW on borderline frames |
| Faces | YuNet (detection) + MobileFaceNet or ArcFace r50 (embeddings) |
| Text | PaddleOCR: PP-OCRv3 detector, PP-OCRv5 recognisers |
| Codes | ZXing |

Indexing runs in the background at two paces: full speed while the phone is charging and
idle, quiet the rest of the time, with pauses so the device stays cool. Roughly 140 ms per
photo on a Snapdragon 8s Gen 3, about 380 ms on a Snapdragon 680.

Measurements and details: [docs/performance.md](docs/performance.md) (in Russian).
Release builds: [docs/release.md](docs/release.md). Models: [models/README.md](models/README.md).

## Building

```bash
git clone <this repository>
cd SpaceGallery
./gradlew assembleDebug        # models for development: ./models/install_models.sh
```

A debug build reads models from `app/src/debug/assets/models/`; a release build downloads them
from the address set in `gradle.properties` (`modelsBaseUrl`).

Note that the source comments and the documentation under `docs/` are written in Russian.

## License

[GNU GPL v3](LICENSE) — use it, change it, share it, but derivative works have to stay open
as well.
