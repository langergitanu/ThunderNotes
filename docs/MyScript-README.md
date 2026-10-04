# MyScript Nebo Reverse-Engineering — Findings & Reuse Guide

Source: `$HOME/AndroidLab/apps/MyScript` (base + split arm64 + xxhdpi)
Package: `com.myscript.nebo`. Tools: apktool, jadx. ~31,045 classes. Author: opencode

---

## 0. TL;DR — Verdict

**Best *architecture* donor so far; useless as an *algorithm* donor.** Nebo is a thin Java/SWIG
layer over a **proprietary native engine**. Every hard algorithm (handwriting recognition, equation
recognition, reflow, shape/gesture analysis, prediction) lives in C++ `.so` — **not extractable via
jadx, and not worth Ghidra.** But the Java integration layer is clean and readable, giving us
excellent reference designs for: engine facade API, tool/palette model, export options, AI feature
flow, the reflow (add-space) interface, clipboard/injection via ContentProvider, and paid gating.

**This is a reference donor, not a code donor.** No new native analysis is justified.

---

## 1. Architecture

- UI/business: readable, named packages (`com.myscript.nebo.*`).
- Engine: `com.myscript.snt.core` (156 classes) = SWIG-generated wrapper over `libNebo.so` +
  `NeboEngineJNI`. This is Nebo's "Smart Note Technology" (SNT) — an iink-derived stack.
- Native libs (`apktool/split_arm64/lib/arm64-v8a/`): `libMyScriptEngine`, `libMyScriptMath`,
  `libMyScriptText`, `libMyScriptShape`, `libMyScriptGesture`, `libMyScriptInk`,
  `libMyScriptDocument`, `libMyScriptAnalyzer`, `libMyScript2D`, `libMyScriptPrediction`,
  `libNebo`, `libPDFNetC` (PDFTron). **All proprietary.**
- Recognition models: `assets/resources/*.res` in iink `.res` bundle format
  (`math/math-ak.res`, `math/math-grm-standard.res`, `gesture/gek-standard.res`,
  `shape/shk-diagram.res`, `analyzer/ank-*.res`) + `conf/{math,gesture,diagram,raw-content}.conf`.

---

## 2. Equation recognition (your snipping interest)

- **Native + license-gated.** The engine does handwriting→math. Java only configures it:
  `conf/math.conf` loads `math-ak.res`/`math-grm-standard.res`; `MimeType` includes
  **`MATHML`** and **`LATEX`**, so the engine exports recognized equations as MathML/LaTeX.
- `NeboExporter.exportPages(pages, ..., Json jiix, ...)` and `exportRaw(...)` — exports via a
  **JIIX** (JSON Ink Interchange) writer (`NeboJiixWriter`), plus formats: `TEXT, HTML, MATHML,
  LATEX, SVG, PNG, PDF, DOCX, NEBO`.
- **Conclusion:** the equation-OCR *algorithm* is not recoverable, and we don't need it — our spec
  uses a **pluggable AI OCR → LaTeX** engine. What we CAN reuse is the **pipeline/interface shape**
  (below), which is clean and worth mirroring.

---

## 3. "Add extra writing space" — how Nebo does it

- `ReflowSession` + `ReflowOptions` + `IReflowSessionListener` in `snt/core`.
  - `ReflowOptions.setEnableStacking(boolean)`.
  - Listener callbacks: `reflowUpdated(Extent)`, **`reflowInsertRejected(Extent)`**,
    `reflowInkRejected(int, List<PendingStroke>)`, `reflowTypesetRejected(List<String>)`.
- So Nebo *does* physically reflow — **in native code** — and it explicitly **rejects** reflow when
  it can't do it cheaply. That confirms our earlier design decision: reflow is expensive; the
  metadata **Spacer + Fenwick prefix-sum** approach (see `thunder-format-proposal.md` §C) is the
  right, non-hanging choice for ThunderNotes. Use Nebo's listener names as an API reference.

---

## 4. High-value **reference designs** (mirror these patterns)

1. **Engine facade API** — `NeboEngine`, `DocumentController` (`save`, `saveToTemp`,
   `lastOpenedPage`, `getTemporaryPageController`), `PageController`, `RecoContext`,
   `DisplayContext`, `PageKey`/`NotebookKey`. Clean separation of document vs page vs reco vs
   display. **Adopt this layering** for our engine wrapper.
2. **Tool/palette model** — `ToolType`, `ToolConfiguration`, `ToolProperty`/`ToolPropertyExt`,
   `ToolState`, `ToolbarConfiguration`, `ToolbarController`. Tool options are **string-keyed
   properties** (e.g. pen stroker `felt`/`fountain-pen`, eraser `policy` stroke/precise,
   `dynamic radius`, lasso shape polygon/rectangle, pressure sensitivity, thickness ratio). Great
   blueprint for our penset/palette/data model.
3. **Export** — `ExportOptions` (flatten, combine-as-one-document, include-annotations,
   include-background-color, page-index filter, available MIME types) and `MimeType` enum
   (`PDF`, `DOCX`, `SVG`, PNG, HTML, TEXT, MathML, LaTeX, `NEBO`). Use as the model for our
   PDF + `.thunder` export UI/API.
4. **AI features** — `com.myscript.nebo.ai` (Compose): Explain, Summarize, Quiz (multi-choice /
   true-false), streaming text cards, `promptForQuizMode`, `summarizeText`. The prompt-building and
   streaming UI **is in Java/Compose** (remote LLM calls) — directly relevant to our AI-snip
   integration UX.
5. **Clipboard / injection** — `com.myscript.nebo.clipboard.NeboClipboardContentProvider` +
   `ClipboardContentContract` expose internal content via a **ContentProvider**. Excellent precedent
   for our external **injection** ingress (see `thunder-format-proposal.md` §B) — a provider is a
   clean, permission-scoped alternative to raw intents.
6. **Paid gating** — `com.myscript.nebo.freemium.*` + **RevenueCat** SDK
   (`InAppPurchaseController`, `PurchaseController`, `FreemiumBusinessModelStatus`,
   `FreemiumLimitationDialog`). Gating is **client-side entitlement checks**; but the engine
   features are *also* license-locked inside native code. Confirms the two-layer gating model.

---

## 5. Assets worth noting

- `assets/resources/*.res` — iink recognition bundles (proprietary; not reusable).
- `assets/fonts`, `assets/notebooks`, lottie JSONs (`convert.json`, `shaped.json`, `scratch.json`).
- `assets/VODB/8.11.0.708/resources/en_US/` — voice/text database resources.
- `assets/conf/*.conf` — analyzer configs (math/gesture/diagram/raw-content) — readable and
  informative about how iink wires analyzers.

---

## 6. Ghidra? — **No.**

The engines are large, optimized, commercial C++ with license checks; recovering usable algorithms
is impractical and unnecessary. We already have (a) AndroidX Ink for ink, (b) an AI-OCR plan for
equations, and (c) a metadata-spacer plan for writing space. **Skip Ghidra.**

---

## 7. What (if anything) to copy for ThunderNotes

| Need | Take from MyScript? |
|---|---|
| Equation/ink recognition algorithm | ❌ native; use AI-OCR plan instead |
| Engine facade layering (doc/page/reco/display) | ✅ mirror the pattern |
| Tool/palette string-keyed property model | ✅ mirror |
| Export options + MIME model | ✅ mirror (PDF + `.thunder`) |
| AI streaming UX (summarize/quiz) | ✅ reference for our AI panes |
| Clipboard ContentProvider as injection ingress | ✅ strong precedent |
| Freemium/RevenueCat gating | ➖ not needed (our app is free) |
| `.res` models, native libs | ❌ proprietary |

**Bottom line:** MyScript is the reference for *how to structure* the engine-facing and AI-facing
layers — not a source of algorithms or copy-paste code. Reverse-engineering is now complete:
Notein (ink + format), Samsung (CRUD + folder tree), MyScript (engine/AI/export API design).
