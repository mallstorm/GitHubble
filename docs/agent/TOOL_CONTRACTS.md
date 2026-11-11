# Контракты инструментов

Каталог — `GitHubAgentOperations.READ_CATALOG/WRITE_CATALOG` (источник истины, поддерживается тестами моста).

Чтение: github_profile, github_usage, github_rate_limit, github_capabilities, repository_branches,
repository_read_file, repository_issues, repository_pulls, issue_details, pull_request_details,
build_artifacts, verify_build, build_run_status.

Запись (с подтверждением по уровню): create_repository, create_branch, update_repository,
update_account_profile, create_issue, update_issue, create_pull_request, update_pull_request,
comment_issue, merge_pull_request, dispatch_workflow.

Хост-инструмент: `await_build {run_id|branch+sha, timeout_s≤60}` — ждёт завершения запуска, при успехе
выполняет `verify_build`, при провале запрашивает диагностику, классифицирует и возвращает решение
`RetryPolicy` (FIX_SOURCE / RETRY_AFTER / WAIT_EXTERNAL / FAIL / NEED_LOG_EXCERPT) со счётчиками бюджета.
`dispatch_workflow` отклоняется, когда исчерпан бюджет запусков (8 на диалог).

Общие правила: неизвестное имя → отказ; аргументы валидируются; записи не повторяются автоматически
после неоднозначной ошибки; результат содержит только данные хоста.
Не реализованы как инструменты: создание релиза, листинг релизов, скачивание артефакта.
