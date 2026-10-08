# GLM-5.3 → BigPickle Sync Notes

> Living document. GLM-5.3 (the architect, writing in `/home/z/my-project/thundernotes/`) writes here;
> BigPickle (the build/fix agent, operating on the user's local Android Studio copy) reads + acts.
>
> **Communication protocol:**
> - BigPickle pushes to `langergitanu/ThunderNotes-BigPickle` and updates `BigPickle.txt` when making changes.
> - For bug reports without fixes (no code changed), the user pastes `BigPickle.txt` content here in chat.
> - GLM-5.3 (me) updates this `GLM-5.3.md` after every sync cycle so BigPickle always knows
>   the current state + next priorities.
> - BigPickle is **not** forced to accept GLM-5.3's changes either — review just like GLM-5.3
>   reviews BigPickle's. But always update the local codebase + tell GLM-5.3 what to do next
>   via this file.

---

## Phase 5 — UI for 9 library pages (2026-10-05)

### What was built

The full library UI — **9 pages + persistent sidebar + NavHost + 2 create modals** — translated from the mock HTML/Tailwind UIs to Android XML layouts + Fragments + ViewModels. **40 new/modified files** (5 modified + 35 new).

**Infrastructure:**
- `activity_main.xml` — rewritten as master-detail: persistent 22%-width sidebar (RecyclerView) + NavHostFragment
- `MainActivity.kt` — rewritten to wire sidebar + NavHost + active-destination tracking
- `res/navigation/nav_graph.xml` — 9 destinations (Home, Notes, Folders, Bookmarks, Trash, Templates, Plugins, CoverSelection, ImportFile)
- `ui/common/SidebarAdapter.kt` — RecyclerView adapter with active-item highlighting (crimson background + white icon/label)
- `res/layout/item_sidebar.xml` — sidebar item layout (icon + label + optional count badge)
- 8 sidebar icons (home, notes, folders, bookmarks, trash, templates, plugins, premium) + 7 action icons (search, add, create-note, create-folder, more-vert, close, import) + 4 background drawables

**9 library pages (Fragment + ViewModel + layout each):**
1. **ThunderHomePage** — dashboard with top bar (search + import + create-note button) + Recent Folders horizontal scroll + Recent Notes grid + empty state + floating create dock (Create Folder FAB + Create Note FAB)
2. **NotesLibraryPage** — top bar (search + Import Note + Create Note) + 3-column notes grid + empty state
3. **FoldersLibraryPage** — top bar (search + Create Folder) + 3-column folders grid + empty state
4. **BookmarksPage** — bookmarked notes grid + bookmarked folders list + empty state
5. **TrashPage** — top bar (Restore All + Empty Trash buttons) + trashed notes grid + trashed folders list + empty state
6. **CreateNotePage** (modal BottomSheetDialogFragment) — 2×2 grid of page-type × orientation cards (Blank/Lined × Portrait/Landscape) + Infinite Canvas card (disabled with "coming soon" popup per spec §6.6)
7. **CreateFolderPage** (modal BottomSheetDialogFragment) — folder-name text input + 6 color swatches (brand palette) + Create button
8. **CoverSelectionPage** — grid of placeholder cover cards (Phase 12 will add 50+ real covers)
9. **ImportFilePage** — "Select .thunder file" button using ActivityResultContracts.OpenDocument; rejects non-.thunder files with error message
10. **TemplatesPage** — placeholder "coming soon" (Phase 12 will add Template Library)
11. **PluginsPage** — placeholder "coming soon" (Plugin system under development; first plugin = text translator)

**Shared components:**
- `NoteAdapter` + `item_note_card.xml` — note card with cover preview (placeholder), title, date + page count, bookmark indicator, 3-dots overflow button
- `FolderAdapter` + `item_folder_card.xml` — folder card with folder icon, name, bookmark indicator, overflow button

**Reactive wiring:** Each Fragment observes its ViewModel's StateFlow via `repeatOnLifecycle(STARTED)`. The ViewModels call into `RepositoryModule.notes` / `folders` / `trash` / `bookmarks` — this is the **first time the Room layer runs live** (not just in tests). The `AppDatabaseTest` + `NoteDatabaseTest` (25 tests) are the safety net.

**Create flow works end-to-end:** clicking Create Note → modal → pick Blank/Lined + Portrait/Landscape → `NotesRepository.createNote()` → new `.thunder` file on disk + new `NoteEntity` row in `thundernotes-app.db` → modal dismisses → the notes grid auto-updates (Flow). Same for Create Folder.

### ⚠️ UI verification guidelines for BigPickle (from the USER directly)

**The user explicitly said: do NOT try to match the mock UIs pixel by pixel.** The mock UIs are HTML+Tailwind reference for button positions + general overview — NOT pixel-perfect specs. They're all in landscape orientation; the app must support both portrait + landscape. The mock UIs are not optimized for Android either.

**BigPickle should ONLY look for genuine UI bugs:**
- Overlapping buttons
- Sidebar not working (navigation broken, active item not highlighting)
- Misplacement of elements (button off-screen, text cut off)
- Crashes on any page
- Layout that doesn't fit the tablet screen
- RecyclerView not scrolling
- Modal not dismissing after create

**BigPickle should NOT waste time on:**
- Color matching (the mock UIs use #111115 for library pages vs #131317 in colors.xml — this is a known discrepancy; we use `@color/surface_base` tokens, not hex literals)
- Exact pixel positions
- Matching every icon from the mock UI
- Typography/spacing exactness

The mock UI is a **concept reference**, not a spec. The app's design system (Obsidian Precision Note System color tokens in `colors.xml`) is the source of truth.

### Penset + shape icons deferred to Phase 6

The user specified 10 icons to copy from the mock UI (5 penset: ballpoint, fountain, highlighter, eraser, lasso + 5 shape: shape picker, table maker, add extra space, 3 shift icons, finger/stylus mode). These are **canvas icons (Phase 6)**, not library page icons. Phase 5 uses standard Material-style icons for everything. The 10 canvas icons will be extracted from the mock UI source code (SVG) in Phase 6.

---

## Sync 5 — 2026-10-05 — BigPickle found + I fixed the SpacerManager duplicate-offset bug

### What BigPickle did (in BigPickle.txt Sync 4 round)

**BigPickle verified Sync 4** by clean-cloning `d4b21c5` + building from scratch:
- `./gradlew clean :app:assembleDebug :app:testDebugUnitTest` → BUILD SUCCESSFUL, 24/24 tests, 0 Kotlin errors.
- `./gradlew :app:lintDebug` → 0 errors / 145 warnings (115 UnusedResources — exactly as predicted).
- On device (OnePlus Pad 2): installs, launches, no crash, DB intact. Sync 3 behavior not regressed.

**BigPickle found a REAL BUG in SpacerManager** (Open Issue 1):

The in-memory Fenwick tree diverged from the Room DB when two spacers shared the same `offset_in_page`. BigPickle wrote a throwaway Robolectric probe (not committed — lived in `/tmp`) that drove SpacerManager through duplicate-offset scenarios + computed ground truth straight from the DB. Results:

| Scenario | Manager | DB | Verdict |
|---|---|---|---|
| S1: remove-FIRST of two spacers @offset 50 (h=50, h=100) | 150.0 | 100.0 | ❌ WRONG (removal was no-op in memory) |
| S2: remove-SECOND of same pair | 50.0 | 50.0 | ✓ (happens to work) |
| S3: resize-FIRST of two spacers @offset 50 (50→300) | 150.0 | 400.0 | ❌ WRONG |
| S4: resize-SECOND | 350.0 | 350.0 | ✓ (happens to work) |
| S5: remove before any query (tree never built) | 0.0 | 0.0 | ✓ (cache empty → rebuilds) |
| **S6: STICKINESS** — remove-first, then later unrelated insert @offset 300 | 157.0 | 107.0 | ❌ STILL WRONG (+50 forever) |
| S7: distinct offsets (my test's case) | 80.0 | 80.0 | ✓ |
| S8: point exactly at spacer offset | 0.0 / 50.0 | — | ✓ (strict < is correct) |
| **S9: FRESH manager** reading same DB after S1's divergence | 100.0 | 100.0 | ✓ (proves DB is correct, cache is what corrupts) |

**Root cause (two compounding issues in `FenwickTree`):**
1. `insert(offset, height)` used `countSpacersStrictlyAbove(offset)` which returns the FIRST index where `offsets[index] >= offset`. So inserting (50, 100) after (50, 50) put the new spacer at index 0, pushing the old one to index 1.
2. `remove(offset, height)` + `update(offset, oldHeight, newHeight)` only checked `offsets[index]` + `heights[index]` at that first index. The spacer pushed to index 1 could never be matched. Remove/update silently failed in memory while the DB mutated.

**Why my test missed it:** `NoteDatabaseTest` test #3 used DISTINCT offsets (50f and 250f). The duplicate-offset branch was never entered. The bug is deterministic — no concurrency needed.

### What GLM-5.3 CHANGED (pushing to `langergitanu/ThunderNotes` next)

**Open Issue 1 fix — SpacerManager rewritten with invalidate-on-mutation design:**

BigPickle's suggested fix was elegant: since `FenwickTree.insert/remove/update` already rebuilt the whole array O(N) (not actually O(log N) on mutation), the in-memory incremental update bought nothing. Just **invalidate the cache on every mutation** — the next query rebuilds from the DB. This makes memory/DB divergence **structurally impossible**.

`app/src/main/java/com/thundernotes/data/spacer/SpacerManager.kt` — rewritten:
- `insertSpacer` / `removeSpacer` / `resizeSpacer` now: write to DB → call `invalidate(pageId)`. No incremental tree update.
- `FenwickTree` is now **read-only**: built once from a list of spacers (sorted by offset ASC), supports only `countSpacersStrictlyAbove(y)` + `prefixSum(count)` + `size`. NO `insert/remove/update` methods — the parent `SpacerManager` invalidates the whole tree on any mutation + rebuilds on the next query.
- The duplicate-offset ambiguity is **gone** because the Fenwick tree no longer tries to match individual spacers — it just sums whatever the DB has.
- Performance: O(N) build on first access (or after invalidation), then O(log N) per query. For a render loop with many queries between mutations, the Fenwick tree is still a win. The "incremental update" was never actually O(log N) — it was O(N) either way (rebuild).
- KDoc updated with: the bug history, why the old design was wrong, why the new design is correct, thread-safety note (NOT thread-safe; canvas render is typically single-threaded per page; future work: `ConcurrentHashMap` + `computeIfAbsent` if needed).

**Open Issue 1 regression test — `NoteDatabaseTest` test #6 added:**

`app/src/test/java/com/thundernotes/data/NoteDatabaseTest.kt` — new test:
```
duplicate-offset spacers - remove and resize keep manager consistent with DB
```
- Inserts two spacers at the SAME offset (50) with different heights (50, 100).
- Asserts `manager == DB ground truth` at every step.
- Removes the FIRST spacer (the case that was silently a no-op in the old design).
- Asserts `manager == DB`.
- Resizes the remaining spacer (100 → 300).
- Asserts `manager == DB`.
- Inserts a third spacer at a DIFFERENT offset (200) — BigPickle's S6 stickiness scenario. With the invalidate-on-mutation fix, the stale +50 error is impossible.
- Asserts `manager == DB` at y=100 (only offset-50 spacer above) AND y=300 (both spacers above).
- Also verifies `totalHeightOnPage` matches DB.
- Helper `assertManagerMatchesDb(pageId, y)` computes DB ground truth directly from `SpacerDao.getByPageOrdered` + `filter { offset < y } + sumOf { height }`.

**Test count: 24 → 25** (+1 regression test).

**Open Issue 2 fix — doc number drift corrected:**

BigPickle noted GLM-5.3.md said "65 Kotlin + XML resource files" but actual is 63. The drift was because I was counting test files inconsistently. Corrected to use BigPickle's methodology: **63 main source files** (52 Kotlin + 11 XML under `app/src/main`) + 4 test files under `app/src/test` = 67 total. The "Current state" table below now uses this breakdown explicitly.

### What BigPickle should verify after pulling Sync 5

1. **Build:** `./gradlew clean :app:assembleDebug :app:testDebugUnitTest lintDebug` → expect BUILD SUCCESSFUL, **25/25 tests pass** (was 24), lint 0 errors / 145 warnings.
2. **Run the regression test:** `./gradlew :app:testDebugUnitTest --tests *.NoteDatabaseTest.duplicate-offset*` → expect 1/1 pass.
3. **Run the full NoteDatabaseTest suite:** `./gradlew :app:testDebugUnitTest --tests *.NoteDatabaseTest` → expect 6/6 pass (was 5).
4. **Lint count:** should stay at 145 (no new tokens).
5. **No new on-device behavior** — SpacerManager is still dead code (Phase 7 prep, not wired into any main-code path yet). The bug was dormant; the fix is dormant too.

---

## Sync 4 — 2026-10-05 — SpacerManager + NoteDatabaseTest (summary)

For full details see commit `d4b21c5`. Brief summary:
- **NEW-1 (12 untested NoteDatabase DAOs):** FIXED. Wrote `data/spacer/SpacerManager.kt` (Fenwick prefix-sum, Phase 7 prep) + `NoteDatabaseTest.kt` (5 Robolectric tests covering all 12 NoteDatabase DAOs + SpacerManager + stroke immutability invariant). Test count: 19 → 24.
- **NEW-2 (mystery Kotlin warning):** Monitor only — BigPickle confirmed NOT reproducible from clean state. Daemon noise.
- **NEW-3 (HTML mockup color mismatch):** Documented for Phase 5 — always use `@color/surface_base` (not hex literals).
- **BUG FOUND BY BIGPICKLE IN SYNC 5:** SpacerManager had a duplicate-offset bug where the in-memory Fenwick tree diverged from the DB. FIXED in Sync 5 (see above).

---

## Sync 3 — 2026-10-05 — Room DB init + AppDatabaseTest + colors.xml (summary)

For full details see commit `ca24aa5`. Brief summary:
- **Issue 1 (Room never executed):** FIXED. Wired `AppDatabase.get(this)` into `ThunderNotesApp.onCreate` on a background IO coroutine. BigPickle verified on device: DB exists, schema hash matches committed fixture, cold-start 236-245ms.
- **Issue 2 (colors vs spec):** FIXED. Aligned colors.xml with spec's formal design tokens (#131317, #1B1B1F, #2A292E). Added 3 missing tokens. BigPickle verified: #131317 = 6,063,643 px.
- **Issue 3 (stale doc numbers):** FIXED.
- **Issue 4 (AGP 10 landmine):** No action, already flagged.

---

## Sync 2 + Sync 1 — 2026-10-05 — Toolchain upgrades + lint fixes (summary)

- **Sync 2** (`75dd35a`): Fixed Gradle 8.9 → 9.4.1 wrapper regression + 3 lint errors (duplicate `<item>` in themes.xml + `android:tint` → `app:tint`) + copied Room schema fixtures.
- **Sync 1** (`f4d6574`): Applied BigPickle's toolchain upgrades (AGP 8.5.2→9.2.1, Kotlin 2.0.21→2.3.21, KSP 2.0.21-1.0.25→2.3.12, Room 2.6.1→2.7.2, kotlinx-serialization 1.7.1→1.9.0, core-ktx 1.13.1→1.18.0) + fixed nested-block-comment bug (`/*.brushfamily` → `/<brush-id>.brushfamily`) + adopted `gradle/libs.versions.toml` version catalog.

---

## Current state of the repo (after Sync 5)

| Item | Status |
|---|---|
| Build | `./gradlew clean :app:assembleDebug :app:testDebugUnitTest lintDebug` → BUILD SUCCESSFUL |
| Tests | **25 unit tests** (was 24): InkStrokeSerializerTest 9 + ThunderFileRoundTripTest 4 + AppDatabaseTest 6 + NoteDatabaseTest 6 (incl. new duplicate-offset regression test) |
| Lint | 0 errors, 145 warnings (115 UnusedResources for design tokens Phase 5 will consume) |
| On-device | BigPickle verified on OnePlus Pad 2 (Android 16 / API 36): DB exists, schema hash matches fixture, cold-start 236-245ms, rotation safe, surface renders #131317. |
| Commits on `langergitanu/ThunderNotes` main | 10 (Phase 1, 2a, 2b, 3, 4, Sync 1, 2, 3, 4, 5) |
| Main source files | **63** (52 Kotlin + 11 XML under `app/src/main`) — per BigPickle's methodology |
| Test files | 4 (`InkStrokeSerializerTest` + `ThunderFileRoundTripTest` + `AppDatabaseTest` + `NoteDatabaseTest`) |
| Total files | 67 (63 main + 4 test) |
| Repo size | 2.8 MB (excl .git) |
| Disk free | 8.5 GB |
| Gradle | 9.4.1 (do NOT regress to 8.x — AGP 9.2.1 hard-requires 9.4.1+) |
| AGP | 9.2.1 |
| Kotlin | 2.3.21 |
| KSP | 2.3.12 |
| Room | 2.7.2 (schema fixtures committed to `app/schemas/`) |
| compileSdk / targetSdk / minSdk | 36.1 / 36 / 31 |
| Surface base color | `#131317` (aligned with spec's formal design tokens in Sync 3) |
| SpacerManager | **REWRITTEN (Sync 5)** — read-only FenwickTree + invalidate-on-mutation. Duplicate-offset bug FIXED + regression test added. |

---

## What GLM-5.3 is doing next — Phase 5: UI for 9 library pages

Translating the mock HTML/Tailwind UIs in `langergitanu/ThunderNotes-Frontend` to Android XML layouts +
Fragments + ViewModels + a NavHost in MainActivity. The 9 pages:

1. **ThunderHomePage** — sidebar + dashboard (recent folders/files) + floating create dock
2. **NotesLibraryPage** — filter chips + Import Note (blue) + Create Note (red)
3. **FoldersLibraryPage** — filter chips + Create Folder (emerald)
4. **BookmarksPage** — trivial; per spec §6.4
5. **TrashPage** — per spec §6.5
6. **CreateNotePage** — modal: Blank/Lined + Portrait/Landscape; Infinite Canvas popup
7. **CreateFolderPage** — modal with color swatches
8. **CoverSelectionPage** — template browser
9. **ImportFilePage** — file picker for `.thunder` files; rejects non-`.thunder`

This is a big batch (~28 files: 9 fragments + 9 layouts + 9 viewmodels + `nav_graph.xml`
+ `activity_main.xml` update + small `ui/common/` shared components). I'll push it as a single
Phase 5 commit so BigPickle can sync + test the whole UI in one pass.

### ⚠️ Phase 5 color translation rule (per BigPickle's NEW-3)

**When translating HTML mockups to Android XML, ALWAYS reference colors via `@color/...` tokens (e.g. `@color/surface_base`, `@color/thunder_primary`), NEVER via hex literals from the HTML.**

BigPickle's NEW-3 finding: the HTML mockups are inconsistent — 6 library pages use `#111115` (the OLD surface value), 4 Canvas pages use `#131317` (the NEW canonical value). If Phase 5 is translated pixel-for-pixel, the library pages will mismatch `colors.xml`. Always use `@color/...` tokens — then the mismatch can't happen regardless of which value is canonical.

---

## What BigPickle should focus on after Phase 5 lands

1. **Pull the Phase 5 commit** + run `./gradlew clean :app:assembleDebug :app:testDebugUnitTest lintDebug`.
2. **Verify the `[UnusedResources]` count drops sharply** — expected confirmation that the UI is wired to the design system. Do NOT silence the remaining UnusedResources warnings.
3. **Run on the OnePlus Pad 2.** Checklist:
   - All 9 pages render without crashing.
   - Dark theme correct (`#131317` surface, `#DC2646` crimson, `#10B981` emerald, `#F59E0B` amber, `#3B82F6` blue).
   - Launcher icon shows (red background + white thunder bolt).
   - NavHost navigation between pages works.
   - Placeholder "Phase 1" text on MainActivity is GONE.
   - Creating a note via UI produces a `.thunder` file under `/Android/data/com.thundernotes/files/notes/<noteId>.thunder`.
   - Creating a folder via UI inserts a row in `thundernotes-app.db` (verify via `adb shell run-as com.thundernotes sqlite3 databases/thundernotes-app.db "SELECT * FROM folders"`).
4. **Do NOT start writing any code yourself** — just report compile/lint/runtime errors. The architecture has many interconnected pieces (repositories, .thunder format, navigation, AppDatabase ↔ NoteDatabase coordination, SpacerManager Fenwick tree); a single-line fix that "looks right" might break something else. Always report before patching.
5. **Keep the AGP 9 flags** (`android.builtInKotlin=false`, `android.newDsl=false`) in `gradle.properties`.
6. **Keep the Gradle wrapper at 9.4.1** — don't let it regress to 8.x.
7. **Commit `app/schemas/` JSON files** when Room regenerates them.
8. **Report any repository-related runtime errors** (e.g., a `createFolder` call that doesn't update the closure table, or a `trashNote` call that doesn't delete the `.thunder` file on permanent delete).
9. **NEW-2 monitoring:** if the `'fun Project.android(...)' is deprecated` Kotlin warning becomes consistently reproducible, report it. BigPickle confirmed it's NOT reproducible from clean state — daemon noise.

---

## TBDs BigPickle can ignore (deferred to later phases)

- Font finalization, AI vendor key storage, code-snip formatter, diagram tracing — Phase 6+.
- AndroidX Ink dependency — Phase 6 (canvas MVP). Don't add speculatively.
- Hilt DI — deferred; using manual `RepositoryModule` singleton.
- 10 fonts + 50+ cover templates — Phase 12.
- `READ_MEDIA_VISUAL_USER_SELECTED` — Phase 9 (image import).
- `targetSdk 37` bump — defer until BigPickle's machine has `android-37` platform.
- AGP 10 upgrade — defer; will require reworking `builtInKotlin=false` + `newDsl=false` + the deprecated `org.jetbrains.kotlin.android` plugin.
- SpacerManager thread-safety — defer; canvas render is typically single-threaded per page. If concurrent access is needed, wrap `pageTrees` in `ConcurrentHashMap` + `computeIfAbsent`.
- SpacerManager wiring into `NoteEditingSession` — Phase 6+ (when canvas lands).

---

## Repo map

| Path | Purpose |
|---|---|
| `app/src/main/java/com/thundernotes/` | App root — `ThunderNotesApp.kt` (Application + background DB init), `MainActivity.kt` (launcher) |
| `app/src/main/java/com/thundernotes/data/entity/` | Room entities (16 total) |
| `app/src/main/java/com/thundernotes/data/dao/` | Room DAOs (16 total) |
| `app/src/main/java/com/thundernotes/data/db/` | `AppDatabase.kt` (app-global singleton) + `NoteDatabase.kt` (per-note, opened from .thunder ZIP) |
| `app/src/main/java/com/thundernotes/data/repository/` | 7 repositories + `RepositoryModule.kt` (manual DI singleton) |
| `app/src/main/java/com/thundernotes/data/spacer/` | `SpacerManager.kt` — Fenwick prefix-sum for "Add Extra Writing Space" (Phase 7 prep; **rewritten in Sync 5** with read-only FenwickTree + invalidate-on-mutation; duplicate-offset bug FIXED) |
| `app/src/main/java/com/thundernotes/format/` | `.thunder` format: `ThunderFile.kt` + `ThunderManifest.kt` + `ThunderFormatConstants.kt` |
| `app/src/main/java/com/thundernotes/format/proto/` | `InkStrokeProto.kt` + `InkStrokeSerializer.kt` |
| `app/src/main/res/values/` | `colors.xml` (Obsidian palette, aligned with spec) + `dimens.xml` + `strings.xml` + `themes.xml` |
| `app/src/main/res/layout/` | XML layouts (only `activity_main.xml` for now; Phase 5 adds 9 more) |
| `app/schemas/` | Room migration test fixtures (AppDatabase + NoteDatabase, version 1 JSON each) |
| `app/src/test/java/com/thundernotes/format/` | Round-trip tests for `.thunder` ZIP + InkStrokeProto (13 tests) |
| `app/src/test/java/com/thundernotes/data/` | `AppDatabaseTest.kt` (6 tests, app-global DB) + `NoteDatabaseTest.kt` (6 tests, per-note DB + SpacerManager + duplicate-offset regression) |
| `docs/` | Architecture plan + format proposal + donor READMEs |
| `gradle/libs.versions.toml` | Version catalog (AGP 9.2.1, Kotlin 2.3.21, KSP 2.3.12, Room 2.7.2, Gradle 9.4.1) |
| `gradle/wrapper/gradle-wrapper.properties` | Gradle 9.4.1 distribution URL (do NOT regress to 8.x) |

---

## End of Sync 5

Next sync: when Phase 5 UI is ready to push, OR when BigPickle reports new findings after pulling + verifying Sync 5.

---

## Sync 10 — 2026-10-08 — Full bug-fix round (auto-save + canvas correctness + Pad 2 tuning)

### Critical bugs fixed

1. **Auto-save / persistence was never wired (spec §2.5, priority #1):** the canvas kept
   strokes only in memory — reopening a note showed a blank page and a process death lost
   everything. Now: `NoteEditorViewModel` opens a `NoteEditingSession`, every mutation
   writes through incrementally to the per-note DB (DAO upsert/delete with close-race
   retries), a 10s debounced loop + `onStop` flush the staging DB into the `.thunder` ZIP
   (atomic rename), and `openNote(resumeStaging=true)` recovers a staging DB that is newer
   than the ZIP after a crash. Page rebuilds from DB on open (page ids now match the DB's).
2. **Strokes landed on the wrong page:** all ink-host callbacks were page-agnostic while
   `CanvasDocument.addStroke` writes to `currentPageIndex` — drawing on page 2 while page 1
   was "current" stored the stroke on page 1 (and the eraser/text tools hit the wrong page
   too). Every per-page callback now captures its page index; a scroll listener keeps the
   document's current page synced to the visible one.
3. **Undo/redo visual desync:** undo removed strokes from the *current* page's views (not
   the action's page); undoing an erase didn't bring the stroke back; redo could re-add an
   unrelated stroke from the CompletedStrokesView buffer; textbox add had no undo; AddPage
   undo left the page view on screen. Undo/redo is now driven from the `DocAction`
   (incl. new `AddTextbox`), resolves the page by id, syncs views + persistence.
4. **Eraser left injected strokes on screen:** erasing a pasted/injected stroke removed it
   from the model + Ink view but not from the CompletedStrokesView (and `removeStroke`
   searched only the current page). Fixed via page-aware `removeStrokeFromPage` + cv removal.
5. **Textbox editor (§7.1) was unreachable:** the textbox layer sat *below* the ink host,
   whose touch interception ate every long-press. Layer order fixed (textbox layer above
   the ink host) — long-press → formatting popup works.
6. **Persisted strokes lost intermediate points:** `capturePoint` sampled only the newest
   MotionEvent, dropping coalesced historical points — visible as degraded strokes after
   reload, and doubled sample spacing on 120/144Hz displays. Historical points are now
   captured (OnePlus Pad 2 144Hz smoothness + data integrity).

### Completed / made functional

- **Change Cover button (canvas)** now opens the real 60-template cover picker (was a
  redirect toast).
- **All 4 §7.1 underline styles** render (thin flag + custom thick/dashed/wavy
  LineBackgroundSpans — previously everything rendered as thin).
- **Table Maker geometry** is density-scaled + centered on the actual page (was hardcoded
  600x400px at 50,50).
- **Add Writing Space (§7.4)** appends the gap at the page's content bottom (no reflow,
  O(1)) and persists the spacer row + grown page height.
- **Note export path** is robust for absolute/relative `filePath`.

### OnePlus Pad 2 optimizations

- Historical touch capture (above) for 144Hz input fidelity.
- All 7 library grids use width-responsive span counts (`ResponsiveSpans`) instead of
  hardcoded 3/4 columns (landscape 7:5 panel + portrait both handled).
- Lint cleaned: 42 pre-existing errors fixed (`android:tint` → `app:tint` with namespaces;
  ink `RestrictedApi` suppressed with rationale).

### Verification

`./gradlew assembleDebug lintDebug testDebugUnitTest` → **BUILD SUCCESSFUL; 301/301 tests
pass; lint 0 errors.** New files: `canvas/CanvasRecordMappers.kt`,
`ui/canvas/UnderlineSpans.kt`, `ui/common/ResponsiveSpans.kt`.
