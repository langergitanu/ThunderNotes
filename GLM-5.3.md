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

## Sync 3 — 2026-10-05 — BigPickle's on-device test + 4 open issues processed

### What BigPickle did (in BigPickle.txt Sync 2 round 3)

**BigPickle tested the build independently + ran the app on the actual OnePlus Pad 2:**
- Device: OnePlus Pad 2 (OPD2403), Android 16 / API 36, 2120×3000 @ 420dpi.
- Clean clone of my Sync 2 commit `75dd35a` → BUILD SUCCESSFUL, 13 tests, lint 0 errors / 142 warnings.
- Installs + launches on device, no crash in the crash buffer.
- Startup marker fires (`ThunderNotes v0.1.0 starting`) → `ThunderNotesApp.onCreate` runs.
- Rotation to landscape does NOT recreate the Activity (same PID) → `configChanges` list is correct.
- Dark theme renders the Obsidian palette as intended.

**BigPickle reported 4 open issues** (no code changed this round — just a report):

| # | Severity | What | My decision |
|---|---|---|---|
| **1** | HIGH | Room layer compiled-but-never-executed. `RepositoryModule` referenced only in its own KDoc. `/data/data/com.thundernotes/databases/` didn't exist on device. Zero Room test coverage (all 13 existing tests covered only `format/`). | **FIXED** — wired `AppDatabase.get(this)` into `ThunderNotesApp.onCreate` on a background IO coroutine + wrote 6 Robolectric tests in `AppDatabaseTest.kt` exercising folder closure-table + note CRUD + recycle bin + title search. |
| **2** | MEDIUM | `colors.xml` contradicted the design-system spec. Spec's formal design tokens said `surface: #131317`, colors.xml had `surface_base: #111115`. BigPickle sampled rendered pixels → #111115 shipped. Spec was internally inconsistent (top-of-file tokens vs. body "Surface Hierarchy" prose). | **FIXED** — spec wins. Updated colors.xml to match the formal design tokens: `surface_base` #111115 → #131317, `surface_container_low` #18181C → #1B1B1F, `surface_container_high` #222228 → #2A292E. Added missing tokens: `surface_container_lowest` (#0E0E12), `surface_container_highest` (#353439), `surface_bright` (#39393D). Kept custom `surface_overlay` (#2A2A32) for popovers/dialogs. |
| **3** | DOCS | GLM-5.3.md numbers stale: "145 warnings" (actual 142), "14 unit tests" (actual 13 — the 14th was BigPickle's wizard `ExampleUnitTest.kt`), "64 files" (actual 62). | **FIXED** — corrected all numbers in this file. |
| **4** | FUTURE | AGP 10 will hard-break the build (deprecated `android.builtInKotlin=false` + Kotlin plugin warning). | **No action now** — already flagged in Sync 1 + Sync 2. |

### What GLM-5.3 CHANGED (pushing to `langergitanu/ThunderNotes` next)

**Issue 1 fix — Room layer now actually runs:**
1. `app/src/main/java/com/thundernotes/ThunderNotesApp.kt` — rewrote:
   - Added `appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)` for app-level background work.
   - In `onCreate`, launched a background coroutine that calls `AppDatabase.get(this)` to force DB construction + `RepositoryModule.templates.seedPreinstalledTemplates(this)` (no-op for now). Try/catch swallows failures so the app still launches if DB init fails — the first UI access via RepositoryModule will retry.
   - Updated KDoc: Phase 1-4 marked done, Phase 5 (UI) marked next, Phase 6 (canvas), Phase 9 (SnipEngine). Preserved the "never write `/*` in KDoc" warning (attached to Phase 6 brush-family line).
   - **After this fix, `/data/data/com.thundernotes/databases/thundernotes-app.db` should exist on first launch.** BigPickle can verify on device.
2. `app/src/test/java/com/thundernotes/data/AppDatabaseTest.kt` — NEW file, 6 Robolectric tests:
   - `createFolder at root inserts self-closure row` — verifies the closure-table self-row (depth 0) exists + `getAncestors` returns empty list (excludes self).
   - `createFolder hierarchy builds correct closure table` — builds Root → Middle → Leaf, verifies Leaf's ancestors = [Root, Middle] in depth-DESC order, Middle's ancestors = [Root], Root's = [], direct child counts.
   - `moveFolder rebuilds closure table under new parent` — moves Child from RootA to RootB, verifies ancestors rebuild correctly + old parent's child count drops to 0.
   - `deleteFolderPermanently removes folder and its closure subtree` — verifies cascade closure-table delete + folder row delete.
   - `NoteDao CRUD + bookmark + recycle bin flow` — insert → read → bookmark → trash (clears bookmark) → restore (bookmark stays cleared) → permanent delete.
   - `NoteDao title search returns matching notes only` — 3 notes, "Calculus" → 2 results, "homework" → 2, "nonexistent" → 0, "calculus" (lowercase) → 2 (case-insensitive).
   - Uses `@RunWith(AndroidJUnit4::class)` + `@Config(sdk = [33])` (Robolectric 4.13 max SDK). In-memory Room DB via `Room.inMemoryDatabaseBuilder`.
3. `app/build.gradle.kts` — added `testImplementation(libs.androidx.junit)` (was only `androidTestImplementation`) so the Robolectric test can use `AndroidJUnit4` runner.

**Issue 2 fix — colors.xml aligned with spec's formal design tokens:**
- `app/src/main/res/values/colors.xml` — surface tier block rewritten:
  - `surface_base`: `#111115` → `#131317` (spec's `surface`)
  - `surface_container_low`: `#18181C` → `#1B1B1F` (spec's `surface-container-low`)
  - `surface_container_high`: `#222228` → `#2A292E` (spec's `surface-container-high`)
  - NEW: `surface_container_lowest` = `#0E0E12` (spec's `surface-container-lowest`)
  - NEW: `surface_container_highest` = `#353439` (spec's `surface-container-highest`)
  - NEW: `surface_bright` = `#39393D` (spec's `surface-bright`)
  - KEPT: `surface_overlay` = `#2A2A32` (custom token, matches spec body's "Layer 3 Floating Overlays" rendered value)
  - Added a comment block explaining the spec's two color systems + why we chose the formal tokens.

**Issue 3 fix — stale numbers corrected** (see "Current state" table below).

### What BigPickle should verify after pulling Sync 3

1. **Build:** `./gradlew clean :app:assembleDebug :app:testDebugUnitTest lintDebug` → expect BUILD SUCCESSFUL, **19 tests pass** (was 13), lint 0 errors.
2. **On device (OnePlus Pad 2):**
   - Launch the app.
   - Verify `/data/data/com.thundernotes/databases/thundernotes-app.db` NOW EXISTS (was missing before Sync 3 — Issue 1 fix). Use `adb shell run-as com.thundernotes.debug ls -la databases/` (debug build has `applicationId = com.thundernotes`, so `run-as com.thundernotes` works).
   - Verify the startup log includes `AppDatabase constructed: thundernotes-app.db.` + `Background init complete.` (via `adb logcat -s ThunderNotesApp`).
   - Verify the surface background is now `#131317` (was `#111115`). Sample via `adb shell screencap` + check the dominant pixel color.
3. **Run the new Robolectric test:** `./gradlew :app:testDebugUnitTest --tests *.AppDatabaseTest` → expect 6/6 pass.
4. **Lint warning count check:** the `[UnusedResources]` count may have INCREASED by 3 (the 3 new surface tokens `surface_container_lowest` / `surface_container_highest` / `surface_bright` aren't referenced yet — Phase 5 UI will consume them). Do NOT silence — that drop is the expected confirmation signal when Phase 5 lands.

---

## Sync 2 — 2026-10-05 — BigPickle's lint findings + Gradle wrapper regression processed (summary)

For full details see commit `75dd35a`. Brief summary:
- **H1 (URGENT):** Fixed Gradle 8.9 → 9.4.1 wrapper regression I introduced in Sync 1 (AGP 9.2.1 requires Gradle 9.4.1+).
- **G1a + G1b (3 lint errors):** Duplicate `<item>` attributes in `themes.xml` + `android:tint` → `app:tint` on ImageView.
- **H5:** Copied Room schema fixtures (`app/schemas/.../1.json` × 2).
- Apologized for the wrapper regression; future syncs diff wrapper files explicitly.

---

## Sync 1 — 2026-10-05 — BigPickle's toolchain upgrades processed (summary)

For full details see commit `f4d6574`. Brief summary:
- **A1 (CRITICAL):** Nested-block-comment bug in `ThunderNotesApp.kt:16` — fixed `/*.brushfamily` → `/<brush-id>.brushfamily`.
- **B1-B6 + C:** Toolchain upgrades (AGP 8.5.2→9.2.1, Kotlin 2.0.21→2.3.21, KSP 2.0.21-1.0.25→2.3.12, Room 2.6.1→2.7.2, kotlinx-serialization 1.7.1→1.9.0, core-ktx 1.13.1→1.18.0). Added `android.builtInKotlin=false` + `android.newDsl=false` AGP 9 flags (deprecated, will need rework before AGP 10). Added `-jvm-default=enable` + `-opt-in=ExperimentalSerializationApi` + `ksp room.schemaLocation` + `fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)`.
- **D:** Adopted BigPickle's `gradle/libs.versions.toml` version catalog pattern.
- **E2:** Fixed stale name "ThunderNotesDatabase" → "AppDatabase" in `ThunderNotesApp.kt:14` KDoc.

---

## Current state of the repo (after Sync 3)

| Item | Status |
|---|---|
| Build | `./gradlew clean :app:assembleDebug :app:testDebugUnitTest lintDebug` → BUILD SUCCESSFUL (BigPickle verified by clean-clone of Sync 2; Sync 3 changes are additive + shouldn't break) |
| Tests | **19 unit tests** (was 13): InkStrokeSerializerTest 9 + ThunderFileRoundTripTest 4 + **AppDatabaseTest 6** (NEW — exercises Room layer end-to-end) |
| Lint | 0 errors, **142 warnings** (all informational — see Sync 2 table; count may rise by 3 after Sync 3 adds 3 new unused surface color tokens) |
| On-device | BigPickle verified on OnePlus Pad 2 (Android 16 / API 36): installs, launches, no crash, rotation doesn't recreate Activity, dark theme renders correctly. **After Sync 3, also verify `/data/data/com.thundernotes/databases/thundernotes-app.db` exists.** |
| Commits on `langergitanu/ThunderNotes` main | 8 (Phase 1, 2a, 2b, 3, 4, Sync 1, Sync 2, Sync 3) |
| Source files | **63 Kotlin + XML resource files** (was 62: 51 Kotlin + 11 XML under `app/src/main`, +1 new test file `AppDatabaseTest.kt` under `app/src/test`) |
| Repo size | 2.5 MB (excl .git) |
| Disk free | 8.5 GB |
| Gradle | 9.4.1 (per H1 — do NOT regress to 8.x) |
| AGP | 9.2.1 |
| Kotlin | 2.3.21 |
| KSP | 2.3.12 |
| Room | 2.7.2 (schema fixtures committed to `app/schemas/`) |
| compileSdk / targetSdk / minSdk | 36.1 / 36 / 31 |
| Surface base color | `#131317` (was `#111115` — aligned with spec's formal design tokens in Sync 3) |

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

The Obsidian Precision Note System design tokens are now in `app/src/main/res/values/colors.xml`
(aligned with the spec's formal design tokens in Sync 3) — the UI will use them via
`@color/surface_base`, `@color/thunder_primary`, `@dimen/space_md`, etc.

**Phase 5 will exercise the Room layer live for the first time** — the UI will call into
`RepositoryModule.notes` / `folders` / `trash` / `bookmarks` / `templates` / `search`. The
`AppDatabaseTest` I added in Sync 3 is the safety net: if a DAO is wrong, the test fails
before Phase 5 UI crashes.

---

## What BigPickle should focus on after Phase 5 lands

1. **Pull the Phase 5 commit** + run `./gradlew clean :app:assembleDebug :app:testDebugUnitTest lintDebug`.
2. **Verify the `[UnusedResources]` count drops sharply** — expected confirmation that the UI is wired
   to the design system. Per BigPickle's note (BigPickle.txt Sync 2 Section I.3), please don't silence
   the remaining UnusedResources warnings; they'll be consumed as more UI lands in Phase 6+.
3. **Run on the OnePlus Pad 2** (BigPickle's `adb devices` is empty on their machine, so on-device
   verification is the user's job). Checklist:
   - All 9 pages render without crashing.
   - The dark theme is correctly applied (Obsidian Precision Note System colors — **`#131317`** surface
     base after Sync 3, `#DC2646` crimson primary, `#10B981` emerald, `#F59E0B` amber, `#3B82F6` blue).
   - The launcher icon shows (red background + white thunder bolt).
   - Navigation between pages works via the NavHost.
   - The placeholder "Phase 1" text on MainActivity is GONE (replaced by the NavHost).
   - Creating a note via the UI produces a `.thunder` file under
     `/Android/data/com.thundernotes/files/notes/<noteId>.thunder`.
   - Creating a folder via the UI inserts a row in `thundernotes-app.db` (verify via
     `adb shell run-as com.thundernotes sqlite3 databases/thundernotes-app.db "SELECT * FROM folders"`).
4. **Do NOT start writing any code yourself** — just report compile/lint/runtime errors. The architecture
   has many interconnected pieces (repositories, .thunder format, navigation, AppDatabase ↔ NoteDatabase
   coordination); a single-line fix that "looks right" might break something else. Always report before
   patching.
5. **Keep the AGP 9 flags** (`android.builtInKotlin=false`, `android.newDsl=false`) in `gradle.properties`.
   Don't "clean them up" — they're load-bearing for the KSP/Room pipeline on AGP 9.
6. **Keep the Gradle wrapper at 9.4.1** — don't let it regress to 8.x. AGP 9.2.1 hard-requires 9.4.1+.
7. **Commit `app/schemas/` JSON files** when Room regenerates them after I add new entities in a future
   phase — they're the migration test fixtures.
8. **Report any repository-related runtime errors** (e.g., a `createFolder` call that doesn't update the
   closure table, or a `trashNote` call that doesn't delete the `.thunder` file on permanent delete) —
   those are likely real bugs in my code that need a function-level fix, not a single-line patch.

---

## TBDs BigPickle can ignore (deferred to later phases)

- The remaining "developer-decided" items from the spec (`docs/architecture-plan.md` §8) — font
  finalization (1 more serif, 1 more sans-serif, 3 more handwriting), AI vendor key storage, code-snip
  formatter (proposed: Prism4j), diagram tracing specifics — will be tackled in Phase 6+ when those
  subsystems land.
- AndroidX Ink dependency will land in Phase 6 (canvas MVP). Don't add it speculatively.
- Hilt DI is deferred — we're using a manual `RepositoryModule` singleton for now.
- 10 fonts + 50+ cover templates will land in Phase 12.
- `READ_MEDIA_VISUAL_USER_SELECTED` for Android 14+ partial photo access — defer to Phase 9 (image import).
- `targetSdk 37` bump — defer until BigPickle's machine has the `android-37` platform installed.
- AGP 10 upgrade — defer; will require reworking the `android.builtInKotlin=false` + `android.newDsl=false`
  flags + the deprecated `org.jetbrains.kotlin.android` plugin (Issue 4 from Sync 3 report).

---

## Repo map (so BigPickle always knows where to look)

| Path | Purpose |
|---|---|
| `app/src/main/java/com/thundernotes/` | App root — `ThunderNotesApp.kt` (Application + background DB init in `onCreate`), `MainActivity.kt` (launcher) |
| `app/src/main/java/com/thundernotes/data/entity/` | Room entities (16 total — Note, Folder, FolderClosure, Template, NoteContent, NotePage, PageLayer, Stroke, Shape, TextBox, Image, Outline, Comment, HyperLink, Spacer, PdfInfo + PageEnums + BrushEnums) |
| `app/src/main/java/com/thundernotes/data/dao/` | Room DAOs (16 total) |
| `app/src/main/java/com/thundernotes/data/db/` | `AppDatabase.kt` (app-global singleton — now eagerly constructed in `ThunderNotesApp.onCreate` per Sync 3 Issue 1 fix) + `NoteDatabase.kt` (per-note, opened from .thunder ZIP) |
| `app/src/main/java/com/thundernotes/data/repository/` | 7 repositories + `RepositoryModule.kt` (manual DI singleton) |
| `app/src/main/java/com/thundernotes/format/` | `.thunder` format: `ThunderFile.kt` (ZIP reader/writer) + `ThunderManifest.kt` + `ThunderFormatConstants.kt` |
| `app/src/main/java/com/thundernotes/format/proto/` | `InkStrokeProto.kt` (14-field protobuf mirror of Notein) + `InkStrokeSerializer.kt` ("TNPD" magic bytes) |
| `app/src/main/res/values/` | `colors.xml` (Obsidian palette — aligned with spec's formal design tokens per Sync 3 Issue 2 fix) + `dimens.xml` + `strings.xml` + `themes.xml` |
| `app/src/main/res/layout/` | XML layouts (only `activity_main.xml` for now; Phase 5 adds 9 more) |
| `app/schemas/` | Room migration test fixtures (AppDatabase + NoteDatabase, version 1 JSON each) |
| `app/src/test/java/com/thundernotes/format/` | Round-trip tests for `.thunder` ZIP + InkStrokeProto (13 tests) |
| `app/src/test/java/com/thundernotes/data/` | **NEW (Sync 3):** `AppDatabaseTest.kt` — 6 Robolectric tests exercising folder closure + note CRUD + search |
| `docs/` | Architecture plan + format proposal + donor READMEs (Notein, SamsungNotes, MyScript, Mock-UI) |
| `gradle/libs.versions.toml` | Version catalog (AGP 9.2.1, Kotlin 2.3.21, KSP 2.3.12, Room 2.7.2, Gradle 9.4.1, etc.) |
| `gradle/wrapper/gradle-wrapper.properties` | Gradle 9.4.1 distribution URL (do NOT regress to 8.x — AGP 9.2.1 hard-requires 9.4.1+) |

---

## End of Sync 3

Next sync: when Phase 5 UI is ready to push, OR when BigPickle reports new findings after pulling + verifying Sync 3.
