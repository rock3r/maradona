## Diagnosis Report

### (1) Failed tasks
- `./gradlew build` (broad), `./gradlew clean build`, and `./gradlew :maradona:build :sample-desktop:build` all **pass** (exit 0). The premise "the project does not build" is false for `build` itself — the failure is latent.
- The actual failure: **`:sample-desktop:run` FAILS** (exit 1).

### (2) Primary failure fingerprint with file:line evidence
- Fingerprint `6d6c21643e851fb9f5456bd8bc5b339e8616562b98ab9bba439965c50174cd39`: `Error: Could not find or load main class dev.maradona.sample.MainKt`
- Fingerprint `0d634c4bf4d7f05099ab975b596ed6b41fa6e134eebd34d7826778f13d5e1da2`: `Execution failed for task ':sample-desktop:run' (registered by plugin 'org.jetbrains.compose')`

**Root cause** — a source-set layout mismatch, not a compiler error:
- `sample-desktop/build.gradle.kts:8` declares a Kotlin **Multiplatform** target `jvm("desktop")`, whose main source set is `desktopMain`, i.e. `src/desktopMain/kotlin`.
- `sample-desktop/build.gradle.kts:29` sets `mainClass = "dev.maradona.sample.MainKt"`.
- But the only main source lives at `sample-desktop/src/main/kotlin/dev/maradona/sample/Main.kt:1` (package `dev.maradona.sample`) — the Kotlin/JVM single-target layout, which KMP silently ignores.
- Result: `compileKotlinDesktop` is effectively NO-SOURCE and `sample-desktop/build/libs/sample-desktop-desktop.jar` contains **0 files** (verified via `unzip -l`). `build` succeeds because an empty jar is valid and all tests (4/4 in `RenderScreenshotTest`, plus `:maradona` tests) pass; only `run`/packaging expose the missing class.

### (3) Minimal fix recommendation (for parent to apply — no build-file change needed)
Move the source directory to the KMP source-set layout:
```
git mv sample-desktop/src/main sample-desktop/src/desktopMain
```
(i.e. `sample-desktop/src/main/kotlin/...` → `sample-desktop/src/desktopMain/kotlin/...`). No edits to `build.gradle.kts` required; `desktopMain` already depends on `project(":maradona")` and the Compose desktop artifacts.

### (4) Narrowest verification command
```
python3 .../gradle_run.py run --workflow ca2ff77694578b257ad78843eee0d2f3 --scope targeted \
  --question "Is MainKt now compiled into the desktop jar?" -- ./gradlew :sample-desktop:compileKotlinDesktop :sample-desktop:desktopJar
```
Then confirm `sample-desktop-desktop.jar` contains `dev/maradona/sample/MainKt.class`. (`:sample-desktop:run` would then launch a GUI and block, so it is not the narrowest non-interactive check.)