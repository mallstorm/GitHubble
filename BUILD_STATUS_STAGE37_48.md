# Статус сборки Stage 37–48

## Результат

- `clean assembleRelease`: успешно;
- Android SDK / compileSdk: 36;
- Build Tools: 36.0.0;
- Gradle: 9.5.1;
- package: `app.githubble.mobile`;
- versionCode: 20;
- versionName: `0.20.0-alpha-main-stage37-48`;
- minSdk: 28;
- targetSdk: 36.

## Подпись

APK подписан тем же сертификатом, что и GitHubble 0.19.0:

```text
SHA-256: bf293dafdb634c9c68786a19e70a660f65133cfb66cfcbeea7f678da6a45c619
```

## Проверки

- APK Signature Scheme v3: успешно;
- zipalign: успешно;
- ZIP integrity: успешно;
- запрещённый вызов `api.github.com/notifications`: отсутствует в исходниках;
- Client Secret, private key и access token: не включены.

Физическое тестирование интерфейса и реальных GraphQL permissions выполняется на устройстве после установки.
