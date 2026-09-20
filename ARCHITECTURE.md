# Архитектура GitHubble

Текущая линия: `1.0.0-rc4.2`.

GitHubble — нативное Android-приложение с Java-ядром и отдельными feature-контроллерами вокруг центрального UI слоя. Основная рабочая реализация находится в `app/src/main`.

## Основные компоненты

- `MainActivity.java` — основная оболочка и переходы между feature-экранами;\n- `ui/BottomNavigationBar.java` — отдельный единый набор пяти корневых вкладок и обработчиков переходов;\n- `ui/HomeScreen.java` / `ui/RepositoriesScreen.java` — нативные карточки реальных репозиториев, поиск, фильтры и порционная отрисовка;\n- `build/BuildRunFilter.java` — типизированные фильтры статусов запусков GitHub Actions;
- `GitHubApiClient.java` / `GitHubGraphQlClient.java` — централизованный доступ к GitHub REST/GraphQL;
- `OAuthManager.java` + `SecureStore.java` — GitHub Device Flow и Keystore-backed хранение токена;
- `RepositoryController.java` — чтение и базовые операции с репозиториями;
- `RepositoryStore.java` — единый account-scoped snapshot репозиториев для Home и списка;
- `RepositoryAdminController.java` — настройки, видимость, создание и удаление репозиториев;
- `RepositoryImportController.java` — импорт папок, ZIP и одиночных файлов через Git blobs/trees/commits;
- `RepositoryPathPolicy.java` — нормализация путей и защита импорта;
- `TrackingEngine` / `TrackingDatabase` — наблюдение и локальная история;
- `AdvancedSearchPlanner` / `SmartSearchPlanner` — расширенный поиск;
- `TranslationEngine` — защищённая локализация пользовательского текста;
- `OfflineActionQueue` — account-scoped очередь с TTL, idempotency key и защитой от replay другим аккаунтом;
- `CopilotAgentController` — интеграционные функции GitHub Copilot Cloud Agent.

## Принципы

- UI не должен сообщать об успешной операции до подтверждения GitHub API;
- приватные токены и ключи не хранятся в исходниках;
- OAuth-токен хранится через Android Keystore;
- импорт репозитория сохраняет структуру директорий и использует Git object API;
- опасные операции требуют явного подтверждения;
- при недоступном GitHub App permission функция должна корректно деградировать или показать реальную API-ошибку.

В RC4.1 официальный обзор Billing и долгий инвентаризационный скан выполняются отдельно: inventory — выделенный исполнитель и контролируемое по аккаунту/поколению экрана обновление UI. Уведомления о сборках агрегируются по отслеживаемому репозиторию.\n\nИсторические stage-документы удалены из `main`; актуальным описанием состояния служат `README.md`, `BUILD_STATUS.md` и `release/RELEASE_NOTES_RU.md`.
