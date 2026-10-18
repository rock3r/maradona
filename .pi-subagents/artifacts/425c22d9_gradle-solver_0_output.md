## Diagnosis Report — AGP 9 migration verification of /Users/rock3r/src/maradona

### Step 1: Android + desktop compile — **FAIL (1 blocker, isolated)**

**Preliminary (diagnostic-command) failure** — fingerprint `45d220402df072177790d0e6e0200cc723046b248769bb9f765105672016bd0b`: the requested task `:maradona:compileDebugKotlinAndroid` **does not exist**. Under `com.android.kotlin.multiplatform.library` there are no debug/release build variants; verified via `:maradona:tasks --all` — the correct task is `:maradona:compileAndroidMain` (assemble equivalent: `:maradona:assembleAndroidMain`). Not a code problem; the verification command itself needed adjusting.

**Primary failure** — fingerprint `f8231048b1a148d0d728e9323db7d3683fdc274b03bc9f9ff514beb4733a0048`, task `:sample-android:processDebugResources` (AAPT link):
- **Evidence:** `ERROR: /Users/rock3r/src/maradona/sample-android/src/main/AndroidManifest.xml:4:5-16:19: AAPT: error: resource android:style/Theme.Material.NoTitleBar not found.` (AAPT merged-manifest coordinates; the offending line is **sample-android/src/main/AndroidManifest.xml:6**: `android:theme="@android:style/Theme.Material.NoTitleBar"`)
- **Root cause (proven outside Gradle, /tmp-only repro):** `Theme.Material.NoTitleBar` **does not exist in the Android framework at any API level**. Reproduced with `aapt2 link` (build-tools 37.0.0) against platforms android-34, 35, 36, and 37.0 — all fail identically. Dumping `resources.arsc` from android-36's android.jar confirms only `Theme.Material.NoActionBar` and `Theme.Material.Light.NoActionBar` exist (no `NoTitleBar` variant of Material). So this is a bad theme reference in the migrated manifest, not a platform/SDK/AGP issue. compileSdk 37 resolves fine to the installed `android-37.0` platform.
- **Minimal fix (parent to apply):** in `sample-android/src/main/AndroidManifest.xml` line 6, change `@android:style/Theme.Material.NoTitleBar` → `@android:style/Theme.Material.Light.NoActionBar` (or `Theme.Material.NoActionBar`) — a Compose app needs a NoActionBar base theme anyway.

**What passed in the same run:** `:sample-android:compileDebugKotlin`, and in a follow-up run `:sample-desktop:compileKotlinDesktop` + `:maradona:compileAndroidMain` (exit 0, no warnings beyond deprecations). Kotlin compilation is clean everywhere.

### Step 2: iOS simulator framework — **PASS**
`:sample-shared:linkDebugFrameworkIosSimulatorArm64` — exit 0, 106s, no failures. (Ran despite the step-1 blocker because it is isolated to sample-android resource linking and cannot affect the K/N link.)

### Non-blocking warnings worth fixing later
- `maradona/build.gradle.kts:12` and `sample-shared/build.gradle.kts:10`: `kotlin { androidLibrary { … } }` is deprecated — message says use `android { }` instead (Kotlin 2.4.20 / AGP 9.4.1).
- "commonTest source directory exists, but android host tests are not enabled" — add `withHostTest {}` if host tests are wanted.

### Narrowest next command (after the 1-line manifest fix)
```
python3 …/gradle_run.py run --workflow 5974526cbfa4616650c4ef643fb4144a --scope targeted --question "Does assembleDebug pass after the manifest theme fix?" -- ./gradlew :sample-android:assembleDebug
```