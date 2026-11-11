# Системная архитектура (как реализовано)

```
UI (MainActivity + контроллеры)
  └─ ProjectRuntime            JSON-цикл инструментов (≤10 раундов, ≤6/раунд, ≤60 всего)
       └─ NativeAgentToolHost  политика разрешений, ревью, привязка аккаунта/диалога/HEAD
            ├─ GitHubAgentOperations / GitHubAgentBridge   типизированные GitHub-инструменты
            ├─ AgentGitWorkspace, WorkspaceChangeReview    локальные правки и ревью
            ├─ AgentBuildDiagnostics / RepairTicket / ArtifactController
            └─ agent.core.*  чистая Java-логика: RetryPolicy, BuildFailureClassifier,
                             Supervisor, ArtifactVerifier, ApkManifestReader
```

Границы доверия: модель только предлагает вызов инструмента; хост проверяет имя и аргументы по каталогу,
разрешения, аккаунт и лимиты, исполняет и возвращает результат. Файлы, PR, сборки и APK объявляются
«готовыми» только по данным хоста/GitHub, не по тексту модели.

Не реализовано: Remote Agent Runtime, долговечная задача (журнал создан, но не подключён),
GitHub App-идентичность. См. `docs/audit/FUNCTION_MATRIX.md`.
