# Changelog

## Unreleased — GitHubble 2.0, проход I–III (PR #29)
- Fix: атомарная привязка GitHub-сессии в `AgentGitWorkspace`, `AgentBuildDiagnostics`, `AgentBuildArtifactController`.
- Add: пакет `agent.core` (машина состояний, журнал, ToolLedger, ExecutionMode, классификатор сбоев, RetryPolicy, Supervisor, проверка APK).
- Add: инструменты `build_run_status`, `build_artifacts`, `verify_build`, `update_pull_request`, `comment_issue`, `await_build`.
- Change: repair-диалог отказывает для сбоев не из-за исходников; `dispatch_workflow` ограничен бюджетом.
- Docs: аудит, архитектура, контракты. Remote Runtime, GitHub App-идентичность — не реализованы.
