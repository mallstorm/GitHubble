---
name: actions-maintenance
description: Кэш, артефакты и запуски GitHub Actions: проверить объём и очистить
tools: actions_cleanup, github_request
---
1. Объём: github_get /repos/{o}/{r}/actions/cache/usage, /actions/caches?per_page=100, /actions/artifacts?per_page=100. Назови размеры в МБ.
2. Предложи политику: кэш старше 7 дней, артефакты старше 14 дней, завершённые запуски старше 30 дней.
3. Очистка — actions_cleanup {repository,kind:caches|artifacts|runs,older_than_days}. Хост сам покажет количество и размер и спросит один раз.
4. Остановить или перезапустить сборку: github_request POST /repos/{o}/{r}/actions/runs/{id}/cancel | rerun | rerun-failed-jobs.
5. Итог: сколько освобождено. Не удаляй артефакты последнего успешного релиза без явной просьбы.
