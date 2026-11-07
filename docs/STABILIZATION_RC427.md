# RC4.2.27: стабилизация модернизированного GitHubble

Основа: PR #23 b98f2e2, включает разработку PR #22. Версия 1.0.0-rc4.2.27, code 74.

## Исправления

- GitHubSession фиксирует token/scope/generation на все попытки HTTP. SecureStore и registry используют единый process-wide lock. Старый ответ не сохраняется после switch/logout, даже если in-memory cache уже сброшен. Account-bound клиенты агента сохраняются.
- Записи автоматически не повторяются при 5xx/таймаутах. Неоднозначный результат обозначается отдельно и требует проверки сервера. Read retries учитывают Retry-After; большие ожидания возвращаются caller без раннего повтора.
- Queue schema 4 добавляет claims. HTTP выполняется вне DB/queue lock; захваченные операции выполняются один раз в исходной сессии. Прерванные leases/неоднозначные записи переходят в UNKNOWN. Требуется явный retry после проверки GitHub.
- Проверки версии перед PATCH используют NetworkRequired без offline fallback. Это best-effort GET+PATCH, не атомарный серверный CAS. Автоматического обещания отсутствия всех конфликтов нет.
- UI очереди загружает только текущий аккаунт вне main thread; запрет retry/delete IN_FLIGHT, подтверждение retry/force. Список файлов/предпросмотр/коммиты отбрасывают устаревшие callbacks, пути encoded.
- Полный reset делегирован ActivityManager.clearApplicationUserData: Android удаляет все preferences/БД/секреты/файлы приложения и останавливает процессы/jobs. Общие Downloads и серверные данные сохраняются.
- Private Release assets загружаются по API ID с OAuth только на api.github.com; последующие HTTPS CDN requests не содержат OAuth. Запись потоковая, неполные файлы удаляются, размер проверяется. Android 9 требует storage permission.
- Удалён недоступный из UI и некорректный non-force reset HEAD, который оставлял recovery tag перед ошибкой. История не переписывается; для отмены изменений используется revert/PR.
- Cache ограничен числом/размером ответов; transport ограничивает размер body, отключает неявные authenticated redirects.
- Wrapper 9.5.1 с distribution checksum; переносимые scripts, версия из version.properties, mapping сохраняется при наличии. Manifest проверяет только опубликованные файлы с basename-путями.

## Проверки

GitHubApiClientTest проверяет реальные HTTP-сценарии: delayed response при switch, один POST после 503/потери ответа, fresh read без cache fallback, ETag, Retry-After, отмену bound operation и отсутствие автоматической передачи credentials redirect-адресату.

StabilizationChecks на Android проверяет SQLite+HTTP: чтение очереди при заблокированном HTTP, отмену replay при switch, UNKNOWN после 503, очистку нескольких доменов secrets. Миграция schema 3→4 additive. Полный системный reset завершает процесс, поэтому проверяется отдельно от непрерывного instrumentation runner.

## Ограничения

Workspace остаётся снимком ограниченного набора текстовых файлов, не полноценной Git БД. Не все provider OAuth/subscription endpoints проверяются настоящими аккаунтами в CI. Реальный private release/OAuth/device upgrade требуют дополнительной проверки на телефоне. Релиз сохраняет статус release candidate.
