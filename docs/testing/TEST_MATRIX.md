# Матрица тестов

| Набор | Что проверяет | Где выполнялся |
|---|---|---|
| `TaskJournalTest` | переходы, возобновление, сериализация, атомарное хранилище | локально (JVM) + CI |
| `ToolLedgerTest` | защита от двойного выполнения, unknown-исходы | локально + CI |
| `BuildFailureTest` | классификация (Kotlin/Java, billing, runner…), RetryPolicy | локально + CI |
| `ArtifactVerifierTest` | разбор бинарного манифеста; реальный APK rc4.2.30 (versionCode 77) | локально + CI |
| `SupervisorTest` | проверка утверждений по Evidence | локально + CI |
| `DispatchTrackingTest` | форма триггера workflow_dispatch, подбор запуска по run id | локально + CI |
| `ReleaseAndDispatchToolsTest` | каталог и классификация release-инструментов | CI |
| `AgentActivityPanelTest` | метки состояний, task_state в snapshot, события approval/finished, ResumePrompt | CI (Android-зависимости) |
| `ApkVerdictStoreTest` | итоговый статус, учёт по аккаунту/репозиторию, лимит истории, очистка ожидаемых значений | CI |
| `AgentRunJournalTest` | связка запуска с журналом: состояния, повторы, подтверждения, восстановление, недоступный каталог | локально + CI |
| `AgentRunJournalWiringTest`, `ExecutionModePolicyTest` | `AgentRun` ↔ журнал; инвариант политик | CI (Android-зависимости) |
| `AgentBuildClassificationTest` | `classified()` и отказ repair-тикета | CI (Android-зависимости) |
| Существующие тесты агента/аккаунтов | см. `app/src/test` | CI |

Локально выполнены 64 теста чистого ядра. Полный CI (unit, assembleDebug/Release, lintRelease, Xray smoke,
Account UI, Agent chat UI) — на PR #29. Не выполнялись: E2E-сценарии ТЗ A–H на устройстве, нагрузочные тесты.
