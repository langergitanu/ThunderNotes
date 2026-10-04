# ThunderNotes

> Android tablet-first note-taking app for the OnePlus Pad 2 (and any 12 in+ Android tablet).
> Built in Kotlin + Java with XML Views, [AndroidX Ink](https://developer.android.com/jetpack/androidx/releases/ink) for the canvas, and a
> self-contained `.thunder` document format. Pluggable online + offline AI snipping pipeline.

This is the canonical source repo for the app. The mock-UI repo ([`langergitanu/ThunderNotes-Frontend`](https://github.com/langergitanu/ThunderNotes-Frontend))
holds the HTML/Tailwind reference screens; this repo holds the production Android app.

## Project status

Phase 1 — project skeleton + Room schema + `.thunder` format (in progress).

See `docs/architecture-plan.md` for the full layered plan and `docs/thunder-format-proposal.md`
for the `.thunder` container + writing-space + LaTeX→stroke pipeline design. `docs/Notein-README.md`,
`docs/SamsungNotes-README.md`, `docs/MyScript-README.md`, and `docs/Mock-UI-README.md` are the
reverse-engineering notes from the three decompiled donor apps.

## Build

1. Clone this repo.
2. Open the project root in Android Studio (Ladybug or later).
3. Let Android Studio sync Gradle. The first sync will download Gradle 8.9 + AGP 8.5.2 + Kotlin 2.0.21 + KSP 2.0.21-1.0.25.
4. Plug in a 12 in+ tablet (target device: OnePlus Pad 2, Android 14 = API 34) and press **Run**.

`minSdk = 31` (Android 12 — required for low-latency AndroidX Ink stylus APIs).
`targetSdk = 34` (Android 14).
`compileSdk = 34`.

If you don't have a tablet, debug builds run on the standard Android 14 tablet emulator.

## Architecture (decided)

| Concern | Choice |
|---|---|
| Language | Kotlin 2.0.21 (primary) + Java (interop with donor patterns) |
| UI | **XML Views** (not Compose — per spec) |
| Ink | `androidx.ink:*` 1.1.0-alpha07 (Google public library — same one Notein uses) |
| Documents DB | Room/SQLite (entity model adapted from SamsungNotes; DAO patterns from SamsungNotes) |
| Folder tree | Closure-table (`ancestor/descendant/depth`) — SamsungNotes pattern |
| Stroke serialization | Protobuf (own schema, mirroring Notein's 14-field `InkStrokeProto`) |
| Native container | `.thunder` = ZIP(`manifest.json` + `note.sqlite` + `assets/` + `preview.png`) |
| Writing space | Page-local immutable coords + ordered spacers + Fenwick prefix sums (O(log N) insert/query) |
| External injection | `ContentProvider` + intent action `com.thundernotes.action.INJECT_CONTENT` + watch-&-reload fallback (Nebo precedent) |
| LaTeX render | KaTeX in offscreen WebView (offline assets; black-on-white, high-DPI) |
| LaTeX→strokes | OpenCV centerline tracer (binarize → skeletonize → polylines) → AndroidX Ink strokes; shared with Diagram Snip |
| Snipping OCR | Pluggable `SnipEngine` interface + `FallbackSnipEngine` decorator: Gemini 2.5/3.0 Flash → GLM-4.6V-Flash → PaddleOCR-VL-1.6 (offline) |
| PDF export | Pdfium Android |
| DI | Hilt (to be added in phase 2) |

## Donor apps (used as references, not as imported code)

| App | What we take |
|---|---|
| **Notein** (`com.orion.notein.global`) | Built on `androidx.ink` — proves the engine choice. Stroke protobuf schema + brush family IDs. |
| **Samsung Notes** (`com.samsung.android.app.notes`) | Readable data layer — entities, DAOs, repositories, closure-table folders, recycle bin, sync timestamps. |
| **MyScript Nebo** (`com.myscript.nebo`) | Clean architecture reference — engine facade layering, tool/palette string-keyed properties, `NeboClipboardContentProvider` (precedent for our injection ingress). |

## License

To be finalized. The app itself is free for personal use; third-party assets (fonts, brushes, OCR
models) ship under their respective OFL/Apache licenses (see `app/src/main/assets/licenses/` once
the assets land).
