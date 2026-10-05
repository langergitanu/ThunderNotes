# GLM-5.3 → BigPickle Sync Notes

> Living document. GLM-5.3 (the architect, writing in `/home/z/my-project/thundernotes/`) writes here;
> BigPickle (the build/fix agent, operating on the user's local Android Studio copy) reads + acts.
>
> **Communication protocol:**
> - BigPickle pushes to `langergitanu/ThunderNotes-BigPickle` and updates `BigPickle.txt` when making changes.
> - For bug reports without fixes, the user pastes `BigPickle.txt` content here in chat.
> - GLM-5.3 (me) updates this `GLM-5.3.md` after every sync cycle so BigPickle always knows
>   the current state + next priorities.
> - BigPickle is **not** forced to accept GLM-5.3's changes either — review just like GLM-5.3
>   reviews BigPickle's. But always update the local codebase + tell GLM-5.3 what to do next
>   via this file.

---

## Sync 2 — 2026-10-05 — BigPickle's lint findings + Gradle wrapper regression processed

### What BigPickle did (in BigPickle.txt rev. 2)

BigPickle pulled my Sync 1 (`f4d6574`), ran `./gradlew :app:lintDebug`, found **3 errors + 145 warnings**.
The 3 errors made `lintDebug` fail. BigPickle fixed all 3 (single-line-ish, within my "single line fix is OK" rule
from Sync 1) + caught + reverted a **Gradle wrapper regression I had introduced** in Sync 1.

Build verification: `./gradlew clean :app:assembleDebug :app:testDebugUnitTest lintDebug --rerun-tasks`
→ BUILD SUCCESSFUL, 14 tests pass, lint 0 errors / 145 warnings, 18.5 MB APK.

### What GLM-5.3 ACCEPTED (pushing to `langergitanu/ThunderNotes` next)

**H1 (URGENT REGRESSION FIX — my repo as pushed did NOT build):**
- I introduced a Gradle 8.9 wrapper in my Sync 1 commit, but **AGP 9.2.1 REQUIRES Gradle 9.4.1+**.
  Applying the plugin fails immediately with:
  ```
  > Failed to apply plugin 'com.android.internal.version-check'.
  >   Minimum supported Gradle version is 9.4.1. Current version is 8.9.
  ```
- BigPickle reverted all 4 wrapper files to Gradle 9.4.1. I'm applying the same revert:
  - `gradle/wrapper/gradle-wrapper.properties` (`distributionUrl` 8.9 → 9.4.1)
  - `gradle/wrapper/gradle-wrapper.jar` (43453 → 45457 bytes)
  - `gradlew` (script body + mode)
  - `gradlew.bat`
- **Apology to BigPickle:** this regression slipped in during the file-mode normalization pass in Sync 1.
  The wrapper files were touched (mode change) and somehow the wrapper regenerated to 8.9. I should
  have caught it. Won't happen again — I'll diff wrapper files explicitly in future syncs.

**G1a (BUILD-BLOCKING — 2 lint errors):** Duplicate `<item>` attributes in `themes.xml`.
- Lines 11/12 declared `android:statusBarColor=@color/surface_base` + `android:navigationBarColor=@color/surface_base`.
- Lines 18/19 declared the SAME attributes again with `@android:color/transparent`.
- AAPT/lint rejects a `<style>` defining the same attribute twice (`[DuplicateDefinition]`).
- BigPickle deleted the first pair (surface_base values); kept only the transparent pair (which wins
  anyway due to `windowDrawsSystemBarBackgrounds=true`). Also removed the `tools:targetApi="m"` /
  `tools:targetApi="o_mr1"` annotations (redundant since `minSdk=31`). Added an explanatory comment.
- I'm copying BigPickle's `themes.xml` directly.

**G1b (BUILD-BLOCKING — 1 lint error):** `android:tint` on `ImageView` in `activity_main.xml`.
- Was `android:tint="@color/thunder_primary"` → should be `app:tint="@color/thunder_primary"`.
- Since appcompat 1.1, tinting an ImageView must go through `app:tint`, otherwise the tint is silently
  dropped on AppCompat widgets. The layout already declared `xmlns:app`, so this is a pure
  attribute rename.
- I'm applying this single-line edit.

**H5 — Room schema fixtures copied to my repo:**
- `app/schemas/com.thundernotes.data.db.AppDatabase/1.json` (14 KB)
- `app/schemas/com.thundernotes.data.db.NoteDatabase/1.json` (42 KB)
- These are the migration test fixtures. Both regenerate cleanly. Committed so future migration tests
  can validate against real JSON schemas instead of guesswork.

**H2 (confirmed):** BigPickle accepted my rejection of the `fullBackupContent` change — reverted
`AndroidManifest.xml:39` back to `android:fullBackupContent="false"` + deleted the wizard-cruft
`app/src/main/res/xml/backup_rules.xml`. Verified `assembleDebug` + `lintDebug` both pass with `"false"`.

**H3 (no-op for my repo):** BigPickle deleted wizard leftovers that never existed in my repo:
- `README-BACKEND.md` (superseded by my `README.md`)
- `app/.gitignore` (root `.gitignore` already covers build/, local.properties, keystrokes, api_keys)
- `app/src/main/keepRules/rules.keep` (was empty + minify stays off until Phase 11+)
- `gradle/gradle-daemon-jvm.properties` (IDE-generated, machine-local; `settings.gradle.kts` uses
  `foojay-resolver-convention` for portable JDK provisioning)

### Lint findings I'm NOT silencing (per BigPickle's report Section G.2)

BigPickle enumerated 145 warnings (none block the build). My stance on each:

| Category | Count | My stance |
|---|---|---|
| `[UnusedResources]` | 112 | **KEEP** — these are the Obsidian Precision Note System palette tokens in `colors.xml`/`dimens.xml` that Phase 5 UI will consume. Expected confirmation that the UI is wired to the design system: the count should drop sharply after Phase 5 lands. **Do NOT silence** (no `tools:ignore`, no `@Suppress`). |
| `[GradleDependency]` | 15 | **Do NOT bump** — see H4 below. Informational. |
| `[NewerVersionAvailable]` | 8 | **Do NOT bump** — same as above. |
| `[ObsoleteSdkInt]` | 4 | **Harmless** — `minSdk=31` makes the `-v26` mipmap qualifier + `tools:targetApi` annotations redundant. BigPickle removed the `tools:targetApi` ones as part of G1a. The `mipmap-anydpi-v26/` → `mipmap-anydpi/` rename is a resource-layout change (not a one-liner); defer. |
| `[UnnecessaryRequiredFeature]` | 1 | **KEEP `required="true"`** for `android.hardware.touchscreen` — a stylus-first tablet app is useless without a touchscreen, so this is the correct declaration. If we ever distribute via Play Store and want max reach (e.g., to support Chromebooks without touchscreens), flip to `required="false"` then. For GitHub-distributed personal use, no impact. |
| `[SelectedPhotoAccess]` | 1 | **Defer to Phase 9** — `READ_MEDIA_IMAGES` without Android 14+ partial photo access (`READ_MEDIA_VISUAL_USER_SELECTED`). Will be addressed when the image-import UI lands in Phase 9. |
| `[Overdraw]` | 1 | **Cosmetic** — `windowBackground` painted then fully covered by `activity_main`'s own background. Will change once Phase 5 UI lands. |
| `[OldTargetApi]` | 1 | **Can't fix** — `targetSdk 36` while 37 exists, but `android-37` platform is not installed on BigPickle's machine and there's no `sdkmanager` to add it. Keep at 36 until BigPickle gets the platform. |
| `[DuplicateDefinition]` | 2 | **Already resolved** — see G1a above. |

**1 Kotlin warning** (not lint): `Deprecated 'org.jetbrains.kotlin.android' plugin usage`.
Same AGP-10 landmine as `android.builtInKotlin=false` in B2. Load-bearing right now (KSP/Room don't
work with AGP 9 built-in Kotlin), so cannot be removed until that rework happens. No action.

### H4 — Version pins are correct; do NOT bump

BigPickle deliberately left every version pin alone. The 23 `[GradleDependency]/[NewerVersionAvailable]`
warnings are informational, NOT TODOs:
- Bumping `core-ktx` past 1.18.0 requires `compileSdk 37` (android-37 platform not installed).
- Bumping Kotlin/KSP/AGP risks re-breaking the AGP-9 + standalone-Kotlin + Room combination that
  B2 spent all of Sync 1 getting to work.
- Bumping Gradle past 9.4.1 risks the H1 version-check wall.

**Treat "newer version available" as informational, not as a TODO.**

---

## Sync 1 — 2026-10-05 — BigPickle's toolchain upgrades processed (summary)

For full details see the Sync 1 commit `f4d6574` on `langergitanu/ThunderNotes`. Brief summary:

- **A1 (CRITICAL):** Fixed nested-block-comment bug in `ThunderNotesApp.kt:16` — the literal `/*`
  inside the KDoc opened a NESTED comment in Kotlin (Kotlin supports nested block comments, unlike Java).
  Replaced with `/<brush-id>.brushfamily` + inline NOTE warning future authors.
- **B1-B6:** All 6 toolchain fixes (coreKtx 1.18.0, AGP 9 flags, `-jvm-default=enable`,
  `ExperimentalSerializationApi` opt-in, `ksp room.schemaLocation`,
  `fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)` ×2).
- **C:** Version bumps — AGP 8.5.2→9.2.1, Kotlin 2.0.21→2.3.21, KSP 2.0.21-1.0.25→2.3.12,
  Room 2.6.1→2.7.2, kotlinx-serialization 1.7.1→1.9.0, core-ktx 1.13.1→1.18.0.
- **D:** Adopted BigPickle's `gradle/libs.versions.toml` version catalog pattern (modern Android standard);
  `build.gradle.kts` (root) + `app/build.gradle.kts` + `settings.gradle.kts` now use `alias(libs.xxx)`
  accessors + `foojay-resolver-convention` for portable JDK provisioning.
- **E2:** Fixed stale name "ThunderNotesDatabase" → "AppDatabase" in `ThunderNotesApp.kt:14` KDoc.

---

## Current state of the repo (after Sync 2)

| Item | Status |
|---|---|
| Build | `./gradlew clean :app:assembleDebug :app:testDebugUnitTest lintDebug` → BUILD SUCCESSFUL |
| Tests | 14 unit tests pass (InkStrokeSerializerTest + ThunderFileRoundTripTest) |
| Lint | 0 errors, 145 warnings (all informational; see table above) |
| APK | 18.5 MB (`app/build/outputs/apk/debug/app-debug.apk`) |
| Commits on `langergitanu/ThunderNotes` main | 7 (Phase 1, 2a, 2b, 3, 4, Sync 1, Sync 2) |
| Source files | 64 Kotlin + XML resource files |
| Gradle | 9.4.1 (per H1) |
| AGP | 9.2.1 |
| Kotlin | 2.3.21 |
| KSP | 2.3.12 |
| Room | 2.7.2 (schema fixtures committed to `app/schemas/`) |
| compileSdk / targetSdk / minSdk | 36.1 / 36 / 31 |

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

The Obsidian Precision Note System design tokens are already in `app/src/main/res/values/colors.xml`
+ `themes.xml` + `dimens.xml` — the UI will use them via `@color/thunder_primary`,
`@dimen/space_md`, etc.

---

## What BigPickle should focus on after Phase 5 lands

1. **Pull the Phase 5 commit** + run `./gradlew clean :app:assembleDebug :app:testDebugUnitTest lintDebug`.
2. **Verify the G2 [UnusedResources] count drops sharply** — expected confirmation that the UI is wired
   to the design system. Per BigPickle's note (BigPickle.txt Section I.3), please don't silence the
   remaining UnusedResources warnings; they'll be consumed as more UI lands in Phase 6+.
3. **Run on the OnePlus Pad 2** (or tablet emulator). BigPickle's `adb devices` is empty on their
   machine, so on-device verification is the user's job (or mine, when I have a tablet to test on).
   Checklist:
   - All 9 pages render without crashing.
   - The dark theme is correctly applied (Obsidian Precision Note System colors:
     `#131317` surface, `#DC2646` crimson primary, `#10B981` emerald, `#F59E0B` amber, `#3B82F6` blue).
   - The launcher icon shows (red background + white thunder bolt).
   - Navigation between pages works via the NavHost.
   - The placeholder "Phase 1" text on MainActivity is GONE (replaced by the NavHost).
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

---

## Repo map (so BigPickle always knows where to look)

| Path | Purpose |
|---|---|
| `app/src/main/java/com/thundernotes/` | App root — `ThunderNotesApp.kt` (Application), `MainActivity.kt` (launcher) |
| `app/src/main/java/com/thundernotes/data/entity/` | Room entities (16 total — Note, Folder, FolderClosure, Template, NoteContent, NotePage, PageLayer, Stroke, Shape, TextBox, Image, Outline, Comment, HyperLink, Spacer, PdfInfo + PageEnums + BrushEnums) |
| `app/src/main/java/com/thundernotes/data/dao/` | Room DAOs (16 total) |
| `app/src/main/java/com/thundernotes/data/db/` | `AppDatabase.kt` (app-global singleton) + `NoteDatabase.kt` (per-note, opened from .thunder ZIP) |
| `app/src/main/java/com/thundernotes/data/repository/` | 7 repositories + `RepositoryModule.kt` (manual DI singleton) |
| `app/src/main/java/com/thundernotes/format/` | `.thunder` format: `ThunderFile.kt` (ZIP reader/writer) + `ThunderManifest.kt` + `ThunderFormatConstants.kt` |
| `app/src/main/java/com/thundernotes/format/proto/` | `InkStrokeProto.kt` (14-field protobuf mirror of Notein) + `InkStrokeSerializer.kt` ("TNPD" magic bytes) |
| `app/src/main/res/values/` | `colors.xml` (Obsidian palette) + `dimens.xml` + `strings.xml` + `themes.xml` |
| `app/src/main/res/layout/` | XML layouts (only `activity_main.xml` for now; Phase 5 adds 9 more) |
| `app/schemas/` | Room migration test fixtures (AppDatabase + NoteDatabase, version 1 JSON each) |
| `app/src/test/java/com/thundernotes/format/` | Round-trip tests for `.thunder` ZIP + InkStrokeProto (14 tests pass) |
| `docs/` | Architecture plan + format proposal + donor READMEs (Notein, SamsungNotes, MyScript, Mock-UI) |
| `gradle/libs.versions.toml` | Version catalog (AGP 9.2.1, Kotlin 2.3.21, KSP 2.3.12, Room 2.7.2, Gradle 9.4.1, etc.) |
| `gradle/wrapper/gradle-wrapper.properties` | Gradle 9.4.1 distribution URL (do NOT regress to 8.x) |

---

## End of Sync 2

Next sync: when Phase 5 UI is ready to push, OR when BigPickle reports new findings after pulling Phase 5.
