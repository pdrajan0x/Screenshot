# Dot Screenshots

Find any screenshot by what's in it. Dot Screenshots reads the text in your screenshots, describes
what's in their pictures and lists the objects it sees, entirely on your phone: no account, no
internet, nothing uploaded.

<p align="center">
  <img src="docs/screenshots/home.png" width="24%" alt="Home: screenshots by day with the floating search bar" />
  <img src="docs/screenshots/search.png" width="24%" alt="Search results" />
  <img src="docs/screenshots/viewer.png" width="24%" alt="Viewing a screenshot" />
  <img src="docs/screenshots/details-keywords.png" width="24%" alt="Details and keywords of a screenshot" />
</p>
<p align="center">
  <img src="docs/screenshots/edit.png" width="24%" alt="Editing a screenshot" />
  <img src="docs/screenshots/settings.png" width="24%" alt="Settings and processing status" />
</p>

## Features

- **Descriptions**: every screenshot gets one, like "a woman sitting on a couch wearing a black top and blue jeans, with a pair of black boots" (Florence-2).
- **Search by words or pictures**: "upi payment", "train ticket", "black boots". Matches in keywords, the app, your notes and the description come first; matches only in the screen's text are behind *Show more*. Every result contains your words.
- **Knows the app**: names the app from the words its own screen shows (WhatsApp, Instagram, Reddit, GitHub…).
- **Picture keywords**: things in photos (food, places, animals, documents) from MobileCLIP, only when it's sure.
- **Details**: description, date and time, size, resolution, folder, location (when recorded); keywords and the screen's text on request.
- **Edit**: crop, rotate, straighten, markup, adjust and filters; saved as a copy next to the original.
- **Collections, favourites, notes** (notes are searchable too) and quick actions for links, numbers and codes.
- **Easy on the battery**: new screenshots are read right away; older ones on battery above 20 % or while charging (you choose in Settings).

## Download

Get `DotScreenshots.apk` from the [latest release](https://github.com/pdrajan0x/Screenshot/releases/latest),
open it on your phone and install. Android 8.0 or newer, 64-bit ARM.

## Build the APK

You need JDK 21, the Android SDK (`ANDROID_HOME` set) and Python 3.12.

```bash
git clone https://github.com/pdrajan0x/Screenshot.git && cd Screenshot

# 1. Get the on-device models into model-out/ (once): MobileCLIP2-S2 exported to ONNX,
#    and Florence-2-base downloaded as ONNX
pip install -r tools/model/requirements.txt
python tools/model/export_mobileclip.py --out model-out
python tools/model/fetch_florence.py --out model-out

# 2. Build
./gradlew :app-screenshots:assembleRelease
```

The APK is in `app-screenshots/build/outputs/apk/release/`. Without your own signing key
(`DOT_SIGNING_STORE` and `DOT_SIGNING_PASSWORD`) it is signed with the debug key.
Every push to the default branch is also built by GitHub Actions and published as the latest release.

## How it works

| Part | What it does |
| --- | --- |
| Google ML Kit text recognition (bundled) | Reads the text on screen (Latin, optionally Devanagari) |
| Florence-2-base on ONNX Runtime | Describes each screenshot and finds the objects in it |
| MobileCLIP2-S2 on ONNX Runtime | Picture keywords, categories and similar screenshots |
| SQLite FTS4 | Fast, precise word search |
| Jetpack Compose | Nothing-inspired UI in black, white and red |

Code layout: `app-screenshots` (the app), `core/engine` (search, keywords, app names; plain Kotlin with tests),
`core/ml` (OCR, MobileCLIP and Florence-2), `core/media` (MediaStore, battery rules), `core/design` (theme and components),
`tools/model` (model export and download).

## Licenses

MobileCLIP2 is licensed under Apple's Machine Learning Research Model License (research, non-commercial use).
Florence-2 and ONNX Runtime: MIT. Fonts (Doto, Space Grotesk, Space Mono): SIL OFL 1.1. Coil, Telephoto, Haze, AndroidX: Apache 2.0.
