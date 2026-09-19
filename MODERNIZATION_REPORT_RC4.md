# GitHubble RC4.0 — отчёт крупного прохода модернизации

Основание: `GitHubble_RC3.9_AUDIT_ROADMAP_RU.md`, исходный коммит RC3.9
`d97665734b494585290a4430463c8165dc0b9c9a`.

## Реализовано

- GH-01/06/07/22: единый account-scoped `RepositoryStore`, пагинация, атомарный snapshot,
  stale-while-revalidate, валидация карточек и общий источник для Home/Repositories.
- GH-02/04/16: изоляция HTTP cache и offline queue по аккаунту; logout очищает приватное
  состояние активного аккаунта; старые записи без владельца не воспроизводятся.
- GH-03/15: expiry/refresh metadata OAuth, refresh rotation, reauth после 401, scoped ETag,
  Retry-After/backoff и защита 304 без сохранённого тела.
- GH-05/14/20: многопоточный I/O scheduler и generation/account guard для главного
  репозиторного потока; typed route state остаётся совместимым со старой навигацией.
- GH-10/11/12/28: корректная invalidation всех репозиториев после cleanup, удалён force
  reset HEAD из UI, optimistic SHA/recovery tag в защитном API, tri-state star/follow,
  лимит 512 MiB/5000 файлов/32 уровня и collision guard для ZIP.
- GH-13: EntityCache обновляется без destructive migration; offline queue schema v3 —
  только additive migration.
- GH-17/29/31: единый `version.properties`, BuildConfig в UI/User-Agent, Device Flow-only
  документация, удалены устаревшие инструкции и runtime-заглушка.
- GH-18/19: release без постоянного keystore блокируется; CI запускает unit, lint,
  debug/release compile и instrumentation на emulator.
- GH-24/25/26/30: репозитории вынесены в bottom navigation, увеличены подписи,
  убран принудительный focus order, diagnostics переведён на whitelist.

## Сохранённые ограничения

- Полная декомпозиция `MainActivity` и поэкранный перенос в feature-модули остаются
  архитектурной работой после стабилизации; big-bang rewrite намеренно не выполнялся.
- Billing inventory всё ещё является отдельной длительной операцией до появления
  фонового progress API; значения unknown/partial не подменяются подтверждённым нулём.
- Copilot/Agents остаётся экспериментальной функцией и требует live capability проверки
  на конкретном GitHub-аккаунте.
- Динамические сценарии A→B, >100 private repos, 401/403/429 и обновление подписи
  окончательно подтверждаются GitHub Actions и устройством владельца.

## Release gate

Версия: `1.0.0-rc4.0`, versionCode `45`. Авторитетный gate — workflow
`.github/workflows/android.yml`; подписанный APK допустим только после зелёных lint,
unit/instrumentation тестов и проверки постоянного сертификата.
