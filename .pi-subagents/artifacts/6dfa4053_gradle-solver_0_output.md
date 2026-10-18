All evidence gathered. Compiling the final diagnosis.

## Diagnosis Report

### Environment note (pre-build blocker, worked around for verification)
- `gradle/wrapper/gradle-wrapper.properties:3` — `distributionUrl=...gradle-9.5-bin.zip` returns **HTTP 404**; that distribution does not exist. The 9.5 line only publishes three-part names: `gradle-9.5.0-bin.zip` (200) and `gradle-9.5.1-bin.zip` (200).
- To run verification without editing the repo, I pre-populated the wrapper cache (`~/.gradle/wrapper/dists/gradle-9.5-bin/csvdp1e2a2n81wwwhkxdjm2mb/`) with the 9.5.1 distribution. **The repo fix is still required** — any clean machine will 404.
- **Minimal fix:** change `gradle-wrapper.properties:3` to `gradle-9.5.1-bin.zip`.

### Step 1 — FAIL
`./gradlew :sample-android:assembleDebug :sample-desktop:compileKotlinDesktop :maradona:compileDebugKotlinAndroid`
- Failed task: `:sample-android:checkDebugAarMetadata`
- Primary fingerprint `40cd645d…`: **16 AAR-metadata issues** — all `androidx.compose.*:1.12.0` artifacts require **compileSdk ≥ 37 and AGP ≥ 9.1.0**; project is on compileSdk 36 / AGP 8.13.2 (log 0003, lines 55–116).
- Evidence: `gradle/libs.versions.toml:3` `compose = "1.12.0"`, `:5` `agp = "8.13.2"`, `:7` `android-compile-sdk = "36"`, `:9` `android-target-sdk = "36"`; consumed at `sample-android/build.gradle.kts:9,13,14`.
- Follow-up run (`:sample-desktop:compileKotlinDesktop :maradona:compileDebugKotlinAndroid`, log 0004): **BUILD SUCCESSFUL** — desktop target and the `maradona` Android library compile fine with Gradle 9.5.1. The only Step-1 blocker is the `sample-android` app's SDK/AGP level.
- **Minimal fix (either/or):**
  - (a) `libs.versions.toml`: `agp = "9.1.0"`, `android-compile-sdk = "37"`, `android-target-sdk = "37"` — android-37.0/37.1 platforms and build-tools 37.0.0 are already installed in `/Users/rock3r/android-sdk`. Note: AGP 9.x has breaking changes (built-in Kotlin), so this may surface follow-up issues.
  - (b) Pin `compose` to a 1.9.x line compatible with compileSdk 36 / AGP 8.x (one line, lower blast radius; must be checked against `kotlin = "2.4.20"`).

### Step 2 — FAIL
`./gradlew :sample-shared:linkDebugFrameworkIosSimulatorArm64`
- Failed task: `:maradona:compileKotlinIosSimulatorArm64`
- Primary fingerprint `a62d8a56…`: **`maradona/src/commonMain/kotlin/dev/maradona/layout/FlowchartLayout.kt:68:26` — `Unresolved reference 'sortedMapOf'`**, with cascading inference errors at `:69:40`, `:69:73`, `:78:28`, `:78:46`, `:84:92` (log 0005).
- Root cause: `sortedMapOf` is JVM-only stdlib; it doesn't exist in Kotlin/Native common. It compiled on desktop/Android (Step 1 follow-up) but fails on iOS.
- **Minimal fix:** at `FlowchartLayout.kt:68`, replace `sortedMapOf<Int, MutableList<Item>>()` with `mutableMapOf<Int, MutableList<Item>>()` — line 84 already orders via `layers.keys.sorted()`, so the sorted map is redundant; zero behavioral change.

### Non-blocking warnings (residual)
- Fingerprint `a7182e8a…` (16×): Compose 1.12.0 does not resolve for **iosX64** (`sample-shared/build.gradle.kts:13` declares `iosX64()`). Compose 1.12.0 no longer publishes iosX64 artifacts. It only errors during lenient metadata reporting today, but **any iosX64 compile/link task will fail**. Minimal fix: drop `iosX64()` from the target list at `sample-shared/build.gradle.kts:12–16` (and in `maradona/` if declared there too), or pin Compose to a version still shipping iosX64.
- `iosX64Test` disabled warning; "SDK XML version 4" notice — informational only.

### Narrowest next commands (after parent applies fixes)
1. Wrapper URL fix → `./gradlew :sample-desktop:compileKotlinDesktop :maradona:compileDebugKotlinAndroid` (sanity, ~30s)
2. SDK/AGP fix → `./gradlew :sample-android:assembleDebug`
3. FlowchartLayout fix → `./gradlew :sample-shared:linkDebugFrameworkIosSimulatorArm64`