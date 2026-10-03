# On-device screenshot search for budget Android phones — research notes

Goal: a Pixel Screenshots–style app that runs fully on-device, stays light, barely
touches the battery, and works on ₹12,000-class Android phones.

Researched October 2026. Figures marked **(est.)** are my estimates and have not
been measured. Check them on a real budget phone before relying on them.

---

## 1. Verdict

| Question | Answer |
|---|---|
| Will it work for our use cases? | **Yes** for object search (car, dog, cat, mountain, phone), movie-name search, a broad "movie" search, and "related screenshots". See §6. |
| Will it be light? | **Yes.** About 15 MB without the image model. About 50–80 MB installed with it (§4). |
| Will it drain the battery? | **No.** About 1–3 J per screenshot (est.). 30 screenshots a day ≈ 0.1–0.2 % of a 5,000 mAh battery. Big backlogs are processed only while charging (§5). |
| As good as Pixel Screenshots? | Close for search. Weaker for summaries and loose questions (no LLM on budget phones). |

---

## 2. Target hardware (₹12k phones, mid-2026)

Typical models: Moto G45 5G (Snapdragon 6s Gen 3, 4 GB), Redmi 14C 5G (Snapdragon 4
Gen 2, 6 GB), Infinix Hot 50 5G / Realme C63 5G (Dimensity 6300, 6–8 GB), Ai+ Nova
(Unisoc T8200, 6 GB), Itel Zeno 200 (Unisoc T7250). All have 5,000 mAh batteries.

What we design for:
- **4 GB RAM.**
- **CPU-only inference** (2 big cores + 6 small cores). No usable NPU or GPU delegate.
- **Android 14/15** in the field, with **minSdk 26** (Android 8) so older phones still work.
- **Aggressive OEM battery managers.** Xiaomi/HyperOS, Realme/ColorOS and Vivo kill background work.

Gemini Nano (AICore / ML Kit GenAI) is **not available** on any of these phones.
It is limited to flagships such as the Pixel 9/10 and Galaxy S25. Gemma 3n E2B needs
about 20 s per image description on a Galaxy S24 FE. A budget phone would be several
times slower. Neither can be the main engine.

---

## 3. Architecture

```
New screenshot appears in MediaStore (Pictures/Screenshots or DCIM/Screenshots)
        │
        ▼
Index worker (WorkManager)
  1. Decode a downscaled bitmap (never the full-res image in memory longer than needed)
  2. OCR ─────────────── ML Kit Text Recognition v2 (Latin + Devanagari)
  3. Image embedding ─── TinyCLIP image encoder (int8) → 512-d vector
  4. Auto-tags ───────── compare the vector with ~200 pre-computed tag-text vectors
                         ("car", "dog", "movie scene", "receipt", "chat", …)
  5. Source app ──────── parse it from the file name where the OEM includes it
  6. Entities ────────── regex: dates, phone numbers, URLs, UPI IDs, amounts
        │
        ▼
Room database
  - screenshots(id, uri, taken_at, app, ocr_text, tags, entities)
  - screenshots_fts  (@Fts4 virtual table over ocr_text + tags + app)
  - embeddings(id, vector as float16 BLOB)
        │
        ▼
Search (query typed by user)
  a. FTS4 keyword match over text/tags/app          → ranked list A
  b. TinyCLIP text encoder → cosine vs all vectors  → ranked list B
     (brute force: 10k × 512 dot products ≈ 5M MACs → a few ms)
  c. Merge A + B with reciprocal-rank fusion, drop CLIP hits below a calibrated threshold
  d. "More like this": cosine between image vectors (no text needed)
```

Optional layer: on phones where `ML Kit GenAI Image Description` reports the feature
as available (Gemini Nano), store its one-line description as extra searchable text.
This brings flagship results close to Pixel Screenshots at zero extra cost on
budget phones.

### Stack
- Kotlin and Jetpack Compose (UI), Room with `@Fts4` (search), WorkManager (background), Coil (thumbnails).
- ML Kit Text Recognition v2, **unbundled** through Google Play Services. It adds about 260 KB per script instead of about 4 MB per script per ABI when bundled.
- TinyCLIP exported to **LiteRT (TFLite)**, which has a small runtime. ONNX Runtime is the fallback: it's proven in the TIDY app (minSdk 26), but it's heavier. If we use it, we need **≥ 1.22** for the 16 KB page-size rule.
- No separate ML Kit image-labeling model. CLIP zero-shot tags replace it, and they're open-vocabulary.

---

## 4. Model choice and size budget

### Image–text model (CLIP family)

| Model | Params (img + txt) | IN-1k zero-shot | License | Ship in a Play app? |
|---|---|---|---|---|
| Apple MobileCLIP2-S0 | 11.4M + 63.4M | 71.5 % | **Apple ML Research license: research only, no commercial use** | ❌ No |
| OpenAI / LAION CLIP ViT-B/32 | ~88M + 63M | ~63–66 % | MIT | ✅, but about 150 MB at int8 (image encoder alone is about 91 MB) |
| SigLIP 2 base/16 | ~375M total | higher | Apache 2.0 | ✅, but too big for budget phones |
| **TinyCLIP ViT-45M/32 Text-18M** (LAION+YFCC) | 45M + 18M | **62.7 %** at 1.9 GMACs | **MIT** | ✅ **Recommended.** About 65 MB at int8 (est.) |
| TinyCLIP ViT-22M/32 Text-10M | 22M + 10M | 53.7 % at 1.9 GMACs | MIT | ✅ "Lite" option. About 32 MB at int8 (est.) |
| TinyCLIP ViT-8M/16 Text-3M | 23.4M total | 41.1 % | MIT | Too inaccurate |

TinyCLIP ViT-45M/32 matches OpenAI ViT-B/32 accuracy with less than half the compute.
It's MIT-licensed, so we can ship it commercially.

### App size

| Part | Size |
|---|---|
| App code + Compose + Room + WorkManager | ~6–10 MB (est.) |
| ML Kit OCR (unbundled, Latin + Devanagari) | < 1 MB in APK; model fetched by Play Services |
| LiteRT runtime (arm64) | a few hundred KB to a few MB |
| TinyCLIP 45M/18M int8 | ~65 MB (est.), delivered as a Play Asset Delivery *fast-follow* pack or a first-run download |
| **Total** | **~10–15 MB Play download first; ~50–80 MB installed with the model** |

Index database: about 1–3 KB per screenshot (OCR text + 1 KB float16 vector).
10,000 screenshots ≈ 20–30 MB.

Peak RAM while indexing: about 200–300 MB (est.). Load the models only inside the
worker and release them afterwards.

---

## 5. Speed and battery

### Speed (est., Snapdragon 4 Gen 2 / Dimensity 6300 class)
- **OCR:** about 0.3–1 s per screenshot. Google measured about 33 ms per frame on a Pixel 3 for camera-sized frames. Full-res text-dense screenshots on slow cores take longer.
- **TinyCLIP image encode** (224 px input, int8, 2–4 threads): about 0.1–0.4 s.
- **Total:** under about 1.5 s per new screenshot. Pixel Screenshots needed 15–20 s on a Pixel 9 and capped itself at about 15 a day.
- **Search:** text-encoder pass (~18M params) + vector scan + FTS ≈ under 100 ms.

### Battery (est.)
- Reference point: MobileNetV1 costs about 120 mJ per CPU inference. TinyCLIP image encode ≈ 3–4× that compute. OCR is the larger cost. Estimate **1–3 J per screenshot**, including model load.
- 5,000 mAh × 3.85 V ≈ 69,000 J.
- **30 screenshots/day ≈ 30–90 J ≈ 0.05–0.13 % of the battery per day.**
- **Initial backlog of 2,000 old screenshots ≈ 2–6 kJ ≈ 3–9 % of a battery**, so run it only with `setRequiresCharging(true)` (plus `setRequiresDeviceIdle` where available).
- New screenshots run with `setRequiresBatteryNotLow(true)`.
- No foreground service, no always-running process, no polling.

---

## 6. How each of our use cases is handled

| Use case | How | Expected quality |
|---|---|---|
| Search "car", "dog", "cat", "mountain", "phone" | CLIP text→image similarity | Good when the object is clearly visible. Small or partly hidden objects (a pen) are hit-or-miss |
| Search a movie's name seen in a Reel/Short | OCR text → FTS | Good when the title, caption or poster text is on screen |
| Search "movie" | (1) CLIP auto-tag "movie scene / film still"; (2) keyword rule tags the screenshot "Movies" (trailer, IMDb, cast, release date, in theatres, OTT names); (3) source app (YouTube/Instagram) where the file name carries it | Decent. Can't name a movie from a text-free frame |
| "Screenshots related to this" | Image-vector similarity + shared OCR keywords | Good for visually similar or same-topic shots |
| Hindi text inside screenshots | ML Kit Devanagari recognizer | Supported (slower than Latin) |
| Hindi/Hinglish *queries* | FTS works on the OCR'd Hindi text. CLIP queries are English-only | Partial. A multilingual text encoder could come later |

Source-app detection: Samsung names files `Screenshot_<date>_<AppName>`, and MIUI
reportedly does similar. AOSP-style names (`Screenshot_20240229-103000.png`) carry no
app. Treat this as a bonus signal, not a guarantee.

---

## 7. Android platform and Play Store requirements

- **Target SDK:** from **31 Aug 2026**, new apps and updates must target **API 36 (Android 16)**. Extensions are available to 1 Nov 2026.
- **16 KB page size:** every native library must be 16 KB-aligned. ONNX Runtime added this in 1.22. Use current LiteRT and ML Kit releases.
- **Media permission:** `READ_MEDIA_IMAGES` (API 33+) / `READ_EXTERNAL_STORAGE` (≤ 32). Google Play treats READ_MEDIA_IMAGES as **restricted**: we must submit the Photo & Video Permissions declaration and show that **persistent access to all images is the core feature**. A screenshot organizer qualifies, but describe it clearly to avoid rejection. The Photo Picker is not enough because we index automatically.
- **Partial access (Android 14+):** users can pick "Select photos", which grants `READ_MEDIA_VISUAL_USER_SELECTED` only. We then see just the chosen images. Detect this, explain it, and offer a re-select / grant-full-access button.
- **Detecting new screenshots:**
  - WorkManager `Constraints.Builder.addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)` (API 24+). The job wakes when images change, then we filter the `RELATIVE_PATH` for Screenshots. This is the battery-friendly path: no service and no polling.
  - **Catch-up on app open.** OEM killers (Xiaomi, Realme, Vivo) can block background jobs, so every launch indexes anything new. At about 1 s per shot that takes seconds.
  - Optional onboarding tip linking to the OEM's "allow background activity" setting (as dontkillmyapp.com documents). Never required.
- **Avoid foreground services for indexing.** Since Android 15, `dataSync`/`mediaProcessing` foreground services are limited to 6 h per 24 h, and they need a visible notification anyway.
- **Full-text search:** Room supports `@Fts3/@Fts4/@Fts5`. Use **FTS4**, which the platform SQLite on every Android version includes.

---

## 8. What the alternatives do (for reference)

| App | Approach | Where processing happens |
|---|---|---|
| Pixel Screenshots | Gemini Nano multimodal description + semantic search. Since June 2026 (v1.26.134.11) it also uses Private AI Compute in the cloud | Hybrid |
| PixelShot | On-device OCR, then the text is sent to a cloud LLM for summaries | Cloud |
| Shots Studio (GPL-3.0) | Gemini API (cloud) or a ~3 GB Gemma model on-device + OCR | User's choice |
| TIDY (F-Droid) | OpenCLIP ViT-B/32 (LAION-2B) via ONNX Runtime, Room, minSdk 26 | On-device |
| SmartScan (GPL-3.0) | CLIP-style embeddings, auto collections, duplicates. Optional cloud descriptions. minSdk 30 | On-device (+ optional cloud) |
| OnePlus Plus Mind / Mind Space | Screenshot capture + key-info extraction + actions | On-device + "Private Cloud Computing" |

Our design is TIDY's CLIP approach plus OCR, a smaller commercially licensed model,
screenshot-specific features, and optional Gemini Nano on flagships.

---

## 9. Risks and things to test first

1. **Benchmark on a real ₹12k phone** (e.g. Redmi 14C or Moto G45): OCR + CLIP time per screenshot, peak RAM, and battery over a 2,000-shot backlog.
2. **CLIP similarity threshold.** CLIP always returns *some* nearest image, so calibrate a cutoff on real screenshots to avoid junk results.
3. **CLIP on UI-heavy screenshots.** It's trained on photos. Check how "car" and "movie scene" rank on screenshots that are mostly text and UI.
4. **TinyCLIP → LiteRT conversion.** Check accuracy after int8 quantization. Fall back to ONNX Runtime ≥ 1.22 if conversion is painful.
5. **Play review** of the READ_MEDIA_IMAGES declaration.
6. **Model delivery.** A Play Asset Delivery fast-follow pack keeps the first download about 10–15 MB.

---

## Sources

- Pixel Screenshots help: https://support.google.com/pixelphone/answer/15312581
- Pixel Screenshots throttle: https://www.androidauthority.com/pixel-screenshots-throttle-3472066
- Pixel Screenshots cloud processing: https://www.androidauthority.com/pixel-screenshots-cloud-processing-3678819/ · https://9to5google.com/2026/06/17/pixel-screenshots-cloud-ai/
- MobileCLIP README, LICENSE_MODELS: https://github.com/apple/ml-mobileclip
- TinyCLIP model zoo (MIT): https://github.com/microsoft/Cream/tree/main/TinyCLIP
- SigLIP 2: https://huggingface.co/google/siglip2-base-patch16-256
- CLIP ViT-B/32 int8 on Android: https://github.com/greyovo/CLIP-android-demo
- TIDY: https://github.com/slavabarkov/tidy
- SmartScan: https://github.com/dev-diaries41/smartscan
- Shots Studio: https://github.com/AnsahMohammad/shots-studio
- PixelShot: https://www.androidauthority.com/pixelshot-app-3501179/
- ML Kit text recognition v2: https://developers.google.com/ml-kit/vision/text-recognition/v2/android
- ML Kit image labeling: https://developers.google.com/ml-kit/vision/image-labeling/android
- ML Kit GenAI APIs: https://developers.google.com/ml-kit/genai
- Gemma 3n: https://deepmind.google/models/gemma/gemma-3n/ · https://www.analyticsvidhya.com/blog/2025/08/run-gemma-3n-mobile/
- Budget phones: https://www.mysmartprice.com/mobile/pricelist/mobiles-under-12000-in-india.html
- Play photo/video permissions: https://support.google.com/googleplay/android-developer/answer/14115180
- Play target API: https://developer.android.com/google/play/requirements/target-sdk
- 16 KB pages: https://developer.android.com/guide/practices/page-sizes
- ONNX Runtime 16 KB support: https://github.com/microsoft/onnxruntime/pull/24947
- Partial photo access: https://developer.android.com/about/versions/14/changes/partial-photo-video-access
- WorkManager content-URI trigger: https://developer.android.com/reference/androidx/work/Constraints.Builder
- FGS timeouts: https://developer.android.com/develop/background-work/services/fgs/timeout
- Room FTS: https://developer.android.com/training/data-storage/room/defining-data
- Samsung screenshot naming: https://www.androidpolice.com/2017/12/19/neat-samsung-adding-context-info-screenshot-names-android-oreo/
- Energy per inference: MobileNetV1 ≈ 120 mJ on a phone CPU. The figure comes from search results on mobile-inference energy studies (e.g. https://arxiv.org/pdf/1707.04610); the exact paper is not verified.
