# GitHubble — отчёт реализации этапов 25–36

Версия проекта: `0.3.0-alpha03`  
Пакет: `app.githubble.mobile`

## Реализованные этапы

### 25. Завершение OAuth через безопасный backend
Добавлен `OAuthBackendClient`: authorization code и PKCE verifier отправляются на настраиваемый backend `/oauth/github/exchange`. Client secret в APK не хранится.

### 26. Защищённая сессия пользователя
Полученный access token сохраняется через существующий `SecureTokenStore`; при запуске выполняется загрузка профиля, при выходе токен удаляется.

### 27. Room-кэш
Добавлена база `githubble.db`, таблицы API-кэша и переводов, DAO для чтения, обновления и очистки устаревших записей.

### 28. WorkManager
Добавлены периодическая очистка кэша и фоновый загрузчик файлов с повтором после временной ошибки.

### 29. Менеджер загрузок
Release assets можно поставить в очередь. Файлы сохраняются в приватную внешнюю папку приложения `downloads`; имена очищаются от опасных символов.

### 30. Расширение GitHub API
Добавлены методы получения Issues, Pull Requests, Discussions, Releases, release assets и персональных уведомлений.

### 31. Issues
Добавлен нативный список задач с номером, состоянием, автором и количеством комментариев. PR исключаются из списка Issues.

### 32. Pull Requests
Добавлен список PR с состоянием, автором и признаком черновика.

### 33. Discussions
Добавлен экран Discussions с корректным пустым состоянием при недоступности API. Для полноценной поддержки далее необходим GitHub GraphQL API.

### 34. Releases
Добавлены карточки релизов, release notes, список assets, размер и постановка файла в очередь загрузки.

### 35. Реальный подключаемый переводчик
Добавлен OpenAI-compatible HTTP provider. Endpoint, API-ключ и модель задаются в настройках. Перед отправкой TranslationEngine защищает код, команды, URL и пути. Результаты Markdown-перевода сохраняются в Room.

### 36. Настройки и интеграция интерфейса
Добавлен экран настроек OAuth backend и переводчика, разделы репозитория, реальные уведомления и новые маршруты Navigation Compose.

## Ключевые новые файлы

- `core/auth/OAuthBackendClient.kt`
- `core/auth/AuthSettings.kt`
- `core/database/AppDatabase.kt`
- `core/work/CacheCleanupWorker.kt`
- `core/work/DownloadWorker.kt`
- `core/translation/HttpTranslationProvider.kt`
- `core/translation/TranslationSettings.kt`
- `core/model/Models.kt`
- обновлённые `GitHubApi.kt`, `MainViewModel.kt`, `Routes.kt`, `Screens.kt`, `MainActivity.kt`

## Проверки

- XML-манифест и ресурсы проверены на корректность структуры.
- Выполнена статическая проверка наличия маршрутов, моделей, DAO и WorkManager-классов.
- Добавлены unit-тесты моделей релизов и PR.
- Полная Gradle-компиляция в текущем контейнере не выполнена: Android SDK и Gradle Wrapper отсутствуют.

## Ограничения

- Для реального входа нужны GitHub Client ID и развёрнутый OAuth backend.
- Discussions лучше перевести на GraphQL API на следующем проходе.
- API-ключ переводчика пока хранится в приватных preferences; перед production-релизом его следует перенести в Android Keystore-backed хранилище.
- Фоновая загрузка пока без системного прогресс-уведомления и pause/resume.
