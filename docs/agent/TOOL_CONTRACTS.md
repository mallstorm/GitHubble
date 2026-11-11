# Контракты инструментов

Каталог — `GitHubAgentOperations.READ_CATALOG/WRITE_CATALOG` (источник истины, поддерживается тестами моста).

Чтение: github_profile, github_usage, github_rate_limit, github_capabilities, repository_branches,
repository_read_file, repository_issues, repository_pulls, issue_details, pull_request_details,
build_artifacts, verify_build, build_run_status, list_releases.

Запись (с подтверждением по уровню): create_repository, create_branch, update_repository,
update_account_profile, create_issue, update_issue, create_pull_request, update_pull_request,
comment_issue, merge_pull_request, dispatch_workflow, create_release.

Хост-инструмент: `await_build {run_id|branch+sha, timeout_s≤60}` — ждёт завершения запуска, при успехе
выполняет `verify_build`, при провале запрашивает диагностику, классифицирует и возвращает решение
`RetryPolicy` (FIX_SOURCE / RETRY_AFTER / WAIT_EXTERNAL / FAIL / NEED_LOG_EXCERPT) со счётчиками бюджета.
`dispatch_workflow` отклоняется, когда исчерпан бюджет запусков (8 на диалог).

`dispatch_workflow`: до запроса подтверждения хост проверяет, что workflow активен и что в его файле на целевой ветке есть
триггер `workflow_dispatch` (иначе отказ с объяснением). После запуска хост находит созданный запуск (сравнение со списком
запусков до dispatch, не по времени устройства) и возвращает `run_id`; статусы `tracking`: MATCHED / AMBIGUOUS (взят самый
новый, с предупреждением) / NOT_FOUND (за ~17 с не найден; повторять запуск нельзя до проверки).

`create_release {repository,tag,name,body,target,draft:true,prerelease:false}` — по умолчанию черновик; всегда с подтверждением;
финальный (draft=false) классифицируется как MERGE_PROTECTED. Загрузка файлов в релиз не реализована.

Хост-инструмент `download_artifact {artifact_id}`: только артефакты привязанного репозитория, с подтверждением; ZIP кладётся в
Загрузки/GitHubble, APK проверяется на устройстве (`BuildDownloadReceiver`). Итог проверки приходит пользователю уведомлением и
агенту **не виден** — каталог прямо запрещает утверждать, что APK проверен.

Общие правила: неизвестное имя → отказ; аргументы валидируются; записи не повторяются автоматически
после неоднозначной ошибки; результат содержит только данные хоста.
Не реализованы как инструменты: создание релиза, листинг релизов, скачивание артефакта.
