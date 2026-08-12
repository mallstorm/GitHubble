# GitHubble — отчёт прохода 01–12

Завершён первый архитектурно-функциональный проход: проект, Compose UI, русская локализация, гостевой режим, REST-поиск, OAuth/PKCE-каркас, Translation Core, техническая защита и тесты.

## Ограничения alpha01
- OAuth callback и token exchange ещё не завершены.
- Реальный провайдер перевода не подключён; интерфейс готов.
- Уведомления, избранное и расширенные экраны пока навигационные точки.
- APK не собран из-за отсутствия Android SDK в среде исполнения.

## Следующий проход 13–24
Полный OAuth callback/token storage, repository details, branches/files, README renderer, translation provider, cache, settings, favorites/history, downloads, offline cache, error/rate-limit UX, release APK после успешной настройки SDK.
