# Матрица тестов

| Набор | Что проверяет | Где выполнялся |
|---|---|---|
| `TaskJournalTest` | переходы, возобновление, сериализация, атомарное хранилище | локально (JVM) + CI |
| `ToolLedgerTest` | защита от двойного выполнения, unknown-исходы | локально + CI |
| `BuildFailureTest` | классификация (Kotlin/Java, billing, runner…), RetryPolicy | локально + CI |
| `ArtifactVerifierTest` | разбор бинарного манифеста; реальный APK rc4.2.30 (versionCode 77) | локально + CI |
| `SupervisorTest` | проверка утверждений по Evidence | локально + CI |
| `AgentBuildClassificationTest` | `classified()` и отказ repair-тикета | CI (Android-зависимости) |
| Существующие тесты агента/аккаунтов | см. `app/src/test` | CI |

Локально выполнены 46 тестов чистого ядра. Полный CI (unit, assembleDebug/Release, lintRelease, Xray smoke,
Account UI, Agent chat UI) — на PR #29. Не выполнялись: E2E-сценарии ТЗ A–H на устройстве, нагрузочные тесты.
