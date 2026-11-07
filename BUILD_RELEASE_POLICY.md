# GitHubble: сборка и доставка

PR в main выполняет unit-тесты, Debug/Release compile и lintRelease через проверенный Gradle Wrapper 9.5.1. Модернизированные ветки PR #22/#23 дополнительно проходят Android 13 instrumentation и UI screenshots. APK публикуется только после успешных проверок. Это тестовый Debug ARM64 APK; release-signing secrets в PR не используются. PR artifacts хранятся 1 день.

Push в main выполняет проверки, подписывает Release APK постоянным keystore из GitHub Secrets и публикует точный source ZIP, APK и checksums напрямую в приватный GitHub prerelease. Отсутствие signing secrets явно отмечается; unsigned APK не публикуется как устанавливаемый. Source commit, версия и SHA256 соответствуют этому запуску. Checksum manifests содержат только опубликованные файлы и basename-пути.

workflow_dispatch/full_validation запускает release lint и Android emulator regression, без публикации релиза. Эмулятор использует временный x86_64 Debug; распространяемые APK — ARM64.

Не переписывать историю main и не передавать секреты в исходники/артефакты. Production signing выполняется только для push в main. При изменении релизного процесса сохранять эти условия.

GitHub Releases приватного репозитория требуют авторизации. Для доставки APK в чат скачать реальный бинарный файл и проверить подпись/SHA256. Debug signing key может меняться между запусками; production updates требуют совпадающего постоянного сертификата. Не предлагать удаление установленного приложения как способ обновления без предупреждения о потере данных.

Эмулятор, fixtures и успешная компиляция не подтверждают реальный OAuth, доступность платных AI-подписок или корректность на физическом телефоне. Такие ограничения перечисляются в release notes; непроверенная сборка не называется проверенным stable release.
