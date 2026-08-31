# GitHubble

**GitHubble** — нативный Android-клиент для работы с GitHub с русскоязычным интерфейсом, безопасной авторизацией через GitHub App Device Flow, расширенным поиском, просмотром кода и diff, Issues / Pull Requests, отслеживанием репозиториев и разработчиков, офлайн-кэшем и полноценным управлением репозиториями прямо со смартфона.

## Текущая версия

- Version: `1.0.0-rc4-stabilization`
- versionCode: `37`
- applicationId: `app.githubble.mobile`
- minSdk: `28`
- targetSdk / compileSdk: `36`
- Java source/target: `17`
- стабилизационная ветка: `rc4-stabilization`

RC4 доводит линию RC3.2 до финишной стабилизации: добавляет прозрачную диагностику доступа к приватным репозиториям, квоты GitHub API / Actions, состояние Workflows/Runs и уточняет необходимые GitHub App permissions.

## Основные возможности

- авторизация GitHub через **GitHub App Device Flow** без хранения логина и пароля;
- безопасное хранение OAuth-токена с использованием Android Keystore;
- просмотр профиля, организаций, репозиториев, веток, тегов и Releases;
- работа с публичными и приватными репозиториями в пределах выданных GitHub App permissions и Repository access установки GiHubble;
- просмотр файлов, исходного кода, Markdown и diff;
- Issues, Pull Requests и Discussions;
- отслеживание репозиториев и разработчиков с локальной историей и уведомлениями;
- расширенный русскоязычный поиск по GitHub;
- защищённая локализация пользовательского контента без повреждения кода, путей, идентификаторов и технических токенов;
- офлайн-кэш и очередь действий;
- диагностика, экспорт состояния и контроль производительности;
- интеграционные компоненты для GitHub Copilot / Cloud Agent;
- светлая, бежевая и тёмная визуальные линии приложения;
- главная карточка **«Квоты и Actions»**: REST API rate limit, доступные/приватные репозитории, Actions usage, Workflows и последние Runs.

## Private repository access

GitHubble запрашивает `/user/repos?visibility=all&affiliation=owner,collaborator,organization_member`, поэтому приложение не отбрасывает приватные репозитории своим фильтром.

Для GitHub App действуют два независимых условия: аккаунт пользователя должен иметь доступ к репозиторию, и установленное приложение **GiHubble** должно иметь Repository access к этому репозиторию. Если собственный приватный репозиторий не отображается, откройте **GitHub → Settings → Applications → Installed GitHub Apps → GiHubble → Configure** и выберите **All repositories** либо добавьте нужный репозиторий в Selected repositories. Затем обновите список или выполните повторный вход.

## Квоты, Actions и Workflows

RC4 показывает на главной:

- остаток и общий лимит GitHub REST API;
- текущий план GitHub и включённую Actions-квоту, когда её можно определить;
- фактический Actions usage текущего месяца через GitHub billing usage API при наличии **Account Plan: Read**;
- количество доступных и приватных репозиториев;
- количество установок GiHubble, если GitHub разрешает перечисление installations;
- число Workflows, активных workflow и состояние последних Runs по доступным репозиториям при наличии **Repository Actions: Read**.

Если billing/Actions permission недоступен, приложение явно показывает ограниченный режим и не подставляет выдуманные значения.

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

- Account Plan: **Read** — точный Actions billing/usage;
- Repository Actions: **Read** — Workflows и Runs;
- Repository Contents: **Read and write**;
- Repository Administration: **Read and write**;
- Repository Workflows: **Read and write** только если приложение должно импортировать или изменять `.github/workflows/*`;
- Issues / Pull Requests / Discussions и другие permissions — для соответствующих разделов приложения.

Если permission недоступен, приложение не должно имитировать успешную операцию: пользователь получает реальную ошибку GitHub API или read-only режим.

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
- Android Gradle Plugin 9.2.1;
- `GITHUB_CLIENT_ID` для GitHub App Device Flow.

Репозиторий умеет автоматически использовать локальный Maven-кэш автономной Linux-среды по пути `/mnt/data/offline_env/APKForge-Offline-Dev-Linux-x86_64/workspace/APKForge/offline-maven`, если он существует.

```bash
export ANDROID_HOME=/path/to/android-sdk
export GITHUB_CLIENT_ID=<public GitHub App client id>
gradle --no-daemon test assembleRelease assembleDebugAndroidTest bundleRelease
```

## Актуальная документация

- `README.md` — основное описание приложения;
- `ARCHITECTURE.md` — текущая архитектура;
- `BUILD_STATUS.md` — статус проверенной сборки;
- `IMPLEMENTATION_REPORT_RC3.2_REPOSITORY_MANAGER.md` — Repository Manager;
- `IMPLEMENTATION_REPORT_RC4_STABILIZATION.md` — финальный стабилизационный проход;
- `OAUTH_SETUP.md` и `GITHUB_APP_CLIENT.md` — Device Flow / GitHub App;
- `COPILOT_INTEGRATION.md` — интеграция Copilot Cloud Agent;
- `release/` — release, privacy и permission документы.
