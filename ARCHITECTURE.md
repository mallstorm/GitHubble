# Архитектура GitHubble

Текущая линия: `1.0.0-rc3.2-repository-manager`.

GitHubble — нативное Android-приложение с Java-ядром и отдельными feature-контроллерами вокруг центрального UI слоя. Основная рабочая реализация находится в `app/src/main`.

## Основные компоненты

- `MainActivity.java` — основной UI и навигационная оболочка приложения;
- `GitHubApiClient.java` / `GitHubGraphQlClient.java` — централизованный доступ к GitHub REST/GraphQL;
- `OAuthManager.java` + `SecureStore.java` — GitHub Device Flow и Keystore-backed хранение токена;
- `RepositoryController.java` — чтение и базовые операции с репозиториями;
- `RepositoryAdminController.java` — настройки, видимость, создание и удаление репозиториев;
- `RepositoryImportController.java` — импорт папок, ZIP и одиночных файлов через Git blobs/trees/commits;
- `RepositoryPathPolicy.java` — нормализация путей и защита импорта;
- `TrackingEngine` / `TrackingDatabase` — наблюдение и локальная история;
- `AdvancedSearchPlanner` / `SmartSearchPlanner` — расширенный поиск;
- `TranslationEngine` — защищённая локализация пользовательского текста;
- `OfflineActionQueue` — очередь действий для нестабильной сети;
- `CopilotAgentController` — интеграционные функции GitHub Copilot Cloud Agent.

## Принципы

- UI не должен сообщать об успешной операции до подтверждения GitHub API;
- приватные токены и ключи не хранятся в исходниках;
- OAuth-токен хранится через Android Keystore;
- импорт репозитория сохраняет структуру директорий и использует Git object API;
- опасные операции требуют явного подтверждения;
- при недоступном GitHub App permission функция должна корректно деградировать или показать реальную API-ошибку.

Исторические stage-документы удалены из `main`; актуальным описанием состояния служат `README.md` и `IMPLEMENTATION_REPORT_RC3.2_REPOSITORY_MANAGER.md`.