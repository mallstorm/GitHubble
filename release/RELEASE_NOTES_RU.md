# GitHubble 1.0.0 RC4.0 — модернизация и стабилизация

- единый `RepositoryStore`: Home и «Мои репозитории» используют один snapshot;
- пагинация более 100 репозиториев, stale-while-revalidate и явное состояние ошибки;
- HTTP/ETag cache изолирован по аккаунту, 304 без тела больше не маскируется;
- OAuth сохраняет expiry/refresh metadata, поддерживает безопасную ротацию и reauth после 401;
- офлайн-очередь привязана к аккаунту, repository и idempotency key, имеет TTL;
- logout очищает приватный кэш и очередь текущего аккаунта;
- устаревший force reset HEAD удалён из навигации; default branch защищена;
- очистка Actions инвалидирует каждый затронутый репозиторий;
- неизвестное состояние star/follow не показывается как `false`;
- bottom navigation: Главная, Репозитории, Поиск, Активность, Профиль;
- исправлены accessibility touch targets и семантика без принудительного focus order;
- диагностический экспорт использует whitelist и не выгружает приватные payload;
- версия вынесена в `version.properties`, UI/User-Agent/artifacts читают BuildConfig;
- CI выполняет tests, lint, debug/release build и emulator regression suite;
- release без постоянного signing keystore блокируется.
