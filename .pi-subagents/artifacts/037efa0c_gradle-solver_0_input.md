# Task for gradle-solver

Verify a zoom-gesture change to MaradonaDiagram.kt (commonMain) compiles everywhere and tests still pass in /Users/rock3r/src/maradona. Workflow id: a6cecafdde96d4758e04c182f9fc0b86. Wrapper runs only; NO edits.

Run (targeted, question "Does the zoom change compile on all targets and pass tests?"):
python3 /Users/rock3r/.agents/skills/gradle-run/scripts/gradle_run.py run --workflow a6cecafdde96d4758e04c182f9fc0b86 --scope targeted --question "Does the zoom change compile on all targets and pass tests?" -- ./gradlew :maradona:assembleAndroidMain :maradona:compileKotlinIosSimulatorArm64 :maradona:desktopTest :sample-shared:compileKotlinIosSimulatorArm64 :sample-android:assembleDebug :sample-desktop:desktopTest

Report pass/fail, failure fingerprints with file:line evidence, minimal fix recommendations. Do not edit anything.

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