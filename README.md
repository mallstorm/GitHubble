# GitHubble

**GitHubble** — нативный Android-клиент для работы с GitHub с русскоязычным интерфейсом, безопасной авторизацией через GitHub App Device Flow, расширенным поиском, просмотром кода и diff, Issues / Pull Requests, отслеживанием репозиториев и разработчиков, офлайн-кэшем и полноценным управлением репозиториями прямо со смартфона.

## Текущая версия

- Version: `1.0.0-rc4.0`
- versionCode: `45`
- applicationId: `app.githubble.mobile`
- minSdk: `28`
- targetSdk / compileSdk: `36`
- Java source/target: `17`
- основная ветка разработки: `main`

RC4.0 объединяет синхронизацию Home и списка репозиториев, изолирует приватные данные и очередь по аккаунту, исправляет lifecycle OAuth и усиливает release CI.

## Основные возможности

- единственный способ авторизации — официальный **GitHub Device Flow** по одноразовому коду;
- безопасное хранение OAuth-токена с использованием Android Keystore;
- просмотр профиля, организаций, репозиториев, веток, тегов и Releases;
- работа с публичными и приватными репозиториями в пределах выданных GitHub App permissions;
- просмотр файлов, исходного кода, Markdown и diff;
- Issues, Pull Requests и Discussions;
- отслеживание репозиториев и разработчиков с локальной историей и уведомлениями;
- расширенный русскоязычный поиск по GitHub;
- защищённая локализация пользовательского контента без повреждения кода, путей, идентификаторов и технических токенов;
- офлайн-кэш и очередь действий;
- диагностика, экспорт состояния и контроль производительности;
- интеграционные компоненты для GitHub Copilot / Cloud Agent;
- светлая, бежевая и тёмная визуальные линии приложения.


## Repository Sync RC3.7

Экран **Мои репозитории** синхронизируется через официальный GitHub App user-access-token маршрут:

- `GET /user/installations`;
- `GET /user/installations/{installation_id}/repositories`.

Так приложение видит именно пересечение доступа пользователя и установки GiHubble, включая разрешённые приватные репозитории. Кнопка **Обновить репозитории** очищает локальный API/ETag-кэш и делает принудительную синхронизацию.

Экран показывает число установок, public/private репозиториев, режим `All repositories` / `Selected repositories` и ошибки permission. После возврата из GitHub Settings список синхронизируется автоматически.

## Repository Manager

Раздел **Repositories → repository → Management** позволяет управлять репозиторием непосредственно из приложения:

- создавать новый публичный или приватный репозиторий;
- переименовывать репозиторий и изменять его описание;
- переключать видимость `Public ↔ Private` с подтверждением опасного действия;
- создавать ветки, теги, forks и Releases;
- удалять репозиторий только после ввода точного `owner/repository`;
- выбирать Android-папку через Storage Access Framework и загружать её с сохранением структуры каталогов;
- импортировать ZIP как набор исходников;
- загружать одиночный файл или ZIP как один файл репозитория;
- видеть preview импорта, количество файлов, размер, пример путей и прогресс операции.

Импорт папки и ZIP выполняется через GitHub Git objects API: файлы отправляются как blobs, затем формируется tree и один commit. Большие файлы не загружаются полностью в оперативную память. Небезопасные пути ZIP и служебные каталоги `.git`, `__MACOSX`, `.DS_Store`, `Thumbs.db` блокируются или игнорируются.

Практический лимит одного импортируемого Git blob — **99 MiB**. Для более крупных файлов следует использовать Git LFS или Release assets.

## GitHub App permissions

Для полного набора функций GitHub App `GiHubble` должна иметь необходимые разрешения, в частности:

- Repository Contents: **Read and write**;
- Repository Administration: **Read and write**;
- Workflows: **Read and write** только если приложение должно импортировать или изменять `.github/workflows/*`;
- Issues / Pull Requests / Discussions и другие permissions — для соответствующих разделов приложения.

Если permission недоступен, приложение не должно имитировать успешную операцию: пользователь получает реальную ошибку GitHub API или read-only режим.


## Account Center

Раздел **Настройки → GitHub Billing и Actions** показывает данные личного GitHub-аккаунта и состояние GitHub Actions:

- тариф аккаунта и включённые лимиты для GitHub Free / Pro;
- расход Actions minutes текущего месяца через Billing Usage API;
- текущий объём workflow artifacts отдельно от Actions cache: artifact quota сравнивается с 500 MB/1 GB, cache показывается отдельно с базовым лимитом 10 GB на репозиторий;
- список репозиториев, отсортированный по занимаемому месту;
- безопасную очистку: пользователь отмечает галочками конкретные artifacts/cache и подтверждает удаление;
- переход к официальной GitHub Billing & payment странице внутри защищённой Custom Tab;
- переход к GitHub App installations для выдачи GiHubble доступа к выбранным приватным репозиториям.

Платёжные реквизиты (карта/PayPal) приложение не запрашивает и не хранит: GitHub не предоставляет публичный REST API для изменения способа оплаты, поэтому платёжная форма остаётся на официальной стороне GitHub.

Принудительное удаление HEAD из обычной навигации убрано. Для отмены изменений следует создавать revert-коммит и Pull Request, сохраняя историю и правила защиты ветки.

## Безопасность

- Client Secret, private key, access token и signing passwords не хранятся в репозитории;
- публичный GitHub App Client ID передаётся при сборке через `GITHUB_CLIENT_ID`;
- Device Flow завершается на официальной странице GitHub;
- OAuth-токен хранится в Keystore-backed зашифрованном хранилище;
- удаление репозитория требует подтверждения точного имени;
- публикация приватного репозитория сопровождается предупреждением о возможной публикации секретов;
- release signing выполняется вне исходного репозитория.

## Сборка

Требуемое окружение:

- JDK 21, Java source/target 17;
- Gradle 9.5.1;
- Android SDK platform 36;
- Android build-tools 36.0.0;
- `GITHUB_CLIENT_ID` для GitHub App с включённым Device Flow.

```bash
export ANDROID_HOME=/path/to/android-sdk
export GITHUB_CLIENT_ID=<public GitHub App client id>
gradle --no-daemon assembleRelease assembleDebugAndroidTest bundleRelease
```

GitHub Actions запускает unit tests, release lint, debug/release compilation и on-device regression suite. Подписанный release создаётся только для `main` и только постоянным keystore из GitHub Secrets.

## Актуальная документация

В `main` намеренно не хранятся исторические stage/alpha отчёты и устаревшие NEXT_BLOCK-документы. Для текущего состояния используйте:

- `README.md` — основное описание приложения;
- `ARCHITECTURE.md` — текущая архитектура;
- `BUILD_STATUS.md` — статус проверенной сборки;
- `OAUTH_SETUP.md` и `GITHUB_APP_CLIENT.md` — Device Flow / GitHub App;
- `COPILOT_INTEGRATION.md` — интеграция Copilot Cloud Agent;
- `release/` — актуальные release, privacy и permission документы.
