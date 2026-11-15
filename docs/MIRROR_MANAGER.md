# GitHubble Mirror Manager OneShot

Режим: одноразовый независимый Git snapshot source → destination через временный GitHub Actions workflow **в исходном репозитории**. Без Fork, без общего Mirror Controller, без GitHub App PEM, без ручного Client ID. Аккаунты источника/назначения выбираются независимо друг от друга.

## Вход

Настройки → Аккаунт и GitHub → Мастер настройки зеркалирования: краткая информация о подключённых аккаунтах и необходимых разрешениях. Репозитории → репозиторий источника → Mirror Manager: непосредственное копирование. Выбор и создание выполняются только после подтверждения.

## Требования к токенам

- Источник (GitHub App user OAuth): Contents:write, Workflows:write, Secrets:write и Actions:write; право вносить изменения в default branch. GitHub App installation должна включать исходный репозиторий.
- Назначение (другой user OAuth): право создания репозитория (для GitHub App user token необходимы соответствующие user/repository permissions), Contents:write и Workflows:write для новых `.github/workflows` в зеркале. При недостаточных полномочиях исходник не передаётся на телефон.
- Не требуется держать аккаунт mallstorm авторизованным, если переносятся, например, marwell4 → Digger401.

## Технический поток

1. Создать новый пустой независимый destination с приватностью source. Существующие репозитории не трогаем.
2. Сохранить исходный SHA default branch как snapshot baseline, создать временный `.github/workflows/githubble-oneshot-<requestId>.yml` в default branch источника (изменяет его историю одним коммитом).
3. Получить публичный ключ Actions Secrets из исходного репозитория. Зашифровать целевой OAuth token на телефоне **libsodium crypto_box_seal**, записать в `GITHUBBLE_ONESHOT_DST_<requestId>` через API. Значение никогда не попадает в workflow source, JSON inputs, логи или SharedPreferences.
4. `workflow_dispatch` запускает workflow внутри source repository. GITHUB_TOKEN предоставляет source read. Временный secret — запись в destination.
5. Runner `git clone --bare`, `git lfs fetch --all`, возвращает default branch на исходный baseline SHA перед push, чтобы технический workflow-коммит не попал в destination; проверяет что destination пустой, выполняет `git lfs push --all` и Git push heads/tags без force.
6. Сверить refs/tags. После того как GitHub подтвердил `completed`, приложение удаляет временный Secret и workflow. Удаление workflow добавляет второй технический коммит в default branch source; Git-история останется, хотя файл исчезнет.
7. Журнал сохраняется в GitHubble с requestId, состояниями и URL Actions. При проблеме с очисткой покажет предупреждение; необходим повторный запуск проверки. Нет фонового серверного гарантированного удаления Secret при удалении приложения или полном исчезновении связи.

## Ограничения

- Этот режим — **однократный снимок** (не ongoing mirroring). Для нового переноса создайте новое пустое назначение. Старый режим managed отключён.
- Создание/удаление временного workflow требуют записи в default branch source. Branch protection и repository rules могут блокировать операцию.
- Передача destination user access token через Secret требует особого доверия к source repository collaborators / GitHub Actions. Удаление Secret **не отзывает** сам токен. По возможности отдельный минимально-разрешённый токен назначения безопаснее.
- При потере соединения во время запуска Secret остаётся до пользовательской проверки и очистки; серверная автоматическая очистка не гарантируется. `sweep` удаляет только OneShot secrets старше 6 ч на источнике, но запускается только при наличии исходной сессии и явном использовании функции.
- GitHub LFS `.lfsconfig` на любой ветке/теге блокирует перенос до ручной проверки, чтобы токен не отправлялся на непроверенный LFS-host.
- Если Git data больше доступного места runner, или GitHub rules / branch restrictions / LFS quotas не позволяют push, OneShot сообщит ошибку.
- GitHub Issues/PR/Discussions/Wiki, Releases assets, Secrets/Variables, Actions logs, branch protection, collaborators не входят в Git snapshot.
- Настоящий end-to-end перенос между двумя аккаунтами ещё не подтверждён до ручного теста.