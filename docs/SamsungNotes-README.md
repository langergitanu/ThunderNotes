# Samsung Notes Reverse-Engineering — Findings & Reuse Guide

Source: `$HOME/AndroidLab/apps/SamsungNotes` (a *copy*, not the official Play build)
Tools: apktool (`apktool/base/`), jadx (`jadx/base/sources/`)
Package: `com.samsung.android.app.notes`
Author: opencode analysis

---

## 0. TL;DR — Verdict

**This is the donor Notein should have been.** Samsung Notes is **far less obfuscated**
(18,594 classes readable under `com/`, only a handful of R8-renamed leftovers). Its entire
**data layer — DAOs, repositories, entities, save pipeline, recycle bin, search — is readable.**
This is precisely the CRUD/business logic that was hidden in Notein.

However, the **canvas/ink engine is proprietary**: Samsung uses the **SPen SDK**
(`com.samsung.android.sdk.pen.worddoc.SpenWNote`), which is not redistributable. So:
- **Copy from Samsung:** data model, folder tree, CRUD lifecycle, search, PDF/export repo patterns.
- **Do NOT copy from Samsung:** ink engine (use AndroidX Ink as per Notein analysis).

**Ghidra: still not needed.**

---

## 1. Where the readable code lives

The real notes library is `com.samsung.android.support.senl` (~5,405 files), especially:
- `senl/p148nt/data/` — **DB, DAOs, repositories** (the goldmine)
- `senl/p148nt/model/` — document model, save pipeline, constants
- `senl/p145cm/` — common/spen wrapper
- `senl/document/`, `senl/ntnl/` — document utilities

App shell: `com.samsung/android/app/notes/` (Activities, `MemoApplication`).
Obfuscation: most `com/*` readable; some classes renamed to `C#####a/b/...` but the important
ones kept real names. (`p148nt`/`p136ui`/`p607ui` are renamed numeric packages from jadx.)

---

## 2. HIGH-VALUE: Complete CRUD data layer (readable)

### 2a. Entities (`senl/p148nt/data/database/core/document/entity/`)
Fully readable Room entities — mirrors of what we need:
`NotesDocumentEntity`, `NotesContentEntity`, `NotesDocumentPageEntity`, `NotesStrokeSearchEntity`,
`NotesDocumentCoverEntity`, `NotesCategoryTreeEntity`, `NotesCategoryTreeClosureEntity`,
`NotesOrganizedDocumentEntity`, `NotesMappedDocumentEntity`, `NotesTemplateEntity`,
`NotesTagEntity`/`NotesHashtagEntity`, `NotesRetryEntity`, `DocumentThumbnailEntity`,
`DocumentExtraEntity`, `StickyNoteEntity`, `SuggestedCoverEntity`, `ExternalSharedDocumentEntity`.

### 2b. DAOs (`.../database/core/document/dao/`) — all readable
`NotesDocumentDao`, `NotesContentDao`, `NotesDocumentPageDao`, `NotesCategoryTreeDao`,
`NotesCategoryTreeClosureDao`, `NotesRecycleBinDocumentDao`, `NotesRestoreDao`, `NotesSearchDao`,
`NotesStrokeDao`, `NotesTemplateDao`, `DocumentThumbnailDao`, `NotesTagDao`, `NotesHashtagDao`,
`CoverTemplateDao`, `ExternalSharedDocumentDao`, plus `*_Impl` generated Room impls (include the
exact SQL — extremely useful).

### 2c. Repositories (`.../data/repository/`) — the execution layer Notein hid
- `document/NotesDocumentRepository.java` (420 lines) — create/insert/delete/move
- `document/NotesRecycleBinRepository.java` — trash lifecycle
- `document/NotesContentRepository.java`, `NotesDocumentPageRepository.java`,
  `DocumentThumbnailRepository.java`, `DocumentTempDataRepository.java`
- `category/*` — folder CRUD
- `pdfwriter/PDFWriterRepository.java` — uuid → export file mapping
- `search/NotesSearchRepository.java`, `SemanticSearchRepository.java`, `page/*`
- `restore/`, `converted/`, `sync/`, `recognition/`
- `data/impl/repository/*` — implementation layer

### 2d. Key lifecycle methods found (copy these patterns)
- **Create:** `DocumentInitializer.initNewNote(SpenWNote)` — sets page template, default height,
  appends `DEFAULT_PAGE_COUNT = 2` pages, page background.
- **Insert:** `NotesDocumentRepository.insert(NotesDocumentEntity)` / batch insert.
- **Soft delete / Trash:** `moveToRecycleBinByUuid(...)`, `updateRecycleBinTimeMoved(uuid, time)`,
  `getExpiredRecycleBinDataList(time)` — recycle-bin with expiry.
- **Restore:** `restoreByUuids(...)`, `restoreByUuidWithSyncTimestampUpdate(...)`.
- **Sync-aware deletes:** `deleteByUuidWithTimestampIncrease(...)` — every mutation bumps a
  timestamp for cloud sync reconciliation (a strong pattern to adopt for our future sync).
- **Save pipeline:** `model/document/save/` → `DocumentDBSaver`, `NotesDocumentOperationProcessor`,
  `PageThumbnailSaver`, `legacy/SaveNoteContentsResolver`, `autotitle/DocumentAutoTitleCreator`,
  `LinkTitleCreator`, `converter/`.

---

## 3. HIGH-VALUE: Folder hierarchy via a CLOSURE TABLE

Samsung stores folders as a **category tree + closure table** — the standard efficient pattern for
arbitrary-depth trees and subtree queries:
- Tables: `category_tree` + `category_tree_closure` (ancestor, descendant, depth).
- `NotesCategoryTreeDao`: `insertFolder(...)`, `insertCategoryClosure(...)`,
  `changeParentUuid(...)` (move), `deleteAllCategorySubTree(uuid, ...)`,
  `deleteCategoryTreeClosureSubTree(...)`, `getChildrenUuids`, `getChildrenCount`,
  `getParentsCategoryUuidList`, `findUuidListByDisplayNameIncludeDeleted`, and a raw SQL query
  joining `category_tree` with `category_tree_closure` to compute depth and document counts.
- `NotesFolder`, `DocumentCategoryTree`, `NotesCategoryTreeSortUtils` — in-memory helpers.

**Adopt this** for ThunderNotes folders — it is scalable, handles move/rename/subtree delete, and
supports cheap "all notes under folder X" queries.

---

## 4. HIGH-VALUE: Search architecture

Samsung does **content + handwriting/OCR search**, not title-only:
- `NotesSearchDao` + `NotesDocumentTextSearchEntity`, `NotesPageSearchInfoEntity`,
  `NotesStrokeSearchEntity`, `NotesHashtagContentEntity`
- `NotesPageSearchInfoRepository`, `NotesSearchRepository`, `SemanticSearchRepository`
- `MainListDocumentData`, `DocumentTextData` projections; `rawQuery(SupportSQLiteQuery)` for
  flexible searching.

Even though **our spec is title-only search**, this is the reference design if we later add
content/OCR search (Notein also had it). Keep the separate search-table pattern in mind.

---

## 5. Document model constants / ratios

`senl/p148nt/model/base/document/DocumentConstants.java`, `DocumentPageRatio.java` — page
ratio/size logic (A4/portrait/landscape), `SpenWNote.PageMode.SINGLE/LIST`, template type IDs
(e.g. type 16 = no template, 12 = special invert). Useful for our page-type (blank/lined) and
orientation (portrait/landscape) feature.

---

## 6. NOT reusable — proprietary components

| Component | Why not |
|---|---|
| **SPen SDK** (`com.samsung.android.sdk.pen.*`, `com.samsung.android.spen.*`, `SpenWNote`, `SpenWPage`) | Samsung proprietary; not redistributable. Use **AndroidX Ink** instead. |
| Samsung **SCloud** sync, **Microsoft Graph** sync | Samsung/MS account specific (`app/notes/sync/...`) |
| Samsung add-ons (`senl/addons/*`), S Note converter | Samsung ecosystem only |
| Samsung docscan / OCR | decoder tied partly to Samsung services |
| OEM windowing tricks | device-specific |

Note the **contrast**: Notein = AndroidX Ink (portable, open) + obfuscated business logic.
Samsung = clean business logic + proprietary SPen engine. **The two are complementary** — take the
engine approach from Notein, the data/CRUD architecture from Samsung.

---

## 7. Ghidra — still not recommended

Samsung's native libs are SDK/SoC infra (SPen native, document parser, OCR/ML). Their algorithms
are not needed: we use AndroidX Ink for ink and standard OSS for the rest. No binary here changes
our build plan. **Skip Ghidra.**

---

## 8. Combined reuse plan (Notein + Samsung)

| Concern | Source of truth | Action |
|---|---|---|
| Ink engine / stylus feel | **Notein** → AndroidX Ink 1.1.0+ | add AndroidX Ink dependencies |
| Stroke serialization | **Notein** (`InkStrokeProtoSerializer`) | design our protobuf schema from it |
| Document DB schema | **Samsung** entities + Notein entities | design our Room schema |
| Folder tree | **Samsung** closure table | adopt directly (rewrite) |
| CRUD / repositories | **Samsung** `senl/p148nt/data` | rewrite cleanly in our architecture |
| Trash / restore / expiry | **Samsung** recycle-bin repos | adopt pattern |
| Sync timestamps | **Samsung** timestamp-increase pattern | adopt for future cloud sync |
| AI / OCR | **Notein** `service/ocr`, `service/p604ai` | mirror request/response, pluggable engine |
| Diagram tracing | **Notein** → OpenCV | use OpenCV Android SDK |
| PDF export | **Notein** → Pdfium/PDFcpu; **Samsung** `PDFWriterRepository` | Pdfium + repo mapping pattern |
| Search | **Samsung** search tables | start title-only; keep design for content search |
| Fonts / covers / brushes | **Notein** assets | use only open-licensed assets |

---

## 9. What this changes in our spec

1. **Confidence up:** we now have readable, production-grade references for the exact CRUD that was
   previously unknown (create note/folder/bookmark, trash, restore, move, search).
2. **`.thunder` format:** combine Notein's protobuf-stroke container idea with Samsung's
   page/content/stroke entity model.
3. **Folder storage:** plan on a closure table from day one (avoids painful migration later).
4. **Sync-ready design:** include per-row timestamps now even though cloud is future.

---

## 10. Next recommended step

We now have enough for **phase 3 (architecture planning)** — no more decompilation strictly needed.
Optional donors later (only if a specific gap appears):
- **MyScript** — if we want an alternative handwriting/equation recognition reference.
- **StarNote / Notein** — already covered.
- A real exported **`.notein`/Samsung `.sdocx`** sample to lock the `.thunder` layout.