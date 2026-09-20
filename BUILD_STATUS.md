# Статус сборки GitHubble

Текущая версия: `1.0.0-rc4.1` (`versionCode 46`).

Проверки GitHub Actions для push в main, pull request в main и ручного запуска:

- unit tests;
- Android Lint для release;
- `assembleDebug` и `assembleRelease`;
- on-device regression suite на Android emulator API 35;
- экспорт точного source archive и SHA-256.

Подписанный APK публикуется только из `main`. Concurrency отменяет устаревшие запуски одной ветки, когда в неё отправлен более свежий коммит. Workflow требует постоянный PKCS12
keystore и пароли из GitHub Actions Secrets; временный сертификат больше не
создаётся, поэтому несовместимое обновление приложения не может быть выпущено
случайно.

Локальная среда текущего coding-прохода не содержит Gradle/Android SDK. Итоговый
compile gate является GitHub Actions workflow; его результат считается
авторитетным для релизного артефакта.
