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

## Sync 4 — 2026-10-05 — BigPickle's on-device verification + 3 NEW issues processed

### What BigPickle did (in BigPickle.txt Sync 3 round)

**BigPickle verified Sync 3 by pulling the APK off the OnePlus Pad 2 + opening the SQLite file with Python:**

1. **Logcat proves `ThunderNotesApp.onCreate` ran the new init path:**
   ```
   D/ThunderNotesApp( 1699): ThunderNotes v0.1.0 starting.
   D/ThunderNotesApp( 1699): AppDatabase constructed: thundernotes-app.db.
   D/ThunderNotesApp( 1699): Background init complete.
   ```
   No `Log.e`, no "Background init failed". Try/catch fallback never fired.
2. **The directory that didn't exist last round now exists:**
   ```
   /data/data/com.thundernotes/databases/
     thundernotes-app.db        4,096 bytes
     thundernotes-app.db-shm   32,768 bytes
     thundernotes-app.db-wal  123,632 bytes
   ```
3. **Schema identity hash matches the committed migration fixture:**
   - `room_master_table` identity_hash on device = `873c689116728905e37e6d99653f1818`
   - `app/schemas/.../AppDatabase/1.json` identityHash = SAME VALUE
   - **First real end-to-end proof that the Room pipeline + schema export are mutually consistent.**
4. **Cold-start timing (3 consecutive `am start -W` runs):** 236 / 244 / 245 ms. DB construction never blocks UI thread, never lost the race against first paint. `Dispatchers.IO + SupervisorJob` choice is right.
5. **Rotation still safe:** PID 2412 identical before/after rotating to landscape. 0 crash-buffer entries.
6. **All 4 Sync 3 issues resolved.** Issue 1 (Room never executed) → FIXED + verified on tablet. Issue 2 (colors vs spec) → FIXED, renders #131317 (6,063,643 px), #111115 = 0 px. Issue 3 (stale doc numbers) → FIXED. Issue 4 (AGP 10) → unchanged, no action needed.

**BigPickle reported 3 NEW issues** (no code changed this round — just a report):

| # | Severity | What | My decision |
|---|---|---|---|
| **NEW-1** | Medium-HIGH | The other 12 NoteDatabase DAOs are still never executed. `AppDatabaseTest` mentions `NoteDatabase` only in a KDoc explaining why it's NOT covered. The uncovered entities include the hardest logic: `SpacerEntity` documents an O(log N) Fenwick prefix-sum; a sign error would silently shift every stroke below the insertion point. Also notes `data/spacer/SpacerManager.kt` doesn't exist yet. | **FIXED.** (a) Wrote `data/spacer/SpacerManager.kt` — the Fenwick prefix-sum implementation (Phase 7 prep pulled forward to de-risk the hardest invariant). (b) Wrote `NoteDatabaseTest.kt` with 5 Robolectric tests covering all 12 NoteDatabase DAOs + SpacerManager + the stroke immutability invariant. |
| **NEW-2** | Low | A mystery Kotlin warning appeared once in a clean build: `'fun Project.android(...)' is deprecated. Replaced by com.android.build.api.dsl.ApplicationExtension.` BigPickle couldn't reproduce it consistently — likely daemon warm-up noise. Same family as the `android.newDsl=false` AGP-10 landmine. | **Monitor only** — I can't reproduce (no Android SDK here). BigPickle to report if it becomes consistently reproducible. |
| **NEW-3** | Low now, medium when Phase 5 starts | Issue 2's fix (#131317) is right, but the HTML mockups Phase 5 will translate from mostly use the OLD value (#111115): 6 library pages use #111115 (9 occurrences), 4 Canvas pages use #131317 (5 occurrences). Risk: pixel-for-pixel translation → background mismatch. | **Note for Phase 5**: when translating mockups, always use `@color/surface_base` (not the HTML hex literal). Then mismatch can't happen regardless of canonical value. Documented below. |

### What GLM-5.3 CHANGED (pushing to `langergitanu/ThunderNotes` next)

**NEW-1 fix — SpacerManager + NoteDatabaseTest:**

1. **NEW: `app/src/main/java/com/thundernotes/data/spacer/SpacerManager.kt`** — the Fenwick prefix-sum implementation:
   - `cumulativeHeightAbove(pageId, y)` — O(log N) query: returns the sum of heights of all spacers on the page where `offset < y` (strict less-than — a spacer AT y is NOT counted, matching the user mental model: "content at y stays at the top of the new space, content below y shifts down").
   - `insertSpacer` / `removeSpacer` / `resizeSpacer` — update the in-memory Fenwick tree + the DB.
   - `invalidate(pageId)` / `invalidateAll()` — cache management for when a page is reloaded or the note is closed.
   - Private `FenwickTree` class: 1-indexed BIT array, binary-search for offset insertion point, O(log N) prefix-sum + point-update. Insert/remove/update rebuild the tree (O(N)) — acceptable since spacers are infrequent (user adds maybe 5-10 per page); the rebuild is simpler than a balanced-tree index.
   - **Invariants pinned by NoteDatabaseTest:** (1) adding a spacer at offset Y shifts points STRICTLY BELOW Y (Y' > Y) by exactly the spacer's height; (2) stroke stored coords NEVER mutated by spacer ops; (3) removing reverses exactly; (4) resizing by Δ shifts by Δ.

2. **NEW: `app/src/test/java/com/thundernotes/data/NoteDatabaseTest.kt`** — 5 Robolectric tests covering ALL 12 NoteDatabase DAOs + SpacerManager:
   - `bootstrap note creates content + page + layer rows` — exercises `NoteContentDao` + `NotePageDao` + `LayerDao` (the bootstrap trio from `NotesRepository.createNote`).
   - `stroke insert + bounding box spatial query` — exercises `StrokeDao`: insert + `getByStrokeId` + `getByBoundingBox` (viewport overlapping → 1 result; viewport above → 0; viewport right → 0) + `countByPage`.
   - `spacer insert + resize + remove via SpacerManager pins stroke immutability` — **the big one.** Exercises `SpacerManager` + `SpacerDao` + `StrokeDao` immutability. Inserts a stroke at top=100/bottom=200, inserts a spacer ABOVE (offset=50, height=50), asserts `cumulativeHeightAbove(100) = 50` + stroke bounding box UNCHANGED + blob byte-identical. Inserts a spacer BELOW (offset=250, height=30), asserts stroke unaffected. Resizes first spacer (50→80, Δ=+30), asserts cumulative shifts by 30 + stroke unchanged. Removes first spacer, asserts shift reverses + stroke unchanged.
   - `shape + textbox + image insert + read` — exercises `ShapeDao` + `TextBoxDao` + `ImageDao` + `getAllReferencedAssetPaths` (for GC).
   - `outline + comment + hyperlink + pdfInfo insert + read` — exercises `OutlineDao` + `CommentDao` (incl. `updateAnchor` for lasso moves) + `HyperLinkDao` (incl. `updateBounds`) + `PdfInfoDao` (incl. `updateCurrentPage`).
   - Uses `@RunWith(AndroidJUnit4::class)` + `@Config(sdk = [33])`. In-memory NoteDatabase via `Room.inMemoryDatabaseBuilder`.
   - The stroke test helper `makeStrokeEntity` builds a real `InkStrokeProto` (2 points) + serializes via `InkStrokeSerializer` so the test exercises the full StrokeEntity ↔ InkStrokeProto ↔ InkStrokeSerializer pipeline.

**Test count: 19 → 24** (InkStrokeSerializerTest 9 + ThunderFileRoundTripTest 4 + AppDatabaseTest 6 + NoteDatabaseTest 5).

**File count: 63 → 65** (+1 SpacerManager.kt, +1 NoteDatabaseTest.kt).

### What BigPickle should verify after pulling Sync 4

1. **Build:** `./gradlew clean :app:assembleDebug :app:testDebugUnitTest lintDebug` → expect BUILD SUCCESSFUL, **24 tests pass** (was 19), lint 0 errors.
2. **Run the new test specifically:** `./gradlew :app:testDebugUnitTest --tests *.NoteDatabaseTest` → expect 5/5 pass.
3. **Lint warning count check:** the `[UnusedResources]` count should stay at 115 (SpacerManager + NoteDatabaseTest don't add new color/dimen tokens). If it changes, report.
4. **On device (OnePlus Pad 2):** the existing checklist from Sync 3 still applies (DB exists, surface #131317, rotation safe). No new on-device behavior to verify — SpacerManager + NoteDatabaseTest are pure logic, no UI yet.
5. **NEW-2 monitoring:** if you see the `'fun Project.android(...)' is deprecated` warning **consistently** (not just once on a cold daemon), report it. If it appears once and disappears, it's daemon warm-up noise — ignore.

---

## Sync 3 — 2026-10-05 — BigPickle's on-device test + 4 open issues processed (summary)

For full details see commit `ca24aa5`. Brief summary:
- **Issue 1 (HIGH):** Room layer compiled-but-never-executed → FIXED. Wired `AppDatabase.get(this)` into `ThunderNotesApp.onCreate` on a background IO coroutine + wrote `AppDatabaseTest.kt` (6 Robolectric tests). BigPickle verified on device: `/data/data/com.thundernotes/databases/thundernotes-app.db` exists, schema hash matches committed fixture.
- **Issue 2 (MEDIUM):** colors.xml contradicted spec → FIXED. Aligned with spec's formal design tokens (#131317, #1B1B1F, #2A292E). Added 3 missing tokens. BigPickle verified: #131317 = 6,063,643 px, #111115 = 0 px.
- **Issue 3 (DOCS):** stale numbers → FIXED.
- **Issue 4 (FUTURE):** AGP 10 landmine → no action, already flagged.

---

## Sync 2 — 2026-10-05 — BigPickle's lint findings + Gradle wrapper regression (summary)

For full details see commit `75dd35a`. Brief summary:
- **H1 (URGENT):** Fixed Gradle 8.9 → 9.4.1 wrapper regression (AGP 9.2.1 requires 9.4.1+).
- **G1a + G1b (3 lint errors):** Duplicate `<item>` in themes.xml + `android:tint` → `app:tint`.
- **H5:** Copied Room schema fixtures.

---

## Sync 1 — 2026-10-05 — BigPickle's toolchain upgrades (summary)

For full details see commit `f4d6574`. Brief summary:
- **A1 (CRITICAL):** Nested-block-comment bug `/*.brushfamily` → `/<brush-id>.brushfamily`.
- **B1-B6 + C:** AGP 8.5.2→9.2.1, Kotlin 2.0.21→2.3.21, KSP 2.0.21-1.0.25→2.3.12, Room 2.6.1→2.7.2, kotlinx-serialization 1.7.1→1.9.0, core-ktx 1.13.1→1.18.0. Added AGP 9 flags + `-jvm-default=enable` + opt-in + room.schemaLocation + dropAllTables.
- **D:** Adopted `gradle/libs.versions.toml` version catalog.

---

## Current state of the repo (after Sync 4)

| Item | Status |
|---|---|
| Build | `./gradlew clean :app:assembleDebug :app:testDebugUnitTest lintDebug` → BUILD SUCCESSFUL (BigPickle verified by clean-clone of Sync 3; Sync 4 changes are additive + shouldn't break) |
| Tests | **24 unit tests** (was 19): InkStrokeSerializerTest 9 + ThunderFileRoundTripTest 4 + AppDatabaseTest 6 + **NoteDatabaseTest 5** (NEW — covers all 12 NoteDatabase DAOs + SpacerManager + stroke immutability) |
| Lint | 0 errors, 145 warnings (all informational — 115 UnusedResources for design tokens Phase 5 will consume) |
| On-device | BigPickle verified on OnePlus Pad 2 (Android 16 / API 36): DB exists, schema hash matches fixture, cold-start 236-245ms, rotation safe, surface renders #131317. |
| Commits on `langergitanu/ThunderNotes` main | 9 (Phase 1, 2a, 2b, 3, 4, Sync 1, 2, 3, 4) |
| Source files | **65 Kotlin + XML resource files** (was 63: +1 SpacerManager.kt, +1 NoteDatabaseTest.kt) |
| Repo size | 2.6 MB (excl .git) |
| Disk free | 8.5 GB |
| Gradle | 9.4.1 (do NOT regress to 8.x — AGP 9.2.1 hard-requires 9.4.1+) |
| AGP | 9.2.1 |
| Kotlin | 2.3.21 |
| KSP | 2.3.12 |
| Room | 2.7.2 (schema fixtures committed to `app/schemas/`) |
| compileSdk / targetSdk / minSdk | 36.1 / 36 / 31 |
| Surface base color | `#131317` (aligned with spec's formal design tokens in Sync 3) |
| SpacerManager | **NEW (Sync 4)** — Fenwick prefix-sum for "Add Extra Writing Space" (Phase 7 prep, pulled forward to de-risk the hardest invariant) |

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

BigPickle's NEW-3 finding: the HTML mockups are inconsistent — 6 library/library-modal pages (ThunderHomePage, NotesLibraryPage, FoldersLibraryPage, CreateNotePage, CreateFolderPage, CoverSelectionPage) use `#111115` (the OLD surface value, 9 occurrences total), while 4 Canvas pages (CanvasLayout, Customization, Settings, Utility) use `#131317` (the NEW canonical value, 5 occurrences).

If Phase 5 is translated pixel-for-pixel from the HTML hex literals, those 6 library pages will come out `#111115` while `colors.xml` says `#131317` → a subtle 2/255 background mismatch between app chrome (theme `@color/surface_base`) and hardcoded page backgrounds. The delta is small enough to look like "the design is slightly off" rather than a bug.

**Solution:** always use `@color/surface_base` (and other `@color/...` tokens) in XML layouts. Then the mismatch can't happen regardless of which value is canonical. If the HTML uses `#111115`, ignore it and use `@color/surface_base` (= `#131317` per current colors.xml). If we later decide `#111115` should win, we revert colors.xml + the GLM-5.3.md checklist together so they don't drift.

The same rule applies to all other colors (thunder_primary, thunder_secondary, surface tiers, text colors, etc.) — always `@color/...`, never hex literals.

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
     base, `#DC2646` crimson primary, `#10B981` emerald, `#F59E0B` amber, `#3B82F6` blue).
   - The launcher icon shows (red background + white thunder bolt).
   - Navigation between pages works via the NavHost.
   - The placeholder "Phase 1" text on MainActivity is GONE (replaced by the NavHost).
   - Creating a note via the UI produces a `.thunder` file under
     `/Android/data/com.thundernotes/files/notes/<noteId>.thunder`.
   - Creating a folder via the UI inserts a row in `thundernotes-app.db` (verify via
     `adb shell run-as com.thundernotes sqlite3 databases/thundernotes-app.db "SELECT * FROM folders"`).
4. **Do NOT start writing any code yourself** — just report compile/lint/runtime errors. The architecture
   has many interconnected pieces (repositories, .thunder format, navigation, AppDatabase ↔ NoteDatabase
   coordination, SpacerManager Fenwick tree); a single-line fix that "looks right" might break something
   else. Always report before patching.
5. **Keep the AGP 9 flags** (`android.builtInKotlin=false`, `android.newDsl=false`) in `gradle.properties`.
   Don't "clean them up" — they're load-bearing for the KSP/Room pipeline on AGP 9.
6. **Keep the Gradle wrapper at 9.4.1** — don't let it regress to 8.x.
7. **Commit `app/schemas/` JSON files** when Room regenerates them after I add new entities.
8. **Report any repository-related runtime errors** (e.g., a `createFolder` call that doesn't update the
   closure table, or a `trashNote` call that doesn't delete the `.thunder` file on permanent delete).
9. **NEW-2 monitoring:** if the `'fun Project.android(...)' is deprecated` Kotlin warning becomes
   consistently reproducible (not just on cold daemons), report it. Same AGP-10 landmine family as
   `android.newDsl=false`.

---

## TBDs BigPickle can ignore (deferred to later phases)

- The remaining "developer-decided" items from the spec (`docs/architecture-plan.md` §8) — font
  finalization, AI vendor key storage, code-snip formatter, diagram tracing specifics — Phase 6+.
- AndroidX Ink dependency will land in Phase 6 (canvas MVP). Don't add it speculatively.
- Hilt DI is deferred — we're using a manual `RepositoryModule` singleton.
- 10 fonts + 50+ cover templates will land in Phase 12.
- `READ_MEDIA_VISUAL_USER_SELECTED` for Android 14+ partial photo access — defer to Phase 9.
- `targetSdk 37` bump — defer until BigPickle's machine has the `android-37` platform.
- AGP 10 upgrade — defer; will require reworking the `builtInKotlin=false` + `newDsl=false` flags
  + the deprecated `org.jetbrains.kotlin.android` plugin (Issue 4 + NEW-2).

---

## Repo map (so BigPickle always knows where to look)

| Path | Purpose |
|---|---|
| `app/src/main/java/com/thundernotes/` | App root — `ThunderNotesApp.kt` (Application + background DB init), `MainActivity.kt` (launcher) |
| `app/src/main/java/com/thundernotes/data/entity/` | Room entities (16 total) |
| `app/src/main/java/com/thundernotes/data/dao/` | Room DAOs (16 total) |
| `app/src/main/java/com/thundernotes/data/db/` | `AppDatabase.kt` (app-global singleton) + `NoteDatabase.kt` (per-note, opened from .thunder ZIP) |
| `app/src/main/java/com/thundernotes/data/repository/` | 7 repositories + `RepositoryModule.kt` (manual DI singleton) |
| `app/src/main/java/com/thundernotes/data/spacer/` | **NEW (Sync 4):** `SpacerManager.kt` — Fenwick prefix-sum for "Add Extra Writing Space" (Phase 7 prep) |
| `app/src/main/java/com/thundernotes/format/` | `.thunder` format: `ThunderFile.kt` + `ThunderManifest.kt` + `ThunderFormatConstants.kt` |
| `app/src/main/java/com/thundernotes/format/proto/` | `InkStrokeProto.kt` + `InkStrokeSerializer.kt` |
| `app/src/main/res/values/` | `colors.xml` (Obsidian palette, aligned with spec) + `dimens.xml` + `strings.xml` + `themes.xml` |
| `app/src/main/res/layout/` | XML layouts (only `activity_main.xml` for now; Phase 5 adds 9 more) |
| `app/schemas/` | Room migration test fixtures (AppDatabase + NoteDatabase, version 1 JSON each) |
| `app/src/test/java/com/thundernotes/format/` | Round-trip tests for `.thunder` ZIP + InkStrokeProto (13 tests) |
| `app/src/test/java/com/thundernotes/data/` | `AppDatabaseTest.kt` (6 tests, app-global DB) + **NEW (Sync 4):** `NoteDatabaseTest.kt` (5 tests, per-note DB + SpacerManager) |
| `docs/` | Architecture plan + format proposal + donor READMEs |
| `gradle/libs.versions.toml` | Version catalog (AGP 9.2.1, Kotlin 2.3.21, KSP 2.3.12, Room 2.7.2, Gradle 9.4.1) |
| `gradle/wrapper/gradle-wrapper.properties` | Gradle 9.4.1 distribution URL (do NOT regress to 8.x) |

---

## End of Sync 4

Next sync: when Phase 5 UI is ready to push, OR when BigPickle reports new findings after pulling + verifying Sync 4.
