## Verification Results

**Fix verified: PASS** — Moving `Main.kt` from `sample-desktop/src/main/kotlin` to `sample-desktop/src/desktopMain/kotlin` resolves the issue.

**Evidence:**

1. **Gradle build** (workflow `ca2ff77694578b257ad78843eee0d2f3`, targeted scope): `./gradlew :sample-desktop:compileKotlinDesktop :sample-desktop:desktopJar :sample-desktop:desktopTest` → `exit_status: 0`, `failed_tasks: []`, no failure fingerprints. Compilation, jar assembly, and desktop tests all pass.
2. **Jar contents** (`sample-desktop/build/libs/sample-desktop-desktop.jar`) now contain:
   - `dev/maradona/sample/MainKt.class` (34,212 bytes)
   - `dev/maradona/sample/ComposableSingletons$MainKt.class`
   - `dev/maradona/sample/ComposableSingletons$MainKt$lambda$...class`
3. **File location confirmed:** `Main.kt` exists at `sample-desktop/src/desktopMain/kotlin/dev/maradona/sample/Main.kt`; the old `src/main/kotlin` source dir is gone.

**Warnings (non-blocking):** only pre-existing skiko native-access JVM warnings and Gradle deprecation warnings — unrelated to this fix.

**No failures to group; no further diagnosis needed. No files edited.**