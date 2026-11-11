# Changelog

## Unreleased — GitHubble 2.0, проход I–III (PR #29)
- Fix: атомарная привязка GitHub-сессии в `AgentGitWorkspace`, `AgentBuildDiagnostics`, `AgentBuildArtifactController`.
- Add: пакет `agent.core` (машина состояний, журнал, ToolLedger, ExecutionMode, классификатор сбоев, RetryPolicy, Supervisor, проверка APK).
- Add: инструменты `build_run_status`, `build_artifacts`, `verify_build`, `update_pull_request`, `comment_issue`, `await_build`.
- Change: repair-диалог отказывает для сбоев не из-за исходников; `dispatch_workflow` ограничен бюджетом.
- Add: проверка APK при загрузке (`BuildDownloadReceiver` + `ArtifactVerifier`); классификация действий и надстройка `ExecutionMode` в хосте.
- Add: `AgentRunJournal` — durable-журнал каждого запуска агента, защита от повторов создания/merge, пометка оборванных запусков при старте.
- Add: проверка workflow и триггера `workflow_dispatch` до подтверждения, возврат `run_id` у `dispatch_workflow`; `list_releases`, `create_release`; `download_artifact`.
- Add: строка состояния задачи в панели запуска, кнопка «Продолжить с контекстом» (`ResumePrompt`), уведомления агента (`AgentNotifier`).
- Remove: неиспользуемые `AiConnectionStore`, `OmniChatChatGateway`.
- Fix: каталог хоста не дублируется в промпте.
- Docs: аудит, архитектура, контракты. Remote Runtime, GitHub App-идентичность — не реализованы.
