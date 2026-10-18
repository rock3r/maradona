# Task for gradle-solver

Applied: manifest theme fixed to Theme.Material.Light.NoActionBar, and kotlin { androidLibrary {} } replaced with kotlin { android {} } in maradona and sample-shared to clear the deprecation. Verify /Users/rock3r/src/maradona. Workflow id: 5974526cbfa4616650c4ef643fb4144a. Wrapper runs only; NO edits.

Step 1 (targeted, question "Does assembleDebug pass after the manifest theme fix?"):
python3 /Users/rock3r/.agents/skills/gradle-run/scripts/gradle_run.py run --workflow 5974526cbfa4616650c4ef643fb4144a --scope targeted --question "Does assembleDebug pass after the manifest theme fix?" -- ./gradlew :sample-android:assembleDebug

Step 2 (targeted, question "Do all modules compile and tests pass with the android {} DSL?", broad-ish set):
python3 /Users/rock3r/.agents/skills/gradle-run/scripts/gradle_run.py run --workflow 5974526cbfa4616650c4ef643fb4144a --scope targeted --question "Do all modules compile and tests pass with the android {} DSL?" -- ./gradlew :maradona:assembleAndroidMain :sample-shared:assembleAndroidMain :sample-shared:linkDebugFrameworkIosSimulatorArm64 :sample-desktop:desktopTest :maradona:desktopTest

Report per-step: pass/fail, failure fingerprints with file:line evidence, minimal fix recommendations. Flag if the android {} rename itself broke anything. Do not edit anything.

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