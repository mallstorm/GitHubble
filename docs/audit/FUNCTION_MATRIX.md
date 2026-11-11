# Матрица функций GitHubble 2.0

Статусы: WORKING (подтверждено тестом/CI/кодом), PARTIAL, BROKEN, DUPLICATE, CONFLICT, UNUSED,
PLACEHOLDER, UNKNOWN (не проверено), MISSING. «Подтверждено» = юнит-тест или зелёный CI; живой
эмулятор и реальные провайдеры в этом проходе не запускались.

| Область | Функция | Статус | Основание / примечание |
|---|---|---|---|
| Аккаунты | Вход, переключение, SESSION_LOCK, привязка клиента | WORKING | Тесты `Account*`, `GitHubSession`; D1 исправлен для agent-классов |
| Аккаунты | GitHub App-идентичность для действий агента | MISSING | Действия идут от пользовательского токена; `github_capabilities` только читает установки |
| Агент | JSON-цикл инструментов `ProjectRuntime` (≤10 раундов, ≤6/раунд, ≤60 всего) | WORKING | Тесты `ProjectRuntime*` |
| Агент | Разрешения READ_ONLY/REVIEW/LOCAL_AUTO/GITHUB_AUTO | WORKING | `AgentPermissionPolicy` + тесты |
| Агент | Режимы AUTO/SEMI/MANUAL (`ExecutionMode`) | PARTIAL | Политика, классификация действий и тест-инвариант есть; в хосте работает как надстройка над 4 уровнями; отдельного переключателя в UI нет |
| Агент | Явная машина состояний задачи (`TaskState`, `TaskJournal`) | PARTIAL | Валидируется и сериализуется (тесты); чат её **не использует**, `AgentRun` остаётся в памяти |
| Агент | Долговечное восстановление после перезапуска (`JournalStore`) | PARTIAL | Атомарная запись и чтение есть; в runtime не вызывается |
| Агент | Журнал инструментов и защита от двойного выполнения (`ToolLedger`) | PARTIAL | Тесты есть; в хост не встроен. Фактическая защита сейчас — `AgentRun` (unknown после неоднозначной ошибки) |
| Агент | Ревью записей и карточки файлов от хоста | WORKING | `WorkspaceChangeReview`, тесты |
| GitHub | Чтение: профиль, лимиты, ветки, файлы, issues, PR, детали | WORKING | `GitHubAgentOperations`, тесты моста |
| GitHub | Запись: репозиторий, ветка, issue, PR, merge, dispatch | WORKING | Требуют подтверждения по уровню |
| GitHub | `update_pull_request`, `comment_issue` | WORKING (новое) | Компиляция проверена CI; поведение на реальном GitHub в этом проходе не проверялось |
| GitHub | `build_run_status`, `build_artifacts`, `verify_build` | WORKING (новое) | Логика Supervisor — юнит-тесты; сетевая часть `GitHubEvidence` — только CI-компиляция |
| GitHub | Создание релиза, листинг релизов, скачивание артефакта инструментом агента | MISSING | Скачивание есть в UI-пути (`AgentBuildArtifactController`), не как инструмент |
| GitHub | Привязка запуска к `dispatch_workflow` (run id), проверка `workflow_dispatch` | MISSING | Запуск определяется по `build_run_status` по ветке/sha |
| Сборка | Диагностика упавшей сборки (джобы, шаги, лог) | WORKING | `AgentBuildDiagnostics` |
| Сборка | Классификация причины (`BuildFailureClassifier`, 17 классов) | WORKING (новое) | 46 юнит-тестов локально, включая биллинг, runner, Kotlin/Java |
| Сборка | Repair-диалог только для исправимых в исходниках причин | WORKING (новое) | `AgentBuildRepairTicket`; `AgentBuildClassificationTest` |
| Сборка | Лимиты сборок/исправлений, backoff, повторы, WAIT_EXTERNAL (`RetryPolicy`) | WORKING (новое) | Применяется хостом в `await_build`/`dispatch_workflow` |
| Сборка | `await_build` (ожидание ≤60 с за вызов, решение о следующем шаге) | WORKING (новое) | Только компиляция CI; живой прогон не выполнялся |
| Сборка | Проверка APK (`ApkManifestReader`, `ArtifactVerifier`) | PARTIAL | Встроена в `BuildDownloadReceiver`: APK распаковывается в кэш, проверяются ZIP/CRC, classes.dex, манифест, наличие подписи; при FAIL в Загрузки не публикуется. Сверка с ожидаемым пакетом/версией при ручной загрузке не выполняется (ожидание неизвестно); `verify_build` APK не читает (`apk_inspected=false`) |
| Сборка | Авто-скачивание/установка после проверки | UNKNOWN | `BuildAutoDownloadPolicy` существует; связка с верификацией не сделана |
| Память | Память проекта, редактирование секретов | WORKING | `MemoryPolicy`, тесты |
| Память | Индекс кода в продакшн-пути | UNUSED | `ProjectIndex.kt` вызывается только тестами |
| UI | Activity Panel агента (отдельная панель шагов/состояний) | MISSING | Есть таймлайн сообщений чата RC4.31 |
| UI | Уведомления о событиях агента | MISSING | |
| Runtime | Remote Agent Runtime | MISSING | Только `AgentRun` в процессе приложения |
| Код | `AiConnectionStore`, `OmniChatChatGateway` | UNUSED/DUPLICATE | Нет вызовов, текст устарел |
| Код | `AgentExecutionEngine.analyze` | UNUSED | Вытеснен `ProjectRuntime` |
| Код | `app/src-compose` | UNUSED | Не входит в сборку |
| Код | Дублирование `host.catalog()` в запросе | CONFLICT | D5, лишние токены |
| CI | Android checks, Account UI, Agent chat UI, Xray smoke | WORKING | Зелёные на коммите 2c124ea |
