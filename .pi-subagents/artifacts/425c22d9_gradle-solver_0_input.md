# Task for gradle-solver

Migrated to the proper AGP 9 pattern (from JetBrains/kmp-production-sample): maradona + sample-shared now use com.android.kotlin.multiplatform.library with kotlin { androidLibrary { namespace/compileSdk } } (no androidTarget(), no top-level android{}), sample-android uses com.android.application with built-in Kotlin (no kotlin plugin; top-level kotlin { compilerOptions/dependencies } + compose.multiplatform + kotlin.plugin.compose, sources back in src/main). Wrapper is gradle-9.8.0, AGP 9.4.1, Kotlin 2.4.20, Compose 1.12.0. Re-verify /Users/rock3r/src/maradona. Workflow id: 5974526cbfa4616650c4ef643fb4144a. Wrapper runs only; NO edits — report diagnosis and minimal fixes to the parent.

Step 1 (targeted, question "Do Android and desktop compile under AGP 9.4.1?"):
python3 /Users/rock3r/.agents/skills/gradle-run/scripts/gradle_run.py run --workflow 5974526cbfa4616650c4ef643fb4144a --scope targeted --question "Do Android and desktop compile under AGP 9.4.1?" -- ./gradlew :sample-android:assembleDebug :sample-desktop:compileKotlinDesktop :maradona:compileDebugKotlinAndroid

If step 1 passes, step 2 (targeted, question "Does the iOS simulator framework link?" — Kotlin/Native toolchain download may make this slow):
python3 /Users/rock3r/.agents/skills/gradle-run/scripts/gradle_run.py run --workflow 5974526cbfa4616650c4ef643fb4144a --scope targeted --question "Does the iOS simulator framework link?" -- ./gradlew :sample-shared:linkDebugFrameworkIosSimulatorArm64

Report per-step: pass/fail, primary failure fingerprint with exact file:line evidence, minimal fix recommendation. Do not edit anything.

## Acceptance Contract
Acceptance level: attested
Completion is not accepted from prose alone. End with a structured acceptance report.

Criteria:
- criterion-1: Return concrete findings with file paths and severity when applicable

Required evidence: review-findings, residual-risks

Finish with a fenced JSON block tagged `acceptance-report` in this shape:
Use empty arrays when no items apply; array fields contain strings unless object entries are shown.
`criteriaSatisfied[].status` must be exactly one of: satisfied, not-satisfied, not-applicable.
`commandsRun[].result` must be exactly one of: passed, failed, not-run.
`manualNotes` and `notes` are optional strings; an empty string means no note and does not satisfy `manual-notes` evidence.
```acceptance-report
{
  "criteriaSatisfied": [
    {
      "id": "criterion-1",
      "status": "satisfied",
      "evidence": "specific proof"
    }
  ],
  "changedFiles": [
    "src/file.ts"
  ],
  "testsAddedOrUpdated": [
    "test/file.test.ts"
  ],
  "commandsRun": [
    {
      "command": "command",
      "result": "passed",
      "summary": "short result"
    }
  ],
  "validationOutput": [
    "validation output or concise summary"
  ],
  "residualRisks": [
    "none"
  ],
  "noStagedFiles": true,
  "diffSummary": "short description of the diff",
  "reviewFindings": [
    "blocker: file.ts:12 - issue found, or no blockers"
  ],
  "manualNotes": "anything else the parent should know"
}
```