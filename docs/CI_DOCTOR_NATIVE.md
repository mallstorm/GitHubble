# CI Doctor (native Actions run detail)

CI Doctor is available from **Центр сборок → Детали запуска → CI Doctor · Разобрать ошибку** for failing or timed-out GitHub Actions runs.

## Scope
- Reuses the existing account/session-bound AgentBuildDiagnostics and BuildFailureClassifier; no duplicate log client or classifier.
- Reads failed jobs and bounded, secret-redacted excerpts from up to two job logs.
- Shows a cautious diagnosis and category-specific next action. Unknown failures do not suggest source changes.
- Does not restart workflows, alter billing, modify refs/files or send logs to an AI model.
- Optional **Исправление через агента** is shown only for source-fixable failures in a matching agent project. Existing AgentBuildRepairTicket verifies HEAD/workspace revision and obtains explicit consent before model transfer.
- **GitHub Actions** opens the official run page for full logs and manual reruns.

## Validation
Android, Mirror Manager CI and R8 workflows are required before merge. Six focused JUnit tests verify safety decisions and fail-closed treatment of unknown classes. End-to-end inspection of a failed private job log still needs an authenticated device smoke test.
