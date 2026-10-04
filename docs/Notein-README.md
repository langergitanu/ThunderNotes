# Notein Reverse-Engineering — Findings & Reuse Guide

Source: `$HOME/AndroidLab/apps/Notein` (latest version, pulled from tablet)
Tools: apktool 3.0.2 (`apktool/base/`), jadx 1.5.5 (`jadx/base/sources/`)
Author: opencode analysis

---

## 0. TL;DR — Verdict

**Highly reusable. Notein is an excellent donor.**
The single most important finding: **Notein is built on Google's official Jetpack `androidx.ink`**
library (`androidx.ink:ink-authoring`, `ink-brush`, `ink-geometry`, `ink-strokes`, `ink-storage`,
`ink-rendering`), version **1.1.0-alpha07**. It is NOT a hand-rolled ink engine. This means we do
**not** need to copy Notein's rendering internals at all — we can depend on the same public AndroidX
library and inherit the same low-latency, artifact-free stylus behaviour Notein has. Notein's own
code is mostly *glue* around AndroidX Ink.

Ghidra analysis of `libink.so`: **NOT worth it** — that `.so` is simply the JNI backend of the public
AndroidX Ink library (symbols are `Java_androidx_ink_brush_*`, `Java_androidx_ink_strokes_*`). The
library is open source and obfuscation-free. Analyze the AndroidX source instead of the binary.

One native lib IS worth a look: `libestimate_interface.so` (Xiaomi pen path estimator) — see §6.

---

## 1. App identity

| Item | Value |
|---|---|
| Package | `com.orion.notein.global` (app class is wrapped by `com.pairip.application.Application`) |
| Launcher | `com.orion.notein.MainActivity` |
| Protection | PairIP app-level wrapper; real classes are unobfuscated and readable |
| Architecture | Kotlin + some Java; hybrid (Compose present but editor is View-based) |
| Ink stack | AndroidX Ink 1.1.0-alpha07 (see §2) |
| Split APKs | `base.apk`, `split_config.xxhdpi.apk`, `split_xxhdpi` |

Useful package roots (under `jadx/base/sources/com/orion/`):
- `penkit/` — ink stroke models, tools, serialization
- `ni_render_engine/` — render engine glue (brush family provider)
- `notein/data/` — Room DB (`p602db`), repositories, OCR/AI services
- `editor/` — canvas/editor views, context menus, zoom handles, text selection
- `notein/domain/` — domain models, workers, mappers

---

## 2. Ink engine — USE ANDROIDX INK, DON'T COPY BINARIES

**Rule: add `androidx.ink` dependencies to our project rather than porting Notein's engine.**

Notein's brush family IDs (from `ni_render_engine/impl/ink/BrushFamilyProvider.java`):
```
notein-ballpoint-v1
notein-fountain-v1 / v2
notein-highlighter-v1
notein-pencil-v1 / v2
notein-rainbow-v1 / v2
pencil-unstable (legacy)
```
Brush definitions ship as assets (see §5). The provider also caches custom brush families and
texture bitmaps (`ConcurrentHashMap`) — a good pattern to copy for our brush registry.

Classes to study (not copy verbatim):
- `penkit/` — `InkStrokeInfo`, `DrawRecord`, `Mask`, `LayerInfo`, `EraserDot`, `LassoParams`
- `ni_render_engine/impl/ink/BrushFamilyProvider.java` — brush-family loading/caching
- `editor/ForegroundView.java`, `editor/PDFView.java` — canvas foreground/PDF overlay views
- `editor/p600ui/` — context menu, zoom handles, text selection (view-pattern reference)

AndroidX packages present (all from the public library, so just add the dependency):
`androidx.ink.authoring` (+`InProgressStrokesView`, `InProgressShapesView`),
`androidx.ink.brush` (+`StockBrushes`, `BrushBehaviorNative`, `StampingTexture`),
`androidx.ink.geometry`, `androidx.ink.strokes`, `androidx.ink.storage`.
Low-latency front-buffered rendering helpers are present (`GLFrontBufferedRenderer` refs,
`CanvasInProgressStrokesRenderHelperV21`) — provided by the library.

---

## 3. Native document / stroke serialization — HIGH REUSE VALUE

This is the best copy candidate. Notein serializes each stroke as a **protobuf** message wrapped in
a magic header, Base64-encoded for text channels.

Files (under `jadx/base/sources/com/orion/penkit/serialization/ink/proto/`):
- `InkStrokeProto.java` (1065 lines) — generated protobuf message
- `InkStrokeProtoOrBuilder.java`
- `InkStrokeProtoSerializer.java` (347 lines) — **read this**
- `ToolType.java` — STYLUS / TOUCH / MOUSE / UNKNOWN
- `InkStroke.java`

Serialization details extracted from `InkStrokeProtoSerializer`:
- Magic bytes: `{78, 73, 80, 66}` = ASCII **"NIPB"**; Base64 prefix `"TklQQg"`.
- Minimum valid length: 10.
- `InkStrokeProto` fields: `id(1)`, `layer_id(2)`, `creation_time(3)`, `brush_size(4)`,
  `brush_color(5)`, `brush_epsilon(6)`, `brush_family_id(7)`, `tool_type(8)`,
  `stroke_unit_length_cm(9)`, `input_xy(10)`, `input_attrs(11)`, `stroke_to_world(12)`,
  `world_to_view(13)`, `behavior_params(14)`.
- Straight port to AndroidX Ink:
  `InkStroke` = `Brush(brushSize, brushEpsilon, brushColor, BrushFamily)` + `StrokeInputBatch`.
- `input_xy` = flat [x0,y0,x1,y1,...]; `input_attrs` = **5 floats per point**
  (`ATTR_STRIDE = 5`: timestamp, pressure, tilt, orientation, ... as read in
  `buildStrokeInputBatch`).
- `stroke_to_world` / `world_to_view` = 9-value affine `Matrix` (0 or exactly 9 elements).
- `behavior_params` = `Map<String,Float>` of brush behaviour overrides
  (e.g. `pressure_correct`, `speed_correct`).
- Two modes: `serialize`/`deserialize` (single stroke, Base64) and
  `serializeToBytes`/`deserializeFromBytes` (raw bytes)

**Recommended action:** define our own protobuf schema (mirroring these fields) for `.thunder`, and
copy the serializer's structure — validation, magic header, matrix handling, tool-type mapping.
Do NOT copy package names or the AndroidX-Ink coupling blindly; adapt.

Companion data-model clues (Room entities, `notein/data/p602db/entity/`):
- `NoteEntity`, `NoteFileEntity`, `NoteContentEntity`, `NoteContentDatabase`
- `NoteContentEntity` stores `pageListJson`, `pageLayerListJson`, `outlines`, `defaultPageId`,
  `pdfInfoId`, `savedPageIndex`, `activatedPageLayerId`, `zoom`, `pageOffSet`, `unboundedNote`,
  `extrasJson` → **their document = pages + layers-per-page + zoom/offset + outlines**.
- Separately: `LayerInfo`, `PagePart`, `ClipboardData`, `LassoParams`, `Mask`, `ImageEntity`,
  `CommentEntity`, `HyperLinkEntity`, `LabelEntity`, `ColorSetEntity`, `MyTemplateEntity`,
  `AudioFileEntity`, `NoteConflictEntity` (conflict handling!), `LatestOpenedNoteEntity`.

Native export container: extension **`.notein`**, MIME `application/octet-stream`
(found via `notein/data/repo/backup`). Backup/export classes are obfuscated
(`OooOOO*`); if we need the container layout, read them or diff an actual `.notein` file.

---

## 4. AI / OCR architecture — architecture reference

Notein's OCR/AI are remote API calls, not on-device (except ML Kit OCR models in assets).

- `notein/data/service/ocr/request/GeminiTextPrompt.java`
- `notein/data/service/ocr/response/OcrGoogleResponse.java`
- `notein/data/service/p604ai/request/{CreateIndexRequest, PromptQuestion}.java`
- `notein/data/service/p604ai/response/{AnswerResponse, CreateIndexResponse, UploadFileResponse}.java`
- Cloud/AI DBs: `NoteAiDatabase`, `CloudSyncDatabase`, `NoteCacheDatabase`
- `AiOcrSessionEntity`, `AiOcrTextPageEntity`, `NoteinAiQuotaRecordEntity(V2)` → they track AI quotas.

Takeaway: Notein uses **Gemini + Google OCR + a create-index/ask-question RAG flow**. Our spec's
pluggable online+offline AI engine interface can mirror this request/response shape.

Diagram tracing: **OpenCV is bundled** (`lib/arm64-v8a/libopencv_java4.so`) — that is how vector
tracing / contour extraction is done. Reusable approach; we can depend on OpenCV Android SDK.

---

## 5. Assets worth reusing (check licenses!)

- **Brush definitions** (`apktool/base/assets/ink/`): `notein-pencil-v1/v2.noteinbrush`,
  `notein-rainbow-v1/v2.noteinbrush`, `pencil-unstable.brushfamily`. ⚠️ Proprietary format/names —
  read them to learn the brush-behaviour parameters, but define our own.
- **Fonts** (`assets/fonts/`, 18 TTFs): Caveat, Caveat Brush, Christopherhand, Dancing Script,
  Edu AU VIC WA NT Hand, Great Vibes, harrison, Journal, Kalam, Note this, Pacifico, Patrick Hand,
  Playwrite US Modern/Trad, Roboto, Roboto Serif, Zhiyong Elegant/Write.
  ⚠️ Licenses for several are in `assets/font-licenses/` (Caveat, Dancing Script, Great Vibes,
  Kalam, Pacifico, Patrick Hand, Playwrite...). Use licensed/open fonts for our 6 handwriting fonts.
- **ML Kit OCR models** (`assets/mlkit-google-ocr-models/`) — Google ML Kit; use the ML Kit
  dependency instead of copying models.
- **`assets/font-licenses/sources.json`** — provenance list; useful for our licensing decisions.

---

## 6. Native libraries inventory (complete)

30 unique `.so` files (the "50+" count is per-ABI duplicates: arm64-v8a + armeabi-v7a).
**All are third-party; none is Notein's own rendering code.** Verdict per library:

| Library | Origin / symbols | Ghidra? |
|---|---|---|
| `libink.so` | **AndroidX Ink** JNI backend (`Java_androidx_ink_*`) | ❌ open source — use dependency |
| `libopencv_java4.so` | OpenCV 4 | ❌ use OpenCV SDK |
| `libpdfium.cr.so`, `libjniPdfium.so` | Pdfium | ❌ use Pdfium dependency |
| `libimagepipeline.so`, `libstatic-webp.so`, `libnative-imagetranscoder.so`, `libnative-filters.so`, `libglide-webp.so` | Facebook Fresco / Glide image loaders | ❌ no |
| `libmlkit_google_ocr_pipeline.so`, `libimage_processing_util_jni.so`, `libsurface_util_jni.so` | Google ML Kit / CameraX | ❌ use dependencies |
| `libtranslate_jni.so`, `liblanguage_id_l2c_jni.so` | ML Kit NL translate / lang-id | ❌ use dependencies |
| `libicuuc.cr.so`, `libabsl.cr.so`, `libc++_chrome.cr.so`, `libchrome_zlib.cr.so`, `libpartition_alloc.cr.so`, `libcrashlytics*.so`, `libdatastore_shared_counter.so`, `libc++_shared.so` | infra (ICU, absl, zlib, Crashlytics, DataStore) | ❌ no |
| `libgraphics-core.so` | Skia / Jetpack Compose graphics (AOSP build) | ❌ no |
| `libgojni.so` (16 MB) | **PDFcpu** (Go) — `Java_pdfcpu_1mobile_*`: merge/optimize/pageCount | ❌ third party (Go PDF tool) |
| `libestimate_interface.so` (6.5 KB) | **Xiaomi** pen engine facade — `Java_com_miui_penengine_facade_algorithm_JNIPathEstimateInterface_*`; it only `dlopen`s the system pen lib | ⚠️ wrapper — no logic inside |
| `libtrack_prediction.so` (424 KB) | **Vivo** track predictor — `Java_com_vivo_trackpredictor_core_TrackPredCore_*`, strings `TRACKPRED-track predictor`, `predict:PEN DOWN,reset!` | ⚠️ real predictor, but OEM-specific |

### Is Ghidra worth it? — Definitive answer: **No, not on any of them.**

1. The libraries that matter (`libink`, OpenCV, Pdfium, ML Kit) are **public open-source** — read
   their Kotlin/Java, don't reverse the binary.
2. `libestimate_interface.so` is a **tiny dlopen wrapper** around a system-partition pen library; it
   contains no algorithm to recover.
3. `libtrack_prediction.so` (Vivo) is a self-contained predictor, but it is an OEM system component
   and **we already get stroke prediction from AndroidX Ink**. Reverse-engineering it would only
   matter if we wanted to out-predict AndroidX Ink — not needed.
4. Everything else is infra (Fresco, Crashlytics, ICU, absl, PDFcpu).

**Action: skip Ghidra entirely for Notein.** Instead, list the exact public dependencies and move on.
Revisit only if, after integrating AndroidX Ink, the OnePlus Pad 2 stylus feel is still insufficient.

---

## 6b. How much code was actually revealed?

**Obfuscation reality:** ~1635 Java classes under `com/orion`; ~372 are obfuscated-looking
(`OooO*` / `C#####*`), and the **data/business layer is the most obfuscated part.**

| Area | Status | Examples |
|---|---|---|
| Ink stroke models & serialization | ✅ **Readable** | `penkit/models/InkStrokeInfo`, `penkit/serialization/.../InkStrokeProtoSerializer` |
| Brush family provider | ✅ **Readable** | `ni_render_engine/impl/ink/BrushFamilyProvider` |
| Room **entities** | ✅ **Readable** | `FolderEntity`, `NoteEntity`, `NoteContentEntity`, `ImageEntity`, `LabelEntity`, `CommentEntity`, `NoteConflictEntity` |
| UI dialogs / fragments | ✅ **Readable** | `FolderCreationDialogFragment`, `RenameDialogFragment`, `FabNoteTypeFragment`, `MoveToFolderFragment`, `NoteSearchView`, `RecentFragment` |
| AI/OCR request-response DTOs | ✅ **Readable** | `service/ocr/*`, `service/p604ai/*` |
| Domain models | ✅ **Readable** | `domain/models/{folder,note,page,paper,label,handwriting,...}` |
| **DAOs** | ❌ **None readable** | no `*Dao.java` survived — all obfuscated |
| **Repositories / business logic** | ❌ **Mostly hidden** | only 3rd-party repos readable (OneDrive, Billing, Baidu); core CRUD is in `OooOOO*` classes |

### So — is the "create note / folder / bookmark" code revealed?
**Partly, and split by layer:**
- ✅ **The UI/spec layer is revealed:** `FolderCreationDialogFragment` shows the exact fields and
  flow; `FabNoteTypeFragment` shows note-type creation; `RenameDialogFragment`, `MoveToFolder*`,
  label/bookmark fragments show the rest.
- ✅ **The schema layer is revealed:** every Room `*Entity` untouched — so we know exactly what
  columns a folder/note/bookmark has, including `bookmarked`, `recycledTime` (trash),
  `bookmarkTime`, `syncType`, `parentId`, etc.
- ❌ **The execution layer is NOT revealed:** the actual DAO queries and repository insert logic are
  obfuscated (`C#####OooO*`). You cannot lift "createFolder()" cleanly.

**Conclusion:** Notein is a 10/10 donor for **architecture, schema, ink serialization, and library
selection**, but a poor donor for **CRUD implementation** because its business logic is obfuscated.

---

## 7. Permissions observed (for our design reference)

Declared by Notein: `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`,
`SYSTEM_ALERT_WINDOW` (overlay — matches our AI-snip floating button),
`FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PROJECTION` (screen capture for snip),
`READ_MEDIA_IMAGES` / `READ_EXTERNAL_STORAGE` / `WRITE_EXTERNAL_STORAGE`,
`RECORD_AUDIO` (audio notes — not in our spec), `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`,
`WAKE_LOCK`, `USE_BIOMETRIC`/`USE_FINGERPRINT`, `NFC` (stylus/quick actions).
OEM-specific stylus hooks: `com.oplus.ipemanager.permission.receiver.DOUBLE_CLICK` (OnePlus/OPPO
stylus double-click!) — relevant to our OnePlus Pad 2 target.

Our app likely needs: INTERNET, SYSTEM_ALERT_WINDOW, FOREGROUND_SERVICE(+mediaProjection),
READ_MEDIA_IMAGES, POST_NOTIFICATIONS, plus OEM stylus receiver declarations.

---

## 8. How to use this code — recommended workflow

1. **Do NOT import files wholesale.** Jadx output is noisy (synthetic `OooO*` classes, renamed
   fields, `@Metadata` annotations). Use it as a *reference*, re-implement cleanly in our project.
2. **Adopt the public libraries Notein uses instead of copying binaries:**
   - `androidx.ink:*` (ink authoring/brush/strokes/rendering/geometry/storage)
   - OpenCV Android SDK (diagram tracing)
   - Pdfium (PDF render/export); ML Kit (OCR / translate)
3. **Copy structurally (rewrite):**
   - Stroke protobuf schema + serializer pattern → our `.thunder` stroke layer (§3)
   - Room entity layout → our notes/folders/layers/conflicts model (§3)
   - Brush-family provider caching pattern (§2)
   - AI request/response shape (§4)
4. **Read for design, don't port:** editor views, context menus, zoom/text-selection UX (§2).
5. **Legal note:** this is for *inspiration/study*. Brush files, fonts, Aspose licenses, and ML Kit
   models carry their own terms. Ship open-licensed fonts/brushes of our own; use standard OSS libs.

---

## 9. Open items for the next phase

- Diff/parse an actual exported `.notein` file to finalize `.thunder` container design.
- Decide `.thunder` = zip(protobuf strokes + page/layer JSON + assets) vs single-file DB.
- Confirm AndroidX Ink stable version to target (Notein used 1.1.0-alpha07; check current stable).
- **Ghidra: NOT recommended for Notein** (see §6). Revisit only if stylus feel is insufficient.
- **Recommended next donor: Samsung Notes** — reportedly much less obfuscated, so its CRUD/room
  repositories (the layer Notein hid) should be readable. Decompile it with apktool + jadx and
  extract: file/folder/bookmark creation & lifecycle, DAO queries, trash/restore, and PDF export.