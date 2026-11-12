# Changelog

## Unreleased — редизайн (PR #37, #38)
- Change: плоские компактные карточки без теней, единая шкала радиусов (`ui/DesignSystem`), компактные плитки репозитория, строки «Дополнительных действий».
- Add: `UiPolish` — страховка от слипшихся кнопок/чипов/плиток (LinearLayout и GridLayout), зелёные переключатели.
- Fix: «null» вместо описания/языка (`ui/JsonText`); цвет статуса «Сборка успешна»; тип файла релиза («APK», «ZIP»); загрузка релиза — заливка плашки и кнопка «Открыть».
- Add: `RedesignUiInstrumentation` и workflow «Redesign UI review» — реальные экраны на эмуляторе, скриншоты и аудит слипшихся/обрезанных элементов.

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
- Add: вердикт проверки APK на устройстве (`ApkVerdictStore`), `artifact_verdict`, `expect` у `download_artifact`; несовпадение пакета/версии = APK не публикуется.
- Change: уровни разрешений названы по режимам; в уровне «Авто» запуск сборки без диалога (бюджет 8, проверка workflow).
- Add: классификация отказов GitHub API (`ApiFailure`); `dispatch_workflow` сообщает, наблюдается ли репозиторий в Мониторе сборок.
- Docs: анализ Remote Runtime, проект контракта API, разбор GitHub App; симуляции сценариев D, E, F, G.
- Fix: каталог хоста не дублируется в промпте.
- Docs: аудит, архитектура, контракты. Remote Runtime, GitHub App-идентичность — не реализованы.
- Test: `SecurityPropertiesTest` (журнал без аргументов, защита от выхода из каталога, линейный разбор триггеров). Docs: итоговый отчёт `docs/audit/FINAL_REPORT.md`.
- Docs: решение владельца — бот-идентичность GitHub App не нужна (работа через пользовательский токен App «GiHubble»); Remote Runtime не планируется без отдельного запроса.
- Remove: `app/src-compose` (не входил в сборку; архив в ветке `claude/archive-src-compose-2026-11`). Fix: `tracking_max_pages` и `tracking_release_check_limit` редактируются в настройках отслеживания (диапазоны 1–10 и 1–30).
