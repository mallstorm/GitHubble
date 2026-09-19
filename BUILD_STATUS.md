# Статус сборки GitHubble

Текущая версия: `1.0.0-rc4.0` (`versionCode 45`).

Проверки GitHub Actions для каждого push и pull request:

- unit tests;
- Android Lint для release;
- `assembleDebug` и `assembleRelease`;
- on-device regression suite на Android emulator API 35;
- экспорт точного source archive и SHA-256.

Подписанный APK публикуется только из `main`. Workflow требует постоянный PKCS12
keystore и пароли из GitHub Actions Secrets; временный сертификат больше не
создаётся, поэтому несовместимое обновление приложения не может быть выпущено
случайно.

Локальная среда текущего coding-прохода не содержит Gradle/Android SDK. Итоговый
compile gate является GitHub Actions workflow; его результат считается
авторитетным для релизного артефакта.
