# ThunderNotes — `.thunder` Format & Live External Stroke Injection

Companion to `$HOME/AndroidLab/apps/Notein/native_exports/README.md` (format analysis).
Author: opencode

---

## Part A — `.thunder` format (decided from Notein `.in`)

Adopt Notein's proven shape with fixes:

```
note.thunder  (ZIP)
├── manifest.json      # format_version, app_version, schema_version, note meta, checksum
├── note.sqlite        # Room DB (tables: NoteContent, Page, Stroke, Shape, TextBox,
│                      #   Image, Outline, Comment, HyperLink, PageLayer, PdfInfo, …)
├── assets/            # images, pdf, audio, thumbnails — EMBEDDED (relative paths)
└── preview.png
```

- **Strokes = AndroidX Ink `InkStrokeProto` bytes** (BLOB). This is portable and matches the
  modern path in Notein. Optional `record_json` fallback for non-protobuf producers.
- Every content row keeps a **bounding box** (`left/top/right/bottom`) as a spatial index.
- **Fixes vs Notein:** embed assets (self-contained), readable JSON keys, explicit version +
  checksum manifest, schema migrations.

Benefits: inspectable, debuggable, streamable, and — crucially — **injectable** (Part B).

---

## Part B — Can live external pen-stroke injection work without root? **YES**

### Why it failed in Notein (root cause)
Your edit-and-reimport worked because import was a **batch** operation on a closed file. Live
injection failed because:
1. The running app holds the note DB open in **private storage** and keeps note content in memory;
   it never re-reads the file, so an on-disk edit is invisible until restart.
2. Notein has **no external API** to receive new strokes. Other apps cannot write its private files
   without root anyway.

It failed by app design, **not** because Android/root makes it impossible. Since *we* are building
ThunderNotes, we control the app and can make injection a first-class feature — **no root needed.**

### Two independent capabilities to build

**B1. File-level "watch & reload" (works with no code in the injector)**
- Keep `.thunder` importable while the app is open.
- **Do not hold an exclusive lock**: copy/import into a staging file, then open a snapshot; watch the
  source with `FileObserver` (or a low-frequency poll) on a public app dir (e.g.
  `/Android/data/com.thundernotes/files/import/`).
- On change → parse the new DB, **diff and merge** strokes into the live document (by stroke id),
  then redraw. This reproduces your Notein experiment *while the note is open*, no root.

**B2. IPC injection (clean, real-time, recommended)**
Expose a documented, **permission-protected** ingress so an external tool/PC/plugin can push strokes
directly into the running app:
- A **foreground `BroadcastReceiver`/`Activity` intent**:
  `com.thundernotes.action.INJECT_CONTENT` carrying a protobuf/JSON payload
  (`page_id`, `layer_id`, stroke blob, or textbox). Guard with a signature/`normal` permission.
- Or an **AIDL bound service** / **ContentProvider** for richer two-way control.
- Or **ADB for development**: `adb shell am start -a com.thundernotes.action.INJECT_CONTENT ...`
  (works on debug builds without root) — ideal for automated testing.
- Since our spec already mandates **PLUGINS**, expose the same as a `PluginApi.injectStroke(...)`.

### Internally (trivial in our own app)
We own the canvas + DB, so an internal `InkInjector` just:
1. builds a `Stroke` from an `InkStrokeProto` blob,
2. writes `StrokeEntity` (+ bounds),
3. invalidates the page and schedules a redraw.
External tools reach this through B2 (or B1). This directly satisfies the spec requirement
*"canvas optimized to support dynamic injection of pen strokes and text boxes externally."*

### Android storage note
Other apps still can't touch our private DB. That is fine: injection goes **through an interface we
provide**, not through filesystem access. File-level B1 uses a **shared app-external dir** we expose
for importing. No root, no system modification, fully within normal Android rules.

### Recommendation
Build **B2 (intent/AIDL/plugin) as the primary path** and **B1 (watch-&-reload) as the file-based
fallback**. Together they fully solve the problem you hit in Notein.

---

## Part C — "Add Extra Writing Space" (the tricky one)

### Reference implementations (from decompiled code)
- **Notein** does NOT shift strokes. It keeps `unboundedNote` + note-level
  `unbounded_page_offset_x/y` (`NoteContentEntity`) and a per-page `PageOffSet{offset_x, offset_y}`
  (`page_offset_json`; `UpdatePageOffSetEntity`). Rendering applies offsets; content stays put.
- **Samsung** offers `SpenWNote.PageMode.SINGLE` (one long page) and `insertPage(index)` — it
  inserts blank pages / extra page height, it does not reflow existing strokes.
- **Conclusion: nobody moves strokes.** Moving every stroke below an insertion point is O(strokes)
  and is exactly what "hangs on large files". Both vendors avoid it.

### Recommended model for ThunderNotes: page-local coords + spacers (never move strokes)

1. **Coordinates are page-local and immutable.** A stroke's stored `x/y` never changes when space is
   added/removed. This also keeps live injection cheap (an INSERT, never a global reflow).
2. **Space is metadata, not geometry.** Add a `SpacerEntity` (or a `spacers` JSON column on
   `NoteContentEntity`): `{ id, anchor_page_id, offset_in_page, height }`.
   - Global render Y of a point = `pageLocalY + cumulativePageOffset(page) + Σ(spacer heights above it)`.
3. **Cumulative offsets via prefix sums.** Store spacers in order; maintain a **Fenwick tree (BIT)**
   in memory keyed by gap index. Insert/remove space = `O(log N)`; query a page's offset =
   `O(log N)`. 1000+ pages stay instant.
4. **Redraw only the viewport.** Rendering cost is `O(visible strokes)`, independent of document size.
5. **Reversible & bakeable.** Removing space deletes a spacer. For export, optionally bake cumulative
   offsets into the output. This matches Notein's `PageOffSet` idea but generalized to arbitrary gaps.

### Interaction with our other features
- **Live stroke injection / snip-paste:** just `INSERT` a `StrokeEntity` with page-local coords — no
  interaction with spacers at all. ✅ (This is why the offset model matters.)
- **Infinite-canvas feel (no gap + dotted separator):** spacers naturally create the extra gap; pages
  still have zero base gap and a dotted separator.
- **Vertical/Horizontal Shifter (lasso):** a transform on selected items only — unrelated.
- **Theme toggle:** operates on colors, not geometry — unaffected.

### Verdict
**Yes — the `.thunder` (ZIP + SQLite) blueprint handles it cleanly**, provided we (a) keep page-local
coordinates, (b) store space as spacer/offset metadata, and (c) use a Fenwick prefix-sum for offsets.
No "cunning exotic structure" is needed beyond this; it is the standard rope/piece-table idea applied
to page offsets. This satisfies the "must not hang below 1000 pages" requirement.

---

## Part D — LaTeX → Pen-Stroke Pipeline (equation snip + LaTeX direct-input)

**Chosen method (single path, shared with the Diagram-Snip tracer):**

```
LaTeX ──> KaTeX render (offscreen WebView, OFFLINE assets, black-on-white, high-DPI)
      ──> bitmap ──> centerline tracer (OpenCV: binarize → skeletonize → junction graph → polylines)
      ──> AndroidX Ink strokes (ballpoint brush, width measured in cm, base color black)
      ──> clipboard GROUP (relative coords + bbox)
      ──> paste → InkInjector → StrokeEntity rows → canvas
```

### Why this method (and why NOT outline extraction)
- A rendered glyph is a **filled outline**, not a pen stroke. The pen stroke we want is the
  **centerline of that fill** — which is exactly what skeletonizing the rasterized glyph yields.
  Flattening the SVG outline would draw the glyph's *border*, not the glyph (a subtle, bad bug).
  So: rasterize at high DPI → skeletonize. One method, one code path (reused from Diagram Snip).

### Sync with the native `.thunder` format (Part A) — no schema changes
1. Every stroke is an **ordinary `StrokeEntity` row**: `ink_stroke_blob` = our `InkStrokeProto`
   bytes (brush family/size/color/epsilon + points + 5-float attrs + matrices) with
   `left/top/right/bottom` bounds — identical to hand-drawn strokes.
2. The pipeline produces **androidx.ink `InkStroke` objects**; serialization to `InkStrokeProto`
   is handled by the existing serializer (Part A). Nothing new to invent.
3. **No new tables, no migrations, no format-version churn** — pasted equations load/export,
   render, erase, lasso, minimize, and PDF-export through the existing paths with
   **zero special-casing**.
4. `layer_id` = the active layer; coordinates are page-local, matching Part A's model.
5. Theme inversion works for free: strokes carry **base color black**; the canvas theme toggle
   (§6.10 Row 1 right) inverts presentation exactly as it does for hand-drawn strokes.

### Rendering & tracing rules (bug-avoidance)
- **Always render black-on-white**, independent of canvas theme, at high DPI (line height
  ≥ ~96–128 px) so skeletonization is stable and glyph width is measurable.
- **Stroke width:** skeletonize, then per branch take the distance-transform maximum →
  width = 2×DT (px) → cm via render DPI → `brush_size`. Quantize widths to a few buckets
  (thin/normal/thick) so blobs stay compact and InkBrush sizes stay sane.
- **Spur cleanup:** drop skeleton branches shorter than ~2–3 px; optionally merge collinear
  segments (angle deviation < ~10°) across junctions to avoid over-segmentation.
- **Determinism:** strokes emitted row-major (reading order) with monotonic timestamps —
  the same LaTeX always yields the same stroke set (stable tests, stable diffs).
- **Point downsampling:** keep ~1 point per 2–3 px so blobs stay small on 100-page docs.
- **Multi-line LaTeX:** render the whole `aligned` block, trace as one group; row order is
  preserved by the tracer's row-major emission.
- **Failure handling:** KaTeX parse error → surface error, do not inject. WebView failure →
  fallback renderer (Android JLaTeXMath port) for the bitmap. Tracer failure → last-resort only:
  insert the rendered bitmap as an `ImageEntity` (never silently drop content).

### Performance
- Whole-equation trace ≈ **10–80 ms on CPU** (OpenCV on a small crop); fully offline; no network.
- Re-render after a LaTeX edit re-runs the same idempotent path — the §7.3 edit-and-re-render
  loop stays instant.

---

## Part E — What to do next

1. ✅ Format analysis complete — `.thunder` can mirror `.in` with the fixes above.
2. Architecture phase (phase 3) should include:
   - the `.thunder` ZIP+SQLite+protobuf-stroke design in the master prompt;
   - the `InkInjector` internal API + one external ingress (intent and/or AIDL/plugin);
   - the watch-&-reload import path.
3. Reverse-engineering is now **sufficient**. No more decompilation required unless a specific gap
   appears. (Optional: a real exported PDF to validate the PDF-export pipeline.)
4. Proceed to craft the master prompt for GLM-5.3.