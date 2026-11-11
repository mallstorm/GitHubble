# Матрица функций GitHubble 2.0

Статусы: WORKING (подтверждено тестом/CI/кодом), PARTIAL, BROKEN, DUPLICATE, CONFLICT, UNUSED,
PLACEHOLDER, UNKNOWN (не проверено), MISSING. «Подтверждено» = юнит-тест или зелёный CI; живой
эмулятор и реальные провайдеры в этом проходе не запускались.

| Область | Функция | Статус | Основание / примечание |
|---|---|---|---|
| Аккаунты | Вход, переключение, SESSION_LOCK, привязка клиента | WORKING | Тесты `Account*`, `GitHubSession`; D1 исправлен для agent-классов |
| Аккаунты | GitHub App-идентичность для действий агента | MISSING | Действия идут от пользовательского токена (автор — пользователь, без подмены); `github_capabilities` только читает установки; разбор по пунктам — `docs/github/GITHUB_APP.md` |
| Агент | JSON-цикл инструментов `ProjectRuntime` (≤10 раундов, ≤6/раунд, ≤60 всего) | WORKING | Тесты `ProjectRuntime*` |
| Агент | Разрешения READ_ONLY/REVIEW/LOCAL_AUTO/GITHUB_AUTO | WORKING | `AgentPermissionPolicy` + тесты |
| Агент | Режимы AUTO/SEMI/MANUAL (`ExecutionMode`) | PARTIAL | Политика, классификация и тест-инвариант; уровни в диалоге названы по режимам; сборки в AUTO без диалога в пределах бюджета (отличие от ТЗ: merge/релизы/настройки всё равно с подтверждением); отдельного переключателя помимо диалога разрешений нет |
| Агент | Явная машина состояний задачи (`TaskState`, `TaskJournal`) | PARTIAL | Каждый запуск агента в чате ведёт `TaskJournal` через `AgentRunJournal` (CREATED→ANALYZING→…→COMPLETED/PAUSED/FAILED/CANCELLED), состояние пишется на диск; чат-логика остаётся прежней (`AgentRun` в памяти) |
| Агент | Долговечное восстановление после перезапуска (`JournalStore`) | PARTIAL | Журнал пишется атомарно в `files/agent-journal`; при старте приложения запуски, оборванные закрытием процесса, помечаются FAILED. Автоматического возобновления задачи нет |
| Агент | Журнал инструментов и защита от двойного выполнения (`ToolLedger`) | PARTIAL | Встроен через `AgentRun`: каждый вызов записывается; повтор create_repository/branch/issue/pull_request и merge с теми же аргументами после успеха или неизвестного исхода отклоняется. Для остальных инструментов только запись. Журнал вызовов в памяти, на диск не пишется |
| Агент | Ревью записей и карточки файлов от хоста | WORKING | `WorkspaceChangeReview`, тесты |
| GitHub | Чтение: профиль, лимиты, ветки, файлы, issues, PR, детали | WORKING | `GitHubAgentOperations`, тесты моста |
| GitHub | Запись: репозиторий, ветка, issue, PR, merge, dispatch | WORKING | Требуют подтверждения по уровню |
| GitHub | `update_pull_request`, `comment_issue` | WORKING (новое) | Компиляция проверена CI; поведение на реальном GitHub в этом проходе не проверялось |
| GitHub | `build_run_status`, `build_artifacts`, `verify_build` | WORKING (новое) | Логика Supervisor — юнит-тесты; сетевая часть `GitHubEvidence` — только CI-компиляция |
| GitHub | `list_releases`, `create_release` (draft по умолчанию) | WORKING (новое) | Компиляция и юнит-тесты классификации в CI; живой вызов не проверялся. Загрузка файлов в релиз — MISSING |
| GitHub | Скачивание артефакта инструментом агента (`download_artifact`) | WORKING (новое) | Ставит загрузку в очередь; итог проверки APK агент читает через `artifact_verdict` (`PENDING`, пока загрузка идёт) |
| GitHub | Привязка запуска к `dispatch_workflow` (run id), проверка `workflow_dispatch` | WORKING (новое) | `WorkflowTrigger` и `DispatchRunMatcher` покрыты юнит-тестами; сетевая часть — только CI-компиляция |
| Сборка | Диагностика упавшей сборки (джобы, шаги, лог) | WORKING | `AgentBuildDiagnostics` |
| Сборка | Классификация причины (`BuildFailureClassifier`, 17 классов) | WORKING (новое) | 46 юнит-тестов локально, включая биллинг, runner, Kotlin/Java |
| Сборка | Repair-диалог только для исправимых в исходниках причин | WORKING (новое) | `AgentBuildRepairTicket`; `AgentBuildClassificationTest` |
| Сборка | Лимиты сборок/исправлений, backoff, повторы, WAIT_EXTERNAL (`RetryPolicy`) | WORKING (новое) | Применяется хостом в `await_build`/`dispatch_workflow` |
| Сборка | `await_build` (ожидание ≤60 с за вызов, решение о следующем шаге) | WORKING (новое) | Только компиляция CI; живой прогон не выполнялся |
| Сборка | Проверка APK (`ApkManifestReader`, `ArtifactVerifier`) | WORKING (новое) | Встроена в `BuildDownloadReceiver`: ZIP/CRC, classes.dex, манифест, подпись, сверка с `expect` (пакет/versionCode/versionName), вердикт сохраняется (`ApkVerdictStore`) и доступен агенту через `artifact_verdict`. При ручной загрузке без `expect` проверяется только структура. Криптографическая валидность подписи не проверяется (нужен apksigner, делает CI). `verify_build` сам APK не читает |
| Сборка | Авто-скачивание/установка после проверки | UNKNOWN | `BuildAutoDownloadPolicy` существует; связка с верификацией не сделана |
| Память | Память проекта, редактирование секретов | WORKING | `MemoryPolicy`, тесты |
| Память | Индекс символов/поиск по Workspace (`ProjectIndexRegistry`) | WORKING | Используется `symbol_index`/`search_files` через `WorkspaceTools`; тесты `ProjectIndexTest`. Поправка: ранее ошибочно помечен UNUSED. Индекса для удалённого репозитория без Workspace нет — MISSING |
| UI | Панель активности запуска (`AgentRunView`): шаги, итерации, статусы, ошибки | WORKING | Таймлайн RC4.31, тесты Agent chat UI |
| UI | Строка «Состояние задачи · режим» (из журнала) в панели запуска | WORKING (новое) | `AgentTaskLabels`, `AgentActivityPanelTest`; визуально на устройстве не проверялась |
| UI | Кнопка «Продолжить с контекстом» для прерванных/ошибочных/приостановленных запусков | PARTIAL (новое) | Перезапуск задачи с записанным контекстом (`ResumePrompt`), НЕ продолжение прежнего цикла модели; только для последнего запуска диалога |
| UI | Отдельный экран панели активности по всем задачам | MISSING | Есть только панель внутри диалога |
| UI | Уведомления агента (ждёт подтверждения, задача завершена/прервана) | WORKING (новое) | `AgentNotifier`, только когда приложение не на переднем плане, без аргументов инструментов; на устройстве не проверялось |
| Runtime | Remote Agent Runtime | MISSING | Только `AgentRun` в процессе приложения; анализ вариантов и проект API — `docs/architecture/REMOTE_RUNTIME*.md`; нужно решение владельца |
| Runtime | Классификация отказов GitHub API (`ApiFailure`) с инструкцией хоста «не обходить» | WORKING (новое) | `error_class`/`instruction` в результате инструмента; юнит-тесты классификации |
| Код | `AiConnectionStore`, `OmniChatChatGateway` | удалено (проход C) | Не имели ссылок; активное хранилище — `OmniChatConnectionStore` |
| Код | `AgentExecutionEngine.analyze` | UNUSED в боевом пути | Вытеснен `ProjectRuntime`; оставлен: его вызывает инструментальный smoke `GitAgentUiChecks`. `propose`/`applyApproved`/`executeReadOnly` в работе |
| Код | `app/src-compose` | REMOVED | Удалён по решению владельца; архив — ветка `claude/archive-src-compose-2026-11` (коммит 2f564f1) |
| Код | Дублирование `host.catalog()` в запросе | WORKING (исправлено) | Каталог хоста теперь в промпте один раз (в «CURRENT HOST CAPABILITIES» на каждом раунде) |
| CI | Android checks, Account UI, Agent chat UI, Xray smoke | WORKING | Зелёные на коммите 2c124ea |
