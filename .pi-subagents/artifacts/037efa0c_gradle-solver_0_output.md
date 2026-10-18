Diagnosis complete. All evidence gathered; no files were edited.

## Result: FAIL — the zoom change does not compile on any target; tests never ran

### Failure summary

The full targeted run (`:maradona:assembleAndroidMain :maradona:compileKotlinIosSimulatorArm64 :maradona:desktopTest :sample-shared:compileKotlinIosSimulatorArm64 :sample-android:assembleDebug :sample-desktop:desktopTest`) fails at `:maradona:compileAndroidMain` and `:maradona:compileKotlinIosSimulatorArm64`. A follow-up desktop-only run confirms `:maradona:compileKotlinDesktop` also fails. All test and sample tasks were never reached.

All errors are in one file: `maradona/src/commonMain/kotlin/dev/maradona/render/MaradonaDiagram.kt`

### Failure fingerprint groups (9 distinct errors, 3 root causes)

**Root cause A — `Modifier.onPointerEvent` is not usable from commonMain with Compose Multiplatform 1.12.0** (blocker)
- `MaradonaDiagram.kt:26:42` — `Unresolved reference 'onPointerEvent'` (import) — fingerprints `edc5f47d…`, `3c5f1d7d…` (Android + iOS)
- `MaradonaDiagram.kt:100:14` — on desktop the API exists but is `@ExperimentalComposeUiApi`: "This API is experimental and is likely to change in the future" — fingerprint `cd1451b7…`
- Verified against artifacts: `onPointerEvent` lives in `SuspendingPointerInputFilter_skikoKt` (skiko/desktop-only) in `org.jetbrains.compose.ui:ui:1.12.0`; absent from the Android and iOS (`ui-uikit`) variants.
- Cascade errors (same root cause, `event` type uninferable): `100:56`, `101:35`, `102:36` (`changes`), `102:61`, `103:39` (`keyboardModifiers`), `104:75`, `105:33` (`scrollDelta`) — fingerprints `d6a3f910…`, `4fa66f79…`, `27a89a96…`, `1c06c7fb…`, `d0ce3015…`, `d73db79a…`

**Root cause B — `isCtrlPressed`/`isMetaPressed` no longer visible** (blocker, fails even on desktop)
- `MaradonaDiagram.kt:104:32` — `Unresolved reference 'isCtrlPressed' on receiver of type 'PointerKeyboardModifiers'` — fingerprint `45e25f30…`
- `MaradonaDiagram.kt:104:60` — `Unresolved reference 'isMetaPressed' on receiver of type 'PointerKeyboardModifiers'` — fingerprint `ec7a9600…`
- Verified: in CMP 1.12.0 `PointerKeyboardModifiers` is a bare packed-value inline class; the `isCtrlPressed`/`isMetaPressed` extension properties exist only in `PointerEvent.skiko.kt` (desktop-internal) and are not visible.

**Root cause C** — none. No test failures; tests were never executed.

### Minimal fix recommendation (for parent to apply)

1. Replace the `.onPointerEvent(PointerEventType.Scroll) { … }` block (lines ~100–110) with the stable common API: `Modifier.pointerInput(zoomEnabled) { awaitPointerEventScope { while (true) { val event = awaitPointerEvent(); if (event.type == PointerEventType.Scroll) { … } } } }`. `awaitPointerEvent`, `event.type/changes`, `scrollDelta`, `position`, `consume()` are all common and stable.
2. The Ctrl/Meta-gated wheel-zoom logic cannot stay in commonMain as-is (`keyboardModifiers` modifier checks are skiko-only). Narrowest fix: extract it behind an `expect fun Modifier.wheelZoom(enabled: Boolean, zoomBy: (Float, Offset) -> Unit): Modifier` — `actual` in `desktopMain` implementing the current logic (with `@OptIn(ExperimentalComposeUiApi::class)` if `onPointerEvent` is kept, or the `awaitPointerEventScope` loop using `keyboardModifiers.packedValue` bitmask checks), and no-op `actual`s in `androidMain`/`iosMain` (wheel zoom is meaningless there anyway). Pinch (`detectTransformGestures`) and double-tap reset already in commonMain are fine.
3. Delete the dead import at line 26.
4. Re-run the exact original targeted command to verify.

**Narrowest next command** (after fix): same as original —
`python3 …/gradle_run.py run --workflow a6cecafdde96d4758e04c182f9fc0b86 --scope targeted --question "Does the zoom change compile on all targets and pass tests?" -- ./gradlew :maradona:assembleAndroidMain :maradona:compileKotlinIosSimulatorArm64 :maradona:desktopTest :sample-shared:compileKotlinIosSimulatorArm64 :sample-android:assembleDebug :sample-desktop:desktopTest`