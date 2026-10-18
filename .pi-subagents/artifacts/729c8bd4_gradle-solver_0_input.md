# Task for gradle-solver

Zoom fix revised per your diagnosis: onPointerEvent replaced with common pointerInput + awaitPointerEventScope loop, and the missing extension imports (androidx.compose.ui.input.pointer.isCtrlPressed / isMetaPressed — these ARE public common expect vals in CMP 1.12, verified in ui-desktop-1.12.0-sources.jar) added. Re-run verification in /Users/rock3r/src/maradona. Workflow id: a6cecafdde96d4758e04c182f9fc0b86. Wrapper only; NO edits.

python3 /Users/rock3r/.agents/skills/gradle-run/scripts/gradle_run.py run --workflow a6cecafdde96d4758e04c182f9fc0b86 --scope targeted --question "Does the revised zoom change compile on all targets and pass tests?" -- ./gradlew :maradona:assembleAndroidMain :maradona:compileKotlinIosSimulatorArm64 :maradona:desktopTest :sample-shared:compileKotlinIosSimulatorArm64 :sample-android:assembleDebug :sample-desktop:desktopTest

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