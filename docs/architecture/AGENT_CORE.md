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
`Supervisor`/`Evidence`, `ArtifactVerifier`, `ExecutionMode` (как надстройка над уровнями разрешений) и
`AgentRunJournal`, который связывает `AgentRun` с `TaskJournal`, `JournalStore` и `ToolLedger`:

- запуск агента → ANALYZING; запись-инструмент → EXECUTING; `dispatch_workflow`/`await_build` → BUILDING;
  `verify_build` → VERIFYING; ожидание подтверждения → WAITING_PERMISSION; промежуточные состояния, которых в чате
  отдельного шага нет, записываются как «неявно».
- ответ без изменений, локальные правки без CI → COMPLETED из ANALYZING/EXECUTING/TESTING. Запущенная, но не проверенная
  сборка → PAUSED (не COMPLETED). Проверенная сборка → DELIVERING → COMPLETED.
- прерванный запуск → FAILED; отмена пользователем → CANCELLED; при следующем старте приложения активные журналы
  оборванных процессов помечаются FAILED.
- ограничения: журнал не восстанавливает цикл модели (возобновления нет); сохранение best-effort; журнал вызовов
  `ToolLedger` живёт только в памяти запуска; режим журнала берётся из уровня разрешений в момент старта.
