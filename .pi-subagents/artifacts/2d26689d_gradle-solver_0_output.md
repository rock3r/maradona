# Re-verification Diagnosis — /Users/rock3r/src/maradona

## Step 1: `:sample-android:assembleDebug :sample-desktop:compileKotlinDesktop :maradona:compileDebugKotlinAndroid` — **FAIL**

**Result:** Configuration-time failure (no tasks ran). Build fails in ~3s before any compilation.

**Primary failure fingerprint:** `595cd8febe1fa0cac50ec07ea8a9dd295b5fca713f52a4cdadf02d6f1ec076b1` (count 1, reproducible across runs; also reproduced with a reduced command `:maradona:compileDebugKotlinAndroid`, confirming it's not module-specific).

**Exact error (from bounded log extraction):**
```
An exception occurred applying plugin request [id: 'com.android.library', version: '9.4.1']
> Failed to apply plugin 'com.android.internal.library'.
  > java.lang.IllegalStateException: The 'com.android.library' (or 'com.android.application') plugin
    is not compatible with the 'org.jetbrains.kotlin.multiplatform' plugin since AGP 9.0.
    Solution:
      - [Recommended] Replace the 'com.android.library' plugin with the
        'com.android.kotlin.multiplatform.library' plugin
      - Or set the Gradle property 'android.builtInKotlin=false' and 'android.newDsl=false'
        to temporarily bypass this issue.
```

**Root cause with file:line evidence** — three modules apply an AGP plugin together with KMP, which AGP 9.x rejects at plugin-application time:

| Module | KMP plugin | Conflicting AGP plugin |
|---|---|---|
| `maradona/build.gradle.kts` | line 2 `alias(libs.plugins.kotlin.multiplatform)` | line 5 `alias(libs.plugins.android.library)` |
| `sample-shared/build.gradle.kts` | line 2 | line 5 `alias(libs.plugins.android.library)` |
| `sample-android/build.gradle.kts` | line 2 | line 5 `alias(libs.plugins.android.application)` |

Note: the sample-android KMP conversion (per the applied fixes) is what introduced this conflict there — `com.android.application` is equally incompatible with KMP under AGP 9. Version catalog: `gradle/libs.versions.toml:19,22` (`agp = "9.4.1"`, `kotlin = "2.4.20"`).

## Step 2: iOS simulator framework link — **NOT RUN** (blocked; step 1 failed at configuration, before any target compiles)

## Minimal fix recommendations (for parent to apply)

**Option A — smallest diff, temporary (explicitly offered by AGP):** add to `gradle.properties`:
```properties
android.builtInKotlin=false
android.newDsl=false
```
This restores legacy behavior and unblocks everything immediately, but is deprecated and will be removed in a future AGP.

**Option B — recommended, proper AGP 9 migration:**
1. `gradle/libs.versions.toml`: add `android-kmp-library = { id = "com.android.kotlin.multiplatform.library", version.ref = "agp" }`.
2. `maradona/build.gradle.kts` and `sample-shared/build.gradle.kts`: replace `alias(libs.plugins.android.library)` with `alias(libs.plugins.android.kmp.library)`; delete the top-level `android { }` block and move `namespace`/`compileSdk` into the KMP DSL: `kotlin { androidLibrary { namespace = "..."; compileSdk = 37 } }`.
3. `sample-android/build.gradle.kts`: there is no KMP application plugin — revert this module to `alias(libs.plugins.kotlin.android)` + `alias(libs.plugins.android.application)` and move sources from `src/androidMain` to `src/main` (standard kotlin-android layout).

**Narrowest next command after fix:** re-run the step 1 wrapper command unchanged (`--scope targeted`, same question). If it passes, proceed to step 2.

**Residual note:** the "Deprecated Gradle features... incompatible with Gradle 10" warning (fingerprint `63c0bf18...`) is generic noise, not a blocker.