# ThunderNotes — Consolidated Architecture Plan (Phase 3)

Synthesis of: spec (`ThunderNotesOpencode.md`), mockups, and reverse-engineering of
Notein, SamsungNotes, MyScript Nebo (+ `.in` native-exports analysis).
Author: opencode

---

## 0. Executive summary

- **Ink/render:** AndroidX Ink (Google, open) — chosen from Notein analysis.
- **Storage/format:** Notein-style ZIP + SQLite/Room + protobuf stroke blobs, with fixes → `.thunder`.
- **Data/CRUD:** Samsung's entity + closure-table + recycle-bin patterns, rewritten onto our model.
- **Engine/AI/export API:** mirror MyScript Nebo's layering (doc/page/reco/tool/export).
- **Snipping:** pluggable **online + offline fallback** engine per snip type (vendor list §6).
- **Writing space:** metadata spacers + Fenwick prefix sums (no stroke movement).
- **Injection:** internal `InkInjector` + external ContentProvider/intent ingress.

Rough effort split (personal-use build):
**~25–30% adopt (libraries/patterns, light change) · ~40% adapt donor patterns into our model ·
~30–35% genuinely new code (integration seams, reflow, AI glue, equation→stroke conversion).**

---

## 1. Technology stack (decided)

| Concern | Choice | Source |
|---|---|---|
| Language | Kotlin (+ Java) | native Android |
| UI | **XML Views** (not Compose) | spec |
| Min SDK | **~29–31** (low-latency ink path; Pad 2 = A13/14) | analysis |
| Ink | **androidx.ink** (authoring/brush/strokes/rendering/geometry/storage) | Notein |
| Canvas hosting | View + `InProgressStrokesView` / front-buffered renderer | AndroidX Ink |
| Documents DB | **Room/SQLite** | both donors |
| Stroke serialization | **Protobuf `InkStrokeProto`** (own schema) | Notein |
| Folder tree | **Category tree + closure table** | Samsung |
| Diagram tracing | **OpenCV (Android SDK)** | Notein |
| PDF render/export | **Pdfium** (render) + export writer | Notein |
| Text OCR | **ML Kit Text Recognition v2** (offline) | Notein assets |
| Equation OCR | **AI vision → LaTeX** (BYO key), offline fallback | spec + MyScript ref |
| Equation render | **KaTeX** in offscreen WebView (offline assets) — decided | new |
| Equation render→strokes | centerline tracer: binarize→skeletonize→polylines → Ink strokes (shared with Diagram Snip) — decided | new |
| Syntax highlight | local highlighter (Prism4j / highlight.js) | new |
| Image loading | Glide/Coil | donors |
| DI / async | Hilt + Coroutines | convention |
| Capture from other apps | **MediaProjection** + overlay | Notein manifest |

---

## 2. Layered architecture

```
┌───────────────────────────────────────────────────────────────┐
│ UI (XML)  Home · Libraries · Canvas(4 modes) · Dialogs        │  ← mockups
├───────────────────────────────────────────────────────────────┤
│ ViewModels / Controllers                                      │
├──────────────┬───────────────┬───────────────┬────────────────┤
│ Canvas/Ink   │ Snipping      │ Organization  │ Export/Import  │
│ service      │ pipeline      │ (notes/folders│ (.thunder/PDF) │
│ (injector)   │ (OCR→strokes) │  /trash/search)│                │
├──────────────┴───────────────┴───────────────┴────────────────┤
│ Domain model  (Note · Page · Layer · Stroke · Shape ·         │
│  TextBox · Image · Outline · Spacer)                          │
├───────────────────────────────────────────────────────────────┤
│ Repositories (Room DAOs) + FileStore (assets/thumbnails)      │
├───────────────────────────────────────────────────────────────┤
│ Platform: AndroidX Ink · MediaProjection · ContentProvider ·  │
│  ML Kit · OpenCV · Pdfium · JLaTeXMath · AI HTTP client       │
└───────────────────────────────────────────────────────────────┘
```

---

## 3. Module inventory — copy / adapt / write

Legend: **A** = adopt (library or near-verbatim, light change) · **B** = adapt (rewrite into our
model using donor as reference) · **C** = craft new.

| Module | Kind | Donor/reference | Notes |
|---|---|---|---|
| Ink engine (draw/erase/lasso/shapes) | **A** | AndroidX Ink | dependency; no code copy |
| Brush families + caching | **A/B** | Notein `BrushFamilyProvider` | define our own brush set |
| Low-latency render host | **A** | AndroidX Ink authoring | use library views |
| Stroke protobuf schema + serializer | **B** | Notein `InkStrokeProtoSerializer` | own schema, magic header |
| Document/page/layer model | **B** | Notein entities + Samsung entities | unify |
| `.thunder` container (ZIP+DB) | **B** | Notein `.in` | + manifest, embed assets, WAL checkpoint |
| Folder closure tree (SQL + repo) | **B** | Samsung `NotesCategoryTreeDao` | near-direct pattern |
| Note/folder CRUD repositories | **B** | Samsung `.../data/repository/*` | rewrite on our schema |
| Recycle bin + restore + expiry | **B** | Samsung `NotesRecycleBinRepository` | + sync timestamps |
| Search (title-only now) | **B** | Samsung `NotesSearchDao` | design for content search later |
| PDF export | **B** | Notein (Pdfium) + Samsung `PDFWriterRepository` | mapping table pattern |
| Textbox + rich text | **B** | Notein `TextBoxEntity` / Nebo text model | fonts, bold/italic/underline |
| Tool/palette data model | **B** | Nebo `ToolConfiguration`/`ToolProperty` | string-keyed props |
| Engine facade layering | **B** | Nebo `DocumentController`/`PageController` | doc/page/reco/display split |
| AI feature UX (stream panes) | **B** | Nebo `nebo.ai` | summarize/explain/quiz optional |
| Injection (internal) | **C** | — | `InkInjector` writes strokes/textboxes |
| Injection (external ingress) | **B/C** | Nebo `NeboClipboardContentProvider` | ContentProvider or intent |
| **Add writing space (spacer+Fenwick)** | **C** | Notein offsets / Nebo reflow (concept) | core new algorithm |
| **Smart theme inversion** | **C** | spec | preserve hue, invert lightness only |
| **Equation snip → LaTeX → strokes** | **C** | MyScript pipeline (concept) | KaTeX (offscreen WebView) → centerline trace → Ink strokes; shared tracer with Diagram Snip (see thunder-format-proposal Part D) |
| **Diagram snip tracing** | **C** | Notein (OpenCV concept) | segmentation rule |
| **Code snip formatter** | **C** | — | OCR + highlighter |
| **Uploadable/offline AI fallback** | **C** | spec | engine interface |
| Page minimap | **B** | Notein `canvasUtility` mock | thumbnail list |
| Templates library | **B** | mockups + Notein `MyTemplateEntity` | covers/papers |
| Plugins (translator test) | **C** | spec | plugin interface |
| On-screen overlay snip button | **B** | Notein `SYSTEM_ALERT_WINDOW` + MediaProjection | permission flow |
| Import/export `.thunder` round-trip | **C** | — | validates format |
| UI screens (10) | **B** | mockups (HTML/Tailwind) | translate to XML, not pixel-perfect |
| Fonts/equation rendering | **C** | font licensing (§7) | TTF assets + KaTeX (offscreen WebView) |

**Estimate:** ~30% A (adopt), ~40% B (adapt), ~30% C (new). The **C** items are the risk centers and
should be prototyped early (see §9).

---

## 4. Data model / `.thunder`

Adopt the Notein document schema (pages, layers, strokes, shapes, textboxes, images, outlines,
comments, hyperlinks) **plus** Samsung's folder/closure/recycle-bin at the *global* level, **plus**
our additions:
- `SpacerEntity` (or `spacers` JSON) for Add Writing Space.
- `manifest.json` with `format_version`, `schema_version`, `checksum`.
- Embedded `assets/` (self-contained).
- WAL checkpoint before export.

Full detail: `docs/thunder-format-proposal.md`.

---

## 5. Injection & writing space (resolved)

- **Internal paste** (normal snip users): OCR → build `Stroke`/`TextBox` → `INSERT` → redraw. No
  root/USB. ✅
- **External injection** (plugins/tests): `ContentProvider`/intent ingress (Nebo precedent) +
  file watch-and-reload fallback.
- **Add Writing Space:** page-local immutable coords + ordered spacers + **Fenwick prefix sums**
  → O(log N) insert/query, O(viewport) redraw, no hang <1000 pages.

---

## 6. Snipping architecture (the TBD's) — viable software list

Planned model: **one `SnipEngine` interface per type; online primary + offline fallback; pluggable.**
Output: Text/Code → textbox; Equation/Diagram → native strokes (erasable).

### 6.1 Text snip
| Tier | Option | License/notes |
|---|---|---|
| Offline (DECIDED) | **PaddleOCR-VL-1.6** (ONNX Runtime Mobile / Paddle-Lite) | offline engine chosen |
| Offline fast alt | Google ML Kit Text Recognition v2 | lightweight optional fast path |
| Online (DECIDED chain) | **Gemini 2.5/3.0 Flash → GLM-4.6V-Flash** | free, BYO key |
**Recommend:** online chain first (Gemini → GLM), fall back to PaddleOCR-VL-1.6 offline → handwriting
font → textbox.

### 6.2 Equation snip
| Tier | Option | License/notes |
|---|---|---|
| Online (DECIDED chain) | **Gemini 2.5/3.0 Flash → GLM-4.6V-Flash** → LaTeX | free, BYO key |
| Online alt | **Mathpix API** | purpose-built, commercial/free tier |
| Offline (DECIDED) | **PaddleOCR-VL-1.6** + `PP-FormulaNet_plus-L` for math-only crops | Apache-2.0 |
| Render | **KaTeX** in offscreen WebView (offline assets; black-on-white, high-DPI) — DECIDED | open |
| Render→strokes | **centerline tracer**: binarize → skeletonize → polylines → Ink strokes; **shared with Diagram Snip** — DECIDED | open |
**Recommend:** online chain first (Gemini → GLM → PaddleOCR); user-editable LaTeX + re-render
(mockup flow); then KaTeX render → centerline trace → native strokes (Part D of
`thunder-format-proposal.md`). Multi-line: prompt the LLM / build a LaTeX `aligned` block, then
line-aware trace.

### 6.3 Code snip
| Tier | Option | License/notes |
|---|---|---|
| OCR | **ML Kit Text Recognition v2** | offline |
| Format/highlight (offline) ✅ | **Prism4j** (Java) or **highlight.js** in WebView | open |
| Online fallback | LLM "format this code" | BYO key |
**Recommend:** ML Kit OCR → local syntax highlighter → colored spans in a textbox. (Spec: colors
need not match palette.)

### 6.4 Diagram snip
| Tier | Option | License/notes |
|---|---|---|
| Trace/vectorize (offline) ✅ | **OpenCV** (contours, `approxPolyDP`, color cluster) | Apache-2.0 |
| Trace alt | potrace / autotrace | GPL (fine for personal) |
| Segment rule | separate strokes iff **sharp point OR color change** | spec |
| Online fallback | vision LLM → SVG (experimental) | optional |
**Recommend:** OpenCV offline primary.

### 6.5 Cross-cutting
- **Capture overlay:** `SYSTEM_ALERT_WINDOW` floating button + **MediaProjection** (from Notein).
- **Result storage:** app clipboard (single item, glows while held); paste via paste button.
- **Engine interface:** `interface SnipEngine<T> { suspend fun run(bitmap): Result<T> }`, with a
  **3-tier fallback decorator** `FallbackSnipEngine(gemini, glm, paddleocr)` matching the decided
  chain — tried in order until one returns a result.
- **LaTeX → strokes:** KaTeX (offscreen WebView) → shared centerline tracer → AndroidX Ink strokes
  → clipboard group. See `thunder-format-proposal.md` Part D.
- **LaTeX direct-input:** separate shortcut button (typed LaTeX → same render→stroke pipeline, no OCR).

---

## 7. Font selection (decided candidates)

Requirements (spec): 10 fonts — 2 serif, 2 sans-serif, 6 handwriting; neat, clean, good
text+equation support, integrates with Android. Notein's `assets/font-licenses/` confirms several
are freely reusable (mostly **SIL OFL 1.1**).

### 7.1 Serif (2) — math/symbol coverage matters
1. **STIX Two Text** — OFL; full math glyphs for equations. *(owner-chosen)*
2. *(1 more serif — developer to choose.)*

### 7.2 Sans-serif (2)
1. **Noto Sans** — OFL; huge coverage. *(owner-chosen)*
2. *(1 more sans-serif — developer to choose; alt candidates: Roboto (Apache-2.0), Inter (OFL).)*

### 7.3 Handwriting (6)
Owner-chosen (3) + developer-chosen (3):
| Font | License | Notes |
|---|---|---|
| **Patrick Hand** | OFL | owner-chosen; clean print-style |
| **Short Stack** | OFL | owner-chosen |
| **Comic Neue** | OFL | owner-chosen |
| *(3 more handwriting)* | — | developer to choose from OFL handwriting fonts |

> License files present in Notein for: Caveat, Caveat Brush, Dancing Script, Edu AU VIC WA NT Hand,
> Great Vibes, Kalam, Pacifico, Patrick Hand, Playwrite US Modern/Trad → all reusable (verify each).
> Avoid: fonts without a clear OFL/Apache license (e.g., the ambiguous `Note this.ttf`).

Fonts ship as `.ttf` in `assets/fonts/`; equation text uses the serif-math families. "Convert OCR
text to native handwriting font" = render via `Typeface` into a textbox/strokes.

---

## 8. What remains TBD (owner-decided later)

1. `.thunder` final container fields (after prototype round-trip).
2. AI vendor + key storage (BYO key; online primary decided, vendor final TBD).
3. Which offline equation fallback (iink license vs heuristic).
4. Templates integration point (sidebar vs cover picker).
5. Exact font set confirmation + licensing sign-off (§7 candidates).
6. Cloud/premium — intentionally out of scope.

---

## 9. Recommended build/prototype order

1. **Skeleton + .thunder round-trip** (container, Room schema, save/load) — de-risks format.
2. **Ink canvas** on AndroidX Ink (pen/highlighter/eraser/lasso/shapes) + low-latency on Pad 2.
3. **Internal `InkInjector`** + paste flow (proves snip output path).
4. **Add Writing Space** (spacers + Fenwick) — the known-hard item.
5. **CRUD/folders/trash/search** on Samsung patterns.
6. **Snipping** engines one-by-one (Text → Code → Diagram → Equation) behind `SnipEngine`.
7. **Export** (PDF + `.thunder`), import, templates, minimap, plugins, theme inversion, settings.
8. UI polish against mockups.

---

## 10. Do we need more decompilation? — **No (optional only)**

Coverage is now complete for every planned subsystem:
- ink + format → Notein · data/CRUD → Samsung · engine/AI/export API → MyScript.

Optional donors **only if a specific gap appears**:
| App | Would add | When to bother |
|---|---|---|
| **Notewise** (Android) | modern ink + AI UX | if our AI/injection UX needs more reference |
| **Flexcil** (Android) | PDF annotation, lasso/snippets | if diagram/PDF-annotation features grow |
| **OneNote** | infinite canvas | spec says infinite canvas deferred — skip |
| **StarNote** (already staged) | unknown | only if a gap in note UI/UX |

**Recommendation: proceed to phase 4 (master prompt) now.** No further APKs required.

---

## 11. Next action

Finalize §7 fonts and §6 vendor choices (you decide the online provider + offline fallback), then
I draft the master prompt for GLM-5.3 from this plan + `ThunderNotesOpencode.md` + the three donor
READMEs + mockups.