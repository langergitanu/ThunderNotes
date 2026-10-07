# ThunderNotes — Compatibility Report

**Repo:** `https://github.com/langergitanu/ThunderNotes`
**Clone location:** `~/android-projects/ThunderNotes`
**Verdict: ✅ FULLY COMPATIBLE** — builds & tests pass on the installed Android toolchain.

---

## 1. What the repo requires (from its committed config)

| Requirement | Value (from repo) |
|---|---|
| Gradle | **9.4.1** (pinned in `gradle/wrapper/gradle-wrapper.properties`) |
| Android Gradle Plugin (AGP) | **9.2.1** (`gradle/libs.versions.toml`) |
| Kotlin | **2.3.21** |
| KSP | **2.3.12** (Room annotation processor) |
| compileSdk | **36.1** (`release(36) { minorApiLevel = 1 }` — AGP 9 DSL) |
| targetSdk | **36** (Android 16) |
| minSdk | **31** (Android 12) |
| Java source/target | 17 |
| Key libs | Room 2.7.2 (KSP), Navigation 2.7.7, kotlinx-serialization 1.9.0 (json + protobuf), OkHttp 4.12.0, Robolectric 4.13 (tests) |

> ⚠️ **The README is stale.** It says "Gradle 8.9 + AGP 8.5.2 + Kotlin 2.0.21 + compileSdk 34", but the
> latest commit (`dce1c7a` "Phase 5 sync: apply BigPickle's 5 build fixes") migrated the toolchain to
> the AGP 9.x / Kotlin 2.3.x line. The actual config files are what matter — and they use the newer
> 2026-era versions.

## 2. What the installed environment provides

| Component | Installed | Meets repo requirement? |
|---|---|---|
| JDK | Temurin JDK 21 LTS (`~/jdk/jdk-21.0.12.1+1`, incl. `javac`) | ✅ AGP 9.x needs JDK 17+; 21 is fine |
| Android SDK cmdline-tools | 19.0 | ✅ |
| platform-tools (adb) | 37.0.1 | ✅ |
| build-tools | 34.0.0, 35.0.1, **36.0.0** | ✅ (36.0.0 covers compileSdk 36) |
| platforms | android-34, android-35, **android-36**, **android-36.1** | ✅ (36.1 was **auto-installed by AGP** on first build) |
| Gradle (standalone) | 8.11.1 (`~/gradle/...`) | ℹ️ Not used for building — the project's `./gradlew` wrapper auto-downloads Gradle 9.4.1 itself |
| Environment vars | `JAVA_HOME`, `ANDROID_HOME`, `GRADLE_HOME`, `PATH` | ✅ (in `~/.android_env.sh`, auto-sourced) |
| `local.properties` | created → `sdk.dir=/home/z/Android/Sdk` | ✅ |

## 3. Verification results

| Check | Result |
|---|---|
| All required 2026-era versions exist on the Maven repos (Gradle 9.4.1, AGP 9.2.1, Kotlin 2.3.21, KSP 2.3.12, Room 2.7.2, …) | ✅ probed HTTP 200 / 307 |
| `./gradlew assembleDebug` | ✅ BUILD SUCCESSFUL in 56s → `app-debug.apk` (18 MB) |
| `./gradlew assembleRelease` | ✅ BUILD SUCCESSFUL in 2m 44s → `app-release-unsigned.apk` (15 MB) |
| `./gradlew test` (Robolectric + JUnit) | ✅ BUILD SUCCESSFUL — **25/25 tests pass, 0 failures** |
| APK metadata | `com.thundernotes` v0.1.0, platformBuild Android 16 (API 36), minSdk 31 / targetSdk 36 |

Test breakdown:
- `AppDatabaseTest` — 6 tests ✓ (Room closure-table / folder / spacer schemas)
- `NoteDatabaseTest` — 6 tests ✓ (12 per-note DAOs)
- `InkStrokeSerializerTest` — 9 tests ✓ (protobuf stroke blobs)
- `ThunderFileRoundTripTest` — 4 tests ✓ (.thunder ZIP container round-trip)

## 4. One adaptation was required (memory)

The repo's `gradle.properties` set `org.gradle.jvmargs=-Xmx4g` (intended for a real dev machine).
This build host has only **4 GB RAM and no swap**, so the first build died at
`Task :app:compileDebugKotlin` with `Gradle build daemon disappeared unexpectedly`
(Linux OOM-killer). I made the following **sandbox-only** adaptation in
`gradle.properties` (originals preserved as comments so you can restore them on a
bigger machine):

```properties
# was -Xmx4g
org.gradle.jvmargs=-Xmx2g -XX:+UseParallelGC -Dfile.encoding=UTF-8
# were true
org.gradle.parallel=false
org.gradle.configuration-cache=false
```

→ On a real dev machine with ≥8 GB RAM, restore `-Xmx4g`, `parallel=true`, `configuration-cache=true`
for faster builds. The committed AGP-9 compatibility flags (`android.builtInKotlin=false`,
`android.newDsl=false`) were left untouched.

## 5. What's in the app so far (project status, per README + git log)

- **Phase 1–5 done**: project skeleton, Room schema (closure-table folders + 12 per-note DAOs +
  spacers via Fenwick prefix sums), `.thunder` container format (ZIP of manifest.json + note.sqlite +
  assets + preview), stroke protobuf serialization.
- **Home page UI present**: `fragment_thunder_home.xml` + `ThunderHomeFragment.kt` +
  `ThunderHomeViewModel.kt`. Plus 8 more library pages (folders, notes, bookmarks, templates, plugins,
  trash, create-folder/create-note modals, cover-selection, import-file) wired through a single-Activity
  NavHost (`nav_graph.xml`) with a sidebar (`item_sidebar.xml`).
- **Not yet built (per README architecture table)**: Hilt DI, AndroidX Ink canvas, KaTeX WebView,
  OpenCV LaTeX→stroke tracer, SnipEngine (Gemini/GLM/PaddleOCR), Pdfium export.

## 6. How to build from here

```bash
source ~/.android_env.sh
cd ~/android-projects/ThunderNotes

./gradlew assembleDebug       # → app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease     # → app/build/outputs/apk/release/app-release-unsigned.apk
./gradlew test                # 25 Robolectric/JUnit unit tests
./gradlew lint                # static checks
./gradlew clean               # wipe build/
```

## 7. Disk usage (after this verification)

```
~/Android                       1.2 GB   (SDK: platforms 34/35/36/36.1, build-tools 34/35/36, platform-tools)
~/jdk                           346 MB   (Temurin JDK 21 LTS)
~/gradle                        146 MB   (standalone Gradle 8.11.1)
~/.gradle                       2.6 GB   (Gradle 9.4.1 dist + AGP/Kotlin/KSP/AndroidX dep caches)
~/android-projects/ThunderNotes 144 MB   (repo + build outputs)
Free: 3.3 GB
```
