# Agent Core

Пакет `app.githubble.mobile.agent.core` — чистая Java без Android-зависимостей, тестируется на JVM.

- `TaskState` — 17 состояний (CREATED…COMPLETED, PAUSED, WAITING_PERMISSION, WAITING_EXTERNAL,
  RECOVERING, FAILED, CANCELLED, ROLLED_BACK) с явной таблицей переходов.
- `TaskJournal` — история переходов, возобновление в прерванное состояние, сериализация; `JournalStore` —
  атомарная запись на диск.
- `ToolLedger` — запись каждого вызова (id операции, инструмент, отпечаток аргументов, состояние)
  и отказ от повторного выполнения изменяющего вызова, завершившегося успехом или с неизвестным исходом.
- `ExecutionMode` — политика AUTO/SEMI/MANUAL по классам действий.

**Статус интеграции:** используются в боевом пути `RetryPolicy`, `BuildFailureClassifier`, `FailureClass`,
`Supervisor`/`Evidence`, `ArtifactVerifier` (через `verify_build`). `TaskJournal`, `JournalStore`,
`ToolLedger`, `ExecutionMode` покрыты тестами, но чат ими пока не управляется.
