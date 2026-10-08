# ThunderNotes — Codebase Maintenance Guide

> This README is a **project guide for maintaining the codebase**. It is written
> for a developer who knows Java/C++/Python but is *new to Android* — it maps a
> reading path through the source, introduces every Android concept you'll meet
> (with the file that demonstrates it), and gives step-by-step recipes for the
> changes a maintainer actually makes. It deliberately does **not** describe the
> app's feature list; the behavioral contract lives in `docs/ThunderNote.md`
> (the owner's spec) and every source comment cites the spec section it
> implements.

**Two rules that shaped this codebase, and that you should keep following:**

1. **Pure core, thin Android shell.** Anything that doesn't need an Android
   class (document model, geometry, OCR preprocessing, serialization) is plain
   Kotlin with zero Android imports → it runs in fast JVM unit tests. Only
   rendering, dialogs, and OS services live in the `ui/` + `snip/` layers.
2. **Ordinary data everywhere.** A snipped equation becomes a normal
   `StrokeRecord`; a pasted snippet goes through the same add-stroke path as a
   hand-drawn line. Special-cased data models are how note apps rot.

---

## 1. The 10-minute mental model

```
┌────────────────────────── UI layer (Android) ───────────────────────────┐
│ MainActivity + 9 library fragments     CanvasActivity (the editor)     │
│ (Navigation component)                 CanvasInkHost (AndroidX Ink GL) │
│                                         CompletedStrokesView (custom)  │
│                                         BottomSheets (snip/latex/…)    │
└──────────────┬──────────────────────────────────┬───────────────────────┘
               │ StateFlow / callbacks            │
┌──────────────▼───────────── Kotlin, no Android ─▼───────────────────────┐
│ CanvasDocument (pages + undo/redo actions)  EditorUiState               │
│ NoteEditorViewModel (auto-save engine)      Lasso/Shape/Transforms      │
└──────────────┬──────────────────────────────────────────────────────────┘
               │ StrokeRecord / TextBoxRecord
┌──────────────▼───────────── data + format ──────────────────────────────┐
│ Room DAOs (app DB + per-note DB)   ThunderFile (.thunder ZIP writer)    │
│ CanvasRecordMappers (record⇄entity) InkStrokeProto (protobuf blob)      │
│ NotesRepository (staging + sessions)                                    │
└──────────────────────────────────────────────────────────────────────────┘
               ▲
┌──────────────┴──────────── snip (AI recognition) ───────────────────────┐
│ SnipEngine chain (Gemini → GLM → PaddleOCR)  Preprocessor, Tracers,     │
│ SnipBottomSheet pipeline, KaTeX renderer → StrokeGroup → clipboard      │
└──────────────────────────────────────────────────────────────────────────┘
```

The editor is the heart; everything else feeds it. Data flows **down** as
records and flows **up** as re-rendered views.

---

## 2. The reading path (read the files in this order)

Each stage lists files with the one idea it teaches. Budget ~30 minutes per
stage; total ≈ 1 day to feel at home, ~3 days to make non-trivial changes
safely.

### Stage 1 — App entry & the view system (start here)

| # | File | What you learn |
|---|------|----------------|
| 1 | `app/src/main/AndroidManifest.xml` | What an app *is*: every Activity/Service/permission declared; comments explain each permission and the foreground-service typing. |
| 2 | `ui/…/ThunderNotesApp.kt` (`app/src/main/java/com/thundernotes/`) | The Application class — process-wide singletons (DB, snip key store) initialized in `onCreate`. |
| 3 | `MainActivity.kt` | Single-Activity + Navigation-component pattern; ViewBinding. |
| 4 | `ui/folders/FoldersLibraryFragment.kt` | **The library-fragment recipe** (ViewBinding → ViewModel → RecyclerView → `repeatOnLifecycle` → bottom-sheet menus). Every library screen repeats it; the header primer spells out all five pieces. |
| 5 | `ui/notes/NotesLibraryFragment.kt` | The richest variant: search + grid + the 7-function note menu. Read to see the pattern scaled up. |
| 6 | `res/layout/activity_main.xml` + `res/navigation/nav_graph.xml` | How XML layouts declare the sidebar + NavHost, and how destinations map to fragments. |

### Stage 2 — The data layer (Room + repositories)

| # | File | What you learn |
|---|------|----------------|
| 7 | `data/entity/NoteEntity.kt` then `TextBoxEntity.kt` | Room `@Entity`: one class = one SQL table; `@ColumnInfo`/`@PrimaryKey`. |
| 8 | `data/dao/TextBoxDao.kt` | Room `@Dao`: compile-time-checked SQL, `suspend` = background, `Flow` = live queries, `OnConflictStrategy.REPLACE` = upsert. |
| 9 | `data/db/AppDatabase.kt` | The app-global DB (notes/folders/templates metadata). |
| 10 | `data/db/NoteDatabase.kt` | The **per-note** DB (strokes/textboxes inside each `.thunder`) + the Room primer + the `MIGRATION_1_2` example (how schema changes ship). |
| 11 | `data/repository/NotesRepository.kt` | Repository pattern: staging directories, `NoteEditingSession`, open/save lifecycle. |
| 12 | `data/repository/RepositoryModule.kt` | Manual dependency injection (no Hilt — one singleton object on purpose). |

### Stage 3 — The `.thunder` document format

| # | File | What you learn |
|---|------|----------------|
| 13 | `format/ThunderFile.kt` | Why a note is a **ZIP container**; atomic `.tmp`→rename writes; staging extraction. |
| 14 | `format/ThunderManifest.kt` | kotlinx.serialization JSON (the manifest inside the ZIP). |
| 15 | `format/proto/InkStrokeProto.kt` | Protobuf-style field numbering + why strokes are one BLOB per row. **Field numbers must never change.** |
| 16 | `format/proto/InkStrokeSerializer.kt` | Encode/decode of the proto ↔ byte array. |
| 17 | `canvas/StrokeBlobCodec.kt` | The glue: `StrokeRecord` ⇄ proto bytes ⇄ DB blob. |

### Stage 4 — The editor core (the heart)

| # | File | What you learn |
|---|------|----------------|
| 18 | `canvas/StrokeRecord.kt` + `canvas/TextBoxRecord.kt` | The pure in-memory stroke/textbox models — the currency of the whole app. Note the flat `inputXy`/`inputAttrs` packing. |
| 19 | `canvas/CanvasDocument.kt` | The document model + **command-pattern undo/redo** (`DocAction`). Read the NEWCOMER PRIMER first. |
| 20 | `ui/canvas/CanvasInkHost.kt` | **AndroidX Ink**: the GL drawing surface, touch routing per tool, palm rejection, coalesced-point capture. The primer explains the library. |
| 21 | `canvas/StrokeRecordConverter.kt` + `StrokeRecordPoints.kt` | The pure⇄renderable bridge (why two representations exist). |
| 22 | `ui/canvas/CompletedStrokesView.kt` | Custom View + `invalidate()`; where finished/pasted/reloaded strokes render. |
| 23 | `canvas/CanvasRecordMappers.kt` | record ⇄ Room entity mapping + bbox estimation for culling queries. |
| 24 | `canvas/BrushRegistry.kt` + `ui/canvas/EditorUiState.kt` | Tool/color/width state and the brush config it produces. |

### Stage 5 — The editor UI & auto-save (the big file)

| # | File | What you learn |
|---|------|----------------|
| 25 | `ui/canvas/CanvasActivity.kt` | ~1900 lines wiring every canvas feature. **Read the header section map first**, then `onCreate`, `addPageItem`, `handlePaste`, `handleUndo/Redo`, `rebuildFromLoad`. The per-page view-stack (6 stacked views/page) is the key structure. |
| 26 | `ui/canvas/NoteEditorViewModel.kt` | ViewModel + StateFlow primer, then the auto-save engine: DAO write-through → debounced ZIP flush → crash recovery via staging. |
| 27 | `ui/canvas/TextboxEditorBottomSheet.kt` | BottomSheetDialogFragment primer + the §7.1 formatting editor. |
| 28 | `canvas/lasso/LassoSelector.kt`, `LassoOps.kt`, `StrokeTransforms.kt`, `ShapeGeometry.kt` | Pure geometry: selection, cut/copy/delete, the 9 lasso transforms, shape generation. |
| 29 | `ui/canvas/CanvasSettingsBottomSheet.kt`, `GridlineOverlayView.kt`, `RulerOverlayView.kt`, `LassoOverlayView.kt` | The remaining editor UI — each is a small, focused custom view. |

### Stage 6 — The AI snip pipeline (the differentiator)

| # | File | What you learn |
|---|------|----------------|
| 30 | `snip/SnipEngine.kt` | Strategy + chain-of-responsibility: the pluggable engine interface, fallback chain, multi-account keys. |
| 31 | `snip/GeminiSnipEngine.kt` + `GLMSnipEngine.kt` | OkHttp + JSON REST calls, prompt-per-type, error surfacing. |
| 32 | `snip/SnipPreprocessor.kt` + `snip/SnipImage.kt` | Pure image math: Otsu binarization, line-band detection, crop/upscale/downscale. |
| 33 | `snip/SnipRegionDialogFragment.kt` + `snip/SnipCaptureActivity.kt` + `SnipOverlayService.kt` + `SnipProjectionService.kt` | Screen capture end-to-end: overlays, MediaProjection, **Android 14+ foreground-service typing** (two services exist precisely because of those rules — read their headers). |
| 34 | `snip/SnipBottomSheet.kt` | The pipeline orchestrator: 4 snip types → preprocess → recognize → map to clipboard items. |
| 35 | `snip/KatexRenderer.kt` + `LatexToStrokes.kt` + `LatexEditDialog.kt` + `LatexCleaner.kt` | LaTeX → offscreen WebView render → trace; the edit/confirm loop. |
| 36 | `snip/CenterlineTracer.kt` + `DiagramTracer.kt` + `StrokeGroupScaler.kt` | Zhang-Suen skeletonization, polyline tracing, stroke splitting, scaling to page space. |
| 37 | `snip/CodeHighlighter.kt` + `CodeFormatter.kt` + `CodeTheme.kt` | Regex tokenizer → Spannable colors for code snips. |
| 38 | `canvas/inject/ThunderClipboard.kt` + `ClipboardItem.kt` + `InkInjector.kt` + `ClipboardPayloadCodec.kt` | The app clipboard + paste path + the external injection ingress. |
| 39 | `export/PdfExporter.kt` | Rasterized PDF export + FileProvider sharing. |
| 40 | `plugins/TranslatorPlugin.kt` | The plugin proof-of-concept (spec §6.1.2). |

After Stage 6, skim `docs/architecture-plan.md` and
`docs/thunder-format-proposal.md` — they are the design rationale this code
implements.

---

## 3. Android concepts you'll meet (in the order the path hits them)

- **View & ViewGroup** — the UI tree. A `TextView` draws text, a `FrameLayout`
  stacks children, a `RecyclerView` recycles list rows. Every screen is a tree
  rooted in an Activity/Fragment. Demonstrated: every `res/layout/*.xml`.
- **ViewBinding** — a generated class (`ActivityCanvasBinding`) that turns XML
  ids into Kotlin properties. No `findViewById` anywhere.
- **Activity vs Fragment** — an Activity is an OS-level screen entry; a
  Fragment is a swappable chunk hosted in one. The library uses the
  Navigation component; the editor is its own Activity.
- **ViewModel + StateFlow + coroutines** — UI state that survives rotation,
  observable state holder, and structured concurrency (`Dispatchers.IO` for
  blocking work). Primer: `NoteEditorViewModel.kt` header.
- **Custom View + `invalidate()`** — override `onDraw`; never draw outside it;
  call `invalidate()` after state changes. Primers:
  `CompletedStrokesView.kt`, `LassoOverlayView.kt`.
- **Room** — compile-time-checked SQLite ORM. Entities, DAOs, migrations,
  exported schemas in `app/schemas/`. Primer: `NoteDatabase.kt` header.
- **AndroidX Ink** — Google's low-latency stylus library: an
  `InProgressStrokesView` renders the stroke you're drawing on a GL surface;
  on pen-up it hands you a finished `Stroke` and your app owns it. Primer:
  `CanvasInkHost.kt` header.
- **BottomSheetDialogFragment** — the slide-up panel used for every
  contextual menu. Primer: `TextboxEditorBottomSheet.kt`.
- **Foreground services + MediaProjection (Android 14+)** — overlay buttons
  need `specialUse` FGS; screen capture needs the system consent dialog plus
  a `mediaProjection`-type FGS running before `createVirtualDisplay()`. The
  two services in `snip/` exist exactly because these rules differ; read
  `SnipOverlayService` and `SnipProjectionService` headers together.
- **Protobuf-style BLOBs** — see Stage 3 / `InkStrokeProto.kt`.

---

## 4. Key data flows (trace these once; then the app has no secrets)

**Drawing one stroke.** Touch → `CanvasInkHost.onTouchEvent` (tool dispatch,
palm rejection) → `InProgressStrokesView.startStroke/addToStroke/finishStroke`
(GL trail) while the host mirrors every coalesced point into
`trackedXy/trackedAttrs` → finished listener builds a `StrokeRecord` →
`CanvasActivity` adds it to `CanvasDocument` (undo action pushed), renders on
`CompletedStrokesView`, and calls `viewModel.persistStrokeUpsert` (DAO
write-through).

**Auto-save.** Every edit = an immediate DAO upsert. `NoteEditorViewModel`
runs a debounced loop (10 s) + `onStop` flush → `NotesRepository.saveNote`
closes + re-zips the staging `note.sqlite` into the `.thunder` (atomic
`.tmp`→rename). Crash between flushes → next open resumes the staging DB.

**Snip → paste.** Overlay button tap → `SnipCaptureActivity` (consent →
projection FGS → one frame) → `SnipRegionDialogFragment` (drag-select +
confirm) → `SnipBottomSheet` (type → preprocess → engine chain →
`ClipboardItem`) → `ThunderClipboard.put` → paste button glows →
`handlePaste` → `InkInjector` drops records at the page, re-rendered +
persisted like hand-drawn content.

**Undo/redo.** `CanvasDocument.undo()` pops a `DocAction`, applies the
inverse, exposes `lastUndoneAction` → the activity syncs ONLY that action's
visuals (remove/re-add on the right page's views) + persists the inverse.

---

## 5. Making changes — recipes

**Add a toolbar button** (canvas): add the ImageButton in
`res/layout/activity_canvas.xml` → wire it in `CanvasActivity.onCreate` →
add string resources in `res/values/strings.xml` → (if it changes editor
state) extend `EditorUiState` + the ViewModel. Follow any existing
`binding.btnXxx.setOnClickListener` as the template.

**Add a per-page view** (overlay/layer): add the view in
`res/layout/item_canvas_page.xml` → add a `pageXxxViews` registry list in
`CanvasActivity` → append to it in `addPageItem` **and** remove from it in
`removePageView` (the two functions MUST stay symmetric — see the header
section map).

**Add a DB column** (per-note content): add the field + `@ColumnInfo` to the
entity → bump `NoteDatabase.version` → write a `Migration(n, n+1)` with the
`ALTER TABLE` and register it in `open()` (copy `MIGRATION_1_2`) → update
`CanvasRecordMappers` both directions → extend the codec/proto **only with a
new field number**. The schema JSON appears in `app/schemas/` on next build —
commit it.

**Add a snip engine**: implement `SnipEngine` (name/isEnabled/recognize) →
insert it into the `FallbackSnipEngine(listOf(...))` in
`SnipBottomSheet.onViewCreated` → add a toggle row in
`SnipSettingsBottomSheet` if user-disable is wanted. Nothing else changes.

**Add a plugin**: self-contained feature class + a row in the Plugins screen
(`ui/home` → Plugins fragment) following `TranslatorPlugin.kt`. The canvas
never learns about it.

---

## 6. Build, test, verify

```bash
./gradlew assembleDebug          # APK → app/build/outputs/apk/debug/
./gradlew testDebugUnitTest      # 300+ JVM tests (pure layers + Robolectric)
./gradlew lintDebug              # 0 errors required
```

Requirements: JDK 17, Android SDK with platform 36. `minSdk = 31`
(low-latency stylus APIs), `targetSdk = 36`. Test philosophy: everything pure
is tested (document, tracer, preprocessor, codecs, lasso math, engines'
fallback logic); Android-glue classes are kept thin + verified by build +
lint. Robolectric covers the DB/fragment layer.

Tests live in `app/src/test/java/com/thundernotes/…` mirroring the main
package structure — when you add pure logic, add the test file next to it.

---

## 7. Conventions & gotchas

- **Spec citations**: comments reference `ThunderNote.md` sections (§7.3 etc.)
  — grep for a section number to find every file involved in a feature.
- **`docs/`** holds the spec + reverse-engineering notes of the three donor
  apps (Notein/SamsungNotes/MyScript) — `Notein-README.md` in particular
  explains many structural choices.
- **Never** rename a `@ProtoNumber`, change a Room column without a
  migration, or store an Activity/View in a ViewModel.
- The editor uses **page-local coordinates**: strokes never move when writing
  space is added (only spacer metadata shifts) — Part C of the format
  proposal. Don't "fix" that by translating strokes.
- Kotlin nesting gotcha you'll actually hit: inside
  `someView.apply { … }` a bare property name resolves to the VIEW's members
  first (e.g. `overlay` → `View.getOverlay()`). Qualify with
  `this@OuterClass.field` — there's a warning comment in
  `SnipRegionDialogFragment.kt` marking the one place it bit us.
- The `sdk = 36` build uses AGP 9 / Kotlin 2.3; keep KSP + Room versions in
  `gradle/libs.versions.toml` in sync (Room compiler is KSP-annotated).

## 8. Known limitations (documented, not hidden)

- Split view shows the second note's title only (content mirroring pending).
- The random/free-form lasso is approximated by the rectangular variant.
- PaddleOCR offline engine is a stub (model download/integration pending);
  the chain falls through to it gracefully.
- Filler tool drops a translucent stamp (true vector flood-fill is future work).
- PDF export is rasterized (bitmap pages), not vector.

Each has a comment at its implementation site — search the spec section
number to find them.
