# Task for gradle-solver

Diagnose why the Gradle project at /Users/rock3r/src/maradona does not build. Workflow id: ca2ff77694578b257ad78843eee0d2f3.

Run the broad build via the wrapper with question "What is the primary build failure?":

python3 /Users/rock3r/.agents/skills/gradle-run/scripts/gradle_run.py run --workflow ca2ff77694578b257ad78843eee0d2f3 --scope broad --question "What is the primary build failure?" -- ./gradlew build

Read only the bounded JSON summary. Report: (1) failed tasks, (2) the primary source/compiler failure fingerprint with exact file:line evidence, (3) the minimal fix recommendation for the parent to apply, (4) the narrowest Gradle task to verify a fix. Do not edit anything.

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