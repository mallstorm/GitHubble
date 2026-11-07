# GitHubble

**GitHubble** — нативный Android-клиент для работы с GitHub с русскоязычным интерфейсом. Приложение рассчитано на полноценную работу с GitHub со смартфона: авторизация через официальный GitHub Device Flow, несколько аккаунтов, репозитории, код и diff, Issues / Pull Requests, Actions, Releases, поиск, профиль, локальные уведомления, офлайн-кэш и инструменты управления репозиториями.

## Текущая версия

- Version: `1.0.0-rc4.2.28`
- versionCode: `75`
- release channel: `rc4.2.28`
- applicationId: `app.githubble.mobile`
- minSdk: `28`
- targetSdk / compileSdk: `36`
- Java source/target: `17`
- JDK для сборки: `21`
- основная ветка: `main`

### Стабилизация RC4.2.27

Модернизированная версия из PR #22/#23 сохраняет Git Agent, Workspace и новые настройки.
Исправлены гонки аккаунтов в HTTP/кэше/очереди, опасные повторы записей,
блокировка очереди во время HTTP и загрузка private Release assets.
Полное удаление данных выполняет Android: приложение закроется, все локальные
AI/GitHub-профили, ключи и диалоги будут удалены; общие Downloads сохраняются.

Сборка: `./gradlew --no-daemon test assembleDebug assembleRelease lintRelease`.
Gradle Wrapper 9.5.1 с checksum включён в репозиторий. Дистрибутив ARM64.
Подробности и проверяемые ограничения: [docs/STABILIZATION_RC427.md](docs/STABILIZATION_RC427.md).

### Что входит в RC4.2.14

Текущая ветка объединяет изменения RC4.2.3–RC4.2.14:

- нативный просмотр коммита, изменённых файлов и построчного diff;
- GitHub Inbox и детали Actions внутри приложения;
- встроенный редактор UTF-8 файлов с сохранением отдельным Git-коммитом и проверкой blob SHA;
- новая навигация профиля и настроек;
- нативное редактирование поддерживаемых GitHub Profile-полей;
- голосовой поиск через системный Android Speech Recognizer с ru-RU / en-US;
- локальный аватар для каждого подключённого аккаунта;
- устойчивое поведение поиска при HTTP 401/403/422/429 без ложного сброса всей сессии;
- поиск файла по имени внутри выбранного репозитория и ветки через Git Trees API;
- переработанный Repository Actions UI;
- менеджер Actions artifacts и cache с безопасным выборочным удалением;
- независимые политики хранения для репозиториев;
- переработанный раздел Billing & Actions: квоты, storage, платежные переходы и диагностика;
- безопасное переключение между несколькими GitHub-аккаунтами с изоляцией локального состояния;
- production signing через постоянный release keystore в GitHub Secrets;
- уменьшенный срок хранения временных CI-артефактов.

## Основные возможности

- официальный **GitHub Device Flow** по одноразовому коду;
- работа с несколькими GitHub-аккаунтами и быстрое переключение между ними;
- раздельное локальное состояние, токены, кэш, настройки и аватары для каждого аккаунта;
- хранение OAuth-токенов в Keystore-backed зашифрованном хранилище;
- просмотр профиля, организаций, репозиториев, веток, тегов и Releases;
- работа с публичными и разрешёнными приватными репозиториями;
- просмотр файлов, исходного кода, Markdown и diff;
- нативная страница коммита;
- встроенный редактор текстовых файлов;
- Issues, Pull Requests, reviews и комментарии;
- GitHub Inbox / Activity;
- GitHub Actions: runs, jobs, steps, artifacts и загрузка ZIP;
- Центр сборок с периодической проверкой через Android JobScheduler;
- Repository Manager: создание, изменение, импорт и администрирование репозиториев;
- импорт Android-папки или ZIP с сохранением структуры через Git objects API;
- поиск по GitHub и поиск имени файла внутри конкретного репозитория;
- голосовой ввод поискового запроса;
- офлайн-кэш и очередь действий;
- диагностика, экспорт состояния и контроль производительности;
- AI-модели и локальные Workspace работают без входа в GitHub;
- светлая, бежевая и тёмная визуальные линии приложения.

## Мультиаккаунт

GitHubble поддерживает подключение нескольких GitHub-аккаунтов через Device Flow.

Для каждого аккаунта отдельно хранятся:

- OAuth-сессия;
- профиль и локальный аватар;
- разрешённые репозитории;
- локальные настройки;
- кэш и состояние экранов;
- настройки мониторинга Actions.

Переключение аккаунта не должно смешивать данные предыдущего профиля. Если GitHub App не установлена для выбранного аккаунта или не имеет доступа к нужному репозиторию, приложение предлагает открыть официальный экран GitHub App installations.

## Repository Sync

Экран **Мои репозитории** синхронизируется через официальный GitHub App user-access-token маршрут:

- `GET /user/installations`;
- `GET /user/installations/{installation_id}/repositories`.

Так приложение видит пересечение доступа пользователя и установки GitHubble, включая разрешённые приватные репозитории. Принудительное обновление очищает локальный API/ETag-кэш и повторяет синхронизацию.

Экран показывает установки GitHub App, public/private репозитории, режим `All repositories` / `Selected repositories` и ошибки permission.

## Repository Manager

Раздел **Repositories → repository → Management** позволяет:

- создавать публичный или приватный репозиторий;
- переименовывать репозиторий и менять описание;
- переключать видимость `Public ↔ Private` с подтверждением;
- создавать ветки, теги, forks и Releases;
- удалять репозиторий только после ввода точного `owner/repository`;
- выбирать Android-папку через Storage Access Framework;
- импортировать ZIP как дерево исходников;
- загружать одиночные файлы;
- видеть preview импорта, количество файлов, размер и прогресс.

Импорт папки и ZIP выполняется через GitHub Git objects API: blobs → tree → commit. Большие файлы не должны целиком удерживаться в оперативной памяти. Небезопасные ZIP-пути и служебные каталоги вроде `.git`, `__MACOSX`, `.DS_Store` и `Thumbs.db` блокируются или игнорируются.

Практический лимит одного импортируемого Git blob — **99 MiB**. Для крупных файлов следует использовать Git LFS или Release assets.

## Поиск

GitHubble поддерживает обычный расширенный поиск GitHub и отдельный поиск файлов по имени.

### Поиск файла по имени

**Поиск → Найти файл по имени**:

- выбор репозитория из синхронизированного списка или ввод `owner/name`;
- поиск по выбранной ветке;
- обход путей через Git Trees API с `recursive=1`;
- приоритет точного совпадения имени;
- нативное открытие результата;
- предупреждение, если GitHub вернул `truncated=true`.

Это поиск по именам и путям, а не полнотекстовый поиск содержимого.

### Голосовой поиск

Поле поиска поддерживает системное Android-распознавание речи:

- тап по микрофону — начало;
- повторный тап / STOP — завершение;
- результат помещается в редактируемое поле;
- поддерживаются ru-RU и en-US;
- звук самим GitHubble не сохраняется.

На Android 13 и младше язык можно менять вручную долгим нажатием. Работа зависит от установленной системной службы распознавания речи.

## Профиль

Профиль открывается по аватару в верхней панели или из **Настройки → Аккаунт и доступ → Личный профиль**.

Нативный редактор поддерживает поля GitHub REST `PATCH /user`:

- имя;
- bio;
- компания;
- местоположение;
- сайт;
- X/Twitter;
- публичный email;
- флаг готовности к предложениям о работе.

Логин и GitHub ID доступны только для чтения.

GitHub REST API не предоставляет загрузку аватара через `PATCH /user`, поэтому GitHubble поддерживает отдельный локальный аватар для каждого аккаунта, а изменение фотографии GitHub открывается на официальной странице GitHub.

## GitHub Actions и Центр сборок

Раздел Actions показывает:

- workflow runs;
- состояние, ветку и commit SHA;
- jobs и steps;
- artifacts;
- загрузку ZIP;
- локальную историю и уведомления.

Мониторинг без внешнего сервера выполняется через Android JobScheduler. Проверка выполняется периодически и может задерживаться Doze, энергосбережением или ограничениями MIUI.

Настоящие мгновенные push-уведомления требуют отдельной инфраструктуры: GitHub webhook, проверки `X-Hub-Signature-256`, Firebase project и защищённого серверного backend. Эти секреты нельзя встраивать в APK.

Подробнее: [BUILD_CENTER.md](BUILD_CENTER.md).

## Billing & Actions

Раздел **Настройки → GitHub Billing и Actions** разделён на три логических экрана:

1. **Квоты и использование** — тариф, доступные лимиты и usage, получаемые из доступных GitHub Billing API.
2. **Actions Storage Manager** — workflow artifacts, Actions cache, занимаемое место и политики хранения.
3. **Оплата и диагностика** — переходы на официальные страницы GitHub Billing и диагностическая информация.

Важно:

- artifacts и Actions cache учитываются отдельно;
- удаление выполняется только после явного выбора и подтверждения;
- GitHubble не хранит банковские карты и PayPal;
- изменение способа оплаты выполняется только на официальной стороне GitHub;
- приложение различает официальные данные GitHub API и локальные расчёты/инвентаризацию.

## GitHub App permissions

Для полного набора функций GitHub App должна иметь соответствующие разрешения, в частности:

- Repository Contents: **Read and write**;
- Repository Administration: **Read and write**;
- Actions: **Read** или **Read and write** для операций Actions;
- Workflows: **Read and write**, если приложение меняет `.github/workflows/*`;
- Blocking users / Interaction limits: **Read and write** для управления блокировками и ограничениями аккаунта;
- Followers: **Read and write** для изменения подписки на пользователя;
- Profile: **Write** для редактирования профиля;
- Issues / Pull Requests / Discussions — для соответствующих разделов.

Управление блокировками, ограничениями и сведениями об установках: **Настройки → Аккаунт и GitHub → Управление аккаунтом и доступом**. Новые права необходимо подтвердить на GitHub; пользовательская авторизация и права установки согласовываются отдельно.

Notifications API не поддерживает GitHub App user tokens. Экран Inbox предлагает официальный сайт GitHub.

Если permission отсутствует, приложение должно показывать реальную ошибку GitHub API или read-only режим, а не имитировать успешную операцию.

## Безопасность

- Client Secret, private key, OAuth access token и signing passwords не хранятся в репозитории;
- публичный GitHub App Client ID передаётся через `GITHUB_CLIENT_ID`;
- Device Flow завершается на официальной странице GitHub;
- OAuth-токены хранятся в Keystore-backed зашифрованном хранилище;
- состояние разных GitHub-аккаунтов изолировано;
- удаление репозитория требует подтверждения точного имени;
- изменение visibility сопровождается предупреждением;
- production signing выполняется только через GitHub Secrets и постоянный release keystore.

## Сборка

Требуемое окружение:

- JDK 21;
- Java source/target 17;
- Gradle 9.5.1;
- Android SDK platform 36;
- Android build-tools 36.0.0;
- Android NDK 28.2.13676358 (native debug stripping);
- `GITHUB_CLIENT_ID` GitHub App с включённым Device Flow.

```bash
export ANDROID_HOME=/path/to/android-sdk
export GITHUB_CLIENT_ID=<public GitHub App client id>
./gradlew --no-daemon test assembleDebug assembleRelease lintRelease
```

### GitHub Actions

Обычный fast path:

- unit tests;
- Debug compile;
- Release compile.

Для Pull Request публикуются тестовый Debug APK и ZIP точного source tree. Для `main` публикуется итоговый bundle артефактов.

Расширенная проверка запускается вручную через `workflow_dispatch → full_validation=true` и добавляет:

- release lint;
- instrumentation APK;
- Android emulator regression suite.

Production-signed Release APK создаётся только в `main` и только если настроены постоянные secrets:

- `GITHUBBLE_RELEASE_KEYSTORE_BASE64`;
- `GITHUBBLE_RELEASE_STORE_PASSWORD`;
- `GITHUBBLE_RELEASE_KEY_PASSWORD`;
- `GITHUBBLE_RELEASE_KEY_ALIAS`.

Временные PR artifacts имеют короткий срок хранения; main artifacts также хранятся ограниченное время, а релизные файлы публикуются отдельно в GitHub Releases.

## Оптимизация Release

В ветке оптимизации Release использует R8, удаление неиспользуемых ресурсов и штатный NDK stripping. Xray/VLESS сохраняется. Удалённые native debug symbols и R8 mapping сохраняются отдельно для диагностики; они не увеличивают устанавливаемый APK.

Сравнение размеров и границы проверки: [docs/R8_OPTIMIZATION.md](docs/R8_OPTIMIZATION.md).
Для существующей рабочей папки после установки NDK нужна одна чистая сборка: `./gradlew clean assembleRelease` — старый результат без stripping может оставаться up-to-date.

## Документация

Актуальные документы:

- [ARCHITECTURE.md](ARCHITECTURE.md) — архитектура;
- [BUILD_STATUS.md](BUILD_STATUS.md) — состояние сборки;
- [BUILD_CENTER.md](BUILD_CENTER.md) — Центр сборок;
- [BUILD_RELEASE_POLICY.md](BUILD_RELEASE_POLICY.md) — политика release;
- [OAUTH_SETUP.md](OAUTH_SETUP.md) — Device Flow;
- [GITHUB_APP_CLIENT.md](GITHUB_APP_CLIENT.md) — GitHub App;
- [docs/GIT_AGENT_PROJECT_RUNTIME_V1.md](docs/GIT_AGENT_PROJECT_RUNTIME_V1.md) — Project Runtime и два режима;
- [RC4.2.3_NATIVE_COMMITS.md](RC4.2.3_NATIVE_COMMITS.md) — встроенный просмотр коммитов;
- [RC4.2.4_NATIVE_FIRST.md](RC4.2.4_NATIVE_FIRST.md) — native-first изменения;
- [RC4.2.5_PROFILE_NAVIGATION.md](RC4.2.5_PROFILE_NAVIGATION.md) — профиль и навигация;
- [RC4.2.8_SEARCH_PROFILE.md](RC4.2.8_SEARCH_PROFILE.md) — голосовой поиск и аватар;
- [RC4.2.9_SEARCH_RELIABILITY.md](RC4.2.9_SEARCH_RELIABILITY.md) — надёжность поиска;
- [CONTRIBUTING.md](CONTRIBUTING.md) — правила разработки;
- [docs/GITHUB_WORKFLOW.md](docs/GITHUB_WORKFLOW.md) — ветки, проверки и выпуск.

## Разработка

Основная ветка — `main`. Для изменений рекомендуется Pull Request с прохождением Android checks.

Автоматическая branch protection зависит от возможностей текущего GitHub-плана и настроек репозитория. Если server-side ruleset недоступен, процесс review/CI должен соблюдаться вручную.

---

**Текущий эталон исходников: GitHubble `1.0.0-rc4.2.27`, versionCode `61`.**
