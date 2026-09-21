# GitHubble: стабильный процесс сборки APK

Эта схема фиксирована. Не меняйте триггеры и условия публикации без отдельной задачи пользователя.

## Pull Request → тестовый APK

Каждый PR в `main` запускает unit tests, lint, debug/release compile и Android emulator regressions. Только после успешной проверки публикуется отдельный Actions-артефакт с установочным **Debug APK**, точным исходным ZIP проверенного HEAD, SHA256SUMS, SOURCE_SHA256, SOURCE_COMMIT и SIGNING_INFO. Артефакт помечен `githubble-pr<NUMBER>-...`, хранится 14 дней. Release keystore и прочие signing secrets не используются для PR.

При неудачном тесте артефакт APK не публикуется: ошибку исправляют в исходной ветке PR. Никакой необходимости сливать PR только ради получения APK нет.

## main → финальная сборка

После принятия PR merge в `main` автоматически запускает тот же CI и публикует артефакт `githubble-<VERSION>-<SHA>` с Debug APK, unsigned Release APK, точным ZIP дерева main, SHA256 и метаданными подписи. Хранение 30 дней. Если настроен постоянный release keystore в GitHub Actions Secrets, добавляется подписанный Release APK; в противном случае не называйте unsigned Release устанавливаемым.

## Доставка пользователю

Чтобы приложить APK прямо в чат: загрузить Actions-артефакт, извлечь Debug APK, проверить SHA256, затем дать пользователю реальную ссылку на извлечённый файл. PR-тестовый APK и финальный APK различать явно, указывать commit SHA.

## Ограничения

GitHub Actions не подменяет ручные UI-проверки на реальном Android-устройстве. Обычные ветки вне PR и main не публикуют installable APK.
