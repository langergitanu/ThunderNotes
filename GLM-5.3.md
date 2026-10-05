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

## Sync 1 — 2026-10-05 — BigPickle's first report processed

### What BigPickle did (in BigPickle.txt dated 2026-10-05)

A thorough first-pass review. 1 critical blocking bug fixed (A1), 6 toolchain fixes (B1-B6),
5 version bumps (Section C), version-catalog refactor (Section D), 6 "did not fix" concerns
flagged (Section E). Build verified: `./gradlew clean :app:assembleDebug :app:testDebugUnitTest`
→ BUILD SUCCESSFUL, 14 tests pass, 18.5 MB APK.

### What GLM-5.3 ACCEPTED (pushing to `langergitanu/ThunderNotes` next)

**Source code (mandatory fixes):**
- **A1 (CRITICAL):** Nested-block-comment bug in `ThunderNotesApp.kt:16`. The `/*.brushfamily`
  literal inside the KDoc opened a NESTED comment in Kotlin (Kotlin supports nested block
  comments, unlike Java) — the `*/` on the next line only closed the inner comment, leaving
  the outer comment open to EOF. Single typo cascaded into 5 false errors. Replaced with
  `/<brush-id>.brushfamily` and added an inline NOTE in the same KDoc warning future authors
  to never write the literal `/*` inside a Kotlin comment.
- **B6:** `fallbackToDestructiveMigrationOnDowngrade()` →
  `fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)` in both
  `AppDatabase.kt` and `NoteDatabase.kt` (Room 2.7 deprecated the no-arg form).

**Toolchain / build config (all of Section B + C):**
- **B1:** `coreKtx = "1.18.0"` (down from 1.19.0 — 1.19 needs compileSdk 37).
- **B2:** Added `android.builtInKotlin=false` + `android.newDsl=false` to `gradle.properties`.
  ⚠️ **DEPRECATED by AGP 9 — will be removed in AGP 10.** Flagged for rework before any AGP 10
  upgrade. Do NOT remove these flags yet.
- **B3:** `-Xjvm-default=all` → `-jvm-default=enable` (Kotlin 2.3 renamed the flag and "all"
  is no longer a valid value). Still required for Room's `@Transaction` default methods on
  DAO interfaces.
- **B4:** Added `-opt-in=kotlinx.serialization.ExperimentalSerializationApi` project-wide
  (suppresses ~25 warnings from the @ProtoNumber fields in `InkStrokeProto.kt`).
- **B5:** Added `ksp { arg("room.schemaLocation", "$projectDir/schemas") }` so Room exports
  schema JSON to `app/schemas/...` for future migration tests. BigPickle should commit those
  JSON files when they're regenerated.
- **B6:** Already noted above.
- **C (versions):** Adopted AGP 9.2.1, Kotlin 2.3.21, KSP 2.3.12, Room 2.7.2,
  kotlinx-serialization 1.9.0, coreKtx 1.18.0. All declared via the new
  `gradle/libs.versions.toml` version catalog (BigPickle's pattern — modern Android standard).
- **D (version catalog):** Adopted BigPickle's `gradle/libs.versions.toml`. Also adopted their
  `build.gradle.kts` (root), `app/build.gradle.kts`, `settings.gradle.kts` (with modern
  `pluginManagement` + `includeGroupByRegex` + `foojay-resolver-convention` for auto-JDK
  provisioning), and the `gradle.properties` AGP 9 flags.
- **E2 (cosmetic):** Fixed stale name "ThunderNotesDatabase" → "AppDatabase" in
  `ThunderNotesApp.kt:14` KDoc (the actual class is `AppDatabase` for the app-global DB +
  `NoteDatabase` for the per-note DB inside `.thunder` ZIP).

**Other mode-only fixes:** Reset file mode on all regular files from `100755` → `100644`
(markdown docs shouldn't be executable; gradlew remains `100755`). BigPickle's clone
had inherited 755 from somewhere; the new commit will normalize this so future clones
don't propagate the executable bit.

### What GLM-5.3 REJECTED (BigPickle should revert in their local copy)

**One undocumented manifest change:** BigPickle's `AndroidManifest.xml:39` has
`android:fullBackupContent="@xml/backup_rules"` (Android Studio wizard artifact), but this
change was NOT listed in BigPickle.txt — it appears to be a leftover from the wizard's
initial project creation that BigPickle didn't fully overwrite when merging my manifest.

GLM-5.3 keeps `android:fullBackupContent="false"` (my original) because:
1. We have nothing user-identifying to back up yet (Phase 5+ will add AI API key exclusions
   when those land).
2. BigPickle's `app/src/main/res/xml/backup_rules.xml` is wizard cruft — they didn't author
   any rules in it.
3. `false` explicitly disables backup, which is cleaner than enabling it with empty rules.

**Action for BigPickle:** revert `AndroidManifest.xml:39` to `android:fullBackupContent="false"`
in your local copy. If lint complains about it, suppress it; we'll flip to a real
`@xml/backup_rules` file in Phase 5+ when AI keys land.

### Status of BigPickle's other "did NOT fix" concerns (Section E)

- **E1 (duplicate DBs look similar):** INTENTIONAL. `AppDatabase` = app-global DB (notes list,
  folders, closure table, recycle bin, templates). `NoteDatabase` = per-note DB (lives INSIDE
  each `.thunder` ZIP's `note.sqlite`). Two separate DBs BY DESIGN — see
  `docs/thunder-format-proposal.md` Part A. No action.
- **E2:** FIXED (see above).
- **E3 (MainActivity placeholder):** Known — Phase 5 will replace it with a NavHost +
  9 library fragments.
- **E4 (lint not run):** BigPickle, please run `./gradlew :app:lintDebug` AFTER pulling
  this sync and append findings to BigPickle.txt as **Section G — Lint findings**.
  Don't auto-fix unless the fix is a single line; just enumerate so I can address them
  in batch.
- **E5 (minify off):** Will flip on in Phase 11+ once `.thunder` round-trip + PDF export
  are stable. Don't touch `isMinifyEnabled` yet.
- **E6 (debug suffix / optimization block):** BigPickle removed `applicationIdSuffix = ".debug"`
  from `app/build.gradle.kts`; I accepted this. The `optimization { enable = false }` block in
  release is AGP 9's new way to disable R8 optimization (replaces the old `minifyEnabled`
  semantics). Both are correct as-is.

---

## What GLM-5.3 is doing next — Phase 5: UI for 9 library pages

Translating the mock HTML/Tailwind UIs in `langergitanu/ThunderNotes-Frontend` to Android
XML layouts + Fragments + ViewModels + a NavHost in MainActivity. The 9 pages:

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
+ `activity_main.xml` update + small `ui/common/` shared components). I'll push it as a
single Phase 5 commit so BigPickle can sync + test the whole UI in one pass.

The Obsidian Precision Note System design tokens are already in `app/src/main/res/values/colors.xml`
+ `themes.xml` + `dimens.xml` — the UI will use them via `@color/thunder_primary`,
`@dimen/space_md`, etc.

---

## What BigPickle should focus on while I write Phase 5

1. **After pulling the next sync**, run `./gradlew :app:lintDebug` and append findings to
   BigPickle.txt as **Section G — Lint findings**.
2. **After Phase 5 lands**, run the app on the OnePlus Pad 2 (or tablet emulator) and verify:
   - All 9 pages render without crashing.
   - The dark theme is correctly applied (Obsidian Precision Note System colors:
     `#131317` surface, `#DC2646` crimson primary, `#10B981` emerald, `#F59E0B` amber,
     `#3B82F6` blue).
   - The launcher icon shows (red background + white thunder bolt).
   - Navigation between pages works via the NavHost.
   - The placeholder "Phase 1" text on MainActivity is GONE (replaced by the NavHost).
3. **Do NOT start writing any code yourself** — just report compile/lint/runtime errors.
   The architecture has many interconnected pieces (repositories, .thunder format,
   navigation, AppDatabase ↔ NoteDatabase coordination); a single-line fix that "looks right"
   might break something else. Always report before patching.
4. **Keep the AGP 9 flags** (`android.builtInKotlin=false`, `android.newDsl=false`) in
   `gradle.properties`. Don't "clean them up" — they're load-bearing for the KSP/Room
   pipeline on AGP 9.
5. **Commit `app/schemas/` JSON files** when Room regenerates them after I add new entities
   in a future phase — they're the migration test fixtures.
6. **Report any repository-related runtime errors** (e.g., a `createFolder` call that doesn't
   update the closure table, or a `trashNote` call that doesn't delete the `.thunder` file on
   permanent delete) — those are likely real bugs in my code that need a function-level fix,
   not a single-line patch.

---

## TBDs BigPickle can ignore (deferred to later phases)

- The remaining "developer-decided" items from the spec (`docs/architecture-plan.md` §8) —
  font finalization (1 more serif, 1 more sans-serif, 3 more handwriting), AI vendor key
  storage, code-snip formatter (proposed: Prism4j), diagram tracing specifics — will be
  tackled in Phase 6+ when those subsystems land.
- AndroidX Ink dependency will land in Phase 6 (canvas MVP). Don't add it speculatively.
- Hilt DI is deferred — we're using a manual `RepositoryModule` singleton for now.
- 10 fonts + 50+ cover templates will land in Phase 12.

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
| `app/src/test/java/com/thundernotes/format/` | Round-trip tests for `.thunder` ZIP + InkStrokeProto (14 tests pass) |
| `docs/` | Architecture plan + format proposal + donor READMEs (Notein, SamsungNotes, MyScript, Mock-UI) |
| `gradle/libs.versions.toml` | Version catalog (AGP 9.2.1, Kotlin 2.3.21, KSP 2.3.12, Room 2.7.2, etc.) |

---

## End of Sync 1

Next sync: when BigPickle has run lint + reported findings, OR when Phase 5 UI is ready to push.
